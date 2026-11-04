'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { createProxy } = require('../mindcraft/proxy.js')

const prices = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'mindcraft', 'prices.json'), 'utf8')).models

function listen (server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
}

async function harness (t, ceilingUsd, usage) {
  const seen = []
  const upstream = http.createServer((req, res) => {
    let body = ''
    req.on('data', (c) => { body += c })
    req.on('end', () => {
      seen.push({ url: req.url, authorization: req.headers.authorization, body: JSON.parse(body) })
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: '!stats' } }], usage }))
    })
  })
  const upstreamPort = await listen(upstream)
  const logPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mindcraft-proxy-')), 'calls.jsonl')
  const { server, state } = createProxy({ prices, ceilingUsd, logPath, upstream: `http://127.0.0.1:${upstreamPort}` })
  const port = await listen(server)
  t.after(() => { server.close(); upstream.close() })
  const call = (agent, body, endpoint = 'chat/completions') => fetch(`http://127.0.0.1:${port}/agent/${agent}/v1/${endpoint}`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body)
  })
  const calls = () => fs.readFileSync(logPath, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
  return { call, calls, seen, state }
}

test('a call is forwarded without a credential, priced from its usage and attributed to its agent', async (t) => {
  const usage = { prompt_tokens: 1000, completion_tokens: 20, prompt_tokens_details: { cached_tokens: 400 } }
  const { call, calls, seen } = await harness(t, 1, usage)
  const reply = await call('ada_ash', { model: 'gpt-5.4', messages: [{ role: 'user', content: 'hi' }] })
  assert.equal(reply.status, 200)
  assert.equal((await reply.json()).choices[0].message.content, '!stats')
  assert.equal(seen[0].url, '/v1/chat/completions')
  assert.equal(seen[0].authorization, undefined)
  assert.equal(seen[0].body.max_completion_tokens, 8192)
  const [logged] = calls()
  assert.equal(logged.agent, 'ada_ash')
  assert.equal(logged.model, 'gpt-5.4')
  assert.equal(logged.promptTokens, 1000)
  assert.equal(logged.cachedTokens, 400)
  assert.equal(logged.completionTokens, 20)
  // 600 * 2500 + 400 * 250 + 20 * 15000 nano-USD
  assert.equal(logged.costUsd, 0.0019)
})

test('a call whose worst case could pass the ceiling is refused with 429 and never sent', async (t) => {
  const { call, calls, seen, state } = await harness(t, 0.1, { prompt_tokens: 10, completion_tokens: 10 })
  // 8192 reserved output tokens of gpt-5.4 alone cost about $0.12, past a $0.10 ceiling.
  const reply = await call('ada_ash', { model: 'gpt-5.4', messages: [] })
  assert.equal(reply.status, 429)
  assert.equal(seen.length, 0)
  assert.equal(state.spent, 0)
  assert.equal(calls()[0].refused, 'ceiling')
  // A nano call's worst case fits, so it still goes through.
  assert.equal((await call('ada_ash', { model: 'gpt-5.4-nano', messages: [] })).status, 200)
  assert.equal(seen.length, 1)
})

test('an unpriced model, a stream, or an unknown route is refused', async (t) => {
  const { call, seen } = await harness(t, 1, {})
  assert.equal((await call('ada_ash', { model: 'gpt-9', messages: [] })).status, 400)
  assert.equal((await call('ada_ash', { model: 'gpt-5.4', stream: true, messages: [] })).status, 400)
  assert.equal((await call('ab', { model: 'gpt-5.4', messages: [] })).status, 404)
  assert.equal((await call('ada_ash', { model: 'gpt-5.4' }, 'responses')).status, 404)
  assert.equal(seen.length, 0)
})

test('the credential can only go to api.openai.com', () => {
  const logPath = path.join(os.tmpdir(), 'unused.jsonl')
  assert.throws(() => createProxy({ prices, ceilingUsd: 1, logPath, upstream: 'http://example.com' }), /upstream must be/)
  assert.throws(() => createProxy({ prices, ceilingUsd: 1, logPath, upstream: 'https://api.openai.com' }), /needs a key/)
  assert.throws(() => createProxy({ prices, ceilingUsd: 0, logPath, upstream: 'http://127.0.0.1:9' }), /positive/)
})
