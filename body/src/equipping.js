'use strict'

/**
 * One explicit equip of one inventory stack offered by one retained sense answer.
 *
 * The request chooses both the exact stack and the destination. Mineflayer is allowed
 * only its mechanical hand-slot choice; the report records the actual slot it selected.
 * A failed armour move is `incompatible` only when the live destination confirms the
 * offered stack was not accepted, never because Loom keeps a second armour table.
 */

const { performance } = require('node:perf_hooks')
const {
  elapsedSince,
  offeredStack,
  stackMatchesOffer
} = require('./interaction')

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
          finish('libraryFailure')
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
  MINEFLAYER_DESTINATIONS,
  equipOfferedStack
}
