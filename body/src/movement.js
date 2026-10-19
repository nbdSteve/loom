'use strict'

/**
 * One walk, or one flight, asked for explicitly and answered once.
 *
 * Nothing here decides *whether* to walk, *where* to walk or *when* to stop. There is no
 * follow behaviour, no wander, no auto-retreat and no movement loop: a body moves because
 * a controller sent a `moveTo` naming a destination, a `flee` naming one creature of one
 * sense answer, or an explicitly approaching `collect` naming one dropped item whose sensed
 * position becomes the fixed destination. A body with no such request stands perfectly
 * still. That is the same rule `eating.js` obeys, and it is why the only state this module
 * keeps is *which one request owns the path right now*.
 *
 * **The route is `mineflayer-pathfinder`'s and the physics is Prismarine's.** Nothing here
 * searches, steps, jumps, falls or sends a movement packet. A player's movement in
 * Minecraft is client-simulated - there is no "server, please walk me there" packet - so a
 * body that can be asked to walk has to simulate and report its own position, which is the
 * client's role rather than a decision of Loom's. What this file adds is a *request scope*
 * around a library that has none: bounds, one owner, one answer, deterministic
 * cancellation, and four measurements.
 *
 * **The simulation is request-scoped, and that is what keeps "nothing runs of its own accord"
 * true of a process that can walk.** Mineflayer's `physics` plugin is not one of the process's
 * resident reporting plugins and must not become one: it steps, falls, collides and writes the
 * client's own position roughly once a second, of nobody's asking, which is exactly the
 * autonomy this package refuses. It is loaded here instead, beside the pathfinder, because
 * this is the module that needs it - and [scopedSimulation] holds both halves of it shut,
 * `bot.physicsEnabled` false and every packet in [SCOPED_MOVEMENT_PACKETS] dropped at the
 * client seam, unless one accepted request owns the path. The gate is taken at the moment a
 * route is handed to the library and released in [createMovement]'s `settle`, which every
 * ending goes through - so an arrival, a refusal, a deadline, a cancellation, a replacement, a
 * lost connection and a throw from the library all stop it, on one path rather than one per
 * branch. It is released *against the token that took it*, so a settle arriving after a later
 * request has taken the path over stops nothing of that later request's.
 *
 * **The invariant is that a terminal event admits only the current frame's final position and
 * control deactivation, and from the moment the request is answered it holds no control state.**
 * The library emits terminal events from inside its own `physicsTick` listener and carries on
 * after each callback returns. Closing the packet gate there drops its later `fullStop` and the
 * physics plugin's position report, while leaving it open admits a new activation from a partial
 * route restored after a refusal. The gate therefore enters a drain state: final position and
 * control-deactivation packets pass, new control activations do not, and no later request may
 * take ownership. Loom's post-physics listener measures and clears the route, then a microtask
 * releases ownership only after the physics plugin has written the final position. [repaired]
 * checks that teardown once more before the answer is delivered. Each half is needed: a dropped
 * packet leaves the server sprinting or one position behind, and an open activation path lets a
 * route continue after its terminal answer.
 *
 * One part of it is the library's and cannot be scoped: the plugin arms a 50 ms interval on
 * the bot's own `login` and clears it only from `end`, with no supported way to stop and start
 * it again. So what a request scopes is what that interval *does*, and with nothing in flight
 * it does nothing at all - no `physicsTick`, no step, no packet. `movement.test.js` proves
 * that by counting ticks and packets across every ending rather than by reading this.
 *
 * The consequence is deliberate rather than a gap: a body the server left in mid-air stays
 * there until a request owns it, which is the state this process was in before it could walk
 * at all. Falling is simulation, and simulation is a request's. The one holder that is not a
 * route is a dig by a body standing on support, which [holdSimulation] scopes to establishing
 * the ground state a dig is timed by.
 *
 * **Every movement-policy field is set explicitly, because the library's defaults are
 * permissive.** `new Movements(bot)` arrives willing to dig, pillar, scaffold, parkour,
 * sprint, push through entities and drop four blocks. Loom wants a body that walks and
 * does nothing else, so [movementPolicyFor] states all thirty-three of them rather than
 * overriding the handful that look dangerous today - a field the pinned library adds later
 * would otherwise arrive at its own default with nothing to notice it. `movement.test.js`
 * pins the coverage against the real library instead of against this list.
 *
 * **Water is impassable for routing, and that is what "no swimming" means here.** The
 * ability is not a flag: `Movements` has no swim setting, and a route through water is
 * refused by making water a block to avoid. `getBlock` marks water `safe` by default
 * because its bounding box is empty, `safeToBreak` short-circuits on `canDig` being false,
 * and `getMoveForward` then abandons any move whose cost passed a hundred - so a water
 * block in `blocksToAvoid` is a wall to the search. The body still floats if it falls in,
 * because that is Prismarine physics doing what vanilla does and not a policy of Loom's.
 *
 * **Sprinting is the one ability a request decides, and it is off unless the request says
 * so.** Sprinting spends food, so a body allowed to sprint whenever it could would be
 * quietly deciding to be hungrier - which is a survival judgement and the controller's.
 * That is why the policy is built per request rather than once at startup.
 *
 * **One accepted request owns the path, and a second is refused rather than served.**
 * `bot.pathfinder` has exactly one goal, so a second `setGoal` would silently displace a
 * route whose answer the first controller is still waiting for. [createMovement] holds the
 * owner instead: a `moveTo` or a `flee` arriving while either is running answers
 * `undefined`, which `body.js` refuses as `notReady`, and the running request is untouched.
 *
 * **Cancellation is `setGoal(null)` and never `bot.pathfinder.stop()`.** `stop()` only sets
 * a flag the library acts on at some later tick, while `setGoal(null)` runs the library's
 * own `resetPath` synchronously: the path is emptied, the A* context is dropped, the three
 * item locks are released, the collision index is cleared and `bot.clearControlStates()` is
 * called. Loom's four listeners are removed first, so the teardown's own events are never
 * observed. `monitorMovement` is then never called again either, because the same `settle`
 * shuts the simulation gate and the plugin stops emitting `physicsTick` - so the body **stops
 * dead and freezes where it was** rather than decelerating. That is the request scope working
 * as designed and not a movement control left running: the next accepted request is what
 * starts the body falling or walking again.
 *
 * **`undefined` out of [createMovement]'s three functions always means *ask again*.** It has
 * four causes and they share that meaning honestly: a movement already owns the path; the
 * library has not injected the pathfinder yet; the body cannot state a bounded position, which
 * is a body that has died or disconnected; or a call into the library threw, which is a request
 * nothing was searched for and nothing was left running by. None of the four readings a report
 * carries may be invented, so a body that cannot measure where it is refuses the answer rather
 * than defaulting one - the rule `sensing.js` states and `eating.js` inherits.
 */

const { performance } = require('node:perf_hooks')
const physics = require('mineflayer/lib/plugins/physics')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const { installVanillaCollision } = require('./collision')
const { borderExclusion, columnInside, trackWorldBorder, worldBorderOf } = require('./border')
const { positionOf } = require('./sensing')

/**
 * How far from the body a `moveTo` destination may be, in blocks.
 *
 * Equal to the wire's own `MAX_RETREAT_DISTANCE`, because a flight is a walk with the goal
 * inverted and running away may not buy a body a wider reach than walking does. The wire
 * can state that bound for a flee, since a flee names its distance as a field; a `moveTo`
 * names absolute coordinates, so the reach is measured against the body's live position
 * and can only be enforced here. `movement.test.js` pins the two numbers equal.
 */
const MOVE_REACH_BLOCKS = 32

/**
 * How much further than the reach a body may actually walk before the request is ended.
 *
 * Ground covered is not ground gained: a route round a wall is longer than the line, and a
 * body oscillating between two nodes covers a great deal while going nowhere. Four times
 * the reach is generous for any detour a bounded search can produce and short enough that a
 * loop is caught in seconds rather than at the deadline - which matters because
 * `overTravelled` and `timedOut` are separate outcomes on purpose, and a loop reported as a
 * timeout would read as a slow search.
 */
const TRAVEL_ALLOWANCE = 4

/** Furthest a body may actually walk on one request, in blocks. */
const MAX_TRAVEL_BLOCKS = MOVE_REACH_BLOCKS * TRAVEL_ALLOWANCE

/**
 * How many times a route may be abandoned as stuck before the request is ended.
 *
 * The library's own futility rule is what produces these: it abandons a route whose next
 * node has not been reached in 3.5 s and then re-searches on the following tick, so a body
 * wedged against something loops search-walk-stuck indefinitely. Counting them is therefore
 * the bound, and two retries is the smallest number that still lets a genuinely transient
 * obstruction - a mob in a doorway, a block placed under the body - be walked around.
 *
 * The arithmetic is worth restating whenever [SEARCH_BUDGET_MS] moves, because it is what says
 * whether this outcome can be reached at all. Three stall windows are about 10.5 s of standing
 * still and the three searches between them add at most three budgets, which is about 1.5 s: some
 * 12 s in total, against the 20 s `MAX_MOVE_DEADLINE_MS` allows. So `stalled` is reachable at the
 * widest deadline the wire allows, with about eight seconds to spare - which is a change from the
 * 4000 ms budget this file used to carry, where the same three searches added 12 s and pushed the
 * total past every deadline, leaving `stalled` a bound nothing could actually reach.
 *
 * What has not changed is that a *shorter* deadline still answers first: below about 12 s a
 * request runs out of time before it runs out of retries and is answered `timedOut`, and both are
 * true statements about what happened. So the deadline a controller names is what decides which of
 * the two bounds a wedged body meets.
 */
const MAX_STALL_RETRIES = 2

/**
 * How many consecutive simulation steps a body must stand still on the ground, with that
 * same grounded position already reported to the server, before a reached goal is an arrival.
 *
 * The pathfinder emits `goal_reached` once the last node is within 0.35 blocks horizontally
 * and less than one block vertically, so a walk that ends in a step down reaches its goal
 * while the body is still falling onto it. Answering then shut the simulation with the body
 * in mid-air: the first live ten-agent run left one agent hanging 0.85 blocks above its
 * destination, and every later walk it was asked for was refused as `noRoute`. Two steps are
 * the fewest that show both a landed position and the report of it: Mineflayer writes a
 * step's report only after every `physicsTick` listener has run.
 */
const ARRIVAL_SETTLE_TICKS = 2

/**
 * How long one search may run, in milliseconds, and so how long the process may stall.
 *
 * **This is a control-plane latency figure and not a search-generosity one.** A search runs to
 * its end inside a single synchronous call (see [NO_SEARCH_SLICE]), so whatever this number is,
 * it is how long this process can stop reading its control channel, stop simulating its body and
 * stop answering every other connection it holds. Reaching it is the library answering `timeout`
 * with the best partial route it found, which Loom settles as its own `searchOverBudget` outcome
 * and never as `noRoute`: `noRoute` is the ground's answer, and this is Loom's own bound.
 *
 * Chosen from measurements rather than picked, at the bounds this file already enforces. Taken
 * on one host against the pinned library, over 25 searches each, with the world loaded out to
 * seven chunk columns each way so the search had room to expand:
 *
 *  - A walk to the far edge of [MOVE_REACH_BLOCKS] over open ground visits 32 nodes: 12 ms
 *    minimum, 15 ms median, 30 ms worst.
 *  - A flight at the wire's own `MAX_RETREAT_DISTANCE` visits 301 nodes and is the **worst
 *    search this body can be asked for and still succeed**: 112 ms minimum, 127 ms median,
 *    172 ms worst. It does not grow with the loaded world, because the separation it has to gain
 *    is what bounds it.
 *  - An *unreachable* destination at the full reach exhausts 6115 nodes in 1.8 to 2.9 s, and up
 *    to 5.2 s on a loaded host. That is the stall the previous 4000 ms budget permitted, and it
 *    is why the number came down. The node count is the whole graph a request can produce and
 *    `movement.test.js`'s own `WIDEST_SEARCH_DESTINATION` carries the arithmetic for it.
 *
 * Three times the worst succeeding search is the smallest budget those measurements support: it
 * covers that search on a host three times slower than the one measured, and it is a fifth of
 * what an exhausting search would spend.
 *
 * **What a wall clock cannot buy, and this one does not pretend to.** The bound is time, while the
 * thing bounded is work, so on a host that takes the process away mid-search the two stop
 * agreeing: the flight above measured 1296 ms with sixteen busy processes on twelve cores, and
 * even a ten-node search - three milliseconds of work - has been seen past this bound on a host
 * oversubscribed that far *and* running three other suites. The library offers no way to bound a
 * search by nodes, so a clock is the only lever there is, and the answer such a host gets is
 * `searchOverBudget`: Loom saying it stopped looking, which is true, rather than `noRoute`, which
 * would be a claim about ground nothing looked at. That is the whole point of splitting the two,
 * and it is why `movement.test.js` asserts the *work* a search does wherever it can and the clock
 * only where the clock is the subject.
 */
const SEARCH_BUDGET_MS = 516

/**
 * The per-call slice, which Loom deliberately does not have: one search, one call.
 *
 * The library slices a search by wall clock and resumes the remainder on the next physics tick,
 * and **a resumed search is not the search it interrupted**: `getPathFromTo` runs
 * `postProcessPath` on the result of every slice, which rewrites the `curPoint` of the very
 * `Move` nodes the A* context still holds in its open heap and open map. The resumed search then
 * walks a graph that has moved under it, and can lose the goal it was one pop from and exhaust
 * itself instead - which arrives as `noPath`, and would be reported as the ground refusing a
 * route the body was walking most of the way across. For a `flee` that is an agent told there is
 * nowhere to run while a hostile closes.
 *
 * So there is no slice. With none, `compute()` runs to its own end - the goal, an exhausted
 * graph, or [SEARCH_BUDGET_MS] - inside one call, and a resume is unreachable by construction
 * rather than merely unlikely. The library checks the slice before the budget in the same loop,
 * so this has to be strictly greater than the budget for the budget to be the one that answers;
 * unbounded is the only value that cannot be reached at all.
 *
 * The cost is paid by [SEARCH_BUDGET_MS], which is why that number is a latency bound.
 */
const NO_SEARCH_SLICE = Number.POSITIVE_INFINITY

/**
 * How far from the start the search may expand, in blocks.
 *
 * Twice the reach, so an ordinary detour round an obstacle is affordable while a search
 * cannot wander the world. The library's own default is -1, which is no limit at all.
 *
 * **It is a weak bound for a flight and must not be read as a hard one.** The library
 * derives its ceiling as the start node's heuristic plus this number and then only applies
 * it while that ceiling is positive - and a flee's goal is inverted, so its start heuristic
 * is negative and the ceiling can come out at or below zero, which disables the radius
 * entirely. [SEARCH_BUDGET_MS] is the bound that holds unconditionally.
 */
const SEARCH_RADIUS_BLOCKS = 64

/**
 * How far a route may drop in one move, in blocks.
 *
 * Below vanilla's own fall-damage threshold, so a body never hurts itself walking a route
 * Loom asked for. The library's default of four is exactly the height that starts to hurt.
 */
const MAX_DROP_BLOCKS = 3

/**
 * Every packet the client writes to say where its own simulation left the body.
 *
 * `flying` is the one a reader would miss: it is the report the plugin writes when neither the
 * position nor the facing changed, which is precisely the unasked heartbeat. `teleport_confirm`
 * is deliberately *not* in it - an unconfirmed teleport leaves the server ignoring the client,
 * so it is the protocol's own reply to something the server did rather than a report of the
 * body's, and it is written whether or not a request is in flight.
 *
 * Position reports are only one of the two kinds of packet a walk produces; see
 * [MOVEMENT_CONTROL_PACKETS] for the other and [SCOPED_MOVEMENT_PACKETS] for the set the gate
 * is actually built on.
 */
const MOVEMENT_REPORT_PACKETS = Object.freeze(['position', 'position_look', 'look', 'flying'])

/**
 * Every packet the client writes because one of its control states changed.
 *
 * `bot.setControlState` is the writer a route reaches, and it writes for exactly two of the
 * seven controls: `sprint` is an `entity_action`, and `sneak` is a `player_input` where
 * `supportFeature('newPlayerInputPacket')` holds and an `entity_action` where it does not.
 * Mineflayer's physics plugin has another `entity_action` writer for an explicit elytra action,
 * which no body-control operation calls; the packet set stays broad enough to scope that writer
 * if an explicit operation ever does. `forward`, `back`, `left`, `right` and `jump` are simulated
 * locally and write nothing of their own - they reach the server as the position reports above -
 * which is why they are still *state* worth clearing even though they are silent.
 *
 * These are here because the library sets a control after its terminal-event callback returns,
 * so a set of report packets alone let an unasked `entity_action` out with no matching stop
 * behind it. See the module header.
 */
const MOVEMENT_CONTROL_PACKETS = Object.freeze(['entity_action', 'player_input'])

/**
 * Both kinds at once, and the whole of "the process writes no movement packet unasked".
 *
 * A closed set, because it is what [scopedSimulation] drops while nothing owns the path. The
 * union is the enforced statement rather than either half: the two halves are named separately
 * only because they fail differently and are measured differently, and a suite that counted
 * one of them as the whole would have missed exactly the packet that escaped.
 */
const SCOPED_MOVEMENT_PACKETS = Object.freeze([
  ...MOVEMENT_REPORT_PACKETS,
  ...MOVEMENT_CONTROL_PACKETS
])

/**
 * The gate that makes the client's simulation a request's rather than the process's.
 *
 * Two halves, because the plugin has two and each is reached by its own branch of its own
 * interval: `bot.physicsEnabled` is what gates the step simulation and the `physicsTick` it
 * emits, and the packet filter is what gates everything the plugin writes - the position
 * report, which it writes on a branch `physicsEnabled` never reaches, so turning the
 * simulation off alone still left a `position` packet going out once a second with nothing
 * asking for it, and the control packets, which the *pathfinder* causes it to write from
 * inside the tick that ended the request. See the module header for the second of those.
 *
 * The filter is installed once, on the client, and is a *drop* rather than a refusal: the
 * plugin has no error path for a packet it could not send, and there is nothing to tell it
 * anyway - with no request in flight nothing has moved, so what is dropped is a restatement of
 * a position the server already has.
 *
 * A bot with no client to write through gets the inert gate instead, which is not a special
 * case so much as the honest answer for one: a body that cannot write a packet has no
 * simulation to scope. That is what the wrapper's own unit cases run against.
 */
function isControlDeactivation (name, parameters) {
  if (name === 'entity_action') {
    return ['stop_sprinting', 'stop_sneaking', 1, 4].includes(parameters?.actionId)
  }
  if (name === 'player_input') {
    const inputs = parameters?.inputs
    return inputs !== null && typeof inputs === 'object' &&
      Object.values(inputs).every((value) => value === false)
  }
  return false
}

function scopedSimulation (bot) {
  const client = bot._client
  if (client === null || client === undefined || typeof client.write !== 'function') {
    return inertSimulation()
  }
  const reported = client.write.bind(client)
  let owner
  let draining = false
  let permitted
  // What the server was last told about where the body stands, from the reports that passed.
  const standing = { position: undefined, onGround: undefined }
  const passed = (name, parameters) => {
    if (MOVEMENT_REPORT_PACKETS.includes(name)) {
      if (typeof parameters?.onGround === 'boolean') standing.onGround = parameters.onGround
      if (Number.isFinite(parameters?.x) && Number.isFinite(parameters?.y) && Number.isFinite(parameters?.z)) {
        standing.position = [parameters.x, parameters.y, parameters.z]
      }
    }
    return reported(name, parameters)
  }
  client.write = (name, parameters) => {
    if (!SCOPED_MOVEMENT_PACKETS.includes(name)) return reported(name, parameters)
    if (owner === undefined && permitted?.(name, parameters) === true) {
      permitted = undefined
      return passed(name, parameters)
    }
    if (owner === undefined) return undefined
    if (draining && MOVEMENT_CONTROL_PACKETS.includes(name) &&
      !isControlDeactivation(name, parameters)) return undefined
    return passed(name, parameters)
  }
  return {
    /** The last position and ground state a report that reached the server stated. */
    reportedStanding () {
      return { position: standing.position, onGround: standing.onGround }
    },
    take (token) {
      owner = token
      draining = false
      bot.physicsEnabled = true
    },
    /** Takes the gate for `token` only when nothing owns it; answers whether it did. */
    takeIfFree (token) {
      if (owner !== undefined) return false
      owner = token
      draining = false
      bot.physicsEnabled = true
      return true
    },
    /**
     * Holds the current frame open only for its final report and control deactivation.
     *
     * A later request cannot take the gate while its owner is draining, and an activation
     * written after the terminal callback is dropped. The final position and `fullStop`
     * packets still reach the server before [release] closes the frame in a microtask.
     */
    drain (token) {
      if (owner === token) draining = true
    },
    /**
     * Lets exactly one packet `matches` accepts through while nothing owns the path, for an
     * explicit operation that must write a control packet of its own. Answers the revoke.
     */
    permitOnce (matches) {
      permitted = matches
      return () => {
        if (permitted === matches) permitted = undefined
      }
    },
    /**
     * Closes the gate, if `token` is still what opened it.
     *
     * Keyed on the token for the reason the module header gives, and idempotent in both
     * directions: a second release of the same token finds the gate already shut, and a
     * release of a superseded one finds a token that is not its own.
     */
    release (token) {
      if (owner !== token) return
      owner = undefined
      draining = false
      bot.physicsEnabled = false
    }
  }
}

/** A gate for a bot with nothing to gate: every call is a no-op. */
function inertSimulation () {
  return {
    take () {},
    takeIfFree () { return false },
    drain () {},
    release () {},
    permitOnce () { return () => {} },
    // No wire, so nothing was reported that an arrival could be checked against.
    reportedStanding () { return undefined }
  }
}

/** Each installed bot's gate, for operations that need one scoped packet permit. */
const simulations = new WeakMap()

/** Vanilla's leave-bed request, which a sleep's wake writes as a control packet. */
function isLeaveBed (name, parameters) {
  return name === 'entity_action' && [2, 'leave_bed'].includes(parameters?.actionId)
}

/**
 * Lets one leave-bed request through the movement gate for a sleep that is waking, while no
 * movement owns the path. Answers the revoke; a bot without the gate gets a no-op.
 */
function permitLeaveBed (bot) {
  return simulations.get(bot)?.permitOnce(isLeaveBed) ?? (() => {})
}

/**
 * Runs the client simulation for one explicit operation that moves nothing, and answers the
 * release, or undefined when another owner holds the gate or the bot has none.
 *
 * Only for a body standing on support, so the simulation can establish its true ground state
 * and nothing else: with no route and no control state, a supported body does not move. The
 * reason is a dig. Mineflayer answers every server teleport with `onGround: false`, and with
 * the simulation shut nothing ever corrects it - so both Mineflayer's dig time and the
 * server's destroy progress ran at vanilla's five-times-slower airborne rate for a body
 * standing on the ground, which put every bare-hand terracotta dig past its deadline.
 */
function holdSimulation (bot) {
  const simulation = simulations.get(bot)
  if (simulation === undefined) return undefined
  const token = Symbol('held')
  if (!simulation.takeIfFree(token)) return undefined
  return () => simulation.release(token)
}

/**
 * The most simulation steps a held operation's ending waits for the body to come to rest.
 *
 * Two seconds, which a fall of any height a body can be standing over inside the loaded
 * distance has finished well inside. A bound rather than a wait, so a lost connection that
 * stops the physics interval cannot hold an answer: the timer behind it covers that.
 */
const REST_SETTLE_MAX_TICKS = 40

/**
 * Keeps a held simulation running until the body is at rest, then releases it.
 *
 * At rest is what an arrival requires ([ARRIVAL_SETTLE_TICKS]): still on the ground for two
 * steps with that same grounded position already reported to the server. A dig can remove the
 * block a body stands on, and releasing the moment the server confirmed the break shut the
 * simulation one step into the fall - which left a live agent hanging 0.08 blocks into the air
 * for the rest of the run, so every later dig of its ran at the airborne rate and timed out.
 */
function releaseAtRest (bot, release, { maxTicks = REST_SETTLE_MAX_TICKS } = {}) {
  return new Promise((resolve) => {
    untilAtRest(bot, () => simulations.get(bot)?.reportedStanding?.(), () => {
      release()
      resolve()
    }, maxTicks)
  })
}

/**
 * Calls `done` once, when the body is at rest in the sense [releaseAtRest] states or after
 * `maxTicks` steps - or, should the physics interval stop, after the timer behind them.
 * `reportedStanding` answers what the server was last told, or undefined where there is no wire.
 */
function untilAtRest (bot, reportedStanding, done, maxTicks = REST_SETTLE_MAX_TICKS) {
  let ticks = 0
  let stable = 0
  let last
  const finish = () => {
    bot.off('physicsTick', step)
    clearTimeout(timer)
    done()
  }
  function step () {
    ticks += 1
    const here = bot.entity?.position === undefined || bot.entity?.position === null
      ? undefined
      : positionOf(bot.entity.position)
    const still = here !== undefined && last !== undefined && blocksApart(here, last) === 0
    last = here
    const wire = reportedStanding()
    const reported = wire === undefined || (
      here !== undefined && wire.onGround === true && wire.position !== undefined &&
      blocksApart(wire.position, here) === 0
    )
    stable = still && bot.entity?.onGround === true && reported ? stable + 1 : 0
    if (stable >= ARRIVAL_SETTLE_TICKS || ticks >= maxTicks) finish()
  }
  bot.on('physicsTick', step)
  const timer = setTimeout(finish, maxTicks * 50 + 500)
}

/** Whether a collision box of the block under the feet has its top exactly at the feet. */
function standsOnSupport (bot) {
  const feet = bot.entity?.position
  if (feet === undefined || feet === null) return false
  let below
  try {
    below = bot.blockAt(feet.offset(0, -1e-3, 0))
  } catch {
    return false
  }
  if (!Array.isArray(below?.shapes)) return false
  return below.shapes.some((shape) =>
    Math.abs(below.position.y + shape[4] - feet.y) < 1e-6 &&
    feet.x >= below.position.x + shape[0] - 0.3 && feet.x <= below.position.x + shape[3] + 0.3 &&
    feet.z >= below.position.z + shape[2] - 0.3 && feet.z <= below.position.z + shape[5] + 0.3
  )
}

/** Straight-line distance between two positions, in blocks. */
function blocksApart (one, other) {
  const dx = one[0] - other[0]
  const dy = one[1] - other[1]
  const dz = one[2] - other[2]
  return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz))
}

/**
 * Every movement-policy field a route is searched under, stated explicitly.
 *
 * Three kinds of field are in here and the difference is worth reading before changing one.
 *
 * The *abilities* are turned off, and off is stronger than it looks: `canDig` false
 * short-circuits `safeToBreak`, so nothing is ever broken and the four dig-related fields
 * below it are never read at all; `allowEntityDetection` false makes the entity count
 * return zero without sweeping anything, so a body neither avoids nor pushes through
 * creatures; and an empty `scafoldingBlocks` is strictly stronger than `allow1by1towers`
 * false, because the tower branch is gated on carrying scaffolding in the first place.
 *
 * The *costs and bounds* are stated even where an ability that reads them is off, because a
 * number left at a default is a number nobody chose. `maxDropDown` and
 * `infiniteLiquidDropdownDistance` are the two that are read whatever else is disabled.
 *
 * The *registry vocabularies* are assigned back from the instance the library built, and
 * that is deliberate rather than lazy. Emptying them would be either inert or wrong:
 * `blocksCantBreak` is a *restriction* on digging, so emptying it widens an ability rather
 * than narrowing one; `emptyBlocks` seeds the start-node offset and `climbables`,
 * `carpets`, `blocksToAvoid`, `fences`, `replaceables`, `liquids`, `gravityBlocks` and
 * `openable` are read for every node the search touches, so emptying them changes what the
 * ground *is*; and `interactableBlocks`, `entitiesToAvoid` and `passableEntities` are read
 * nowhere under this policy at all - the first is a constructor input already consumed, the
 * other two are gated behind entity detection - so emptying them would tighten nothing
 * while reading as though it had. Assigning them keeps the coverage proof honest: it fails
 * when the pinned library adds a field, and it never claims a bound this policy does not have.
 *
 * `blocksToAvoid` is the one derived field. Water is added to whatever the library put
 * there, which is what makes a route through water impossible - see the module header for
 * why that is the mechanism and not a flag. The id is read straight from the registry
 * because the library's own `inject` already requires it to be there.
 */
function movementPolicyFor (movements, { sprint, border }) {
  const water = movements.bot.registry.blocksByName.water.id
  return {
    // Abilities. Every one of these is a thing a later explicit skill would own.
    canDig: false,
    allow1by1towers: false,
    allowParkour: false,
    allowFreeMotion: false,
    allowEntityDetection: false,
    canOpenDoors: false,
    scafoldingBlocks: [],
    // The server's world border and nothing of Loom's: see `border.js`.
    exclusionAreasStep: border === undefined ? [] : [borderExclusion(border)],
    exclusionAreasBreak: [],
    exclusionAreasPlace: [],
    entityIntersections: {},
    // The one ability the request itself decides.
    allowSprinting: sprint,
    // Costs. Unread while their abilities are off, and stated rather than defaulted.
    digCost: 1,
    placeCost: 1,
    liquidCost: 1,
    entityCost: 1,
    dontCreateFlow: true,
    dontMineUnderFallingBlock: true,
    // Bounds that are read whatever else is disabled.
    maxDropDown: MAX_DROP_BLOCKS,
    infiniteLiquidDropdownDistance: false,
    // The library's own water, plus water, which is what makes water a wall.
    blocksToAvoid: new Set(movements.blocksToAvoid).add(water),
    // Registry vocabularies, kept exactly as the library derived them for this version.
    blocksCantBreak: movements.blocksCantBreak,
    liquids: movements.liquids,
    gravityBlocks: movements.gravityBlocks,
    climbables: movements.climbables,
    emptyBlocks: movements.emptyBlocks,
    replaceables: movements.replaceables,
    fences: movements.fences,
    carpets: movements.carpets,
    openable: movements.openable,
    interactableBlocks: movements.interactableBlocks,
    entitiesToAvoid: movements.entitiesToAvoid,
    passableEntities: movements.passableEntities
  }
}

/** Applies [movementPolicyFor] to `movements`, and answers it. */
function applyMovementPolicy (movements, { sprint, border }) {
  return Object.assign(movements, movementPolicyFor(movements, { sprint, border }))
}

/**
 * The six search settings, applied to the library's own plugin surface.
 *
 * Applied per request rather than once at startup, for two reasons. The plugin's `inject`
 * is deferred by Mineflayer to its `inject_allowed` event, so `bot.pathfinder` does not
 * exist at the moment the plugin is loaded and there is no ordering these settings could
 * rely on. And a setting applied per request cannot be left behind by something that reset
 * it: every search a body ever runs is run under exactly these numbers.
 *
 * `bestHarvestTool` answering null is the explicit "no tool selection": the library's own
 * default reaches into the inventory and equips something, which is a decision about the
 * body's hands that no request asked for. It is unreachable while `canDig` is false and is
 * stated anyway, for the reason the unread costs are.
 *
 * The order of the two clocks is not the order they are written in and is worth knowing: the
 * library checks the slice first, so the budget only ever answers because [NO_SEARCH_SLICE] is
 * a value the slice check cannot reach.
 */
function applyPathfinderPolicy (finder) {
  finder.thinkTimeout = SEARCH_BUDGET_MS
  finder.tickTimeout = NO_SEARCH_SLICE
  finder.searchRadius = SEARCH_RADIUS_BLOCKS
  finder.enablePathShortcut = false
  finder.LOSWhenPlacingBlocks = true
  finder.bestHarvestTool = () => null
}

/**
 * The request-scoped wrapper: `moveTo`, `flee`, the fixed route an explicit collection may
 * borrow, `halt`, and the one path owner between them.
 *
 * `now` is injected for the reason `body.js`'s is - `elapsedMs` is a measurement, and a
 * measurement is only proven by being driven. `simulation` is injected because the gate needs
 * a client to write through and this wrapper does not: [installMovement] is where the real one
 * is built, and a wrapper handed none scopes nothing.
 *
 * **The search clocks are deliberately *not* injectable.** They were, for one revision, so that
 * the suite could buy determinism with a value production never ran - which meant the only case
 * touching the real numbers was the one whose subject was the numbers themselves. Determinism is
 * now a property of the policy instead: with no slice there is no resume, so what a search
 * answers depends on the graph rather than on how often this host schedules a tick, and every
 * case runs under exactly what a body runs under. See [NO_SEARCH_SLICE].
 */
function createMovement (bot, {
  now = () => performance.now(),
  simulation = inertSimulation()
} = {}) {
  /**
   * The request that owns the path, or undefined when the body is not moving.
   *
   * Carries the run's own `owner` token as well as its cancellation, because that token is the
   * one identity behind all three effects an accepted request has: it occupies this slot, it
   * holds the simulation gate open, and it is what `body.js` records the asking connection
   * against. One token means a settle can be checked against the current holder rather than
   * assumed to be it.
   */
  let running

  /** Where the body stands, as the three numbers the wire carries, or undefined. */
  function standing () {
    const self = bot.entity
    if (self === null || self === undefined) return undefined
    return positionOf(self.position)
  }

  /** The current position of the exact entity one flight admitted, or undefined. */
  function liveThreatPosition (threat) {
    if (threat.dead === true) return undefined
    const entities = bot.entities
    if (entities === null || entities === undefined) return undefined
    const tracked = entities[threat.id]
    if (tracked !== threat.entity || tracked.isValid === false) return undefined
    return positionOf(tracked.position)
  }

  /** How long since `startedAt`, as the whole non-negative count the wire carries. */
  function elapsedSince (startedAt) {
    return Math.max(0, Math.round(now() - startedAt))
  }

  /**
   * The report for an outcome the body reached without moving at all.
   *
   * The four readings are present exactly as they are on any other report, because a
   * request refused before anything was searched for has walked nowhere - which is a
   * measurement of zero rather than an absence - and a controller that asked a body to walk
   * needs to know where the body is whatever the answer was.
   */
  function unmoved (outcome) {
    const from = standing()
    if (from === undefined) return undefined
    return { outcome, from, position: from, travelled: 0, elapsedMs: 0 }
  }

  /**
   * Runs one route to its end and answers what became of it.
   *
   * The setup order is load-bearing rather than tidy. `setMovements` must precede `setGoal`,
   * because the library's `resetPath` calls `clearCollisionIndex` on the current movements
   * without checking there are any. Loom's own listeners attach *after* both, and every one
   * of those three calls is synchronous, so the library's own setup events - a `path_reset`
   * for the goal being replaced, a `goal_updated` - are never observed as though they were
   * news about this request.
   *
   * Four route listeners and, for a flight, one exact-entity death listener. `goal_reached`
   * is the arrival. `path_update` carries the search's own verdict, and both of the ways it
   * can fail become terminal at once rather
   * than being waited out: the library answers `noPath` and `timeout` with the best *partial*
   * route it found and then re-searches nothing, so a body left alone would walk toward the
   * nearest node it could reach and stand there until the deadline, reported as a timeout
   * for a route the ground had already refused. `path_reset` is counted only for the
   * library's own futility reason, since every other reason is either Loom's own teardown or
   * a world change the library is about to re-plan around, which the deadline bounds. And
   * `physicsTick` is where the final measurements are taken and every movement bound is
   * applied before the terminal outcome is selected.
   *
   * Nothing listens for `path_stop` or `end`. The first is unreachable, since only
   * `bot.pathfinder.stop()` and an invalid goal emit it and neither happens here. The deadline
   * is the backstop for a lost connection. A flight does listen for `entityDead`, because
   * Mineflayer can leave a dead object in its live table until a later destroy packet; that
   * exact-object listener is removed by the same settlement as every route listener.
   *
   * **Every call into the third-party library is guarded, because this runs inside a socket
   * `data` listener.** There an exception is not a refused request but the end of the process,
   * taking every other connection with it and printing unbounded text from below this process
   * on the way out - which in a permadeath run is an agent's life spent on one movement
   * request. That is the rule `senseAnswer` and `settleMeal` already state, and the three calls
   * here obey it: a throw from the policy build, from `setMovements` or from `setGoal` leaves
   * `undefined`, which `body.js` refuses as `notReady`, with the gate closed again, the
   * deadline disarmed, no listener attached and nothing of the failure on the wire or in a log.
   */
  function walk ({ goal, sprint, timeoutMs, threat, owner = {} }) {
    const finder = bot.pathfinder
    if (finder === null || finder === undefined) return undefined
    const from = standing()
    if (from === undefined) return undefined
    const startedAt = now()
    const deadlineAt = startedAt + timeoutMs
    const seen = threat === undefined ? undefined : liveThreatPosition(threat)
    if (threat !== undefined && seen === undefined) return unmoved('threatGone')
    const separationAt = seen === undefined ? undefined : blocksApart(from, seen)
    const state = {
      travelled: 0,
      last: from,
      stalls: 0,
      seen,
      pending: undefined,
      draining: false,
      terminalFacing: undefined,
      landing: undefined,
      settled: false
    }
    let movements
    try {
      applyPathfinderPolicy(finder)
      movements = applyMovementPolicy(new Movements(bot), { sprint, border: worldBorderOf(bot) })
    } catch {
      return undefined
    }
    return new Promise((resolve) => {
      const searchTimeouts = new WeakMap()
      let originalGetPathTo
      let boundedGetPathTo
      const timer = setTimeout(
        () => finish('timedOut'),
        Math.max(0, deadlineAt - now())
      )

      function report (outcome) {
        const position = standing()
        if (position === undefined) return undefined
        const answer = {
          outcome,
          from,
          position,
          travelled: state.travelled,
          elapsedMs: elapsedSince(startedAt)
        }
        if (outcome !== 'threatGone' && state.seen !== undefined) {
          answer.separation = blocksApart(position, state.seen)
          answer.separationGained = answer.separation - separationAt
        }
        return answer
      }

      function threatDied (entity) {
        if (threat === undefined || entity !== threat.entity) return
        threat.dead = true
        terminal('threatGone')
      }

      function installSearchBoundary () {
        if (typeof finder.getPathTo !== 'function') return
        originalGetPathTo = finder.getPathTo
        boundedGetPathTo = function (movements, searchGoal) {
          const remaining = Math.max(0, deadlineAt - now())
          const requestBound = remaining <= SEARCH_BUDGET_MS
          const results = originalGetPathTo.call(
            this,
            movements,
            searchGoal,
            Math.min(SEARCH_BUDGET_MS, remaining)
          )
          if (results !== null && typeof results === 'object') {
            searchTimeouts.set(
              results,
              requestBound ? 'timedOut' : 'searchOverBudget'
            )
          }
          return results
        }
        finder.getPathTo = boundedGetPathTo
      }

      function restoreSearchBoundary () {
        if (boundedGetPathTo !== undefined && finder.getPathTo === boundedGetPathTo) {
          finder.getPathTo = originalGetPathTo
        }
      }

      function sample () {
        const here = standing()
        if (here !== undefined) {
          state.travelled += blocksApart(state.last, here)
          state.last = here
        }
        if (threat !== undefined) {
          state.seen = liveThreatPosition(threat)
          if (state.seen === undefined) return 'threatGone'
        }
        if (state.travelled > MAX_TRAVEL_BLOCKS) return 'overTravelled'
        return undefined
      }

      function selectedOutcome (requested, measured) {
        if (measured !== undefined) return measured
        if (requested === 'timedOut' || requested === 'searchOverBudget') return requested
        if (now() >= deadlineAt) return 'timedOut'
        return requested
      }

      function finish (outcome) {
        if (state.settled) return
        const requested = state.pending ?? outcome
        state.pending = undefined
        settle(selectedOutcome(requested, sample()))
      }

      function flushTerminal () {
        if (state.pending !== undefined) finish(state.pending)
      }

      function terminal (outcome) {
        if (state.settled || state.pending !== undefined) return
        state.pending = outcome
        state.draining = true
        if (Number.isFinite(bot.entity?.yaw) && Number.isFinite(bot.entity?.pitch)) {
          state.terminalFacing = { yaw: bot.entity.yaw, pitch: bot.entity.pitch }
        }
        simulation.drain(owner)
        queueMicrotask(flushTerminal)
      }

      /**
       * The teardown, checked once more after the library has finished with the tick.
       *
       * A library terminal event puts the packet gate into its drain state inside the callback,
       * while [settle] normally runs from Loom's later listener on the same `physicsTick`. This
       * check keeps the state half of the invariant true for every ending: a route left behind
       * is dropped, and dropping it is what clears the controls, for the reason [walk]'s own
       * cancellation note gives.
       *
       * Three properties, each load-bearing. A **microtask**, because that is the first moment
       * the library's synchronous tick is certainly over and it is still ahead of the `resolve`
       * below, so "from the moment a request is answered" is literally true rather than nearly
       * so - and because a refusal must reach its controller when it is decided rather than a
       * tick later, which rules out deferring the answer instead. Keyed on the **run token**,
       * through the `running` slot this settle cleared only if the path was still its own: a
       * later request that has taken the path over owns the controls, and a stale settle may
       * not clear a control that request set. And **conditional on there being residue**, so
       * the endings the library does tidy up after itself pass through as a no-op rather than
       * handing the library a second goal to report - an arrival clears the controls in
       * `fullStop` after the emit that settled it, and a stall in the `resetPath` whose own
       * `path_reset` settled it.
       */
      function repaired () {
        if (running !== undefined) return
        try {
          if (finder.isMoving()) finder.setGoal(null)
        } catch {
          // Nothing to report and nothing to log: see [walk]'s header.
        }
      }

      /**
       * The one ending, whatever ended it.
       *
       * Every outcome this request can reach after it took the path comes through here - the
       * arrival, the search's refusal, the stall bound, the travel bound, the deadline and a
       * cancellation - which is what lets the gate be closed on one path rather than one per
       * branch. Ordered: the listeners go first so the teardown's own events are not observed,
       * the route is dropped next, and the simulation is stopped after it, because dropping
       * the route is what clears the body's controls and that still wants a live client.
       *
       * The route is dropped inside a guard for the reason [walk]'s header gives, and the two
       * things that must not be skipped are after it: a throw from the library here would
       * otherwise leave the gate open and the promise unsettled, so the request would never be
       * answered and the body would keep simulating with nothing owning it.
       *
       * [repaired] is queued last because this teardown is not always the last word - see its
       * own note - and queued rather than called because what it has to outlast is the library
       * frame this function is running inside.
       */
      function settle (outcome) {
        if (state.settled) return
        state.settled = true
        bot.off('physicsTick', stepped)
        bot.off('goal_reached', reached)
        bot.off('path_update', searched)
        bot.off('path_reset', abandoned)
        bot.off('entityDead', threatDied)
        clearTimeout(timer)
        restoreSearchBoundary()
        try {
          finder.setGoal(null)
        } catch {
          // Nothing to report and nothing to log: see [walk]'s header.
        }
        if (state.terminalFacing !== undefined && bot.entity !== undefined && bot.entity !== null) {
          bot.entity.yaw = state.terminalFacing.yaw
          bot.entity.pitch = state.terminalFacing.pitch
        }

        const complete = () => {
          simulation.release(owner)
          if (running?.owner === owner) running = undefined
          queueMicrotask(repaired)
          resolve(report(outcome))
        }
        // A terminal library event happened inside the physics plugin's call stack. Its final
        // position report is written only after every `physicsTick` listener returns, so the gate
        // and owner stay in their drain state until the first microtask after that frame.
        const answer = () => {
          if (state.draining) queueMicrotask(complete)
          else complete()
        }
        // An ending that leaves the body in the air - a stall, a deadline or a refused route
        // mid-step - keeps the drained gate until it has landed, so a release never leaves it
        // hanging: one live agent's stalled flight did, and every later flight of its stalled
        // from the air. A body whose ground state the client does not state is not waited on.
        if (bot.entity?.onGround !== false) {
          answer()
          return
        }
        state.draining = true
        simulation.drain(owner)
        untilAtRest(bot, () => simulation.reportedStanding?.(), () => {
          // The fall is part of the journey: without it the landed position would be further
          // from the start than the distance the report says was travelled.
          sample()
          answer()
        })
      }

      /**
       * One tick's worth of measurement.
       *
       * The distance walked is summed as it is walked, which is what makes it the very
       * number the bound is applied to rather than a figure derived afterwards. A tick the
       * body has no statable position on is skipped rather than guessed at, so the next
       * readable tick accounts for the whole gap.
       *
       * The threat is resolved through the live entity table here, so a flight is answered
       * against where the same creature actually is and a vanished or replaced entity is
       * `threatGone` rather than a measurement against a stale object.
       */
      function stepped () {
        const requested = state.pending
        state.pending = undefined
        const outcome = selectedOutcome(requested, sample())
        if (outcome !== undefined) {
          settle(outcome)
          return
        }
        if (state.landing !== undefined) landing()
      }

      /**
       * A reached goal, held open until the body has landed; see [ARRIVAL_SETTLE_TICKS].
       *
       * The library clears its goal and stops the controls as it emits this, so the simulation
       * carries on only to let the body come to rest. The gate drains exactly as for a terminal
       * event - reports and deactivations pass, a new activation does not, and no later request
       * may take the path - and the facing held here is the one the answer restores. Nothing
       * else is asked of the library unless the landed position no longer satisfies the goal.
       */
      function reached () {
        if (state.settled || state.pending !== undefined || state.landing !== undefined) return
        state.landing = { stable: 0, last: undefined }
        if (Number.isFinite(bot.entity?.yaw) && Number.isFinite(bot.entity?.pitch)) {
          state.terminalFacing = { yaw: bot.entity.yaw, pitch: bot.entity.pitch }
        }
        simulation.drain(owner)
      }

      /** Whether the server was last told this exact grounded position, where there is a wire. */
      function reportedGrounded (here) {
        const wire = simulation.reportedStanding?.()
        if (wire === undefined) return true
        return wire.onGround === true && wire.position !== undefined && blocksApart(wire.position, here) === 0
      }

      function landing () {
        const here = standing()
        const still = here !== undefined && state.landing.last !== undefined &&
          blocksApart(here, state.landing.last) === 0
        state.landing.last = here
        state.landing.stable = still && bot.entity?.onGround === true && reportedGrounded(here)
          ? state.landing.stable + 1
          : 0
        if (state.landing.stable < ARRIVAL_SETTLE_TICKS) return
        state.landing = undefined
        if (goalHolds()) {
          settle('arrived')
          return
        }
        // Landed somewhere the goal does not hold: the route is asked for again from the
        // ground, under the stall bound, rather than reported as an arrival it is not.
        state.stalls += 1
        if (state.stalls > MAX_STALL_RETRIES) {
          settle('stalled')
          return
        }
        state.terminalFacing = undefined
        try {
          simulation.take(owner)
          finder.setGoal(goal)
        } catch {
          settle('stalled')
        }
      }

      /** The library's own arrival test, applied to where the body came to rest. */
      function goalHolds () {
        const position = bot.entity?.position
        if (position === undefined || position === null) return false
        try {
          const floored = position.floored()
          return goal.isEnd(floored) || goal.isEnd(floored.offset(0, 1, 0))
        } catch {
          return false
        }
      }

      /**
       * The search's own verdict, and the one place the two kinds of failure are told apart.
       *
       * `noPath` is the *ground's* answer: the library expanded every node it was allowed to
       * and none of them was the goal, so `noRoute` is a true statement about the world.
       * `timeout` is *Loom's* answer: either [SEARCH_BUDGET_MS] or the request's remaining
       * deadline stopped a search that had not finished. The bound supplied to that call is
       * retained so the first is `searchOverBudget` and the second is `timedOut`; neither is
       * `noRoute`, because nothing was learned about the ground. Collapsing them told a
       * controller the ground refused a route where the truth was that this process would
       * not spend any longer looking - and for a flight that reads as nowhere to run, which
       * is an agent standing still with a hostile on it.
       *
       * `partial` is unreachable, because there is no slice to exceed; see [NO_SEARCH_SLICE].
       * It is not settled on for that reason rather than by omission: a partial route is a
       * search still in progress, and this file has none.
       */
      function searched (results) {
        if (results.status === 'timeout') {
          terminal(searchTimeouts.get(results) ?? 'searchOverBudget')
          return
        }
        if (now() >= deadlineAt) {
          terminal('timedOut')
          return
        }
        if (results.status === 'noPath') terminal('noRoute')
      }

      function abandoned (reason) {
        if (reason !== 'stuck') return
        state.stalls += 1
        if (state.stalls > MAX_STALL_RETRIES) terminal('stalled')
      }

      // The moment this request takes the path, and so the moment the body starts being
      // simulated at all. Guarded with the two library calls it belongs beside, and unwound
      // whole if either throws: nothing was searched for, so nothing may be left running.
      try {
        simulation.take(owner)
        installSearchBoundary()
        finder.setMovements(movements)
        finder.setGoal(goal)
      } catch {
        clearTimeout(timer)
        restoreSearchBoundary()
        // Best effort, in its own guard: the throw may have been `setGoal` itself, and a goal
        // left on the library would be a route with no owner - which is the one thing this
        // wrapper exists to make impossible.
        try {
          finder.setGoal(null)
        } catch {
          // Nothing to report and nothing to log: see this function's header.
        }
        simulation.release(owner)
        resolve(undefined)
        return
      }
      bot.on('physicsTick', stepped)
      bot.on('goal_reached', reached)
      bot.on('path_update', searched)
      bot.on('path_reset', abandoned)
      if (threat !== undefined) bot.on('entityDead', threatDied)
      running = { owner, halt: () => finish('cancelled') }
    })
  }

  return {
    /**
     * Walks to one absolute destination, and answers once the route has ended.
     *
     * `outOfRange` is measured against the body's live position and settled before anything
     * is searched for, so a destination the body was never going to reach costs no search
     * time. Distance is a straight line and all three axes count, because the bound it is
     * measured against is a walk and a walk is never shorter than the line.
     *
     * `owner` is the caller's own token for this request, and is optional because only a
     * caller with something else to key on it - `body.js` keys the asking connection - has one
     * to give. Given none, the run mints its own, so the slot and the simulation gate are
     * keyed either way.
     */
    moveTo ({ destination, tolerance, timeoutMs, sprint, owner }) {
      if (running !== undefined) return undefined
      const from = standing()
      if (from === undefined) return undefined
      if (blocksApart(from, destination) > MOVE_REACH_BLOCKS) return unmoved('outOfRange')
      const [x, y, z] = destination
      return walk({ goal: new goals.GoalNear(x, y, z, tolerance), sprint, timeoutMs, owner })
    },

    /**
     * Walks until the body's feet are in any one of `cells`, and answers once the route has
     * ended. The cells are the caller's, already chosen: a collect names every standable cell
     * from which vanilla would pick its one item up, so the walk ends wherever the route first
     * reaches one, and no cell at all is `noRoute` before anything is searched for.
     */
    approach ({ cells, timeoutMs, sprint, owner }) {
      if (running !== undefined) return undefined
      const from = standing()
      if (from === undefined) return undefined
      if (cells.length === 0) return unmoved('noRoute')
      if (cells.every((cell) => blocksApart(from, cell) > MOVE_REACH_BLOCKS)) return unmoved('outOfRange')
      const goal = new goals.GoalCompositeAny(cells.map(([x, y, z]) => new goals.GoalBlock(x, y, z)))
      return walk({ goal, sprint, timeoutMs, owner })
    },

    /**
     * Steps the body straight toward one point beside it, without the pathfinder, and answers
     * once it has come to rest.
     *
     * A route ends wherever the pathfinder first stands in a goal cell, but vanilla picks an
     * item up only inside the player's box grown by one block, so an item lying in a one-high
     * nook beside the body can be in reach from one part of the cell it stands in and out of it
     * from the rest: the r28 plains rehearsal's alpha stood 0.08 short of its own dirt, and no
     * cell covered the item from every point. The caller names the point, inside a cell the
     * body can stand in, where the body's box fits that cell whole; this only walks there. It
     * holds the simulation and the path exactly as a route does, looks along the step and
     * presses forward each tick until the body is within [FINAL_APPROACH_TOLERANCE_BLOCKS] of
     * the point, has passed it, or [FINAL_APPROACH_MAX_TICKS] have run (`stalled`), then lets go
     * and answers once the body is at rest. A point further than [FINAL_APPROACH_MAX_BLOCKS]
     * is `outOfRange` before anything moves.
     */
    finalApproach ({ point, timeoutMs, owner = {} }) {
      if (running !== undefined) return undefined
      const from = standing()
      if (from === undefined) return undefined
      const apart = (here) => Math.hypot(point[0] - here[0], point[2] - here[2])
      if (apart(from) > FINAL_APPROACH_MAX_BLOCKS) return unmoved('outOfRange')
      const startedAt = now()
      return new Promise((resolve) => {
        let ticks = 0
        let settled = false
        let travelled = 0
        let last = from
        const towards = [point[0] - from[0], point[2] - from[2]]
        const timer = setTimeout(() => finish('timedOut'), Math.max(0, timeoutMs))
        function sample () {
          const here = standing()
          if (here === undefined) return last
          travelled += blocksApart(last, here)
          last = here
          return here
        }
        function release () {
          try {
            bot.setControlState('forward', false)
            bot.setControlState('sprint', false)
          } catch {
            // Nothing to report and nothing to log: see [walk]'s header.
          }
        }
        function finish (outcome) {
          if (settled) return
          settled = true
          bot.off('physicsTick', step)
          clearTimeout(timer)
          release()
          simulation.drain(owner)
          untilAtRest(bot, () => simulation.reportedStanding?.(), () => {
            sample()
            simulation.release(owner)
            if (running?.owner === owner) running = undefined
            resolve({ outcome, from, position: last, travelled, elapsedMs: elapsedSince(startedAt) })
          })
        }
        function step () {
          const here = sample()
          ticks += 1
          const dx = point[0] - here[0]
          const dz = point[2] - here[2]
          // Within the tolerance, or past the point along the step: the step is done.
          if (Math.hypot(dx, dz) <= FINAL_APPROACH_TOLERANCE_BLOCKS || dx * towards[0] + dz * towards[1] < 0) {
            finish('arrived')
            return
          }
          if (ticks > FINAL_APPROACH_MAX_TICKS) {
            finish('stalled')
            return
          }
          try {
            bot.entity.yaw = Math.atan2(-dx, -dz)
            bot.entity.pitch = 0
            bot.setControlState('sprint', false)
            bot.setControlState('forward', true)
          } catch {
            finish('stalled')
          }
        }
        try {
          simulation.take(owner)
        } catch {
          clearTimeout(timer)
          resolve(undefined)
          return
        }
        bot.on('physicsTick', step)
        running = { owner, halt: () => finish('cancelled') }
      })
    },

    /**
     * Runs from one creature a named sense answer reported, and answers once the flight has
     * ended.
     *
     * The three handle checks are in the order `eating.js` states and for the same reasons:
     * the handle first, because an unresolvable one names no creature to look for; then the
     * place in the answer, because an index past its end is a handle the mind invented
     * rather than named back; then the creature itself, because a threat can die, despawn or
     * change dimension while a decision is in flight. All three are answered before anything
     * is searched for.
     *
     * The goal is a fixed snapshot of where the threat stood, deliberately not a goal that
     * follows it: one request gets one answer here, and a goal that moved would be the body
     * re-deciding where to go for as long as the request lasted. What the flight is *scored*
     * against is the live reading instead - see [walk]'s per-tick measurement - so the
     * snapshot bounds the search and never the report.
     */
    flee ({ offered, entity, avoid = [], distance, timeoutMs, sprint, owner }) {
      if (running !== undefined) return undefined
      if (offered === undefined) return unmoved('senseForgotten')
      const named = offered[entity]
      if (named === undefined) return unmoved('threatUnnamed')
      const tracked = bot.entities === null || bot.entities === undefined
        ? undefined
        : bot.entities[named.id]
      const seen = tracked === undefined || named.dead === true ||
        tracked !== named.entity || tracked.isValid === false
        ? undefined
        : positionOf(tracked.position)
      if (seen === undefined) return unmoved('threatGone')
      const near = new goals.GoalNear(seen[0], seen[1], seen[2], distance)
      const start = bot.entity?.position
      // The other hunters the controller named, where each stands now; one gone is passed over.
      const others = avoid.map((index) => offered[index]).filter((other) => other !== undefined && other.dead !== true)
        .map((other) => bot.entities?.[other.id] === other.entity ? positionOf(other.entity.position) : undefined)
        .filter((at) => at !== undefined)
      return walk({
        goal: new SafeFlightGoal(new goals.GoalInvert(near), {
          border: worldBorderOf(bot),
          floor: Number.isFinite(start?.y) ? Math.floor(start.y) - MAX_FLIGHT_DESCENT_BLOCKS : -Infinity,
          threat: seen,
          others,
          start: Number.isFinite(start?.x) ? [start.x, start.y, start.z] : undefined
        }),
        sprint,
        timeoutMs,
        threat: named,
        owner
      })
    },

    /**
     * Stops whichever movement is running, and answers whether one was.
     *
     * False is not a refusal: a controller that halts twice, or halts after a route has
     * already ended, is asking what state the body is in. The movement itself is answered on
     * its own request with `cancelled`, so the controller holding it open sees that request
     * end rather than having to infer it.
     */
    halt () {
      if (running === undefined) return false
      running.halt()
      return true
    }
  }
}

/** How far inside the world border a flight's end must lie, in blocks. */
const FLIGHT_BORDER_MARGIN_BLOCKS = 3

/** How far from every other named hunter a flight must end, of those it began further from. */
const FLIGHT_OTHER_CLEARANCE_BLOCKS = 8

/** The horizontal distance between two positions. */
function horizontalApart (one, other) {
  return Math.hypot(one[0] - other[0], one[2] - other[2])
}

/** How far below where it began a flight may end: a ravine is no refuge. */
const MAX_FLIGHT_DESCENT_BLOCKS = 4

/**
 * A flight's goal with its end held to safe ground: at least [FLIGHT_BORDER_MARGIN_BLOCKS] inside
 * the server's world border, and no more than [MAX_FLIGHT_DESCENT_BLOCKS] below where it began.
 * The route may pass anywhere the search allows; only where it may stop is held. In r23 eight
 * flights ended against the border, kappa's six pinning it along z -63.4 in a ravine for 1100
 * ticks, and delta's v30 rehearsal flight ended sixteen blocks down a ravine. With no safe end in
 * reach the search answers noRoute, which is the ground's honest answer.
 */
class SafeFlightGoal extends goals.Goal {
  constructor (inner, { border, floor, threat, others = [], start }) {
    super()
    this.inner = inner
    this.border = border
    this.floor = floor
    this.others = others
    // Where the flight began against each other hunter, and its least separation from them all.
    this.startedFrom = start === undefined ? [] : others.map((other) => horizontalApart(start, other))
    this.leastAtStart = start === undefined ? 0 : Math.min(...[threat, ...others].filter((at) => at !== undefined).map((at) => horizontalApart(start, at)))
    this.threat = threat
  }

  heuristic (node) {
    return this.inner.heuristic(node)
  }

  isEnd (node) {
    return this.inner.isEnd(node) && node.y >= this.floor && this.clearOfBorder(node) && this.clearOfOthers(node)
  }

  /**
   * Whether an end at `node` keeps clear of every other hunter the controller named: no nearer to
   * any than the flight began, at least [FLIGHT_OTHER_CLEARANCE_BLOCKS] from any it began further
   * from, and its least separation from all of them grown (r24: gamma's flight from a spider ended
   * 0.6 blocks from a creeper, and alpha's beside one).
   */
  clearOfOthers (node) {
    if (this.others.length === 0) return true
    const at = [node.x + 0.5, node.y, node.z + 0.5]
    const clear = this.others.every((other, index) => {
      const apart = horizontalApart(at, other)
      const began = this.startedFrom[index] ?? 0
      return apart >= Math.min(began, FLIGHT_OTHER_CLEARANCE_BLOCKS) && (began < FLIGHT_OTHER_CLEARANCE_BLOCKS || apart >= FLIGHT_OTHER_CLEARANCE_BLOCKS)
    })
    const least = Math.min(...[this.threat, ...this.others].filter((pos) => pos !== undefined).map((pos) => horizontalApart(at, pos)))
    return clear && least > this.leastAtStart
  }

  clearOfBorder (node) {
    if (this.border === undefined) return true
    const margin = { ...this.border, diameter: this.border.diameter - 2 * FLIGHT_BORDER_MARGIN_BLOCKS }
    return margin.diameter > 0 && columnInside(margin, Math.floor(node.x), Math.floor(node.z))
  }

  hasChanged () {
    return this.inner.hasChanged()
  }

  isValid () {
    return this.inner.isValid()
  }
}

/**
 * The bounds on one final approach: at most this far, in this many simulated ticks, to within
 * this horizontal distance of the point. A point a collect asks for lies inside the cell the body
 * already stands in, so a straight step of under one block is all it can need, and at walking
 * speed that takes about ten ticks; twenty is the most a step this short may stall for.
 */
const FINAL_APPROACH_MAX_BLOCKS = 1
const FINAL_APPROACH_MAX_TICKS = 20
const FINAL_APPROACH_TOLERANCE_BLOCKS = 0.08

/** The one wrapper each bot has, so a second install cannot mint a second owner of one path. */
const installations = new WeakMap()

/**
 * Loads the two plugins a walk needs into `bot` and answers the wrapper around them.
 *
 * Safe to call the moment the bot exists, before it has connected: Mineflayer defers every
 * plugin's `inject` to its own `inject_allowed` event, which fires after `bot.registry` is
 * assigned - which is what both plugins' `inject` needs. Everything this module does with
 * `bot.pathfinder` therefore happens per request, where the plugin is certain to be there.
 *
 * Both are loaded here through `bot.loadPlugin`, which is a separate seam from
 * `loadInternalPlugins` and leaves `body.js`'s own plugin table - the statement of which of the
 * *client library's* plugins merely report - untouched. `physics` is one of the library's own
 * and is loaded from here anyway, because it is neither a reporter nor a choice: it is the
 * simulation this module scopes, and the module that scopes something is the honest place to
 * load it. The order is the order it is needed in, since the pathfinder drives what physics
 * defines.
 *
 * **Idempotent, and that is an invariant rather than a convenience.** `bot.pathfinder` has
 * exactly one goal and the client has one position, so two wrappers over one bot would be two
 * owners of one path - precisely what the single-owner rule exists to prevent - and their two
 * gates would each hold the other's shut. One bot, one wrapper.
 */
function installMovement (bot, options) {
  const installed = installations.get(bot)
  if (installed !== undefined) return installed
  installVanillaCollision()
  trackWorldBorder(bot)
  bot.loadPlugin(physics)
  bot.loadPlugin(pathfinder)
  const simulation = scopedSimulation(bot)
  simulations.set(bot, simulation)
  const movement = createMovement(bot, { simulation, ...options })
  installations.set(bot, movement)
  return movement
}

module.exports = {
  FLIGHT_BORDER_MARGIN_BLOCKS,
  FLIGHT_OTHER_CLEARANCE_BLOCKS,
  MAX_FLIGHT_DESCENT_BLOCKS,
  SafeFlightGoal,
  ARRIVAL_SETTLE_TICKS,
  FINAL_APPROACH_MAX_BLOCKS,
  FINAL_APPROACH_MAX_TICKS,
  FINAL_APPROACH_TOLERANCE_BLOCKS,
  REST_SETTLE_MAX_TICKS,
  releaseAtRest,
  holdSimulation,
  permitLeaveBed,
  standsOnSupport,
  MAX_DROP_BLOCKS,
  MAX_STALL_RETRIES,
  MAX_TRAVEL_BLOCKS,
  MOVEMENT_CONTROL_PACKETS,
  MOVEMENT_REPORT_PACKETS,
  MOVE_REACH_BLOCKS,
  NO_SEARCH_SLICE,
  SCOPED_MOVEMENT_PACKETS,
  SEARCH_BUDGET_MS,
  SEARCH_RADIUS_BLOCKS,
  applyMovementPolicy,
  blocksApart,
  createMovement,
  installMovement,
  movementPolicyFor
}
