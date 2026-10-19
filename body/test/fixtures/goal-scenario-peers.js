'use strict'

/**
 * Real body processes for the JVM's offline goal-scenario acceptance test. Not a suite.
 *
 * Takes one JSON scene argument naming the agents, where each stands, the blocks on the stone
 * floor at y 63 - five chunk columns across - the creatures and dropped items in view, and what
 * each body carries. Starts one real body
 * per agent, each driving real Mineflayer with the real pathfinder and physics over the fake
 * protocol client, on its own loopback control port. Prints one `{"ready":[...]}` line, then one `{"wrote":...}` line per
 * dig packet a client writes - the fake server's view of the wire.
 *
 * With `serveBreaks` the script is also the shared server for digs: the first finish written
 * for a block is the one break it takes, which it reports as a `{"broke":...}` line and then
 * broadcasts to every client, the way vanilla answers a finished dig to the digger and to every
 * player tracking the chunk. With `serveCrafts` it also answers crafts in the player's own grid.
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

/** A stone floor at y 63 across every loaded column, as wide as a flight may run. */
function floor (bot, columns) {
  const stone = registry.blocksByName.stone.defaultState
  for (let cx = -columns; cx <= columns; cx += 1) {
    for (let cz = -columns; cz <= columns; cz += 1) {
      const column = new Chunk(OVERWORLD)
      for (let x = 0; x < 16; x += 1) {
        for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, FLOOR_Y, z), stone)
      }
      bot.world.setColumn(cx, cz, column)
    }
  }
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
    emit({ broke: { agentId, ...location } })
    setTimeout(() => {
      for (const { client } of started) client.emit('block_change', { location: { ...location }, type: 0 })
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
      return write(name, parameters)
    }
    spawned(peer.client)
    clockReported(peer.client, { timeOfDay: scene.timeOfDay ?? 6000, day: 1 })
    peer.client.emit('position', positionPacket({ ...feet, yaw: 0, pitch: 0, teleportId: 1 }))
    floor(peer.bot, scene.columns ?? COLUMNS)
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
    started.push({ agentId, username, port: peer.port, body: peer.body, client: peer.client })
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
