'use strict'

/**
 * One explicit placement of an already-held offered stack against one face of one
 * offered support block.
 *
 * The body never equips or searches for a block here. Both handles are revalidated
 * immediately before the packet, the named inventory slot must already be the main
 * hand, and one packet is followed only by a bounded wait for the destination block
 * update. `forceLook: 'ignore'` is deliberate: inactive physics emits no ticks, so the
 * public helper's look task would otherwise wait on simulation this request does not own.
 */

const { performance } = require('node:perf_hooks')
const { Vec3 } = require('vec3')
const genericPlace = require('mineflayer/lib/plugins/generic_place')
const {
  boundedRemaining,
  blockWithinReach,
  effectName,
  elapsedSince,
  heldSlot,
  isAirBlock,
  offeredBlock,
  offeredStack,
  stackMatchesOffer,
  visibleBlock
} = require('./interaction')

/** The one installation each player gets; a second would duplicate packet listeners. */
const installations = new WeakSet()

/**
 * Loads Mineflayer's mechanical placement primitive without choosing or placing anything.
 *
 * Kept outside the reporting plugin table because this plugin defines an action. The exact
 * support, face, held stack, deadline, and one-shot wait remain in [placeHeldBlock].
 */
function installPlacing (bot) {
  if (installations.has(bot)) return
  bot.loadPlugin(genericPlace)
  installations.add(bot)
}

const FACE_VECTORS = Object.freeze({
  down: new Vec3(0, -1, 0),
  up: new Vec3(0, 1, 0),
  north: new Vec3(0, 0, -1),
  south: new Vec3(0, 0, 1),
  west: new Vec3(-1, 0, 0),
  east: new Vec3(1, 0, 0)
})

/** A checked pre-application answer. */
function refused (outcome, startedAt, now) {
  return {
    promise: Promise.resolve({ outcome, elapsedMs: elapsedSince(startedAt, now) }),
    cancel: () => false
  }
}

/** Starts one bounded placement, or undefined when a required live reading is absent. */
function placeHeldBlock (bot, {
  blockOffers,
  slots,
  block: blockIndex,
  slot,
  face,
  timeoutMs,
  now = () => performance.now()
}) {
  const startedAt = now()
  const resolvedBlock = offeredBlock(bot, blockOffers, blockIndex)
  if (resolvedBlock === undefined) return undefined
  if (resolvedBlock.outcome !== undefined) {
    return refused(resolvedBlock.outcome, startedAt, now)
  }
  const resolvedStack = offeredStack(bot, slots, slot)
  if (resolvedStack === undefined) return undefined
  if (resolvedStack.outcome !== undefined) {
    return refused(resolvedStack.outcome, startedAt, now)
  }

  const inReach = blockWithinReach(bot, resolvedBlock.block)
  if (inReach === undefined) return undefined
  if (!inReach) return refused('outOfReach', startedAt, now)
  const visible = visibleBlock(bot, resolvedBlock.block)
  if (visible === undefined) return undefined
  if (!visible) return refused('notVisible', startedAt, now)

  const held = bot.heldItem
  if (heldSlot(bot) !== slot ||
      !stackMatchesOffer(held, resolvedStack.offered)) {
    return refused('itemNotHeld', startedAt, now)
  }
  if (bot.registry?.blocksByName?.[resolvedStack.offered.what] === undefined) {
    return refused('notPlaceable', startedAt, now)
  }

  const vector = FACE_VECTORS[face]
  if (vector === undefined) return undefined
  const destination = resolvedBlock.block.position.plus(vector)
  let before
  try {
    before = bot.blockAt(destination)
  } catch {
    return undefined
  }
  if (before === null || before === undefined) return undefined
  if (!isAirBlock(before)) return refused('destinationBlocked', startedAt, now)

  let settled = false
  let abort
  const promise = new Promise((resolve) => {
    const eventName = `blockUpdate:${destination}`
    const timer = setTimeout(() => finish('timedOut'), timeoutMs)

    function cleanup () {
      clearTimeout(timer)
      bot.off(eventName, updated)
    }

    function observedPlacement () {
      let standing
      try {
        standing = bot.blockAt(destination)
      } catch {
        return undefined
      }
      if (standing === null || standing === undefined || isAirBlock(standing)) return undefined
      const what = effectName(standing.name)
      if (what === undefined) return undefined
      return {
        what,
        position: [destination.x, destination.y, destination.z],
        remaining: boundedRemaining(bot.inventory?.slots?.[slot], resolvedStack.offered.what)
      }
    }

    function finish (fallback) {
      if (settled) return
      const effect = observedPlacement()
      settled = true
      cleanup()
      resolve(effect === undefined
        ? { outcome: fallback, elapsedMs: elapsedSince(startedAt, now) }
        : {
            outcome: 'placed',
            elapsedMs: elapsedSince(startedAt, now),
            ...effect
          })
    }
    abort = () => {
      if (settled) return false
      finish('cancelled')
      return true
    }

    function updated () {
      if (observedPlacement() !== undefined) finish('libraryFailure')
    }

    bot.on(eventName, updated)
    try {
      Promise.resolve(bot._genericPlace(
        resolvedBlock.block,
        vector,
        { forceLook: 'ignore', swingArm: 'right' }
      )).then(
        () => {
          if (observedPlacement() !== undefined) finish('libraryFailure')
        },
        () => finish('libraryFailure')
      )
    } catch {
      finish('libraryFailure')
    }
  })

  // Once the placement packet is sent Mineflayer exposes no cancellation primitive, so
  // an ordinary halt cannot promise it stopped the server effect. Process teardown still
  // uses `abort` to clear the bounded listener/timer before the connection closes.
  return { promise, cancel: () => false, abort: () => abort?.() ?? false }
}

module.exports = {
  FACE_VECTORS,
  installPlacing,
  placeHeldBlock
}
