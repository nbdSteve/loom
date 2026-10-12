'use strict'

/**
 * One explicit, bounded sleep at one retained bed.
 *
 * No clock policy lives here. The controller names the bed and Mineflayer asks vanilla
 * to use it once. The request remains the exclusive owner through the server's wake
 * event, or through bounded wake cleanup after timeout or halt.
 */

const { performance } = require('node:perf_hooks')
const bed = require('mineflayer/lib/plugins/bed')
const {
  blockWithinReach,
  elapsedSince,
  offeredBlock,
  visibleBlock
} = require('./interaction')

/** How long cancellation waits for vanilla's wake event before releasing ownership. */
const WAKE_GRACE_MS = 1000

/**
 * How long a terminal request guards against a late sleep entry.
 *
 * The pinned Mineflayer bed plugin rejects its own wait after 3000 ms. Keeping this
 * guard slightly longer means a timeout or halt cannot answer "not entered" and then
 * leave a later server acceptance sleeping outside any request.
 */
const SLEEP_ENTRY_DRAIN_MS = 3250

const installations = new WeakSet()

function installSleeping (bot) {
  if (installations.has(bot)) return
  bot.loadPlugin(bed)
  installations.add(bot)
}

function bedOutcome (outcome) {
  if (outcome === 'blockUnnamed') return 'bedUnnamed'
  if (outcome === 'blockGone') return 'bedGone'
  if (outcome === 'blockChanged') return 'bedChanged'
  return outcome
}

function refused (outcome, startedAt, now) {
  return {
    promise: Promise.resolve({
      outcome,
      elapsedMs: elapsedSince(startedAt, now),
      entered: false
    }),
    cancel: () => false
  }
}

/** Closed mapping of Mineflayer's own sleep refusals. */
function refusalFrom (failure) {
  const message = failure instanceof Error ? failure.message : ''
  if (message.includes('not night') || message.includes('thunderstorm')) return 'notNight'
  if (message.includes('occupied') || message.includes('already sleeping')) return 'occupied'
  if (message.includes('not a bed') || message.includes('half bed')) return 'notBed'
  if (message.includes('too far') || message.includes('cant click')) return 'outOfReach'
  if (message.includes('monsters nearby')) return 'unsafe'
  if (message.includes('not sleeping')) return 'sleepRefused'
  return 'libraryFailure'
}

/**
 * Starts one exact sleep. Undefined means a live reading or Mineflayer primitive is
 * unavailable and the control layer should refuse the request as notReady.
 */
function sleepInOfferedBed (bot, {
  offered,
  block: index,
  timeoutMs,
  entryDrainMs = SLEEP_ENTRY_DRAIN_MS,
  now = () => performance.now()
}) {
  const startedAt = now()
  const resolved = offeredBlock(bot, offered, index)
  if (resolved === undefined) return undefined
  if (resolved.outcome !== undefined) {
    return refused(bedOutcome(resolved.outcome), startedAt, now)
  }
  if (bot.game?.dimension !== 'overworld') {
    return refused('wrongDimension', startedAt, now)
  }
  if (typeof bot.isABed !== 'function' ||
      typeof bot.parseBedMetadata !== 'function' ||
      typeof bot.sleep !== 'function' ||
      typeof bot.wake !== 'function') return undefined
  if (!bot.isABed(resolved.block)) return refused('notBed', startedAt, now)
  let metadata
  try {
    metadata = bot.parseBedMetadata(resolved.block)
  } catch {
    return undefined
  }
  if (metadata?.occupied) return refused('occupied', startedAt, now)
  const inReach = blockWithinReach(bot, resolved.block)
  if (inReach === undefined) return undefined
  if (!inReach) return refused('outOfReach', startedAt, now)
  const visible = visibleBlock(bot, resolved.block)
  if (visible === undefined) return undefined
  if (!visible) return refused('notVisible', startedAt, now)

  let settled = false
  let entered = false
  let terminal
  let wakeTimer
  let entryDrainTimer
  let wakeRequested = false
  let abort
  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => requestTerminal('timedOut'), timeoutMs)

    function cleanup () {
      clearTimeout(timer)
      clearTimeout(wakeTimer)
      clearTimeout(entryDrainTimer)
      bot.off('sleep', sawSleep)
      bot.off('wake', sawWake)
    }

    function finish (outcome) {
      if (settled) return
      settled = true
      cleanup()
      resolve({
        outcome,
        elapsedMs: elapsedSince(startedAt, now),
        entered
      })
    }

    function requestWake () {
      if (wakeRequested || settled) return
      wakeRequested = true
      wakeTimer = setTimeout(() => finish(terminal ?? 'libraryFailure'), WAKE_GRACE_MS)
      try {
        Promise.resolve(bot.wake()).catch(() => {})
      } catch {
        // The grace below still bounds ownership when the library cannot write wake.
      }
    }

    function guardLateEntry () {
      if (entryDrainTimer !== undefined || settled) return
      const remaining = Math.max(0, entryDrainMs - elapsedSince(startedAt, now))
      entryDrainTimer = setTimeout(() => {
        entryDrainTimer = undefined
        if (entered || bot.isSleeping === true) requestWake()
        else finish(terminal ?? 'libraryFailure')
      }, remaining)
    }

    function requestTerminal (outcome) {
      if (settled || terminal !== undefined) return false
      terminal = outcome
      if (outcome === 'bodyDied' || outcome === 'disconnected') {
        finish(outcome)
      } else if (entered || bot.isSleeping === true) {
        requestWake()
      } else {
        guardLateEntry()
      }
      return true
    }

    abort = (outcome = 'cancelled') => requestTerminal(outcome)

    function sawSleep () {
      entered = true
      if (terminal !== undefined) requestWake()
    }

    function sawWake () {
      finish(terminal ?? (entered ? 'woke' : 'sleepRefused'))
    }

    bot.on('sleep', sawSleep)
    bot.on('wake', sawWake)

    try {
      Promise.resolve(bot.sleep(resolved.block)).then(
        () => {
          entered = entered || bot.isSleeping === true
          if (!entered) finish(terminal ?? 'sleepRefused')
          else if (terminal !== undefined) requestWake()
          // Otherwise vanilla's later wake event is this request's terminal answer.
        },
        (failure) => {
          const outcome = terminal ?? refusalFrom(failure)
          if (terminal !== undefined ||
              outcome === 'sleepRefused' ||
              outcome === 'libraryFailure') {
            if (terminal === undefined) terminal = outcome
            guardLateEntry()
          } else {
            finish(outcome)
          }
        }
      )
    } catch (failure) {
      const outcome = terminal ?? refusalFrom(failure)
      if (terminal !== undefined ||
          outcome === 'sleepRefused' ||
          outcome === 'libraryFailure') {
        if (terminal === undefined) terminal = outcome
        guardLateEntry()
      } else {
        finish(outcome)
      }
    }
  })

  return {
    promise,
    cancel: () => abort?.('cancelled') ?? false,
    abort: (outcome) => abort?.(outcome) ?? false
  }
}

module.exports = {
  SLEEP_ENTRY_DRAIN_MS,
  WAKE_GRACE_MS,
  installSleeping,
  refusalFrom,
  sleepInOfferedBed
}
