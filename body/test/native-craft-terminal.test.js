'use strict'

/**
 * Craft driven to its real terminal outcomes, offline, against a real Mineflayer bot.
 *
 * The body process runs around a real `mineflayer.createBot` fed real 1.21.1 packets by
 * the harness's fake protocol client. What this file adds is a *window responder*: the
 * half of a vanilla server that answers a crafting transaction. It opens a crafting-table
 * window when the bot uses the table, restates its slots with `window_items`, answers every
 * grid click with the result slot vanilla would compute, and restates the player's own
 * slots once the window closes. Nothing in the body or in Mineflayer is stubbed, so the
 * `crafted` report is reached by Mineflayer's own craft plugin under Loom's own gate.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')
const { Vec3 } = require('vec3')

const H = require('./harness')
const { SENSE_BOUNDARY_HOLD_MS } = require('../src/crafting')

afterEach(H.disconnected)

const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })
const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const SLOT = 20
const TABLE = Object.freeze({ x: 10, y: 64, z: 8 })
const CRAFTING_WINDOW_TYPE = 12
const TABLE_WINDOW = 3

let openBody = null
afterEach(async () => {
  if (openBody !== null) await openBody.stop()
  openBody = null
})

function notch (item) {
  return H.Item.toNotch(item ?? null)
}

/**
 * The server half of a crafting transaction.
 *
 * A grid click is answered on a later turn, because Mineflayer subscribes to the result
 * slot only after it has written the click. The result is the retained recipe's own once
 * the grid holds every ingredient the recipe names, and empty before, which is what
 * vanilla's recipe matcher answers for a partly filled grid.
 */
function windowResponder (bot, client, { answerGrid = true, onGridClick = () => {}, answerDelayMs = 0 } = {}) {
  const original = client.write
  const seen = []
  let stateId = 10
  let expected = null
  let result = null
  client.write = function (name, parameters) {
    seen.push({ name, parameters })
    const answered = original.call(this, name, parameters)
    if (name === 'block_place') {
      const at = parameters.location
      if (at.x === TABLE.x && at.y === TABLE.y && at.z === TABLE.z &&
          bot.blockAt(new Vec3(at.x, at.y, at.z))?.name === 'crafting_table') {
        setImmediate(() => openTable())
      }
    } else if (name === 'window_click') {
      const window = parameters.windowId === 0 ? bot.inventory : bot.currentWindow
      const grid = parameters.windowId === 0 ? [1, 2, 3, 4] : [1, 2, 3, 4, 5, 6, 7, 8, 9]
      if (grid.includes(parameters.slot)) {
        onGridClick(parameters)
        if (answerGrid) {
          const filled = grid.filter((slot) => window.slots[slot] !== null && window.slots[slot] !== undefined).length
          const item = filled >= expected ? result : null
          const restate = () => client.emit('set_slot', {
            windowId: parameters.windowId, stateId: ++stateId, slot: 0, item: notch(item)
          })
          if (answerDelayMs > 0) setTimeout(restate, answerDelayMs)
          else setImmediate(restate)
        }
      }
    } else if (name === 'close_window') {
      setImmediate(() => restateInventory())
    }
    return answered
  }

  function openTable () {
    client.emit('open_window', {
      windowId: TABLE_WINDOW,
      inventoryType: CRAFTING_WINDOW_TYPE,
      windowTitle: JSON.stringify({ translate: 'container.crafting' })
    })
    const items = []
    for (let slot = 0; slot < 46; slot += 1) {
      // Window slots 10..45 are the player's own 9..44.
      items.push(notch(slot >= 10 ? bot.inventory.slots[slot - 1] : null))
    }
    client.emit('window_items', { windowId: TABLE_WINDOW, stateId: ++stateId, items, carriedItem: notch(null) })
  }

  function restateInventory () {
    for (let slot = 9; slot < 45; slot += 1) {
      client.emit('set_slot', { windowId: 0, stateId: ++stateId, slot, item: notch(bot.inventory.slots[slot]) })
    }
  }

  return {
    seen,
    expect (recipe) {
      expected = recipe.inShape
        ? recipe.inShape.flat().filter((cell) => cell.id !== -1).length
        : recipe.ingredients.length
      result = new H.Item(recipe.result.id, recipe.result.count, recipe.result.metadata ?? null)
    },
    clicksAfter (index) {
      return seen.slice(index).filter((packet) => packet.name === 'window_click').length
    }
  }
}

async function scene ({ stacks, table = false } = {}) {
  const given = await H.embodied()
  const { body, bot, client, port } = given
  openBody = body
  H.spawned(client, FED)
  H.clockReported(client, { timeOfDay: 6000, day: 2 })
  client.emit('position', H.positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  H.ground(bot)
  if (table) {
    bot.world.setBlockStateId(new Vec3(TABLE.x, TABLE.y, TABLE.z),
      H.registry.blocksByName.crafting_table.defaultState)
  }
  H.stocked(client, stacks)
  await H.settled(200)
  const peer = await H.handshaken(port)
  return { ...given, peer }
}

async function sensed (peer, requestId = 's1') {
  const answer = await peer.answer(H.request('sense', requestId, { radius: 16, limit: 48 }))
  assert.equal(answer.op, 'sense', JSON.stringify(answer))
  return answer
}

/** The retained Mineflayer recipe behind one offer, read back for the responder only. */
function recipeBehind (bot, what, requiresTable) {
  const id = H.registry.itemsByName[what].id
  const recipes = bot.recipesAll(id, null, requiresTable ? true : null)
  const recipe = recipes.find((one) => (one.requiresTable === true) === requiresTable)
  assert.ok(recipe, `a ${what} recipe exists`)
  return recipe
}

function countOf (bot, what) {
  return bot.inventory.count(H.registry.itemsByName[what].id, null)
}

function offerOf (answer, what, requiresTable) {
  const index = answer.recipes.findIndex((recipe) =>
    recipe.what === what && recipe.requiresTable === requiresTable)
  assert.ok(index >= 0, `${what} offered: ${JSON.stringify(answer.recipes)}`)
  return index
}

test('craft: a held recipe reaches crafted through real grid clicks and server result slots', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_log', 2]] })
  const server = windowResponder(bot, client)
  const answer = await sensed(peer)
  const planks = offerOf(answer, 'oak_planks', false)
  assert.equal(answer.recipes[planks].maxCrafts, 2)
  server.expect(recipeBehind(bot, 'oak_planks', false))
  const report = await peer.answer(H.request('craft', 'c1', {
    senseId: 's1', recipe: planks, crafts: 2, timeoutMs: 6000
  }))
  assert.equal(report.outcome, 'crafted', JSON.stringify(report))
  assert.equal(report.what, 'oak_planks')
  assert.equal(report.count, 8)
  assert.equal(report.crafts, 2)
  assert.equal(report.attempted, true)
  assert.ok(server.clicksAfter(0) >= 4, 'each application clicked the grid and the result')
  assert.equal(countOf(bot, 'oak_log'), 0)
  assert.equal(countOf(bot, 'oak_planks'), 8)
  const after = await sensed(peer, 's2')
  const planksHeld = after.inventory.filter((entry) => entry.what === 'oak_planks')
    .reduce((count, entry) => count + entry.count, 0)
  assert.equal(planksHeld, 8, JSON.stringify(after.inventory))
  peer.stop()
})

test('craft: a workstation recipe opens the exact retained table window and reaches crafted', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_planks', 6]], table: true })
  const server = windowResponder(bot, client)
  const answer = await sensed(peer)
  const stairs = offerOf(answer, 'oak_stairs', true)
  server.expect(recipeBehind(bot, 'oak_stairs', true))
  const report = await peer.answer(H.request('craft', 'c-table', {
    senseId: 's1', recipe: stairs, crafts: 1, timeoutMs: 6000
  }))
  assert.equal(report.outcome, 'crafted', JSON.stringify(report))
  assert.equal(report.count, 4)
  const used = server.seen.filter((packet) => packet.name === 'block_place')
  assert.equal(used.length, 1)
  assert.deepEqual([used[0].parameters.location.x, used[0].parameters.location.y, used[0].parameters.location.z],
    [TABLE.x, TABLE.y, TABLE.z])
  assert.ok(server.seen.some((packet) => packet.name === 'window_click' && packet.parameters.windowId === TABLE_WINDOW))
  assert.ok(server.seen.some((packet) => packet.name === 'close_window'), 'the table window is closed')
  assert.equal(bot.currentWindow, null)
  assert.equal(countOf(bot, 'oak_stairs'), 4)
  assert.equal(countOf(bot, 'oak_planks'), 0)
  peer.stop()
})

test('craft: a changed or removed workstation after sense fails closed with no click', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_planks', 12]], table: true })
  const server = windowResponder(bot, client)
  const answer = await sensed(peer)
  const stairs = offerOf(answer, 'oak_stairs', true)
  client.emit('block_change', {
    location: TABLE, type: H.registry.blocksByName.stone.defaultState
  })
  await H.settled()
  const changed = await peer.answer(H.request('craft', 'c-changed', {
    senseId: 's1', recipe: stairs, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(changed.outcome, 'workstationChanged', JSON.stringify(changed))
  assert.equal(changed.attempted, false)
  client.emit('block_change', { location: TABLE, type: H.registry.blocksByName.air.defaultState })
  await H.settled()
  const gone = await peer.answer(H.request('craft', 'c-gone', {
    senseId: 's1', recipe: stairs, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(gone.outcome, 'workstationGone', JSON.stringify(gone))
  assert.equal(server.clicksAfter(0), 0)
  assert.equal(server.seen.filter((packet) => packet.name === 'block_place').length, 0)
  peer.stop()
})

test('craft: a quantity past the offer and changed ingredients fail closed before any click', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_log', 1]] })
  const server = windowResponder(bot, client)
  const answer = await sensed(peer)
  const planks = offerOf(answer, 'oak_planks', false)
  assert.equal(answer.recipes[planks].maxCrafts, 1)
  const greedy = await peer.answer(H.request('craft', 'c-greedy', {
    senseId: 's1', recipe: planks, crafts: 2, timeoutMs: 4000
  }))
  assert.equal(greedy.outcome, 'recipeUnavailable', JSON.stringify(greedy))
  assert.equal(greedy.attempted, false)
  client.emit('set_slot', { windowId: 0, stateId: 99, slot: SLOT, item: notch(H.stack('oak_log', 2)) })
  await H.settled()
  const changed = await peer.answer(H.request('craft', 'c-changed', {
    senseId: 's1', recipe: planks, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(changed.outcome, 'ingredientsChanged', JSON.stringify(changed))
  const unnamed = await peer.answer(H.request('craft', 'c-unnamed', {
    senseId: 's1', recipe: answer.recipes.length, crafts: 1, timeoutMs: 4000
  }))
  assert.equal(unnamed.outcome, 'recipeUnnamed', JSON.stringify(unnamed))
  assert.equal(server.clicksAfter(0), 0)
  peer.stop()
})

test('craft: an outside inventory change between applications stops before the second application', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_log', 2]] })
  let injected = false
  let gridClicks = 0
  const server = windowResponder(bot, client, {
    onGridClick () {
      gridClicks += 1
      if (injected) return
      injected = true
      // The server reports an unrelated stack arriving, after the first grid click.
      setImmediate(() => client.emit('set_slot', {
        windowId: 0, stateId: 200, slot: 30, item: notch(H.stack('dirt', 1))
      }))
    }
  })
  const answer = await sensed(peer)
  const planks = offerOf(answer, 'oak_planks', false)
  server.expect(recipeBehind(bot, 'oak_planks', false))
  const report = await peer.answer(H.request('craft', 'c-mutated', {
    senseId: 's1', recipe: planks, crafts: 2, timeoutMs: 6000
  }))
  assert.notEqual(report.outcome, 'crafted', JSON.stringify(report))
  assert.equal(report.outcome, 'libraryFailure', JSON.stringify(report))
  assert.equal(report.attempted, true)
  assert.equal(gridClicks, 1, 'no second application was started')
  peer.stop()
})

test('craft: a server that never answers a grid click times out and the undrained client is ended', async () => {
  const { peer, bot, client } = await scene({ stacks: [[SLOT, 'oak_log', 1]] })
  const server = windowResponder(bot, client, { answerGrid: false })
  const answer = await sensed(peer)
  const planks = offerOf(answer, 'oak_planks', false)
  server.expect(recipeBehind(bot, 'oak_planks', false))
  const ended = new Promise((resolve) => client.once('end', resolve))
  const startedAt = Date.now()
  const report = await H.promptly(peer.answer(H.request('craft', 'c-stall', {
    senseId: 's1', recipe: planks, crafts: 1, timeoutMs: 300
  })), 'a bounded craft answer', 4000)
  assert.equal(report.outcome, 'timedOut', JSON.stringify(report))
  assert.equal(report.attempted, true)
  assert.ok(Date.now() - startedAt < 300 + 500 + 1000, 'answered inside timeout plus cleanup grace')
  const clicksAtAnswer = server.clicksAfter(0)
  await H.promptly(ended, 'the undrained Minecraft client ending', 2000)
  await H.settled(200)
  assert.equal(server.clicksAfter(0), clicksAtAnswer, 'no click is written after the terminal answer')
  peer.stop()
})

test('craft: a sense taken mid-application is read at the settled boundary, never with the grid occupied', async () => {
  // r29 beta (tick 2946): a sense answered between the clicks of its own yellow-dye craft claimed
  // a dandelion in crafting-grid slot 4, which the server only passed through inside one tick.
  const { peer, bot, client } = await scene({ stacks: [[36, 'dandelion', 2]] })
  let asked = false
  const clicks = []
  const server = windowResponder(bot, client, {
    answerDelayMs: 150,
    onGridClick () {
      clicks.push(Date.now())
      if (asked) return
      asked = true
      setImmediate(() => peer.send(H.request('sense', 's-mid', { radius: 16, limit: 48 })))
    }
  })
  const answer = await sensed(peer)
  const dye = offerOf(answer, 'yellow_dye', false)
  server.expect(recipeBehind(bot, 'yellow_dye', false))
  peer.send(H.request('craft', 'c-dye', { senseId: 's1', recipe: dye, crafts: 2, timeoutMs: 6000 }))
  const first = await peer.read()
  const answeredAt = Date.now()
  const frames = [first, await peer.read()]
  const mid = frames.find((frame) => frame.requestId === 's-mid')
  assert.equal(first.requestId, 's-mid', 'the sense is answered before the craft ends')
  // r32 eta: the next application waits until the server has sampled the boundary it described.
  const next = clicks.find((at) => at > answeredAt)
  assert.ok(next !== undefined && next - answeredAt >= SENSE_BOUNDARY_HOLD_MS - 20, `${next - answeredAt} ms`)
  const report = frames.find((frame) => frame.requestId === 'c-dye')
  assert.equal(report.outcome, 'crafted', JSON.stringify(report))
  assert.equal(mid.op, 'sense', JSON.stringify(mid))
  assert.deepEqual(mid.inventory.filter((entry) => entry.slot >= 0 && entry.slot <= 4), [],
    `no stack in the crafting window: ${JSON.stringify(mid.inventory)}`)
  // A settled state: one application applied, its dandelion consumed and its dye held.
  const count = (what) => mid.inventory.filter((entry) => entry.what === what)
    .reduce((sum, entry) => sum + entry.count, 0)
  assert.deepEqual([count('dandelion'), count('yellow_dye')], [1, 1], JSON.stringify(mid.inventory))
  peer.stop()
})
