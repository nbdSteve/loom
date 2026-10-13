'use strict'

const CRASH_CONTAINMENT_MODE = 'crash-containment'
const ROUTINE_V12_PRIMARY_BEHAVIOR_MODE = 'routine-v12-primary-behavior'
const PRIMARY_BEHAVIOR_AGENTS = ['tribe-alpha', 'tribe-beta', 'tribe-gamma']
const PRIMARY_BEHAVIOR_INITIAL_POSITIONS = {
  'tribe-alpha': [10.5, 68, 3.5],
  'tribe-beta': [1.5, 68, 1.5],
  'tribe-gamma': [-9.5, 68, -7.5]
}

function configuredMode (config) {
  return config.mode ?? CRASH_CONTAINMENT_MODE
}

function isPrimaryBehaviorMode (config) {
  return configuredMode(config) === ROUTINE_V12_PRIMARY_BEHAVIOR_MODE
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
    mode: ROUTINE_V12_PRIMARY_BEHAVIOR_MODE,
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
  ROUTINE_V12_PRIMARY_BEHAVIOR_MODE,
  configuredMode,
  createInterventionSchedule,
  isPrimaryBehaviorMode,
  postCrashReserveFor,
  primaryBehaviorStimulusRecord,
  requestOriginFor
}
