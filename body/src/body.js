'use strict'

/**
 * The body process: one persistent Mineflayer player, and the bounded control
 * server a controller drives its lifecycle over.
 *
 * This module is the whole process apart from its entry point. It is deliberately
 * **policy-free**: it chooses no meal, no threat, no resource, no target, no route
 * and no fight, it registers no gameplay tick listener, and it never decides to
 * reconnect. Everything here is either Mineflayer core lifecycle synchronisation
 * or the control server, and nothing else belongs in it - the mind above the wire
 * owns every choice, and a body that quietly made one would be a second mind.
 *
 * Eight properties are load-bearing rather than stylistic:
 *
 * - **A lifecycle state is observed, never inferred from one event.** Mineflayer
 *   reaches `login` and `death` without ever reaching `spawn` when a body logs in
 *   already dead, so a process that waited on `spawn` alone would sit in
 *   `connecting` for the whole of a dead reconnect and report a lie. Each of the
 *   seven states has its own trigger and `death` is one of them.
 * - **Nothing but a literal loopback address is accepted, to bind or to connect.**
 *   A name such as `localhost` is refused rather than resolved: what it means is
 *   whatever the host's resolver last said, so a loopback promise checked against a
 *   name is not a promise at all.
 * - **One internal plugin is loaded, and the rest of the lifecycle is Loom's own.**
 *   See `INTERNAL_LIFECYCLE_PLUGINS` and `announceLogin`: Mineflayer's default is to
 *   load all of them, which puts a physics interval, autonomous position updates, an
 *   automatic respawn and a whole shelf of action plugins inside a process whose
 *   purpose is to make no choices. `respawn: false` on top of that keeps the decision
 *   to come back to life out of the one internal plugin that would make it.
 * - **The dependency tree's own debug logging is switched off before it is loaded.**
 *   See `silenceDependencyDebug`: `debug` decides whether a logger writes at the
 *   moment it is created, which is `require` time, so an inherited `DEBUG` has to be
 *   gone before `require('mineflayer')` rather than before the first packet.
 * - **Every frame is refused with a closed code and bounded ids, and the bounds are
 *   the protocol's own.** This module adds only the bounds a *socket* needs, which
 *   `protocol.js` cannot state because it has no socket: how long an unterminated
 *   line may grow, how many connections may be admitted, how many request ids one
 *   connection may be remembered for, how many refusals a connection may earn, and
 *   how long a silent one may hold.
 * - **A refusal is a request answered.** A correlated one reserves its request id
 *   and counts in `requestsHandled` before its verdict is read, so a duplicate stays
 *   a duplicate after a refusal and a controller cannot spend one id over and over.
 *   See `handle`.
 * - **An authenticated shutdown is always acknowledged.** Repeating it is an
 *   acknowledgement and not a `shuttingDown` refusal, and only the first one starts
 *   teardown. See `answer`.
 * - **Shutdown is ordered, bounded and idempotent.** The Minecraft connection closes
 *   first, then the control channel, then the promise settles; `stop` returns the
 *   same promise however often it is called and the terminal state is emitted
 *   exactly once. See `stop`.
 */

const net = require('node:net')
const { performance } = require('node:perf_hooks')

const {
  LIFECYCLE_STATES,
  MAX_FRAME_BYTES,
  handshakeAccepted,
  isControlId,
  isUsername,
  decodeRequest,
  encodeResponse,
  refusal,
  shutdownAccepted,
  statusReport
} = require('./protocol')

/** Every frame this generation ends with, and the byte a reader splits on. */
const FRAME_TERMINATOR = '\n'

/** Most control connections the process admits at once. */
const MAX_CONTROL_CONNECTIONS = 32

/**
 * Most request ids one control connection is remembered for.
 *
 * This is what bounds "outstanding requests" on a wire whose answers are
 * synchronous: nothing can be in flight here, so the unbounded thing is the *set*
 * a duplicate is detected against. Past this bound a further id is refused as
 * `tooManyRequests` and is the one correlated refusal that reserves nothing - which
 * is what makes the bound a bound. It still counts in `requestsHandled`, because it
 * is a request this process answered. Duplicate detection therefore stays exact for
 * every id the process did reserve, and neither the process nor a fresh connection
 * is any less usable for having reached it.
 */
const MAX_TRACKED_REQUESTS = 64

/**
 * Most refusals one control connection may earn before it is closed.
 *
 * A malformed frame costs a parse and a short write, so a flood of them is cheap
 * rather than free - and a peer sending nothing this process can read is not a
 * controller. The connection is closed rather than the process stopped, because
 * one bad channel is not a reason to abandon a live player.
 */
const MAX_REFUSALS_PER_CONNECTION = 8

/**
 * How long a control connection may say nothing before it is closed.
 *
 * This is the one place the protocol's `timeout` code is earned: a socket that
 * opens and then holds is indistinguishable from a controller that has gone away,
 * and every admitted handle needs a bounded lifetime. The socket's own idle timer
 * does the counting, so nothing is left behind when it fires.
 */
const CONTROL_IDLE_MS = 30_000

/**
 * How long a stopping process waits for its own channels to close cleanly.
 *
 * A clean close is worth waiting for - it is what puts a correlated shutdown's
 * acceptance on the wire - but it is not worth waiting for indefinitely, because
 * `server.close` settles only once every connection it accepted has gone and a peer
 * that never reads is a peer that could hold the process open for ever. Every wait in
 * `stop` is bounded by this, the last one included: past it whatever this process
 * tracked is destroyed and whatever it does not track is left rather than held on to,
 * so shutdown is prompt whatever the controller does.
 */
const SHUTDOWN_GRACE_MS = 2_000

/** What this module says when it will not start. Never carries a value. */
const UNCONFIGURED = 'the loom body refuses this configuration'

/**
 * The reason a stopping body gives for ending its connection.
 *
 * This is Mineflayer's own default, stated here because the function that supplies
 * it - `bot.quit` - is not available for the whole of a connecting body's life. See
 * `quitBot`.
 */
const QUIT_REASON = 'disconnect.quitting'

/**
 * The only Mineflayer internal plugin this process loads, with
 * `loadInternalPlugins` off so that this list is the whole of it.
 *
 * The loader keeps an internal plugin when `options.plugins[key] || loadInternalPlugins`
 * is true, so naming this one with the flag off loads exactly this one.
 *
 * `health` is where `spawn` and `death` are, and `respawn: false` is what keeps it
 * from making the one gameplay decision it is able to make. `end`, `error` and
 * `connect` are wired to the raw client by the loader itself and need no plugin at
 * all, and `login` is `announceLogin` below. Everything left out is a choice this
 * process must not make - `physics` runs a movement interval and sends autonomous
 * position updates, `kick` performs an automatic respawn, and the rest are action
 * plugins (dig, place, attack, craft, inventory, chat) that exist to do things in
 * the world.
 */
const INTERNAL_LIFECYCLE_PLUGINS = Object.freeze(['health'])

/**
 * The login half of the lifecycle, as a plugin of Loom's own.
 *
 * Mineflayer emits `login` from its `game` plugin, and that plugin is not loadable
 * here whatever else it is useful for: on a `win_game` game-state change it writes
 * `client_command` back at the server, which is the vanilla client's *respawn* -
 * exactly the automatic respawn `respawn: false` exists to prevent, arriving by
 * another route and reachable by any server that sends the packet. Loading it for
 * one event would have put a gameplay decision inside a process whose whole purpose
 * is to make none.
 *
 * So this is the whole of what was wanted from it: the raw `login` packet, said once
 * as the lifecycle event. Nothing is read out of the packet and nothing is written
 * back. What is deliberately given up with the rest of that plugin is `bot.game`,
 * the dimension codec, the client brand and the `ping`/`pong` latency reply - none
 * of which this process reports, and none of which keeps a connection alive:
 * `keep_alive` is answered by minecraft-protocol's own client
 * (`minecraft-protocol/src/client/keepalive.js`), below any plugin.
 */
function announceLogin (bot) {
  bot._client.on('login', () => bot.emit('login'))
}

/**
 * Every environment variable the dependency tree reads to turn on unbounded
 * logging, as a closed set.
 *
 * `debug` - which `minecraft-protocol` and most of the tree below it use - decides
 * whether each of its loggers writes at the moment the logger is *created*, which
 * is when its module is required. So an inherited `DEBUG=*` cannot be neutralised
 * after the fact: by the time a packet arrives the decision has been taken, and
 * every packet then prints peer text, host names and local paths straight to
 * stderr, outside the bounded output path this process promises. `NODE_DEBUG` is
 * Node's own equivalent for `node:net` and reads the same way.
 */
const DEBUG_VARIABLES = Object.freeze([
  'DEBUG',
  'DEBUG_COLORS',
  'DEBUG_DEPTH',
  'DEBUG_FD',
  'DEBUG_HIDE_DATE',
  'DEBUG_SHOW_HIDDEN',
  'NODE_DEBUG',
  'NODE_DEBUG_NATIVE'
])

/** Every field a configuration can be refused for, as a closed set. */
const CONFIGURATION_FIELDS = Object.freeze([
  'agentId',
  'username',
  'minecraftHost',
  'minecraftPort',
  'controlHost',
  'controlPort'
])

class ConfigurationRefusal extends Error {
  constructor (field) {
    super(`${UNCONFIGURED}: ${field}`)
    this.name = 'ConfigurationRefusal'
  }
}

/** Highest port a socket can be asked for. */
const MAX_PORT = 65535

/**
 * Removes every inherited dependency debug setting from `environment`.
 *
 * Deleted rather than emptied, because `debug` treats an empty `DEBUG` as "no
 * namespaces" but other readers in the tree only test for presence.
 */
function silenceDependencyDebug (environment) {
  for (const name of DEBUG_VARIABLES) delete environment[name]
}

/** Whether `host` is a literal loopback address rather than a name for one. */
function isLoopbackAddress (host) {
  if (typeof host !== 'string') return false
  if (host === '::1') return true
  return net.isIP(host) === 4 && host.split('.')[0] === '127'
}

/** Whether `port` is a port a socket can actually be asked for. */
function isPort (port) {
  return Number.isInteger(port) && port >= 1 && port <= MAX_PORT
}

/**
 * The Minecraft version this process was configured for, or nothing.
 *
 * A blank value is *unset* rather than a version, which is the one normalisation this
 * field needs: blanking a variable is how an operator turns a setting off, and
 * `LOOM_BODY_MC_VERSION=` in an env file or a unit file reads as unset to whoever
 * wrote it. Carried through as `''` it would reach Mineflayer as an explicit falsy
 * version instead of leaving it to negotiate one, and nothing refuses it - the version
 * is deliberately not among `CONFIGURATION_FIELDS`, because what a version may say is
 * Mineflayer's to know rather than this process's.
 *
 * The value carried is the trimmed one, so the reading that decides whether this is a
 * version and the reading handed to Mineflayer are the same reading. Padded and
 * returned verbatim, ` 1.21.1` or a newline a here-doc left on the end is a version
 * Mineflayer cannot match, and the operator is told only that the client is
 * `unavailable` - a bounded line that names neither the field nor the stray character.
 */
function configuredVersion (version) {
  if (typeof version !== 'string') return undefined
  const stated = version.trim()
  return stated === '' ? undefined : stated
}

/**
 * The configuration this process will run, or a bounded throw naming the one field
 * it refused.
 *
 * The message carries the field's name from `CONFIGURATION_FIELDS` and never the
 * value, because a refused value is exactly the kind of thing - a path, a host
 * name, a token someone put in the wrong variable - that must not reach a log.
 */
function checkedConfiguration ({ agentId, username, minecraft, control }) {
  const refuse = (field) => {
    throw new ConfigurationRefusal(field)
  }
  if (!isControlId(agentId)) refuse('agentId')
  if (!isUsername(username)) refuse('username')
  if (!isLoopbackAddress(minecraft?.host)) refuse('minecraftHost')
  if (!isPort(minecraft?.port)) refuse('minecraftPort')
  if (!isLoopbackAddress(control?.host)) refuse('controlHost')
  if (!isPort(control?.port)) refuse('controlPort')
  return {
    agentId,
    username,
    minecraft: {
      host: minecraft.host,
      port: minecraft.port,
      version: configuredVersion(minecraft.version)
    },
    control: { host: control.host, port: control.port }
  }
}

/**
 * The Mineflayer options this process runs a body with, and the only ones it runs
 * it with.
 *
 * Five of them are decisions rather than settings. `auth: 'offline'` is why no
 * credential is embedded anywhere in this package: the only server this process
 * will connect to is a loopback one in offline mode. `loadInternalPlugins: false`
 * with `plugins` naming `INTERNAL_LIFECYCLE_PLUGINS` and `announceLogin` is the
 * policy-free invariant, and `respawn: false` keeps the one internal plugin that
 * would make a gameplay decision from making it. `hideErrors: true` stops Mineflayer
 * printing a raw transport error, which is unbounded text that has carried a local
 * path before now; this process reports the bounded `failed` state instead, which is
 * the whole of what a controller needs.
 *
 * `plugins` is one map of both kinds, which is how Mineflayer's loader takes them: a
 * `true` names an internal plugin to keep, and a function is a plugin of the
 * caller's own to inject (`mineflayer/lib/loader.js`).
 */
function botOptionsFor ({ username, minecraft }) {
  const plugins = { loomLifecycle: announceLogin }
  for (const name of INTERNAL_LIFECYCLE_PLUGINS) plugins[name] = true
  const options = {
    host: minecraft.host,
    port: minecraft.port,
    username,
    auth: 'offline',
    respawn: false,
    hideErrors: true,
    loadInternalPlugins: false,
    plugins
  }
  // Only when asked for: Mineflayer negotiates the version itself otherwise, and an
  // explicit `undefined` is a claim about a field this process was not configured for.
  if (minecraft.version !== undefined) options.version = minecraft.version
  return options
}

/** A promise that settles after `ms`, without holding the event loop open. */
function afterGrace (ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    if (typeof timer.unref === 'function') timer.unref()
  })
}

/**
 * A body process, not yet started.
 *
 * `createBot` and `now` are injected so every lifecycle transition, every bound and
 * the whole of shutdown are provable without a Minecraft server and without a
 * clock: the tests drive a fake bot that emits exactly the events Mineflayer emits.
 * `onState` is called once per transition with a state from `LIFECYCLE_STATES` and
 * nothing else, so a caller can log a life without being handed anything unbounded.
 */
function createBody (options) {
  const configuration = checkedConfiguration(options)
  const { agentId, username, control } = configuration
  const createBot = options.createBot
  const now = options.now ?? (() => performance.now())
  const onState = options.onState ?? (() => {})
  // Injected for the same reason the clock is: a bound is only proven by being reached.
  const idleMs = options.idleMs ?? CONTROL_IDLE_MS
  const graceMs = options.graceMs ?? SHUTDOWN_GRACE_MS
  const environment = options.environment ?? process.env

  const startedAt = now()
  let state = 'connecting'
  let stateSince = startedAt
  let requestsHandled = 0
  let bot
  // Set the moment the Minecraft connection is known to have gone, whichever side
  // ended it. A shutdown must not wait on an `end` that has already been emitted:
  // Mineflayer's `end` fires once, so a stop after an earlier disconnect would
  // otherwise sit out the whole grace before touching the control channel.
  let clientGone = false
  let stopped
  let rejectStart
  // Set the moment a shutdown is accepted, which is *before* anything is torn down:
  // a controller that pipelined a status behind its shutdown is refused rather than
  // served, and gets that refusal on the socket it is still holding.
  let closing = false
  const sockets = new Set()
  const server = net.createServer({ allowHalfOpen: false }, (socket) => accept(socket))
  server.on('error', failControlServer)

  /**
   * Moves to `next`, unless the process is already stopping.
   *
   * `stopping` is terminal on purpose: a bot's `end` arrives *because* the process
   * is shutting down, and a state that moved on afterwards would report
   * `disconnected` as the last thing a controller ever heard - a body that dropped
   * rather than one that was asked to stop.
   */
  function transition (next) {
    if (state === 'stopping' || state === next) return
    state = next
    stateSince = now()
    onState(next)
  }

  /** The three numbers a status report carries, measured together. */
  function snapshot () {
    const sampledAt = now()
    const uptimeMs = Math.max(0, Math.trunc(sampledAt - startedAt))
    return {
      state,
      // Never more than the uptime, which is the coherence rule the protocol
      // enforces: the process has been in this state for part of its own life.
      stateMs: Math.min(uptimeMs, Math.max(0, Math.trunc(sampledAt - stateSince))),
      uptimeMs,
      requestsHandled
    }
  }

  function failControlServer (failure) {
    if (rejectStart !== undefined) {
      const reject = rejectStart
      rejectStart = undefined
      reject(failure)
      return
    }
    transition('failed')
    stop()
  }

  /**
   * Subscribes to the five Mineflayer events a lifecycle is made of, and to nothing
   * else.
   *
   * `death` is not folded into anything: a body can log in already dead and reach
   * `login` then `death` without ever reaching `spawn`, so death is its own trigger
   * rather than a thing that happens to a ready body. Nothing here is a tick
   * listener, and nothing reacts to a death or a disconnection by acting in the
   * world - what to do about either is the controller's to say.
   */
  function follow (created) {
    created.on('login', () => transition('loggedIn'))
    created.on('spawn', () => transition('ready'))
    created.on('death', () => transition('dead'))
    created.on('end', () => {
      clientGone = true
      transition('disconnected')
    })
    created.on('error', () => transition('failed'))
  }

  /** Binds the control channel. */
  async function startControl () {
    await new Promise((resolve, reject) => {
      rejectStart = reject
      // Bound to a literal loopback address, checked above: this channel is the
      // process's whole control surface and must not be reachable off the host.
      try {
        server.listen({ host: control.host, port: control.port }, () => {
          rejectStart = undefined
          resolve()
        })
      } catch (failure) {
        rejectStart = undefined
        reject(failure)
      }
    })
    return { control: server.address() }
  }

  /**
   * Opens the Minecraft connection after the control channel is bound.
   *
   * The debug settings go before the require and not merely before the connection,
   * for the reason `DEBUG_VARIABLES` records.
   */
  function startClient () {
    silenceDependencyDebug(environment)
    const launch = createBot ?? require('mineflayer').createBot
    bot = launch(botOptionsFor(configuration))
    follow(bot)
  }

  /** Starts both process channels in their required order. */
  async function start () {
    const started = await startControl()
    startClient()
    return started
  }

  /**
   * Stops, once, whatever else is happening.
   *
   * The order is the contract, and it is Minecraft first: the player leaves the
   * world before the channel that answers questions about it goes away, so a
   * controller is never able to read a live state off a process whose body is
   * already being taken out of the game. The control channel goes second, its open
   * sockets flushed before the listener is closed behind them, so a correlated
   * shutdown's acceptance reaches the wire rather than the controller having to
   * infer it from a dropped connection. The promise settles third, and only once
   * both are actually shut.
   *
   * That order is *waited for* rather than merely written down. The two channels
   * close asynchronously and at different speeds, so a shutdown that fired both and
   * awaited neither has no order at all - whichever handle the event loop happened to
   * finish first won, and a test could observe the intended order on a run that had
   * not enforced it. So the client's own `end` is awaited before the control channel
   * is touched, which makes the sequence causal.
   *
   * Every wait for a clean close is bounded by `graceMs`, the last one included, after
   * which what would not close is taken: a client that never answers must not hold the
   * control channel open, and `server.close` settles only when the last connection it
   * accepted has gone - including one refused before it was ever tracked - so a peer
   * that never reads could otherwise hold a stopping process open for ever.
   *
   * `stopped` is in place *before* the terminal transition is announced, because a
   * listener may answer `stopping` by stopping again - the entry point does exactly
   * that, to exit - and a re-entrant call has to be handed this same promise.
   * Announced first, it would see no promise yet and close everything a second time.
   */
  function stop () {
    if (stopped !== undefined) return stopped
    closing = true
    let settle
    stopped = new Promise((resolve) => { settle = resolve })
    transition('stopping')
    settle((async () => {
      await Promise.race([quitBot(), afterGrace(graceMs)])
      const closed = new Promise((resolve) => server.close(() => resolve()))
      for (const socket of [...sockets]) closeSocket(socket)
      await Promise.race([closed, afterGrace(graceMs)])
      // Whatever would not flush inside the grace is taken rather than waited for.
      for (const socket of [...sockets]) socket.destroy()
      // Defence rather than a bound anything reaches today, and deliberately kept:
      // `server.close` settles only once every connection it *accepted* has gone, and
      // the loop above reaches only what this process tracked. Nothing on the current
      // path is both untracked and slow to go - the one untracked connection, a peer
      // refused past `MAX_CONTROL_CONNECTIONS`, is closed the moment its hundred-byte
      // refusal reaches the kernel, which needs no peer to read it - so this cannot be
      // pinned by a test without contriving the case. Left unbounded, a future
      // untracked handle would hold a stopping process open for ever, and the entry
      // point's exit is behind this promise.
      await Promise.race([closed, afterGrace(graceMs)])
    })())
    return stopped
  }

  /**
   * Ends the Minecraft connection without letting its own failure become this
   * process's.
   *
   * `bot.end` and not `bot.quit`, which is the obvious call and the wrong one:
   * `quit` is a *plugin* function (`mineflayer/lib/plugins/kick.js`) injected once a
   * client is far enough in to run plugins, while `end` is defined on the bot at
   * construction. A body stopped while still connecting - which is every body whose
   * server is not there - therefore had no `quit` to call at all, so nothing ended
   * its socket and the process it was stopping stayed alive. `kick` is not among
   * `INTERNAL_LIFECYCLE_PLUGINS` either, so `quit` is never injected here at all.
   * All it adds is a default reason, which `QUIT_REASON` states instead.
   *
   * The lifecycle listeners are deliberately **left attached**. `transition` is
   * terminal at `stopping`, so none of them can report anything after this, and
   * removing them takes Mineflayer's own error listener with them - after which a
   * client error arriving later is emitted on an emitter with no listeners, which is
   * an uncaught exception rather than a disconnection.
   *
   * A client already ended throws, and that is the ordinary case on the disconnect
   * and error paths - the connection this is closing is the one that just went away.
   * Nothing is logged, because the only thing to log would be the transport's own
   * unbounded text.
   *
   * The returned promise is what makes the shutdown order causal rather than hopeful:
   * it settles when the client's own `end` arrives, so the caller can hold the
   * control channel open until the player has actually left the world. It settles at
   * once for a client that was never started, one that has already gone, and one
   * whose `end` threw - in each case there is nothing left to wait for, and waiting
   * would spend the whole grace to learn that.
   */
  function quitBot () {
    if (bot === undefined || clientGone) return Promise.resolve()
    const client = bot
    // Attached before the call, because a client that ends synchronously would
    // otherwise emit into a promise nothing was listening for yet.
    const ended = new Promise((resolve) => client.once('end', () => resolve()))
    try {
      client.end(QUIT_REASON)
    } catch {
      return Promise.resolve()
    }
    return ended
  }

  /**
   * Flushes whatever is queued on `socket`, then closes it.
   *
   * `end`'s callback fires once every byte has reached the operating system, so the
   * response to a correlated shutdown is on the wire before the handle goes - which
   * is the difference between a controller reading `stopping` and a controller
   * watching its connection drop and having to guess.
   */
  function closeSocket (socket) {
    if (socket.destroyed || socket.writableEnded) return
    socket.end(() => socket.destroy())
  }

  /** Takes one control connection, with every per-connection bound it has. */
  function accept (socket) {
    const session = { pending: '', seen: new Set(), refusals: 0, handshaken: false }
    socket.setNoDelay(true)
    // A control channel is not a reason to fail: a peer that resets its connection
    // has said nothing about the player.
    socket.on('error', () => closeSocket(socket))
    socket.on('close', () => sockets.delete(socket))
    if (sockets.size >= MAX_CONTROL_CONNECTIONS) {
      refuse(socket, session, { code: 'tooManyRequests', close: true })
      return
    }
    sockets.add(socket)
    socket.setTimeout(idleMs)
    socket.on('timeout', () => {
      // A silent peer is refused with the protocol's own word for it, rather than
      // dropped, so a controller that is merely slow learns why its channel closed.
      write(socket, refusal({ agentId, code: 'timeout' }))
      closeSocket(socket)
    })
    socket.setEncoding('utf8')
    socket.on('data', (chunk) => receive(socket, session, chunk))
  }

  /**
   * Reads whatever arrived, one complete frame at a time.
   *
   * The unterminated remainder is bounded as well as each finished line, because a
   * peer that never sends a newline is the one case a per-frame bound cannot see:
   * before this the buffer was the flood's to size.
   *
   * That bound is unconditional, and a shutdown is exactly when it must not be
   * relaxed. A stopping process reads on for as long as it waits for the client's own
   * `end` - up to `SHUTDOWN_GRACE_MS` - so a peer that sends a shutdown and then a
   * newline-free blob is a peer with a whole grace to fill this buffer in, on a scan
   * that rereads the whole accumulation per chunk. Skipped while `closing`, as this
   * once did, the only bound on an unterminated line was off for every connection at
   * once, and the abort that ends is the unbounded fatal report `bin/loom-body.js`
   * exists to keep off stderr. The guard bought nothing either way: no legal frame
   * comes near the bound, and refusing during a shutdown costs nothing, since `write`
   * already no-ops on an ended socket.
   *
   * A shutdown is carried out *after* this drain rather than inside the frame that
   * asked for it, so everything a controller had already sent is answered - each
   * shutdown with its acceptance, a status pipelined behind one with `shuttingDown` -
   * before the socket the answers go on is closed.
   */
  function receive (socket, session, chunk) {
    session.pending += chunk
    let terminator = session.pending.indexOf(FRAME_TERMINATOR)
    while (terminator !== -1) {
      const line = session.pending.slice(0, terminator)
      session.pending = session.pending.slice(terminator + 1)
      if (!handle(socket, session, line)) break
      terminator = session.pending.indexOf(FRAME_TERMINATOR)
    }
    if (Buffer.byteLength(session.pending) > MAX_FRAME_BYTES) {
      refuse(socket, session, { code: 'frameTooLarge' })
    }
    if (closing) stop()
  }

  /**
   * Answers one frame. Returns false once the connection is finished with, so a
   * flood stops being read rather than being answered to the end of its buffer.
   *
   * Everything a readable request id earns is earned here, *before* the verdict is
   * read and before the agent is checked: a correlated refusal is a request this
   * process answered, so it reserves its id and counts in `requestsHandled`. Read
   * the other way round - the way this once was - the refusals reserved nothing, so
   * a controller could spend one id on an unbounded number of malformed, unsupported,
   * unknown-operation or wrong-agent frames and none of them would appear in the
   * count a controller reconciles against. A duplicate therefore stays a duplicate
   * after a refusal.
   *
   * An *uncorrelated* frame reserves nothing, because there is no id to reserve: an
   * unreadable line and a line whose `requestId` is not a bounded control id are
   * both refused without one, and echoing anything there would be inventing a
   * correlation the controller never sent.
   */
  function handle (socket, session, line) {
    if (Buffer.byteLength(line) > MAX_FRAME_BYTES) {
      return refuse(socket, session, { code: 'frameTooLarge' })
    }
    const verdict = decodeRequest(line)
    const requestId = verdict.ok ? verdict.request.requestId : verdict.requestId
    if (requestId === undefined) {
      return refuse(socket, session, { code: verdict.code })
    }
    requestsHandled += 1
    if (session.seen.has(requestId)) {
      return refuse(socket, session, { code: 'duplicateRequestId', requestId })
    }
    if (session.seen.size >= MAX_TRACKED_REQUESTS) {
      // The one correlated refusal that reserves nothing. See MAX_TRACKED_REQUESTS.
      return refuse(socket, session, { code: 'tooManyRequests', requestId })
    }
    session.seen.add(requestId)
    if (!verdict.ok) {
      return refuse(socket, session, { code: verdict.code, requestId })
    }
    // A frame for another agent is not this body's to answer, and answering it would
    // let a controller drive the wrong player through the right socket.
    if (verdict.request.agentId !== agentId) {
      return refuse(socket, session, { code: 'wrongAgent', requestId })
    }
    return answer(socket, session, verdict.request.op, requestId)
  }

  /**
   * The three operations of this generation.
   *
   * A handshake is answered first, and refused while stopping: a process that has
   * begun leaving has nothing true to say about the generation it is agreeing to.
   * The handshake gate is next, and applies to both remaining operations, because a
   * status or a shutdown from a peer that has not agreed the protocol generation is
   * a controller acting on a guess.
   *
   * A shutdown is then **always acknowledged**, and only the first one starts
   * teardown. Refused as `shuttingDown` once stopping had begun - the way this once
   * was - a controller that retried a shutdown it had not yet seen answered, or
   * whose acknowledgement was lost with its connection, would be told its request
   * was rejected by a process that was in fact doing exactly what it asked. A status
   * is the operation that genuinely cannot be answered while stopping, so it is the
   * one refused there.
   */
  function answer (socket, session, op, requestId) {
    if (op === 'handshake') {
      if (closing) {
        return refuse(socket, session, { code: 'shuttingDown', requestId })
      }
      session.handshaken = true
      return write(socket, handshakeAccepted({ requestId, agentId, state, username }))
    }
    if (!session.handshaken) {
      return refuse(socket, session, { code: 'handshakeRequired', requestId })
    }
    if (op === 'shutdown') {
      // Answered before anything closes, so the last thing on the wire is the
      // process saying it is stopping rather than the wire going quiet. `receive`
      // carries the stop out once it has drained what the controller already sent,
      // and `stop` is idempotent, so a repeat starts nothing a second time.
      closing = true
      return write(socket, shutdownAccepted({ requestId, agentId }))
    }
    if (closing) {
      return refuse(socket, session, { code: 'shuttingDown', requestId })
    }
    const { stateMs, uptimeMs } = snapshot()
    return write(socket, statusReport({
      requestId,
      agentId,
      state,
      uptimeMs,
      stateMs,
      requestsHandled
    }))
  }

  /**
   * Refuses one frame, and closes the connection once it has earned enough
   * refusals. Returns whether the connection is still worth reading.
   */
  function refuse (socket, session, { code, requestId, close = false }) {
    write(socket, refusal({ requestId, agentId, code }))
    session.refusals += 1
    if (close || code === 'frameTooLarge' || session.refusals >= MAX_REFUSALS_PER_CONNECTION) {
      closeSocket(socket)
      return false
    }
    return true
  }

  /**
   * Writes one frame, if there is still a socket to write it to.
   *
   * Encoding goes through the protocol's own checked encoder, so a frame this
   * process would not read back is never written - which is what keeps an id it
   * echoes bounded even on the refusal paths.
   */
  function write (socket, frame) {
    if (socket.destroyed || socket.writableEnded) return true
    socket.write(encodeResponse(frame) + FRAME_TERMINATOR)
    return true
  }

  return { start, startControl, startClient, stop, snapshot, state: () => state, agentId, username }
}

module.exports = {
  CONTROL_IDLE_MS,
  DEBUG_VARIABLES,
  INTERNAL_LIFECYCLE_PLUGINS,
  LIFECYCLE_STATES,
  MAX_CONTROL_CONNECTIONS,
  MAX_REFUSALS_PER_CONNECTION,
  MAX_TRACKED_REQUESTS,
  SHUTDOWN_GRACE_MS,
  CONFIGURATION_FIELDS,
  ConfigurationRefusal,
  announceLogin,
  botOptionsFor,
  createBody,
  isLoopbackAddress,
  silenceDependencyDebug
}
