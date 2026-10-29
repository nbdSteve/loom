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
 * the agent died, and three survival placements were refused behind it. A route ends up to
 * 0.35 of a block off its cell's centre and a pickup or a blow carries a body further, so a
 * body's own box often reaches into the cell beside it: a placement there first takes one
 * bounded straight step to the centre of the body's own cell (`finalApproach`), which clears
 * every cell beside it, and is refused as `destinationBlocked` only where that step cannot be
 * taken or does not clear it (the provider-free v36 rehearsal's delta could close none of the
 * faces its box reached and died in the open). Nothing is chosen: the centre is the cell's own.
 *
 * A destination is air or a block vanilla replaces with the placed one, the 1.21.1
 * `minecraft:replaceable` tag ([REPLACEABLE_BLOCKS]): short grass beside a body is no wall, and a
 * shelter in a meadow could close no face that held it.
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
const { standsOnSupport, writeShift } = require('./movement')

/** A player's own collision box, for a body Mineflayer states no size for: 0.6 wide, 1.8 tall. */
const PLAYER_WIDTH = 0.6
const PLAYER_HEIGHT = 1.8

/**
 * The blocks vanilla 1.21.1 replaces with a placed block: its `minecraft:replaceable` block tag,
 * read from the server's own data pack. Snow is replaced only as a single layer by any item but
 * snow, which [replaceable] holds it to.
 */
const REPLACEABLE_BLOCKS = new Set([
  'air', 'water', 'lava', 'short_grass', 'fern', 'dead_bush', 'seagrass', 'tall_seagrass', 'fire',
  'soul_fire', 'snow', 'vine', 'glow_lichen', 'light', 'tall_grass', 'large_fern', 'structure_void',
  'void_air', 'cave_air', 'bubble_column', 'warped_roots', 'nether_sprouts', 'crimson_roots',
  'hanging_roots'
])

/** Whether a placed block lands in [block]'s cell: air, or a block vanilla replaces with it. */
function replaceable (block) {
  if (isAirBlock(block)) return true
  if (!REPLACEABLE_BLOCKS.has(block?.name)) return false
  if (block.name !== 'snow') return true
  try {
    return block.getProperties?.().layers === 1
  } catch {
    return false
  }
}

/** The last sequence a placement packet carried: each is fresh, and the library's own are all 0. */
let lastSequence = 0

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
function bodyStandsIn (bot, destination, { others = true, own = true } = {}) {
  const self = bot.entity
  return Object.values(bot.entities ?? {}).concat(self === undefined || self === null ? [] : [self])
    .some((entity) => {
      if (entity === self ? !own : !others || !LIVING_ENTITY_TYPES.has(entity?.type)) return false
      return boxReaches(entity, entity === self, entity?.position, destination)
    })
}

/** Whether [entity]'s box, its feet at [position], reaches into the cell at [destination]. */
function boxReaches (entity, own, position, destination) {
  if (position === undefined || position === null) return false
  const width = Number.isFinite(entity?.width) ? entity.width : own ? PLAYER_WIDTH : undefined
  const height = Number.isFinite(entity?.height) ? entity.height : own ? PLAYER_HEIGHT : undefined
  if (width === undefined || height === undefined) return false
  const half = width / 2
  return position.x - half < destination.x + 1 && position.x + half > destination.x &&
    position.y < destination.y + 1 && position.y + height > destination.y &&
    position.z - half < destination.z + 1 && position.z + half > destination.z
}

/**
 * The centre of the cell the body stands in, when its own box reaches into [destination] only from
 * where it stands: a step there, onto the same support, leaves the box clear of the cell. `null`
 * when the box does not reach it, and undefined when no such step clears it.
 */
function centringFor (bot, destination) {
  const self = bot.entity
  const position = self?.position
  if (!boxReaches(self, true, position, destination)) return null
  const centre = new Vec3(Math.floor(position.x) + 0.5, position.y, Math.floor(position.z) + 0.5)
  if (boxReaches(self, true, centre, destination) || !standsOnSupport(bot, centre)) return undefined
  return centre
}

/**
 * Runs [place], which writes its one placement packet before its first await, with that packet
 * carrying [sequence]: the library writes none of its own.
 */
function withSequence (client, sequence, place) {
  const write = client?.write
  if (typeof write !== 'function') return place()
  client.write = function (name, parameters) {
    return write.call(this, name, name === 'block_place' ? { ...parameters, sequence } : parameters)
  }
  try {
    return place()
  } finally {
    client.write = write
  }
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
  movement,
  owner,
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
  if (!replaceable(before) || bodyStandsIn(bot, destination, { own: false })) {
    return refused('destinationBlocked', startedAt, now)
  }
  // The body's own box reaching the cell is cleared by a step to its own cell's centre, where one
  // can be taken; see the header.
  const centre = centringFor(bot, destination)
  if (centre === undefined || (centre !== null && typeof movement?.finalApproach !== 'function')) {
    return refused('destinationBlocked', startedAt, now)
  }
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
  let cancelCentring
  const promise = new Promise((resolve) => {
    const eventName = `blockUpdate:${destination}`
    const timer = setTimeout(() => finish('timedOut'), timeoutMs)
    const client = bot._client
    let sent = false
    let shifted = false
    // The step to the centre of the body's own cell, while it is still to be taken or in flight.
    let approaching

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

    // A block other than the one the destination held: air or a replaceable block gave way to it.
    function observedPlacement () {
      let standing
      try {
        standing = bot.blockAt(destination)
      } catch {
        return undefined
      }
      if (standing === null || standing === undefined || isAirBlock(standing) ||
        standing.stateId === before.stateId) return undefined
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
      const report = effect === undefined
        ? { outcome: fallback, elapsedMs: elapsedSince(startedAt, now) }
        : {
            outcome: 'placed',
            elapsedMs: elapsedSince(startedAt, now),
            ...effect
          }
      // A step still in flight is stopped, and the answer waits for the body to come to rest, so
      // the owner it frees finds the path free as well.
      if (approaching === undefined) return resolve(report)
      movement.halt()
      approaching.then(() => resolve(report), () => resolve(report))
    }
    abort = () => {
      if (settled) return false
      finish('cancelled')
      return true
    }
    // Before the packet nothing has reached the server, so a halt stops the step cleanly.
    cancelCentring = () => approaching !== undefined && abort()

    function updated () {
      if (observedPlacement() !== undefined) finish('libraryFailure')
    }

    // The server has answered this packet: the destination it resent is the placement's result.
    // A refusal is a body standing in the cell when one does, which the server saw first, and
    // otherwise the library's operation ending without the confirmation it was owed.
    function acknowledged (packet) {
      if (sent && packet?.sequenceId >= sequence) {
        finish(bodyStandsIn(bot, destination) ? 'destinationBlocked' : 'libraryFailure')
      }
    }

    let sequence
    function place () {
      bot.on(eventName, updated)
      client?.on?.('acknowledge_player_digging', acknowledged)
      sequence = ++lastSequence
      try {
        writeShift(bot, true)
        shifted = true
        sent = true
        Promise.resolve(withSequence(client, sequence, () => bot._genericPlace(
          resolvedBlock.block,
          vector,
          { forceLook: 'ignore', swingArm: 'right' }
        ))).then(
          () => {
            if (observedPlacement() !== undefined) finish('libraryFailure')
          },
          () => finish('libraryFailure')
        )
      } catch {
        finish('libraryFailure')
      }
    }

    if (centre === null) {
      place()
      return
    }
    // From the centre the box clears the cell, and the support is read again from there.
    approaching = Promise.resolve(movement.finalApproach({
      point: [centre.x, centre.y, centre.z],
      timeoutMs: Math.max(0, timeoutMs - elapsedSince(startedAt, now)),
      owner
    }))
    approaching.then(
      (stepped) => {
        if (settled) return
        approaching = undefined
        if (stepped === undefined || bodyStandsIn(bot, destination)) return finish('destinationBlocked')
        const stillInReach = blockWithinReach(bot, resolvedBlock.block)
        if (stillInReach !== true) return finish(stillInReach === false ? 'outOfReach' : 'libraryFailure')
        const stillVisible = visibleBlock(bot, resolvedBlock.block)
        if (stillVisible !== true) return finish(stillVisible === false ? 'notVisible' : 'libraryFailure')
        place()
      },
      () => {
        if (settled) return
        approaching = undefined
        finish('libraryFailure')
      }
    )
  })

  // Once the placement packet is sent Mineflayer exposes no cancellation primitive, so
  // an ordinary halt cannot promise it stopped the server effect; before it, a halt ends the step
  // to the centre. Process teardown still uses `abort` to clear the bounded listener and timer
  // before the connection closes.
  return { promise, cancel: () => cancelCentring?.() ?? false, abort: () => abort?.() ?? false }
}

module.exports = {
  FACE_VECTORS,
  installPlacing,
  placeHeldBlock
}
