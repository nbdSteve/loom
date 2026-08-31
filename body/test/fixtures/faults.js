'use strict'

/**
 * The failures a body process cannot be made to have from outside it.
 *
 * Loaded with `node --require`, so every fault is in place before the entry point
 * runs and none of it is reachable from the process itself: there is no test flag in
 * `bin/loom-body.js` and no seam in it that exists only for a test. A supervisor
 * running the shipped `loom-body` gets the shipped process.
 *
 * Every injected failure carries a marker that must not be printed - a path, or
 * `CLIENT_CALLBACK_MARKER` - because the point of these tests is not that the process
 * survives but that what it says about the failure stays bounded.
 *
 * The two control-server modes are failures on opposite sides of the bind, and they
 * prove different things. `control-listen` fails *before* the channel is open, which is
 * the path that reports a code, so its `code` is spelled as something plausible and
 * unrecognised (`LOCAL_PATH`): a code passed straight through instead of being
 * allowlisted would be visible. `control-accept` fails *after* the channel is bound,
 * where there is no startup promise left to reject - so what it proves is that the
 * process reaches the bounded `failed` lifecycle state and exits on it rather than
 * carrying on with a control channel that has gone. That path prints the state and
 * nothing of the failure at all, neither its message nor its code, which is why its
 * `EMFILE` is there to stay out of the output rather than to be translated in it.
 */
const mode = process.env.LOOM_BODY_TEST_FAULT

if (mode === 'mineflayer-load') {
  const Module = require('node:module')
  const load = Module._load
  Module._load = function (request) {
    if (request === 'mineflayer') {
      Module._load = load
      throw new Error('/private/loom/mineflayer-load')
    }
    return load.apply(this, arguments)
  }
}

if (mode === 'client-callback' || mode === 'client-rejection') {
  const Module = require('node:module')
  const load = Module._load
  Module._load = function (request) {
    const loaded = load.apply(this, arguments)
    if (request !== 'mineflayer') return loaded
    Module._load = load
    const createBot = loaded.createBot
    loaded.createBot = function (...parameters) {
      const bot = createBot.apply(this, parameters)
      bot.on('connect', () => {
        const failure = new Error('CLIENT_CALLBACK_MARKER /private/loom/client-callback.js')
        if (mode === 'client-callback') throw failure
        Promise.reject(failure)
      })
      return bot
    }
    return loaded
  }
}

if (mode === 'control-listen' || mode === 'control-accept') {
  const net = require('node:net')
  const createServer = net.createServer
  net.createServer = function (...parameters) {
    net.createServer = createServer
    const server = createServer.apply(this, parameters)
    if (mode === 'control-listen') {
      server.listen = () => {
        const failure = new Error('/private/loom/control-listen')
        failure.code = 'LOCAL_PATH'
        throw failure
      }
    } else {
      server.once('listening', () => {
        setImmediate(() => {
          const failure = new Error('/private/loom/control-accept')
          failure.code = 'EMFILE'
          server.emit('error', failure)
        })
      })
    }
    return server
  }
}
