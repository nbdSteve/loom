'use strict'

/**
 * What the body's suites drive the body with, in one place.
 *
 * Not a suite: `npm test` runs `test/*.test.js`, so nothing here is collected as a
 * test. It is here because three suites need the same two harnesses, and a second
 * copy of either would drift from the first without any test failing.
 *
 * There are two, and the split is the seam the process itself has. `controller` and
 * `startedBody` drive the body from *above*, over a real loopback socket, the way a
 * controller does. `realBot` drives it from *below*, with the real
 * `mineflayer.createBot` over a fake minecraft-protocol client, the way a server does.
 * `embodied` is both at once, which is what lets a request read off a wire be answered
 * from the real client library rather than from a fake of one.
 *
 * The Minecraft client is fake and the control socket is not, because the two halves
 * fail in different ways. A lifecycle is Mineflayer's event order and can be stated at
 * a desk; framing, flood behaviour and a deterministic close are socket behaviour, and
 * a mock that is not a socket cannot express them. The protocol client is fake for the
 * opposite reason: a packet has to be *arrived* rather than waited for, there is no
 * server in these suites and no credential to reach one with, and every write the bot
 * makes has to be observable, which a socket does not offer. Mineflayer takes such a
 * client itself, through `options.client`, so nothing about the loader or the plugins
 * is stubbed.
 *
 * **A suite that builds a real bot must register `afterEach(disconnected)`.** Every one
 * of them does, and the reason it is not optional is in that function's own comment.
 */

const assert = require('node:assert/strict')
const net = require('node:net')
const { EventEmitter } = require('node:events')
const { once } = require('node:events')

const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const { botOptionsFor, createBody } = require('../src/body')
const { installDigging } = require('../src/digging')
const { PROTOCOL_VERSION } = require('../src/protocol')
const { installMovement } = require('../src/movement')
const { installPlacing } = require('../src/placing')

const AGENT = 'loom.test.body'
const OTHER_AGENT = 'loom.test.other'
const USERNAME = 'loom_test'

/** The version the disposable Paper server in the proof runs, and this body targets. */
const VERSION = '1.21.1'

/** Long enough for a 50 ms physics interval to have ticked several times. */
const WATCHED_MS = 250

/**
 * The real registry, item, chunk and window types for the version above.
 *
 * Loaded once, from the same libraries Mineflayer itself loads, so an item a test
 * stocks and an item the bot reports are the same kind of thing. Nothing here builds a
 * stand-in for a stack, a block or a food table.
 */
const registry = require('prismarine-registry')(VERSION)
const Item = require('prismarine-item')(registry)
const Chunk = require('prismarine-chunk')(registry)

/** The vanilla overworld's own world shape, which a chunk column is built against. */
const OVERWORLD = Object.freeze({ minY: -64, height: 384 })

/** The slot the quick bar starts at, and the one `equip(item, 'hand')` reaches for. */
const QUICK_BAR_START = 36

/**
 * A Mineflayer client, as far as this process is concerned.
 *
 * `end` is present from the start and `quit` appears only at `login`, because that is
 * what Mineflayer does: `end` is defined on the bot at construction
 * (`mineflayer/lib/loader.js`) while `quit` is injected by a plugin during login
 * (`mineflayer/lib/plugins/kick.js`). A fake that offered `quit` throughout hid the
 * fact that a body stopped before login had nothing to call.
 *
 * `end()` answers with an `end` *event* rather than returning, which is also what
 * Mineflayer does, and `endDelayMs` is what a real socket close costs. A fake that
 * ended instantly made every shutdown order look correct, because a synchronous
 * client always finishes before a socket that has to be closed over the network.
 *
 * `loadPlugin` records rather than injects, and `loaded` is what a test reads it back
 * from. A third-party plugin is loaded through that method and not through the option
 * table, so the two halves of the plugin policy are observable in two different places -
 * `created.plugins` for the library's own, `bot.loaded` for anything above them.
 */
function fakeBot () {
  const bot = new EventEmitter()
  bot.subscribed = []
  bot.loaded = []
  bot.ends = 0
  bot.endDelayMs = 0
  bot.loadPlugin = (plugin) => { bot.loaded.push(plugin) }
  const on = bot.on.bind(bot)
  bot.on = (event, listener) => {
    bot.subscribed.push(event)
    return on(event, listener)
  }
  bot.end = () => {
    bot.ends += 1
    setTimeout(() => bot.emit('end'), bot.endDelayMs)
  }
  // Subscribed with the raw `on`, so this fake's own bookkeeping is not mistaken for
  // a subscription the body under test made.
  on('login', () => { bot.quit = (reason) => bot.end(reason) })
  return bot
}

/** A port nothing is listening on, so a test never has to guess one. */
async function freePort () {
  const probe = net.createServer()
  await new Promise((resolve) => probe.listen({ host: '127.0.0.1', port: 0 }, resolve))
  const { port } = probe.address()
  await new Promise((resolve) => probe.close(resolve))
  return port
}

/**
 * Starts a body with a fake client on a free control port.
 *
 * `created` is the options the body handed to `createBot`, which is where the
 * Mineflayer decisions this process makes are observable.
 */
async function startedBody (overrides = {}) {
  const { bot = fakeBot(), ...rest } = overrides
  let created
  const states = []
  const body = createBody({
    agentId: AGENT,
    username: USERNAME,
    minecraft: { host: '127.0.0.1', port: 25565 },
    control: { host: '127.0.0.1', port: await freePort() },
    createBot: (options) => {
      created = options
      return bot
    },
    onState: (state) => states.push(state),
    ...rest
  })
  const { control } = await body.start()
  return { body, bot, created, states, port: control.port }
}

/** A controller: sends whole frames, and reads whole frames back. */
function controller (port) {
  const socket = net.connect({ host: '127.0.0.1', port })
  socket.setEncoding('utf8')
  // A controller that keeps writing at a channel the body has closed earns a reset,
  // which is the ordinary end of a flood rather than a failure of the test.
  socket.on('error', () => {})
  const taken = []
  const waiting = []
  let pending = ''
  socket.on('data', (chunk) => {
    pending += chunk
    let end = pending.indexOf('\n')
    while (end !== -1) {
      const line = pending.slice(0, end)
      pending = pending.slice(end + 1)
      if (waiting.length > 0) waiting.shift()(line)
      else taken.push(line)
      end = pending.indexOf('\n')
    }
  })
  return {
    socket,
    connected: once(socket, 'connect'),
    // Resolved rather than `once(socket, 'close')`, which attaches an `error`
    // listener of its own and *rejects* on it - and a connection the body closed
    // under a bound is exactly the one whose last act is a reset. Waiting for a close
    // must not depend on how quietly it arrived.
    closed: new Promise((resolve) => socket.once('close', resolve)),
    send (frame) {
      socket.write(typeof frame === 'string' ? frame + '\n' : JSON.stringify(frame) + '\n')
    },
    /** Writes several frames as one chunk, so a pipelined batch is drained together. */
    pipeline (frames) {
      socket.write(frames.map((frame) => JSON.stringify(frame) + '\n').join(''))
    },
    next () {
      return new Promise((resolve) => {
        if (taken.length > 0) resolve(taken.shift())
        else waiting.push(resolve)
      })
    },
    async read () {
      return JSON.parse(await this.next())
    },
    async answer (frame) {
      this.send(frame)
      return JSON.parse(await this.next())
    },
    stop () {
      socket.destroy()
    }
  }
}

/**
 * Retries `attempt` until it succeeds or the wait runs out.
 *
 * Only for the one thing a test cannot observe directly: a socket's close reaches the
 * body on its own turn of the event loop, so a freed connection slot is waited for
 * rather than assumed to be free on the next line.
 */
async function eventually (attempt, ms = 2_000) {
  const until = Date.now() + ms
  for (;;) {
    try {
      return await attempt()
    } catch (failure) {
      if (Date.now() >= until) throw failure
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
  }
}

/**
 * Fails if `waited` has not settled inside `ms`.
 *
 * For the bounds whose regression is an *absence*: a body that never closes an idle
 * connection, or never refuses a flood, leaves a read waiting for ever, and a plain
 * `await` would hang the suite rather than fail it. A hung suite says nothing about
 * which bound was lost.
 */
function promptly (waited, what, ms = 5_000) {
  let timer
  return Promise.race([
    waited,
    new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`${what} did not arrive in ${ms}ms`)), ms)
    })
  ]).finally(() => clearTimeout(timer))
}

/**
 * A request of the current generation, correlated and addressed to the body under test.
 *
 * The generation is read from the codec rather than written here, so a suite never has to be
 * edited when it moves - and a case whose subject is a *refused* generation states its own
 * number, which is the only place one belongs.
 */
function request (op, requestId, overrides = {}) {
  return { op, protocol: PROTOCOL_VERSION, requestId, agentId: AGENT, ...overrides }
}

/** A connected controller that has agreed the protocol generation. */
async function handshaken (port) {
  const peer = controller(port)
  await peer.connected
  const accepted = await peer.answer(request('handshake', 'h1'))
  assert.equal(accepted.op, 'handshake')
  return peer
}

/**
 * A minecraft-protocol client, as far as Mineflayer is concerned.
 *
 * Every `write` is recorded rather than sent, because what these suites are about is
 * the packets a policy-free body must never produce. `wait_connect` is deliberately
 * absent: Mineflayer waits for a server ping before it will set up a registry, and
 * there is no server here to answer one.
 */
function fakeClient () {
  const client = new EventEmitter()
  client.version = VERSION
  client.writes = []
  client.write = (name, parameters) => client.writes.push({ name, parameters })
  client.end = () => client.emit('end')
  client.registerChannel = () => {}
  client.writeChannel = () => {}
  return client
}

/** Every bot [realBot] has built and whose client has not ended yet. */
const openBots = new Set()

/**
 * Ends every bot a suite has opened, and is what an `afterEach(disconnected)` calls.
 *
 * A suite that builds a real bot must give it up, and the reason is a timer rather than
 * tidiness. Mineflayer's `physics` plugin starts a 50 ms interval on the bot's own
 * `login` event and clears it only from `end`, so a case that logs a bot in and walks
 * away leaves that interval behind and the test process never exits. That arrives as the
 * whole *file* timing out rather than as a failing case, and a timed-out file prints no
 * assertion diagnostics at all - so one leaked bot hides every real failure beside it.
 *
 * Tracked here rather than at each call site so that a case which throws still gives its
 * bot up, and `end` rather than `quit` for the reason `body.js`'s own `quitBot` gives:
 * the plugin that defines `quit` is not loaded, and what the plugins clean up on is the
 * client's `end`.
 */
function disconnected () {
  for (const bot of openBots) bot.end()
  openBots.clear()
}

/**
 * A real bot, loaded with this process's own options and its own movement seam, with every
 * plugin injected.
 *
 * Mineflayer injects on its own `inject_allowed`, one turn of the event loop after
 * `createBot` returns, so a test that asserted immediately would be asserting about
 * a bot with no plugins - which passes every one of these tests for the wrong reason.
 *
 * The movement seam is installed here because the process installs it the moment the client
 * exists, and it is where two of the three plugins a body runs come from: a bot without it is
 * missing the client-side simulation *and* the gate that scopes it, so a claim about what an
 * unasked body writes would be a claim about a bot this process never builds.
 * [installMovement] is idempotent, so a suite that installs it again for the wrapper's own
 * sake - `movement.test.js` does - gets this one back rather than a second owner of one path.
 *
 * The bot is registered for teardown here; see [disconnected] for why that is not
 * optional.
 */
async function realBot () {
  const client = fakeClient()
  const bot = mineflayer.createBot({
    ...botOptionsFor({ username: USERNAME, minecraft: { host: '127.0.0.1', port: 25565 } }),
    client,
    version: VERSION
  })
  installMovement(bot)
  installDigging(bot)
  installPlacing(bot)
  openBots.add(bot)
  bot.once('end', () => openBots.delete(bot))
  const events = []
  for (const name of ['login', 'spawn', 'death', 'respawn', 'health', 'game']) {
    bot.on(name, (...given) => events.push({ name, given }))
  }
  await once(bot, 'inject_allowed')
  return { bot, client, events }
}

/** The older name for `realBot`, kept because that is what the plugin suite calls it. */
const loadedBot = realBot

/**
 * A started body process driving a real bot, with the client that feeds it.
 *
 * The bot is built first, because its packets are what put it in the world, and the
 * body is started around it: `createBody` subscribes to `login` and `spawn` on the bot
 * it is handed, so the subscriptions are in place before `spawned` is called.
 */
async function embodied (overrides = {}) {
  const { bot, client, events } = await realBot()
  const started = await startedBody({ bot, ...overrides })
  return { ...started, client, events }
}

/**
 * The dimension registry a 1.21.1 server sends in the configuration phase.
 *
 * The shape is the wire's own: one `registry_data` packet per registry, carrying
 * entries whose values are NBT. `min_y` and `height` are the two fields the world
 * shape is read from, and they are what a chunk column is built against - so the
 * numbers here are the vanilla overworld's rather than round ones.
 */
function registryPacket () {
  return {
    id: 'minecraft:dimension_type',
    entries: [
      {
        key: 'minecraft:overworld',
        value: {
          type: 'compound',
          value: {
            min_y: { type: 'int', value: OVERWORLD.minY },
            height: { type: 'int', value: OVERWORLD.height }
          }
        }
      }
    ]
  }
}

/**
 * The login packet a 1.21.1 server sends, as far as anything here reads it.
 *
 * The spawn data is nested under `worldState` and the dimension is an index into the
 * registry above, which is what makes the two packets a pair: a login read without the
 * registry names no dimension and so answers no world shape.
 */
function loginPacket ({ gamemode = 'survival' } = {}) {
  return {
    entityId: 7,
    isHardcore: false,
    worldState: { dimension: 0, name: 'minecraft:overworld', gamemode }
  }
}

/**
 * A body in the world: registries, login, and the first health report that spawns it.
 *
 * The game mode is the login report's own, because that is where the library reads it
 * (`bot.game.gameMode`, set once from this packet) - and it decides whether vanilla
 * consumes an item at all, so a creative body is driven by sending a creative login rather
 * than by writing the field afterwards.
 */
function spawned (client, vitals = { health: 20, food: 20, foodSaturation: 5 }, { gamemode } = {}) {
  client.emit('registry_data', registryPacket())
  client.emit('login', loginPacket({ gamemode }))
  client.emit('update_health', vitals)
}

/**
 * A clientbound position report, in the spelling 1.20.5 and above use.
 *
 * `flags` is a bitflags object there, and `_value` is the raw byte the library carries
 * beside the named fields - included because it is what a real server delivers, and
 * ignored on the reading side.
 */
function positionPacket ({ x, y, z, yaw, pitch, relative = {}, teleportId }) {
  return {
    x,
    y,
    z,
    yaw,
    pitch,
    flags: {
      x: Boolean(relative.x),
      y: Boolean(relative.y),
      z: Boolean(relative.z),
      yaw: Boolean(relative.yaw),
      pitch: Boolean(relative.pitch),
      _value: 0
    },
    teleportId
  }
}

/**
 * The world clock, reported the way the wire spells it.
 *
 * Both longs are `[high, low]` pairs, which is what Mineflayer's own time plugin reads
 * (`longToBigInt`): a plain number is a `TypeError` there rather than a wrong clock.
 */
function clockReported (client, { timeOfDay, day = 0 }) {
  const time = day * 24_000 + timeOfDay
  client.emit('update_time', { age: [0, time], time: [0, time] })
}

/** A real stack of `count` `what`, built by the library that models an item. */
function stack (what, count) {
  return new Item(registry.itemsByName[what].id, count)
}

/**
 * Puts real stacks in the player's own inventory window, the way a server does.
 *
 * `set_slot` is the packet a server sends for every inventory change it makes, so what
 * lands in `bot.inventory.slots` is the library's own model of the window rather than
 * anything a test wrote there. `held_item_slot` is sent too, because `bot.quickBarSlot`
 * is null until a server names one and `bot.heldItem` reads through it.
 */
function stocked (client, stacks, { quickBarSlot = 0 } = {}) {
  for (const [slot, what, count] of stacks) {
    client.emit('set_slot', { windowId: 0, stateId: 1, slot, item: Item.toNotch(stack(what, count)) })
  }
  client.emit('held_item_slot', { slot: quickBarSlot })
}

/** Empties one slot of the player's own window, the way a server reports a change. */
function emptied (client, slot) {
  client.emit('set_slot', { windowId: 0, stateId: 1, slot, item: { present: false } })
}

/**
 * Real ground under the body, and optionally a real wall beside it.
 *
 * A whole chunk column is built and handed to the synchronous world, so `bot.blockAt`
 * and `bot.world.raycast` are the library's own answers over the library's own block
 * shapes. Nothing outside this one column is loaded, which is what makes a reading
 * taken further out the *absent* answer rather than air.
 */
function ground (bot, { level = 63, walls = [] } = {}) {
  const column = new Chunk(OVERWORLD)
  const stone = registry.blocksByName.stone.defaultState
  for (let x = 0; x < 16; x += 1) {
    for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, level, z), stone)
  }
  for (const { x, y, z } of walls) column.setBlockStateId(new Vec3(x, y, z), stone)
  bot.world.setColumn(0, 0, column)
}

/**
 * An entity in the world, spawned the way a server spawns one.
 *
 * The type is the registry's own numeric id, so the entity that appears in
 * `bot.entities` carries the library's own name, height and position.
 */
function spotted (client, { id, what, x, y, z }) {
  client.emit('spawn_entity', {
    entityId: id,
    entityUUID: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
    objectUUID: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
    type: registry.entitiesByName[what].id,
    x,
    y,
    z,
    pitch: 0,
    yaw: 0,
    headPitch: 0,
    objectData: 0,
    velocityX: 0,
    velocityY: 0,
    velocityZ: 0
  })
}

/** One turn of the event loop, long enough for every handler on a packet to have run. */
function settled (ms = 50) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

module.exports = {
  AGENT,
  Chunk,
  Item,
  OTHER_AGENT,
  OVERWORLD,
  QUICK_BAR_START,
  USERNAME,
  VERSION,
  WATCHED_MS,
  clockReported,
  controller,
  disconnected,
  embodied,
  emptied,
  eventually,
  fakeBot,
  fakeClient,
  freePort,
  ground,
  handshaken,
  loadedBot,
  loginPacket,
  positionPacket,
  promptly,
  realBot,
  registry,
  registryPacket,
  request,
  spawned,
  spotted,
  stack,
  startedBody,
  stocked,
  settled
}
