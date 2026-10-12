'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  componentSourceHashes,
  publishArtifact,
  validateArtifact,
  validateProspectiveManifest,
  validateRunConfig
} = require('../lib/artifact.js')
const { readJson } = require('../lib/util.js')
const {
  fixedRawRecords,
  openAiProofConfig,
  proofConfig,
  writeJsonLines
} = require('./fixtures.js')

const SOURCE = {
  commit: 'a'.repeat(40),
  repository: 'https://github.com/nbdSteve/loom.git'
}
const COMPONENTS = {
  plugin: { buildSha256: 'b'.repeat(64), sourceSha256: 'c'.repeat(64) },
  body: { buildSha256: 'd'.repeat(64), sourceSha256: 'e'.repeat(64) },
  cognition: { buildSha256: 'f'.repeat(64), sourceSha256: '1'.repeat(64) }
}
const TOOLS = {
  os: 'test-os',
  architecture: 'test-arch',
  java: 'openjdk version 21',
  node: 'v22.0.0',
  npm: '10.0.0',
  rustc: 'rustc 1.90.0',
  cargo: 'cargo 1.90.0',
  git: 'git version 2.50.0',
  lsof: 'lsof 4.91',
  gradle: 'Gradle 8.14'
}

function liveApprovalConfig () {
  return readJson(path.join(__dirname, '..', 'config', 'first-tribe-openai.json'))
}

function liveRawRecords () {
  const raw = fixedRawRecords()
  for (const record of raw.body) {
    if (record.provenance !== undefined) record.provenance.model = 'small-model'
    if (record.decision?.provenance !== undefined) {
      record.decision.provenance.model = 'small-model'
    }
  }
  for (const record of raw.harness) {
    if (record.event === 'experimentStarted') record.cognitionBackend = 'openai'
    if (record.provenance !== undefined) record.provenance.model = 'small-model'
  }
  for (const [index, forwarded] of raw.harness
    .filter((record) => record.event === 'cognitionForwarded')
    .entries()) {
    raw.harness.push({
      observedAt: `2026-10-12T00:00:${String(index + 4).padStart(2, '0')}.500Z`,
      event: 'providerUsage',
      agentId: forwarded.agentId,
      requestId: forwarded.requestId,
      model: 'small-model',
      promptTokens: 100,
      cachedPromptTokens: 20,
      completionTokens: 10,
      totalTokens: 110,
      costNanoUsd: 13_200
    })
  }
  return raw
}

function publishFixture ({
  config = proofConfig(),
  raw = fixedRawRecords()
} = {}) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-artifact-test-'))
  const body = path.join(temporary, 'body.jsonl')
  const harness = path.join(temporary, 'harness.jsonl')
  writeJsonLines(body, raw.body)
  writeJsonLines(harness, raw.harness)
  const artifactRoot = path.join(temporary, 'nested', 'published')
  const result = publishArtifact({
    config,
    source: SOURCE,
    components: COMPONENTS,
    tools: TOOLS,
    startedAt: '2026-10-12T00:00:00.000Z',
    endedAt: '2026-10-12T00:00:28.000Z',
    stopReason: 'durationElapsed',
    rawBodyEvidence: body,
    rawHarnessEvidence: harness,
    artifactDirectory: path.join(artifactRoot, config.experimentId)
  })
  return {
    ...result,
    config,
    artifactDirectory: path.join(artifactRoot, config.experimentId)
  }
}

test('a prospective generation-3 manifest is validated before launch', () => {
  const config = proofConfig()
  const manifest = validateProspectiveManifest(config, SOURCE)
  assert.equal(manifest.schemaVersion, 'loom-experiment-manifest/3')
  assert.equal(manifest.agents.length, 3)
  assert.equal(manifest.publication.manifestValidatedBeforeLaunch, true)
})

test('the committed live template preflights at its bounded spend envelope', () => {
  const config = liveApprovalConfig()
  assert.deepEqual(validateRunConfig(config), [])
  const manifest = validateProspectiveManifest(config, SOURCE)
  assert.equal(manifest.provenance.backend, 'openai')
  assert.deepEqual(manifest.provenance.models, {
    ordinary: 'gpt-5.4-nano',
    pivotal: 'gpt-5.4'
  })
  assert.equal(manifest.agents.length, 3)
  assert.equal(manifest.bounds.runDurationSeconds, 180)
  assert.equal(manifest.bounds.simulationTickCeiling, 4000)
  assert.equal(manifest.cognition.globalCallCeiling, 24)
  assert.equal(manifest.protocol.controlledBodyCrash.reservedCallsPerPeer, 1)
  assert.equal(manifest.cognition.worstCaseConfiguredSpendUsd, 4.66944)
  assert.equal(manifest.cognition.maxExternalSpendUsd, 4.67)
})

test('preflight refuses unsafe public fields before launch', () => {
  const config = proofConfig()
  config.title = 'accidentally copied sk-abcdefghijklmnopqrstuvwxyz'
  assert.throws(
    () => validateProspectiveManifest(config, SOURCE),
    /unsafe public material.*OpenAI-style API key/s
  )
})

test('a publication is self-validating and every metric re-derives from normalized evidence', () => {
  const published = publishFixture()
  const validation = validateArtifact(published.artifactDirectory)
  assert.deepEqual(validation.errors, [])
  assert.equal(validation.ok, true)
  assert.equal(published.manifest.rawEvidence.sha256, published.manifest.metrics.derivedFrom)
  assert.equal(published.metrics.crashContainment.cleanupVerified, true)
  assert.equal(published.manifest.integrityChecks.crashContained.passed, true)
  assert.equal(published.manifest.cognition.actualExternalSpendUsd, 0)
  assert.equal(published.manifest.cognition.worstCaseConfiguredSpendUsd, 0)
  assert.equal(published.manifest.eventCounts.total, published.metrics.records.body)
  assert.equal(published.manifest.integrityChecks.serverEvidenceUncontested.passed, true)
})

test('a live-model publication correlates successful decisions to pinned-price usage', () => {
  const published = publishFixture({
    config: openAiProofConfig(),
    raw: liveRawRecords()
  })
  const validation = validateArtifact(published.artifactDirectory)
  assert.deepEqual(validation.errors, [])
  assert.equal(published.manifest.provenance.backend, 'openai')
  assert.equal(published.manifest.cognition.actualExternalSpendUsd, 0.000066)
  assert.equal(published.metrics.cognition.providerUsageCalls, 5)
  assert.equal(published.metrics.cognition.usageByModel['small-model'].totalTokens, 550)
  assert.ok(published.manifest.cognition.worstCaseConfiguredSpendUsd <= 0.25)
  const readme = fs.readFileSync(path.join(published.artifactDirectory, 'README.md'), 'utf8')
  assert.match(readme, /This live-model run observes independently routed agents/)
  assert.doesNotMatch(readme, /Scripted decisions are acceptance traffic/)
})

test('partial malformed secret-bearing and inconsistent artifacts are refused', () => {
  {
    const published = publishFixture()
    fs.writeFileSync(path.join(published.artifactDirectory, 'manifest.json'), '{"schemaVersion":')
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('Unexpected end')))
  }
  {
    const published = publishFixture()
    const manifestFile = path.join(published.artifactDirectory, 'manifest.json')
    const manifest = readJson(manifestFile)
    delete manifest.agents
    fs.writeFileSync(manifestFile, JSON.stringify(manifest))
    assert.equal(validateArtifact(published.artifactDirectory).ok, false)
  }
  {
    const published = publishFixture()
    fs.appendFileSync(path.join(published.artifactDirectory, 'README.md'), '\nsk-abcdefghijklmnopqrstuvwxyz\n')
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('OpenAI-style API key')))
  }
  {
    const published = publishFixture()
    const manifestFile = path.join(published.artifactDirectory, 'manifest.json')
    const manifest = readJson(manifestFile)
    manifest.agents[0].username = 'loom_0000000000'
    fs.writeFileSync(manifestFile, JSON.stringify(manifest))
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('deterministic offline identity')))
  }
  {
    const published = publishFixture()
    const metricsFile = path.join(published.artifactDirectory, 'metrics.json')
    const metrics = readJson(metricsFile)
    metrics.cycles.decided += 1
    fs.writeFileSync(metricsFile, JSON.stringify(metrics))
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) =>
      error.includes('metrics SHA-256') || error.includes('deterministic derivation')
    ))
  }
  {
    const published = publishFixture()
    const manifestFile = path.join(published.artifactDirectory, 'manifest.json')
    const manifest = readJson(manifestFile)
    manifest.cognition.actualExternalSpendUsd = manifest.cognition.maxExternalSpendUsd + 1
    fs.writeFileSync(manifestFile, JSON.stringify(manifest))
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('external spend')))
  }
  {
    const published = publishFixture({
      config: openAiProofConfig(),
      raw: liveRawRecords()
    })
    const manifestFile = path.join(published.artifactDirectory, 'manifest.json')
    const manifest = readJson(manifestFile)
    manifest.provenance.models.ordinary = 'large-model'
    fs.writeFileSync(manifestFile, JSON.stringify(manifest))
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('configured model')))
  }
  {
    const published = publishFixture({
      config: openAiProofConfig(),
      raw: liveRawRecords()
    })
    const evidenceFile = path.join(published.artifactDirectory, 'evidence.jsonl')
    const lines = fs.readFileSync(evidenceFile, 'utf8').trimEnd().split('\n')
    const providerIndex = lines.findIndex((line) => JSON.parse(line).event === 'providerUsage')
    lines.splice(providerIndex, 1)
    fs.writeFileSync(evidenceFile, `${lines.join('\n')}\n`)
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) =>
      error.includes('provider usage') || error.includes('evidence SHA-256')
    ))
  }
  {
    const published = publishFixture()
    fs.appendFileSync(
      path.join(published.artifactDirectory, 'README.md'),
      'x'.repeat(published.manifest.bounds.artifactBytes)
    )
    const validation = validateArtifact(published.artifactDirectory)
    assert.equal(validation.ok, false)
    assert.ok(validation.errors.some((error) => error.includes('artifact is')))
  }
})

test('run configuration rejects unsafe unbounded or inconsistent conditions', () => {
  const valid = proofConfig()
  assert.deepEqual(validateRunConfig(valid), [])
  assert.deepEqual(validateRunConfig(openAiProofConfig()), [])
  const cases = [
    { path: ['agents'], value: ['only-one'] },
    { path: ['network', 'host'], value: '0.0.0.0' },
    { path: ['network', 'cognitionProxyPort'], value: valid.network.minecraftPort },
    { path: ['world', 'worldBorderDiameterBlocks'], value: valid.world.maxWorldSizeBlocks + 1 },
    { path: ['world', 'difficulty'], value: 'story' },
    { path: ['bounds', 'startupAttempts'], value: 4 },
    { path: ['cognition', 'socialRolesInjected'], value: true },
    { path: ['cognition', 'maxExternalSpendUsd'], value: 1 },
    { path: ['proof', 'postCrashCallsPerPeer'], value: 0 },
    { path: ['proof', 'postCrashCallsPerPeer'], value: valid.cognition.perAgentCallCeiling },
    { path: ['cognition', 'providerEndpoint'], value: 'https://credential@example.com/v1' },
    { path: ['paper', 'downloadUrl'], value: 'https://token@example.com/paper.jar' },
    {
      path: ['cognition', 'pricing', 'models', 'scripted-stand-in', 'inputNanoUsdPerToken'],
      value: 1
    }
  ]
  for (const entry of cases) {
    const changed = structuredClone(valid)
    let owner = changed
    for (const step of entry.path.slice(0, -1)) owner = owner[step]
    owner[entry.path.at(-1)] = entry.value
    assert.notDeepEqual(validateRunConfig(changed), [], entry.path.join('.'))
  }
  const globallyOversubscribed = structuredClone(valid)
  globallyOversubscribed.proof.postCrashCallsPerPeer = 5
  globallyOversubscribed.cognition.globalCallCeiling = 10
  assert.ok(
    validateRunConfig(globallyOversubscribed)
      .includes('the post-crash peer reserve must fit below the global call ceiling')
  )
})

test('component source hashes use committed files and ignore generated caches', () => {
  const repositoryRoot = path.resolve(__dirname, '..', '..')
  const before = componentSourceHashes(repositoryRoot)
  const generated = fs.mkdtempSync(
    path.join(repositoryRoot, 'body', '.loom-source-hash-test-')
  )
  try {
    fs.writeFileSync(path.join(generated, 'transient.txt'), 'not source\n')
    assert.deepEqual(componentSourceHashes(repositoryRoot), before)
  } finally {
    fs.rmSync(generated, { recursive: true, force: true })
  }
})
