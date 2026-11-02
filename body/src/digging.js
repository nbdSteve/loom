'use strict'

/**
 * One explicit, bounded dig of one block offered by one retained sense answer.
 *
 * No tool is selected here. Mineflayer uses the main-hand item the controller left in
 * place, and the report records that actual item separately from the block choice.
 * Every mutable fact is re-read before the first dig packet, and every ending clears
 * Mineflayer's one digging task before releasing ownership.
 *
 * A body standing on support holds the client simulation for the dig, and starts it only once
 * the simulation has established the ground state, because both Mineflayer's dig time and the
 * server's destroy progress are five times slower for a player who is not on the ground and a
 * teleport leaves the client reporting exactly that. See `holdSimulation` in `movement.js`.
 *
 * `dug` waits for the server's own update at the offered position; see [serverUpdates] for why
 * Mineflayer's resolved dig is only a prediction.
 *
 * And `dug` is this body's own break only: the server's update has to answer this client's own
 * finish packet. Mineflayer resolves a dig whenever the block turns to air, so a block another
 * player broke while this one was still digging resolved as if this body had broken it; that is
 * `blockGone`, attempted. A finish already written cannot be taken back, so a halt arriving
 * while the server's answer is in flight stops nothing, and the dig answers that answer: the
 * r23 rehearsal had one agent report `cancelled` for a break the server credited to it and the
 * other `dug` for the same block (`test/native-dig-contest.test.js`).
 *
 * And the block dug is the block offered. The world changes blocks of its own accord - grass
 * spreads onto dirt the sky reaches - so the offer is read again as the dig starts, and a server
 * update that turns the offered block into another before this body's finish stops the dig as
 * `blockChanged`, attempted: the v28 plains rehearsal had grass spread onto the dirt at the
 * bottom of an agent's own pit between its sense and its break, and the server broke a
 * grass_block the body had been offered as dirt.
 */

const { performance } = require('node:perf_hooks')
const { Vec3 } = require('vec3')
const digging = require('mineflayer/lib/plugins/digging')
const { isResourceName } = require('./protocol')
const {
  blockWithinReach,
  elapsedSince,
  isAirBlock,
  offeredBlock,
  visibleBlock
} = require('./interaction')
const { holdSimulation, releaseAtRest, standsOnSupport } = require('./movement')
const { blockInside, worldBorderOf } = require('./border')

/**
 * How many simulation steps a standing body is given to establish its ground state before the
 * dig starts. One supported step is enough; the bound only stops a body the simulation never
 * puts on the ground from holding the dig back, and that body digs at the airborne rate it has.
 */
const GROUND_SETTLE_TICKS = 4

/** The one installation each player gets; a second would install a second dig task. */
const installations = new WeakSet()

/**
 * Loads Mineflayer's mechanical dig primitive without invoking it.
 *
 * Kept outside the reporting plugin table because this plugin defines an action. The
 * target, deadline, cancellation, and single ownership all remain in [digOfferedBlock].
 */
function installDigging (bot) {
  if (installations.has(bot)) return
  bot.loadPlugin(digging)
  installations.add(bot)
}

/** A checked pre-application answer. */
function refused (outcome, startedAt, now) {
  return {
    promise: Promise.resolve({
      outcome,
      elapsedMs: elapsedSince(startedAt, now),
      attempted: false
    }),
    cancel: () => false
  }
}

/**
 * Watches the server's own block updates for one position, from the moment a dig starts.
 *
 * Mineflayer predicts a finished dig: it sets the block to air locally as it writes the finish
 * packet and resolves `bot.dig` on that local update, before the server has judged the dig.
 * A server that refuses the finish answers with the block's real state, and one that accepts
 * broadcasts the change to every player tracking the chunk, the digger included. So only a
 * server update at the offered position says what became of it. Answers `seen()` - whether
 * one has arrived - `next(listener)` to hear the next one, and `close()`.
 */
function serverUpdates (bot, position) {
  const client = bot._client
  if (client === null || client === undefined || typeof client.on !== 'function') return undefined
  const [x, y, z] = position
  let seen = false
  // Whether this client wrote its own finish of the position, and whether a server update has
  // arrived since - which is what makes that update an answer to this body's dig. An update
  // before the finish (a server echoing the dig's start, or another player's break) is none.
  let finished = false
  let answered = false
  let waiting
  let changed
  const heard = () => {
    if (finished) answered = true
    seen = true
    const listener = waiting
    waiting = undefined
    listener?.()
    if (!finished) changed?.()
  }
  const originalWrite = client.write
  const watchedWrite = typeof originalWrite === 'function'
    ? function (name, parameters) {
      const at = parameters?.location
      if (name === 'block_dig' && parameters?.status === 2 && at?.x === x && at?.y === y && at?.z === z) {
        finished = true
      }
      return originalWrite.call(this, name, parameters)
    }
    : undefined
  if (watchedWrite !== undefined) client.write = watchedWrite
  const single = (packet) => {
    const at = packet?.location
    if (at?.x === x && at?.y === y && at?.z === z) heard()
  }
  const multiple = (packet) => {
    if (multiBlockPositions(bot, packet).some((at) => at[0] === x && at[1] === y && at[2] === z)) heard()
  }
  client.on('block_change', single)
  client.on('multi_block_change', multiple)
  return {
    seen: () => seen,
    /** Whether this client has written its own finish of the position. */
    finished: () => finished,
    /** Whether a server update at the position has arrived since this client's own finish. */
    answered: () => answered,
    next (listener) {
      waiting = listener
    },
    /** Hears every server update at the position before this client's own finish. */
    beforeFinish (listener) {
      changed = listener
    },
    close () {
      waiting = undefined
      changed = undefined
      client.off('block_change', single)
      client.off('multi_block_change', multiple)
      if (watchedWrite !== undefined && client.write === watchedWrite) client.write = originalWrite
    }
  }
}

/** The positions one `multi_block_change` names, decoded the way Mineflayer decodes them. */
function multiBlockPositions (bot, packet) {
  if (typeof bot.supportFeature !== 'function' || !Array.isArray(packet?.records)) return []
  const single = bot.supportFeature('usesMultiblockSingleLong')
  const section = bot.supportFeature('usesMultiblock3DChunkCoords')
    ? [packet.chunkCoordinates?.x, packet.chunkCoordinates?.y, packet.chunkCoordinates?.z]
    : [packet.chunkX, 0, packet.chunkZ]
  return packet.records.map((record) => {
    const offset = single
      ? [(record >> 8) & 0x0f, record & 0x0f, (record >> 4) & 0x0f]
      : [(record.horizontalPos >> 4) & 0x0f, record.y, record.horizontalPos & 0x0f]
    return [section[0] * 16 + offset[0], section[1] * 16 + offset[1], section[2] * 16 + offset[2]]
  })
}

/** Whether the offered position now contains empty space. */
function removed (bot, offered) {
  try {
    const block = bot.blockAt(
      new Vec3(offered.position[0], offered.position[1], offered.position[2])
    )
    return isAirBlock(block)
  } catch {
    return false
  }
}

/**
 * Starts one dig, or returns undefined when the body cannot state the required live
 * readings. The returned cancellation is idempotent and answers whether it stopped
 * work that had not already settled.
 */
function digOfferedBlock (bot, {
  offered,
  block: index,
  timeoutMs,
  movement,
  owner,
  now = () => performance.now()
}) {
  const startedAt = now()
  const resolved = offeredBlock(bot, offered, index)
  if (resolved === undefined) return undefined
  if (resolved.outcome !== undefined) return refused(resolved.outcome, startedAt, now)

  const inReach = blockWithinReach(bot, resolved.block)
  if (inReach === undefined) return undefined
  if (!inReach) return refused('outOfReach', startedAt, now)
  // The server puts back any block past its world border a body digs, so no dig of one is sent:
  // r36 zeta's predicted break of short grass at x -65 waited out its deadline.
  const border = worldBorderOf(bot)
  if (border !== undefined && !blockInside(border, resolved.block.position.x, resolved.block.position.z)) {
    return refused('notDiggable', startedAt, now)
  }
  const visible = visibleBlock(bot, resolved.block)
  if (visible === undefined) return undefined
  if (!visible) return refused('notVisible', startedAt, now)

  let digTime
  try {
    digTime = bot.digTime(resolved.block)
  } catch {
    return refused('notDiggable', startedAt, now)
  }
  if (resolved.block.diggable !== true || !Number.isFinite(digTime)) {
    return refused('notDiggable', startedAt, now)
  }

  const held = bot.heldItem
  const tool = isResourceName(held?.name) ? held.name : undefined
  let settled = false
  let timedOut = false
  let cancelled = false
  let finish
  let settling
  // A descent by a body whose box reaches past its own column is held up by the column beside it,
  // so it first steps to its own cell's centre, where the dug block is all that holds it: the
  // provider-free v36 rehearsal's delta dug the block under its feet 0.3 off its centre, stayed
  // on the edge above its own hole for the rest of the run, and died there.
  // Without a movement to take the step (a suite's bare bot), the dig is as it always was.
  const centre = typeof movement?.finalApproach === 'function' ? descentCentreOf(bot, resolved.block) : undefined
  // A body standing on support holds the simulation for the whole dig, so the client and the
  // server both have its true ground state, and so a dig of the block it stands on ends with
  // the body landed rather than hanging; see [holdSimulation] and [releaseAtRest].
  let release
  let approaching
  // Undefined only for a bot with no client, which has no server to predict ahead of.
  const server = serverUpdates(bot, resolved.offered.position)
  let predicted = false
  /** Whether the server itself has said the offered block is gone, in answer to this body's finish. */
  const serverRemoved = () => (server === undefined || server.answered()) && removed(bot, resolved.offered)
  /**
   * Whether the server removed the block before this body finished: another player's break.
   * Mineflayer predicts air only as it writes this client's finish, so air with no finish written
   * is the server's word, and not this body's break.
   */
  const takenAway = () =>
    server !== undefined && server.seen() && !server.finished() && removed(bot, resolved.offered)
  /** The outcome for a block found gone: this body's break, another's, or `otherwise`. */
  const gone = (otherwise) => serverRemoved() ? 'dug' : takenAway() ? 'blockGone' : otherwise
  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => {
      timedOut = true
      stopDigging()
      // A predicted finish the server has not answered by the deadline is unconfirmed, and one the
      // step to the centre held past it never began.
      if (approaching !== undefined) complete('timedOut', false)
      else complete(gone(predicted ? 'libraryFailure' : 'timedOut'))
    }, timeoutMs)

    function stopDigging () {
      try {
        bot.stopDigging()
      } catch {
        // The outcome below is still bounded and attributed; no library text escapes.
      }
    }

    function complete (outcome, attempted = true) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (settling !== undefined) bot.off('physicsTick', settling)
      server?.close()
      const report = {
        outcome,
        elapsedMs: elapsedSince(startedAt, now),
        attempted,
        ...(attempted && tool !== undefined ? { tool } : {})
      }
      if (approaching !== undefined) {
        // A step to the centre still in flight is stopped, and the answer waits for it to rest.
        movement.halt()
        approaching.then(() => resolve(report), () => resolve(report))
      } else if (release === undefined) {
        resolve(report)
      } else {
        releaseAtRest(bot, release).then(() => resolve(report))
      }
    }

    finish = () => {
      // A finish already written cannot be taken back: the dig answers the server's answer.
      if (settled || server?.finished() === true) return false
      cancelled = true
      stopDigging()
      complete(approaching !== undefined ? 'cancelled' : gone('cancelled'), approaching === undefined)
      return true
    }

    /** The offer read again: a block the world turned into another is no longer the one offered. */
    const changedUnder = () => offeredBlock(bot, offered, index)?.outcome === 'blockChanged'

    // A server update that turns the offered block into another before this body's finish
    // stops the dig; one that removes it is answered by [gone] as before.
    server?.beforeFinish(() => setImmediate(() => {
      if (settled || server.finished() || !changedUnder()) return
      stopDigging()
      complete('blockChanged')
    }))

    function start () {
      if (settled) return
      // The simulation may have held the dig for a few ticks since the offer was read.
      const again = offeredBlock(bot, offered, index)
      if (again?.outcome !== undefined) {
        complete(again.outcome, false)
        return
      }
      try {
        Promise.resolve(bot.dig(resolved.block, 'raycast')).then(
          confirmed,
          () => {
            if (serverRemoved()) complete('dug')
            else if (takenAway()) complete('blockGone')
            else if (timedOut) complete('timedOut')
            else if (cancelled) complete('cancelled')
            else complete('libraryFailure')
          }
        )
      } catch {
        complete('libraryFailure')
      }
    }

    /**
     * Mineflayer's own finish, which may be only its prediction: the answer waits, inside the
     * deadline, for the server's update at the offered position. `dug` is a vanilla-confirmed
     * break; a server that put the block back is the residual Mineflayer could not confirm.
     */
    function confirmed () {
      if (settled) return
      if (server === undefined || server.answered() || !server.finished()) {
        complete(gone('libraryFailure'))
        return
      }
      predicted = true
      server.next(() => {
        if (!settled) complete(gone('libraryFailure'))
      })
    }

    function begin () {
      release = standsOnSupport(bot) ? holdSimulation(bot) : undefined
      if (release === undefined) {
        start()
        return
      }
      // The dig time is read when the dig starts and the ground state is reported after every
      // `physicsTick` listener returns, so the dig starts on the turn after the settling step.
      let ticks = 0
      settling = () => {
        ticks += 1
        if (bot.entity?.onGround !== true && ticks < GROUND_SETTLE_TICKS) return
        bot.off('physicsTick', settling)
        settling = undefined
        setImmediate(start)
      }
      bot.on('physicsTick', settling)
    }

    if (centre === undefined) {
      begin()
      return
    }
    approaching = Promise.resolve(movement.finalApproach({
      point: [centre.x, centre.y, centre.z],
      timeoutMs: Math.max(0, timeoutMs - elapsedSince(startedAt, now)),
      owner
    }))
    approaching.then(
      (stepped) => {
        if (settled) return
        approaching = undefined
        // From the centre the block is read again: in reach, in sight, and still the one offered.
        if (stepped === undefined || descentCentreOf(bot, resolved.block) !== undefined) {
          return complete('notDiggable', false)
        }
        const stillInReach = blockWithinReach(bot, resolved.block)
        if (stillInReach !== true) return complete(stillInReach === false ? 'outOfReach' : 'libraryFailure', false)
        const stillVisible = visibleBlock(bot, resolved.block)
        if (stillVisible !== true) return complete(stillVisible === false ? 'notVisible' : 'libraryFailure', false)
        begin()
      },
      () => {
        if (settled) return
        approaching = undefined
        complete('libraryFailure', false)
      }
    )
  })

  const cancel = () => finish?.() ?? false
  return { promise, cancel, abort: cancel }
}

/** How far off its cell's centre a body's 0.6-wide box stays inside its own column. */
const OWN_COLUMN_OFFSET_BLOCKS = 0.2

/**
 * The centre of the body's own cell when [block] is the one its feet cell stands on and its box
 * reaches past its own column, so a dig of it would leave the body on the edge of the column beside
 * it; undefined otherwise. A body already that near its centre drops into the cell it dug.
 */
function descentCentreOf (bot, block) {
  const position = bot.entity?.position
  if (position === undefined || position === null || block?.position === undefined) return undefined
  const cell = { x: Math.floor(position.x), y: Math.floor(position.y + 1e-3), z: Math.floor(position.z) }
  const under = block.position
  if (under.x !== cell.x || under.y !== cell.y - 1 || under.z !== cell.z) return undefined
  const centre = new Vec3(cell.x + 0.5, position.y, cell.z + 0.5)
  if (Math.abs(position.x - centre.x) <= OWN_COLUMN_OFFSET_BLOCKS &&
    Math.abs(position.z - centre.z) <= OWN_COLUMN_OFFSET_BLOCKS) return undefined
  return centre
}

module.exports = {
  GROUND_SETTLE_TICKS,
  descentCentreOf,
  digOfferedBlock,
  installDigging
}
