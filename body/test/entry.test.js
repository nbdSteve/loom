'use strict'

/**
 * The entry point, run as the process it is.
 *
 * Every test here spawns the real `bin/loom-body.js` in a real child process and
 * reads its real exit status and its real standard error, because that is the whole
 * of what this file is: a configuration read from the environment, a bounded line,
 * and a status a supervisor acts on. None of those exist inside the module - an
 * in-process test would have to fake `process.exit` and `process.env` and would then
 * be proving something about the fake.
 *
 * The Minecraft side is a bare TCP listener rather than a server. Mineflayer's client
 * emits `connect` as soon as the socket is up and long before any handshake, which is
 * all that is needed to reach the client callbacks, and a test suite must not need a
 * Paper server to run. Nothing here reaches a real Minecraft server, and nothing here
 * has a credential to reach one with.
 */

const assert = require('node:assert/strict')
const net = require('node:net')
const path = require('node:path')
const { once } = require('node:events')
const { spawn } = require('node:child_process')
const { test } = require('node:test')

const { DEBUG_VARIABLES } = require('../src/body')
const { PROTOCOL_VERSION } = require('../src/protocol')

const ENTRY = path.join(__dirname, '..', 'bin', 'loom-body.js')
const FAULTS = path.join(__dirname, 'fixtures', 'faults.js')

/**
 * The whole of a refused configuration, which is one field name and no value.
 *
 * Written out rather than imported, because what this file pins is the *output* of
 * the process: a wording change is a change to what an operator reads, and a test
 * that built the expectation from the same constant the process prints would pass
 * through one.
 */
function refusalOf (field) {
  return `the loom body refuses this configuration: ${field}`
}

const AGENT = 'loom.test.entry'
const USERNAME = 'loom_entry'

/** How long a child gets to reach its own end before a test gives up on it. */
const PATIENCE_MS = 20_000

/** A port nothing is listening on. */
async function freePort () {
  const probe = net.createServer()
  await new Promise((resolve) => probe.listen({ host: '127.0.0.1', port: 0 }, resolve))
  const { port } = probe.address()
  await new Promise((resolve) => probe.close(resolve))
  return port
}

/**
 * A listener that accepts and says nothing, standing in for the Minecraft port.
 *
 * Sockets are tracked and destroyed on close, because an accepted connection outlives
 * `server.close` and would hold the test process open after the suite has finished.
 */
async function silentListener () {
  const sockets = new Set()
  const server = net.createServer((socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })
  await new Promise((resolve) => server.listen({ host: '127.0.0.1', port: 0 }, resolve))
  return {
    port: server.address().port,
    /** Closes every accepted socket politely, which is a server hanging up. */
    hangUp () {
      for (const socket of sockets) socket.end()
    },
    async close () {
      for (const socket of sockets) socket.destroy()
      await new Promise((resolve) => server.close(() => resolve()))
    }
  }
}

/**
 * Starts the entry point as a child process.
 *
 * The environment is built from nothing rather than from this process's own, so a
 * `DEBUG` or a `LOOM_BODY_*` in the shell that ran the suite cannot decide what is
 * being tested. `PATH` is carried because Node needs it for nothing here but a child
 * without one is a surprise nobody wants to debug.
 */
function startEntry (variables = {}, { fault } = {}) {
  const env = { PATH: process.env.PATH, ...variables }
  if (fault !== undefined) env.LOOM_BODY_TEST_FAULT = fault
  const args = fault === undefined ? [ENTRY] : ['--require', FAULTS, ENTRY]
  const child = spawn(process.execPath, args, {
    env,
    stdio: ['ignore', 'pipe', 'pipe']
  })
  let said = ''
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', (chunk) => { said += chunk })
  let out = ''
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', (chunk) => { out += chunk })
  return {
    child,
    said: () => said,
    out: () => out,
    /** Waits for a line matching `pattern` to be said, or gives up. */
    async waitFor (pattern) {
      const deadline = Date.now() + PATIENCE_MS
      while (!pattern.test(said)) {
        if (Date.now() > deadline) {
          throw new Error(`waited for ${pattern} and heard: ${JSON.stringify(said)}`)
        }
        if (child.exitCode !== null || child.signalCode !== null) {
          throw new Error(`child ended before ${pattern}, having said: ${JSON.stringify(said)}`)
        }
        await new Promise((resolve) => setTimeout(resolve, 20))
      }
    },
    /**
     * Waits for the child to end, and gives up rather than hanging.
     *
     * A test that awaited an exit with no bound would not fail on a process that
     * never goes: it would hang the whole suite, which is the one failure a bound
     * on a *residual* process must not be reported as.
     */
    async ended () {
      const abandon = new AbortController()
      const patience = setTimeout(() => abandon.abort(), PATIENCE_MS)
      try {
        const [status, signal] = await once(child, 'exit', { signal: abandon.signal })
        return { status, signal, said, out }
      } catch {
        throw new Error(`the child never ended, having said: ${JSON.stringify(said)}`)
      } finally {
        clearTimeout(patience)
      }
    },
    stop () {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
    }
  }
}

/** The configuration a body needs, with a control port of its own. */
async function configuration (overrides = {}) {
  return {
    LOOM_BODY_AGENT_ID: AGENT,
    LOOM_BODY_USERNAME: USERNAME,
    LOOM_BODY_CONTROL_PORT: String(await freePort()),
    LOOM_BODY_MC_PORT: String(await freePort()),
    ...overrides
  }
}

/**
 * One control connection, over which frames are sent and answered in order.
 *
 * One connection rather than one per frame, because a handshake authenticates the
 * *connection* it arrived on: a shutdown sent over a socket of its own is an
 * unauthenticated request, and is refused however well formed it is.
 */
async function controlSession (port) {
  const socket = net.connect({ host: '127.0.0.1', port })
  socket.setEncoding('utf8')
  await once(socket, 'connect')
  let pending = ''
  const lines = []
  let waiting
  socket.on('data', (chunk) => {
    pending += chunk
    let end = pending.indexOf('\n')
    while (end !== -1) {
      lines.push(pending.slice(0, end))
      pending = pending.slice(end + 1)
      end = pending.indexOf('\n')
    }
    if (waiting !== undefined && lines.length > 0) waiting()
  })
  return {
    async ask (frame) {
      socket.write(JSON.stringify(frame) + '\n')
      const deadline = Date.now() + PATIENCE_MS
      while (lines.length === 0) {
        if (Date.now() > deadline) throw new Error('the control channel said nothing')
        await new Promise((resolve) => {
          waiting = resolve
          setTimeout(resolve, 20)
        })
        waiting = undefined
      }
      return JSON.parse(lines.shift())
    },
    close () {
      socket.destroy()
    }
  }
}

/**
 * Whether a process is still there.
 *
 * Signal 0 checks for the process without touching it, which is how a residual
 * process is detected: an exit status says the parent was reaped, and says nothing
 * about a child of its own still running.
 */
function stillRunning (pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

test('a body with no control port is refused by field name and nothing else', async () => {
  const started = startEntry({
    LOOM_BODY_AGENT_ID: AGENT,
    LOOM_BODY_USERNAME: USERNAME
  })
  try {
    const { status, said } = await started.ended()
    // Its own status, so a supervisor does not restart into the same refusal.
    assert.equal(status, 2)
    assert.equal(said.trim(), refusalOf('controlPort'))
  } finally {
    started.stop()
  }
})

test('a host that is a name rather than a loopback literal is refused', async () => {
  // `localhost` is the spelling that looks canonical and is not: it is a name, and
  // what a resolver answers for it is not this process's to know.
  for (const [variable, field] of [
    ['LOOM_BODY_CONTROL_HOST', 'controlHost'],
    ['LOOM_BODY_MC_HOST', 'minecraftHost']
  ]) {
    const started = startEntry(await configuration({ [variable]: 'localhost' }))
    try {
      const { status, said } = await started.ended()
      assert.equal(status, 2, variable)
      assert.equal(said.trim(), refusalOf(field))
    } finally {
      started.stop()
    }
  }
})

test('a routable address is refused even spelled as a literal', async () => {
  const started = startEntry(await configuration({ LOOM_BODY_CONTROL_HOST: '0.0.0.0' }))
  try {
    const { status, said } = await started.ended()
    assert.equal(status, 2)
    assert.equal(said.trim(), refusalOf('controlHost'))
  } finally {
    started.stop()
  }
})

test('a malformed port is refused as a port rather than repeated back', async () => {
  const marker = '/private/loom/not-a-port'
  const started = startEntry(await configuration({ LOOM_BODY_CONTROL_PORT: marker }))
  try {
    const { status, said } = await started.ended()
    assert.equal(status, 2)
    assert.equal(said.trim(), refusalOf('controlPort'))
    assert.equal(said.includes(marker), false)
  } finally {
    started.stop()
  }
})

test('a control channel that cannot be bound has its own status and a bounded code', async () => {
  const started = startEntry(await configuration(), { fault: 'control-listen' })
  try {
    const { status, said } = await started.ended()
    // Not 2: the configuration was well spelled and the host refused it anyway.
    assert.equal(status, 3)
    assert.match(said, /could not open its control channel: unavailable/)
    // The injected failure's own `code` was `LOCAL_PATH`, and it is not allowlisted.
    assert.equal(said.includes('LOCAL_PATH'), false)
    assert.equal(said.includes('/private/loom'), false)
  } finally {
    started.stop()
  }
})

test('a port already held is reported as the one code that says so', async () => {
  const taken = net.createServer()
  await new Promise((resolve) => taken.listen({ host: '127.0.0.1', port: 0 }, resolve))
  const started = startEntry(await configuration({
    LOOM_BODY_CONTROL_PORT: String(taken.address().port)
  }))
  try {
    const { status, said } = await started.ended()
    assert.equal(status, 3)
    // Allowlisted, because it is the one an operator acts on: a body is already there.
    assert.match(said, /could not open its control channel: EADDRINUSE/)
  } finally {
    started.stop()
    await new Promise((resolve) => taken.close(resolve))
  }
})

test('a control channel that fails after it is bound is a failed body', async () => {
  // A silent Minecraft listener, so the only thing that can reach `failed` here is the
  // control server's own error: a refused Minecraft port would fail the body too, and
  // the test would then prove nothing about the post-bind path.
  const listener = await silentListener()
  const started = startEntry(
    await configuration({ LOOM_BODY_MC_PORT: String(listener.port) }),
    { fault: 'control-accept' }
  )
  try {
    const { status, said } = await started.ended()
    // Not 3, which is a channel that would not open: this one opened, was reported as
    // `connecting`, and then went. A restart may well fix that, where a refused
    // address never would.
    assert.equal(status, 1)
    assert.match(said, /: connecting$/m)
    assert.match(said, /: failed$/m)
    // The bounded state is the whole of what is said about it. Nothing on this path
    // prints a code at all, so neither the injected path nor its `EMFILE` appears -
    // not translated to `unavailable`, not repeated, not anywhere.
    assert.equal(said.includes('/private/loom'), false, said)
    assert.equal(said.includes('EMFILE'), false, said)
    assert.equal(said.includes('unavailable'), false, said)
    // Gone because it exited, rather than because a test killed it: a body left running
    // on a dead control channel is a player nothing can reach.
    assert.equal(started.child.signalCode, null)
    assert.equal(stillRunning(started.child.pid), false)
  } finally {
    started.stop()
    await listener.close()
  }
})

test('a Minecraft client that will not load fails without naming its own path', async () => {
  const started = startEntry(await configuration(), { fault: 'mineflayer-load' })
  try {
    const { status, said } = await started.ended()
    assert.equal(status, 1)
    assert.match(said, /could not start its Minecraft client: unavailable/)
    assert.equal(said.includes('/private/loom'), false)
  } finally {
    started.stop()
  }
})

test('an exception thrown by a client callback is contained and says nothing of itself', async () => {
  const listener = await silentListener()
  const started = startEntry(
    await configuration({ LOOM_BODY_MC_PORT: String(listener.port) }),
    { fault: 'client-callback' }
  )
  try {
    const { status, said } = await started.ended()
    // Node's own report would print the exception, its message and its stack.
    assert.equal(status, 1)
    assert.match(said, /the loom body failed: unavailable/)
    assert.equal(said.includes('CLIENT_CALLBACK_MARKER'), false)
    assert.equal(said.includes('/private/loom'), false)
    assert.equal(said.includes('at '), false)
  } finally {
    started.stop()
    await listener.close()
  }
})

test('a rejected promise from a client callback is contained the same way', async () => {
  const listener = await silentListener()
  const started = startEntry(
    await configuration({ LOOM_BODY_MC_PORT: String(listener.port) }),
    { fault: 'client-rejection' }
  )
  try {
    const { status, said } = await started.ended()
    assert.equal(status, 1)
    assert.match(said, /the loom body failed: unavailable/)
    assert.equal(said.includes('CLIENT_CALLBACK_MARKER'), false)
  } finally {
    started.stop()
    await listener.close()
  }
})

test('inherited debug settings reach no dependency and no output', async () => {
  const listener = await silentListener()
  const inherited = {}
  // The `debug` package's own family, which is what Mineflayer and minecraft-protocol
  // read. `NODE_DEBUG` is Node's own and is deliberately not set here: Node writes its
  // core debug output while bootstrapping the entry point, before a line of this
  // process has run, so no process can suppress its own. `silenceDependencyDebug`
  // clears it all the same, for anything created later and for any child process, and
  // `body.test.js` pins that at the unit level where it is observable.
  for (const name of DEBUG_VARIABLES) {
    if (name.startsWith('DEBUG')) inherited[name] = '*'
  }
  const started = startEntry({
    ...await configuration({ LOOM_BODY_MC_PORT: String(listener.port) }),
    ...inherited
  })
  try {
    // Far enough in that Mineflayer and minecraft-protocol have both been loaded and
    // have both had a live socket to be noisy about.
    await started.waitFor(/: connecting$/m)
    await new Promise((resolve) => setTimeout(resolve, 500))
    const said = started.said()
    const lines = said.split('\n').filter((line) => line.length > 0)
    // Every line is one this process meant to write.
    for (const line of lines) {
      assert.match(line, /^(loom body |the loom body )/, said)
    }
    // `debug` writes namespaces in square brackets and colours them; either would be
    // a dependency writing outside the bounded path.
    assert.equal(said.includes('mineflayer'), false, said)
    assert.equal(said.includes('minecraft-protocol'), false, said)
    assert.equal(said.includes('['), false, said)
    assert.equal(said.includes('\u001b'), false, said)
    assert.equal(started.out(), '')
  } finally {
    started.stop()
    await listener.close()
  }
})

test('a correlated shutdown stops the process, promptly and with nothing left behind', async () => {
  const listener = await silentListener()
  const variables = await configuration({ LOOM_BODY_MC_PORT: String(listener.port) })
  const port = Number(variables.LOOM_BODY_CONTROL_PORT)
  const started = startEntry(variables)
  try {
    await started.waitFor(/: connecting$/m)
    const session = await controlSession(port)
    const accepted = await session.ask({
      op: 'handshake', protocol: PROTOCOL_VERSION, requestId: 'h1', agentId: AGENT
    })
    assert.equal(accepted.op, 'handshake', JSON.stringify(accepted))
    const acknowledged = await session.ask({
      op: 'shutdown', protocol: PROTOCOL_VERSION, requestId: 'd1', agentId: AGENT
    })
    assert.equal(acknowledged.op, 'shutdown', JSON.stringify(acknowledged))
    // Asked twice, because a controller that did not see the first acknowledgement
    // retries: the second is acknowledged too, and teardown does not begin again.
    const again = await session.ask({
      op: 'shutdown', protocol: PROTOCOL_VERSION, requestId: 'd2', agentId: AGENT
    })
    assert.equal(again.op, 'shutdown', JSON.stringify(again))
    session.close()

    const { status, said } = await started.ended()
    // A shutdown asked for is not a failure, however the body was doing at the time.
    assert.equal(status, 0)
    assert.match(said, /: stopping$/m)
    // No client, no socket, no timer and no username-holding ghost: the process is
    // gone, and it is gone because it exited rather than because a test killed it.
    assert.equal(started.child.signalCode, null)
    assert.equal(stillRunning(started.child.pid), false)
  } finally {
    started.stop()
    await listener.close()
  }
})

test('a connection dropped rather than closed is a failure and says so', async () => {
  const listener = await silentListener()
  const started = startEntry(await configuration({
    LOOM_BODY_MC_PORT: String(listener.port)
  }))
  try {
    await started.waitFor(/: connecting$/m)
    // A losing connection is `failed` rather than `disconnected`, and its own status:
    // a restart may well fix it, where a refused configuration never would. Reaching a
    // clean `disconnected` needs a server that speaks Minecraft, which is the Paper
    // proof's part rather than this file's - a hang-up mid-handshake is a transport
    // error whether the peer sent a FIN or a reset.
    const lost = Date.now()
    listener.hangUp()
    await listener.close()
    const { status, said } = await started.ended()
    assert.equal(status, 1)
    assert.match(said, /: failed$/m)
    // Teardown must not wait for an `end` that has already been emitted. Bounded well
    // under the shutdown grace, and far under the control channel's idle timeout,
    // which is what a stop waiting on whatever handle was outstanding took.
    assert.ok(Date.now() - lost < 5_000, `stopping took ${Date.now() - lost}ms`)
    assert.equal(stillRunning(started.child.pid), false)
  } finally {
    started.stop()
    await listener.close()
  }
})

test('a signal stops the process the same way a shutdown does', async () => {
  const listener = await silentListener()
  const started = startEntry(await configuration({
    LOOM_BODY_MC_PORT: String(listener.port)
  }))
  try {
    await started.waitFor(/: connecting$/m)
    started.child.kill('SIGTERM')
    const { status, signal, said } = await started.ended()
    // Exited of its own accord, rather than being killed where it stood: a body taken
    // out by the signal itself would leave its player logged in until the server
    // noticed, and would say nothing about having gone.
    assert.equal(signal, null)
    assert.equal(status, 0)
    assert.match(said, /: stopping$/m)
    assert.equal(stillRunning(started.child.pid), false)
  } finally {
    started.stop()
    await listener.close()
  }
})
