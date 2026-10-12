'use strict'

/**
 * Shared mechanical checks for request-scoped native interactions.
 *
 * This module contains no target or item selection. It only resolves one request-local
 * offer against the live Mineflayer state immediately before an action, preserving the
 * distinction between a forgotten handle, an index never offered, and mutable world or
 * inventory state that changed after the sense answer.
 */

const { Vec3 } = require('vec3')
const {
  MAX_STACK_COUNT,
  isResourceName
} = require('./protocol')
const {
  INTERACTION_REACH_BLOCKS,
  blockInSight,
  droppedStack,
  positionOf,
  stackDetail
} = require('./sensing')

const AIR_BLOCKS = Object.freeze(new Set(['air', 'cave_air', 'void_air']))

/** Whether a live item is still the exact offered stack. */
function stackMatchesOffer (stack, offered) {
  if (stack === null || stack === undefined) return false
  const detail = stackDetail(stack)
  return detail !== undefined &&
    stack.name === offered.what &&
    stack.count === offered.count &&
    detail === offered.detail
}

/** Milliseconds elapsed on a clock that may be injected by tests. */
function elapsedSince (startedAt, now) {
  return Math.max(0, Math.trunc(now() - startedAt))
}

/** The body's eye position, or undefined when it cannot be measured. */
function eyesOf (bot) {
  const standing = bot.entity?.position
  const height = bot.entity?.eyeHeight
  if (standing === null || standing === undefined) return undefined
  if (typeof height !== 'number' || !Number.isFinite(height) || height < 0) return undefined
  return standing.offset(0, height, 0)
}

/** The exact live block an offer still names, with a closed refusal when it does not. */
function offeredBlock (bot, offers, index) {
  if (offers === undefined) return { outcome: 'senseForgotten' }
  const offered = offers[index]
  if (offered === undefined) return { outcome: 'blockUnnamed' }
  let block
  try {
    block = bot.blockAt(new Vec3(...offered.position))
  } catch {
    return undefined
  }
  if (block === null || block === undefined || AIR_BLOCKS.has(block.name)) {
    return { outcome: 'blockGone' }
  }
  if (block.name !== offered.what ||
      block.type !== offered.type ||
      block.stateId !== offered.stateId) {
    return { outcome: 'blockChanged' }
  }
  return { offered, block }
}

/** The exact live stack an offer still names, with a closed refusal when it does not. */
function offeredStack (bot, slots, slot) {
  if (slots === undefined) return { outcome: 'senseForgotten' }
  const offered = slots.get(slot)
  if (offered === undefined) return { outcome: 'slotUnnamed' }
  const inventory = bot.inventory?.slots
  if (!Array.isArray(inventory)) return undefined
  const stack = inventory[slot]
  if (stack === null || stack === undefined) return { outcome: 'slotEmpty' }
  if (!stackMatchesOffer(stack, offered)) {
    return { outcome: 'slotChanged' }
  }
  return { offered, stack }
}

/** The exact dropped-item object an offer still names. */
function offeredItem (bot, offers, index) {
  if (offers === undefined) return { outcome: 'senseForgotten' }
  const offered = offers[index]
  if (offered === undefined) return { outcome: 'itemUnnamed' }
  const live = bot.entities?.[offered.id]
  if (offered.gone === true || live !== offered.entity || live?.isValid === false) {
    return { outcome: 'itemGone' }
  }
  const stack = droppedStack(live)
  if (stack === undefined) return { outcome: 'itemGone' }
  if (stack.what !== offered.what ||
      stack.count !== offered.count ||
      stack.detail !== offered.detail) {
    return { outcome: 'itemChanged' }
  }
  const position = positionOf(live.position)
  if (position === undefined) return undefined
  return { offered, entity: live, stack, position }
}

/** Whether a live block's centre is within Mineflayer's native interaction reach. */
function blockWithinReach (bot, block) {
  const eyes = eyesOf(bot)
  if (eyes === undefined) return undefined
  return block.position.offset(0.5, 0.5, 0.5).distanceTo(eyes) <= INTERACTION_REACH_BLOCKS
}

/** Whether a live block can still be reached by a clear ray from the body's eyes. */
function visibleBlock (bot, block) {
  const eyes = eyesOf(bot)
  if (eyes === undefined) return undefined
  try {
    return blockInSight(bot, eyes, block)
  } catch {
    return undefined
  }
}

/** Which inventory slot Mineflayer currently exposes as its main hand. */
function heldSlot (bot) {
  const start = bot.QUICK_BAR_START
  const selected = bot.quickBarSlot
  if (!Number.isInteger(start) || !Number.isInteger(selected)) return undefined
  return start + selected
}

/** A stack count clamped to the domain the wire can state after an effect. */
function boundedRemaining (stack, what) {
  if (stack === null || stack === undefined || stack.name !== what) return 0
  if (!Number.isFinite(stack.count)) return 0
  return Math.min(Math.max(Math.trunc(stack.count), 0), MAX_STACK_COUNT)
}

/** Whether a block name is empty world space. */
function isAirBlock (block) {
  return block !== null && block !== undefined && AIR_BLOCKS.has(block.name)
}

/** Whether a value is a registry name safe to report as an actual effect. */
function effectName (value) {
  return isResourceName(value) ? value : undefined
}

module.exports = {
  boundedRemaining,
  blockWithinReach,
  effectName,
  elapsedSince,
  eyesOf,
  heldSlot,
  isAirBlock,
  offeredBlock,
  offeredItem,
  offeredStack,
  stackDetail,
  stackMatchesOffer,
  visibleBlock
}
