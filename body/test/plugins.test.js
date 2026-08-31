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
 * minecraft-protocol client. The client is fake because a packet has to be *arrived*
 * rather than waited for - there is no server in this suite and no credential to
 * reach one with - and because every write the bot makes has to be observable, which
 * a socket does not offer. Mineflayer takes such a client itself, through
 * `options.client`, so nothing about the loader or the plugins is stubbed.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { once } = require('node:events')
const { test } = require('node:test')

const mineflayer = require('mineflayer')

const { botOptionsFor } = require('../src/body')

/** The version the disposable Paper server in the proof runs, and this body targets. */
const VERSION = '1.21.1'

/** Long enough for a 50 ms physics interval to have ticked several times. */
const WATCHED_MS = 250

/**
 * A minecraft-protocol client, as far as Mineflayer is concerned.
 *
 * Every `write` is recorded rather than sent, because what this file is about is the
 * packets a policy-free body must never produce. `wait_connect` is deliberately
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

/**
 * A real bot, loaded with this process's own options, with its plugins injected.
 *
 * Mineflayer injects on its own `inject_allowed`, one turn of the event loop after
 * `createBot` returns, so a test that asserted immediately would be asserting about
 * a bot with no plugins - which passes every one of these tests for the wrong reason.
 */
async function loadedBot () {
  const client = fakeClient()
  const bot = mineflayer.createBot({
    ...botOptionsFor({ username: 'loom_test', minecraft: { host: '127.0.0.1', port: 25565 } }),
    client,
    version: VERSION
  })
  const events = []
  for (const name of ['login', 'spawn', 'death', 'respawn', 'health', 'game']) {
    bot.on(name, (...given) => events.push({ name, given }))
  }
  await once(bot, 'inject_allowed')
  return { bot, client, events }
}

/** The login packet a 1.21.1 server sends, as far as anything here reads it. */
function loginPacket () {
  return { worldType: 'minecraft:overworld', gamemode: 0, maxPlayers: 20 }
}

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
  await new Promise((resolve) => setTimeout(resolve, 50))
  assert.deepEqual(client.writes, [])
})

test('the raw login packet is the login event, said once and read for nothing', async () => {
  const { client, events } = await loadedBot()
  client.emit('login', loginPacket())
  await new Promise((resolve) => setTimeout(resolve, 50))
  assert.deepEqual(events, [{ name: 'login', given: [] }])
  // Nothing is read out of the packet, so nothing about the world can reach a caller
  // through it, and nothing is written back: a login is a lifecycle fact and not a
  // handshake this process takes part in.
  assert.deepEqual(client.writes, [])
})

test('a death is reported and never answered with a respawn', async () => {
  const { client, events } = await loadedBot()
  client.emit('login', loginPacket())
  client.emit('update_health', { health: 20, food: 20, foodSaturation: 5 })
  client.emit('update_health', { health: 0, food: 20, foodSaturation: 5 })
  await new Promise((resolve) => setTimeout(resolve, 50))
  assert.deepEqual(
    events.map((event) => event.name),
    ['login', 'spawn', 'health', 'health', 'death']
  )
  // `respawn: false` is what stops `health` writing `client_command` here. A reconnect
  // that reaches `dead` without a `spawn` depends on this: a body that came back to
  // life of its own accord would report a spawn nobody asked for.
  assert.deepEqual(client.writes, [])
})

test('no gameplay loop runs and no gameplay action is available', async () => {
  const { bot, client } = await loadedBot()
  client.emit('login', loginPacket())
  client.emit('update_health', { health: 20, food: 20, foodSaturation: 5 })
  // Spawned and left alone. `physics` runs a 50 ms interval that writes a position
  // every tick, which is this process sending autonomous movement; `kick` respawns.
  await new Promise((resolve) => setTimeout(resolve, WATCHED_MS))
  assert.deepEqual(client.writes, [])
  // Nothing is loaded that could act in the world even if something asked it to.
  for (const action of [
    'dig', 'placeBlock', 'attack', 'activateItem', 'craft', 'equip', 'toss',
    'setControlState', 'lookAt', 'chat', 'quit', 'openContainer', 'sleep'
  ]) {
    assert.equal(typeof bot[action], 'undefined', action)
  }
  // And the state a policy would be made of is not there either.
  for (const held of ['physics', 'entity', 'inventory', 'blockAt', 'pathfinder']) {
    assert.equal(typeof bot[held], 'undefined', held)
  }
})
