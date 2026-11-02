'use strict'

/**
 * One explicit, bounded combat request against one retained living entity.
 *
 * The request chooses the exact target, the exact inventory stack, whether one fixed
 * approach is allowed, and the strike budget. This module never searches for a target,
 * changes targets, follows a moving goal, or attacks outside that one request. Every
 * strike revalidates entity object identity, life, range, the held stack, and the
 * body's health floor.
 */

const { performance } = require('node:perf_hooks')
const { blocksApart } = require('./movement')
const {
  elapsedSince,
  offeredStack,
  stackMatchesOffer
} = require('./interaction')
const { positionOf } = require('./sensing')

/** Vanilla melee interaction reach, with a small client-coordinate allowance. */
const ATTACK_REACH_BLOCKS = 3.1

/** Time between attack packets; this makes the packet budget a time budget too. */
const ATTACK_INTERVAL_MS = 625

/** Delay after the final packet for the server's entity/death update to arrive. */
const ATTACK_SETTLE_MS = 100

/** Registry entity types that can receive living-entity combat. */
const LIVING_ENTITY_TYPES = Object.freeze(new Set([
  'ambient',
  'animal',
  'hostile',
  'living',
  'mob',
  'passive',
  'player',
  'water_creature'
]))

function unmoved (outcome, position, startedAt, now) {
  return {
    outcome,
    from: position,
    position,
    travelled: 0,
    elapsedMs: elapsedSince(startedAt, now),
    attacks: 0
  }
}

function refused (outcome, position, startedAt, now) {
  return {
    promise: Promise.resolve(unmoved(outcome, position, startedAt, now)),
    cancel: () => false
  }
}

/** The exact retained entity, distinguishing disappearance from id replacement. */
function offeredTarget (bot, offers, index) {
  if (offers === undefined) return { outcome: 'senseForgotten' }
  const offered = offers[index]
  if (offered === undefined) return { outcome: 'targetUnnamed' }
  const live = bot.entities?.[offered.id]
  if (offered.dead === true || live === undefined || live === null ||
      live.isValid === false) return { outcome: 'targetGone' }
  if (live !== offered.entity) return { outcome: 'targetChanged' }
  // Mineflayer's Entity.type is already the registry category (`hostile`,
  // `animal`, `player`, ...). Entity.entityType is the numeric registry id.
  // Indexing the registry with Entity.type therefore looks up (for example)
  // `entities.hostile` and rejects every real mob even though test doubles that
  // incorrectly made `type` numeric appeared to work.
  if (!LIVING_ENTITY_TYPES.has(live.type)) return { outcome: 'targetChanged' }
  const position = positionOf(live.position)
  if (position === undefined) return undefined
  return { offered, entity: live, position }
}

/** A cancellable delay used between discrete strikes. */
function delay (ms, cancelled) {
  return new Promise((resolve) => {
    if (ms <= 0 || cancelled()) return resolve()
    const timer = setTimeout(resolve, ms)
    cancelled.install(() => {
      clearTimeout(timer)
      resolve()
    })
  })
}

/**
 * Starts one combat request. Undefined means a required live reading or primitive is
 * absent and the control layer should answer notReady without taking ownership.
 */
function attackOfferedTarget (bot, movement, {
  entityOffers,
  slots,
  entity: index,
  slot,
  attacks,
  approach,
  maxPursuitDistance,
  minHealth,
  timeoutMs,
  sprint,
  owner,
  now = () => performance.now()
}) {
  const startedAt = now()
  const from = positionOf(bot.entity?.position)
  if (from === undefined) return undefined
  const target = offeredTarget(bot, entityOffers, index)
  if (target === undefined) return undefined
  if (target.outcome !== undefined) return refused(target.outcome, from, startedAt, now)
  const weapon = offeredStack(bot, slots, slot)
  if (weapon === undefined) return undefined
  if (weapon.outcome !== undefined) return refused(weapon.outcome, from, startedAt, now)
  if (typeof bot.attack !== 'function' || typeof bot.equip !== 'function') return undefined
  if (typeof bot.health !== 'number' || !Number.isFinite(bot.health)) return undefined
  if (bot.health <= minHealth) return refused('healthBound', from, startedAt, now)
  const initialDistance = blocksApart(from, target.position)
  if ((!approach && initialDistance > ATTACK_REACH_BLOCKS) ||
      (approach && initialDistance > maxPursuitDistance)) {
    return refused('outOfRange', from, startedAt, now)
  }

  let settled = false
  let terminal
  let route
  let journey = { from, position: from, travelled: 0 }
  let attacksSent = 0
  let routeTravelled = 0
  let routeLast = from
  let routeOverBound = false
  let wakeDelay
  let abort

  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => requestTerminal('timedOut'), timeoutMs)

    function currentPosition () {
      return positionOf(bot.entity?.position) ?? journey.position ?? from
    }

    function cleanup () {
      clearTimeout(timer)
      bot.off('entityDead', sawDead)
      bot.off('entityGone', sawGone)
      bot.off('physicsTick', measuredRoute)
      bot.off('health', sawHealth)
      if (wakeDelay !== undefined) {
        wakeDelay()
        wakeDelay = undefined
      }
    }

    function finish (outcome) {
      if (settled) return
      settled = true
      cleanup()
      resolve({
        outcome,
        from: journey.from ?? from,
        position: currentPosition(),
        travelled: journey.travelled ?? routeTravelled,
        elapsedMs: elapsedSince(startedAt, now),
        attacks: attacksSent,
        ...(journey.recovery === undefined ? {} : { recovery: journey.recovery })
      })
    }

    function requestTerminal (outcome) {
      if (settled || terminal !== undefined) return false
      terminal = outcome
      try {
        movement?.halt()
      } catch {
        // The report remains bounded and the movement wrapper owns its cleanup.
      }
      if (wakeDelay !== undefined) wakeDelay()
      return true
    }

    abort = (outcome = 'cancelled') => requestTerminal(outcome)

    function sawDead (entity) {
      if (entity === target.entity) {
        requestTerminal(attacksSent > 0 ? 'targetDied' : 'targetGone')
      }
    }

    function sawGone (entity) {
      if (entity === target.entity) requestTerminal('targetGone')
    }

    function sawHealth () {
      if (Number.isFinite(bot.health) && bot.health <= minHealth) {
        requestTerminal('healthBound')
      }
    }

    function measuredRoute () {
      const here = positionOf(bot.entity?.position)
      if (here === undefined) return
      routeTravelled += blocksApart(routeLast, here)
      routeLast = here
      if (routeTravelled > maxPursuitDistance) {
        routeOverBound = true
        try {
          movement?.halt()
        } catch {
          requestTerminal('libraryFailure')
        }
      }
    }

    function targetNow () {
      return offeredTarget(bot, entityOffers, index)
    }

    function selectedRouteOutcome (report) {
      if (terminal !== undefined) return terminal
      if (routeOverBound ||
          (Number.isFinite(report?.travelled) &&
            report.travelled > maxPursuitDistance)) return 'overTravelled'
      if (report?.outcome !== 'arrived') return report?.outcome ?? 'libraryFailure'
      return undefined
    }

    async function equipWeapon () {
      if (stackMatchesOffer(bot.heldItem, weapon.offered)) return true
      await bot.equip(weapon.stack, 'hand')
      return stackMatchesOffer(bot.heldItem, weapon.offered)
    }

    async function run () {
      bot.on('entityDead', sawDead)
      bot.on('entityGone', sawGone)
      bot.on('health', sawHealth)

      if (approach && initialDistance > ATTACK_REACH_BLOCKS) {
        bot.on('physicsTick', measuredRoute)
        route = movement?.moveTo({
          destination: target.position,
          tolerance: 2,
          timeoutMs,
          sprint,
          owner,
          departs: false
        })
        if (route === undefined) return finish('libraryFailure')
        const report = await route
        bot.off('physicsTick', measuredRoute)
        if (report !== undefined) journey = report
        const routeOutcome = selectedRouteOutcome(report)
        if (routeOutcome !== undefined) return finish(routeOutcome)
      }

      if (terminal !== undefined) return finish(terminal)
      const afterRoute = targetNow()
      if (afterRoute === undefined) return finish('libraryFailure')
      if (afterRoute.outcome !== undefined) return finish(afterRoute.outcome)
      if (blocksApart(currentPosition(), afterRoute.position) > ATTACK_REACH_BLOCKS ||
          blocksApart(target.position, afterRoute.position) > maxPursuitDistance) {
        return finish('outOfRange')
      }

      const equipped = await equipWeapon()
      if (terminal !== undefined) return finish(terminal)
      if (!equipped) return finish('slotChanged')

      for (let attack = 0; attack < attacks; attack += 1) {
        if (terminal !== undefined) break
        const live = targetNow()
        if (live === undefined) return finish('libraryFailure')
        if (live.outcome !== undefined) return finish(live.outcome)
        if (blocksApart(currentPosition(), live.position) > ATTACK_REACH_BLOCKS ||
            blocksApart(target.position, live.position) > maxPursuitDistance) {
          return finish('outOfRange')
        }
        if (!Number.isFinite(bot.health)) return finish('libraryFailure')
        if (bot.health <= minHealth) return finish('healthBound')
        if (!stackMatchesOffer(bot.heldItem, weapon.offered)) return finish('slotChanged')
        attacksSent += 1
        try {
          bot.attack(live.entity, true)
        } catch (failure) {
          attacksSent -= 1
          throw failure
        }
        const wait = attack + 1 === attacks ? ATTACK_SETTLE_MS : ATTACK_INTERVAL_MS
        const cancelled = () => terminal !== undefined
        cancelled.install = (release) => { wakeDelay = release }
        await delay(wait, cancelled)
        wakeDelay = undefined
      }
      finish(terminal ?? 'attacked')
    }

    run().catch(() => finish(terminal ?? 'libraryFailure'))
  })

  return {
    promise,
    cancel: () => abort?.('cancelled') ?? false,
    abort: (outcome) => abort?.(outcome) ?? false
  }
}

module.exports = {
  ATTACK_INTERVAL_MS,
  ATTACK_REACH_BLOCKS,
  ATTACK_SETTLE_MS,
  LIVING_ENTITY_TYPES,
  attackOfferedTarget,
  offeredTarget
}
