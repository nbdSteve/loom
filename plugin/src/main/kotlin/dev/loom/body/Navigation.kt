/*
 * How far a body may be asked to walk, how often it may be asked, and the one
 * relationship that keeps the answer honest.
 *
 * Loom's bodies walk with Minecraft's own ground navigator: a body asked to go
 * somewhere goes round a wall and up a step instead of pressing into it. That buys
 * real movement at the price of a real cost, and the cost is a *search*: one A* over a
 * bounded node count, plus a cube of chunk references built around the body to read the
 * world through. So the two things Loom bounds are how far a body may be sent and how often
 * anything may ask, which is all this file is - but note that it bounds only the searches
 * *Loom* asks for. The engine starts its own from a block change, and on its own tick when
 * it has throttled one; see [DEFAULT_PATH_REQUESTS_PER_TICK].
 *
 * THE ENGINE'S BOUND IS A WALK BUDGET, NOT A REACH, AND READING IT AS A REACH IS THE
 * ONE MISTAKE THIS FILE EXISTS TO PREVENT. `FOLLOW_RANGE` bounds the *length of the
 * route*: `PathFinder` abandons a partial route the moment the distance walked along it
 * reaches that number. A body sent straight across open ground may therefore go the
 * whole budget, while a body that has to go round something pays for the detour out of
 * the same money - so the furthest Loom may honestly promise is a fraction of it. That
 * is why [PATH_REQUEST_REACH_BLOCKS] is derived from [BODY_FOLLOW_RANGE_BLOCKS] and not
 * equal to it, and why the difference between them is called an allowance rather than
 * slack.
 *
 * MINECRAFT MAY FIND A ROUTE. IT MAY NEVER CHOOSE A GOAL. Every path request carries
 * a target the mind named. Nothing here or below it invents one, nothing here picks a
 * target when the mind's is refused - a refusal is recorded and the body stays where
 * it is, which [NavigationPolicy.route] makes true by dropping whatever route the body
 * was walking, for every rejected request and not only for the ones Loom's own bounds
 * refuse - and the body keeps zero vanilla goal selectors so nothing in the engine can
 * choose either. Clamping an out-of-range target to the edge of the reach would be Loom
 * choosing a goal the mind did not name, which is the one thing this design must not do,
 * so an out-of-range target is refused instead.
 *
 * THE COHERENCE RULE IS LOAD-BEARING, AND FAILS QUIETLY. A path search reads the
 * world through `PathNavigationRegion`, which fetches chunks without ever blocking
 * and substitutes an *empty* chunk for any that is not loaded. An unloaded chunk
 * therefore reads as air rather than as an error: the search comes back with a
 * truncated route, or one that walks through ground which is really there, and
 * nothing anywhere says so. The area Loom holds loaded must therefore cover
 * everything a request can read, which is what [holdRadiusChunksFor] derives from the
 * numbers that decide it - the perception radius among them, because a perception reads
 * the world around the same body and an unloaded chunk hands back no entities rather
 * than an error. Writing the hold radius down as a literal instead is what lets them
 * drift apart, in the direction of paths and scenes that look fine and are wrong - so it
 * is derived, and `NavigationTest` pins the derivation against both readers.
 *
 * Engine-free, for the same reason `PerceptionBound`, `Metabolism` and
 * `WorldFootprint` are: these are Loom's rules about movement rather than the
 * engine's, so a unit test pins them and the adapter is left holding nothing but the
 * API calls.
 */
package dev.loom.body

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The whole budget one route may spend, in blocks of walking.
 *
 * This one number sets four things at once, which is why it is stated once here and
 * derived everywhere else. It is the body's `FOLLOW_RANGE` attribute, and the engine
 * spends it three ways: `PathFinder` will not extend a route whose walked length has
 * reached it, nor expand any node further than it in a straight line from the start; the
 * navigator's constructor turns it into the search's node budget at sixteen nodes per
 * block; and every request uses it as the half-extent of the cube of chunk references
 * the search reads the world through, plus [PATH_REGION_RANGE_BLOCKS]. The fourth thing
 * is Loom's own reach, [PATH_REQUEST_REACH_BLOCKS], derived from it here.
 *
 * 16 gives a 256-node search over a 49-block cube. Villager's own default is 48,
 * which would be 768 nodes over a 113-block cube for every request, and a hold radius
 * of 4 chunks - 81 chunks per lone agent - to keep that cube readable.
 */
const val BODY_FOLLOW_RANGE_BLOCKS: Double = 16.0

/**
 * How far from itself a body may be asked to walk, in blocks.
 *
 * Half the walk budget, and the halving is the whole derivation: what is left over is
 * what a route may spend going round something. A reach equal to the budget would only
 * ever be honoured across open ground, because any obstacle makes the walk longer than
 * the straight line, and the search gives up on a route the moment its walked length
 * reaches the budget - answering with its best effort so far rather than with nothing,
 * so the shortfall arrives as a body pressed against a wall rather than as a refusal.
 *
 * That distinction is invisible from the API and cost a whole in-world run to find. A
 * body three blocks from a nine-block wall, sent to a block seven and a half blocks away
 * on the far side, was given a four-node route that walked it into the wall and stopped:
 * the way round was eighteen blocks of walking against a sixteen-block budget. Half the
 * budget for the reach and half for the detour is what buys a body its way round.
 *
 * It follows that this is *smaller than what a body can see*: the perception radius is
 * deliberately the wider of the two, and says why where it is set
 * ([PERCEPTION_RADIUS_BLOCKS] in `PerceptionBound.kt`). A target the mind saw and named
 * beyond this reach is refused, and the refusal is recorded. Walking there in stages is
 * the agent's own business until an intent exists for it.
 */
const val PATH_REQUEST_REACH_BLOCKS: Double = BODY_FOLLOW_RANGE_BLOCKS / 2

/**
 * The extra margin, in blocks, the engine adds around the follow range when it builds
 * the region a path search reads.
 *
 * Not Loom's number: the `createPath(BlockPos, accuracy)` overload passes a hardcoded
 * 8 for it. It is restated here because the hold radius has to cover the region the
 * engine actually reads, not the one Loom asked for, and because a version bump that
 * changed it would otherwise change the coherence rule silently.
 */
const val PATH_REGION_RANGE_BLOCKS: Int = 8

/**
 * How near a path must get to the named target to count as reaching it, in blocks.
 *
 * Zero: the mind names a block and the body is routed to that block. A larger
 * accuracy is how a vanilla goal says "near enough", and near enough is a judgement
 * about a goal rather than about a route.
 */
const val PATH_REQUEST_ACCURACY_BLOCKS: Int = 0

/**
 * How fast a body walks a route, as a multiplier on its movement speed attribute.
 *
 * A villager's `MOVEMENT_SPEED` is 0.5, and a modifier of 1.0 on top of it reads as a
 * body sprinting everywhere. Half of it is an ordinary walking pace, and matches the
 * per-tick pace the previous cheap movement model was tuned to.
 */
const val WALK_SPEED_MODIFIER: Double = 0.5

/**
 * The smallest displacement worth calling movement, in blocks.
 *
 * Movement is now observed rather than caused: the engine moves the body and Loom
 * reads where it ended up, so the record needs a floor below which a reading is
 * indistinguishable from a body standing still. Deliberately small - a hundredth of a
 * block - because the point is to filter float noise, not to hide slow movement.
 */
const val NOTICEABLE_MOVE_BLOCKS: Double = 0.01

/**
 * How many path requests Loom will serve across all agents in one tick.
 *
 * This is the whole of "one agent now, many later": a path search is the costliest thing
 * native navigation introduces, and every search *Loom asks for* goes through
 * [NavigationPolicy]. At roughly a millisecond per 256-node search, four is under a tenth of
 * a 50 ms tick, and a fifth request in the same tick is refused and *recorded* rather than
 * quietly dropped - so a run that outgrows the budget says so in its own log instead of
 * merely feeling sluggish.
 *
 * IT DOES NOT BOUND EVERY SEARCH, BECAUSE THE ENGINE STARTS SOME ITSELF.
 * `ServerLevel.sendBlockUpdated` runs on every block change whose old and new collision
 * shapes differ, walks the `navigatingMobs` set that `onTrackingStart` puts every `Mob` into,
 * and calls `PathNavigation.recomputePath` on each navigator whose `shouldRecomputePath`
 * answers yes - any body with a live route near the changed block. That is a full A* this
 * bucket never sees, and `recomputePath` throttles itself to one per twenty game ticks by
 * setting `hasDelayedRecomputation`, which `PathNavigation.tick` then acts on, so the tick
 * path is not search-free either. What bounds a recompute is per body rather than per tick:
 * the same 256-node budget [BODY_FOLLOW_RANGE_BLOCKS] fixes, and the fact that it fires only
 * for a body already walking. Budgeting those too is an owed follow-up, deliberately not done
 * here; `AGENTS.md` records it.
 */
const val DEFAULT_PATH_REQUESTS_PER_TICK: Int = 4

/**
 * The chunk radius Loom must hold loaded around a living agent for a path request to
 * read the truth, and for a perception to see what is really there.
 *
 * Derived rather than written down, because the number that matters is a
 * *relationship*: a request centred on the body reads a cube of half-extent
 * [followRangeBlocks] + [regionRangeBlocks], and every chunk that cube touches must
 * already be loaded or it reads as air.
 *
 * A perception reads the world around the same body, out to
 * [perceptionRadiusBlocks], and needs the same thing for a different reason: an
 * unloaded chunk holds no entities the engine will hand back, so a body would perceive
 * a quiet scene rather than fail. Whichever of the two reaches further is what the hold
 * has to cover, so the radius is taken from the larger - today the two are both 24
 * blocks and the hold is unchanged at 2 chunks, which is exactly the coincidence that
 * would have made a later change to either one silent.
 *
 * The chunk-edge case needs no allowance of its own, which is not obvious. With the
 * body in chunk `c` at radius `r`, Loom holds blocks `16(c - r)` through
 * `16(c + r) + 15`, and the body's own block `b` satisfies `16c <= b <= 16c + 15`.
 * The worst case at the low edge is `b = 16c`, needing `16(c - r) <= b - extent`; at
 * the high edge it is `b = 16c + 15`, needing `16(c + r) + 15 >= b + extent`. Both
 * reduce to `16r >= extent`: the fifteen blocks of the body's own chunk supply
 * exactly the slack the other side of the cube loses.
 */
fun holdRadiusChunksFor(
    followRangeBlocks: Double = BODY_FOLLOW_RANGE_BLOCKS,
    regionRangeBlocks: Int = PATH_REGION_RANGE_BLOCKS,
    perceptionRadiusBlocks: Double = PERCEPTION_RADIUS_BLOCKS,
): Int {
    require(followRangeBlocks > 0.0) { "followRangeBlocks must be positive, was $followRangeBlocks" }
    require(regionRangeBlocks >= 0) { "regionRangeBlocks must not be negative, was $regionRangeBlocks" }
    require(perceptionRadiusBlocks > 0.0) {
        "perceptionRadiusBlocks must be positive, was $perceptionRadiusBlocks"
    }
    val extent = max(followRangeBlocks + regionRangeBlocks, perceptionRadiusBlocks)
    return max(1, ceil(extent / CHUNK_BLOCKS).toInt())
}

/**
 * The world-loading footprint that keeps every path request readable.
 *
 * The one way the port should build a [WorldFootprint]: the radius comes from
 * [holdRadiusChunksFor] so raising [BODY_FOLLOW_RANGE_BLOCKS] or
 * [PERCEPTION_RADIUS_BLOCKS] cannot leave the hold behind.
 */
fun navigableFootprint(): WorldFootprint = WorldFootprint(radiusChunks = holdRadiusChunksFor())

/**
 * Whether a body has actually gone anywhere between two readings.
 *
 * A change of world counts however small the coordinates moved, because a body that
 * has crossed a dimension seam is somewhere else by definition.
 */
fun hasTravelled(
    from: BodyLocation,
    to: BodyLocation,
): Boolean =
    from.world != to.world ||
        abs(to.x - from.x) >= NOTICEABLE_MOVE_BLOCKS ||
        abs(to.y - from.y) >= NOTICEABLE_MOVE_BLOCKS ||
        abs(to.z - from.z) >= NOTICEABLE_MOVE_BLOCKS

/**
 * What Loom decided about one request to route a body somewhere.
 *
 * A verdict, taken before the engine is asked anything, so a refusal costs no search
 * and can be recorded with the reason it was refused for. Every refusal is a fact
 * about Loom's own bounds - a target further off than a route can afford to walk, or a
 * tick that has already spent its search budget - and never about the world, which
 * only the engine can answer for.
 */
sealed interface PathRequest {
    /** Ask the engine. A search budget token has been spent on this request. */
    data object Granted : PathRequest

    /**
     * Refused: the target is further away than [PATH_REQUEST_REACH_BLOCKS].
     *
     * Refused rather than clamped. A clamped target would be a goal Loom chose. It is
     * also refused rather than tried and reported, because the engine's answer to a
     * target it cannot afford is a best-effort route that walks the body somewhere it was
     * never sent - so asking would move a body on the strength of a refusal.
     */
    data class OutOfRange(
        val blocksAway: Double,
        val radiusBlocks: Double,
    ) : PathRequest

    /** Refused: this tick has already spent its whole path-search budget. */
    data class OverBudget(
        val requestsPerTick: Int,
    ) : PathRequest
}

/** Says what was decided about a path request, in the words the log carries. */
fun describe(request: PathRequest): String =
    when (request) {
        PathRequest.Granted -> "route requested"
        is PathRequest.OutOfRange ->
            "target is ${amountOf(request.blocksAway)} blocks away, " +
                "beyond the ${amountOf(request.radiusBlocks)} a body may be routed within"
        is PathRequest.OverBudget ->
            "this tick has already spent its ${request.requestsPerTick} path request(s)"
    }

/**
 * The two engine calls Loom's movement policy needs a body to answer, and nothing else.
 *
 * A seam rather than an abstraction of the engine. The policy has to *carry out* its
 * verdict and not merely report it, and both halves of doing so are engine calls, so
 * naming them here is what keeps the whole decision in the domain - where a unit test
 * proves it - and leaves the adapter holding two lines of wiring.
 */
interface Walker {
    /**
     * Sets the body walking to [target], answering with what the engine made of the
     * request. Reached only for a request Loom has granted, and free to answer with
     * anything but an acceptance: the engine has refusals Loom's bounds know nothing
     * about, and [NavigationPolicy.route] treats every one of them as a refusal.
     */
    fun followRouteTo(target: BodyLocation): IntentOutcome

    /** Drops any route the body is following, leaving it standing where it is. */
    fun stopWalking()
}

/**
 * Loom's whole policy on routing bodies: how far, and how many per tick.
 *
 * Server-wide and stateful, because the budget is server-wide: one instance is shared
 * by every agent, so a hundred agents deciding to walk in the same tick cost the same
 * as four. Main-thread only, like the port that holds it - the counter is plain
 * mutable state and is not synchronised.
 *
 * The budget does not accrue. Each tick starts with the whole allowance and any of it
 * left over is gone, because the thing being bounded is the work inside one tick and
 * a saved-up burst would defeat exactly that.
 *
 * @param reachBlocks how far from itself a body may be sent. Must stay well under the
 *   body's own `FOLLOW_RANGE`, which the engine spends on the route's *length*, or Loom
 *   would advertise a reach only open ground could honour - which is why it comes from
 *   [PATH_REQUEST_REACH_BLOCKS] by default.
 * @param requestsPerTick how many searches all agents together may ask for in a tick.
 */
class NavigationPolicy(
    private val reachBlocks: Double = PATH_REQUEST_REACH_BLOCKS,
    private val requestsPerTick: Int = DEFAULT_PATH_REQUESTS_PER_TICK,
) {
    init {
        require(reachBlocks > 0.0) { "reachBlocks must be positive, was $reachBlocks" }
        require(requestsPerTick >= 1) { "requestsPerTick must be at least 1, was $requestsPerTick" }
    }

    /** The tick the current allowance belongs to, or `null` before the first request. */
    private var budgetedTick: Long? = null

    /** Requests granted so far in [budgetedTick]. */
    private var granted = 0

    /**
     * Whether a body at [from] may be routed to [to] on [tick], and why not if not.
     *
     * [to] is a place in the body's own world: the contract's `MoveTo` names three
     * coordinates and no world, so a target is always local to the body that was asked
     * to walk to it, and the world of [to] is not read.
     *
     * Cheap refusals are answered first and cost nothing: only a granted request spends
     * a budget token, so a tick full of out-of-range targets leaves the whole search
     * budget for the requests that could actually use it.
     */
    fun request(
        from: BodyLocation,
        to: BodyLocation,
        tick: Long,
    ): PathRequest {
        val away = blocksApart(from, to)
        return when {
            away > reachBlocks -> PathRequest.OutOfRange(away, reachBlocks)
            !take(tick) -> PathRequest.OverBudget(requestsPerTick)
            else -> PathRequest.Granted
        }
    }

    /**
     * Routes [walker], standing at [from], toward [to] on [tick], and says what happened.
     *
     * The stop on the refusal path is the part of this that is easy to leave out, and it
     * is why the whole verdict is carried out here rather than in the adapter. A body
     * already walking an earlier route keeps walking it until something drops it, so a
     * refusal that only recorded itself would put a `rejected` record in the research log
     * with `moved` records after it - a body moving under an intent Loom refused, which
     * anything derived from the log would read as movement Loom authorised. Standing the
     * body down is what makes this file's own rule true: a refused target leaves the body
     * where it is.
     *
     * THE RULE IS ABOUT THE OUTCOME, NOT ABOUT WHOSE BOUND WAS HIT, which is what makes it
     * hold on the case a reader would not expect. Loom's own refusals are only some of
     * them: a granted request still reaches an engine that has refusals of its own, and a
     * ground navigator will not search at all while its body is off the ground - stepping
     * down a slope, or mid-jump over a one-block rise - so it answers a perfectly
     * reachable target with no route whatever. That branch never touches the navigator, so
     * without this the earlier route would survive it.
     *
     * So the predicate is *not a grant* rather than any particular refusal: an outcome
     * that is not [IntentOutcome.Accepted] stops the body, whether it came from
     * [PathRequest] or from the walker, and whatever variants either grows later. Keying
     * it on `Rejected` instead would leave a walker answering `Unsupported` silently
     * walking the old route, and asserting that only two of the four outcomes can ever
     * arrive here is a stronger assumption than this rule needs.
     *
     * Stopping a body whose route the engine has just replaced costs a redundant call and
     * nothing else, and one rule is worth more than the saved call.
     */
    fun route(
        walker: Walker,
        from: BodyLocation,
        to: BodyLocation,
        tick: Long,
    ): IntentOutcome {
        val request = request(from, to, tick)
        val outcome =
            if (request == PathRequest.Granted) {
                walker.followRouteTo(to)
            } else {
                IntentOutcome.Rejected(describe(request))
            }
        if (outcome !is IntentOutcome.Accepted) {
            walker.stopWalking()
        }
        return outcome
    }

    /**
     * Takes one token from [tick]'s allowance, or reports that there is none left.
     *
     * Any tick that is not the one the allowance belongs to starts a fresh allowance,
     * including a tick that has gone backwards: a replayed tick sequence must get the
     * same answers as the first run of it.
     */
    private fun take(tick: Long): Boolean {
        if (budgetedTick != tick) {
            budgetedTick = tick
            granted = 0
        }
        if (granted >= requestsPerTick) {
            return false
        }
        granted++
        return true
    }
}

/**
 * How far apart two places are for the purpose of routing, in blocks.
 *
 * Straight-line distance, because the bound it is measured against is a *walk budget*
 * and the walk is never shorter than the straight line. That makes this the one reading
 * under which a granted request is a promise about the detour a body can still afford: a
 * target eight blocks away leaves eight more to go round something with.
 *
 * A per-axis maximum would not. It reads a target eight blocks away on each axis - a
 * fourteen-block diagonal - as being eight away, which spends nearly the whole budget
 * before the route has met an obstacle, and the corner of a reach measured that way is
 * exactly where the wall case fails.
 *
 * All three axes count. A route climbs and descends, and the engine charges the vertical
 * part of every step to the same budget.
 *
 * The record measures the *end of a route* against the named target with this same
 * function, so what an outcome says a body fell short by is comparable with the reach
 * that let it be asked at all. The world of either place is not read; both are places in
 * the body's own world by construction.
 */
fun blocksApart(
    from: BodyLocation,
    to: BodyLocation,
): Double = sqrt(square(to.x - from.x) + square(to.y - from.y) + square(to.z - from.z))

/** Reads better than `x * x` inside the sum above, and keeps that line short. */
private fun square(n: Double): Double = n * n
