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
 * - **Only the plugins a wire operation reads or drives are loaded, and what they
 *   leave out is Loom's own.** See `INTERNAL_REPORTING_PLUGINS` and
 *   `body/src/gamestate.js`: Mineflayer's default is to load all of them, which puts an
 *   automatic respawn and a shelf of action plugins inside a process whose purpose is to
 *   make no choices. What this table loads is what turns the server's own packets into
 *   readable state - the world, the entities, the clock, the vitals and the inventory -
 *   and nothing else; `respawn: false` on top of that keeps the decision to come back to
 *   life out of the one loaded plugin able to make it. The client-side simulation a walk
 *   is impossible without is deliberately *not* here, because a resident one steps, falls
 *   and writes the body's own position of nobody's asking: it is loaded by
 *   `body/src/movement.js`, which scopes it to one accepted request.
 * - **The dependency tree's own debug logging is switched off before it is loaded.**
 *   See `silenceDependencyDebug`: `debug` decides whether a logger writes at the
 *   moment it is created, which is `require` time, so an inherited `DEBUG` has to be
 *   gone before `require('mineflayer')` rather than before the first packet.
 * - **Every frame is refused with a closed code and bounded ids, and the bounds are
 *   the protocol's own.** This module adds only the bounds a *socket* needs, which
 *   `protocol.js` cannot state because it has no socket: how long an unterminated
 *   line may grow, how many connections may be admitted, how many request ids one
 *   connection may be remembered for, how many peer errors a connection may earn, how
 *   many *not ready* answers it may earn for asking early, and how long a silent one
 *   may hold.
 * - **A refusal is a request answered.** A correlated one reserves its request id
 *   and counts in `requestsHandled` before its verdict is read, so a duplicate stays
 *   a duplicate after a refusal and a controller cannot spend one id over and over.
 *   `notReady` gives the id back, because it is the one answer that asks for the same
 *   request again. See `handle` and `refuse`.
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
  attackReport,
  collectReport,
  craftReport,
  digReport,
  eatReport,
  equipReport,
  fleeReport,
  haltReport,
  sayReport,
  handshakeAccepted,
  isControlId,
  isUsername,
  decodeRequest,
  encodeResponse,
  moveReport,
  placeReport,
  refusal,
  senseReport,
  sleepReport,
  shutdownAccepted,
  statusReport
} = require('./protocol')

const { followWorldState } = require('./gamestate')
const { collectOfferedItem } = require('./collecting')
const { attackOfferedTarget } = require('./combat')
const {
  craftOfferedRecipe,
  installCrafting,
  recipeReading,
  whenInventorySettled
} = require('./crafting')
const { digOfferedBlock, installDigging } = require('./digging')
const { eatFromSlot } = require('./eating')
const { equipOfferedStack } = require('./equipping')
const { installMovement } = require('./movement')
const { installPlacing, placeHeldBlock } = require('./placing')
const { senseMemory } = require('./senses')
const { createSpeech } = require('./speaking')
const { senseReading, vitalsOf } = require('./sensing')
const { installSleeping, sleepInOfferedBed } = require('./sleeping')

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
 * `tooManyRequests`, which reserves nothing - that is what makes the bound a bound. It
 * still counts in `requestsHandled`, because it is a request this process answered.
 * Duplicate detection therefore stays exact for every id the process did reserve, and
 * neither the process nor a fresh connection is any less usable for having reached it.
 *
 * A `notReady` answer reserves nothing either, and for a different reason: its id is
 * reserved and then **released**, because that round trip minted no handle and completed
 * nothing a duplicate could contradict. Retained, an expected poll spent this bound -
 * so a controller asking early through a slow login filled it in 63 frames and then
 * earned `tooManyRequests`, which *is* a peer error and closed the channel out of the
 * abuse budget after eight more. That put the failure [MAX_NOT_READY_PER_CONNECTION]
 * exists to remove back on the same connection, 72 frames later instead of 8. See
 * `refuse`.
 */
const MAX_TRACKED_REQUESTS = 64

/**
 * Most refusals one control connection may earn before it is closed.
 *
 * A malformed frame costs a parse and a short write, so a flood of them is cheap
 * rather than free - and a peer sending nothing this process can read is not a
 * controller. The connection is closed rather than the process stopped, because
 * one bad channel is not a reason to abandon a live player.
 *
 * This is a budget for *peer errors* alone - `malformedFrame`, `wrongAgent`,
 * `duplicateRequestId` and the rest - which is what its rationale above is about.
 * `notReady` is not one of them and has [MAX_NOT_READY_PER_CONNECTION] instead.
 */
/**
 * How long a replacing flight waits for the flight it cancelled to release the path: a cancelled
 * flight answers once its physics frame has drained and the body is at rest, which is bounded by
 * movement's own rest settle, so one second is generous on a loopback server.
 */
const REPLACE_SETTLE_MS = 1_000

const MAX_REFUSALS_PER_CONNECTION = 8

/**
 * Most `notReady` answers one control connection may earn before it is closed.
 *
 * Its own counter rather than a share of [MAX_REFUSALS_PER_CONNECTION], because a
 * `notReady` is not a peer error at all: it is the ordinary answer to a sense taken while
 * the body is still connecting or still loading the chunks around it, and to an eat that
 * arrived while the previous meal is still in the mouth. `sense`'s own header tells a
 * controller what to do about one - ask again - so counted as abuse a controller polling at
 * 1 Hz through a real login lost its channel after eight seconds of doing exactly that,
 * and `offered.forget` took every slot handle on it as well.
 *
 * It is still a bound, because a controller that polls for ever is still holding one of the
 * connections this process admits. The boundary is the one
 * [MAX_REFUSALS_PER_CONNECTION] has, stated here because the two counters are read
 * separately and a reader must not have to infer it twice: the 64th `notReady` is
 * **written**, and the connection is closed behind it, so there is no 65th request on that
 * channel.
 *
 * That boundary is the one a controller actually meets, which it was not while a `notReady`
 * kept its request id: [MAX_TRACKED_REQUESTS] is the same 64 and was reached first, so the
 * 64th poll earned `tooManyRequests` rather than this. Releasing the id is what makes this
 * counter the one that governs, and the two numbers are independent again rather than
 * related by coincidence.
 */
const MAX_NOT_READY_PER_CONNECTION = 64

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
 * Every Mineflayer internal plugin this process loads, with `loadInternalPlugins` off
 * so that this list is the whole of it.
 *
 * The loader keeps an internal plugin when `options.plugins[key] || loadInternalPlugins`
 * is true, so naming these with the flag off loads exactly these.
 *
 * The line the list is drawn on is **report against act**. Every plugin here turns
 * packets the server sends of its own accord into state a reader can ask about, or
 * carries out an operation the wire names, and none of them decides anything:
 *
 *  - `health` is where `spawn` and `death` are, and where `bot.health`, `bot.food` and
 *    `bot.foodSaturation` come from. `respawn: false` is what keeps it from making the
 *    one gameplay decision it is able to make.
 *  - `entities` is the entity index - `bot.entities`, and `bot.entity` for this body.
 *  - `blocks` is the world - `bot.blockAt` and `bot.world`, whose `raycast` is what a
 *    line of sight is measured with.
 *  - `time` is the clock - `bot.time.timeOfDay` and the day count.
 *  - `inventory` is the window state behind `bot.inventory`, and the two operations an
 *    explicit meal is made of, `bot.equip` and `bot.consume`.
 *  - `simple_inventory` is what `inventory`'s own `equip` is defined in.
 *
 * Everything left out is a choice this process must not make. `kick` performs an
 * automatic respawn; `game` writes the vanilla client's respawn on a `win_game`
 * game-state change; and the rest are action plugins (dig, place, craft, chat, villager
 * trading, creative mode) that exist to do things in the world.
 *
 * **`physics` is left out of this table on purpose, and it may not come back into it.** It
 * is the one of the library's own plugins that is not a reader at all: it steps, falls,
 * collides and writes the client's own position roughly once a second, of nobody's asking,
 * which is the autonomy this process exists without. It is nonetheless the only way a body
 * can walk, because a player's movement in Minecraft is *client*-simulated and there is no
 * "server, please walk me there" packet - so it is loaded by `body/src/movement.js`, which
 * holds its simulation, its position reporting and the control packets a walk sets shut
 * unless one accepted `moveTo`, `flee`, or explicitly approaching `collect` or `attack`
 * owns the path.
 * That file's header is the authority on the whole of that scope, including the one part of
 * the plugin's lifetime the library will not let anything stop, and why the scope needs a
 * second seam beside the packet one.
 * Its *reading* half is unscoped and has to be: the clientbound `position` handler is what
 * moves `bot.entity.position` off the origin and what records the facing a meal is sent
 * with, so every perception and every meal depends on it whether or not anything is
 * walking. See `body/src/gamestate.js` for why that handler is the plugin's alone.
 *
 * The line is drawn per *plugin* and not per method, and three plugins bring actions with
 * them that no operation names: `entities` defines `bot.attack`, `bot.mount` and
 * `bot.useOn` beside the entity index, `simple_inventory` defines `bot.toss` and
 * `bot.tossStack` beside explicit equipment, and `physics` defines `bot.elytraFly`
 * beside the simulation a walk needs. They cannot be left behind, so what keeps them
 * unreachable is the closed operation set and the request-scoped modules that call only
 * named primitives. `test/plugins.test.js` pins that as behaviour rather than trusting
 * this list. Note that `bot.quit` is *not* among them: it is `kick`'s, and `kick` is not
 * loaded, which is why `quitBot` cannot rely on it.
 *
 * `end`, `error` and `connect` are wired to the raw client by the loader itself and need
 * no plugin at all. `login` and `bot.game` are `body/src/gamestate.js`, which is the
 * reporting half of `game` with none of the deciding half - and which that file, not this
 * one, states the reasons for.
 *
 * Two loaded plugins write a packet unasked, and neither is a choice about the world.
 * `blocks` answers a chunk batch with `chunk_batch_received`, which is how the server is
 * told what rate to send terrain at; without it a body is sent one batch and no more. And
 * `inventory` answers a window's `state_id` when it acknowledges a click, which is only
 * reached by a click this process makes on purpose. Both are in the same category as the
 * `keep_alive` minecraft-protocol's own client answers below any plugin
 * (`minecraft-protocol/src/client/keepalive.js`). The third such packet is `physics`'s
 * `teleport_confirm`, which is the same kind of thing for the same reason - an unconfirmed
 * teleport leaves the server ignoring the client - and is the one packet of that plugin's
 * that is *allowed out* with no request in flight. It writes others unasked and always has:
 * a position report about once a second, and a control packet whenever the pathfinder sets a
 * control state, which it can still do after the request that owned the path was answered.
 * Every one of those is dropped at the client seam by `movement.js`, whose header owns the
 * set and the reasoning, so `teleport_confirm` is a deliberate hole in that drop set rather
 * than evidence there is none.
 */
const INTERNAL_REPORTING_PLUGINS = Object.freeze([
  'blocks',
  'entities',
  'health',
  'inventory',
  'simple_inventory',
  'time'
])

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
 * Six of them are decisions rather than settings. `auth: 'offline'` is why no
 * credential is embedded anywhere in this package: the only server this process
 * will connect to is a loopback one in offline mode. `loadInternalPlugins: false`
 * with `plugins` naming `INTERNAL_REPORTING_PLUGINS` and `followWorldState` is the
 * policy-free invariant, and `respawn: false` keeps the one loaded plugin that
 * would make a gameplay decision from making it. `hideErrors: true` stops Mineflayer
 * printing a raw transport error, which is unbounded text that has carried a local
 * path before now; this process reports the bounded `failed` state instead, which is
 * the whole of what a controller needs.
 *
 * `physicsEnabled: false` is the sixth, and it is the *starting* state of a setting
 * `body/src/movement.js` owns from then on: the simulation is a request's, so a body with
 * none in flight simulates nothing. It is stated here rather than there because the plugin
 * reads it once, at injection, from the options the loader was given - so a value written
 * afterwards is a value the injection overwrites. `movement.js`'s header is the authority
 * on what the setting is half of.
 *
 * `plugins` is one map of both kinds, which is how Mineflayer's loader takes them: a
 * `true` names an internal plugin to keep, and a function is a plugin of the
 * caller's own to inject (`mineflayer/lib/loader.js`).
 */
function botOptionsFor ({ username, minecraft }) {
  const plugins = { loomWorldState: followWorldState }
  for (const name of INTERNAL_REPORTING_PLUGINS) plugins[name] = true
  const options = {
    host: minecraft.host,
    port: minecraft.port,
    username,
    auth: 'offline',
    respawn: false,
    hideErrors: true,
    loadInternalPlugins: false,
    physicsEnabled: false,
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
  const speech = createSpeech({ now })
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
  // One meal at a time. `bot.consume` cancels an eating task that is still running, so a
  // second eat arriving mid-meal would make the *first* one throw and be reported as
  // `slotInaccessible` - a fault to go and look at, about a meal that was in fact
  // interrupted by the controller's own next request. The second is refused as `notReady`
  // instead, which is the truthful answer: a body with its mouth full is not ready to be
  // given something else to eat.
  let eating = false
  // What recent sense answers offered - the slots an eat may name, and the entities a flee
  // may - which is the whole of what makes either handle request-scoped. One memory for the
  // whole body, so no number of peers can make it grow, but every entry in it names the
  // connection the answer went out on as well as the request id: two of the connections this
  // process allows may each sense under one id, and a handle that meant different things on
  // two sockets would be a handle that named nothing. `senses.js` carries why.
  const offered = senseMemory()
  // The request-scoped wrapper around the pathfinder, built with the client and holding at
  // most one accepted movement. Undefined until then, so a movement request that arrives
  // before the body is `ready` is refused by the state check rather than by its absence.
  let movement
  // The one cancellable or mutating request in flight, naming the control connection that
  // asked for it. Movement and all seven native interactions share this owner so pathing,
  // world mutation, combat and sleep can never overlap or silently displace each other.
  //
  // A **token per run** rather than the session alone, because the clear is asynchronous and
  // the set is not. A controller replacing a movement sends `halt` and its next request in one
  // write, so both frames arrive in one `data` event: the halt settles movement one
  // synchronously, the replacement is accepted and takes this over in the same run of the
  // loop, and only afterwards does movement one's own `finally` get to run. Keyed on the
  // session, that `finally` cleared the *replacement's* ownership - after which the socket
  // closing stopped nothing, and the body walked a route no controller was waiting for while
  // holding the wrapper's one movement slot against every later request. Keyed on the token it
  // finds an owner that is not its own and leaves it alone. The same token is what
  // `movement.js` keys the path and the simulation gate on, so all three are one identity.
  let actionOwner
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
    created.on('death', () => {
      actionOwner?.abort('bodyDied')
      transition('dead')
    })
    created.on('end', () => {
      actionOwner?.abort('disconnected')
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
    // Action plugins are loaded through the module that bounds the operation using them,
    // rather than through `botOptionsFor`'s reporting-only plugin table. Loading one defines
    // a mechanical primitive on the bot; it does not invoke it or select a target. Each
    // module's own header states the request scope around that primitive.
    movement = installMovement(bot)
    installDigging(bot)
    installPlacing(bot)
    installCrafting(bot)
    installSleeping(bot)
    bot.on('entityDead', offered.sawEntityDead)
    bot.on('entityGone', offered.sawEntityGone)
    follow(bot)
  }

  /**
   * Starts both process channels in their required order.
   *
   * A client that cannot be started stops the control channel already listening before the
   * failure is reported, so a failed start leaves nothing open: a throw here once left one
   * listener per failed start holding the test process open with no timer to end it.
   */
  async function start () {
    const started = await startControl()
    try {
      startClient()
    } catch (failure) {
      await stop()
      throw failure
    }
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
   *
   * A movement in flight is stopped first of all, before the client is touched. Its own
   * deadline is a `setTimeout` that is deliberately not unref'd, so a route left running
   * would hold this process open for whatever is left of [MAX_MOVE_DEADLINE_MS] after
   * everything else had closed - and the controller would get no answer to a request it is
   * still holding an id for. Stopped here, the request is answered `cancelled` on a socket
   * the control channel has not closed yet, because Minecraft goes first and the sockets
   * are flushed after it.
   */
  function stop () {
    if (stopped !== undefined) return stopped
    closing = true
    actionOwner?.abort('cancelled')
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
   * `INTERNAL_REPORTING_PLUGINS` either, so `quit` is never injected here at all.
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
    const session = { pending: '', seen: new Set(), refusals: 0, notReady: 0, handshaken: false }
    socket.setNoDelay(true)
    // A control channel is not a reason to fail: a peer that resets its connection
    // has said nothing about the player.
    socket.on('error', () => closeSocket(socket))
    socket.on('close', () => {
      sockets.delete(socket)
      // Nothing can name one of this connection's sense answers again, so keeping them
      // would spend the body's bounded memory on handles no controller can still eat
      // or flee against.
      offered.forget(session)
      // A movement this connection asked for is stopped with it, because the answer to it
      // would have gone out here and now cannot. Left running, a body would keep walking a
      // route no controller is waiting for, hold the wrapper's one movement slot against the
      // next connection, and be refused `notReady` for a request nothing is still driving.
      // Owning at most one movement is what makes this sound: there is no other connection's
      // route to stop by mistake.
      if (actionOwner?.session === session) actionOwner.abort('disconnected')
    })
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
   *
   * One correlated answer gives its id back rather than keeping it, and only one:
   * `refuse` releases the id of a `notReady`, which is the answer that asks the
   * controller to send the same request again. Everything else a readable id earns here
   * it keeps.
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
    return answer(socket, session, verdict.request, requestId)
  }

  /**
   * The sixteen operations of this generation.
   *
   * A handshake is answered first, and refused while stopping: a process that has
   * begun leaving has nothing true to say about the generation it is agreeing to.
   * The handshake gate is next, and applies to every remaining operation, because a
   * request from a peer that has not agreed the protocol generation is a controller
   * acting on a guess.
   *
   * A shutdown is then **always acknowledged**, and only the first one starts
   * teardown. Refused as `shuttingDown` once stopping had begun - the way this once
   * was - a controller that retried a shutdown it had not yet seen answered, or
   * whose acknowledgement was lost with its connection, would be told its request
   * was rejected by a process that was in fact doing exactly what it asked. Every
   * other operation genuinely cannot be answered while stopping, so all of them are
   * refused there: a status would report a process that is going away, and a sense, an
   * eat or a movement would read, use or walk a player that is being taken out of the
   * world. A halt is refused with them rather than let through as a courtesy, because
   * `stop` has already halted whatever was running - so the honest answer to a halt
   * arriving after it is that this process is going away, not `stopped: false`.
   *
   * A status is the residual rather than a branch of its own, which is total because
   * `decodeRequest` admits exactly `OPERATIONS`, and status is the only operation not
   * dispatched above it.
   */
  function answer (socket, session, request, requestId) {
    const op = request.op
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
    if (op === 'sense') return sense(socket, session, request, requestId)
    if (op === 'eat') return eat(socket, session, request, requestId)
    if (op === 'moveTo' || op === 'flee') return move(socket, session, request, requestId)
    if (['dig', 'collect', 'place', 'equip', 'craft', 'attack', 'sleep'].includes(op)) {
      return interact(socket, session, request, requestId)
    }
    if (op === 'halt') return halt(socket, requestId)
    if (op === 'say') return say(socket, session, request, requestId)
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
   * Answers one sense with what the body read, or refuses it as `notReady`.
   *
   * The whole reading is synchronous, which is a property of the client library rather
   * than of this code: `bot.world` is the *sync* world, so a line of sight is a return
   * value and not a promise, and nothing else a sense reads is a round trip either.
   *
   * `notReady` covers both ways a body cannot answer - it is not in the world, or it is
   * and the library has not yet reported something the wire requires - because they are
   * one fact to a controller: ask again. Neither is a fault, and neither may be answered
   * with a default, which is the rule `sensing.js` holds and this refusal is the other
   * half of.
   *
   * The answer is built before anything is remembered about it, so a frame that never
   * went out leaves no slot handle behind: an eat naming it is then `senseForgotten`,
   * which is true, rather than a meal resolved against an inventory no controller was
   * ever shown.
   *
   * A sense that arrives while this body's own craft application owns the inventory is read at
   * that application's settled boundary instead, bounded by `SENSE_SETTLE_MAX_MS`
   * (`crafting.js` states why), and answered then; the request is still one round trip.
   */
  function sense (socket, session, request, requestId) {
    // Answered at once in the ordinary case, so its verdict still decides whether this
    // connection reads on; a deferred answer leaves the connection open until it is written.
    return whenInventorySettled(bot, () => senseNow(socket, session, request, requestId)) ?? true
  }

  function senseNow (socket, session, request, requestId) {
    const answer = senseAnswer(request, requestId)
    if (answer === undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    // Remembered against the connection this answer goes out on as well as the request id,
    // because that pair is the round trip a handle is scoped to: the duplicate-id guard is
    // `session.seen`, which is per connection, so an id alone names an answer another peer
    // may have given.
    //
    // Every kind of handle is remembered in one call, which `senses.js` states as an
    // invariant rather than tidiness: a partial memory could resolve one action and
    // refuse another off the same frame.
    offered.remember(session, requestId, {
      slotOffers: answer.slotOffers,
      entityOffers: answer.entityOffers,
      blockOffers: answer.blockOffers,
      itemOffers: answer.itemOffers,
      recipeOffers: answer.recipeOffers
    })
    return write(socket, answer.frame)
  }

  /**
   * The whole of one sense answer - the reading, and the frame that would carry it - or
   * `undefined` for a body that cannot state one.
   *
   * Both halves are inside one guard because both are ways this body can fail to produce
   * an answer, and a controller has one thing to do about either: ask again. The library
   * reads a scene it does not own, so a throw from below is always possible; the encoder
   * refuses a frame this process would not read back, and `senseReport` therefore throws
   * rather than returning something unsendable.
   *
   * The encoding half is defence in depth rather than a reachable case today, and that is
   * worth stating so it is not read as dead: `MAX_FRAME_BYTES` is derived as a *sum* over
   * the very bounds `sensing.js` refuses outside of, so a legal reading cannot overrun it.
   * The point is that this runs inside a socket `data` listener, where an exception is not
   * a refused request but the end of the process, taking every other agent's connection
   * with it and printing unbounded text from below this process on the way out.
   *
   * Nothing is logged here, for the reason `settleMeal` gives: there is nothing to log
   * except that unbounded text.
   */
  function senseAnswer (request, requestId) {
    if (state !== 'ready') return undefined
    try {
      const read = senseReading(bot, {
        radius: request.radius,
        limit: request.limit,
        retainItems: request.retainItems,
        retainEntities: request.retainEntities,
        seek: request.seek
      })
      if (read === undefined) return undefined
      const { reading, entityOffers, blockOffers, itemOffers, slotOffers } = read
      const recipes = recipeReading(bot, { blockOffers, limit: request.limit })
      if (recipes === undefined) return undefined
      // Only the reading is spread into the frame. `entityOffers` holds the client library's
      // private id and exact object identity, neither of which may reach the wire, so it comes
      // back beside the frame and travels no further than `offered`; `sensing.js` carries why
      // the two are named rather than spread into one object.
      return {
        entityOffers,
        blockOffers,
        itemOffers,
        slotOffers,
        recipeOffers: recipes.recipeOffers,
        frame: senseReport({
          requestId,
          agentId,
          ...reading,
          recipes: recipes.recipes,
          recipesOmitted: recipes.recipesOmitted
        })
      }
    } catch {
      return undefined
    }
  }

  /**
   * Takes one eat, and answers it later.
   *
   * This is the one operation of this generation that cannot be answered inside the
   * frame that asked for it: bringing a stack to hand and using it are both round trips
   * to the server. So the answer is written when the meal is over, on the socket that
   * asked for it, and `receive` goes on draining whatever else the controller had already
   * sent - which is what makes the one-meal-at-a-time rule necessary rather than tidy.
   *
   * **Admission is where an eat's `state` and its `vitals` come from**, and both are read
   * here rather than at write time. The lifecycle state is checked, and the three vitals are
   * read with it, because an eat answer states `ready` by contract and a reading has to come
   * from the body that was in that state: this is the moment the body was `ready` and the
   * moment those numbers were true of it. A body whose vitals cannot be stated *now* is
   * refused `notReady` before anything is consumed, which is honest - nothing has happened,
   * so asking again costs nothing - and it is what leaves `settleMeal` a whole reading to
   * fall back on rather than a refusal to invent for a meal that already happened.
   */
  function eat (socket, session, request, requestId) {
    const admitted =
      state === 'ready' && !eating && actionOwner === undefined ? vitalsOf(bot) : undefined
    if (admitted === undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    eating = true
    // Nothing awaits this here - the answer goes on the socket rather than to a caller -
    // so its own residual failure is caught rather than left to become an unhandled
    // rejection: a body must not abort over one meal it could not answer.
    settleMeal(socket, session, request, requestId, admitted).catch(() => {})
    return true
  }

  /**
   * Carries out one meal and answers it.
   *
   * Every path answers, including a body that could not state an answer at all: a meal
   * with no reply would leave a controller holding a request id for ever and would
   * eventually close a channel that was working perfectly, on its own idle timer.
   *
   * The vitals are read after the meal where that reading can be stated, because they are
   * the evidence a run counts consumption from - the food level an eat left behind, not the
   * one it started with. They are read on a refusal too, for the reason `eatReport` states.
   *
   * **The reading `eat` was admitted under is the fallback, and never a refusal.** A fresher
   * reading is preferred only while the body is still in the state the frame claims and the
   * three vitals can still be stated together; otherwise `admitted` is carried whole and
   * unchanged. Both are real readings of this body and neither is a mix of the two.
   *
   * Each half of that condition is a case this reached. A vitals read fails after a meal for
   * reasons that have nothing to do with the meal - a server plugin raising the food level
   * past vanilla's own ceiling is enough, since nothing clamps it - and answering that with
   * `notReady` told a controller to ask again for a loaf that was already gone and left the
   * run with no `eaten` record of it at all. And a body that died or disconnected mid-meal
   * reads back perfectly statable numbers that are *not* the body this answer is about: the
   * frame says `ready` because that is the state the meal was taken on in, so a reading from
   * after the body left it would be a reading of one body under another one's label.
   *
   * A handle a meal was in fact eaten against is spent here, so one sense answer's offer of
   * one slot buys one meal and no more. `senseMemory.spend` carries why that cannot be left
   * to the re-read at apply time.
   *
   * `eaten` is the whole of that condition because `eatFromSlot` answers it on every path
   * where `bot.consume` resolved and on no other, which is a property that file states and
   * keeps rather than a coincidence of labels: an answer of anything else after a resolved
   * `consume` would leave the handle live, and a controller re-sending its eat would be
   * served a second meal from a snapshot the first one had already contradicted.
   *
   * So the `notReady` below is reached only for a meal that never started - a missing
   * inventory window or a missing food table, both settled before anything is brought to
   * hand - which is what keeps "nothing after `bot.consume` resolves is answered `notReady`"
   * true one level up from `eatFromSlot` as well as inside it.
   *
   * Nothing is logged when this throws, for the same reason `quitBot` logs nothing: the
   * only thing there would be to log is unbounded text from below this process.
   */
  async function settleMeal (socket, session, request, requestId, admitted) {
    try {
      const report = await eatFromSlot(bot, {
        // Resolved against this connection's own answers, so a sense id another peer used
        // is `senseForgotten` here rather than a snapshot of an inventory this controller
        // was never shown. Only the slots of that answer: its entity handles are a flee's
        // and are untouched by a meal.
        offered: offered.recall(session, request.senseId)?.slots,
        slot: request.slot
      })
      if (report?.outcome === 'eaten') offered.spend(session, request.senseId, request.slot)
      if (report !== undefined) {
        const vitals = (state === 'ready' ? vitalsOf(bot) : undefined) ?? admitted
        return write(socket, eatReport({ requestId, agentId, vitals, ...report }))
      }
    } catch {
      // Answered as `notReady` below, like any other meal this body cannot state.
    } finally {
      eating = false
    }
    return refuse(socket, session, { code: 'notReady', requestId })
  }

  /**
   * Takes one explicit native world interaction and answers it on the asking connection.
   *
   * All seven operations share [actionOwner] with movement. Their targets and inventory
   * choices come only from this connection's retained sense answer; the operation modules
   * revalidate those snapshots immediately before the first Mineflayer call.
   */
  function interact (socket, session, request, requestId) {
    if (state !== 'ready' || eating || actionOwner !== undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    if ((request.op === 'collect' || (request.op === 'attack' && request.approach)) &&
        movement === undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    const remembered = offered.recall(session, request.senseId)
    const owner = { session }
    let running
    if (request.op === 'dig') {
      running = digOfferedBlock(bot, {
        offered: remembered?.blockOffers,
        block: request.block,
        timeoutMs: request.timeoutMs,
        now
      })
    } else if (request.op === 'collect') {
      running = collectOfferedItem(bot, movement, {
        offered: remembered?.itemOffers,
        item: request.item,
        approach: request.approach,
        timeoutMs: request.timeoutMs,
        sprint: request.sprint,
        owner,
        now
      })
    } else if (request.op === 'place') {
      running = placeHeldBlock(bot, {
        blockOffers: remembered?.blockOffers,
        slots: remembered?.slots,
        block: request.block,
        slot: request.slot,
        face: request.face,
        timeoutMs: request.timeoutMs,
        now
      })
    } else if (request.op === 'equip') {
      running = equipOfferedStack(bot, {
        slots: remembered?.slots,
        slot: request.slot,
        destination: request.destination,
        timeoutMs: request.timeoutMs,
        now
      })
    } else if (request.op === 'craft') {
      running = craftOfferedRecipe(bot, {
        offered: remembered?.recipeOffers,
        recipe: request.recipe,
        crafts: request.crafts,
        timeoutMs: request.timeoutMs,
        now
      })
    } else if (request.op === 'attack') {
      running = attackOfferedTarget(bot, movement, {
        entityOffers: remembered?.entityOffers,
        slots: remembered?.slots,
        entity: request.entity,
        slot: request.slot,
        attacks: request.attacks,
        approach: request.approach,
        maxPursuitDistance: request.maxPursuitDistance,
        minHealth: request.minHealth,
        timeoutMs: request.timeoutMs,
        sprint: request.sprint,
        owner,
        now
      })
    } else {
      running = sleepInOfferedBed(bot, {
        offered: remembered?.blockOffers,
        block: request.block,
        timeoutMs: request.timeoutMs,
        now
      })
    }
    if (running === undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    actionOwner = {
      token: owner,
      kind: 'interaction',
      session,
      cancel: running.cancel,
      abort: running.abort ?? running.cancel
    }
    settleInteraction(socket, session, request, requestId, running.promise, owner).catch(() => {})
    return true
  }

  /** Waits for one interaction, spends every mutable handle it changed, and writes its report. */
  async function settleInteraction (socket, session, request, requestId, result, owner) {
    try {
      const report = await result
      if (report === undefined) {
        return refuse(socket, session, { code: 'notReady', requestId })
      }
      if (request.op === 'dig' && report.outcome === 'dug') {
        offered.spendBlock(session, request.senseId, request.block)
      } else if (request.op === 'collect' && report.outcome === 'collected') {
        offered.spendItem(session, request.senseId, request.item)
      } else if (request.op === 'place' && report.outcome === 'placed') {
        offered.spend(session, request.senseId, request.slot)
      } else if (request.op === 'equip' && report.outcome === 'equipped') {
        offered.spend(session, request.senseId, request.slot)
      } else if (request.op === 'craft' && report.attempted === true) {
        offered.spendRecipe(session, request.senseId)
      } else if (request.op === 'attack' && report.attacks > 0) {
        offered.spend(session, request.senseId, request.slot)
      }

      let frame
      if (request.op === 'dig') frame = digReport({ requestId, agentId, ...report })
      else if (request.op === 'collect') frame = collectReport({ requestId, agentId, ...report })
      else if (request.op === 'place') frame = placeReport({ requestId, agentId, ...report })
      else if (request.op === 'equip') frame = equipReport({ requestId, agentId, ...report })
      else if (request.op === 'craft') frame = craftReport({ requestId, agentId, ...report })
      else if (request.op === 'attack') frame = attackReport({ requestId, agentId, ...report })
      else frame = sleepReport({ requestId, agentId, ...report })
      return write(socket, frame)
    } catch {
      return refuse(socket, session, { code: 'notReady', requestId })
    } finally {
      if (actionOwner?.token === owner) actionOwner = undefined
    }
  }

  /**
   * Takes one movement - a walk or a flight - and answers it later.
   *
   * **A takeover is refused rather than served, and the wrapper is what refuses it.** One
   * accepted request owns the body's one path for as long as it runs, so a second `moveTo`
   * or `flee` arriving over it is answered `notReady` on whichever connection sent it and
   * changes nothing about the route already walking. That is the same shape as the one-meal
   * rule above and refused for a sharper reason: two goals on one navigator is not a
   * confused answer but a body walking somewhere neither request asked for. A controller
   * that means to replace a movement sends `halt` and then its next request, so the
   * replacement is two decisions on the wire rather than one silent one.
   *
   * The wrapper's `undefined` covers that refusal, a body whose own position the client
   * library cannot state, and a call into the pathfinder that threw - and `notReady` is the
   * truthful answer to all three: ask again. The last of those is why the wrapper guards every
   * call it makes into that library: this runs inside a socket `data` listener, where an
   * exception is not a refused request but the end of the process, and one movement request
   * must not cost an agent its life. Nothing of the failure reaches the controller or a log,
   * because a refusal carries a code from the wire's own closed set and nothing else.
   *
   * A flight's threat is resolved out of this connection's own sense answers, the way a
   * meal's slot is, so a sense id another peer used is `senseForgotten` rather than an
   * ordinal into a scene this controller was never shown. Only the exact `entityOffers` of
   * that answer: its slot offers are a meal's.
   */
  function move (socket, session, request, requestId) {
    const replacing = request.op === 'flee' && request.replace === true && state === 'ready' &&
      movement !== undefined && !eating && actionOwner?.kind === 'movement'
    if (replacing) {
      // A flight that may take over is checked first, and a refused one leaves the running
      // flight alone (r33: gamma's refused flight halted the flight it meant to replace).
      const refused = movement.fleeRefusal({
        offered: offered.recall(session, request.senseId)?.entityOffers,
        entity: request.entity
      })
      if (refused !== undefined) {
        settleMovement(socket, session, request, requestId, refused, {}).catch(() => {})
        return true
      }
      // The running flight ends as cancelled, and this one holds the body from that moment until
      // the cancelled flight has drained, so nothing else can take the body between them.
      const owned = actionOwner
      owned.cancel()
      const reserved = { token: {}, kind: 'movement', session, cancel: () => false, abort: () => false }
      actionOwner = reserved
      takeOver(socket, session, request, requestId, reserved).catch(() => {})
      return true
    }
    if (state !== 'ready' || movement === undefined || eating || actionOwner !== undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    // This run's own identity, minted before the wrapper is asked so that the path, the
    // simulation gate and this file's record of the asking connection are all keyed on one
    // token. See `movementOwner` for the replacement that made a session alone the wrong key.
    const owner = { session }
    const walking = request.op === 'moveTo'
      ? movement.moveTo({
        destination: request.destination,
        tolerance: request.tolerance,
        timeoutMs: request.timeoutMs,
        sprint: request.sprint,
        owner
      })
      : movement.flee({
        offered: offered.recall(session, request.senseId)?.entityOffers,
        entity: request.entity,
        avoid: request.avoid ?? [],
        distance: request.distance,
        timeoutMs: request.timeoutMs,
        sprint: request.sprint,
        owner
      })
    if (walking === undefined) {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    const cancel = () => movement?.halt() ?? false
    actionOwner = { token: owner, kind: 'movement', session, cancel, abort: cancel }
    // Nothing awaits this here, for the reason `eat` gives: the answer goes on the socket
    // rather than to a caller, so its own residual failure is caught rather than left to
    // become an unhandled rejection.
    settleMovement(socket, session, request, requestId, walking, owner).catch(() => {})
    return true
  }

  /**
   * Starts a replacing flight once the flight it cancelled has released the path, holding the
   * body under [reserved] meanwhile, within [REPLACE_SETTLE_MS]; a path still held after that is
   * `notReady`, the answer a flight behind running work always had.
   */
  async function takeOver (socket, session, request, requestId, reserved) {
    const until = Date.now() + REPLACE_SETTLE_MS
    while (movement?.busy() === true && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 10))
    if (actionOwner !== reserved) return refuse(socket, session, { code: 'notReady', requestId })
    actionOwner = undefined
    return move(socket, session, { ...request, replace: false }, requestId)
  }

  /**
   * Waits out one movement and answers what became of it.
   *
   * `walking` is awaited whether the wrapper answered with a report or with a promise of
   * one: a request refused by a bound before anything moved - a destination past the reach,
   * a threat handle that resolves to nothing - is settled inside the frame that asked for
   * it, and every other movement is settled when it stops. Both are one answer on one
   * socket, which is what keeps a controller from having to tell the two apart.
   *
   * Which encoder is used is the request's own operation and never the report's shape,
   * because the two reports are different frames rather than one frame with optional
   * fields: `fleeReport` requires the separation pair and `moveReport` has no place for it.
   * The wrapper is what keeps that relation - it measures separation only for a movement
   * given a threat, and only `flee` gives it one - so a `moveTo` cannot produce a report the
   * flee encoder would accept, or the reverse.
   *
   * **`notReady` is honest here in a way it is not after a meal.** A movement that cannot be
   * reported has still moved the body, but nothing about it was spent: a destination is
   * absolute, so re-asking walks whatever is left of the way, and an entity handle is not
   * consumed by a flight, so re-asking runs from the same creature again. The only way this
   * is reached is a body whose own position the library can no longer state - it has left the
   * world - which is exactly the case "ask again" describes.
   *
   * Nothing is logged when this throws, for the reason `settleMeal` gives: the only thing
   * there would be to log is unbounded text from below this process.
   *
   * The ownership is given back only if it is still this run's, because this `finally` runs a
   * microtask after the request ended and a replacement may already have taken the path over by
   * then. `movementOwner` carries the whole of why.
   */
  async function settleMovement (socket, session, request, requestId, walking, owner) {
    try {
      const report = await walking
      if (report !== undefined) {
        const frame = request.op === 'moveTo'
          ? moveReport({ requestId, agentId, ...report })
          : fleeReport({ requestId, agentId, ...report })
        return write(socket, frame)
      }
    } catch {
      // Answered as `notReady` below, like any other movement this body cannot state.
    } finally {
      if (actionOwner?.token === owner) actionOwner = undefined
    }
    return refuse(socket, session, { code: 'notReady', requestId })
  }

  /**
   * Stops whatever movement is running, and says whether there was one.
   *
   * **A halt is about the body's one path rather than about a handle, so it carries none and
   * is not scoped to the connection that started the movement.** Answering a peer's halt
   * `stopped: false` would be a lie about the body - something was moving - and there is no
   * second field to say "not yours" in. That is no wider a wire than it already is: any peer
   * that has handshaken can `shutdown` the whole process, so one that can stop a route can
   * already do strictly more than that. A socket *closing* is the opposite case and is scoped,
   * because a close is not a request and may not act on a peer's behalf.
   *
   * The movement it stops gets its own answer, on its own socket, with outcome `cancelled` -
   * so a cancellation is two frames stating two different facts rather than one frame the
   * mover never hears about. Ordering between them is the event loop's and is not a property
   * a controller may rely on: this answer is written synchronously and the mover's resolves a
   * microtask later, but both go out.
   */
  /**
   * Sends one line of player chat, or refuses it as `notReady`.
   *
   * Synchronous, so it takes no exclusive owner and leaves none outstanding: it neither
   * waits on nor blocks a movement or interaction. A send that throws is `notReady`
   * rather than `sent`, because nothing proves the line left.
   */
  function say (socket, session, request, requestId) {
    if (state !== 'ready') return refuse(socket, session, { code: 'notReady', requestId })
    let outcome
    try {
      outcome = speech.say(bot, request.text)
    } catch {
      return refuse(socket, session, { code: 'notReady', requestId })
    }
    return write(socket, sayReport({ requestId, agentId, outcome }))
  }

  function halt (socket, requestId) {
    const owned = actionOwner
    const stopped = owned?.cancel() ?? false
    // Movement cancellation releases the path synchronously. Give ownership back in the
    // same socket data turn so a pipelined halt-then-move can be admitted; the old request's
    // token-guarded `finally` cannot clear that replacement. Other interactions may answer
    // `cancelled` synchronously too, but only movement has a documented replacement pattern.
    if (stopped && owned?.kind === 'movement' && actionOwner === owned) {
      actionOwner = undefined
    }
    return write(socket, haltReport({ requestId, agentId, stopped }))
  }

  /**
   * Refuses one frame, and closes the connection once it has earned enough
   * refusals. Returns whether the connection is still worth reading.
   *
   * Two budgets rather than one, because a `notReady` is not a peer error but the answer a
   * controller is told to ask again after - see [MAX_NOT_READY_PER_CONNECTION]. The
   * counters are independent in both directions: asking early can never spend the abuse
   * budget, and a malformed frame in between can never be paid for out of the looser one.
   * Both close the same way, once whichever budget the code belongs to is spent.
   *
   * A `notReady` also **releases the request id** `handle` reserved for it, because it is
   * the one answer this process gives that leaves nothing behind: no handle was minted, no
   * state moved, and the controller was told to ask again. Held, the poll it invites spent
   * [MAX_TRACKED_REQUESTS] instead and the channel died of `tooManyRequests` - so the
   * release is what makes the looser budget the one that governs. An id offered again is
   * therefore not a duplicate, which is exactly what "ask again" has to mean; every id an
   * *answered* request spent is kept, so duplicate detection is unchanged for the round
   * trips that completed.
   */
  function refuse (socket, session, { code, requestId, close = false }) {
    write(socket, refusal({ requestId, agentId, code }))
    let spent
    if (code === 'notReady') {
      if (requestId !== undefined) session.seen.delete(requestId)
      session.notReady += 1
      spent = session.notReady >= MAX_NOT_READY_PER_CONNECTION
    } else {
      session.refusals += 1
      spent = session.refusals >= MAX_REFUSALS_PER_CONNECTION
    }
    if (close || code === 'frameTooLarge' || spent) {
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
  INTERNAL_REPORTING_PLUGINS,
  LIFECYCLE_STATES,
  MAX_CONTROL_CONNECTIONS,
  MAX_NOT_READY_PER_CONNECTION,
  MAX_REFUSALS_PER_CONNECTION,
  MAX_TRACKED_REQUESTS,
  SHUTDOWN_GRACE_MS,
  CONFIGURATION_FIELDS,
  ConfigurationRefusal,
  botOptionsFor,
  createBody,
  isLoopbackAddress,
  silenceDependencyDebug
}
