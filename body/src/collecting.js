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
const { Vec3 } = require('vec3')
const { blocksApart } = require('./movement')
const { elapsedSince, offeredItem } = require('./interaction')
const { droppedStack, positionOf } = require('./sensing')

/**
 * How near an item must already be when the request forbids approach movement: inside vanilla's
 * grown pickup box from where the body stands. Horizontally the player's half width, its growth
 * and the item's half width, 0.3 + 1 + 0.125 on each axis; vertically from the item's top 0.5
 * below the feet, 0.25 + 0.5, to 0.5 above the head, 1.8 + 0.5. A straight-line reach of two
 * blocks admitted an item one block below the feet that vanilla never picks up, and the collect
 * waited out its deadline (the v25 rehearsal, theta).
 */
const PASSIVE_PICKUP_HORIZONTAL_BLOCKS = 1.425
const PASSIVE_PICKUP_BELOW_BLOCKS = 0.75
const PASSIVE_PICKUP_ABOVE_BLOCKS = 2.3

/** A movement wrapper with no `approach`, as a test double may be, walks into the item's own cell. */
const ITEM_CELL_TOLERANCE = 0

/** Vanilla's player box: 0.6 wide and 1.8 tall, grown by 1, 0.5 and 1 to pick an item up. */
const PLAYER_HALF_WIDTH = 0.3
const PLAYER_HEIGHT = 1.8
const PICKUP_GROWTH = Object.freeze({ x: 1, y: 0.5, z: 1 })
/** A dropped item's box: 0.25 wide and 0.25 tall, centred on its position horizontally. */
const ITEM_HALF_WIDTH = 0.125
const ITEM_HEIGHT = 0.25

/**
 * Every cell a body can stand in from which vanilla would pick the item at `position` up,
 * nearest the body first: its feet and head cells free of collision, a collision box under its
 * feet, and its grown pickup box overlapping the item's box. The item's own floored cell is
 * one only when it has ground under it; r15's stick hung over a one-block trench from the edge
 * of the block beside it, so its own cell had air under it, no route to it could exist, and
 * five collects were refused for it while two cells beside it would have picked it up.
 */
function pickupCells (bot, position, from) {
  const [ix, iy, iz] = position
  const cells = []
  for (let cx = Math.floor(ix) - 1; cx <= Math.floor(ix) + 1; cx += 1) {
    for (let cy = Math.floor(iy) - 2; cy <= Math.floor(iy) + 1; cy += 1) {
      for (let cz = Math.floor(iz) - 1; cz <= Math.floor(iz) + 1; cz += 1) {
        if (covers([cx, cy, cz], position) && standsAt(bot, cx, cy, cz)) cells.push([cx, cy, cz])
      }
    }
  }
  const centre = ([x, y, z]) => [x + 0.5, y, z + 0.5]
  return cells.sort((a, b) => blocksApart(from, centre(a)) - blocksApart(from, centre(b)))
}

/**
 * Whether a body standing anywhere in `cell` would pick up an item lying at `position`. A walk
 * ends where the pathfinder first stands in the cell, not at its centre, so the box must reach
 * the item from every point of the cell's floor: the v25 rehearsal's beta stopped 0.17 short of
 * the centre of a cell whose centre covered its stick, and waited out the deadline.
 */
function covers ([cx, cy, cz], [ix, iy, iz]) {
  const overlaps = (low, high, itemLow, itemHigh) => low < itemHigh && itemLow < high
  const reach = PLAYER_HALF_WIDTH + PICKUP_GROWTH.x
  return overlaps(cx + 1 - reach, cx + reach, ix - ITEM_HALF_WIDTH, ix + ITEM_HALF_WIDTH) &&
    overlaps(cy - PICKUP_GROWTH.y, cy + PLAYER_HEIGHT + PICKUP_GROWTH.y, iy, iy + ITEM_HEIGHT) &&
    overlaps(cz + 1 - reach, cz + reach, iz - ITEM_HALF_WIDTH, iz + ITEM_HALF_WIDTH)
}

/** Whether vanilla would pick up an item at `position` from a body whose feet are at `from`. */
function withinPickup ([fx, fy, fz], [ix, iy, iz]) {
  return Math.abs(ix - fx) < PASSIVE_PICKUP_HORIZONTAL_BLOCKS &&
    Math.abs(iz - fz) < PASSIVE_PICKUP_HORIZONTAL_BLOCKS &&
    iy - fy > -PASSIVE_PICKUP_BELOW_BLOCKS &&
    iy - fy < PASSIVE_PICKUP_ABOVE_BLOCKS
}

/** Whether a body can stand with its feet in the cell: free feet and head, ground beneath. */
function standsAt (bot, x, y, z) {
  const at = (dy) => {
    try {
      return bot.blockAt(new Vec3(x, y + dy, z))
    } catch {
      return null
    }
  }
  const feet = at(0)
  const head = at(1)
  const ground = at(-1)
  return feet?.boundingBox === 'empty' && head?.boundingBox === 'empty' && ground?.boundingBox === 'block'
}

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
  if (!approach && !withinPickup(from, resolved.position)) {
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
      // Any cell a body can stand in whose grown pickup box covers the item, not just the item's
      // own cell: vanilla picks an item up only inside the player's box grown by one block
      // sideways, and the item's own cell can have no ground to stand on. See [pickupCells].
      route = movement?.approach === undefined
        ? movement?.moveTo({
          destination: resolved.position,
          tolerance: ITEM_CELL_TOLERANCE,
          timeoutMs,
          sprint,
          owner
        })
        : movement.approach({
          cells: pickupCells(bot, resolved.position, from),
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
  ITEM_CELL_TOLERANCE,
  PASSIVE_PICKUP_ABOVE_BLOCKS,
  PASSIVE_PICKUP_BELOW_BLOCKS,
  PASSIVE_PICKUP_HORIZONTAL_BLOCKS,
  collectOfferedItem,
  covers,
  pickupCells,
  withinPickup
}
