'use strict'

/**
 * The plugin policy, proven against the real Mineflayer loader.
 *
 * `body.test.js` proves what options this process asks for; this file proves what
 * those options actually load. The two are different claims, and only the second one
 * catches the failure that matters: a plugin whose name looks harmless and which
 * writes a packet back at the server on a packet the server chose to send.
 *
 * So the real `mineflayer.createBot` runs here, with the real options
 * `botOptionsFor` produces and the real plugin loader, over a fake
 * minecraft-protocol client; `test/harness.js` is that harness and records why the
 * client is fake.
 *
 * It is also where `body/src/gamestate.js` is proven, for the same reason: what that
 * file replaces is two Mineflayer plugins, and a replacement is only as good as the
 * library it has to satisfy. Every case below drives it through the real loader and
 * the real registry rather than through a fake of its own.
 */

const assert = require('node:assert/strict')
const { test } = require('node:test')

const conversions = require('mineflayer/lib/conversions')

const {
  Item,
  QUICK_BAR_START,
  WATCHED_MS,
  clockReported,
  embodied,
  ground,
  handshaken,
  loadedBot,
  loginPacket,
  positionPacket,
  promptly,
  registry,
  registryPacket,
  request,
  settled,
  spawned,
  stack,
  stocked
} = require('./harness')

/**
 * How far a facing may drift and still be the server's own answer.
 *
 * Facing is kept in radians and reported in degrees, so a round trip through
 * `bot.entity` is two float conversions and lands within an ulp or two rather than on
 * the number exactly. What the assertions are about is that the value is the server's
 * report and not the zero this process used to send, and that gap is a hundred and
 * eighty degrees wide.
 */
const FACING_TOLERANCE_DEGREES = 1e-6

/**
 * The actions the two reporting plugins define beside the state they are loaded for.
 *
 * `entities` brings `attack`, `mount` and `useOn` with the entity index; `simple_inventory`
 * brings `toss` and `tossStack` with the `equip` a meal is carried out through. Neither
 * plugin can be left behind, so this is the exact surface a five-operation wire has to
 * leave unreached.
 */
const UNREACHED_ACTIONS = Object.freeze(['attack', 'mount', 'toss', 'tossStack', 'useOn'])

/** Where a body stands for the case below: inside the one chunk column that is loaded. */
const FEET = Object.freeze({ x: 8.5, y: 64, z: 8.5 })

/** The ground's own surface, so the block below the body's feet is stone. */
const GROUND_LEVEL = 63

/** A main-inventory slot, and hungry enough for vanilla to accept an ordinary food. */
const BREAD_SLOT = 20

const HUNGRY = Object.freeze({ health: 20, food: 8, foodSaturation: 0 })

test('a win_game game state change is answered with no packet at all', async () => {
  const { client } = await loadedBot()
  // Mineflayer's own `game` plugin writes `client_command` here, which is the vanilla
  // client's respawn: an automatic respawn arriving by a route `respawn: false` does
  // not cover, on a packet any server can choose to send. Both spellings, because the
  // plugin matched the numeric reason as well as the name.
  //
  // Deliberately with no login first, which is also how that plugin takes it: the
  // handler is registered at injection and answers whenever the packet arrives. That
  // is what lets this test fail on the *write* if the plugin is ever loaded again,
  // rather than on the login packet a full fixture would have to carry.
  client.emit('game_state_change', { reason: 'win_game', gameMode: 1 })
  client.emit('game_state_change', { reason: 4, gameMode: 1 })
  await settled()
  assert.deepEqual(client.writes, [])
})

test('the login report is the login event and the world it names, said once', async () => {
  const { bot, client, events } = await loadedBot()
  client.emit('registry_data', registryPacket())
  client.emit('login', loginPacket())
  await settled()
  assert.deepEqual(events, [{ name: 'login', given: [] }])
  // Exactly the four fields the loaded plugins read, and no others: a field nothing
  // reads is a field that can be silently wrong, and `bot.game` is the one place this
  // process stands in for a Mineflayer plugin's own state.
  assert.deepEqual(Object.keys(bot.game).sort(), ['dimension', 'gameMode', 'height', 'minY'])
  assert.equal(bot.game.dimension, 'overworld')
  assert.equal(bot.game.gameMode, 'survival')
  // Read through the registry rather than guessed: the pre-1.18 fallback would answer
  // 0 and 256 here, and `blocks` would then drop every block below sea level.
  assert.equal(bot.game.minY, -64)
  assert.equal(bot.game.height, 384)
  // A login is a report this process reads. It is not a handshake it takes part in.
  assert.deepEqual(client.writes, [])
})

test('a death is reported and never answered with a respawn', async () => {
  const { client, events } = await loadedBot()
  spawned(client)
  client.emit('update_health', { health: 0, food: 20, foodSaturation: 5 })
  await settled()
  assert.deepEqual(
    events.map((event) => event.name),
    ['login', 'spawn', 'health', 'health', 'death']
  )
  // `respawn: false` is what stops `health` writing `client_command` here. A reconnect
  // that reaches `dead` without a `spawn` depends on this: a body that came back to
  // life of its own accord would report a spawn nobody asked for.
  assert.deepEqual(client.writes, [])
})

test('nothing runs of its own accord and nothing unasked is written', async () => {
  const { bot, client } = await loadedBot()
  spawned(client)
  // Spawned and left alone. Mineflayer's `physics` runs a 50 ms interval that writes a
  // position every tick, which is this process sending autonomous movement, and `kick`
  // respawns. Neither is loaded, and the replacement in `gamestate.js` starts no timer.
  await settled(WATCHED_MS)
  assert.deepEqual(client.writes, [])
  // The deciding half of the library is absent. `dig`, `placeBlock` and `craft` change
  // the world; `setControlState`, `lookAt` and `look` move the body; `physics` and
  // `pathfinder` are the loops that would do either without being asked.
  for (const absent of [
    'dig', 'placeBlock', 'craft', 'setControlState', 'lookAt', 'look', 'chat',
    'openContainer', 'sleep', 'physics', 'pathfinder', 'elytraFly', 'creative'
  ]) {
    assert.equal(typeof bot[absent], 'undefined', absent)
  }
  // The reporting half is present, because reporting is the whole purpose: what a
  // perception is read from, and the two calls one explicit meal is made of.
  for (const held of ['entities', 'entity', 'inventory', 'blockAt', 'world', 'time', 'game']) {
    assert.notEqual(typeof bot[held], 'undefined', held)
  }
  for (const reading of ['health', 'food', 'foodSaturation']) {
    assert.equal(typeof bot[reading], 'number', reading)
  }
  for (const operation of ['equip', 'consume', 'activateItem']) {
    assert.equal(typeof bot[operation], 'function', operation)
  }
  // `world.raycast` is what a line of sight is measured with, and it is synchronous
  // because `bot.world` is the synchronous face of prismarine-world.
  assert.equal(typeof bot.world.raycast, 'function')
})

test('the actions the reporting plugins bring with them are reached by no operation', async () => {
  // The plugin list is drawn per plugin, not per method, so the actions in
  // `UNREACHED_ACTIONS` are on the bot whether this process wants them or not. What keeps
  // them from being taken is the wire being five operations - which is a claim about what
  // those five operations do, and so has to be driven rather than read off the source.
  const { body, bot, client, port } = await embodied()
  spawned(client, HUNGRY)
  clockReported(client, { timeOfDay: 6_000, day: 1 })
  client.emit('position', positionPacket({ ...FEET, yaw: 0, pitch: 0, teleportId: 1 }))
  ground(bot, { level: GROUND_LEVEL })
  stocked(client, [[BREAD_SLOT, 'bread', 3]])
  await settled()
  const taken = []
  for (const action of UNREACHED_ACTIONS) {
    // Present, or this case proves nothing: a plugin that stopped defining one would make
    // the assertion below pass for the wrong reason.
    assert.equal(typeof bot[action], 'function', action)
    bot[action] = () => taken.push(action)
  }
  // Not among them, because `bot.quit` is `kick`'s and `kick` is not loaded. That absence
  // is load-bearing rather than incidental: it is why `quitBot` ends the connection through
  // `bot.end` and a reason of its own.
  assert.equal(typeof bot.quit, 'undefined')

  const peer = await handshaken(port)
  assert.equal((await promptly(peer.answer(request('status', 'q1')), 'the status')).op, 'status')
  const sensed = await promptly(
    peer.answer(request('sense', 's1', { radius: 5, limit: 3 })),
    'the sense'
  )
  assert.equal(sensed.op, 'sense')
  // A meal that in fact completes, so the one operation that does drive the library has
  // really run: refused, it would reach neither `equip` nor `consume` and could not have
  // reached anything beside them either.
  peer.send(request('eat', 'e1', { senseId: 's1', slot: BREAD_SLOT }))
  await settled()
  // The use completed, and then the slot the stack came out of restated: a meal is only
  // reported as eaten once the server has said what is left of it.
  client.emit('set_cooldown', { itemID: registry.itemsByName.bread.id })
  client.emit('set_slot', {
    windowId: 0,
    stateId: 2,
    slot: QUICK_BAR_START,
    item: Item.toNotch(stack('bread', 2))
  })
  assert.equal((await promptly(peer.read(), 'the meal')).outcome, 'eaten')
  const stopped = await promptly(peer.answer(request('shutdown', 'x1')), 'the shutdown')
  assert.equal(stopped.op, 'shutdown')

  // Every operation the wire has, and not one of the actions was called.
  assert.deepEqual(taken, [])
  peer.stop()
  await body.stop()
})

test('the server own position is recorded and the teleport confirmed', async () => {
  const { bot, client } = await loadedBot()
  spawned(client)
  client.emit('position', positionPacket({
    x: 10.5, y: 64, z: -3.5, yaw: 137, pitch: 12, teleportId: 4
  }))
  await settled()
  assert.deepEqual(
    { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z },
    { x: 10.5, y: 64, z: -3.5 }
  )
  // Without this handler the position never leaves (0,0,0), so every perception this
  // process reported would be taken at the world origin.
  assert.notEqual(bot.entity.position.x, 0)
  // The confirmation is the protocol's own reply. An unconfirmed teleport leaves the
  // server ignoring the client, and it is the only thing this handler ever writes.
  assert.deepEqual(client.writes, [{ name: 'teleport_confirm', parameters: { teleportId: 4 } }])
})

test('a relative position update is a step and not a place', async () => {
  const { bot, client } = await loadedBot()
  spawned(client)
  client.emit('position', positionPacket({
    x: 10.5, y: 64, z: -3.5, yaw: 90, pitch: 0, teleportId: 1
  }))
  client.emit('position', positionPacket({
    x: 1, y: 0, z: -2, yaw: 10, pitch: 5, relative: { x: true, z: true, yaw: true }, teleportId: 2
  }))
  await settled()
  // Each field is absolute unless its own flag is set. Taken as absolute throughout,
  // the second report would have put the body a step from the origin.
  assert.deepEqual(
    { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z },
    { x: 11.5, y: 0, z: -5.5 }
  )
  // The relative yaw is summed in the protocol's own degrees, before the conversion to
  // radians; the absolute pitch replaces the one held.
  assert.ok(Math.abs(conversions.toNotchianYaw(bot.entity.yaw) - 100) < FACING_TOLERANCE_DEGREES)
  assert.ok(Math.abs(conversions.toNotchianPitch(bot.entity.pitch) - 5) < FACING_TOLERANCE_DEGREES)
  assert.deepEqual(client.writes.map((write) => write.name), ['teleport_confirm', 'teleport_confirm'])
})

test('an older server bitmask spelling of the relative flags reads the same', async () => {
  const { bot, client } = await loadedBot()
  spawned(client)
  client.emit('position', positionPacket({
    x: 10.5, y: 64, z: -3.5, yaw: 90, pitch: 0, teleportId: 1
  }))
  // 1.19 through 1.20.4 send the flags as one signed byte rather than as an object.
  // Both spellings are inside the version range this process documents, so both are
  // read: x is 0x01, z is 0x04, yaw is 0x08.
  client.emit('position', {
    x: 1, y: 0, z: -2, yaw: 10, pitch: 5, flags: 0x01 | 0x04 | 0x08, teleportId: 2
  })
  await settled()
  assert.deepEqual(
    { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z },
    { x: 11.5, y: 0, z: -5.5 }
  )
  assert.ok(Math.abs(conversions.toNotchianYaw(bot.entity.yaw) - 100) < FACING_TOLERANCE_DEGREES)
})

test('facing follows the server so a meal does not turn the body', async () => {
  const { bot, client } = await loadedBot()
  spawned(client)
  client.emit('position', positionPacket({
    x: 10.5, y: 64, z: -3.5, yaw: 137, pitch: 12, teleportId: 1
  }))
  await settled()
  client.writes.length = 0
  // This is the one library call the eat path is built on, and 1.21.1's serverbound
  // `use_item` carries the client's rotation for the server to apply. Both fields
  // default to zero on a fresh entity, which is 180 degrees of yaw once converted - so
  // a process that never recorded the server's own answer would turn the body on every
  // meal, in the one place it is meant only to consume an item.
  bot.activateItem()
  await settled()
  assert.equal(client.writes.length, 1)
  const [{ name, parameters }] = client.writes
  assert.equal(name, 'use_item')
  assert.ok(Math.abs(parameters.rotation.x - 137) < FACING_TOLERANCE_DEGREES, `yaw ${parameters.rotation.x}`)
  assert.ok(Math.abs(parameters.rotation.y - 12) < FACING_TOLERANCE_DEGREES, `pitch ${parameters.rotation.y}`)
})
