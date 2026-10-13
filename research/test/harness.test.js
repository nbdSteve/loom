'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')

const {
  authenticationFailure,
  conciseJavaVersion,
  ensurePaper,
  importProviderUsage,
  keychainCredentialProvider,
  primaryBehaviorRunFailure,
  verifyNoListeners
} = require('../lib/harness.js')
const {
  openAiProofConfig,
  perceptionAuditFixture,
  primaryBehaviorConfig,
  proofConfig,
  writeJsonLines
} = require('./fixtures.js')

test('the Java tool attestation keeps the version banner and drops runtime detail', () => {
  const full =
    'openjdk version "21.0.11" 2026-04-21 LTS ' +
    'OpenJDK Runtime Environment Corretto-21.0.11.10.1 (build 21.0.11+10-LTS) ' +
    'OpenJDK 64-Bit Server VM Corretto-21.0.11.10.1 (build 21.0.11+10-LTS, mixed mode, sharing)'
  assert.equal(
    conciseJavaVersion(full),
    'openjdk version "21.0.11" 2026-04-21 LTS'
  )
  assert.ok(conciseJavaVersion('x'.repeat(300)).length <= 200)
})

test('the Keychain credential is trimmed and consumed exactly once in memory', () => {
  const calls = []
  const provider = keychainCredentialProvider('loom-openai', (service) => {
    calls.push(service)
    return '  credential-value-for-test\n'
  })
  assert.equal(provider(), 'credential-value-for-test')
  assert.deepEqual(calls, ['loom-openai'])
  assert.throws(
    () => provider(),
    /credential was requested more than once/
  )
  assert.deepEqual(calls, ['loom-openai'])
})

test('Keychain lookup failures stay bounded and never repeat lookup detail', () => {
  assert.throws(
    () => keychainCredentialProvider('bad service'),
    /Keychain service must contain/
  )
  const empty = keychainCredentialProvider('loom-openai', () => ' \n')
  assert.throws(() => empty(), /credential was empty/)

  const failed = keychainCredentialProvider('loom-openai', () => {
    throw new Error('sensitive Keychain diagnostic')
  })
  assert.throws(
    () => failed(),
    (error) => {
      assert.equal(error.message, 'the approved OpenAI Keychain credential could not be read')
      assert.doesNotMatch(error.message, /sensitive/)
      return true
    }
  )
})

test('provider authentication refusals become one provider-safe fatal failure', () => {
  const failure = authenticationFailure(
    "cognition failed: the model provider rejected Loom's credential " +
      '(HTTP 401, invalid_request_error/invalid_api_key); check OPENAI_API_KEY'
  )
  assert.equal(failure.stopReason, 'cognitionFailed')
  assert.equal(
    failure.message,
    "the model provider rejected Loom's credential (HTTP 401); check OPENAI_API_KEY"
  )
  const classified = authenticationFailure('message deliberately ignored', {
    mapping: 'providerAuthentication',
    httpStatus: 403
  })
  assert.equal(classified.stopReason, 'cognitionFailed')
  assert.equal(
    classified.message,
    "the model provider rejected Loom's credential (HTTP 403); check OPENAI_API_KEY"
  )
  assert.equal(authenticationFailure('cognition failed: the provider was busy'), null)
})

test('primary behavior stops on fatal evidence and a lost initial condition', () => {
  const config = primaryBehaviorConfig()
  const firstPerception = {
    event: 'perceived',
    agentId: 'tribe-alpha',
    perception: {
      selfState: {
        position: config.primaryBehavior.initialPositions['tribe-alpha'],
        inventorySummary: []
      }
    },
    perceptionAudit: perceptionAuditFixture()
  }
  assert.equal(primaryBehaviorRunFailure(config, [firstPerception]), null)

  const moved = structuredClone(firstPerception)
  moved.perception.selfState.position[0] += 1
  assert.match(
    primaryBehaviorRunFailure(config, [moved]).message,
    /lost a pinned initial condition/
  )
  assert.match(
    primaryBehaviorRunFailure(config, [{ event: 'died' }]).message,
    /agent died/
  )
})

test('Paper download identifies the harness and verifies bytes before caching', async () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-paper-download-'))
  const content = Buffer.from('bounded Paper fixture')
  const config = proofConfig()
  config.paper.sha256 = crypto.createHash('sha256').update(content).digest('hex')
  let request
  const previousFetch = global.fetch
  global.fetch = async (url, options) => {
    request = { url, options }
    let read = false
    return {
      ok: true,
      headers: { get: (name) => name === 'content-length' ? String(content.length) : null },
      body: {
        getReader: () => ({
          read: async () => {
            if (read) return { done: true }
            read = true
            return { done: false, value: content }
          }
        })
      }
    }
  }
  try {
    const file = await ensurePaper(repositoryRoot, config)
    assert.equal(fs.readFileSync(file, 'utf8'), content.toString('utf8'))
    assert.equal(request.url, config.paper.downloadUrl)
    assert.equal(
      request.options.headers['User-Agent'],
      'loom-experiment-harness/0.1.0 (https://github.com/nbdSteve/loom)'
    )
  } finally {
    global.fetch = previousFetch
    fs.rmSync(repositoryRoot, { recursive: true, force: true })
  }
})

test('provider usage is imported with exact pinned-price cost and request identity', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-usage-import-'))
  const file = path.join(directory, 'usage.jsonl')
  writeJsonLines(file, [{
    schemaVersion: 'loom-provider-usage/1',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110
  }])
  const events = []
  const count = importProviderUsage({
    config: openAiProofConfig(),
    file,
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  assert.equal(count, 1)
  assert.deepEqual(events, [{
    event: 'providerUsage',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110,
    costNanoUsd: 13_200
  }])
})

test('duplicate inconsistent or unknown provider usage is refused', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-usage-refusal-'))
  const file = path.join(directory, 'usage.jsonl')
  const record = {
    schemaVersion: 'loom-provider-usage/1',
    agentId: 'tribe-alpha',
    requestId: 'sense-1',
    model: 'small-model',
    promptTokens: 100,
    cachedPromptTokens: 20,
    completionTokens: 10,
    totalTokens: 110
  }
  writeJsonLines(file, [record, record])
  assert.throws(
    () => importProviderUsage({
      config: openAiProofConfig(),
      file,
      journal: { append: () => {} }
    }),
    /duplicate provider usage/
  )
  writeJsonLines(path.join(directory, 'unknown.jsonl'), [{ ...record, model: 'unpriced-model' }])
  assert.throws(
    () => importProviderUsage({
      config: openAiProofConfig(),
      file: path.join(directory, 'unknown.jsonl'),
      journal: { append: () => {} }
    }),
    /unknown request/
  )
})

test('an occupied configured port is refused before startup', async () => {
  const server = net.createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const config = proofConfig()
  config.network.minecraftPort = server.address().port
  try {
    await assert.rejects(
      verifyNoListeners(config, 'startup'),
      /already in use before startup/
    )
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
})
