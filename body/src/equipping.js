'use strict'

/**
 * One explicit equip of one inventory stack offered by one retained sense answer.
 *
 * The request chooses both the exact stack and the destination. Mineflayer is allowed
 * only its mechanical hand-slot choice; the report records the actual slot it selected.
 * A failed armour move is `incompatible` only when the live destination confirms the
 * offered stack was not accepted, never because Loom keeps a second armour table.
 *
 * Mineflayer moves the stack in its local window before the server has answered, and a
 * vanilla server stays silent about a click it accepts but restates the window for one it
 * refuses. So `equipped` waits, after the library settles, one bounded window for that
 * restatement, and is reported only if the destination still holds the offered stack.
 */

const { performance } = require('node:perf_hooks')
const {
  elapsedSince,
  offeredStack,
  stackMatchesOffer
} = require('./interaction')

/**
 * How long, after Mineflayer's clicks settle, a server has to refuse them by restating the
 * window. The same five-tick allowance the eat path gives the server's slot account.
 */
const EQUIP_SETTLE_MS = 250

const MINEFLAYER_DESTINATIONS = Object.freeze({
  hand: 'hand',
  offHand: 'off-hand',
  head: 'head',
  torso: 'torso',
  legs: 'legs',
  feet: 'feet'
})

/** A checked pre-application answer. */
function refused (outcome, startedAt, now) {
  return {
    promise: Promise.resolve({ outcome, elapsedMs: elapsedSince(startedAt, now) }),
    cancel: () => false
  }
}

/** Starts one bounded equip, or undefined when a required live reading is absent. */
function equipOfferedStack (bot, {
  slots,
  slot,
  destination,
  timeoutMs,
  now = () => performance.now()
}) {
  const startedAt = now()
  const resolved = offeredStack(bot, slots, slot)
  if (resolved === undefined) return undefined
  if (resolved.outcome !== undefined) return refused(resolved.outcome, startedAt, now)
  const mineflayerDestination = MINEFLAYER_DESTINATIONS[destination]
  if (mineflayerDestination === undefined) return undefined

  let settled = false
  let librarySettled = false
  let libraryFailed = false
  let abort
  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => finish('timedOut'), timeoutMs)
    let settleTimer
    const client = bot._client
    // Any restatement of the player's own window after the click is the server's answer.
    const restated = (packet) => {
      if (packet?.windowId !== 0 || !librarySettled) return
      if (equippedEffect() === undefined) finish('libraryFailure')
    }
    client?.on?.('set_slot', restated)
    client?.on?.('window_items', restated)

    function equippedEffect () {
      let toSlot
      try {
        toSlot = bot.getEquipmentDestSlot(mineflayerDestination)
      } catch {
        return undefined
      }
      if (!Number.isInteger(toSlot)) return undefined
      const standing = bot.inventory?.slots?.[toSlot]
      if (!stackMatchesOffer(standing, resolved.offered)) return undefined
      return {
        what: resolved.offered.what,
        count: resolved.offered.count,
        toSlot
      }
    }

    function finish (fallback) {
      if (settled) return
      const effect = equippedEffect()
      settled = true
      clearTimeout(timer)
      clearTimeout(settleTimer)
      client?.off?.('set_slot', restated)
      client?.off?.('window_items', restated)
      if (effect !== undefined) {
        resolve({
          outcome: 'equipped',
          elapsedMs: elapsedSince(startedAt, now),
          ...effect
        })
        return
      }
      const armour = !['hand', 'offHand'].includes(destination)
      const outcome = armour && librarySettled && !libraryFailed ? 'incompatible' : fallback
      resolve({ outcome, elapsedMs: elapsedSince(startedAt, now) })
    }
    abort = () => {
      if (settled) return false
      finish('cancelled')
      return true
    }

    try {
      Promise.resolve(bot.equip(resolved.stack, mineflayerDestination)).then(
        () => {
          librarySettled = true
          if (equippedEffect() === undefined) {
            finish('libraryFailure')
            return
          }
          settleTimer = setTimeout(() => finish('libraryFailure'), EQUIP_SETTLE_MS)
        },
        () => {
          librarySettled = true
          libraryFailed = true
          finish('libraryFailure')
        }
      )
    } catch {
      librarySettled = true
      libraryFailed = true
      finish('libraryFailure')
    }
  })

  // Mineflayer exposes no cancellation primitive for in-flight window transactions.
  // Teardown can still clear Loom's own timer and ownership through `abort`.
  return { promise, cancel: () => false, abort: () => abort?.() ?? false }
}

module.exports = {
  EQUIP_SETTLE_MS,
  MINEFLAYER_DESTINATIONS,
  equipOfferedStack
}
