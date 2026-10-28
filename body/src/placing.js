'use strict'

/**
 * One explicit placement of an already-held offered stack against one face of one
 * offered support block.
 *
 * The body never equips or searches for a block here. Both handles are revalidated
 * immediately before the packet, the named inventory slot must already be the main
 * hand, and a destination a body stands in is refused before any packet, as vanilla
 * refuses it. One packet is then answered by the server, which resends the destination,
 * placed or not, and acknowledges the packet's sequence (`acknowledge_player_digging`):
 * the placement settles at that acknowledgement, and only a server that never answers
 * waits out the bounded deadline. `forceLook: 'ignore'` is deliberate: inactive physics
 * emits no ticks, so the public helper's look task would otherwise wait on simulation this
 * request does not own.
 *
 * The shift key is held for the one packet and released at settlement, as a player places
 * against a block: unheld, vanilla uses an interactable support instead - a crafting table opens
 * its window and nothing is placed, which the provider-free v36 rehearsal's delta met twelve
 * times as `libraryFailure` closing its shelter against its own table. Held, the server places
 * against any support, and the release passes the movement gate as the one packet it permits.
 *
 * r39 iota closed a face whose cell its own box reached into, 0.227 of a block past its
 * feet cell's edge. On real Paper the server refused that placement, resent the destination
 * as air and acknowledged it 54 ms after the packet; the body read only a placed block as an
 * answer, so it held the body's one exclusive owner for 330 ticks under skeleton fire, until
 * the agent died, and three survival placements were refused behind it.
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
const { blockInside, worldBorderOf } = require('./border')
const { LIVING_ENTITY_TYPES } = require('./combat')
const { writeShift } = require('./movement')

/** A player's own collision box, for a body Mineflayer states no size for: 0.6 wide, 1.8 tall. */
const PLAYER_WIDTH = 0.6
const PLAYER_HEIGHT = 1.8

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

/**
 * Whether a body - the bot's own, or a living creature's it can see - stands in the cell at
 * [destination], which vanilla will not place a block into (`Level.isUnobstructed`). Boxes that
 * only touch the cell's faces do not stand in it.
 */
function bodyStandsIn (bot, destination) {
  const own = bot.entity
  return Object.values(bot.entities ?? {}).some((entity) => {
    if (entity !== own && !LIVING_ENTITY_TYPES.has(entity?.type)) return false
    const position = entity?.position
    if (position === undefined || position === null) return false
    const width = Number.isFinite(entity.width) ? entity.width : entity === own ? PLAYER_WIDTH : undefined
    const height = Number.isFinite(entity.height) ? entity.height : entity === own ? PLAYER_HEIGHT : undefined
    if (width === undefined || height === undefined) return false
    const half = width / 2
    return position.x - half < destination.x + 1 && position.x + half > destination.x &&
      position.y < destination.y + 1 && position.y + height > destination.y &&
      position.z - half < destination.z + 1 && position.z + half > destination.z
  })
}

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
  if (!isAirBlock(before) || bodyStandsIn(bot, destination)) return refused('destinationBlocked', startedAt, now)
  // The server refuses a use of a block past its world border and puts back a block placed past
  // it, so the wall blocks a placement against or into a cell outside it, as it blocked r36
  // zeta's dig of short grass past the border.
  const border = worldBorderOf(bot)
  if (border !== undefined && [resolvedBlock.block.position, destination]
    .some(({ x, z }) => !blockInside(border, x, z))) {
    return refused('destinationBlocked', startedAt, now)
  }

  let settled = false
  let abort
  const promise = new Promise((resolve) => {
    const eventName = `blockUpdate:${destination}`
    const timer = setTimeout(() => finish('timedOut'), timeoutMs)
    const client = bot._client
    let sent = false
    let shifted = false

    function cleanup () {
      clearTimeout(timer)
      bot.off(eventName, updated)
      client?.off?.('acknowledge_player_digging', acknowledged)
      if (shifted) {
        shifted = false
        try {
          writeShift(bot, false)
        } catch {
          // A connection that cannot take the release has already ended, and the server with it.
        }
      }
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

    // The server has answered the packet: the destination it resent is the placement's result.
    // A refusal is a body standing in the cell when one does, which the server saw first, and
    // otherwise the library's operation ending without the confirmation it was owed.
    function acknowledged () {
      if (sent) finish(bodyStandsIn(bot, destination) ? 'destinationBlocked' : 'libraryFailure')
    }

    bot.on(eventName, updated)
    client?.on?.('acknowledge_player_digging', acknowledged)
    try {
      writeShift(bot, true)
      shifted = true
      sent = true
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
