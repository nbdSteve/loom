'use strict'

/**
 * Real body processes for the JVM's offline goal-scenario acceptance test. Not a suite.
 *
 * Takes one JSON scene argument naming the agents, where each stands, the blocks on the stone
 * floor at y 63 - five chunk columns across - the creatures and dropped items in view, what
 * each body carries, and the world border the server states. Starts one real body
 * per agent, each driving real Mineflayer with the real pathfinder and physics over the fake
 * protocol client, on its own loopback control port. Prints one `{"ready":[...]}` line, then one `{"wrote":...}` line per
 * dig packet a client writes - the fake server's view of the wire.
 *
 * With `serveBreaks` the script is also the shared server for digs: the first finish written
 * for a block is the one break it takes, which it reports as a `{"broke":...}` line and then
 * broadcasts to every client, the way vanilla answers a finished dig to the digger and to every
 * player tracking the chunk; with `dropsOnBreak` the breaker picks its drop up at once. With
 * `servePlaces` a held block placed lands where its face points, reported as `{"placed":...}`, and
 * the hand loses one. With `serveCrafts` it also answers crafts in the player's own grid.
 * Stops every body when stdin closes.
 */

const { Vec3 } = require('vec3')

const {
  Chunk, Item, OVERWORLD, clockReported, embodied, positionPacket, registry, settled, spawned, spotted, stack, stocked
} = require('../harness')

// How long the shared server takes to answer a finished dig, so a crossing finish can land first.
const SERVER_ANSWER_MS = 50
// Chunk columns loaded on each side of the origin column unless the scene says otherwise: room
// for a full-distance flight. A scene whose search must exhaust uses fewer.
const COLUMNS = 2
const FLOOR_Y = 63

/**
 * A floor at y 63 - stone unless the scene names its block - across every loaded column, as wide as
 * a flight may run, with the scene's `depth` layers under it, top first.
 */
function floor (bot, columns, top = 'stone', depth = []) {
  const layers = [top, ...depth].map((name, index) => [FLOOR_Y - index, registry.blocksByName[name].defaultState])
  for (let cx = -columns; cx <= columns; cx += 1) {
    for (let cz = -columns; cz <= columns; cz += 1) {
      const column = new Chunk(OVERWORLD)
      for (let x = 0; x < 16; x += 1) {
        for (let z = 0; z < 16; z += 1) {
          for (const [y, state] of layers) column.setBlockStateId(new Vec3(x, y, z), state)
        }
      }
      bot.world.setColumn(cx, cz, column)
    }
  }
}

/** The item a bare-hand break of each block gives, as vanilla's loot tables give it. */
const DROPS = Object.freeze({ grass_block: 'dirt', dirt: 'dirt', oak_log: 'oak_log', dead_bush: 'stick' })

/** The block a `block_place` face points the placement into, as Mineflayer numbers the faces. */
const FACES = Object.freeze([[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]])

/**
 * Hands [peer] the stack a server pickup would: [what] added to the hand's hotbar slot when it is
 * empty or holds the same item, else to the first empty slot of the hotbar and then the inventory.
 */
function given (peer, what) {
  const slots = peer.bot.inventory.slots
  const order = [36, 37, 38, 39, 40, 41, 42, 43, 44, ...Array.from({ length: 27 }, (_, index) => 9 + index)]
  const slot = order.find((at) => slots[at]?.name === what) ?? order.find((at) => slots[at] == null)
  if (slot === undefined) return
  const count = (slots[slot]?.count ?? 0) + 1
  peer.client.emit('set_slot', { windowId: 0, stateId: 1, slot, item: Item.toNotch(stack(what, count)) })
}

/**
 * The server half of a crafting transaction in the player's own 2x2 grid, as
 * `test/native-craft-terminal.test.js` answers it: each grid click is answered on a later turn
 * with the result vanilla's matcher gives the grid so far, and the player's slots are restated
 * when the grid closes. The recipe each craft expects is the one the body hands Mineflayer.
 */
function serveCrafts (bot, client) {
  let stateId = 10
  let expected = Infinity
  let result = null
  const craft = bot.craft
  bot.craft = function (recipe, count, table) {
    expected = recipe.inShape
      ? recipe.inShape.flat().filter((cell) => cell.id !== -1).length
      : recipe.ingredients.length
    result = new Item(recipe.result.id, recipe.result.count, recipe.result.metadata ?? null)
    return craft.call(this, recipe, count, table)
  }
  const write = client.write
  client.write = function (name, parameters) {
    const answered = write.call(this, name, parameters)
    if (name === 'window_click' && parameters.windowId === 0 && [1, 2, 3, 4].includes(parameters.slot)) {
      const filled = [1, 2, 3, 4].filter((slot) => bot.inventory.slots[slot] != null).length
      const item = filled >= expected ? result : null
      setImmediate(() => client.emit('set_slot', {
        windowId: 0, stateId: ++stateId, slot: 0, item: Item.toNotch(item ?? null)
      }))
    } else if (name === 'close_window') {
      setImmediate(() => {
        for (let slot = 9; slot < 45; slot += 1) {
          client.emit('set_slot', { windowId: 0, stateId: ++stateId, slot, item: Item.toNotch(bot.inventory.slots[slot] ?? null) })
        }
      })
    }
    return answered
  }
}

function emit (line) {
  process.stdout.write(`${JSON.stringify(line)}\n`)
}

function keyOf ({ x, y, z }) {
  return `${x},${y},${z}`
}

async function main () {
  const scene = JSON.parse(process.argv[2])
  const started = []
  const broken = new Set()
  const serve = (agentId, location) => {
    if (broken.has(keyOf(location))) return
    broken.add(keyOf(location))
    const breaker = started.find((peer) => peer.agentId === agentId)
    const name = breaker?.bot.blockAt(new Vec3(location.x, location.y, location.z))?.name
    emit({ broke: { agentId, ...location } })
    setTimeout(() => {
      for (const { client } of started) client.emit('block_change', { location: { ...location }, type: 0 })
      // With `dropsOnBreak` the breaker picks its drop up at once, as one standing on it does.
      if (scene.dropsOnBreak && breaker !== undefined && DROPS[name] !== undefined) given(breaker, DROPS[name])
    }, SERVER_ANSWER_MS)
  }
  /** Answers one `block_place` the way a server does: the held block lands, and the hand loses one. */
  const placed = (peer, { location, direction }) => {
    const face = FACES[direction]
    const held = peer.bot.heldItem
    const state = held === null ? undefined : registry.blocksByName[held.name]?.defaultState
    if (face === undefined || state === undefined) return
    const at = { x: location.x + face[0], y: location.y + face[1], z: location.z + face[2] }
    emit({ placed: { agentId: peer.agentId, ...at, name: held.name } })
    const slot = 36 + peer.bot.quickBarSlot
    const item = held.count > 1 ? Item.toNotch(stack(held.name, held.count - 1)) : Item.toNotch(null)
    setTimeout(() => {
      for (const { client } of started) client.emit('block_change', { location: at, type: state })
      peer.client.emit('set_slot', { windowId: 0, stateId: 1, slot, item })
    }, SERVER_ANSWER_MS)
  }
  for (const { agentId, username, feet } of scene.agents) {
    const peer = await embodied({ agentId, username })
    const write = peer.client.write
    peer.client.write = (name, parameters) => {
      // A server picks up a dropped item a body comes within reach of, as vanilla does.
      if (scene.servePickups && ['position', 'position_look'].includes(name) && Number.isFinite(parameters?.x)) {
        for (const item of scene.items ?? []) {
          if (item.taken || Math.hypot(parameters.x - item.x, parameters.z - item.z) > 1.5 ||
            Math.abs(parameters.y - item.y) > 1) continue
          item.taken = true
          const collector = peer.bot.entity?.id
          setTimeout(() => {
            peer.client.emit('collect', { collectedEntityId: item.id, collectorEntityId: collector, pickupItemCount: item.count })
            peer.client.emit('entity_destroy', { entityIds: [item.id] })
          }, SERVER_ANSWER_MS)
        }
      }
      if (name === 'block_dig') {
        const { location, status } = parameters
        emit({ wrote: { agentId, status, x: location.x, y: location.y, z: location.z } })
        if (scene.serveBreaks && status === 2) serve(agentId, { x: location.x, y: location.y, z: location.z })
      }
      if (name === 'block_place' && scene.servePlaces) placed(peer, parameters)
      return write(name, parameters)
    }
    spawned(peer.client, scene.vitals)
    // A server names the held hotbar slot at login, so the hand holds what slot 36 holds.
    peer.client.emit('held_item_slot', { slot: 0 })
    clockReported(peer.client, { timeOfDay: scene.timeOfDay ?? 6000, day: 1 })
    if (scene.border != null) {
      const { x, z, diameter } = scene.border
      peer.client.emit('initialize_world_border', { x, z, oldDiameter: diameter, newDiameter: diameter, speed: 0 })
    }
    peer.client.emit('position', positionPacket({ ...feet, yaw: 0, pitch: 0, teleportId: 1 }))
    floor(peer.bot, scene.columns ?? COLUMNS, scene.floor ?? 'stone', scene.depth ?? [])
    for (const { x, y, z, name } of scene.blocks ?? []) {
      peer.bot.world.setBlockStateId(new Vec3(x, y, z), registry.blocksByName[name].defaultState)
    }
    for (const entity of scene.entities ?? []) spotted(peer.client, entity)
    // A dropped item, spawned and described the way a server sends one.
    for (const { id, what, count, x, y, z } of scene.items ?? []) {
      spotted(peer.client, { id, what: 'item', x, y, z })
      peer.client.emit('entity_metadata', {
        entityId: id,
        metadata: [{ key: 8, type: 'item_stack', value: Item.toNotch(stack(what, count)) }]
      })
    }
    if (scene.serveCrafts) serveCrafts(peer.bot, peer.client)
    if (scene.inventory !== undefined) {
      stocked(peer.client, scene.inventory.map(({ slot, what, count }) => [slot, what, count]))
    }
    started.push({ agentId, username, port: peer.port, body: peer.body, client: peer.client, bot: peer.bot })
    peer.agentId = agentId
  }
  await settled(300)
  emit({ ready: started.map(({ agentId, username, port }) => ({ agentId, username, port })) })
  process.stdin.resume()
  process.stdin.on('end', async () => {
    for (const { body } of started) await body.stop()
    process.exit(0)
  })
}

main().catch((failure) => {
  process.stderr.write(`${failure.stack}\n`)
  process.exit(1)
})
