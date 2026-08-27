/*
 * Loom's whole policy on running away: how far to look for somewhere to go, how fast
 * to go there, and what it means when there is nowhere.
 *
 * The engine half is two calls - a random-position search and the ordinary navigator -
 * and lives in `dev.loom.body.nms.BodyNavigation`. Everything that can be decided
 * without a world is decided here, for the same reason `Navigation.kt` exists: a
 * retreat that only a running server can explain is a retreat nothing can pin.
 *
 * Fleeing is routing, so it goes through `NavigationPolicy` like any other route and
 * inherits every bound it applies - the reach, the per-tick search budget, the node
 * budget, the water and hazard prices, and the rule that any refusal stops the route
 * the body was already walking. Nothing here re-implements or widens one.
 */
package dev.loom.body

import kotlin.math.sqrt

/**
 * How fast a body walks away from a threat, as a multiplier on its movement speed.
 *
 * Faster than [WALK_SPEED_MODIFIER] and still short of the 1.0 that reads as a sprint:
 * a body that fled at its strolling pace would be caught by everything that hunts its
 * kind - a zombie's own speed is 0.23 against a villager's 0.5, and the modifier is what
 * decides whether the gap actually opens. It is a named constant rather
 * than a literal at the call site because it is the one number that says how seriously
 * Loom takes a threat, and a survival run comparing predation deaths across settings has
 * to be able to state it.
 */
const val FLEE_SPEED_MODIFIER: Double = 0.8

/**
 * How far to either side the retreat search looks for somewhere to stand, in blocks.
 *
 * Not chosen freely: the search picks a spot near the body and away from the threat, and
 * that spot is then routed to through [NavigationPolicy], which refuses anything beyond
 * [PATH_REQUEST_REACH_BLOCKS]. So the *far corner* of the box searched has to sit inside
 * the reach, or Loom would spend searches proposing retreats its own bound then refuses -
 * a body standing still while the log said `rejected` about a target Loom itself chose.
 * [retreatCornerBlocks] is that arithmetic and `RetreatTest` pins it against the reach.
 */
const val RETREAT_SEARCH_HORIZONTAL_BLOCKS: Int = 5

/**
 * How far up or down the retreat search looks, in blocks.
 *
 * Small on purpose. It is the vertical slack the ground search is allowed for finding a
 * standable spot, not an instruction to climb: height counts in full against the reach
 * (see [blocksApart]), so every block of it is a block of horizontal distance given up,
 * and a body that fled upward on a cliff face would be spending its retreat on going
 * nowhere.
 */
const val RETREAT_SEARCH_VERTICAL_BLOCKS: Int = 2

/**
 * The distance to the furthest spot a retreat search can propose, in blocks.
 *
 * The corner of the box: horizontal in both axes at once, plus the vertical slack. Stated
 * as a function so the relationship to [PATH_REQUEST_REACH_BLOCKS] is a test rather than a
 * comment - widening either search bound without widening the reach would otherwise turn
 * every retreat into an out-of-range refusal, and only in the diagonal cases.
 */
fun retreatCornerBlocks(
    horizontalBlocks: Int = RETREAT_SEARCH_HORIZONTAL_BLOCKS,
    verticalBlocks: Int = RETREAT_SEARCH_VERTICAL_BLOCKS,
): Double {
    require(horizontalBlocks > 0) { "horizontalBlocks must be positive, was $horizontalBlocks" }
    require(verticalBlocks >= 0) { "verticalBlocks must not be negative, was $verticalBlocks" }
    val horizontal = horizontalBlocks.toDouble()
    val vertical = verticalBlocks.toDouble()
    return sqrt(horizontal * horizontal * 2 + vertical * vertical)
}

/**
 * How many times the ground is asked for somewhere to retreat to, at most.
 *
 * One answer is not enough, and an in-world run proved it: the vanilla search picks the
 * best of ten random blocks in a cone away from the threat, scored for *standability*
 * rather than for distance, so it is free to answer with the body's own doorstep - it
 * offered a spot 0.71 blocks away while a zombie closed from seven, and the whole wait for
 * the next goal review was spent on a two-node route. Asking a few times and keeping the
 * best answer is what turns that from a coin flip into a policy.
 *
 * Small, constant, and cheap on purpose. `LandRandomPos.getPosAway` costs block probes,
 * not path searches - it scores candidates with `getWalkTargetValue` and a
 * `isStableDestination` block test and never asks the navigator for a route - so sampling
 * does not touch the per-tick search budget. Only the one route the choice earns pays a
 * token, exactly as a single-candidate flee did.
 */
const val RETREAT_SEARCH_ATTEMPTS: Int = 4

/**
 * How far a retreat has to actually go to be worth taking, in blocks.
 *
 * A retreat continues at [RETREAT_RETRY_CADENCE_TICKS] and no faster, so a route that
 * finishes in two nodes leaves the body standing still for the rest of that window
 * with something hunting it - the shape of the death this bound exists to prevent.
 * Well short of [PATH_REQUEST_REACH_BLOCKS] because the search box is
 * itself smaller than the reach and a bound nothing can satisfy is a bound that only
 * refuses.
 */
const val MEANINGFUL_RETREAT_BLOCKS: Double = 3.0

/**
 * How much further from the threat a retreat has to end up, in blocks.
 *
 * Distance covered is not the same fact as distance gained: the vanilla search offers
 * somewhere inside a 90-degree cone away from the threat, so a candidate at the edge of
 * that cone can be a long walk that ends barely further off than it started. This is the
 * half of the policy that says a retreat is *away*, and it is deliberately less than
 * [MEANINGFUL_RETREAT_BLOCKS]: a step that is partly sideways because the ground straight
 * away is blocked is still a step worth taking.
 */
const val MEANINGFUL_SEPARATION_BLOCKS: Double = 2.0

/**
 * Which of the offered spots to retreat to, or why none of them will do.
 *
 * A separate type rather than a nullable target because the two refusals are two
 * different facts about a run - see [RetreatRefusal.NoRetreat] and
 * [RetreatRefusal.NoMeaningfulRetreat] - and a `null` would collapse them.
 */
sealed interface RetreatChoice {
    /** Run to [target]: it is far enough, and further from the threat than here. */
    data class Take(val target: BodyLocation) : RetreatChoice

    /** Do not run: [refusal] says which bound refused, in the words the log carries. */
    data class Refuse(val refusal: RetreatRefusal) : RetreatChoice
}

/**
 * Picks where a body at [from] should run to, given what the ground [offered] and where
 * the [threat] is standing.
 *
 * Three bounds, all measured with [blocksApart] so height counts in full the way the
 * reach does. A candidate has to be at least [MEANINGFUL_RETREAT_BLOCKS] away, has to put
 * at least [MEANINGFUL_SEPARATION_BLOCKS] more between the body and the threat than it has
 * now, and has to sit inside [PATH_REQUEST_REACH_BLOCKS] - the last because the chosen
 * target is routed to through [NavigationPolicy] like any other, and proposing something
 * that bound then refuses would spend a search token on a body left standing.
 *
 * Among the survivors the furthest from the threat wins, and ties keep the order the
 * ground offered them (`maxByOrNull` returns the first maximum), so the same offers always
 * yield the same retreat and a run can be replayed.
 */
fun chooseRetreat(
    from: BodyLocation,
    threat: BodyLocation,
    offered: List<BodyLocation>,
): RetreatChoice {
    if (offered.isEmpty()) {
        return RetreatChoice.Refuse(RetreatRefusal.NoRetreat)
    }
    val separation = blocksApart(from, threat)
    val worthwhile =
        offered.filter { spot ->
            val step = blocksApart(from, spot)
            step >= MEANINGFUL_RETREAT_BLOCKS &&
                step <= PATH_REQUEST_REACH_BLOCKS &&
                blocksApart(spot, threat) - separation >= MEANINGFUL_SEPARATION_BLOCKS
        }
    val chosen = worthwhile.maxByOrNull { blocksApart(it, threat) }
    return if (chosen == null) {
        RetreatChoice.Refuse(RetreatRefusal.NoMeaningfulRetreat)
    } else {
        RetreatChoice.Take(chosen)
    }
}

/**
 * Why a body was not sent running from something.
 *
 * Each variant is a distinct fact about *what went wrong*, and they are kept apart
 * because they need different answers from whoever reads the run back: a threat the body
 * could no longer name is a bound of Loom's own ref window, a threat that has gone is the
 * world moving faster than the mind, and nowhere to run to is the ground. Collapsing them
 * into one "could not flee" would leave a run unable to say whether its agents died
 * because they were cornered or because their decisions arrived too late to act on.
 *
 * Every one of them leaves the body standing: [NavigationPolicy.route] drops a route on
 * any outcome that is not an acceptance, and the refusals reached before it ever gets
 * there stop the body themselves.
 */
sealed interface RetreatRefusal {
    /** The handle is from a request this agent no longer holds bindings for. */
    data object ThreatForgotten : RetreatRefusal

    /** The request is remembered and never issued that handle. */
    data object ThreatUnnamed : RetreatRefusal

    /**
     * The handle named a creature that is not here to run from.
     *
     * Dead, despawned, or in another world - one refusal rather than three, because from
     * the body's point of view they are the same fact and none of them is knowable from
     * the handle. A body must not be sent fleeing a threat that is not in its world: the
     * retreat is computed as a direction *away from a position*, and a position in another
     * dimension is a number with no meaning here.
     */
    data object ThreatGone : RetreatRefusal

    /** The ground offered nowhere to retreat to within the search bounds. */
    data object NoRetreat : RetreatRefusal

    /**
     * The ground offered somewhere and none of it was worth walking to.
     *
     * Kept apart from [NoRetreat] because they say opposite things about the world: that
     * one is a body with nowhere to stand - walled in, over water, at the edge of what the
     * search may look at - and this one is a body with plenty of room whose offers were all
     * on its own doorstep or no further from the threat than it already was. A run that
     * could not tell them apart could not say whether its agents died cornered or died
     * taking retreats that gained nothing, which is precisely the failure this refusal was
     * added for.
     */
    data object NoMeaningfulRetreat : RetreatRefusal
}

/**
 * Which refusal a [resolution] amounts to, or `null` if the handle still names a creature.
 *
 * Total over [RefResolution] with no default branch: a resolution added later must be
 * given its own refusal before it can compile, because a new one silently falling through
 * to "the threat is gone" would report the world moving when in fact Loom had lost track.
 */
fun refusalFor(resolution: RefResolution): RetreatRefusal? =
    when (resolution) {
        is RefResolution.Bound -> null
        RefResolution.Stale -> RetreatRefusal.ThreatForgotten
        RefResolution.Unknown -> RetreatRefusal.ThreatUnnamed
    }

/**
 * The countable tag a refused flight is filed under.
 *
 * Beside [describe] for the same reason the tag of an ending is beside its own sentence: the
 * two answer the same question for the same reader - what a run can count, and what a person
 * reads - and a refusal that gained one without the other is how a transition becomes
 * uncountable.
 *
 * Total over [RetreatRefusal] with no default branch, and deliberately not one tag for all
 * five. [EventTag.RETREAT_FAILED] says the agent is still in range of something hunting it
 * and has been left standing, and it is the count a survival run reads for predation - so
 * only the two answers about the *ground* belong in it. A threat that has gone is the
 * opposite fact, and a handle that named nothing the body could resolve is not a fact about
 * the world at all; totalled together, a burnt-out zombie and a hallucinated ref would both
 * read afterwards as an agent that could not get away.
 */
fun tagOf(refusal: RetreatRefusal): String =
    when (refusal) {
        RetreatRefusal.ThreatForgotten -> EventTag.RETREAT_THREAT_FORGOTTEN
        RetreatRefusal.ThreatUnnamed -> EventTag.RETREAT_THREAT_UNNAMED
        RetreatRefusal.ThreatGone -> EventTag.RETREAT_THREAT_GONE
        RetreatRefusal.NoRetreat -> EventTag.RETREAT_FAILED
        RetreatRefusal.NoMeaningfulRetreat -> EventTag.RETREAT_FAILED
    }

/** Says why a body did not run, in the words the log carries. */
fun describe(refusal: RetreatRefusal): String =
    when (refusal) {
        RetreatRefusal.ThreatForgotten -> "the threat was named by a request this agent no longer holds"
        RetreatRefusal.ThreatUnnamed -> "that request never named such a threat"
        RetreatRefusal.ThreatGone -> "the threat is no longer in this body's world"
        RetreatRefusal.NoRetreat ->
            "nowhere to retreat to within " +
                "$RETREAT_SEARCH_HORIZONTAL_BLOCKS blocks either side and $RETREAT_SEARCH_VERTICAL_BLOCKS up or down"
        RetreatRefusal.NoMeaningfulRetreat ->
            "nothing worth retreating to in $RETREAT_SEARCH_ATTEMPTS look(s): none was " +
                "$MEANINGFUL_RETREAT_BLOCKS blocks off and $MEANINGFUL_SEPARATION_BLOCKS blocks further from the threat"
    }
