'use strict'

/**
 * Bounded recipe offers and one explicit craft against one retained offer.
 *
 * The body never chooses what to make. A sense enumerates the recipes the current
 * inventory can apply and privately binds each wire entry to the exact Mineflayer
 * recipe object, the complete inventory fingerprint, and one exact crafting table
 * when the recipe needs it. A craft revalidates all three before every bounded
 * application and invokes no gathering, recursive crafting, or substitute recipe.
 */

const { performance } = require('node:perf_hooks')
const { Vec3 } = require('vec3')
const crafting = require('mineflayer/lib/plugins/craft')
const {
  MAX_CRAFTS,
  MAX_SENSE_RECIPES,
  MAX_STACK_COUNT,
  isResourceName
} = require('./protocol')
const {
  blockWithinReach,
  elapsedSince,
  stackDetail,
  visibleBlock
} = require('./interaction')

/** A finite ceiling on registry work performed by one sense. */
const MAX_RECIPE_CANDIDATES = 2048

/**
 * How long a stopped craft may wait for its one in-flight window operation.
 *
 * Mineflayer's modern inventory click waits for a server slot update without its own
 * timeout. The server is loopback-only, so ten vanilla ticks is a generous drain; beyond
 * it the Minecraft connection is ended rather than releasing ownership while a late
 * inventory transaction can still arrive.
 */
const CRAFT_CLEANUP_GRACE_MS = 500

/**
 * How long one application's result may take to reach Mineflayer's inventory.
 *
 * Mineflayer's craft resolves once its last click is written, and a real server's restated
 * slots can trail it: in r19 two crafts of two applications (theta body:00000310, epsilon
 * 352) judged the second application before the first one's result had arrived, saw a log
 * consumed and no planks gained, and ended libraryFailure. Ten vanilla ticks is generous on a
 * loopback server; a delta still unmatched after it is a real failure.
 */
const CRAFT_SETTLE_MS = 500

/** How often a settling inventory is read again. */
const CRAFT_SETTLE_POLL_MS = 25

/** The packet Mineflayer's crafting primitive writes for every inventory click. */
const CRAFT_PACKET = 'window_click'

/** The one installation each player gets; a second would replace craft state. */
const installations = new WeakSet()

/** A stopped gate is retained here until its bot disconnects. */
const activeCraftGates = new WeakSet()

/**
 * How long a sense waits for its body's own craft application to settle before it reads anyway.
 *
 * A craft moves its ingredients through the crafting grid, which the server's witness cannot
 * address, and a client mid-application states an inventory the server only passes through
 * inside one tick: r29 beta's sense, answered between the clicks of its own yellow-dye craft,
 * claimed a dandelion in the grid and none in the hotbar, and was disputed. One application's
 * drain and settle together are bounded by [CRAFT_CLEANUP_GRACE_MS] and [CRAFT_SETTLE_MS], and
 * the controller's three-second request timeout leaves two seconds beside this wait.
 */
const SENSE_SETTLE_MAX_MS = CRAFT_CLEANUP_GRACE_MS + CRAFT_SETTLE_MS

/**
 * How long a craft holds its next application after a sense read its settled boundary.
 *
 * The server applies a click as soon as its packet arrives, between ticks, while its witness samples
 * the inventory once a tick: r32 eta's sense, read between two oak-button applications, described a
 * boundary the server held for less than one tick, because the next application's clicks followed the
 * answer at once. Three vanilla ticks leave at least two tick samples of that boundary.
 */
const SENSE_BOUNDARY_HOLD_MS = 150

/** Per bot: whether a craft application owns the inventory, and the reads waiting for it. */
const applications = new WeakMap()

function applicationOf (bot) {
  if (!applications.has(bot)) applications.set(bot, { applying: false, readers: [] })
  return applications.get(bot)
}

/**
 * Runs [read] where the inventory is not mid-application: at once when no craft application
 * owns it, else synchronously at the application's settled boundary, before the next
 * application starts, or after [maxMs] at the latest so a stuck craft cannot hold a sense.
 */
function whenInventorySettled (bot, read, maxMs = SENSE_SETTLE_MAX_MS) {
  const state = applicationOf(bot)
  if (!state.applying) return read()
  let done = false
  const once = () => {
    if (done) return
    done = true
    clearTimeout(timer)
    read()
  }
  const timer = setTimeout(() => {
    state.readers = state.readers.filter((reader) => reader !== once)
    once()
  }, maxMs)
  timer.unref?.()
  state.readers.push(once)
  return undefined
}

/** Marks [bot]'s inventory as owned by one craft application. */
function applicationStarted (bot) {
  applicationOf(bot).applying = true
}

/**
 * Ends the application's ownership and serves every waiting read, in order, at this boundary.
 * Answers how many reads it served.
 */
function applicationSettled (bot) {
  const state = applicationOf(bot)
  state.applying = false
  const readers = state.readers
  state.readers = []
  for (const reader of readers) {
    try {
      reader()
    } catch {
      // A read answers for itself; a fault in one must not leave the craft or another read.
    }
  }
  return readers.length
}

function installCrafting (bot) {
  if (installations.has(bot)) return
  bot.loadPlugin(crafting)
  installations.add(bot)
}

/** A cancellable delay used only to bound cleanup of Mineflayer's own promise. */
function delay (ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * Owns every Mineflayer inventory click and packet for one craft request.
 *
 * The craft plugin resolves `bot.clickWindow` afresh for every step, so replacing that
 * method lets a terminal request refuse every later step. The packet gate is separately
 * necessary: `clickWindow` may have started while the request was live and wait before it
 * writes. Both are restored only after the plugin's whole promise has settled.
 */
function scopedCrafting (bot) {
  const client = bot._client
  if (activeCraftGates.has(bot) ||
      typeof bot.clickWindow !== 'function' ||
      client === null || client === undefined ||
      typeof client.write !== 'function') return undefined

  const originalClick = bot.clickWindow
  const originalWrite = client.write
  let closed = false
  let latePacket = false
  let interrupt
  const pendingClicks = new Set()
  const interrupted = new Promise((resolve) => { interrupt = resolve })
  const gatedClick = (...args) => {
    if (closed) return Promise.reject(new Error('craft request ended'))
    const click = Promise.resolve().then(() => {
      if (closed) throw new Error('craft request ended')
      return originalClick.apply(bot, args)
    })
    pendingClicks.add(click)
    click.then(
      () => pendingClicks.delete(click),
      () => pendingClicks.delete(click)
    )
    return Promise.race([
      click,
      interrupted.then(() => {
        throw new Error('craft request ended')
      })
    ])
  }
  const gatedWrite = function (name, parameters) {
    if (closed && name === CRAFT_PACKET) {
      latePacket = true
      throw new Error('craft request ended')
    }
    return originalWrite.call(this, name, parameters)
  }
  bot.clickWindow = gatedClick
  client.write = gatedWrite
  activeCraftGates.add(bot)

  function restore () {
    if (bot.clickWindow === gatedClick) bot.clickWindow = originalClick
    if (client.write === gatedWrite) client.write = originalWrite
    activeCraftGates.delete(bot)
  }

  return {
    stop () {
      if (closed) return
      closed = true
      interrupt()
    },
    wait (running) {
      return Promise.race([
        running.then(() => true),
        interrupted.then(() => false)
      ])
    },
    async drain (running, graceMs) {
      const work = [running, ...pendingClicks]
      const settled = await Promise.race([
        Promise.allSettled(work).then(() => true),
        delay(graceMs).then(() => false)
      ])
      const safe = settled && !latePacket
      if (safe) restore()
      return safe
    },
    restore
  }
}

/** Ends an unsafe client whose stopped craft did not drain. */
function disconnectUndrainedCraft (bot) {
  try {
    if (typeof bot._client?.end === 'function') {
      bot._client.end()
      return
    }
    bot._client?.socket?.destroy?.()
  } catch {
    // The closed click and packet gates remain installed even if teardown throws.
  }
}

/** A stable private spelling of a recipe object. */
function recipeFingerprint (recipe) {
  function item (value) {
    if (value === null || value === undefined) return null
    return {
      id: value.id,
      metadata: value.metadata ?? null,
      count: value.count
    }
  }
  function shape (value) {
    return value === null || value === undefined
      ? null
      : value.map((row) => row.map(item))
  }
  try {
    return JSON.stringify({
      result: item(recipe.result),
      inShape: shape(recipe.inShape),
      ingredients: recipe.ingredients?.map(item) ?? null,
      outShape: shape(recipe.outShape),
      delta: recipe.delta?.map(item) ?? null,
      requiresTable: recipe.requiresTable === true
    })
  } catch {
    return undefined
  }
}

/**
 * The complete player inventory in both slot order and an item-detail multiset.
 *
 * The slot fingerprint catches any change before an application. The multiset lets the
 * result of an application be checked against the recipe delta without mistaking
 * Mineflayer's legitimate slot rearrangement for an outside inventory mutation.
 */
function inventoryState (bot) {
  const slots = bot.inventory?.slots
  if (!Array.isArray(slots)) return undefined
  const held = []
  const totals = new Map()
  for (let slot = 0; slot < slots.length; slot += 1) {
    const stack = slots[slot]
    if (stack === null || stack === undefined) continue
    const detail = stackDetail(stack)
    if (detail === undefined || !isResourceName(stack.name) ||
        !Number.isInteger(stack.type) ||
        !(stack.metadata === null || stack.metadata === undefined ||
          Number.isInteger(stack.metadata)) ||
        !Number.isInteger(stack.count) ||
        stack.count < 1 || stack.count > MAX_STACK_COUNT) return undefined
    const item = {
      type: stack.type,
      metadata: stack.metadata ?? null,
      what: stack.name,
      detail
    }
    held.push({ slot, ...item, count: stack.count })
    const key = JSON.stringify(item)
    const total = totals.get(key)
    totals.set(key, {
      ...item,
      count: (total?.count ?? 0) + stack.count
    })
  }
  return { fingerprint: JSON.stringify(held), totals }
}

/**
 * The inventory once it shows exactly one `delta` since `before`, read again every
 * [CRAFT_SETTLE_POLL_MS] for at most `settleMs`, or undefined when it never does or cannot be
 * read. `stopped` ends the wait early for a request that has already reached a terminal.
 */
async function settledApplication (bot, before, delta, {
  settleMs = CRAFT_SETTLE_MS,
  pollMs = CRAFT_SETTLE_POLL_MS,
  now = () => performance.now(),
  stopped = () => false
} = {}) {
  const deadline = now() + settleMs
  for (;;) {
    const after = inventoryState(bot)
    if (after !== undefined && inventoryMatchesRecipeDelta(before, after, delta)) return after
    if (stopped() || now() >= deadline) return undefined
    await delay(pollMs)
  }
}

/** The exact slot fingerprint retained by a sense offer. */
function inventoryFingerprint (bot) {
  return inventoryState(bot)?.fingerprint
}

/**
 * A recipe delta grouped by registry id.
 *
 * A recipe that both consumes and produces one registry id is not offered: with only
 * Mineflayer's aggregate delta there is no exact way to distinguish that transformation
 * from a same-id mutation performed by something else during the transaction.
 */
function recipeDeltaOf (recipe) {
  if (!Array.isArray(recipe.delta)) return undefined
  const grouped = new Map()
  for (const delta of recipe.delta) {
    if (!Number.isInteger(delta.id) || !Number.isInteger(delta.count) ||
        delta.count === 0 ||
        !(delta.metadata === null || delta.metadata === undefined ||
          Number.isInteger(delta.metadata))) return undefined
    const existing = grouped.get(delta.id)
    if (existing !== undefined && Math.sign(existing.count) !== Math.sign(delta.count)) {
      return undefined
    }
    const metadata = delta.metadata ?? null
    const next = existing ?? { count: 0, metadata: new Set() }
    next.count += delta.count
    next.metadata.add(metadata)
    grouped.set(delta.id, next)
  }
  return grouped.size === 0 ? undefined : grouped
}

/** Whether one resolved craft changed inventory by exactly one retained recipe delta. */
function inventoryMatchesRecipeDelta (before, after, delta) {
  const keys = new Set([...before.totals.keys(), ...after.totals.keys()])
  const changes = new Map()
  for (const key of keys) {
    const earlier = before.totals.get(key)
    const later = after.totals.get(key)
    const item = later ?? earlier
    const change = (later?.count ?? 0) - (earlier?.count ?? 0)
    const byType = changes.get(item.type) ?? []
    byType.push({ metadata: item.metadata, change })
    changes.set(item.type, byType)
  }
  const types = new Set([...changes.keys(), ...delta.keys()])
  for (const type of types) {
    const expected = delta.get(type)
    const actual = changes.get(type) ?? []
    const sum = actual.reduce((count, item) => count + item.change, 0)
    if (sum !== (expected?.count ?? 0)) return false
    if (expected === undefined) {
      if (actual.some((item) => item.change !== 0)) return false
      continue
    }
    const wildcard = expected.metadata.has(null)
    if (actual.some((item) =>
      item.change !== 0 &&
      (!wildcard && !expected.metadata.has(item.metadata)))) return false
    if (expected.count > 0 && actual.some((item) => item.change < 0)) return false
    if (expected.count < 0 && actual.some((item) => item.change > 0)) return false
  }
  return true
}

/** How many applications the current inventory can support, capped by the wire. */
function availableCrafts (bot, recipe) {
  if (!Array.isArray(recipe.delta) || bot.inventory === undefined) return undefined
  let available = MAX_CRAFTS
  try {
    for (const delta of recipe.delta) {
      if (!Number.isInteger(delta.id) || !Number.isInteger(delta.count)) return undefined
      if (delta.count >= 0) continue
      const count = bot.inventory.count(delta.id, delta.metadata)
      if (!Number.isInteger(count) || count < 0) return undefined
      available = Math.min(available, Math.floor(count / -delta.count))
    }
  } catch {
    return undefined
  }
  return available
}

/** The nearest visible crafting-table offer, already bounded by sense reach. */
function offeredTable (blockOffers) {
  return blockOffers.find((offer) =>
    offer?.what === 'crafting_table' && offer.visible === true
  )
}

/**
 * Recipes this exact sense may offer, plus their private authority.
 *
 * Returning undefined refuses the whole sense instead of silently truncating registry
 * work or hiding a recipe: an omission count is truthful only when every omitted
 * candidate was actually examined.
 */
function recipeReading (bot, { blockOffers, limit }) {
  const recipes = bot.registry?.recipes
  if (recipes === null || recipes === undefined ||
      typeof bot.recipesAll !== 'function') return undefined
  const entries = Object.entries(recipes)
  const candidateCount = entries.reduce(
    (count, [, candidates]) => count + (Array.isArray(candidates) ? candidates.length : 0),
    0
  )
  if (candidateCount > MAX_RECIPE_CANDIDATES) return undefined
  const inventory = inventoryFingerprint(bot)
  if (inventory === undefined) return undefined
  const table = offeredTable(blockOffers)
  const offers = []
  const seen = new Set()
  for (const [rawId] of entries.sort((one, other) => Number(one[0]) - Number(other[0]))) {
    const resultId = Number(rawId)
    if (!Number.isInteger(resultId)) continue
    let candidates
    try {
      candidates = bot.recipesAll(resultId, null, table === undefined ? null : {})
    } catch {
      return undefined
    }
    if (!Array.isArray(candidates)) return undefined
    for (const recipe of candidates) {
      const fingerprint = recipeFingerprint(recipe)
      const delta = recipeDeltaOf(recipe)
      const result = recipe?.result
      const item = bot.registry?.items?.[result?.id]
      const maxCrafts = availableCrafts(bot, recipe)
      if (fingerprint === undefined || delta === undefined || seen.has(fingerprint)) continue
      if (!isResourceName(item?.name) ||
          !Number.isInteger(result?.count) || result.count < 1 || result.count > 64) continue
      if (!Number.isInteger(maxCrafts) || maxCrafts < 1) continue
      if (recipe.requiresTable === true && table === undefined) continue
      seen.add(fingerprint)
      offers.push({
        wire: {
          what: item.name,
          resultCount: result.count,
          maxCrafts,
          requiresTable: recipe.requiresTable === true
        },
        retained: {
          recipe,
          delta,
          fingerprint,
          inventory,
          resultId: result.id,
          resultMetadata: result.metadata ?? null,
          what: item.name,
          resultCount: result.count,
          maxCrafts,
          table: recipe.requiresTable === true
            ? {
                what: table.what,
                position: [...table.position],
                type: table.type,
                stateId: table.stateId
              }
            : undefined
        }
      })
    }
  }
  offers.sort((one, other) =>
    one.wire.what.localeCompare(other.wire.what) ||
    one.wire.resultCount - other.wire.resultCount ||
    Number(one.wire.requiresTable) - Number(other.wire.requiresTable) ||
    one.retained.fingerprint.localeCompare(other.retained.fingerprint)
  )
  const reported = offers.slice(0, Math.min(limit, MAX_SENSE_RECIPES))
  return {
    recipes: reported.map((offer) => offer.wire),
    recipesOmitted: offers.length - reported.length,
    recipeOffers: reported.map((offer) => offer.retained)
  }
}

/** The exact retained crafting table as it exists now. */
function liveTable (bot, offered) {
  if (offered === undefined) return { table: null }
  let block
  try {
    block = bot.blockAt(new Vec3(...offered.position))
  } catch {
    return undefined
  }
  if (block === null || block === undefined || block.name === 'air') {
    return { outcome: 'workstationGone' }
  }
  if (block.name !== offered.what ||
      block.type !== offered.type ||
      block.stateId !== offered.stateId) {
    return { outcome: 'workstationChanged' }
  }
  const inReach = blockWithinReach(bot, block)
  if (inReach === undefined) return undefined
  if (!inReach) return { outcome: 'workstationOutOfReach' }
  const visible = visibleBlock(bot, block)
  if (visible === undefined) return undefined
  if (!visible) return { outcome: 'workstationNotVisible' }
  return { table: block }
}

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

/** Whether Mineflayer still offers this exact recipe for the requested quantity. */
function recipeAvailable (bot, offer, crafts, table) {
  let recipes
  try {
    recipes = bot.recipesFor(
      offer.resultId,
      offer.resultMetadata,
      offer.resultCount * crafts,
      table
    )
  } catch {
    return undefined
  }
  return Array.isArray(recipes) &&
    recipes.some((recipe) => recipeFingerprint(recipe) === offer.fingerprint)
}

/**
 * Applies one retained recipe a bounded number of times.
 *
 * Mineflayer exposes no cancellation primitive for a craft already inside a window
 * transaction. Cancellation closes the window and the request's click/packet gate,
 * then holds ownership through a bounded drain. If Mineflayer still has not settled,
 * the answer is delivered with the gate closed and the client is disconnected in the
 * next microtask, before any later request can share its unsafe inventory state.
 */
function craftOfferedRecipe (bot, {
  offered,
  recipe: index,
  crafts,
  timeoutMs,
  cleanupGraceMs = CRAFT_CLEANUP_GRACE_MS,
  now = () => performance.now()
}) {
  const startedAt = now()
  if (offered === undefined) return refused('senseForgotten', startedAt, now)
  const offer = offered[index]
  if (offer === undefined) return refused('recipeUnnamed', startedAt, now)
  if (inventoryFingerprint(bot) !== offer.inventory) {
    return refused('ingredientsChanged', startedAt, now)
  }
  const table = liveTable(bot, offer.table)
  if (table === undefined) return undefined
  if (table.outcome !== undefined) return refused(table.outcome, startedAt, now)
  const available = recipeAvailable(bot, offer, crafts, table.table)
  if (available === undefined) return undefined
  if (!available || crafts > offer.maxCrafts) {
    return refused('recipeUnavailable', startedAt, now)
  }
  if (typeof bot.craft !== 'function') return undefined
  const gate = scopedCrafting(bot)
  if (gate === undefined) return undefined

  let settled = false
  let attempted = false
  let expectedInventory = offer.inventory
  let terminal
  let activeCraft
  let abort
  const promise = new Promise((resolve) => {
    const timer = setTimeout(() => requestTerminal('timedOut'), timeoutMs)

    function closeCraftWindow () {
      try {
        if (bot.currentWindow !== null && bot.currentWindow !== undefined) {
          bot.closeWindow(bot.currentWindow)
        }
      } catch {
        // The closed outcome below carries all a controller may learn.
      }
    }

    function requestTerminal (outcome) {
      if (settled || terminal !== undefined) return false
      terminal = outcome
      gate.stop()
      closeCraftWindow()
      if (activeCraft === undefined) finish(outcome)
      return true
    }

    function finish (outcome, restore = true) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      applicationSettled(bot)
      if (restore) gate.restore()
      resolve({
        outcome,
        elapsedMs: elapsedSince(startedAt, now),
        attempted,
        ...(outcome === 'crafted'
          ? {
              what: offer.what,
              count: offer.resultCount * crafts,
              crafts
            }
          : {})
      })
    }

    abort = (outcome = 'cancelled') => requestTerminal(outcome)

    Promise.resolve().then(async () => {
      for (let completed = 0; completed < crafts; completed += 1) {
        if (settled || terminal !== undefined) break
        const inventory = inventoryState(bot)
        if (inventory === undefined) {
          terminal = 'libraryFailure'
          break
        }
        if (inventory.fingerprint !== expectedInventory) {
          terminal = 'ingredientsChanged'
          break
        }
        const currentTable = liveTable(bot, offer.table)
        if (currentTable === undefined) {
          terminal = 'libraryFailure'
          break
        }
        if (currentTable.outcome !== undefined) {
          terminal = currentTable.outcome
          break
        }
        const stillAvailable = recipeAvailable(bot, offer, 1, currentTable.table)
        if (stillAvailable !== true) {
          terminal = stillAvailable === false ? 'recipeUnavailable' : 'libraryFailure'
          break
        }
        attempted = true
        applicationStarted(bot)
        activeCraft = Promise.resolve().then(
          () => bot.craft(offer.recipe, 1, currentTable.table)
        )
        let completedCraft
        try {
          completedCraft = await gate.wait(activeCraft)
        } catch {
          if (terminal === undefined) throw new Error('craft failed')
          completedCraft = false
        }
        if (!completedCraft || terminal !== undefined) {
          const drained = await gate.drain(activeCraft, cleanupGraceMs)
          activeCraft = undefined
          if (drained) return finish(terminal ?? 'libraryFailure')
          finish(terminal ?? 'libraryFailure', false)
          queueMicrotask(() => disconnectUndrainedCraft(bot))
          return
        }
        activeCraft = undefined
        const resultingInventory = await settledApplication(bot, inventory, offer.delta, {
          now,
          stopped: () => settled || terminal !== undefined
        })
        // The client now states what the server has confirmed, so a waiting sense reads here, and
        // the next application waits until the server has sampled the state that sense described.
        const served = applicationSettled(bot)
        if (served > 0 && completed + 1 < crafts) await delay(SENSE_BOUNDARY_HOLD_MS)
        if (settled || terminal !== undefined) break
        if (resultingInventory === undefined) {
          terminal = 'libraryFailure'
          break
        }
        expectedInventory = resultingInventory.fingerprint
      }
      finish(terminal ?? 'crafted')
    }).catch(() => {
      if (activeCraft === undefined) return finish(terminal ?? 'libraryFailure')
      gate.drain(activeCraft, cleanupGraceMs).then((drained) => {
        activeCraft = undefined
        if (drained) {
          finish(terminal ?? 'libraryFailure')
        } else {
          finish(terminal ?? 'libraryFailure', false)
          queueMicrotask(() => disconnectUndrainedCraft(bot))
        }
      })
    })
  })

  return {
    promise,
    cancel: () => abort?.('cancelled') ?? false,
    abort: (outcome) => abort?.(outcome) ?? false
  }
}

module.exports = {
  CRAFT_CLEANUP_GRACE_MS,
  CRAFT_SETTLE_MS,
  MAX_RECIPE_CANDIDATES,
  SENSE_BOUNDARY_HOLD_MS,
  SENSE_SETTLE_MAX_MS,
  craftOfferedRecipe,
  installCrafting,
  inventoryFingerprint,
  inventoryState,
  recipeFingerprint,
  recipeReading,
  settledApplication,
  whenInventorySettled
}
