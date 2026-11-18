#!/usr/bin/env node
'use strict'

const http = require('node:http')
const fs = require('node:fs')
const { spawnSync } = require('node:child_process')

const NANO_USD_PER_USD = 1_000_000_000
const OPENAI_UPSTREAM = 'https://api.openai.com'
const DEFAULT_MAX_COMPLETION_TOKENS = 8192
const MAX_REQUEST_BYTES = 4 * 1024 * 1024
const UPSTREAM_TIMEOUT_MS = 120_000
const ROUTE = /^\/agent\/([A-Za-z0-9_]{3,16})\/v1\/(chat\/completions|embeddings)$/

function loopbackHttp (url) {
  const parsed = new URL(url)
  return parsed.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)
}

function keychainKey (service) {
  const result = spawnSync('security', ['find-generic-password', '-s', service, '-w'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 30_000
  })
  if (result.error !== undefined || result.status !== 0 || result.stdout.trim() === '') {
    throw new Error(`the OpenAI key could not be read from Keychain service ${service}`)
  }
  return result.stdout.trim()
}

function costNanoUsd (rates, usage) {
  const prompt = usage.prompt_tokens ?? 0
  const cached = usage.prompt_tokens_details?.cached_tokens ?? 0
  const completion = usage.completion_tokens ?? 0
  return ((prompt - cached) * rates.inputNanoUsdPerToken) +
    (cached * (rates.cachedInputNanoUsdPerToken ?? rates.inputNanoUsdPerToken)) +
    (completion * (rates.outputNanoUsdPerToken ?? 0))
}

function worstCaseNanoUsd (rates, sentBody, maxCompletionTokens) {
  return (Buffer.byteLength(sentBody) * rates.inputNanoUsdPerToken) +
    ((maxCompletionTokens ?? 0) * (rates.outputNanoUsdPerToken ?? 0))
}

function answeredCostNanoUsd ({ rates, usage, ok, reserve }) {
  if (usage !== null) return costNanoUsd(rates, usage)
  return ok ? reserve : 0
}

function createProxy ({ prices, ceilingUsd, logPath, upstream = OPENAI_UPSTREAM, apiKey = null, inflightPath = null }) {
  if (upstream !== OPENAI_UPSTREAM && !loopbackHttp(upstream)) {
    throw new Error(`upstream must be ${OPENAI_UPSTREAM} or a loopback http URL, not ${upstream}`)
  }
  if (upstream === OPENAI_UPSTREAM && !apiKey) throw new Error('the OpenAI upstream needs a key')
  if (!(ceilingUsd > 0)) throw new Error('the ceiling must be a positive number of USD')
  const ceiling = Math.round(ceilingUsd * NANO_USD_PER_USD)
  const state = { spent: 0, reserved: 0, calls: 0, refused: 0 }
  const inflight = {}
  const trackUnansweredCalls = (agent, delta) => {
    const entry = inflight[agent] ?? (inflight[agent] = { inFlight: 0, lastStart: null })
    entry.inFlight += delta
    if (delta > 0) entry.lastStart = new Date().toISOString()
    if (inflightPath !== null) fs.writeFileSync(inflightPath, JSON.stringify(inflight) + '\n')
  }

  const log = (entry) => fs.appendFileSync(logPath, JSON.stringify({
    t: new Date().toISOString(),
    ...entry,
    spentUsd: state.spent / NANO_USD_PER_USD,
    ceilingUsd
  }) + '\n')

  const answer = (res, status, message, type) => {
    res.writeHead(status, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: { message, type, code: type } }))
  }

  const server = http.createServer((req, res) => {
    const route = req.method === 'POST' ? ROUTE.exec(req.url) : null
    if (route === null) return answer(res, 404, 'not proxied', 'not_found')
    const [, agent, endpoint] = route
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_REQUEST_BYTES) req.destroy()
      else chunks.push(chunk)
    })
    req.on('end', () => {
      forward(agent, endpoint, Buffer.concat(chunks), res).catch((error) => {
        log({ agent, endpoint, status: 502, error: String(error.message).slice(0, 200) })
        if (!res.headersSent) answer(res, 502, 'upstream failed', 'upstream_error')
      })
    })
  })

  async function forward (agent, endpoint, raw, res) {
    let body
    try { body = JSON.parse(raw.toString('utf8')) } catch {
      return answer(res, 400, 'request is not JSON', 'invalid_request')
    }
    const model = body.model
    const rates = prices[model]
    if (rates === undefined || body.stream === true) {
      state.refused += 1
      log({ agent, endpoint, model, status: 400, refused: 'unpriced' })
      return answer(res, 400, `model ${model} is not priced here, or streaming was asked`, 'unpriced')
    }
    if (endpoint === 'chat/completions' && body.max_completion_tokens === undefined) {
      body.max_completion_tokens = DEFAULT_MAX_COMPLETION_TOKENS
    }
    const sent = JSON.stringify(body)
    const reserve = worstCaseNanoUsd(rates, sent, body.max_completion_tokens)
    if (state.spent + state.reserved + reserve > ceiling) {
      state.refused += 1
      log({ agent, endpoint, model, status: 429, refused: 'ceiling', reserveUsd: reserve / NANO_USD_PER_USD })
      return answer(res, 429, 'the run spend ceiling is reached', 'spend_ceiling')
    }
    state.reserved += reserve
    let cost = reserve
    trackUnansweredCalls(agent, 1)
    try {
      const headers = { 'content-type': 'application/json' }
      if (upstream === OPENAI_UPSTREAM) headers.authorization = `Bearer ${apiKey}`
      const reply = await fetch(`${upstream}/v1/${endpoint}`, {
        method: 'POST',
        headers,
        body: sent,
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS)
      })
      const text = await reply.text()
      let usage = null
      try { usage = JSON.parse(text).usage ?? null } catch {}
      cost = answeredCostNanoUsd({ rates, usage, ok: reply.ok, reserve })
      state.calls += 1
      state.spent += cost
      log({
        agent,
        endpoint,
        model,
        status: reply.status,
        promptTokens: usage?.prompt_tokens ?? null,
        cachedTokens: usage?.prompt_tokens_details?.cached_tokens ?? null,
        completionTokens: usage?.completion_tokens ?? null,
        costUsd: cost / NANO_USD_PER_USD
      })
      res.writeHead(reply.status, { 'content-type': reply.headers.get('content-type') ?? 'application/json' })
      res.end(text)
    } finally {
      state.reserved -= reserve
      trackUnansweredCalls(agent, -1)
    }
  }

  return { server, state, inflight }
}

function main (argv) {
  const args = {}
  for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1]
  const upstream = args.upstream ?? OPENAI_UPSTREAM
  const prices = JSON.parse(fs.readFileSync(args.prices, 'utf8')).models
  const apiKey = upstream === OPENAI_UPSTREAM ? keychainKey(args['keychain-service'] ?? 'loom-openai') : null
  const { server, state } = createProxy({
    prices,
    ceilingUsd: Number(args['ceiling-usd']),
    logPath: args.log,
    upstream,
    apiKey,
    inflightPath: args.inflight ?? null
  })
  const stop = () => {
    fs.writeFileSync(args.summary, JSON.stringify({
      ceilingUsd: Number(args['ceiling-usd']),
      spentUsd: state.spent / NANO_USD_PER_USD,
      calls: state.calls,
      refused: state.refused,
      upstream
    }, null, 2) + '\n')
    server.close()
    process.exit(0)
  }
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  server.listen(Number(args.port), '127.0.0.1', () => {
    console.log(`spend-cap proxy on 127.0.0.1:${args.port} -> ${upstream}, ceiling $${args['ceiling-usd']}`)
  })
}

if (require.main === module) main(process.argv.slice(2))

module.exports = { createProxy, costNanoUsd, worstCaseNanoUsd, answeredCostNanoUsd }
