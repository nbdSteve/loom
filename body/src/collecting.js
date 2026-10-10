'use strict'

/**
 * One explicit attempt to collect one exact dropped-item entity.
 *
 * A request may either wait where the body stands or explicitly permit one bounded
 * pathfinder route to the item's sensed position. No item is searched for and no new
 * target is chosen. Success requires Mineflayer's `playerCollect` event naming this
 * exact retained entity object and the server packet's exact pickup count.
 */

const { performance } = require('node:perf_hooks')
const { blocksApart } = require('./movement')
const { elapsedSince, offeredItem } = require('./interaction')
const { droppedStack, positionOf } = require('./sensing')

/** How near an item must already be when the request forbids approach movement. */
const PASSIVE_PICKUP_REACH_BLOCKS = 2

/** A no-movement journey for an answer settled before pathing. */
function unmoved (outcome, position, startedAt, now) {
  return {
    outcome,
    from: position,
    position,
    travelled: 0,
    elapsedMs: elapsedSince(startedAt, now)
  }
}

/**
 * Starts one bounded collection. Undefined means the body cannot state a required live
 * reading and the control layer should answer `notReady`.
 */
function collectOfferedItem (bot, movement, {
  offered,
  item: index,
  approach,
  timeoutMs,
  sprint,
  owner,
  now = () => performance.now()
}) {
  const startedAt = now()
  const from = positionOf(bot.entity?.position)
  if (from === undefined) return undefined
  const resolved = offeredItem(bot, offered, index)
  if (resolved === undefined) return undefined
  if (resolved.outcome !== undefined) {
    return {
      promise: Promise.resolve(unmoved(resolved.outcome, from, startedAt, now)),
      cancel: () => false
    }
  }
  if (!approach && blocksApart(from, resolved.position) > PASSIVE_PICKUP_REACH_BLOCKS) {
    return {
      promise: Promise.resolve(unmoved('outOfRange', from, startedAt, now)),
      cancel: () => false
    }
  }

  let settled = false
  let cancelled = false
  let timedOut = false
  let targetGone = false
  let itemChanged = false
  let collected = false
  let pickupCount
  let route
  let finish

  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => {
      timedOut = true
      movement?.halt()
      finishWithCurrentJourney()
    }, timeoutMs)

    function cleanup () {
      clearTimeout(timer)
      bot.off('playerCollect', sawCollection)
      bot.off('entityGone', sawGone)
      bot.off('entityUpdate', sawUpdate)
      bot._client?.off?.('collect', sawCollectPacket)
    }

    function answerFrom (journey, outcome) {
      if (settled) return
      settled = true
      cleanup()
      const report = {
        outcome,
        from: journey.from,
        position: journey.position,
        travelled: journey.travelled,
        elapsedMs: elapsedSince(startedAt, now)
      }
      if (outcome === 'collected') {
        report.what = resolved.stack.what
        report.count = pickupCount
      }
      resolve(report)
    }

    function currentJourney () {
      const position = positionOf(bot.entity?.position) ?? from
      return { from, position, travelled: 0 }
    }

    function selectedOutcome (journey) {
      if (collected && Number.isInteger(pickupCount) && pickupCount >= 1 && pickupCount <= 255) {
        return 'collected'
      }
      if (itemChanged) return 'itemChanged'
      if (targetGone) return 'itemGone'
      if (cancelled) return 'cancelled'
      if (timedOut) return 'timedOut'
      if (journey?.outcome === 'arrived') return 'timedOut'
      if (journey?.outcome !== undefined) return journey.outcome
      return 'libraryFailure'
    }

    function finishWithCurrentJourney () {
      if (route !== undefined) {
        Promise.resolve(route).then(
          (journey) => answerFrom(journey ?? currentJourney(), selectedOutcome(journey)),
          () => answerFrom(currentJourney(), selectedOutcome())
        )
      } else {
        const journey = currentJourney()
        answerFrom(journey, selectedOutcome(journey))
      }
    }

    function sawCollection (collector, item) {
      if (collector !== bot.entity || item !== resolved.entity) return
      if (matchesOffer()) {
        collected = true
      } else {
        itemChanged = true
      }
      movement?.halt()
      // Mineflayer emits this event from its raw packet listener. Our raw listener runs
      // later in the same EventEmitter turn and supplies the exact count before this task.
      queueMicrotask(finishWithCurrentJourney)
    }

    function matchesOffer () {
      const stack = droppedStack(resolved.entity)
      return stack !== undefined &&
        stack.what === resolved.offered.what &&
        stack.count === resolved.offered.count &&
        stack.detail === resolved.offered.detail
    }

    function sawCollectPacket (packet) {
      if (packet?.collectorEntityId !== bot.entity?.id ||
          packet?.collectedEntityId !== resolved.entity.id) return
      pickupCount = packet.pickupItemCount
    }

    function sawGone (entity) {
      if (entity !== resolved.entity || collected) return
      targetGone = true
      movement?.halt()
      finishWithCurrentJourney()
    }

    function sawUpdate (entity) {
      if (entity !== resolved.entity || matchesOffer()) return
      itemChanged = true
      movement?.halt()
      finishWithCurrentJourney()
    }

    finish = () => {
      if (settled) return false
      cancelled = true
      movement?.halt()
      finishWithCurrentJourney()
      return true
    }

    bot.on('playerCollect', sawCollection)
    bot.on('entityGone', sawGone)
    bot.on('entityUpdate', sawUpdate)
    bot._client?.on?.('collect', sawCollectPacket)

    if (approach) {
      route = movement?.moveTo({
        destination: resolved.position,
        tolerance: 1,
        timeoutMs,
        sprint,
        owner
      })
      if (route === undefined) {
        cleanup()
        settled = true
        resolve(undefined)
        return
      }
      Promise.resolve(route).then(
        (journey) => {
          if (settled || journey === undefined) {
            if (!settled) answerFrom(currentJourney(), 'libraryFailure')
            return
          }
          if (collected || itemChanged || targetGone || cancelled || timedOut ||
              journey.outcome !== 'arrived') {
            answerFrom(journey, selectedOutcome(journey))
          }
          // On arrival the listeners remain until the request deadline. A server may send
          // the pickup packet in the same tick after the route's terminal position.
        },
        () => answerFrom(currentJourney(), selectedOutcome())
      )
    }
  })

  const cancel = () => finish?.() ?? false
  return { promise, cancel, abort: cancel }
}

module.exports = {
  PASSIVE_PICKUP_REACH_BLOCKS,
  collectOfferedItem
}
