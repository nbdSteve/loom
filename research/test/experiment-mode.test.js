'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  createInterventionSchedule,
  nativeOriginOf,
  primaryBehaviorStimulusRecord,
  requestOriginFor
} = require('../lib/experiment-mode.js')
const {
  ProcessSupervisor,
  waitForExit
} = require('../lib/lifecycle.js')
const { primaryBehaviorConfig } = require('./fixtures.js')

test('the primary behavior intervention schedule sends no process SIGKILL', () => {
  const config = primaryBehaviorConfig()
  const schedule = createInterventionSchedule(config, 1_000)
  const interventions = [
    ...schedule.due(1_000),
    ...schedule.due(61_000),
    ...schedule.due(181_000)
  ]

  assert.equal(interventions.some((entry) => entry.signal === 'SIGKILL'), false)
  assert.equal(schedule.controlledCrashIssued(), false)
})

test('primary behavior cleanup does not SIGKILL cooperative process groups', async () => {
  const config = primaryBehaviorConfig()
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-primary-cleanup-'))
  const events = []
  const supervisor = new ProcessSupervisor({
    logBytes: config.bounds.logBytes,
    journal: {
      append: (event, fields) => events.push({ event, ...fields })
    },
    onFailure: (failure) => assert.fail(failure.message)
  })
  const child = supervisor.start({
    name: 'cognition',
    command: process.execPath,
    args: ['-e', 'setInterval(() => {}, 1000)'],
    cwd: directory,
    env: process.env,
    logFile: path.join(directory, 'cognition.log')
  })

  try {
    await supervisor.stopAll(1000)
    assert.equal(await waitForExit(child.child, 1000), true)
    assert.equal(
      events.some(
        (event) =>
          event.event === 'processGroupSignalled' &&
          event.signal === 'SIGKILL'
      ),
      false
    )
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('the primary behavior harness issues no loombody think command', () => {
  const config = primaryBehaviorConfig()
  const schedule = createInterventionSchedule(config, 1_000)
  const interventions = [
    ...schedule.due(1_000),
    ...schedule.due(31_000),
    ...schedule.due(181_000)
  ]

  assert.deepEqual(
    interventions.filter((entry) => entry.command?.startsWith('loombody think')),
    []
  )
  assert.deepEqual(primaryBehaviorStimulusRecord(config, 0), {
    mode: 'routine-v35-shelter-goal',
    externalCognitionStimuli: 'none',
    harnessThinkCommandsIssued: 0,
    nativeSchedulersOnly: true,
    behaviorGateGeneration: 19,
    profile: 'pilot-180s',
    nativeGoalReviewSchedule: {
      cadenceTicks: 2400,
      expectedReviewsPerAgent: 2,
      followUpsPerReview: 1,
      sliceTicks: 100
    }
  })
})

test('a primary behavior request names the native source that raised it, a follow-up review its own', () => {
  const config = primaryBehaviorConfig()
  assert.equal(requestOriginFor(config, 'goalReview'), 'nativeGoalReviewSchedule')
  // The one review a settled goal step owes is native, but not the schedule's.
  assert.equal(requestOriginFor(config, 'stepSettled'), 'nativeGoalFollowUp')
  assert.equal(requestOriginFor(config, 'threatSighted'), 'nativeEventDriver')
  // The publication validator holds every forwarded request to the same rule: the third v23
  // rehearsal's follow-up reviews were refused as having no valid native origin.
  for (const trigger of ['goalReview', 'stepSettled', 'damaged', 'spoken']) {
    assert.equal(nativeOriginOf(trigger), requestOriginFor(config, trigger), trigger)
  }
})
