'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  componentSourceHashes,
  publishArtifact,
  readmeQuestion,
  validateArtifact,
  validateProspectiveManifest,
  validateRunConfig
} = require('../lib/artifact.js')
const { SERVED_PROMPTS } = require('../lib/experiment-mode.js')
const { readJson } = require('../lib/util.js')
const {
  fixedRawRecords,
  openAiPrimaryBehaviorConfig,
  openAiProofConfig,
  goalContinuityConfig,
  openAiGoalContinuityConfig,
  primaryBehaviorConfig,
  primaryBehaviorRawRecords,
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

test('historical configurations are refused before startup and only served-prompt configurations are runnable', () => {
  const support = readJson(path.join(__dirname, '..', '..', 'contract', 'fixtures', 'capability-support.json'))
  for (const [name, entry] of Object.entries(support.researchConfigs)) {
    const errors = validateRunConfig(readJson(path.join(__dirname, '..', 'config', name)))
    if (entry.status === 'runnable') {
      assert.deepEqual(errors, [], name)
    } else {
      assert.ok(errors.some((error) => error.includes('historical')), `${name}: ${errors.join('; ')}`)
    }
  }
  const live = liveApprovalConfig()
  live.cognition.prompts = { ...SERVED_PROMPTS }
  const manifest = validateProspectiveManifest(live, SOURCE)
  assert.equal(manifest.cognition.worstCaseConfiguredSpendUsd, 4.66944)
  assert.equal(manifest.protocol.controlledBodyCrash.reservedCallsPerPeer, 1)
})

test('every feature flag must be true because no runtime route reads one', () => {
  const config = goalContinuityConfig()
  for (const flag of Object.keys(config.featureFlags)) {
    const disabled = goalContinuityConfig()
    disabled.featureFlags[flag] = false
    assert.ok(validateRunConfig(disabled).includes(`featureFlags.${flag} must be true: no runtime route can switch it off`))
  }
})

test('the routine-v16 pilot preflights under the v12 treatment plus v16 prompts and category fairness', () => {
  const config = openAiGoalContinuityConfig()
  assert.deepEqual(validateRunConfig(config), [])
  assert.deepEqual(validateRunConfig(goalContinuityConfig()), [])
  const manifest = validateProspectiveManifest(config, SOURCE)
  assert.equal(manifest.protocol.mode, 'routine-v16-grounded-walk')
  assert.equal(manifest.protocol.categoryFairSelection, true)
  assert.equal(manifest.protocol.controlledBodyCrashPlanned, false)
  assert.deepEqual(manifest.provenance.prompts, {
    routine: 'routine-v16',
    notable: 'notable-v16',
    pivotal: 'pivotal-v16'
  })
  // The v16 pilot is the v13 pilot with only its mode, prompts and identity moved.
  for (const suffix of ['', '-scripted']) {
    const read = (version) => {
      const pinned = readJson(path.join(__dirname, '..', 'config', `first-tribe-routine-${version}-agency-pilot${suffix}.json`))
      for (const key of ['experimentId', 'title', 'mode']) delete pinned[key]
      delete pinned.cognition.prompts
      return pinned
    }
    assert.deepEqual(read('v16'), read('v13'))
  }
  assert.deepEqual(manifest.provenance.models, { ordinary: 'gpt-5.4-nano', pivotal: 'gpt-5.4' })
  assert.equal(manifest.cognition.worstCaseConfiguredSpendUsd, 2.33472)
  assert.equal(manifest.cognition.maxExternalSpendUsd, 2.34)

  assert.equal(manifest.cognition.perAgentCallCeiling, 6)
  assert.equal(manifest.cognition.globalCallCeiling, 12)

  // Everything but the mode, title, prompts, fairness attestation, per-agent call share and
  // speech is the v12 pilot.
  const v12 = readJson(path.join(__dirname, '..', 'config', 'first-tribe-routine-v12-agency-pilot.json'))
  for (const drop of [v12, config]) {
    for (const key of ['experimentId', 'title', 'mode', 'configFile']) delete drop[key]
    delete drop.cognition.prompts
    delete drop.cognition.perAgentCallCeiling
    delete drop.primaryBehavior.categoryFairSelection
    // Speech became a body capability after the v12 pilot; v13 attests it and names its profile.
    delete drop.featureFlags.speech
    delete drop.primaryBehavior.profile
  }
  assert.deepEqual(config, v12)
})

test('each native-scheduler pilot refuses the other pilot prompts or fairness attestation', () => {
  const unfair = goalContinuityConfig()
  unfair.primaryBehavior.categoryFairSelection = false
  assert.ok(validateRunConfig(unfair).some((error) => error.includes('categoryFairSelection must be true')))

  const v13OldPrompts = goalContinuityConfig()
  v13OldPrompts.cognition.prompts = { routine: 'routine-v13', notable: 'notable-v13', pivotal: 'pivotal-v13' }
  assert.ok(validateRunConfig(v13OldPrompts).some((error) => error.includes('routine-v16')))
  const v13 = readJson(path.join(__dirname, '..', 'config', 'first-tribe-routine-v13-agency-pilot-scripted.json'))
  assert.ok(validateRunConfig(v13).some((error) => error.includes('mode routine-v13-goal-continuity is historical')))

  const v12 = readJson(path.join(__dirname, '..', 'config', 'first-tribe-routine-v12-agency-pilot-scripted.json'))
  assert.ok(validateRunConfig(v12).some((error) => error.includes('mode routine-v12-primary-behavior is historical')))

  const drifted = [
    (config) => { config.world.seed = '1' },
    (config) => { config.bounds.runDurationSeconds = 240 },
    (config) => { config.cognition.perAgentCallCeiling = 5 },
    (config) => { config.primaryBehavior.injectedDirectives.socialGoals = true },
    (config) => { config.primaryBehavior.startingInventories = 'kit' },
    (config) => { config.primaryBehavior.initialPositions['tribe-alpha'] = [0, 68, 0] }
  ]
  for (const drift of drifted) {
    const config = openAiGoalContinuityConfig()
    drift(config)
    assert.notDeepEqual(validateRunConfig(config), [], drift.toString())
  }
  const spend = openAiGoalContinuityConfig()
  spend.cognition.maxExternalSpendUsd = 3
  assert.notDeepEqual(validateRunConfig(spend), [])
  const model = openAiGoalContinuityConfig()
  model.cognition.models.ordinary = 'gpt-5.4'
  assert.notDeepEqual(validateRunConfig(model), [])
})

test('the scripted v16 publication carries adoption then continuity without claiming agency', () => {
  const config = goalContinuityConfig()
  const published = publishFixture({ config, raw: primaryBehaviorRawRecords(config) })
  const validation = validateArtifact(published.artifactDirectory)
  assert.deepEqual(validation.errors, [])
  assert.equal(published.manifest.protocol.mode, 'routine-v16-grounded-walk')
  assert.equal(published.manifest.protocol.categoryFairSelection, true)
  assert.equal(published.manifest.perceptionAudit.byKind.entities.jvmCutOmittedCount, 0)
  assert.equal(published.metrics.goalContinuity.byOutcome.goalAdopted, 3)
  assert.equal(published.metrics.goalContinuity.byOutcome.goalContinued, 3)
  assert.deepEqual(
    published.metrics.goalContinuity.agentsWithContinuity,
    ['tribe-alpha', 'tribe-beta', 'tribe-gamma']
  )
  const readme = fs.readFileSync(path.join(published.artifactDirectory, 'README.md'), 'utf8')
  assert.match(readme, /routine-v16 pilot/)
  assert.match(readme, /It is not a model-behavior result/)
})

test('a v13 manifest that attests no category fairness is refused at validation', () => {
  const config = goalContinuityConfig()
  const published = publishFixture({ config, raw: primaryBehaviorRawRecords(config) })
  const file = path.join(published.artifactDirectory, 'manifest.json')
  const manifest = JSON.parse(fs.readFileSync(file, 'utf8'))
  manifest.protocol.categoryFairSelection = false
  fs.writeFileSync(file, `${JSON.stringify(manifest, null, 2)}\n`)
  const validation = validateArtifact(published.artifactDirectory)
  assert.equal(validation.ok, false)
  assert.ok(
    validation.errors.some((error) => error.includes('categoryFairSelection true')),
    validation.errors.join('\n')
  )
})

test('primary behavior preflight refuses native review plans outside per-agent or global ceilings', () => {
  const perAgent = primaryBehaviorConfig()
  perAgent.cognition.perAgentCallCeiling = 1
  assert.ok(
    validateRunConfig(perAgent)
      .includes('planned native reviews exceed the per-agent cognition call ceiling')
  )

  const global = primaryBehaviorConfig()
  global.cognition.globalCallCeiling = 5
  assert.ok(
    validateRunConfig(global)
      .includes('planned native reviews exceed the global cognition call ceiling')
  )

  const workers = goalContinuityConfig()
  assert.deepEqual(validateRunConfig(workers), [])
  workers.runtime.cognitionBridge.workers = workers.cognition.globalOutstandingCeiling + 1
  assert.ok(
    validateRunConfig(workers)
      .includes('primary behavior bridge workers must not exceed the global outstanding ceiling')
  )
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

test('primary behavior publication round-trips native-only attestations and per-kind audits', () => {
  const published = publishFixture({
    config: primaryBehaviorConfig(),
    raw: primaryBehaviorRawRecords()
  })
  const validation = validateArtifact(published.artifactDirectory)
  assert.deepEqual(validation.errors, [])
  assert.equal(validation.ok, true)
  assert.equal(published.manifest.integrityChecks.noControlledBodyCrash.passed, true)
  assert.equal(published.manifest.integrityChecks.nativeSchedulersOnly.passed, true)
  assert.equal(published.manifest.integrityChecks.expectedNativeReviewsObserved.passed, true)
  assert.equal(published.manifest.integrityChecks.initialConditionsRecorded.passed, true)
  assert.equal(published.manifest.integrityChecks.perKindPerceptionAuditComplete.passed, true)
  assert.equal(published.manifest.protocol.externalCognitionStimuli.harnessThinkCommandsIssued, 0)
  assert.equal(published.manifest.protocol.externalCognitionStimuli.nativeSchedulersOnly, true)
  assert.deepEqual(
    published.manifest.perceptionAudit,
    published.metrics.perceptionAudit
  )
  assert.equal(published.manifest.perceptionAudit.byKind.blocks.jvmCutOmittedCount, 0)
  const text = fs.readFileSync(
    path.join(published.artifactDirectory, 'evidence.jsonl'),
    'utf8'
  )
  assert.match(text, /"origin":"nativeGoalReviewSchedule"/)
  assert.match(text, /"perceptionAudit":/)
  assert.doesNotMatch(text, /controlledBodyCrash|SIGKILL|loombody think/)
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
  assert.deepEqual(validateRunConfig(primaryBehaviorConfig()), [])
  assert.deepEqual(validateRunConfig(openAiPrimaryBehaviorConfig()), [])
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

test('publication renames a validated directory into place and leaves nothing partial behind', () => {
  const published = publishFixture()
  const root = path.dirname(published.artifactDirectory)
  assert.deepEqual(fs.readdirSync(root), [published.config.experimentId])
  assert.equal(validateArtifact(published.artifactDirectory).ok, true)

  const config = proofConfig()
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-artifact-partial-'))
  const body = path.join(temporary, 'body.jsonl')
  const harness = path.join(temporary, 'harness.jsonl')
  fs.writeFileSync(body, '{"not":"a body record"}\n')
  writeJsonLines(harness, [])
  const artifactRoot = path.join(temporary, 'published')
  try {
    assert.throws(() => publishArtifact({
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
    }))
    assert.deepEqual(fs.readdirSync(artifactRoot), [])
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true })
  }
})

test('two-cycle profiles pin the surveyed ring, the population and the envelope', () => {
  const { PROFILES, SCENES, placementOf } = require('../lib/profiles.js')
  const { reviewWindowBounds } = require('../lib/controlled-stop.js')
  for (const [file, profile, agents] of [
    ['tribe-two-cycle-3-scripted.json', 'two-cycle-3', 3],
    ['tribe-two-cycle-3.json', 'two-cycle-3', 3],
    ['tribe-two-cycle-10-scripted.json', 'two-cycle-10', 10],
    ['tribe-two-cycle-10.json', 'two-cycle-10', 10]
  ]) {
    const config = readJson(path.join(__dirname, '..', 'config', file))
    assert.deepEqual(validateRunConfig(config), [], file)
    assert.equal(config.primaryBehavior.profile, profile)
    assert.equal(config.agents.length, agents)
    assert.equal(config.world.seed, SCENES['s2-ring'].seed)
    assert.deepEqual(config.primaryBehavior.initialPositions, placementOf(PROFILES[profile]).positions)
    const manifest = validateProspectiveManifest(config, SOURCE)
    assert.equal(manifest.protocol.profile, profile)
    assert.match(manifest.protocol.sceneCoverRule, /dead_bush/)
    if (config.cognition.backend === 'openai') {
      assert.ok(manifest.cognition.worstCaseConfiguredSpendUsd <= PROFILES[profile].maxExternalSpendUsd)
    }
  }
  // The three-agent profile is the first three ring slots of the ten-agent one.
  assert.deepEqual(
    placementOf(PROFILES['two-cycle-3']).positions,
    Object.fromEntries(Object.entries(placementOf(PROFILES['two-cycle-10']).positions).slice(0, 3))
  )
  const drifts = [
    (config) => { config.world.seed = '76192346017' },
    (config) => { config.primaryBehavior.initialPositions['tribe-kappa'] = [10.5, 107, -6.5] },
    (config) => { config.agents.pop() },
    (config) => { config.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent = 20 },
    (config) => { config.cognition.globalCallCeiling = 401 },
    (config) => { config.bounds.simulationTickCeiling = 24_000 },
    (config) => { config.primaryBehavior.scene = 'r5-neutral' },
    (config) => { config.runtime.cognitionBridge.workers = 7 },
    (config) => { config.bounds.evidenceRecords = 400_000 },
    (config) => { config.bounds.artifactBytes *= 2 },
    (config) => { config.runtime.paperMemoryMiB = 1024 }
  ]
  for (const drift of drifts) {
    const config = readJson(path.join(__dirname, '..', 'config', 'tribe-two-cycle-10-scripted.json'))
    drift(config)
    assert.notDeepEqual(validateRunConfig(config), [], drift.toString())
  }
  // A full two-cycle window owes 20 or 21 reviews per agent and never more than 21.
  const schedule = { cadenceTicks: 2400, sliceTicks: 100, expectedReviewsPerAgent: 21 }
  assert.deepEqual(reviewWindowBounds(schedule, 10, 48_000), { minimum: 20, maximum: 21 })
  assert.deepEqual(reviewWindowBounds(schedule, 3, 48_000), { minimum: 20, maximum: 21 })
  assert.deepEqual(reviewWindowBounds(schedule, 10, 47_999), { minimum: 20, maximum: 20 })
  // A death at tick 13,000 owes the reviews of that life span alone.
  assert.deepEqual(reviewWindowBounds(schedule, 10, 13_000), { minimum: 5, maximum: 6 })
})

test('the ten-minute shakedown is the ten-agent two-cycle profile with only its pinned differences', () => {
  const { AGENT_ORDER, PROFILES, SCENES, placementOf } = require('../lib/profiles.js')
  const { reviewWindowBounds } = require('../lib/controlled-stop.js')
  const shakedown = PROFILES['shakedown-10m-10']
  const differences = {
    runDurationSeconds: 600,
    simulationTickCeiling: 12_000,
    expectedReviewsPerAgent: 6,
    callCeilings: { perAgent: 24, global: 150, outstanding: 6 },
    maxExternalSpendUsd: 29.19,
    bounds: { logBytes: 16 * 1024 * 1024, evidenceRecords: 8000, artifactBytes: 16 * 1024 * 1024 }
  }
  assert.ok(Object.isFrozen(shakedown))
  assert.deepEqual({ ...shakedown }, { ...PROFILES['two-cycle-10'], ...differences })
  assert.equal(shakedown.ticksPerSecond, undefined)
  assert.equal(shakedown.world, undefined)
  assert.equal(shakedown.scriptedOnly, undefined)

  const read = (file) => readJson(path.join(__dirname, '..', 'config', file))
  // The v13 shakedown ran r1 to r4, the v14 grounded walk r5 and the v15 one r6; all are
  // historical now, and the v16 grounded walk is the same profile under the served mode.
  for (const name of ['grounded-walk-10m-10', 'grounded-walk-v15-10m-10', 'grounded-walk-v16-10m-10']) {
    assert.deepEqual({ ...PROFILES[name] }, { ...shakedown }, name)
  }
  for (const file of ['tribe-shakedown-10m-10.json', 'tribe-shakedown-10m-10-scripted.json']) {
    for (const earlier of [file, file.replace('shakedown', 'grounded-walk'), file.replace('shakedown', 'grounded-walk-v15')]) {
      assert.ok(validateRunConfig(read(earlier)).some((error) => error.includes('historical')), earlier)
    }
    const v13 = read(file)
    const v16 = read(file.replace('shakedown', 'grounded-walk-v16'))
    for (const config of [v13, v16]) {
      for (const key of ['experimentId', 'title', 'mode']) delete config[key]
      delete config.cognition.prompts
      delete config.primaryBehavior.profile
    }
    assert.deepEqual(v16, v13, file)
  }
  const live = read('tribe-grounded-walk-v16-10m-10.json')
  const scripted = read('tribe-grounded-walk-v16-10m-10-scripted.json')
  const base = read('tribe-two-cycle-10.json')
  assert.match(live.experimentId, /^2026-10-16-openai-10-agent-10m-r[0-9]+$/)
  assert.equal(live.mode, 'routine-v16-grounded-walk')
  for (const config of [live, scripted]) {
    assert.deepEqual(validateRunConfig(config), [], config.experimentId)
    assert.equal(config.primaryBehavior.profile, 'grounded-walk-v16-10m-10')
    assert.deepEqual(config.agents, [...AGENT_ORDER])
    assert.equal(config.primaryBehavior.scene, 's2-ring')
    assert.equal(config.world.seed, SCENES['s2-ring'].seed)
    assert.deepEqual(config.primaryBehavior.initialPositions, placementOf(shakedown).positions)
    assert.deepEqual(config.primaryBehavior.initialWorldClock, { timeOfDay: 0, day: 0, isDay: true })
    assert.equal(config.primaryBehavior.startingInventories, 'empty')
    assert.equal(config.primaryBehavior.externalCognitionStimuli, 'none')
    assert.equal(config.proof, undefined)
    assert.deepEqual(config.world, base.world)
    assert.deepEqual(config.featureFlags, base.featureFlags)
    assert.deepEqual(config.cognition.prompts, { ...SERVED_PROMPTS })
    assert.equal(config.cognition.eventDrivenOnly, true)
    assert.equal(config.cognition.socialRolesInjected, false)
    assert.equal(config.runtime.paperMemoryMiB, 2048)
    assert.equal(config.bounds.startupConcurrency, 2)
    assert.equal(config.cognition.maxRequestBytes, 65_536)
    assert.equal(config.cognition.maxOutputTokens, 2048)
  }
  // Against the merged ten-agent configuration only the pinned differences move.
  const pinned = (config) => {
    const copy = structuredClone(config)
    delete copy.experimentId
    delete copy.title
    Object.assign(copy.bounds, { runDurationSeconds: 0, simulationTickCeiling: 0, logBytes: 0, artifactBytes: 0, evidenceRecords: 0 })
    Object.assign(copy.cognition, { perAgentCallCeiling: 0, globalCallCeiling: 0, globalOutstandingCeiling: 0, maxExternalSpendUsd: 0 })
    copy.primaryBehavior.profile = null
    copy.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent = 0
    return copy
  }
  assert.deepEqual(pinned(live), pinned(base))
  assert.deepEqual(pinned(scripted), pinned(read('tribe-two-cycle-10-scripted.json')))
  assert.deepEqual(live.cognition.models, { ordinary: 'gpt-5.4-nano', pivotal: 'gpt-5.4' })
  assert.equal(live.cognition.backend, 'openai')
  assert.equal(scripted.cognition.backend, 'scripted')
  const manifest = validateProspectiveManifest(live, SOURCE)
  assert.equal(manifest.protocol.profile, 'grounded-walk-v16-10m-10')
  assert.equal(manifest.cognition.worstCaseConfiguredSpendUsd, 29.184)

  const drifts = [
    (config) => { config.agents.pop() },
    (config) => { config.primaryBehavior.initialPositions['tribe-alpha'] = [12.5, 104, 0.5] },
    (config) => { config.primaryBehavior.scene = 'r5-neutral' },
    (config) => { config.world.seed = '76192346017' },
    (config) => { config.world.difficulty = 'peaceful' },
    (config) => { config.world.mobSpawning = false },
    (config) => { config.world.worldBorderDiameterBlocks = 256 },
    (config) => { config.world.viewDistanceChunks = 5 },
    (config) => { config.primaryBehavior.initialWorldClock.timeOfDay = 6000 },
    (config) => { config.primaryBehavior.startingInventories = 'kit' },
    (config) => { config.primaryBehavior.injectedDirectives.roles = true },
    (config) => { config.primaryBehavior.externalCognitionStimuli = 'operatorThink' },
    (config) => { config.bounds.runDurationSeconds = 601 },
    (config) => { config.bounds.simulationTickCeiling = 12_001 },
    (config) => { config.primaryBehavior.nativeGoalReviewSchedule.expectedReviewsPerAgent = 5 },
    (config) => { config.cognition.perAgentCallCeiling = 25 },
    (config) => { config.cognition.globalCallCeiling = 151 },
    (config) => { config.cognition.globalOutstandingCeiling = 7 },
    (config) => { config.cognition.maxExternalSpendUsd = 29.2 },
    (config) => { config.cognition.maxExternalSpendUsd = 29.18 },
    (config) => { config.cognition.models.ordinary = 'gpt-5.4' },
    (config) => { config.cognition.models.pivotal = 'gpt-5.4-nano' },
    (config) => { config.cognition.prompts.routine = 'routine-v12' },
    (config) => { config.cognition.maxOutputTokens = 4096 },
    (config) => { config.cognition.maxRequestBytes = 131_072 },
    (config) => { config.featureFlags.speech = false },
    (config) => { config.bounds.evidenceRecords = 20_000 },
    (config) => { config.bounds.logBytes *= 2 },
    (config) => { config.bounds.artifactBytes *= 2 },
    (config) => { config.runtime.paperMemoryMiB = 1024 },
    (config) => { config.primaryBehavior.profile = 'two-cycle-10' },
    (config) => { config.mode = 'routine-v13-goal-continuity' }
  ]
  for (const drift of drifts) {
    const config = read('tribe-grounded-walk-v16-10m-10.json')
    drift(config)
    assert.notDeepEqual(validateRunConfig(config), [], drift.toString())
  }
  // Review 6 is due at tick 12,000 itself; a ten-minute window ending just short owes five.
  const schedule = { cadenceTicks: 2400, sliceTicks: 100, expectedReviewsPerAgent: 6 }
  assert.deepEqual(reviewWindowBounds(schedule, 10, 12_000), { minimum: 5, maximum: 6 })
  assert.deepEqual(reviewWindowBounds(schedule, 10, 11_999), { minimum: 5, maximum: 5 })
})

test('the README question names the configured population and profile', () => {
  const read = (file) => readJson(path.join(__dirname, '..', 'config', file))
  // The pilot wording every committed pilot README carries is unchanged.
  assert.match(
    readmeQuestion(read('first-tribe-routine-v13-agency-pilot.json')),
    /^Can three independently embodied Loom agents complete the bounded routine-v13 pilot using/
  )
  // A ten-agent shakedown was once described as the three-agent pilot.
  const shakedown = readmeQuestion(read('tribe-shakedown-10m-10.json'))
  assert.match(shakedown, /^Can ten independently embodied Loom agents complete the bounded routine-v13 shakedown-10m-10 profile using/)
  assert.doesNotMatch(shakedown, /three|pilot/)
  assert.match(readmeQuestion(read('tribe-two-cycle-3.json')), /^Can three .* two-cycle-3 profile using/)
})
