'use strict'

const CRASH_CONTAINMENT_MODE = 'crash-containment'
const ROUTINE_V12_PRIMARY_BEHAVIOR_MODE = 'routine-v12-primary-behavior'
const ROUTINE_V13_GOAL_CONTINUITY_MODE = 'routine-v13-goal-continuity'
// The two native-scheduler pilots share every condition except what each mode pins here.
// Category fairness is a fact about the JVM selector a run used, so v12 stays false for
// its committed evidence and v13, which runs on the 48-per-kind selector, attests true.
const PRIMARY_BEHAVIOR_MODES = Object.freeze({
  [ROUTINE_V12_PRIMARY_BEHAVIOR_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v12', notable: 'notable-v12', pivotal: 'pivotal-v12' }),
    categoryFairSelection: false
  }),
  [ROUTINE_V13_GOAL_CONTINUITY_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v13', notable: 'notable-v13', pivotal: 'pivotal-v13' }),
    categoryFairSelection: true
  })
})
const PRIMARY_BEHAVIOR_AGENTS = ['tribe-alpha', 'tribe-beta', 'tribe-gamma']
const PRIMARY_BEHAVIOR_INITIAL_POSITIONS = {
  'tribe-alpha': [10.5, 68, 3.5],
  'tribe-beta': [1.5, 68, 1.5],
  'tribe-gamma': [-9.5, 68, -7.5]
}

function configuredMode (config) {
  return config.mode ?? CRASH_CONTAINMENT_MODE
}

function primaryBehaviorModeOf (mode) {
  return Object.hasOwn(PRIMARY_BEHAVIOR_MODES, mode) ? PRIMARY_BEHAVIOR_MODES[mode] : undefined
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
    nativeGoalReviewSchedule: structuredClone(
      config.primaryBehavior.nativeGoalReviewSchedule
    )
  }
}

module.exports = {
  CRASH_CONTAINMENT_MODE,
  PRIMARY_BEHAVIOR_AGENTS,
  PRIMARY_BEHAVIOR_INITIAL_POSITIONS,
  PRIMARY_BEHAVIOR_MODES,
  ROUTINE_V12_PRIMARY_BEHAVIOR_MODE,
  ROUTINE_V13_GOAL_CONTINUITY_MODE,
  configuredMode,
  createInterventionSchedule,
  isPrimaryBehaviorMode,
  postCrashReserveFor,
  primaryBehaviorModeOf,
  primaryBehaviorStimulusRecord,
  requestOriginFor
}
