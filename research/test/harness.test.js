'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')

const {
  conciseJavaVersion,
  importProviderUsage,
  verifyNoListeners
} = require('../lib/harness.js')
const { openAiProofConfig, proofConfig, writeJsonLines } = require('./fixtures.js')

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
