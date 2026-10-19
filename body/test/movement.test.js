'use strict'

/**
 * The request scope around the route: one owner, four measurements, eight endings.
 *
 * The library's own behaviour is `libraries.test.js`'s subject - the pinned versions and the
 * permissive defaults the policy turns off. This file is about what `movement.js` adds that
 * `mineflayer-pathfinder` has none of, and almost all of it is driven against the *real*
 * library walking a *real* bot rather than against a stand-in for either.
 *
 * That is affordable because a player's movement is client-simulated: Prismarine physics
 * steps the body, the library searches the chunk the harness built, and neither needs a
 * server, a network, an account or a credential. So `arrived`, `noRoute`, `timedOut`,
 * `cancelled` and `outOfRange` are all real routes over real ground, and the four
 * measurements are what the physics actually did rather than numbers a fake was told to
 * report.
 *
 * Two outcomes cannot be reached that way, and only those two use a recorder in place of the
 * plugin. `stalled` needs a body wedged against something for three of the library's own
 * 3.5 s futility windows, and `overTravelled` needs a body to walk 128 blocks - both of them
 * minutes of wall clock in a suite that has to run in seconds. The recorder is also where the
 * *goal* is read, because the plugin keeps its current goal private and there is no reader
 * for it: what the library was handed is observable only from the side that receives it.
 *
 * **No case here asserts source text.** The policy is read off the objects the library was
 * given, the bounds are reached by driving past them, and cancellation is proven by counting
 * the process's own live timers and the bot's own live listeners before and after.
 */

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { afterEach, test } = require('node:test')

const { goals: libraryGoals } = require('mineflayer-pathfinder')
const { Vec3 } = require('vec3')

const {
  ARRIVAL_SETTLE_TICKS,
  MAX_STALL_RETRIES,
  MAX_TRAVEL_BLOCKS,
  MOVEMENT_CONTROL_PACKETS,
  MOVEMENT_REPORT_PACKETS,
  MOVE_REACH_BLOCKS,
  SCOPED_MOVEMENT_PACKETS,
  SEARCH_BUDGET_MS,
  SEARCH_RADIUS_BLOCKS,
  NO_SEARCH_SLICE,
  blocksApart,
  createMovement,
  installMovement
} = require('../src/movement')
const { CONTROL_IDLE_MS } = require('../src/body')
const { MAX_MOVE_DEADLINE_MS, MAX_RETREAT_DISTANCE } = require('../src/protocol')
const H = require('./harness')

/** Every bot event `movement.js` subscribes to, which cancellation must give all of back. */
const ROUTE_EVENTS = Object.freeze([
  'physicsTick', 'goal_reached', 'path_update', 'path_reset', 'entityDead'
])

/** Every movement control the library drives, all of which must be off after a halt. */
const CONTROLS = Object.freeze([
  'forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak'
])

/** Whether one control packet can only turn a server-side control off. */
function deactivatesControl ({ name, parameters }) {
  if (name === 'entity_action') {
    return ['stop_sprinting', 'stop_sneaking', 1, 4].includes(parameters.actionId)
  }
  return name === 'player_input' &&
    Object.values(parameters.inputs).every((value) => value === false)
}

/**
 * How many times the body has been stepped, and how many times it has said where it is.
 *
 * The two halves of the simulation, counted from outside it: `physicsTick` is emitted only
 * while the step simulation is enabled, and [MOVEMENT_REPORT_PACKETS] is the whole set of
 * packets that reports where that simulation left the body. Reading both is what makes
 * "the simulation is a request's" a measurement rather than a claim about a plugin table -
 * turning one off and leaving the other on is exactly the half-fix this counts against.
 *
 * The report packets only, deliberately: the *control* packets the gate also drops are written
 * by a control state changing rather than by a step, so counting them here would fold two
 * different things into one reading. `refusedSearchHeldNothing` is where those are counted.
 */
function simulation (bot, client) {
  const counted = { ticks: 0 }
  bot.on('physicsTick', () => { counted.ticks += 1 })
  return () => ({
    ticks: counted.ticks,
    reports: client.writes.filter((write) => MOVEMENT_REPORT_PACKETS.includes(write.name)).length
  })
}

/** Where a placed body stands, and what every distance below is measured from. */
const STANDING = Object.freeze([8.5, 64, 8.5])

/** The exact private identity one sense answer would retain for an entity. */
function offeredEntity (bot, id) {
  return [{ id, entity: bot.entities[id], dead: false }]
}

/** A deadline no case here means to reach, but which bounds the suite if a route hangs. */
const AMPLE_MS = 8000

/**
 * How far the small platform reaches from the body, in blocks. See [platform].
 *
 * Two gives 25 walkable nodes, and the ground refusing a route off it is measured at 21 to 28 ms
 * on the host these numbers were taken on - a twentieth of [SEARCH_BUDGET_MS], which is what keeps
 * `noRoute` the answer on a busy host too.
 */
const PLATFORM_RADIUS = 2

/**
 * Every node a search off [platform] can reach, which is the platform's own tiles and no more.
 *
 * The one graph in this file that a real search is *certain* to exhaust, which is what makes it
 * the place a graph-size pin belongs. An exhausted search visited every node it could build, so
 * asserting the count *exactly* says both halves at once: the search reached all of the ground
 * and none of the air. Measured at 25 against this radius, and at 49 against a radius of three,
 * so the assertion tracks the graph rather than merely bounding it.
 *
 * The same assertion against the widest search's own ceiling could say neither, because the
 * budget stops that search at about a fifth of its graph - so a growth of any size still
 * satisfies it. See [WIDEST_SEARCH_DESTINATION].
 */
const PLATFORM_NODES = ((2 * PLATFORM_RADIUS) + 1) ** 2

/**
 * How many chunk columns each way [openWorld] loads for the widest search.
 *
 * Four, because that is where the graph stops growing: the widest search the wire admits expands
 * 4877 nodes at a span of two, 5940 at three and 6115 at four *and* at five. What bounds it past
 * that is the library's own `maxCost = startNode.h + searchRadius`, not the ground - so a scene
 * this wide is the whole of what a request can ask a search to look at, and a wider world buys
 * nothing. The measured 6115-node ceiling is recorded beside [WIDEST_SEARCH_DESTINATION].
 */
const WIDEST_SPAN_CHUNKS = 4

/**
 * How many chunk columns each way a flight needs, which is fewer.
 *
 * A flight's graph is bounded by the separation it has to gain rather than by the radius, and it
 * measured 301 nodes at every span from two to twelve - so ground enough to walk the full
 * `MAX_RETREAT_DISTANCE` is ground enough, and eighty-one chunk columns would only be eighty-one
 * columns of allocation for a suite to build twice.
 */
const FLIGHT_SPAN_CHUNKS = 2

/**
 * How far past its budget one search may run before the bound is broken, in milliseconds.
 *
 * The library checks its clock once per node it pops, so a search overruns by whatever the
 * expansion in progress costs *plus* whatever the operating system takes the process away for in
 * the middle of it - and the second of those is not a number any suite can bound. Measured here
 * at 0.1 to 8 ms on an idle host and up to 173 ms with sixteen busy processes on twelve cores
 * beside two other suites, so the allowance is the budget itself: generous enough that the
 * assertion is about Loom's bound rather than about how busy the machine is, and still a bound,
 * because a search left to run to its own end costs several times the budget.
 */
const STALL_ALLOWANCE_MS = SEARCH_BUDGET_MS

/**
 * Where the widest search the wire admits is sent, and how big that search is.
 *
 * A destination at the far edge of [MOVE_REACH_BLOCKS] and one block up, so it stands in air and
 * no route reaches it. It is the widest search *any* request can produce, which is an arithmetic
 * fact rather than a lucky scene: the library prunes a node when `g + h > maxCost` and derives
 * `maxCost` as the start node's heuristic plus `SEARCH_RADIUS_BLOCKS`, so the graph is largest
 * when the start heuristic is - and `MOVE_REACH_BLOCKS` is the largest a `moveTo` may name.
 * Measured across placements at that reach: 6115 nodes here, against 5043 for the same distance
 * split between the horizontal and the vertical, and 2897 for a destination straight up.
 *
 * A `flee` cannot beat it either, and the reason is worth writing down because it looks as though
 * it should. A flee's inverted goal makes `g + h` roughly zero for every node walked away from
 * the threat, so the radius prunes nothing at all - but the same inversion means the goal is
 * *satisfied* by the first node further from the threat than the request's own `distance`, so the
 * graph is a ball of radius `MAX_RETREAT_DISTANCE` at most, and 301 nodes in practice. Making that
 * goal unsatisfiable needs a threat far enough away to disable the radius, which is at least
 * `SEARCH_RADIUS_BLOCKS` off, and a threat that far is already further than any `distance` the
 * wire allows - so the flight ends on its first pop. The two cannot both be had.
 *
 * Exhausting this graph is measured at 1.8 to 2.9 s, and up to 5.2 s on a loaded host, against a
 * 516 ms budget. That margin is recorded rather than relied on, and it is deliberately *not* what
 * any assertion here rests on: no case asserts which of the two endings a real search reached,
 * because the same 6115-node graph measured 447 ms with solid ground under the floor and 3064 ms
 * with air under it - a sevenfold swing from a block nobody walks on. The endings are proven where
 * they are decidable instead, at the status seam, and this scene is only ever asked for the stall
 * and for what a refused search leaves behind.
 *
 * The node count is a *measurement* here rather than a pin, for the reason [PLATFORM_NODES]
 * gives: under the production budget this search is stopped at a fraction of the graph, so an
 * assertion against 6115 would be satisfied by a graph of any size at all.
 */
const WIDEST_SEARCH_DESTINATION = Object.freeze([STANDING[0] + MOVE_REACH_BLOCKS - 0.5, 65, STANDING[2]])

/**
 * How many nodes the worst search this body can be asked for *and succeed at* expands.
 *
 * A flight at the wire's own `MAX_RETREAT_DISTANCE`, measured at 301 nodes and 102 to 182 ms on
 * the host [SEARCH_BUDGET_MS] was sized against - which is the number that budget has to pay for,
 * so a change that expands more of them is a change that makes a body's worst legitimate request
 * unaffordable. Asserted as an upper bound, because a busy host can only make a search stop
 * *earlier*: the work is the deterministic half and the clock is not.
 */
const WORST_REACHABLE_NODES = 301

/**
 * Every search the library reported, as the status, the synchronous cost and the work.
 *
 * `path_update` is the only place the library states these, and `time` on it is exactly what one
 * `compute()` call blocked the event loop for - so this is how the stall bound is measured
 * without a stopwatch of the suite's own around an `await` that also waits for a physics tick.
 */
function searchesOf (bot) {
  const seen = []
  bot.on('path_update', (results) => {
    seen.push({ status: results.status, ms: results.time, visited: results.visitedNodes })
  })
  return () => seen
}

/** The body process the wire case starts, so a failing assertion still gives it up. */
let openBody

/**
 * Gives up the bot and the body process, whether or not the case they belong to passed.
 *
 * `H.disconnected` is not optional and its own comment says why: a bot left logged in keeps
 * a 50 ms physics interval and the test process never exits. A started body is the same
 * hazard through a different resource - its control server keeps a listening socket - and
 * stopping it only on the success path is what turns one failed assertion into a hung file
 * with no diagnostics at all.
 */
afterEach(async () => {
  H.disconnected()
  const stopping = openBody
  openBody = undefined
  await stopping?.stop()
})

/**
 * How many timers the process is holding open right now.
 *
 * The harness's bots keep a 50 ms physics interval each, which counts here too - so this is
 * only ever read as a *difference* across one request. That is what makes "cancellation
 * leaves no timer" a measurement rather than a reading of the source: a deadline still armed
 * after a halt shows up as one more live timer than there was before the request.
 */
function liveTimers () {
  return process.getActiveResourcesInfo().filter((kind) => kind === 'Timeout').length
}

/**
 * A real bot standing on real ground at [STANDING], with the movement wrapper installed.
 *
 * The rest of `overrides` goes to [H.ground], so a case shapes the world it needs - a wall to
 * route round, a rise to step up - and everything outside that one chunk column is unloaded,
 * which is what makes a destination out there genuinely unreachable rather than air.
 *
 * Every case here runs under exactly the search policy a real body runs under, and nothing in
 * this file may buy determinism with a number production does not use: with no per-call slice
 * there is no resumed search, so what a route answers depends on the ground rather than on how
 * often this host schedules a physics tick. `movement.js`'s [NO_SEARCH_SLICE] is the authority.
 */
async function placed (overrides = {}) {
  return placedIn((bot) => H.ground(bot, overrides))
}

/** The same body, in whatever world `buildWorld` puts under it. */
async function placedIn (buildWorld) {
  const { bot, client } = await H.realBot()
  const movement = installMovement(bot)
  H.spawned(client)
  client.emit('position', H.positionPacket({
    x: STANDING[0], y: STANDING[1], z: STANDING[2], yaw: 0, pitch: 0, teleportId: 1
  }))
  buildWorld(bot)
  // Long enough for the position to have been applied and the first physics ticks to have run,
  // so a walk starts from a body that is standing rather than one that is still arriving.
  await H.settled(200)
  return { bot, client, movement }
}

/**
 * A stone floor `radius` blocks each way from the body, and nothing else in the column.
 *
 * For the one case whose subject is the *ground* refusing a route. A small platform makes that
 * refusal cost a few dozen node expansions rather than the two hundred and fifty-six a whole
 * chunk column costs, which is what keeps the case's answer a fact about the ground on a loaded
 * host too: the search has to exhaust its graph inside [SEARCH_BUDGET_MS] or the honest answer
 * becomes `searchOverBudget` instead. Bounded work rather than a bounded clock.
 */
function platform (bot, radius) {
  const column = new H.Chunk(H.OVERWORLD)
  const stone = H.registry.blocksByName.stone.defaultState
  const [x, , z] = STANDING.map(Math.floor)
  for (let dx = -radius; dx <= radius; dx += 1) {
    for (let dz = -radius; dz <= radius; dz += 1) {
      column.setBlockStateId(new Vec3(x + dx, 63, z + dz), stone)
    }
  }
  bot.world.setColumn(0, 0, column)
}

/**
 * Flat stone out to `span` chunk columns each way, which is the widest scene this file builds.
 *
 * Only the worst-case search needs it: a search's cost is the nodes it expands, and a body on a
 * lone column cannot expand more than that column holds. This is what a body on a real server
 * stands on, and it is the difference between a search that exhausts in a tenth of a second and
 * one that would run for three.
 */
function openWorld (bot, span) {
  const stone = H.registry.blocksByName.stone.defaultState
  for (let cx = -span; cx <= span; cx += 1) {
    for (let cz = -span; cz <= span; cz += 1) {
      const column = new H.Chunk(H.OVERWORLD)
      for (let x = 0; x < 16; x += 1) {
        for (let z = 0; z < 16; z += 1) column.setBlockStateId(new Vec3(x, 63, z), stone)
      }
      bot.world.setColumn(cx, cz, column)
    }
  }
}

/**
 * A bot whose pathfinder is a recorder, for the two outcomes a real route cannot reach here.
 *
 * Only what `movement.js` reads: the registry a `Movements` is built from, the entity whose
 * position every measurement is taken from, the entity table a flee resolves through, the two
 * setters, and `isMoving`. `goals` and `movements` are what the library would have been handed.
 *
 * `isMoving` answers false because a recorder never holds a route, which is what a settle reads
 * it for: the residue repair is a no-op against a library that put nothing back.
 */
function drivable ({ x = 0, y = 64, z = 0 } = {}) {
  const bot = new EventEmitter()
  bot.registry = H.registry
  bot.entity = { position: new Vec3(x, y, z) }
  bot.entities = {}
  bot.pathfinder = {
    goals: [],
    movements: undefined,
    isMoving () { return false },
    setGoal (goal) { this.goals.push(goal) },
    setMovements (movements) { this.movements = movements }
  }
  return bot
}

/**
 * The simulation gate, counted rather than performed.
 *
 * The token check is the real gate's own, because that is half of what these cases are about: a
 * release has to arrive with the token that took it, or a stale settle could shut a later
 * request's simulation. Nothing here touches a client, which is exactly what the wrapper's
 * default gate does not do either - see `movement.js`'s `inertSimulation`.
 */
function countedGate () {
  const gate = {
    taken: 0,
    released: 0,
    open: false,
    draining: false,
    holder: undefined,
    take (token) {
      gate.taken += 1
      gate.open = true
      gate.holder = token
    },
    drain (token) {
      if (gate.holder === token) gate.draining = true
    },
    release (token) {
      if (gate.holder !== token) return
      gate.released += 1
      gate.open = false
      gate.draining = false
      gate.holder = undefined
    }
  }
  return gate
}

/**
 * A wrapper over a bot whose library surface fails where `how` says it should.
 *
 * The four guarded calls are reached at three different moments - the policy build before the
 * promise exists, the two setters that hand the route over, and the `setGoal(null)` every ending
 * goes through - so each `how` breaks exactly one of them and leaves the rest working. A
 * throwing property setter and a throwing `blocksByName` are stand-ins for "the library threw",
 * which is the whole of what the guards claim to survive; the registry override is layered over
 * the shared one with `Object.create` rather than written into it, because every other case in
 * this file reads the same registry.
 */
function failing (how) {
  const bot = drivable()
  const gate = countedGate()
  if (how === 'policy') {
    Object.defineProperty(bot.pathfinder, 'thinkTimeout', {
      get () { return undefined },
      set () { throw new Error('the library refused a setting') }
    })
  }
  if (how === 'movements') {
    bot.registry = Object.create(H.registry, {
      blocksByName: { get () { throw new Error('the library refused its registry') } }
    })
  }
  if (how === 'goal' || how === 'clear') {
    const refuses = how === 'goal' ? (goal) => goal !== null : (goal) => goal === null
    bot.pathfinder.setGoal = function (goal) {
      if (refuses(goal)) throw new Error('the library refused a goal')
      this.goals.push(goal)
    }
  }
  return { bot, gate, movement: createMovement(bot, { simulation: gate }) }
}

/** Every route event, counted, so a guard can be shown to have left none behind. */
function routeListeners (bot) {
  return ROUTE_EVENTS.map((event) => bot.listenerCount(event))
}

test('a library that throws while a request is being set up refuses the request and keeps nothing', async () => {
  // Three moments, three different amounts of teardown owed, one answer. `undefined` is what
  // `body.js` turns into `notReady`, and it is the only thing a controller may be told: no byte
  // of the exception, the frame or the host may reach it or a log.
  for (const how of ['policy', 'movements', 'goal']) {
    const { bot, gate, movement } = failing(how)
    const before = { timers: liveTimers(), listeners: routeListeners(bot) }

    const refused = await movement.moveTo({
      destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    })

    assert.equal(refused, undefined, `${how}: the request must be refused rather than answered`)
    // The gate: closed, and released with the token that took it rather than at all costs.
    assert.equal(gate.open, false, `${how}: the simulation must not be left running`)
    assert.equal(gate.released, gate.taken, `${how}: every take owes a release`)
    // The deadline: a request that never started must not settle twenty seconds later.
    assert.equal(liveTimers(), before.timers, `${how}: the deadline must be disarmed`)
    assert.deepEqual(routeListeners(bot), before.listeners, `${how}: no listener may be left`)
    // The route: nothing left on the library. A `goal` failure is the one that had to clear
    // something, and its recorder answers null for exactly that, so the clear is visible.
    assert.deepEqual(
      bot.pathfinder.goals.filter((goal) => goal !== null),
      [],
      `${how}: no goal may be left on the library`
    )
    if (how === 'goal') {
      assert.deepEqual(bot.pathfinder.goals, [null], 'a goal the library took must be cleared')
    }
    // And the slot is free, which is the difference between a refused request and a body that
    // can never be asked to walk again: the wrapper's own answer to "is anything running" is
    // no, so the next `moveTo` is served rather than refused as a takeover.
    assert.equal(movement.halt(), false, `${how}: the movement slot must be free`)
  }
})

test('a library that throws while a request is ending still answers it and gives the gate back', async () => {
  const { bot, gate, movement } = failing('clear')
  const before = { timers: liveTimers(), listeners: routeListeners(bot) }

  const walking = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.notEqual(walking, undefined, 'the request was accepted, so it owns the path')
  assert.equal(gate.open, true, 'an accepted request holds the gate')
  assert.equal(movement.halt(), true)

  // The ending is where the throw lands, and the two things after it must not be skipped: a
  // request nothing answers leaves a controller holding an id for ever, and a gate nothing gives
  // back leaves the body simulating with nothing owning it.
  const report = await H.promptly(walking, 'the cancelled walk')
  assert.equal(report.outcome, 'cancelled')
  assert.equal(gate.open, false, 'the gate must be given back even when the teardown threw')
  assert.equal(gate.released, 1)
  assert.equal(liveTimers(), before.timers, 'the deadline must be disarmed')
  assert.deepEqual(routeListeners(bot), before.listeners, 'no listener may be left')
  // And the slot is free again, so one throwing teardown does not end the body's walking life.
  const second = movement.moveTo({
    destination: [4, 64, 0], tolerance: 0, timeoutMs: 60, sprint: false
  })
  assert.notEqual(second, undefined, 'the movement slot must be free for the next request')
  await H.promptly(second, 'the second walk')
})

test('a walk over open ground arrives, and says where the body went', async () => {
  const { movement } = await placed()
  const report = await H.promptly(movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), 'the walk')
  assert.equal(report.outcome, 'arrived')
  assert.deepEqual(report.from, STANDING)
  // The physics put the body where it put it, so the arrival is stated as a tolerance rather
  // than as three exact numbers: what is being proven is that it walked, not where a step
  // simulation happens to land inside the block it was sent to.
  assert.ok(blocksApart(report.position, [12, 64, 12]) < 1, `ended at ${report.position}`)
  // Distance covered is its own reading and is not derived from the two positions: a route
  // round something covers more than the line between its ends. Here there is nothing to go
  // round, so the two agree closely, and both are well above zero.
  assert.ok(report.travelled > 4, `travelled ${report.travelled}`)
  assert.ok(report.elapsedMs > 0)
  // A walk was given no threat, so it reports no separation at all - which is the relation
  // the two wire encoders depend on.
  assert.equal('separation' in report, false)
  assert.equal('separationGained' in report, false)
})

test('a wall is routed around rather than walked into', async () => {
  // Two blocks high, because one is a step: a single layer in front of a body standing at the
  // same level is a rise the physics walks straight over, and a case built that way proves
  // nothing at all about routing.
  const walls = []
  for (let z = 4; z <= 12; z += 1) {
    walls.push({ x: 11, y: 64, z })
    walls.push({ x: 11, y: 65, z })
  }
  const { movement } = await placed({ walls })
  const destination = [13, 64, 8]
  const report = await H.promptly(movement.moveTo({
    destination, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), 'the walk round the wall')
  assert.equal(report.outcome, 'arrived')
  assert.ok(blocksApart(report.position, destination) < 1, `ended at ${report.position}`)
  // The whole case: the body walked substantially further than the straight line, which is
  // only possible by going round. A body that had walked into the wall and stopped would
  // report a distance at or under the line and never arrive.
  const line = blocksApart(STANDING, destination)
  assert.ok(report.travelled > line * 2, `travelled ${report.travelled} against a line of ${line}`)
})

test('a one-block rise is stepped up', async () => {
  const walls = []
  for (let x = 11; x <= 14; x += 1) {
    for (let z = 4; z <= 12; z += 1) walls.push({ x, y: 64, z })
  }
  const { movement } = await placed({ walls })
  const report = await H.promptly(movement.moveTo({
    destination: [13, 65, 8], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), 'the walk up the rise')
  assert.equal(report.outcome, 'arrived')
  // The height is the point: the body is standing a block higher than it started, which the
  // library's own step move is what achieved. Nothing in Loom jumps.
  assert.equal(report.position[1], 65)
  assert.equal(report.from[1], 64)
})

test('ground the body cannot reach is answered as no route', async () => {
  const { bot, movement } = await placedIn((placedBot) => platform(placedBot, PLATFORM_RADIUS))
  const searches = searchesOf(bot)
  // Inside the reach and off the end of the small platform the body is standing on, so the
  // search genuinely has nowhere to put its last node rather than being refused by a bound of
  // Loom's - and it exhausts its whole graph in a few dozen expansions, so which of the two
  // answers this case gets is a fact about the ground on any host.
  const report = await H.promptly(movement.moveTo({
    destination: [STANDING[0] + PLATFORM_RADIUS + 4, 64, STANDING[2]],
    tolerance: 0,
    timeoutMs: AMPLE_MS,
    sprint: false
  }), 'the refused walk')
  assert.equal(report.outcome, 'noRoute')
  // And it is the ground that refused it: the library expanded every node it was allowed to and
  // reported `noPath`, which is the only status this outcome may be settled on. Loom's own
  // budget stopping a search is `searchOverBudget` and is the case below.
  assert.deepEqual(searches().map((search) => search.status), ['noPath'])
  // The graph, exactly, which is the one place in this file a node count bites: an exhausted
  // search visited every node it could build, so this says the search reached all of the
  // platform's tiles and none of the air off it. See [PLATFORM_NODES].
  assert.deepEqual(searches().map((search) => search.visited), [PLATFORM_NODES])
  // Settled on the search's own verdict rather than waited out, which is what keeps it
  // distinct from a timeout: a route the ground refused is answered in well under the
  // deadline it was given.
  assert.ok(report.elapsedMs < AMPLE_MS / 2, `took ${report.elapsedMs}ms`)
})

test('the library two failures are two outcomes, and neither may answer for the other', async () => {
  // The whole of the split, proven where it is decidable *without a clock*. The library's own
  // status is the input here, so nothing this case says can be changed by how fast a host expands
  // a node - and it has to be that way round, because on a real search which status arrives is
  // exactly the race the wall-clock budget cannot win on every host.
  //
  // `noPath` is the ground's answer: the library expanded every node it was allowed to and none of
  // them was the goal. `timeout` is Loom's own budget stopping a search that never learned
  // anything about the ground. For a flight the second reported as the first is an agent told
  // there is nowhere to run while a hostile closes, which is why they may not be one outcome.
  const endings = [['noPath', 'noRoute'], ['timeout', 'searchOverBudget']]
  for (const [status, outcome] of endings) {
    const bot = drivable()
    const movement = createMovement(bot)
    const walking = movement.moveTo({
      destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    })
    bot.emit('path_update', { status, path: [], visitedNodes: 1, generatedNodes: 1, time: 1 })

    const report = await H.promptly(walking, `the ${status} walk`)
    assert.equal(report.outcome, outcome, status)
    // Said in both directions, because one outcome standing in for the other is the defect: a
    // ground refusal may never arrive as a budget refusal either.
    const other = endings.find((ending) => ending[0] !== status)[1]
    assert.notEqual(report.outcome, other, `${status} answered as ${other}`)
  }

  // And the library's two *non*-endings settle nothing at all. A `success` is answered by the
  // arrival that follows it, and a `partial` is a search still in progress, which this file has
  // none of - `movement.js`'s NO_SEARCH_SLICE is why. Each request here is left to its own
  // deadline, so an outcome other than `timedOut` would be a status that had ended it.
  for (const status of ['success', 'partial']) {
    const bot = drivable()
    const movement = createMovement(bot)
    const walking = movement.moveTo({
      destination: [8, 64, 0], tolerance: 0, timeoutMs: 120, sprint: false
    })
    bot.emit('path_update', { status, path: [], visitedNodes: 1, generatedNodes: 1, time: 1 })

    assert.equal((await H.promptly(walking, `the ${status} walk`)).outcome, 'timedOut', status)
  }
})

test('the widest search the wire admits stalls the process for no longer than its budget', async () => {
  // The stall bound, and the reason it is stated as an upper bound rather than as an ending: this
  // is the widest search any request can produce - [WIDEST_SEARCH_DESTINATION] carries the
  // arithmetic - and left to run it exhausts 6115 nodes in seconds of *synchronous* work, seconds
  // in which this process answers no connection and simulates no body. What the budget promises is
  // that the stall is bounded whichever way the search ends, and that is what is asserted, so no
  // host speed can turn this case red for the wrong reason. Removing the budget turns it red for
  // the right one.
  const { bot, movement } = await placedIn((placedBot) => openWorld(placedBot, WIDEST_SPAN_CHUNKS))
  const searches = searchesOf(bot)
  assert.ok(
    blocksApart(STANDING, WIDEST_SEARCH_DESTINATION) < MOVE_REACH_BLOCKS,
    'the widest search must still be a request the wire admits'
  )

  const report = await H.promptly(movement.moveTo({
    destination: WIDEST_SEARCH_DESTINATION, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), 'the widest search')

  // It stands in air, so no route reaches it however long the search is given.
  assert.notEqual(report.outcome, 'arrived')
  const [search, ...rest] = searches()
  assert.deepEqual(rest, [], 'one search, one call: a resumed search must be unreachable')
  // `partial` is what a resumed search would report, and there is no slice to produce one.
  assert.notEqual(search.status, 'partial')
  // The stall itself, measured by the library's own clock over the one synchronous call, and the
  // whole of what this case asserts. This is the number an operator feels as a control connection
  // going quiet, and a change that makes a search slower has to fail here rather than in world.
  //
  // Nothing here is said about the graph or about which of the two endings the search reached. A
  // node-count bound belongs where a search is certain to exhaust, which is [PLATFORM_NODES], and
  // an ending belongs at the status seam - see the case above and the two below.
  assert.ok(
    search.ms < SEARCH_BUDGET_MS + STALL_ALLOWANCE_MS,
    `one search blocked the process for ${Math.round(search.ms)}ms against a ${SEARCH_BUDGET_MS}ms budget`
  )
})

/**
 * Asserts a refused search left the body holding nothing at all, and answers the report.
 *
 * The two search refusals are the only endings the library settles from *inside* its own tick,
 * and it does not stop when the callback that settled it returns: it puts the best-effort partial
 * route it found back on itself, which makes its own empty-path early return false, and then
 * looks at, walks and sprints a body whose controller has already been answered and whose
 * simulation gate is already shut. Driven at the real library with `sprint: true`, because
 * `sprint` is the control whose packet escaped, and asserted from all four sides at once - the
 * wire, the controls, the library's own answer to "is this body moving", and the simulation.
 *
 * Neither scene walks a step, so no control *activation* may reach the server. A deactivation is
 * deliberately allowed through the terminal drain: the library can set a local control after its
 * terminal callback and settlement must still tell the server that control is off.
 *
 * Which of the two refusals arrived is asserted as the pair and never as one of them, for the rule
 * the file header carries: a real search's ending is a race between a wall clock and a graph and
 * the host decides it. The subject here is what an ending left behind, which is the same either
 * way; the ending itself is proven at the status seam, and the ground's own refusal in the bounded
 * scene above.
 */
async function refusedSearchHeldNothing (buildWorld, destination, why) {
  const { bot, client, movement } = await placedIn(buildWorld)
  const controlPackets = () =>
    client.writes.filter((write) => MOVEMENT_CONTROL_PACKETS.includes(write.name))
  const movementPackets = () =>
    client.writes.filter((write) => SCOPED_MOVEMENT_PACKETS.includes(write.name)).length

  const report = await H.promptly(movement.moveTo({
    destination, tolerance: 0, timeoutMs: AMPLE_MS, sprint: true
  }), `the search ${why}`)

  assert.ok(
    ['noRoute', 'searchOverBudget'].includes(report.outcome),
    `${why}: the search answered ${report.outcome} rather than either refusal`
  )
  assert.deepEqual(
    controlPackets().filter((packet) => !deactivatesControl(packet)),
    [],
    `${why}: no control activation may reach the wire after the terminal event`
  )
  for (const control of CONTROLS) {
    assert.equal(bot.controlState[control], false, `${why}: ${control} was left set`)
  }
  assert.equal(bot.pathfinder.isMoving(), false, `${why}: the library was left holding a route`)
  assert.equal(bot.physicsEnabled, false, `${why}: the simulation was left running`)
  // And it stays that way over several turns of the plugin's own 50 ms interval, which is what
  // separates "the body was left still" from "the body had not started yet". The whole scoped set
  // rather than the control half, because a route the library was left holding would report a
  // position as well as walking: the gate is one statement about both.
  const settled = movementPackets()
  const controlsAtAnswer = controlPackets().length
  await H.settled(H.WATCHED_MS)
  assert.equal(controlPackets().length, controlsAtAnswer, `${why}: no control packet may follow the answer`)
  assert.equal(movementPackets(), settled, `${why}: no movement packet may follow the answer`)
  for (const control of CONTROLS) {
    assert.equal(bot.controlState[control], false, `${why}: ${control} came back on`)
  }
  return report
}

test('a search off the end of the ground leaves the body holding nothing', async () => {
  // The scene the ground refuses, which on any host measured answers `noRoute` because its work is
  // bounded to [PLATFORM_NODES] expansions.
  await refusedSearchHeldNothing(
    (bot) => platform(bot, PLATFORM_RADIUS),
    [STANDING[0] + PLATFORM_RADIUS + 4, 64, STANDING[2]],
    'off the end of the ground'
  )
})

test('the widest search the wire admits leaves the body holding nothing', async () => {
  // And the scene the budget refuses, which is the same ending reached from the other side: this
  // graph exhausts seconds past the budget on every host measured. Both are driven because the
  // library restores its partial route on either status, and a fix that covered one would look
  // exactly like a fix that covered both.
  await refusedSearchHeldNothing(
    (bot) => openWorld(bot, WIDEST_SPAN_CHUNKS),
    WIDEST_SEARCH_DESTINATION,
    'at the widest reach'
  )
})

test('the worst search this body can succeed at stays inside the work its budget pays for', async () => {
  // The other side of the bound above. A flight at the wire's own maximum retreat distance is the
  // most expensive search a controller may legitimately ask for and still be answered `arrived`,
  // so it is the number [SEARCH_BUDGET_MS] was sized against - and a change that widens the move
  // set, the radius or the goal has to fail here rather than in world, where it arrives as a
  // control connection going quiet or as a flight refused for a reason the ground never gave.
  const { bot, client, movement } = await placedIn(
    (placedBot) => openWorld(placedBot, FLIGHT_SPAN_CHUNKS)
  )
  const searches = searchesOf(bot)
  // On top of the body, so the flight has the whole distance to gain: a threat further off is a
  // cheaper search, and the worst case is the one this project treats as fatal.
  H.spotted(client, { id: 51, what: 'zombie', x: STANDING[0], y: STANDING[1], z: STANDING[2] })
  await H.settled(50)

  // The search is what this case is about, not the walk: a flight that gains thirty-two blocks
  // spends about eight seconds walking them, so it is halted the moment its search has answered.
  const flying = movement.flee({
    offered: offeredEntity(bot, 51),
    entity: 0,
    distance: MAX_RETREAT_DISTANCE,
    timeoutMs: AMPLE_MS,
    sprint: false
  })
  assert.notEqual(flying, undefined, 'the flight should have been accepted')
  await H.eventually(() => { assert.equal(searches().length, 1, 'the search should have run') }, 4_000)

  const [search, ...rest] = searches()
  assert.deepEqual(rest, [], 'one search, one call')
  assert.ok(
    search.visited <= WORST_REACHABLE_NODES,
    `the worst legitimate search now expands ${search.visited} nodes against ${WORST_REACHABLE_NODES}`
  )
  // Whichever of the two statuses the host produced, it is never `noPath`: this ground offers the
  // flight everywhere to go, so the ground never refused anything. That is the relation worth
  // pinning here, and it is the one a wall-clock budget cannot take away.
  assert.notEqual(search.status, 'noPath', 'open ground cannot refuse a flight')
  // The outcome itself is deliberately not asserted, and that is the honest limit of a wall-clock
  // budget: this search costs a third of it on the host it was measured on, so a heavily loaded
  // machine can reach the bound on a flight that would have succeeded. What such a host gets is
  // `searchOverBudget`, a fact about the body, rather than `noRoute`, which would be a lie about
  // the ground - and telling those two apart is the whole of why the outcome was split.
  // Halted rather than asserted to be haltable, for the same reason: on a host that reached the
  // budget the flight has already answered itself, and this case does not claim which happened.
  movement.halt()
  await H.promptly(flying, 'the halted flight')
})

test('a destination past the reach costs no search at all', async () => {
  const { bot, movement } = await placed()
  const handed = []
  bot.on('goal_updated', (goal) => handed.push(goal))
  const beyond = [STANDING[0] + MOVE_REACH_BLOCKS + 1, STANDING[1], STANDING[2]]
  const report = await movement.moveTo({
    destination: beyond, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.equal(report.outcome, 'outOfRange')
  // The whole of the claim: the library was never given a goal, so nothing was searched for.
  assert.deepEqual(handed, [])
  // And the report is a complete one rather than a bare refusal - a controller that asked a
  // body to walk needs to know where the body is whatever the answer was.
  assert.deepEqual(report.from, STANDING)
  assert.deepEqual(report.position, STANDING)
  assert.equal(report.travelled, 0)
  assert.equal(report.elapsedMs, 0)
  // The bound is a straight line and all three axes count, so a destination inside the reach
  // horizontally is still refused if the height puts it past.
  const high = [STANDING[0] + MOVE_REACH_BLOCKS - 2, STANDING[1] + 12, STANDING[2]]
  assert.ok(blocksApart(STANDING, high) > MOVE_REACH_BLOCKS)
  const refused = await movement.moveTo({
    destination: high, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.equal(refused.outcome, 'outOfRange')
})

test('a deadline ends a walk the route has not finished', async () => {
  const { movement } = await placed()
  const deadline = 120
  const report = await H.promptly(movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: deadline, sprint: false
  }), 'the timed-out walk')
  assert.equal(report.outcome, 'timedOut')
  // Answered near its own deadline rather than at the search's: the same destination arrives
  // in over a second when it is given the time, which the first case above measures. Timer and
  // rounded measurement clocks may straddle the boundary by a few milliseconds on a CI host.
  assert.ok(report.elapsedMs >= deadline - 5, `answered after ${report.elapsedMs}ms`)
  assert.ok(report.elapsedMs < deadline * 4, `answered after ${report.elapsedMs}ms`)
})

test('each search uses the request time left and reports the bound that stopped it', async () => {
  let clock = 0
  const limits = []
  let searchStatus = 'success'
  const bot = drivable()
  const originalGetPathTo = function (_movements, _goal, timeout) {
    limits.push(timeout)
    if (searchStatus === 'success') {
      return { status: 'success', path: [], visitedNodes: 1, generatedNodes: 1, time: 1 }
    }
    clock += timeout + 1
    return { status: 'timeout', path: [], visitedNodes: 1, generatedNodes: 1, time: timeout }
  }
  bot.pathfinder.getPathTo = originalGetPathTo
  const movement = createMovement(bot, { now: () => clock })
  const walking = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: 1000, sprint: false
  })

  const first = bot.pathfinder.getPathTo(bot.pathfinder.movements, bot.pathfinder.goals[0])
  bot.emit('path_update', first)
  clock = 900
  searchStatus = 'timeout'
  const repeated = bot.pathfinder.getPathTo(bot.pathfinder.movements, bot.pathfinder.goals[0])
  bot.emit('path_update', repeated)

  const report = await H.promptly(walking, 'the deadline-bounded search')
  assert.deepEqual(limits, [SEARCH_BUDGET_MS, 100])
  assert.equal(report.outcome, 'timedOut')
  assert.equal(bot.pathfinder.getPathTo, originalGetPathTo)

  clock = 2000
  limits.length = 0
  const budgeted = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: 2000, sprint: false
  })
  const result = bot.pathfinder.getPathTo(bot.pathfinder.movements, bot.pathfinder.goals.at(-1))
  bot.emit('path_update', result)

  assert.equal((await H.promptly(budgeted, 'the body-bounded search')).outcome, 'searchOverBudget')
  assert.deepEqual(limits, [SEARCH_BUDGET_MS])
})

test('a flight names one creature and reports the ground it gained', async () => {
  const { bot, client, movement } = await placed()
  const threat = { id: 44, what: 'zombie', x: 6, y: 64, z: 6 }
  H.spotted(client, threat)
  await H.settled(50)
  const started = blocksApart(STANDING, [threat.x, threat.y, threat.z])
  const report = await H.promptly(movement.flee({
    offered: offeredEntity(bot, threat.id), entity: 0, distance: 6, timeoutMs: AMPLE_MS, sprint: true
  }), 'the flight')
  assert.equal(report.outcome, 'arrived')
  // Displacement: the body is somewhere else, and it walked to get there.
  assert.notDeepEqual(report.position, report.from)
  assert.ok(report.travelled > 0, `travelled ${report.travelled}`)
  // Separation: further from the threat than it started, by the amount it says it gained.
  assert.ok(report.separation > started, `${report.separation} against ${started}`)
  assert.ok(report.separationGained > 0)
  assert.ok(Math.abs((started + report.separationGained) - report.separation) < 1e-9)
  // And the separation is measured against the threat rather than against the goal, which is
  // a snapshot: the reading is the live distance from where the creature actually is.
  const standing = [threat.x, threat.y, threat.z]
  assert.ok(Math.abs(blocksApart(report.position, standing) - report.separation) < 1e-9)
})

test('a flight refuses a replaced live entity on its terminal tick', async () => {
  const bot = drivable()
  const tracked = { position: new Vec3(2, 64, 0), isValid: true }
  bot.entities = { 44: tracked }
  let terminal = false
  bot.on('physicsTick', () => {
    if (!terminal) return
    bot.entity.position = new Vec3(1, 64, 0)
    tracked.position = new Vec3(100, 64, 0)
    bot.entities[44] = { position: new Vec3(3, 64, 0), isValid: true }
    bot.emit('goal_reached')
  })
  const movement = createMovement(bot)
  const flying = movement.flee({
    offered: offeredEntity(bot, 44), entity: 0, distance: 7, timeoutMs: AMPLE_MS, sprint: false
  })

  terminal = true
  bot.emit('physicsTick')

  const report = await H.promptly(flying, 'the vanished-threat flight')
  assert.equal(report.outcome, 'threatGone')
  assert.equal(report.travelled, 1)
  assert.equal('separation' in report, false)
  assert.equal('separationGained' in report, false)
})

test('a flight follows the exact sensed identity and ends when that entity dies', async () => {
  const bot = drivable()
  const sensed = { position: new Vec3(2, 64, 0), isValid: true }
  bot.entities = { 44: sensed }
  const offer = offeredEntity(bot, 44)
  const movement = createMovement(bot)

  // Reusing the numeric id after the sense must not turn the replacement into the same handle.
  bot.entities[44] = { position: new Vec3(3, 64, 0), isValid: true }
  const replaced = await movement.flee({
    offered: offer, entity: 0, distance: 7, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.equal(replaced.outcome, 'threatGone')
  assert.equal(replaced.travelled, 0)
  assert.equal('separation' in replaced, false)

  // The normal death event precedes removal from Mineflayer's entity table. It must still end
  // the flight against that exact object and give the per-run listener back.
  bot.entities[44] = sensed
  const listenersBefore = bot.listenerCount('entityDead')
  const flying = movement.flee({
    offered: offer, entity: 0, distance: 7, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.equal(bot.listenerCount('entityDead'), listenersBefore + 1)
  bot.emit('entityDead', sensed)
  const dead = await H.promptly(flying, 'the dead-threat flight')
  assert.equal(dead.outcome, 'threatGone')
  assert.equal(dead.travelled, 0)
  assert.equal('separation' in dead, false)
  assert.equal('separationGained' in dead, false)
  assert.equal(bot.listenerCount('entityDead'), listenersBefore)
})

test('a flight names a creature three ways it can fail to, and walks for none of them', async () => {
  const { bot, client, movement } = await placed()
  const handed = []
  bot.on('goal_updated', (goal) => handed.push(goal))
  H.spotted(client, { id: 44, what: 'zombie', x: 6, y: 64, z: 6 })
  await H.settled(50)
  const asking = { entity: 0, distance: 6, timeoutMs: AMPLE_MS, sprint: false }
  // No answer of this connection's was named at all, so there is no scene to index into.
  const forgotten = await movement.flee({ ...asking, offered: undefined })
  assert.equal(forgotten.outcome, 'senseForgotten')
  // An index past the end of the answer that *was* named: a handle nobody issued.
  const unnamed = await movement.flee({ ...asking, offered: [] })
  assert.equal(unnamed.outcome, 'threatUnnamed')
  // A creature the answer offered which the world no longer holds - dead, despawned, or in
  // another dimension, which are one fact from where the body is standing.
  const gone = await movement.flee({
    ...asking,
    offered: [{ id: 99, entity: { position: new Vec3(6, 64, 6) }, dead: false }]
  })
  assert.equal(gone.outcome, 'threatGone')
  for (const refused of [forgotten, unnamed, gone]) {
    assert.deepEqual(refused.position, refused.from)
    assert.equal(refused.travelled, 0)
    // No separation, because there is no threat to have measured one against - the same
    // relation a walk keeps, reached from the other side.
    assert.equal('separation' in refused, false)
  }
  assert.deepEqual(handed, [])
})

test('a second request cannot take the path over from a first', async () => {
  const { movement } = await placed()
  const walking = movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.notEqual(walking, undefined)
  await H.settled(150)
  // Neither operation may displace it, and neither may be served as a queued second: the
  // wrapper answers `undefined`, which the wire refuses as `notReady`.
  assert.equal(movement.moveTo({
    destination: [4, 64, 4], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), undefined)
  assert.equal(movement.flee({
    offered: [], entity: 0, distance: 6, timeoutMs: AMPLE_MS, sprint: false
  }), undefined)
  // And the first request is untouched by having been asked over: it arrives where it was
  // sent, rather than at the destination the second request named.
  const report = await H.promptly(walking, 'the first walk')
  assert.equal(report.outcome, 'arrived')
  assert.ok(blocksApart(report.position, [12, 64, 12]) < 1, `ended at ${report.position}`)
  // Once it has ended the path is free again, which is what makes halt-then-ask the way to
  // replace a movement rather than a workaround.
  const second = movement.moveTo({
    destination: [4, 64, 4], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.notEqual(second, undefined)
  assert.equal(movement.halt(), true)
  await H.promptly(second, 'the second walk')
})

test('cancellation leaves no listener, no goal, no timer and no control', async () => {
  const { bot, movement } = await placed()
  const handed = []
  bot.on('goal_updated', (goal) => handed.push(goal))
  // Counted before the request rather than required to be zero afterwards: the library and
  // Mineflayer's own physics plugin both keep a `physicsTick` listener of their own, and
  // cancellation owes back what it took rather than everything there is.
  const listenersBefore = {}
  for (const event of ROUTE_EVENTS) listenersBefore[event] = bot.listenerCount(event)
  const timersBefore = liveTimers()

  const walking = movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  await H.settled(150)
  // Actually moving, or there would be nothing to prove the teardown against.
  assert.equal(bot.pathfinder.isMoving(), true)
  assert.ok(liveTimers() > timersBefore, 'the deadline should be armed')

  assert.equal(movement.halt(), true)
  const report = await H.promptly(walking, 'the cancelled walk')
  // A halt is an ending rather than a refusal, and it reports the ground it did cover.
  assert.equal(report.outcome, 'cancelled')
  assert.ok(report.travelled > 0, `travelled ${report.travelled}`)

  const listenersAfter = {}
  for (const event of ROUTE_EVENTS) listenersAfter[event] = bot.listenerCount(event)
  assert.deepEqual(listenersAfter, listenersBefore)
  assert.equal(liveTimers(), timersBefore)
  // The goal was cleared, which the library reports on the way past: the last thing it was
  // handed is null. There is no reader for its current goal, so this is how it is observable.
  assert.deepEqual(handed.map((goal) => (goal === null ? null : goal.constructor.name)),
    ['GoalNear', null])
  assert.equal(bot.pathfinder.isMoving(), false)
  for (const control of CONTROLS) {
    assert.equal(bot.controlState[control], false, control)
  }
  // A second halt is a question rather than a refusal, and the answer is that nothing runs.
  assert.equal(movement.halt(), false)
})

test('the simulation runs while a request owns the path and at no other time', async () => {
  const { bot, client, movement } = await placed()
  const measured = simulation(bot, client)

  // Placed, standing on real ground, and asked for nothing. Mineflayer's own plugin armed a
  // 50 ms interval from the login it saw and there is no way to stop it, so what is being
  // measured is that the interval *does* nothing: no step, and no packet saying where a step
  // left the body.
  const still = measured()
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), still, 'a body with no request must be neither stepped nor reported')

  // A request refused before anything was searched for takes nothing, so it leaves the body
  // exactly as still - which is the case a gate opened at the top of `move` would fail.
  const beyond = [STANDING[0] + MOVE_REACH_BLOCKS + 1, STANDING[1], STANDING[2]]
  const refused = await movement.moveTo({
    destination: beyond, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.equal(refused.outcome, 'outOfRange')
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), still, 'a refused request must simulate nothing')

  // Accepted: now both halves run, because a body that is not simulated cannot walk and a
  // server that is not told cannot know where it went.
  const walking = movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  await H.settled(150)
  const moving = measured()
  assert.ok(moving.ticks > still.ticks, 'an accepted request must be simulated')
  assert.ok(moving.reports > still.reports, 'and must report where the body is')

  // An arrival, and both halves stop with it.
  assert.equal((await H.promptly(walking, 'the walk')).outcome, 'arrived')
  const arrived = measured()
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), arrived, 'an arrival must stop the simulation')

  // A cancellation, which is the same ending reached from the other side.
  const halting = movement.moveTo({
    destination: [4, 64, 4], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  await H.settled(150)
  assert.ok(measured().ticks > arrived.ticks, 'the second request must be simulated too')
  assert.equal(movement.halt(), true)
  assert.equal((await H.promptly(halting, 'the cancelled walk')).outcome, 'cancelled')
  const cancelled = measured()
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), cancelled, 'a halt must stop the simulation')

  // And a deadline, which is the one ending nothing outside the request asked for.
  const timing = movement.moveTo({
    destination: [12, 64, 4], tolerance: 0, timeoutMs: 120, sprint: false
  })
  assert.equal((await H.promptly(timing, 'the timed-out walk')).outcome, 'timedOut')
  const expired = measured()
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), expired, 'a deadline must stop the simulation')
})

test('every search a body runs is run under this body own six settings', async () => {
  const { bot, movement } = await placed()
  // The plugin's defaults, which are what these would be if nothing applied a policy. Read
  // first so the assertions below are a change rather than a coincidence.
  assert.notEqual(bot.pathfinder.thinkTimeout, SEARCH_BUDGET_MS)
  assert.notEqual(bot.pathfinder.tickTimeout, NO_SEARCH_SLICE)
  assert.notEqual(bot.pathfinder.searchRadius, SEARCH_RADIUS_BLOCKS)

  await H.promptly(movement.moveTo({
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), 'the walk')

  assert.equal(bot.pathfinder.thinkTimeout, SEARCH_BUDGET_MS)
  assert.equal(bot.pathfinder.tickTimeout, NO_SEARCH_SLICE)
  assert.equal(bot.pathfinder.searchRadius, SEARCH_RADIUS_BLOCKS)
  assert.equal(bot.pathfinder.enablePathShortcut, false)
  assert.equal(bot.pathfinder.LOSWhenPlacingBlocks, true)
  // The explicit "no tool selection": the library's own default reaches into the inventory
  // and equips something, which is a decision about the body's hands no request asked for.
  assert.equal(bot.pathfinder.bestHarvestTool(null), null)
})

test('a route abandoned as stuck too often ends the request', async () => {
  const bot = drivable()
  const movement = createMovement(bot)
  const walking = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  // The library's own futility reason, and only that one: every other reset is either Loom's
  // teardown or a world change it is about to re-plan around.
  for (let attempt = 0; attempt <= MAX_STALL_RETRIES; attempt += 1) {
    assert.equal(bot.pathfinder.goals.length, 1, `settled after ${attempt} stalls`)
    bot.emit('path_reset', 'stuck')
  }
  const report = await H.promptly(walking, 'the stalled walk')
  assert.equal(report.outcome, 'stalled')
  // Retries are what the bound counts, so the number of resets it takes is one more than the
  // retries allowed - and a reset for any other reason is not one of them.
  const patient = createMovement(bot)
  const second = patient.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: 200, sprint: false
  })
  for (let attempt = 0; attempt < 20; attempt += 1) bot.emit('path_reset', 'goal_updated')
  assert.equal((await H.promptly(second, 'the patient walk')).outcome, 'timedOut')
})

test('a body that walks further than the allowance ends the request', async () => {
  const bot = drivable()
  const movement = createMovement(bot)
  const walking = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  // One long step rather than many, because what the bound counts is ground covered and it
  // does not care how it was covered - which is the point: a body oscillating between two
  // nodes covers a great deal while going nowhere.
  bot.entity.position = new Vec3(MAX_TRAVEL_BLOCKS + 1, 64, 0)
  bot.emit('physicsTick')
  const report = await H.promptly(walking, 'the over-travelled walk')
  assert.equal(report.outcome, 'overTravelled')
  assert.ok(report.travelled > MAX_TRAVEL_BLOCKS, `travelled ${report.travelled}`)
  // And the allowance is wider than the reach, so no ordinary detour can reach it.
  assert.ok(MAX_TRAVEL_BLOCKS > MOVE_REACH_BLOCKS)
})

test('a terminal physics tick is measured before its outcome is selected', async () => {
  const bot = drivable()
  let terminal = false
  bot.on('physicsTick', () => {
    if (!terminal) return
    bot.entity.position = new Vec3(MAX_TRAVEL_BLOCKS + 0.1, 64, 0)
    bot.emit('goal_reached')
  })
  const movement = createMovement(bot)
  const walking = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })

  bot.entity.position = new Vec3(MAX_TRAVEL_BLOCKS - 0.1, 64, 0)
  bot.emit('physicsTick')
  terminal = true
  bot.emit('physicsTick')

  const report = await H.promptly(walking, 'the terminal tick')
  assert.equal(report.outcome, 'overTravelled')
  assert.ok(report.travelled > MAX_TRAVEL_BLOCKS, `travelled ${report.travelled}`)
  assert.equal(report.position[0], MAX_TRAVEL_BLOCKS + 0.1)
})

test('a terminal frame sends final position and control deactivation before ownership closes', async () => {
  const { bot, client, movement } = await placed()
  const walking = movement.moveTo({
    destination: [14, 64, 8], tolerance: 0, timeoutMs: AMPLE_MS, sprint: true
  })
  await H.eventually(() => {
    assert.equal(bot.controlState.sprint, true, 'the route should be sprinting before arrival')
  }, 4_000)

  const report = await H.promptly(walking, 'the drained terminal frame')
  const scoped = client.writes.filter((write) => SCOPED_MOVEMENT_PACKETS.includes(write.name))
  const stoppedSprint = scoped.some((write) =>
    write.name === 'entity_action' && ['stop_sprinting', 4].includes(write.parameters.actionId)
  )
  assert.equal(stoppedSprint, true, 'the server must receive the sprint deactivation')
  const finalPosition = scoped
    .filter((write) => MOVEMENT_REPORT_PACKETS.includes(write.name))
    .filter((write) => typeof write.parameters.x === 'number')
    .at(-1)
  assert.notEqual(finalPosition, undefined, 'the terminal frame must report its final position')
  assert.ok(
    blocksApart(
      [finalPosition.parameters.x, finalPosition.parameters.y, finalPosition.parameters.z],
      report.position
    ) < 1e-9,
    `the server packet and report disagree: ${JSON.stringify(finalPosition.parameters)} / ${report.position}`
  )
  assert.equal(bot.controlState.sprint, false)
  assert.equal(bot.physicsEnabled, false)

  const written = scoped.length
  await H.settled(H.WATCHED_MS)
  assert.equal(
    client.writes.filter((write) => SCOPED_MOVEMENT_PACKETS.includes(write.name)).length,
    written,
    'no movement packet may be written after the answer'
  )
})

test('a terminal drain keeps a later request from taking ownership early', async () => {
  const bot = drivable()
  const gate = countedGate()
  const movement = createMovement(bot, { simulation: gate })
  bot.entity.yaw = 0
  bot.entity.pitch = 0
  const first = movement.moveTo({
    destination: [8, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  // A pinned-library continuation after the terminal callback may turn the body. The drain
  // restores the facing captured at the terminal boundary before the final frame is written.
  bot.on('goal_reached', () => {
    bot.entity.yaw = 1
    bot.entity.pitch = 0.5
  })

  bot.emit('goal_reached')
  assert.equal(gate.draining, true)
  assert.equal(movement.moveTo({
    destination: [4, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }), undefined)

  // The arrival is answered once the body has come to rest on the goal.
  bot.entity.position = new Vec3(8.5, 64, 0.5)
  bot.entity.onGround = true
  for (let tick = 0; tick <= ARRIVAL_SETTLE_TICKS; tick += 1) bot.emit('physicsTick')
  assert.equal((await H.promptly(first, 'the drained request')).outcome, 'arrived')
  assert.equal(bot.entity.yaw, 0)
  assert.equal(bot.entity.pitch, 0)
  assert.equal(gate.open, false)
  const second = movement.moveTo({
    destination: [4, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  assert.notEqual(second, undefined)
  movement.halt()
  await H.promptly(second, 'the later request')
})

test('the goal the library is handed is the library own, inverted and held to a safe end for a flight', async () => {
  const bot = drivable()
  bot.entities = { 44: { position: new Vec3(2, 64, 0) } }
  const movement = createMovement(bot)
  const walking = movement.moveTo({
    destination: [8, 64, 3], tolerance: 2, timeoutMs: 50, sprint: false
  })
  const [walk] = bot.pathfinder.goals
  // A walk is the library's own near goal, carrying the destination and the tolerance the
  // request named. Nothing here reimplements a goal.
  assert.ok(walk instanceof libraryGoals.GoalNear)
  assert.deepEqual([walk.x, walk.y, walk.z], [8, 64, 3])
  assert.equal(walk.rangeSq, 4)
  // The policy is built per request, and sprinting is the one field the request decides.
  assert.equal(bot.pathfinder.movements.allowSprinting, false)
  await H.promptly(walking, 'the walk')

  const fleeing = movement.flee({
    offered: offeredEntity(bot, 44), entity: 0, distance: 7, timeoutMs: 50, sprint: true
  })
  const held = bot.pathfinder.goals[bot.pathfinder.goals.length - 1]
  // A flight is that same goal inverted - the library's own way of running away from a place
  // rather than a second search of Loom's - built around where the threat was seen, with only
  // where it may end held to safe ground (`SafeFlightGoal`).
  assert.ok(held instanceof libraryGoals.Goal)
  const flight = held.inner
  assert.ok(flight instanceof libraryGoals.GoalInvert)
  assert.ok(flight.goal instanceof libraryGoals.GoalNear)
  assert.deepEqual([flight.goal.x, flight.goal.y, flight.goal.z], [2, 64, 0])
  assert.equal(flight.goal.rangeSq, 49)
  assert.equal(bot.pathfinder.movements.allowSprinting, true)
  await H.promptly(fleeing, 'the flight')
})

test('the reach a walk is bounded by is the reach a flight is', () => {
  // A flight names its distance as a field, so the wire can bound it; a walk names absolute
  // coordinates, so its reach is measured against the body's live position and can only be
  // bounded here. Two statements of one number, in two files that may not import each other.
  assert.equal(MOVE_REACH_BLOCKS, MAX_RETREAT_DISTANCE)
})

test('a movement deadline fits inside the idle window its answer travels through', () => {
  // A movement is answered on the connection that asked for it and nothing flows on that
  // connection while the body walks, so a deadline at or past the idle window would let the
  // socket's own timer close the channel before the answer could be written.
  assert.ok(MAX_MOVE_DEADLINE_MS < CONTROL_IDLE_MS)
})

test('the three movement operations answer over a real control connection', async () => {
  const { body, bot, client, port } = await H.embodied()
  openBody = body
  H.spawned(client)
  // The clock is part of what a sense answer must state, so a body with no reported time
  // answers `notReady` and there is no scene for a flight to name.
  H.clockReported(client, { timeOfDay: 6000 })
  client.emit('position', H.positionPacket({
    x: STANDING[0], y: STANDING[1], z: STANDING[2], yaw: 0, pitch: 0, teleportId: 1
  }))
  H.ground(bot, {})
  H.spotted(client, { id: 44, what: 'zombie', x: 6, y: 64, z: 6 })
  await H.settled(200)
  const peer = await H.handshaken(port)

  // A flight resolves its threat out of this connection's own sense answers, so the ordinal
  // means something only inside the answer that offered it - and the flight is asked first,
  // while the body is still standing near the creature and has ground to gain.
  const sensed = await peer.answer(H.request('sense', 's1', { radius: 20, limit: 4 }))
  assert.equal(sensed.entities.length, 1)
  const fled = await H.promptly(peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity: 0, distance: 8, timeoutMs: AMPLE_MS, sprint: true
  })), 'the flight', AMPLE_MS + 4000)
  assert.equal(fled.op, 'flee')
  assert.equal(fled.requestId, 'f1')
  assert.equal(fled.outcome, 'arrived')
  // Both measurements the wire owes a flight, on the frame rather than only in the wrapper.
  assert.ok(fled.travelled > 0, `travelled ${fled.travelled}`)
  assert.ok(fled.separationGained > 0, `gained ${fled.separationGained}`)
  assert.equal(typeof fled.separation, 'number')

  // A sense id no answer of this connection's ever carried names no scene, which is what
  // makes the handle request-scoped rather than a name for something the process holds.
  const stranger = await peer.answer(H.request('flee', 'f2', {
    senseId: 'someone-else', entity: 0, distance: 8, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(stranger.outcome, 'senseForgotten')
  // A refusal about a threat carries neither measurement, which is the one relation this
  // frame's encoder has that the schema cannot state.
  assert.equal('separation' in stranger, false)
  assert.equal('separationGained' in stranger, false)

  const walked = await H.promptly(peer.answer(H.request('moveTo', 'm1', {
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })), 'the walk', AMPLE_MS + 4000)
  assert.equal(walked.op, 'moveTo')
  assert.equal(walked.requestId, 'm1')
  assert.equal(walked.outcome, 'arrived')
  assert.equal('separation' in walked, false)

  // A halt gets its own answer and the movement it stopped gets its own, on its own request.
  peer.send(H.request('moveTo', 'm2', {
    destination: [2, 64, 2], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }))
  await H.settled(200)
  const halted = await peer.answer(H.request('halt', 'hA'))
  assert.equal(halted.op, 'halt')
  assert.equal(halted.stopped, true)
  const cancelled = await H.promptly(peer.read(), 'the cancelled walk')
  assert.equal(cancelled.op, 'moveTo')
  assert.equal(cancelled.requestId, 'm2')
  assert.equal(cancelled.outcome, 'cancelled')
  // And a halt with nothing to stop is a question rather than a refusal.
  assert.equal((await peer.answer(H.request('halt', 'hB'))).stopped, false)

  peer.stop()
})

test('a sense handle stays bound to the exact creature through a death before flight', async () => {
  const { body, bot, client, port } = await H.embodied()
  openBody = body
  H.spawned(client)
  H.clockReported(client, { timeOfDay: 6000 })
  client.emit('position', H.positionPacket({
    x: STANDING[0], y: STANDING[1], z: STANDING[2], yaw: 0, pitch: 0, teleportId: 1
  }))
  H.ground(bot, {})
  H.spotted(client, { id: 44, what: 'zombie', x: 6, y: 64, z: 6 })
  await H.settled(200)
  const peer = await H.handshaken(port)

  const sensed = await peer.answer(H.request('sense', 's1', { radius: 20, limit: 4 }))
  assert.equal(sensed.entities.length, 1)
  const exact = bot.entities[44]
  bot.emit('entityDead', exact)
  // Mineflayer can leave this same object in the live table until a later destroy packet.
  assert.equal(bot.entities[44], exact)

  const report = await peer.answer(H.request('flee', 'f1', {
    senseId: 's1', entity: 0, distance: 8, timeoutMs: AMPLE_MS, sprint: false
  }))
  assert.equal(report.outcome, 'threatGone')
  assert.equal(report.travelled, 0)
  assert.equal('separation' in report, false)
  assert.equal('separationGained' in report, false)
  peer.stop()
})

/**
 * A destination far enough off that a route to it cannot finish while a case is watching.
 *
 * Load-bearing rather than arbitrary: both cases below prove that a *close* stopped a route,
 * and a route that had time to arrive on its own would prove exactly the same thing without
 * anything having stopped it. Across the chunk, so the walk is seconds rather than one, and
 * both cases check the body did not reach it as well as that the route ended.
 */
const FAR = Object.freeze([1, 64, 1])

/** How close to [FAR] counts as having arrived there, in blocks. */
const ARRIVED_WITHIN = 2

/**
 * A body process in the world at [STANDING], on real ground, with a handshaken controller.
 *
 * The two cases below are about what a *connection* owns, so they need the control channel and
 * the wire rather than the wrapper alone.
 */
async function driven () {
  const { body, bot, client, port } = await H.embodied()
  openBody = body
  H.spawned(client)
  client.emit('position', H.positionPacket({
    x: STANDING[0], y: STANDING[1], z: STANDING[2], yaw: 0, pitch: 0, teleportId: 1
  }))
  H.ground(bot, {})
  await H.settled(200)
  return { bot, client, peer: await H.handshaken(port) }
}

/** Where the body is standing right now, as the three numbers a report carries. */
function standing (bot) {
  return [bot.entity.position.x, bot.entity.position.y, bot.entity.position.z]
}

/**
 * Asserts the route ended without the body having reached [FAR], and stayed ended.
 *
 * The window is short on purpose: a route left running would still be walking, and a case that
 * waited for it would watch it arrive and then agree that nothing was moving.
 */
async function droppedShortOfFar (bot, measured, why) {
  await H.eventually(() => { assert.equal(bot.pathfinder.isMoving(), false) }, 1_000)
  assert.ok(
    blocksApart(standing(bot), FAR) > ARRIVED_WITHIN,
    `${why}: the body reached ${FAR} instead of being stopped at ${standing(bot)}`
  )
  const stopped = measured()
  await H.settled(H.WATCHED_MS)
  assert.deepEqual(measured(), stopped, `${why}: the simulation must stop with the route`)
}

test('a movement the wrapper will not take is refused on the wire as notReady', async () => {
  // The other end of the guards above: whatever the wrapper answers `undefined` for - a body
  // not in the world, a takeover, a library that threw - reaches the controller as this one
  // code from the wire's closed set and nothing else. Driven with no `spawned`, so the body is
  // still `connecting` and the refusal is the one a controller is told to ask again after.
  const { body, port } = await H.embodied()
  openBody = body
  const peer = await H.handshaken(port)

  for (const asked of [
    H.request('moveTo', 'm1', {
      destination: [1, 64, 1], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    }),
    H.request('flee', 'f1', { senseId: 's1', entity: 0, distance: 8, timeoutMs: AMPLE_MS, sprint: false })
  ]) {
    const refused = await H.promptly(peer.answer(asked), `the refused ${asked.op}`)

    assert.equal(refused.error, 'notReady', asked.op)
    // Nothing of the reason travels: the frame carries the code, the agent and the id it echoes,
    // and there is no field a message, a path or a host could arrive in.
    assert.deepEqual(
      Object.keys(refused).sort(),
      ['agentId', 'error', 'protocol', 'requestId'],
      asked.op
    )
  }

  peer.stop()
})

test('a lost connection stops the movement it asked for and the simulation with it', async () => {
  const { bot, client, peer } = await driven()
  const measured = simulation(bot, client)
  const still = measured()

  peer.send(H.request('moveTo', 'm1', {
    destination: FAR, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }))
  await H.settled(150)
  assert.ok(measured().ticks > still.ticks, 'the accepted walk should be simulated')
  assert.equal(bot.pathfinder.isMoving(), true)

  // The answer to that request would have gone out on this socket and now cannot, so the
  // route is dropped with the connection - and the simulation with the route.
  peer.stop()
  await droppedShortOfFar(bot, measured, 'a lost connection')
})

test('a halt pipelined before its replacement leaves the replacement owned by its connection', async () => {
  const { bot, client, peer } = await driven()
  const measured = simulation(bot, client)

  peer.send(H.request('moveTo', 'm1', {
    destination: [12, 64, 12], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }))
  await H.settled(150)
  assert.equal(bot.pathfinder.isMoving(), true)

  // The documented way to replace a movement, sent the way a controller would send it: one
  // write, so both frames arrive in one `data` event and the first request's own settling runs
  // only *after* the replacement has been accepted and taken the path over.
  peer.pipeline([
    H.request('halt', 'hA'),
    H.request('moveTo', 'm2', {
      destination: FAR, tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
    })
  ])
  assert.equal((await H.promptly(peer.read(), 'the halt')).op, 'halt')
  const cancelled = await H.promptly(peer.read(), 'the cancelled walk')
  assert.equal(cancelled.requestId, 'm1')
  assert.equal(cancelled.outcome, 'cancelled')

  // The replacement is still walking, and still being simulated: the first request's settling
  // may take back neither the path nor the gate, because neither is its own any more.
  const replaced = measured()
  await H.settled(150)
  assert.equal(bot.pathfinder.isMoving(), true, 'the replacement should own the path')
  assert.ok(measured().ticks > replaced.ticks, 'the replacement should still be simulated')

  // And the connection that asked for it still owns it, which is the whole of the regression:
  // keyed on the session rather than on the request, the first walk's settling cleared the
  // replacement's ownership and this close stopped nothing at all.
  peer.stop()
  await droppedShortOfFar(bot, measured, 'a lost connection after a pipelined replacement')
})

test('a reached goal is an arrival only once the server was told the same grounded rest', async () => {
  const bot = drivable()
  const gate = countedGate()
  let wire = { position: [0, 64.6, 0], onGround: false }
  gate.reportedStanding = () => wire
  const movement = createMovement(bot, { simulation: gate })
  let answered
  const walking = movement.moveTo({
    destination: [0, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  }).then((report) => { answered = report })

  // Reached while still falling onto the goal: nothing is answered.
  bot.entity.position = new Vec3(0.5, 64.6, 0.5)
  bot.entity.onGround = false
  bot.emit('goal_reached')
  for (let tick = 0; tick < 4; tick += 1) bot.emit('physicsTick')
  await H.settled(10)
  assert.equal(answered, undefined)

  // Landed and still, but the server has not been told so: still nothing.
  bot.entity.position = new Vec3(0.5, 64, 0.5)
  bot.entity.onGround = true
  for (let tick = 0; tick < 4; tick += 1) bot.emit('physicsTick')
  await H.settled(10)
  assert.equal(answered, undefined)
  assert.equal(gate.open, true)

  // Once the grounded report has been written, the rest is the arrival.
  wire = { position: [0.5, 64, 0.5], onGround: true }
  for (let tick = 0; tick <= ARRIVAL_SETTLE_TICKS; tick += 1) bot.emit('physicsTick')
  await H.promptly(walking, 'the landed walk')
  assert.equal(answered.outcome, 'arrived')
  assert.deepEqual(answered.position, [0.5, 64, 0.5])
  assert.equal(gate.open, false)
})

test('a reached goal on a bot with no wire is an arrival once it rests on the ground', async () => {
  // The inert gate has no wire, so there is no report to wait for: rest alone settles it.
  const bot = drivable()
  const movement = createMovement(bot)
  const walking = movement.moveTo({
    destination: [0, 64, 0], tolerance: 0, timeoutMs: AMPLE_MS, sprint: false
  })
  bot.entity.position = new Vec3(0.5, 64, 0.5)
  bot.entity.onGround = true
  bot.emit('goal_reached')
  for (let tick = 0; tick <= ARRIVAL_SETTLE_TICKS; tick += 1) bot.emit('physicsTick')
  assert.equal((await H.promptly(walking, 'the resting walk')).outcome, 'arrived')
})

test('a held simulation is granted only to a bot with a wire and a free gate', async () => {
  const { holdSimulation } = require('../src/movement')
  const unwired = drivable()
  unwired.loadPlugin = () => {}
  installMovement(unwired)
  assert.equal(holdSimulation(unwired), undefined)
  assert.equal(holdSimulation(drivable()), undefined)

  const { bot } = await H.realBot()
  const release = holdSimulation(bot)
  assert.equal(typeof release, 'function')
  assert.equal(bot.physicsEnabled, true)
  // One owner at a time: a second hold, or a walk, cannot take the gate while it is held.
  assert.equal(holdSimulation(bot), undefined)
  release()
  assert.equal(bot.physicsEnabled, false)
  const again = holdSimulation(bot)
  assert.equal(typeof again, 'function')
  again()
})

test('a final approach walks only a short straight step, and none at all past one block', async () => {
  const { EventEmitter } = require('node:events')
  const { Vec3 } = require('vec3')
  const { FINAL_APPROACH_MAX_BLOCKS, createMovement: create } = require('../src/movement')
  const bot = new EventEmitter()
  bot.entity = { position: new Vec3(8.357, 64, 8.5), yaw: 0, pitch: 0, onGround: true }
  const far = create(bot).finalApproach({ point: [8.357 + FINAL_APPROACH_MAX_BLOCKS + 0.01, 64, 8.5], timeoutMs: 1000 })
  assert.equal(far.outcome, 'outOfRange')
  assert.equal(far.travelled, 0)
  // A body that never moves is stalled after the bounded ticks, with every control let go.
  const controls = {}
  bot.setControlState = (name, on) => { controls[name] = on }
  const stuck = create(bot).finalApproach({ point: [8.7, 64, 8.5], timeoutMs: 5000 })
  for (let tick = 0; tick < 30; tick += 1) bot.emit('physicsTick')
  const report = await stuck
  assert.equal(report.outcome, 'stalled')
  assert.equal(controls.forward, false)
})
