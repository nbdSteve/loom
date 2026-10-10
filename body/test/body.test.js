'use strict'

/**
 * The body process, driven the way a controller drives it: over a real loopback
 * socket, against a fake Mineflayer client that emits exactly the events the real
 * one emits.
 *
 * The client is fake and the socket is not, and `test/harness.js` records why.
 *
 * Nothing here reads the module's source text. Every bound is proven by being
 * reached, every refusal by being read off a wire, and the shutdown order by which
 * channel a peer watched close first.
 */

const assert = require('node:assert/strict')
const net = require('node:net')
const { test } = require('node:test')

const digging = require('mineflayer/lib/plugins/digging')
const genericPlace = require('mineflayer/lib/plugins/generic_place')
const physics = require('mineflayer/lib/plugins/physics')
const { pathfinder } = require('mineflayer-pathfinder')

const {
  DEBUG_VARIABLES,
  MAX_CONTROL_CONNECTIONS,
  MAX_NOT_READY_PER_CONNECTION,
  MAX_REFUSALS_PER_CONNECTION,
  MAX_TRACKED_REQUESTS,
  INTERNAL_REPORTING_PLUGINS,
  createBody
} = require('../src/body')
const { followWorldState } = require('../src/gamestate')
const { MAX_FRAME_BYTES, PROTOCOL_VERSION } = require('../src/protocol')
const {
  AGENT,
  OTHER_AGENT,
  USERNAME,
  controller,
  eventually,
  fakeBot,
  freePort,
  handshaken,
  promptly,
  request,
  startedBody
} = require('./harness')

test('a body connects offline, with respawn explicitly off', async () => {
  const { body, created } = await startedBody()
  try {
    // Mineflayer defaults `respawn` to true, which would make coming back to life a
    // decision this process took by omission.
    assert.equal(created.respawn, false)
    assert.equal(created.auth, 'offline')
    assert.equal(created.host, '127.0.0.1')
    assert.equal(created.port, 25565)
    assert.equal(created.username, USERNAME)
    assert.equal(created.hideErrors, true)
    // Nothing that could carry a credential is configured, and the version is left
    // to Mineflayer unless it was asked for.
    assert.equal('password' in created, false)
    assert.equal('version' in created, false)
  } finally {
    await body.stop()
  }
})

test('only the reporting plugins are loaded and the rest is Loom own', async () => {
  const { body, bot, created } = await startedBody()
  try {
    // Mineflayer loads all of its internal plugins by default, which would put an
    // automatic respawn and every action plugin inside a process whose purpose is to
    // make no choices.
    assert.equal(created.loadInternalPlugins, false)
    // Every plugin in this table turns packets the server sends of its own accord into
    // state a reader can ask about, and nothing in it acts. `physics` is deliberately
    // absent: it steps, falls and writes the body's own position of nobody's asking, so a
    // resident one would be autonomy, and `movement.js` loads it under a request scope
    // instead. What replaces `game` is a plugin of Loom's own, because that one writes
    // `client_command` back at the server on a `win_game` packet - the automatic respawn
    // by another route. `plugins.test.js` proves all of it against the real loader rather
    // than against this option map.
    assert.deepEqual(Object.keys(created.plugins).sort(), [
      'blocks', 'entities', 'health', 'inventory', 'loomWorldState',
      'simple_inventory', 'time'
    ])
    for (const name of INTERNAL_REPORTING_PLUGINS) assert.equal(created.plugins[name], true)
    assert.equal(created.plugins.physics, undefined)
    assert.equal(created.plugins.loomWorldState, followWorldState)
    // The simulation starts off, because it is a request's rather than the process's. The
    // plugin reads this once at injection, so it is the starting state and not a hint.
    assert.equal(created.physicsEnabled, false)
    // The whole option set, so a plugin or a policy added by omission fails here.
    assert.deepEqual(Object.keys(created).sort(), [
      'auth', 'hideErrors', 'host', 'loadInternalPlugins', 'physicsEnabled', 'plugins',
      'port', 'respawn', 'username'
    ])
    // Action primitives arrive through their owning bounded modules rather than through
    // the reporting table. Loading them makes no choice and performs no action; the wire
    // cases prove they are reached only by one explicit request.
    assert.deepEqual(bot.loaded, [physics, pathfinder, digging, genericPlace])
  } finally {
    await body.stop()
  }
})

test('an explicitly configured version is carried', async () => {
  // Padded as well as plain, because the two are one variable read two ways: whether
  // this is a version at all is decided on the trimmed value, so the value handed to
  // Mineflayer has to be the same one. A trailing newline off a here-doc, forwarded
  // verbatim, is a version Mineflayer cannot match and nothing names.
  for (const version of ['1.21.1', ' 1.21.1', '1.21.1\n']) {
    const { body, created } = await startedBody({
      minecraft: { host: '127.0.0.1', port: 25565, version }
    })
    try {
      assert.equal(created.version, '1.21.1', JSON.stringify(version))
    } finally {
      await body.stop()
    }
  }
})

test('a blank version is unset rather than a version of its own', async () => {
  // Blanking a variable is how an operator turns a setting off, and the documented
  // contract is that an unset version is negotiated by Mineflayer. Carried through, an
  // empty string is not `undefined`, so Mineflayer would be handed an explicit falsy
  // version for a field this process was never configured for.
  for (const version of ['', '   ']) {
    const { body, created } = await startedBody({
      minecraft: { host: '127.0.0.1', port: 25565, version }
    })
    try {
      assert.equal('version' in created, false, JSON.stringify(version))
    } finally {
      await body.stop()
    }
  }
})

test('a body follows the lifecycle events and no others', async () => {
  const { body, bot } = await startedBody()
  try {
    assert.deepEqual(
      [...bot.subscribed].sort(),
      ['death', 'end', 'entityDead', 'entityGone', 'error', 'login', 'spawn']
    )
  } finally {
    await body.stop()
  }
})

test('a body that spawns is ready, and says so in that order', async () => {
  const { body, bot, states } = await startedBody()
  try {
    // The ordinary way in, and the one state a lifecycle test had never reached: a
    // `spawn` this body did not act on would leave it reporting `loggedIn` for the
    // whole of a live player's life, and every state below it is proven by a test of
    // its own.
    bot.emit('login')
    bot.emit('spawn')
    assert.deepEqual(states, ['loggedIn', 'ready'])
    assert.equal(body.state(), 'ready')
  } finally {
    await body.stop()
  }
})

test('a body that logs in already dead reaches dead without ever spawning', async () => {
  const { body, bot, states } = await startedBody()
  try {
    bot.emit('login')
    bot.emit('death')
    assert.deepEqual(states, ['loggedIn', 'dead'])
    assert.equal(body.state(), 'dead')
  } finally {
    await body.stop()
  }
})

test('a client error is a failed body, not a stopped one', async () => {
  const { body, bot, states } = await startedBody()
  try {
    // A transport that refused is a bounded `failed`, and nothing more is said about
    // it: the only other thing there would be to report is the transport's own text.
    bot.emit('error', new Error('refused'))
    assert.equal(body.state(), 'failed')
    assert.deepEqual(states, ['failed'])
  } finally {
    await body.stop()
  }
})

test('a server disconnect is reported and does not reconnect', async () => {
  const { body, bot, states } = await startedBody()
  try {
    bot.emit('login')
    bot.emit('end')
    assert.deepEqual(states, ['loggedIn', 'disconnected'])
    // Whether to come back is the controller's call: nothing here asked for a second
    // client, and a process that reconnected by itself would have decided to play on.
    assert.equal(bot.ends, 0)
  } finally {
    await body.stop()
  }
})

test('starting the client clears inherited dependency debug settings', async () => {
  const inherited = DEBUG_VARIABLES.map((name) => [name, process.env[name]])
  for (const name of DEBUG_VARIABLES) process.env[name] = '*'
  const body = createBody({
    agentId: AGENT,
    username: USERNAME,
    minecraft: { host: '127.0.0.1', port: 25565 },
    control: { host: '127.0.0.1', port: await freePort() },
    createBot: () => fakeBot()
  })
  try {
    await body.start()
    // `debug` decides whether a logger writes when the logger is created, which is
    // require time, so this has to have happened before `require('mineflayer')`.
    for (const name of DEBUG_VARIABLES) {
      assert.equal(process.env[name], undefined, name)
    }
  } finally {
    await body.stop()
    for (const [name, was] of inherited) {
      if (was === undefined) delete process.env[name]
      else process.env[name] = was
    }
  }
})

test('a handshake reports the body it is a handshake with', async () => {
  const { body, port } = await startedBody()
  const peer = controller(port)
  await peer.connected
  try {
    const accepted = await peer.answer(request('handshake', 'h1'))
    assert.equal(accepted.op, 'handshake')
    assert.equal(accepted.requestId, 'h1')
    assert.equal(accepted.protocol, PROTOCOL_VERSION)
    assert.equal(accepted.agentId, AGENT)
    assert.equal(accepted.username, USERNAME)
    // Nothing has been asked of the client yet, so the state is the one it started in.
    assert.equal(accepted.state, 'connecting')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a status report answers the correlated request that asked for it', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    const report = await peer.answer(request('status', 's1'))
    assert.equal(report.op, 'status')
    assert.equal(report.requestId, 's1')
    assert.equal(report.state, 'connecting')
    // The handshake and this status: a controller reconciles its own traffic on this.
    assert.equal(report.requestsHandled, 2)
    // Time in this state cannot exceed time alive, whatever clock either came from.
    assert.ok(report.stateMs <= report.uptimeMs, `${report.stateMs} <= ${report.uptimeMs}`)
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a status snapshot samples its clock once', async () => {
  let current = 1_000
  let samples = 0
  const { body, port } = await startedBody({
    now: () => {
      samples += 1
      return current
    }
  })
  const peer = await handshaken(port)
  try {
    // Construction took the one reading it needs, and the handshake takes none.
    assert.equal(samples, 1)
    current = 1_250
    const report = await peer.answer(request('status', 's1'))
    // One sample for the whole report, so the three numbers in it describe one
    // moment. Read twice, `stateMs` could exceed `uptimeMs` by the gap between them.
    assert.equal(samples, 2)
    assert.equal(report.uptimeMs, 250)
    assert.equal(report.stateMs, 250)
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a backward wall-clock correction cannot reduce status durations', async () => {
  const wallClock = Date.now
  // A clock that has stopped, and one that has gone backwards on the second reading:
  // an NTP correction, a daylight change, or an operator setting the date.
  let answer = 0
  Date.now = () => answer
  try {
    const { body, port } = await startedBody()
    const peer = await handshaken(port)
    try {
      const first = await peer.answer(request('status', 's1'))
      answer = -600_000
      await new Promise((resolve) => setTimeout(resolve, 30))
      const second = await peer.answer(request('status', 's2'))
      // A duration is measured on a monotonic clock, so real time passing can only
      // increase it. Measured on the wall clock these would both be 0, and the second
      // would be negative.
      assert.ok(second.uptimeMs > first.uptimeMs,
        `uptime ${first.uptimeMs} -> ${second.uptimeMs}`)
      assert.ok(second.stateMs >= 0, `stateMs ${second.stateMs}`)
      assert.ok(second.stateMs <= second.uptimeMs)
    } finally {
      peer.stop()
      await body.stop()
    }
  } finally {
    Date.now = wallClock
  }
})

test('a correlated refusal reserves its request id and counts', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    // Addressed to another agent: refused, but the id is spent and the request is
    // one this process answered.
    const wrong = await peer.answer(request('status', 'r1', { agentId: OTHER_AGENT }))
    assert.equal(wrong.error, 'wrongAgent')
    assert.equal(wrong.requestId, 'r1')

    // The same id again is a duplicate, because the refusal reserved it. Read the
    // other way round a controller could spend one id for ever.
    const again = await peer.answer(request('status', 'r1'))
    assert.equal(again.error, 'duplicateRequestId')
    assert.equal(again.requestId, 'r1')

    // The handshake and both refusals are all requests this process handled.
    const report = await peer.answer(request('status', 's1'))
    assert.equal(report.op, 'status')
    assert.equal(report.requestsHandled, 4)
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('an uncorrelated refusal reserves nothing', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    const unreadable = await peer.answer('{ not json')
    assert.equal(unreadable.error, 'malformedFrame')
    // Nothing to echo, so nothing is invented.
    assert.equal('requestId' in unreadable, false)
    const report = await peer.answer(request('status', 's1'))
    assert.equal(report.requestsHandled, 2)
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('every refusal the control channel can reach carries its own code', async () => {
  const { body, port } = await startedBody()
  // Before any handshake, so the one refusal that only exists there is reached first.
  const early = controller(port)
  await early.connected
  try {
    const unagreed = await early.answer(request('status', 'e1'))
    assert.equal(unagreed.error, 'handshakeRequired')
  } finally {
    early.stop()
  }

  const peer = await handshaken(port)
  try {
    // One connection, so the whole set is proven against a single live session - and
    // deliberately one short of `MAX_REFUSALS_PER_CONNECTION`, which the flood case
    // reaches on purpose rather than by accident here.
    const cases = [
      ['not json at all', 'malformedFrame'],
      [request('status', 'r2', { protocol: PROTOCOL_VERSION + 1 }), 'unsupportedProtocol'],
      [request('jump', 'r3'), 'unknownOperation'],
      [request('status', 'r4', { agentId: 'someone.else' }), 'wrongAgent'],
      [request('status', ''), 'invalidField'],
      [request('status', 'r5', { extra: 1 }), 'invalidField'],
      // `h1` is the id the handshake spent, so the same id again is a duplicate.
      [request('handshake', 'h1'), 'duplicateRequestId']
    ]
    assert.ok(cases.length < MAX_REFUSALS_PER_CONNECTION)
    for (const [frame, code] of cases) {
      const refused = await peer.answer(frame)
      assert.equal(refused.error, code, JSON.stringify(frame))
      // A refusal says who refused and never claims to be an answer.
      assert.equal(refused.agentId, AGENT)
      assert.equal(refused.protocol, PROTOCOL_VERSION)
      assert.equal('op' in refused, false, code)
    }
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a frame with no end is refused and its connection closed', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    // No newline, ever: the per-frame bound cannot catch this, because there is no
    // frame. What bounds it is the buffer a connection is allowed to accumulate.
    peer.socket.write('{"pad":"' + 'x'.repeat(MAX_FRAME_BYTES + 1))
    const refused = JSON.parse(await promptly(peer.next(), 'a refusal of an unterminated frame'))
    assert.equal(refused.error, 'frameTooLarge')
    await promptly(peer.closed, 'the close of an unterminated frame')
    // The bound is the connection's, not the process's.
    const fresh = await handshaken(port)
    assert.equal((await fresh.answer(request('status', 's1'))).op, 'status')
    fresh.stop()
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a frame with no end is refused during a shutdown too', async () => {
  const bot = fakeBot()
  // A client that takes time to go, which is what makes the window real: a stopping
  // process reads on until the client's own `end` arrives, so the readable side of
  // this connection is open for the whole of it.
  bot.endDelayMs = 300
  const { body, port } = await startedBody({ bot })
  const peer = await handshaken(port)
  try {
    assert.equal((await peer.answer(request('shutdown', 'd1'))).op, 'shutdown')
    // The same unterminated blob, sent by a peer that has just asked the process to
    // stop. Relaxed while closing, as this bound once was, one shutdown frame turned
    // the only bound on an unterminated line off for every connection at once, and a
    // grace's worth of loopback is what a peer then had to spend.
    peer.socket.write('{"pad":"' + 'x'.repeat(MAX_FRAME_BYTES + 1))
    const refused = JSON.parse(
      await promptly(peer.next(), 'a refusal of an unterminated frame sent while stopping')
    )
    assert.equal(refused.error, 'frameTooLarge')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a flood of malformed frames is bounded and does not end the body', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    // One more than the bound allows, written as one chunk: a controller that cannot
    // frame is not going to stop when it is told, so the body has to stop listening.
    for (let sent = 0; sent <= MAX_REFUSALS_PER_CONNECTION; sent += 1) {
      peer.send('not json at all')
    }
    for (let read = 0; read < MAX_REFUSALS_PER_CONNECTION; read += 1) {
      const line = await promptly(peer.next(), `refusal ${read} of the flood`)
      assert.equal(JSON.parse(line).error, 'malformedFrame', `refusal ${read}`)
    }
    await promptly(peer.closed, 'the close that ends the flood')
    // The client was never touched and the body never left the state it was in.
    assert.equal(body.state(), 'connecting')
    const fresh = await handshaken(port)
    assert.equal((await fresh.answer(request('status', 's1'))).op, 'status')
    fresh.stop()
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('asking before the body is ready never spends the abuse budget', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  const asking = (at) => request('sense', `s${at}`, { radius: 8, limit: 4 })
  try {
    // The body is still `connecting`, so every sense earns `notReady` - the ordinary answer
    // a controller is told to ask again after, not a frame this process could not read. More
    // of them than the abuse budget allows of anything, on a channel that stays open: at 1 Hz
    // through a real login this used to cost a controller its channel, and `offered.forget`
    // took every slot handle on it as well.
    for (let asked = 1; asked <= MAX_REFUSALS_PER_CONNECTION * 2; asked += 1) {
      const refused = await promptly(peer.answer(asking(asked)), `sense ${asked}`)
      assert.equal(refused.error, 'notReady', `sense ${asked}`)
      assert.equal(refused.requestId, `s${asked}`)
    }
    // And a genuine peer error still counts against its own budget of eight, whatever the
    // asking cost: the eighth duplicate closes the channel behind its own refusal.
    for (let bad = 1; bad <= MAX_REFUSALS_PER_CONNECTION; bad += 1) {
      const refused = await promptly(peer.answer(request('handshake', 'h1')), `duplicate ${bad}`)
      assert.equal(refused.error, 'duplicateRequestId', `duplicate ${bad}`)
    }
    await promptly(peer.closed, 'the close the abuse budget earns')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a controller that only ever asks early is cut off at its own bound and no other', async () => {
  // At the shipped number, with nothing injected. It is reachable only because a `notReady`
  // gives its request id back: held, the 64th poll met `MAX_TRACKED_REQUESTS` first and was
  // answered `tooManyRequests` - a peer error - so this bound governed nothing at all.
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  const asking = (at) => request('sense', `s${at}`, { radius: 8, limit: 4 })
  try {
    // Every one of them is answered `notReady`, the last included: the bound closes the
    // channel *behind* the refusal it writes, exactly as the abuse budget does. Not one is
    // `tooManyRequests`, which is what the released ids buy: this many distinct ids beside
    // the handshake's own is past `MAX_TRACKED_REQUESTS`, so retention is caught here.
    assert.ok(MAX_NOT_READY_PER_CONNECTION + 1 > MAX_TRACKED_REQUESTS)
    for (let asked = 1; asked <= MAX_NOT_READY_PER_CONNECTION; asked += 1) {
      const refused = await promptly(peer.answer(asking(asked)), `sense ${asked}`)
      assert.equal(refused.error, 'notReady', `sense ${asked}`)
      assert.equal(refused.requestId, `s${asked}`)
    }
    await promptly(peer.closed, 'the close the notReady budget earns')
    // So the request that would have exceeded the bound is never answered at all.
    peer.send(asking(MAX_NOT_READY_PER_CONNECTION + 1))
    const beyond = await Promise.race([
      peer.next().then(() => 'answered'),
      new Promise((resolve) => setTimeout(() => resolve('nothing'), 100))
    ])
    assert.equal(beyond, 'nothing')
    // One channel closed is not the process ending, so a fresh controller is served.
    const fresh = await handshaken(port)
    assert.equal((await fresh.answer(request('status', 's1'))).op, 'status')
    fresh.stop()
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a notReady gives its request id back and an answered request keeps its own', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  const asking = () => request('sense', 'poll', { radius: 8, limit: 4 })
  try {
    // The same id, over and over, while the body is still connecting. "Ask again" has to
    // mean the same request may be sent again, so none of these is a duplicate.
    for (let asked = 1; asked <= MAX_REFUSALS_PER_CONNECTION + 2; asked += 1) {
      const refused = await promptly(peer.answer(asking()), `poll ${asked}`)
      assert.equal(refused.error, 'notReady', `poll ${asked}`)
      assert.equal(refused.requestId, 'poll')
    }
    // An *answered* request keeps its id, so duplicate detection is unchanged for the round
    // trips that completed - and it is still per connection.
    assert.equal((await promptly(peer.answer(request('status', 'q1')), 'the status')).op, 'status')
    const again = await promptly(peer.answer(request('status', 'q1')), 'the repeat')
    assert.equal(again.error, 'duplicateRequestId')
    // Including the id a poll was answered under: released is released, so it is spent by
    // whatever completes under it next and a duplicate of *that* is refused.
    assert.equal((await promptly(peer.answer(request('status', 'poll')), 'the poll id')).op, 'status')
    const spent = await promptly(peer.answer(request('status', 'poll')), 'the poll id again')
    assert.equal(spent.error, 'duplicateRequestId')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a silent connection is closed with the protocol word for it', async () => {
  // The bound itself, small enough to reach: a controller that connects and says
  // nothing must not hold a slot for the length of the run.
  const { body, port } = await startedBody({ idleMs: 20 })
  const peer = controller(port)
  await peer.connected
  try {
    const refused = JSON.parse(await promptly(peer.next(), 'an idle refusal'))
    assert.equal(refused.error, 'timeout')
    // Said before the close, so a controller learns why rather than seeing a reset.
    await promptly(peer.closed, 'the close of an idle connection')
    assert.equal(body.state(), 'connecting')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('control connection admission is bounded without ending the body', async () => {
  const { body, port } = await startedBody()
  const held = []
  try {
    for (let taken = 0; taken < MAX_CONTROL_CONNECTIONS; taken += 1) {
      held.push(await handshaken(port))
    }
    // The refusal is made before the connection is admitted, so there is no session
    // to correlate it against: an id echoed here would name a request nothing tracked.
    const over = controller(port)
    await over.connected
    const refused = await over.answer(request('handshake', 'h-over'))
    assert.equal(refused.error, 'tooManyRequests')
    assert.equal('requestId' in refused, false)
    await over.closed
    over.stop()

    // A slot is a slot: release one and the next controller is admitted. The close
    // reaches the body on its own turn of the event loop, hence the retry.
    held.pop().stop()
    const replacement = await eventually(async () => {
      const peer = controller(port)
      await peer.connected
      const answered = await peer.answer(request('handshake', 'h-again'))
      if (answered.error !== undefined) {
        peer.stop()
        throw new Error(`still refused: ${answered.error}`)
      }
      return peer
    })
    assert.equal(body.state(), 'connecting')
    replacement.stop()
  } finally {
    for (const peer of held) peer.stop()
    await body.stop()
  }
})

test('a newline-terminated oversized frame is refused and the process stays usable', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    // Terminated, so the per-frame bound is what has to catch it: the unterminated
    // buffer bound never sees a line that arrived complete.
    const padding = 'x'.repeat(MAX_FRAME_BYTES)
    peer.send(request('status', 'big', { agentId: AGENT + padding }))
    const refused = await peer.read()
    assert.equal(refused.error, 'frameTooLarge')
    assert.equal('requestId' in refused, false)
    // The connection is finished with, and the process is not.
    await peer.closed
    const fresh = await handshaken(port)
    const report = await fresh.answer(request('status', 's1'))
    assert.equal(report.op, 'status')
    fresh.stop()
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('the request past the tracked bound is refused and leaves everything usable', async () => {
  const { body, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    // The handshake already spent one, so this fills the set exactly.
    for (let taken = 1; taken < MAX_TRACKED_REQUESTS; taken += 1) {
      const report = await peer.answer(request('status', `s${taken}`))
      assert.equal(report.op, 'status', `status ${taken}`)
    }
    const refused = await peer.answer(request('status', 'one-too-many'))
    assert.equal(refused.error, 'tooManyRequests')
    assert.equal(refused.requestId, 'one-too-many')

    // Refused rather than reserved, which is what makes the bound a bound: the same
    // id is refused the same way again rather than becoming a duplicate.
    const still = await peer.answer(request('status', 'one-too-many'))
    assert.equal(still.error, 'tooManyRequests')

    // The process and a fresh connection are both still usable.
    const fresh = await handshaken(port)
    const report = await fresh.answer(request('status', 'f1'))
    assert.equal(report.op, 'status')
    // Every refusal counted, so a controller can reconcile against this number.
    assert.equal(report.requestsHandled, MAX_TRACKED_REQUESTS + 4)
    fresh.stop()
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a repeated shutdown is acknowledged rather than refused', async () => {
  const { body, port, states } = await startedBody()
  const peer = await handshaken(port)
  try {
    // One write, so the batch is drained before the stop it asked for is carried out.
    peer.pipeline([
      request('shutdown', 'd1'),
      request('shutdown', 'd2'),
      request('status', 's1')
    ])
    const first = await peer.read()
    assert.equal(first.op, 'shutdown')
    assert.equal(first.requestId, 'd1')
    // A retried shutdown is a process being asked to do what it is already doing.
    const second = await peer.read()
    assert.equal(second.op, 'shutdown')
    assert.equal(second.requestId, 'd2')
    // A status is the operation a stopping process genuinely cannot answer.
    const status = await peer.read()
    assert.equal(status.error, 'shuttingDown')

    await body.stop()
    // Teardown ran once, whatever the controller asked for twice.
    assert.deepEqual(states.filter((state) => state === 'stopping'), ['stopping'])
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('a correlated shutdown is answered before anything closes', async () => {
  const { body, bot, states, port } = await startedBody()
  const peer = await handshaken(port)
  try {
    peer.send(request('shutdown', 'd1'))
    const accepted = await peer.read()
    // Read off a socket the body had not closed yet, which is the whole point: a
    // controller learns that its request was taken rather than inferring it from a
    // dropped connection.
    assert.equal(accepted.op, 'shutdown')
    assert.equal(accepted.requestId, 'd1')
    assert.equal(accepted.state, 'stopping')
    await peer.closed
    await body.stop()
    assert.equal(bot.ends, 1)
    assert.deepEqual(states, ['stopping'])
    // The listener is gone with it, so nothing can be asked of the process again.
    const refused = await new Promise((resolve) => {
      const late = net.connect({ host: '127.0.0.1', port })
      late.on('error', (failure) => resolve(failure.code))
      late.on('connect', () => {
        late.destroy()
        resolve('CONNECTED')
      })
    })
    assert.equal(refused, 'ECONNREFUSED')
  } finally {
    peer.stop()
    await body.stop()
  }
})

test('shutdown closes the Minecraft connection before the control channel', async () => {
  const order = []
  const bot = fakeBot()
  // A client that takes time to go, which is the only case in which an order exists
  // to observe. `once` rather than `on`, so this does not join `subscribed`.
  bot.endDelayMs = 25
  bot.once('end', () => order.push('client'))
  const { body, port } = await startedBody({ bot })
  const peer = controller(port)
  await peer.connected
  peer.closed.then(() => order.push('control'))
  try {
    await body.stop()
    await peer.closed
    // The player leaves the world before the channel that answers questions about it
    // goes away, so a controller cannot read a live state off a body already leaving.
    assert.deepEqual(order, ['client', 'control'])
    assert.equal(bot.ends, 1)
  } finally {
    peer.stop()
  }
})

test('a shutdown after an earlier disconnect is prompt', async () => {
  // A grace this process must not spend: the client has already gone, so there is no
  // `end` still to come and nothing to wait for.
  const { body, bot } = await startedBody({ graceMs: 30_000 })
  bot.emit('login')
  bot.emit('end')
  const started = Date.now()
  await body.stop()
  assert.ok(Date.now() - started < 1_000, `stop took ${Date.now() - started}ms`)
})

test('stopping is idempotent and terminal', async () => {
  const { body, bot, states } = await startedBody()
  const first = body.stop()
  const second = body.stop()
  assert.equal(first, second)
  await first
  // The bot's own end arrives because the process is stopping, and must not be
  // reported as a body that dropped.
  bot.emit('end')
  assert.deepEqual(states, ['stopping'])
  assert.equal(body.state(), 'stopping')
  assert.equal(bot.ends, 1)
})

test('a listener that stops the body from its own terminal state stops it once', async () => {
  const bot = fakeBot()
  const states = []
  // A holder, because the listener has to reach the body the call is still building.
  const held = {}
  held.body = createBody({
    agentId: AGENT,
    username: USERNAME,
    minecraft: { host: '127.0.0.1', port: 25565 },
    control: { host: '127.0.0.1', port: await freePort() },
    createBot: () => bot,
    onState: (state) => {
      states.push(state)
      // Exactly what the entry point does: it answers `stopping` by stopping, so it
      // can exit. A re-entrant call must be handed the promise already in flight
      // rather than closing both channels a second time.
      if (state === 'stopping') held.body.stop()
    }
  })
  await held.body.start()
  await held.body.stop()
  assert.deepEqual(states, ['stopping'])
  assert.equal(bot.ends, 1)
})

test('a body stopped before it ever logged in still ends its connection', async () => {
  const { body, bot } = await startedBody()
  // Never logged in, so Mineflayer has injected no `quit`: a stop that reached for
  // one would have found nothing, and nothing would have closed the socket.
  assert.equal(bot.quit, undefined)
  await body.stop()
  assert.equal(bot.ends, 1)
})

test('a client that will not end does not stop the process from stopping', async () => {
  const bot = fakeBot()
  bot.end = () => {
    bot.ends += 1
    throw new Error('already ended')
  }
  // A grace this must not spend: a throw is an answer, so there is nothing to wait for.
  const { body } = await startedBody({ bot, graceMs: 30_000 })
  const started = Date.now()
  await body.stop()
  assert.equal(body.state(), 'stopping')
  assert.equal(bot.ends, 1)
  assert.ok(Date.now() - started < 1_000, `stop took ${Date.now() - started}ms`)
})

test('a client that never ends is waited for only as long as the grace', async () => {
  const bot = fakeBot()
  // Neither an end nor a throw: the case the grace exists for. A client that says
  // nothing must not hold a stopping process open for ever.
  bot.end = () => { bot.ends += 1 }
  const graceMs = 60
  const { body } = await startedBody({ bot, graceMs })
  const started = Date.now()
  await body.stop()
  const spent = Date.now() - started
  assert.equal(bot.ends, 1)
  // The lower bound carries a tolerance because a timer is not a stopwatch: Node
  // schedules `setTimeout` against libuv's cached loop time, which is sampled at the
  // top of a loop iteration and can already be a few milliseconds behind the wall
  // clock this is measured on - so a 60 ms grace can legitimately read as 58. The
  // upper bound is what actually proves the wait is bounded; the lower one only
  // proves it was waited for at all, which a tolerance does not weaken.
  const skewMs = 15
  assert.ok(spent >= graceMs - skewMs,
    `stop took ${spent}ms, short of the ${graceMs}ms grace by more than ${skewMs}ms`)
  assert.ok(spent < 5_000, `stop took ${spent}ms`)
})

test('a body that failed still stops deterministically', async () => {
  const { body, bot, states } = await startedBody()
  bot.emit('error', new Error('refused'))
  assert.equal(body.state(), 'failed')
  await body.stop()
  // `failed` is where the body ended up, `stopping` is what was done about it, and
  // both are said once.
  assert.deepEqual(states, ['failed', 'stopping'])
  assert.equal(body.state(), 'stopping')
})

test('only a loopback literal is accepted, to bind or to connect', async () => {
  const bodyFor = (overrides) => createBody({
    agentId: AGENT,
    username: USERNAME,
    minecraft: { host: '127.0.0.1', port: 25565 },
    control: { host: '127.0.0.1', port: 25599 },
    createBot: () => fakeBot(),
    ...overrides
  })

  // A literal in the whole 127/8 block, and the v6 loopback.
  for (const host of ['127.0.0.1', '127.1.2.3', '::1']) {
    assert.doesNotThrow(() => bodyFor({ minecraft: { host, port: 25565 } }), host)
  }

  // `localhost` is a name a resolver answers, and what it answers can be changed
  // without touching this process; the rest are not loopback at all, and the last
  // three are spellings a lenient parser would take for one.
  const refused = ['localhost', '0.0.0.0', '10.0.0.1', '::', '127.0.0.256',
    '127.099.099.099', '127.0.0.01', '']
  for (const host of refused) {
    assert.throws(() => bodyFor({ minecraft: { host, port: 25565 } }),
      /minecraftHost$/, `minecraft ${JSON.stringify(host)}`)
  }
  // The same rule binds the control channel, which is the half that would otherwise
  // be reachable off the host.
  for (const host of ['localhost', '0.0.0.0']) {
    assert.throws(() => bodyFor({ control: { host, port: 25599 } }),
      /controlHost$/, `control ${JSON.stringify(host)}`)
  }
})

test('a configuration is refused by field name and never by value', async () => {
  const complete = {
    agentId: AGENT,
    username: USERNAME,
    minecraft: { host: '127.0.0.1', port: 25565 },
    control: { host: '127.0.0.1', port: 25599 },
    createBot: () => fakeBot()
  }
  const broken = [
    ['agentId', { agentId: 'not a valid id' }],
    ['username', { username: 'no' }],
    ['minecraftHost', { minecraft: { host: 'example.com', port: 25565 } }],
    ['minecraftPort', { minecraft: { host: '127.0.0.1', port: 70_000 } }],
    ['controlHost', { control: { host: 'example.com', port: 25599 } }],
    ['controlPort', { control: { host: '127.0.0.1', port: 0 } }]
  ]
  for (const [field, override] of broken) {
    let thrown
    try {
      createBody({ ...complete, ...override })
    } catch (failure) {
      thrown = failure
    }
    assert.ok(thrown !== undefined, `${field} was accepted`)
    // The field name and nothing else. A refusal that quoted the value would put a
    // host name, an id or a credential-shaped string into a log this process bounds.
    assert.equal(thrown.message, `the loom body refuses this configuration: ${field}`)
  }
})
