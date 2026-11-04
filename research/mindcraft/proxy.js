#!/usr/bin/env node
'use strict'

// The spend-cap proxy between Mindcraft and OpenAI.
//
// Each Mindcraft profile points its model `url` at
// http://127.0.0.1:<port>/agent/<name>/v1, so every call is attributed to the
// agent that made it. The proxy forwards only `chat/completions` and
// `embeddings`, prices each answer from its own `usage` at the configured
// rates, appends one JSON line per call to the call log, and refuses with
// HTTP 429 when a call could take spend past the hard ceiling.
//
// The ceiling holds before a call is sent, not after: each call reserves its
// worst case (every request byte priced as an input token, plus
// `max_completion_tokens` output tokens, which the proxy sets when the request
// does not), and the reservation is replaced by the real cost when the answer
// arrives. So the sum of what was billed can never pass the ceiling.
//
// The credential is read from the Keychain only for the real OpenAI upstream,
// and only by this process. Any other upstream must be a loopback http URL and
// receives no credential, which is what the offline mock test uses.

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

function createProxy ({ prices, ceilingUsd, logPath, upstream = OPENAI_UPSTREAM, apiKey = null }) {
  if (upstream !== OPENAI_UPSTREAM && !loopbackHttp(upstream)) {
    throw new Error(`upstream must be ${OPENAI_UPSTREAM} or a loopback http URL, not ${upstream}`)
  }
  if (upstream === OPENAI_UPSTREAM && !apiKey) throw new Error('the OpenAI upstream needs a key')
  if (!(ceilingUsd > 0)) throw new Error('the ceiling must be a positive number of USD')
  const ceiling = Math.round(ceilingUsd * NANO_USD_PER_USD)
  const state = { spent: 0, reserved: 0, calls: 0, refused: 0 }

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
    const reserve = (Buffer.byteLength(sent) * rates.inputNanoUsdPerToken) +
      ((body.max_completion_tokens ?? 0) * (rates.outputNanoUsdPerToken ?? 0))
    if (state.spent + state.reserved + reserve > ceiling) {
      state.refused += 1
      log({ agent, endpoint, model, status: 429, refused: 'ceiling', reserveUsd: reserve / NANO_USD_PER_USD })
      return answer(res, 429, 'the run spend ceiling is reached', 'spend_ceiling')
    }
    state.reserved += reserve
    let cost = reserve
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
      // An answer with no usage is charged its whole reservation, so an unknown
      // cost can only overstate spend, never hide it.
      cost = usage === null ? (reply.ok ? reserve : 0) : costNanoUsd(rates, usage)
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
    }
  }

  return { server, state }
}

function main (argv) {
  const args = {}
  for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1]
  const upstream = args.upstream ?? OPENAI_UPSTREAM
  const prices = JSON.parse(fs.readFileSync(args.prices, 'utf8')).models
  const apiKey = upstream === OPENAI_UPSTREAM ? keychainKey(args['keychain-service'] ?? 'loom-openai') : null
  const { server, state } = createProxy({
    prices, ceilingUsd: Number(args['ceiling-usd']), logPath: args.log, upstream, apiKey
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

module.exports = { createProxy, costNanoUsd }
