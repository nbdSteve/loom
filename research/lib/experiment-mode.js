'use strict'

const { GATE_GENERATION } = require('./behavior-gate.js')

const CRASH_CONTAINMENT_MODE = 'crash-containment'
const ROUTINE_V12_PRIMARY_BEHAVIOR_MODE = 'routine-v12-primary-behavior'
const ROUTINE_V13_GOAL_CONTINUITY_MODE = 'routine-v13-goal-continuity'
// v14 differs from v13 in one affordance: a walk names an observed block by ref rather than a
// coordinate the perception never gave, so the two are separate treatments.
const ROUTINE_V14_GROUNDED_WALK_MODE = 'routine-v14-grounded-walk'
// v15 keeps v14's ref-named walk and states where it ends for a block a body walks through:
// that block's own cell rather than the open air above it.
const ROUTINE_V15_GROUNDED_WALK_MODE = 'routine-v15-grounded-walk'
// The two native-scheduler pilots share every condition except what each mode pins here.
// Category fairness is a fact about the JVM selector a run used, so v12 stays false for
// its committed evidence and v13, which runs on the 48-per-kind selector, attests true.
// The prompt versions the mind serves today (`cognition/crates/cognition/src/prompt.rs`).
// A configuration pinning any other version can only fail after it has run.
const SERVED_PROMPTS = Object.freeze({ routine: 'routine-v15', notable: 'notable-v15', pivotal: 'pivotal-v15' })
// Modes kept only so artifacts committed under them still validate; no run may select one.
const HISTORICAL_PRIMARY_BEHAVIOR_MODES = Object.freeze({
  [ROUTINE_V12_PRIMARY_BEHAVIOR_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v12', notable: 'notable-v12', pivotal: 'pivotal-v12' }),
    categoryFairSelection: false,
    callCeilings: Object.freeze({ perAgent: 4, global: 12, outstanding: 3 })
  }),
  [ROUTINE_V13_GOAL_CONTINUITY_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v13', notable: 'notable-v13', pivotal: 'pivotal-v13' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V14_GROUNDED_WALK_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v14', notable: 'notable-v14', pivotal: 'pivotal-v14' }),
    categoryFairSelection: true
  })
})
const PRIMARY_BEHAVIOR_MODES = Object.freeze({
  [ROUTINE_V15_GROUNDED_WALK_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v15', notable: 'notable-v15', pivotal: 'pivotal-v15' }),
    categoryFairSelection: true
  })
})
function configuredMode (config) {
  return config.mode ?? CRASH_CONTAINMENT_MODE
}

function primaryBehaviorModeOf (mode) {
  if (Object.hasOwn(PRIMARY_BEHAVIOR_MODES, mode)) return PRIMARY_BEHAVIOR_MODES[mode]
  if (Object.hasOwn(HISTORICAL_PRIMARY_BEHAVIOR_MODES, mode)) return HISTORICAL_PRIMARY_BEHAVIOR_MODES[mode]
  return undefined
}

function isHistoricalMode (mode) {
  return Object.hasOwn(HISTORICAL_PRIMARY_BEHAVIOR_MODES, mode)
}

function isPrimaryBehaviorMode (config) {
  return primaryBehaviorModeOf(configuredMode(config)) !== undefined
}

function postCrashReserveFor (config) {
  if (isPrimaryBehaviorMode(config)) return undefined
  return {
    crashAgent: config.proof.crashAgent,
    callsPerPeer: config.proof.postCrashCallsPerPeer
  }
}

function requestOriginFor (config, trigger) {
  if (!isPrimaryBehaviorMode(config)) return undefined
  return trigger === 'goalReview'
    ? 'nativeGoalReviewSchedule'
    : 'nativeEventDriver'
}

function createInterventionSchedule (config, startedMillis) {
  if (isPrimaryBehaviorMode(config)) {
    return {
      due: () => [],
      controlledCrashIssued: () => false
    }
  }

  let nextCycle = startedMillis
  let crashed = false
  return {
    due: (now) => {
      const interventions = []
      if (!crashed && now - startedMillis >= config.proof.crashAfterSeconds * 1000) {
        crashed = true
        interventions.push({
          type: 'controlledBodyCrash',
          agentId: config.proof.crashAgent,
          signal: 'SIGKILL'
        })
        interventions.push({
          type: 'operatorGoalReview',
          agentId: config.proof.crashAgent,
          command: `loombody think ${config.proof.crashAgent}`
        })
      }
      if (now >= nextCycle) {
        for (const agentId of config.agents) {
          if (crashed && agentId === config.proof.crashAgent) continue
          interventions.push({
            type: 'operatorGoalReview',
            agentId,
            command: `loombody think ${agentId}`
          })
        }
        nextCycle = now + config.proof.cycleIntervalSeconds * 1000
      }
      return interventions
    },
    controlledCrashIssued: () => crashed
  }
}

function primaryBehaviorStimulusRecord (config, thinkCommandsIssued) {
  if (!isPrimaryBehaviorMode(config)) return undefined
  return {
    mode: configuredMode(config),
    externalCognitionStimuli: 'none',
    harnessThinkCommandsIssued: thinkCommandsIssued,
    nativeSchedulersOnly: thinkCommandsIssued === 0,
    behaviorGateGeneration: GATE_GENERATION,
    profile: config.primaryBehavior.profile,
    nativeGoalReviewSchedule: structuredClone(
      config.primaryBehavior.nativeGoalReviewSchedule
    )
  }
}

module.exports = {
  CRASH_CONTAINMENT_MODE,
  HISTORICAL_PRIMARY_BEHAVIOR_MODES,
  PRIMARY_BEHAVIOR_MODES,
  ROUTINE_V12_PRIMARY_BEHAVIOR_MODE,
  ROUTINE_V13_GOAL_CONTINUITY_MODE,
  ROUTINE_V14_GROUNDED_WALK_MODE,
  ROUTINE_V15_GROUNDED_WALK_MODE,
  SERVED_PROMPTS,
  configuredMode,
  createInterventionSchedule,
  isHistoricalMode,
  isPrimaryBehaviorMode,
  postCrashReserveFor,
  primaryBehaviorModeOf,
  primaryBehaviorStimulusRecord,
  requestOriginFor
}
