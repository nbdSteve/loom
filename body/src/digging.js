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
const { holdSimulation, standsOnSupport } = require('./movement')

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
  now = () => performance.now()
}) {
  const startedAt = now()
  const resolved = offeredBlock(bot, offered, index)
  if (resolved === undefined) return undefined
  if (resolved.outcome !== undefined) return refused(resolved.outcome, startedAt, now)

  const inReach = blockWithinReach(bot, resolved.block)
  if (inReach === undefined) return undefined
  if (!inReach) return refused('outOfReach', startedAt, now)
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
  // A body standing on support holds the simulation for the whole dig, so the client and the
  // server both have its true ground state; see [holdSimulation] for why nothing else does.
  const release = bot.entity?.onGround !== true && standsOnSupport(bot)
    ? holdSimulation(bot)
    : undefined
  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => {
      timedOut = true
      stopDigging()
      complete(removed(bot, resolved.offered) ? 'dug' : 'timedOut')
    }, timeoutMs)

    function stopDigging () {
      try {
        bot.stopDigging()
      } catch {
        // The outcome below is still bounded and attributed; no library text escapes.
      }
    }

    function complete (outcome) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (settling !== undefined) bot.off('physicsTick', settling)
      release?.()
      resolve({
        outcome,
        elapsedMs: elapsedSince(startedAt, now),
        attempted: true,
        ...(tool === undefined ? {} : { tool })
      })
    }

    finish = () => {
      if (settled) return false
      cancelled = true
      stopDigging()
      complete(removed(bot, resolved.offered) ? 'dug' : 'cancelled')
      return true
    }

    function start () {
      if (settled) return
      try {
        Promise.resolve(bot.dig(resolved.block, 'raycast')).then(
          () => complete('dug'),
          () => {
            if (removed(bot, resolved.offered)) complete('dug')
            else if (timedOut) complete('timedOut')
            else if (cancelled) complete('cancelled')
            else complete('libraryFailure')
          }
        )
      } catch {
        complete('libraryFailure')
      }
    }

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
  })

  const cancel = () => finish?.() ?? false
  return { promise, cancel, abort: cancel }
}

module.exports = {
  GROUND_SETTLE_TICKS,
  digOfferedBlock,
  installDigging
}
