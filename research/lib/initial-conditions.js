'use strict'

// The server's own reading of each agent at `/loombody begin`, the one tick a run's pinned
// initial conditions must hold (the JVM's `InitialConditionsWitness`). An agent's first perception
// can come a whole review stagger later, after vanilla has changed its idle body, so where these
// records exist they, and not the first perception, witness the conditions; the first perception
// then states only the world clock (r38: gamma, idle beside chickens, no longer matched its pinned
// conditions 666 ticks after begin, and the run was refused as if they had never been applied).
const INITIAL_CONDITIONS_EVENT = 'initialConditionsWitnessed'
const INITIAL_CONDITIONS_HELD = 'initialConditionsWitnessed'

/** Each agent's reading at begin, sorted by agent, or undefined when the evidence holds none. */
function initialConditionsWitnessedOf (bodyRecords) {
  const records = bodyRecords.filter((record) => record.event === INITIAL_CONDITIONS_EVENT)
  if (records.length === 0) return undefined
  return records
    .map((record) => ({
      agentId: record.agentId,
      tick: record.tick,
      outcome: record.outcome ?? null,
      position: record.initial?.position ?? null,
      inventoryStacks: record.initial?.inventoryStacks ?? null
    }))
    .sort((first, second) => first.agentId.localeCompare(second.agentId) || first.tick - second.tick)
}

/** Whether [position] is exactly [pinned], coordinate for coordinate. */
function samePosition (position, pinned) {
  return Array.isArray(position) && Array.isArray(pinned) && position.length === pinned.length &&
    position.every((coordinate, index) => coordinate === pinned[index])
}

/** Whether one reading at begin shows the agent at [pinned] holding nothing. */
function heldAtBegin (entry, pinned) {
  return entry !== undefined && entry.outcome === INITIAL_CONDITIONS_HELD &&
    samePosition(entry.position, pinned) && entry.inventoryStacks === 0
}

/** Whether every configured agent has exactly one reading at begin, and each shows its pinned conditions. */
function everyHeldAtBegin (witnessed, agents, positions) {
  if (witnessed.length !== agents.length) return false
  const byAgent = new Map(witnessed.map((entry) => [entry.agentId, entry]))
  return byAgent.size === agents.length &&
    agents.every((agentId) => heldAtBegin(byAgent.get(agentId), positions[agentId]))
}

module.exports = {
  INITIAL_CONDITIONS_EVENT,
  INITIAL_CONDITIONS_HELD,
  everyHeldAtBegin,
  heldAtBegin,
  initialConditionsWitnessedOf,
  samePosition
}
