package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NavigationTest {
    private fun at(
        x: Double,
        y: Double = 64.0,
        z: Double = 0.0,
        world: String = "arena",
    ) = BodyLocation(world, x, y, z)

    private val origin = at(0.0)

    // --- the coherence rule ---------------------------------------------------------

    @Test
    @DisplayName("the hold radius covers the whole cube a path search reads")
    fun theHoldCoversWhatASearchReads() {
        // A request reads a cube of half-extent followRange + regionRange around the
        // body's own block. Held chunks are a square of (2r + 1) chunks around the
        // body's chunk, and the worst case at either edge reduces to 16r >= extent.
        val extent = BODY_FOLLOW_RANGE_BLOCKS + PATH_REGION_RANGE_BLOCKS

        assertTrue(
            holdRadiusChunksFor() * CHUNK_BLOCKS >= extent,
            "a search would read $extent blocks out, past the ${holdRadiusChunksFor() * CHUNK_BLOCKS} held",
        )
    }

    @Test
    @DisplayName("the hold radius covers how far a perception reads as well")
    fun theHoldCoversWhatAPerceptionReads() {
        // The second reader of the hold, and the one nothing related to it before: a
        // perception gathers the living things within PERCEPTION_RADIUS_BLOCKS of the body,
        // and the engine hands back no entities at all from a chunk that is not loaded. So
        // a perception past the hold is not an error and not an empty answer either - it is
        // a *quiet scene*, which is the one reading a survival run must never be given.
        // The 24-block radius fits inside today's two-chunk hold, and that was a
        // coincidence of two independent numbers until holdRadiusChunksFor took it in.
        val held = holdRadiusChunksFor() * CHUNK_BLOCKS

        assertTrue(
            held >= PERCEPTION_RADIUS_BLOCKS,
            "a perception reads $PERCEPTION_RADIUS_BLOCKS blocks out, past the $held held",
        )
    }

    @Test
    @DisplayName("the shipped numbers give a two-chunk hold")
    fun theShippedNumbersGiveTwoChunks() {
        // Pinned as values as well as as a relationship: the relationship alone would
        // stay satisfied if both numbers grew together, and the cost of the hold is
        // 25 chunks per lone agent rather than the 9 it was.
        assertEquals(16.0, BODY_FOLLOW_RANGE_BLOCKS)
        assertEquals(8.0, PATH_REQUEST_REACH_BLOCKS)
        assertEquals(8, PATH_REGION_RANGE_BLOCKS)
        assertEquals(0, PATH_REQUEST_ACCURACY_BLOCKS)
        assertEquals(24.0, PERCEPTION_RADIUS_BLOCKS)
        // Two chunks still: taking the perception radius into the derivation was needed to
        // relate the numbers, not because the existing hold was too small for them.
        assertEquals(2, holdRadiusChunksFor())
    }

    @Test
    @DisplayName("a wider perception radius forces a wider hold")
    fun aWiderPerceptionRadiusForcesAWiderHold() {
        // The half of the derivation the path search cannot exercise: seeing further than a
        // search reads has to widen the hold on its own, or the day the radius is raised is
        // the day bodies quietly stop perceiving the far edge of what they are told they see.
        assertEquals(4, holdRadiusChunksFor(perceptionRadiusBlocks = 56.0))
        assertThrows<IllegalArgumentException> { holdRadiusChunksFor(perceptionRadiusBlocks = 0.0) }
    }

    @Test
    @DisplayName("the reach leaves a route as much budget again to go round something with")
    fun theReachLeavesABudgetForADetour() {
        // The rule the wall case turns on. FOLLOW_RANGE is spent on the route's *length*,
        // so a reach equal to it is only ever honoured across open ground: the way round a
        // nine-block wall three blocks off was eighteen blocks of walking against a
        // sixteen-block budget, and the search answered with a four-node best effort that
        // walked the body into the wall. Whatever the two numbers become, what is left
        // after the reach must still be at least the reach again.
        val detourAllowance = BODY_FOLLOW_RANGE_BLOCKS - PATH_REQUEST_REACH_BLOCKS

        assertTrue(
            detourAllowance >= PATH_REQUEST_REACH_BLOCKS,
            "a reach of $PATH_REQUEST_REACH_BLOCKS leaves only $detourAllowance blocks to go round anything with",
        )
    }

    @Test
    @DisplayName("a wider walk budget forces a wider hold")
    fun aWiderBudgetForcesAWiderHold() {
        // A villager's own default follow range, which is what the body would have kept had
        // the attribute not been narrowed before the navigator was built.
        assertEquals(4, holdRadiusChunksFor(followRangeBlocks = 48.0))
    }

    @Test
    @DisplayName("even a tiny walk budget holds the ring around the body")
    fun aTinyBudgetStillHoldsARing() {
        // Never zero: a body standing at the edge of its own chunk needs the next one. Every
        // reader has to be made tiny to reach the floor at all, which is the derivation
        // working: the widest of them sets the hold, so one small number cannot shrink it.
        assertEquals(
            1,
            holdRadiusChunksFor(
                followRangeBlocks = 1.0,
                regionRangeBlocks = 0,
                perceptionRadiusBlocks = 1.0,
            ),
        )
    }

    @Test
    @DisplayName("a budget that cannot be held is refused rather than rounded")
    fun anUnholdableBudgetIsRefused() {
        assertThrows<IllegalArgumentException> { holdRadiusChunksFor(followRangeBlocks = 0.0) }
        assertThrows<IllegalArgumentException> { holdRadiusChunksFor(regionRangeBlocks = -1) }
    }

    @Test
    @DisplayName("the footprint the port uses is the derived one")
    fun theFootprintIsTheDerivedOne() {
        assertEquals(holdRadiusChunksFor(), navigableFootprint().radiusChunks)
    }

    // --- the request budget ---------------------------------------------------------

    @Test
    @DisplayName("a tick serves its allowance and refuses the request after it")
    fun aTickServesItsAllowanceAndNoMore() {
        val movement = NavigationPolicy(requestsPerTick = 2)

        assertEquals(PathRequest.Granted, movement.request(origin, at(1.0), tick = 7))
        assertEquals(PathRequest.Granted, movement.request(origin, at(2.0), tick = 7))
        assertEquals(PathRequest.OverBudget(2), movement.request(origin, at(3.0), tick = 7))
    }

    @Test
    @DisplayName("the next tick starts with a whole allowance again")
    fun theNextTickRefills() {
        val movement = NavigationPolicy(requestsPerTick = 1)
        movement.request(origin, at(1.0), tick = 7)

        assertEquals(PathRequest.Granted, movement.request(origin, at(1.0), tick = 8))
    }

    @Test
    @DisplayName("an unspent allowance does not accrue into a later burst")
    fun anUnspentAllowanceDoesNotAccrue() {
        val movement = NavigationPolicy(requestsPerTick = 1)
        // Ticks 1 through 5 ask for nothing at all.
        assertEquals(PathRequest.Granted, movement.request(origin, at(1.0), tick = 6))
        assertEquals(PathRequest.OverBudget(1), movement.request(origin, at(2.0), tick = 6))
    }

    @Test
    @DisplayName("a replayed tick sequence gets the same answers as the first run of it")
    fun aReplayedSequenceAnswersTheSame() {
        val movement = NavigationPolicy(requestsPerTick = 1)
        movement.request(origin, at(1.0), tick = 9)

        // A tick that has gone backwards is a different tick, not a continuation of the
        // one before: the budget is per tick, so replaying tick 8 must serve it afresh.
        assertEquals(PathRequest.Granted, movement.request(origin, at(1.0), tick = 8))
    }

    @Test
    @DisplayName("only a granted request spends a token")
    fun onlyAGrantedRequestSpendsAToken() {
        val movement = NavigationPolicy(requestsPerTick = 1)
        // Refused before the engine is asked anything, so it costs no search and no
        // token: a tick full of unreachable targets must leave the budget for the
        // requests that could have used it.
        val tooFar = movement.request(origin, at(100.0), tick = 4)

        assertTrue(tooFar is PathRequest.OutOfRange)
        assertEquals(PathRequest.Granted, movement.request(origin, at(1.0), tick = 4))
    }

    @Test
    @DisplayName("a budget that could serve nobody is refused")
    fun anImpossibleBudgetIsRefused() {
        assertThrows<IllegalArgumentException> { NavigationPolicy(requestsPerTick = 0) }
        assertThrows<IllegalArgumentException> { NavigationPolicy(reachBlocks = 0.0) }
    }

    // --- the reach ------------------------------------------------------------------

    @Test
    @DisplayName("a target at the edge of the reach is granted")
    fun theEdgeOfTheReachIsInside() {
        val movement = NavigationPolicy()

        assertEquals(PathRequest.Granted, movement.request(origin, at(PATH_REQUEST_REACH_BLOCKS), tick = 1))
    }

    @Test
    @DisplayName("a target past the reach is refused, never clamped, and says by how much")
    fun pastTheReachIsRefused() {
        val movement = NavigationPolicy()
        val refused = movement.request(origin, at(PATH_REQUEST_REACH_BLOCKS + 0.01), tick = 1)

        // Refused rather than clamped: a clamped target would be a goal Loom chose, and
        // the mind named this one.
        assertEquals(PathRequest.OutOfRange(PATH_REQUEST_REACH_BLOCKS + 0.01, PATH_REQUEST_REACH_BLOCKS), refused)
    }

    @Test
    @DisplayName("the reach is a straight line, so the corner of a cube of it is outside")
    fun theReachIsMeasuredAsAStraightLine() {
        val movement = NavigationPolicy()
        // The discriminating case, and the one an in-world run failed on. Eight blocks on
        // each of three axes is a straight line of nearly fourteen - which a walk cannot be
        // shorter than, so it leaves barely two blocks of the budget for going round
        // anything. A per-axis reading would grant it and promise a detour that is not
        // there.
        val corner = at(x = 8.0, y = 64.0 + 8.0, z = 8.0)

        assertTrue(movement.request(origin, corner, tick = 1) is PathRequest.OutOfRange)
    }

    @Test
    @DisplayName("a diagonal within the reach is granted, so the reach is not a cube corner short")
    fun aDiagonalInsideTheReachIsGranted() {
        val movement = NavigationPolicy()
        // The other side of the same measure: 4 and 4 on two axes is 5.7 of straight line,
        // comfortably inside, and a body must not be refused a target it can plainly walk
        // to. Pinned so the fix for the corner case above cannot become a per-axis reach of
        // half the size.
        val diagonal = at(x = 4.0, z = 4.0)

        assertEquals(PathRequest.Granted, movement.request(origin, diagonal, tick = 1))
    }

    @Test
    @DisplayName("a vertical target counts against the reach as much as a horizontal one")
    fun heightCountsAgainstTheReach() {
        val movement = NavigationPolicy()
        // A route climbs, and the engine charges the vertical part of every step to the
        // same walk budget. The old straight-line gait ignored height entirely.
        assertTrue(movement.request(origin, at(x = 0.0, y = 64.0 + 9.0), tick = 1) is PathRequest.OutOfRange)
    }

    @Test
    @DisplayName("the target's world is not consulted, because a target is always local")
    fun theTargetsWorldIsNotConsulted() {
        val movement = NavigationPolicy()
        // The contract's MoveTo names three coordinates and no world. The adapter builds
        // the target in the body's own world, and nothing here may second-guess that: a
        // cross-world comparison would be a distance with no meaning.
        val elsewhere = at(x = 1.0, world = "nether")

        assertEquals(PathRequest.Granted, movement.request(origin, elsewhere, tick = 1))
    }

    // --- what a refusal does to a route already running ------------------------------

    /**
     * A body reduced to the two things the policy may ask of one, and a tally of each.
     *
     * [engineAnswer] stands in for what the navigator made of a search Loom granted; left
     * unset it accepts, which is the case where a route really starts.
     */
    private class FakeWalker(
        private val engineAnswer: IntentOutcome? = null,
    ) : Walker {
        var routesAsked = 0
        var stops = 0

        override fun followRouteTo(target: BodyLocation): IntentOutcome {
            routesAsked++
            return engineAnswer ?: IntentOutcome.Accepted("walking to ${target.x},${target.y},${target.z}")
        }

        override fun stopWalking() {
            stops++
        }
    }

    @Test
    @DisplayName("every refusal of Loom's own drops the route the body was already walking")
    fun aRefusalLeavesTheBodyWhereItIs() {
        val movement = NavigationPolicy(requestsPerTick = 1)
        val walker = FakeWalker()
        // A granted request first, so there is a route running for the refusals to leave
        // behind. Without the stop the navigator keeps driving the body toward the *old*
        // target, and the record reads `rejected` followed by `moved` - a body moving under
        // an intent Loom refused, which anything derived from the log reads as movement
        // Loom authorised. Both refusals are exercised, because the stop belongs to the
        // refusal as such and not to one reason for it.
        movement.route(walker, origin, at(1.0), tick = 3)

        val tooFar = movement.route(walker, origin, at(100.0), tick = 3)
        val overBudget = movement.route(walker, origin, at(2.0), tick = 3)

        assertTrue(tooFar is IntentOutcome.Rejected, tooFar.toString())
        assertTrue(overBudget is IntentOutcome.Rejected, overBudget.toString())
        assertEquals(2, walker.stops, "each refusal must leave the body standing where it is")
        assertEquals(1, walker.routesAsked, "a refusal must cost no search")
    }

    @Test
    @DisplayName("a refusal from the engine itself drops the route the body was already walking")
    fun anEngineRefusalLeavesTheBodyWhereItIs() {
        val movement = NavigationPolicy()
        // The refusal a reader would not expect, and the one a Loom-side-only stop misses
        // entirely: the target is inside the reach and the tick has budget, so Loom grants
        // the request, and the *engine* turns it down. A ground navigator will not search at
        // all while its body is off the ground - stepping down a slope, or mid-jump over a
        // one-block rise - so it answers a perfectly reachable target with no route, having
        // never touched the route the body is already walking.
        val refusal = IntentOutcome.Rejected("the engine offered no route to 1,64,0")
        val walker = FakeWalker(engineAnswer = refusal)

        val outcome = movement.route(walker, origin, at(1.0), tick = 1)

        assertEquals(refusal, outcome)
        assertEquals(1, walker.routesAsked, "Loom granted this one, so the engine must still be asked")
        assertEquals(1, walker.stops, "the engine's own refusal must leave the body standing where it is")
    }

    @Test
    @DisplayName("a walker answering anything but an acceptance drops the route too")
    fun anyNonGrantLeavesTheBodyWhereItIs() {
        val movement = NavigationPolicy()
        // The rule is "not a grant", not "rejected": a walker for a body flavor that cannot
        // walk at all would answer Unsupported, which is no more an authorisation to keep
        // moving than a Rejected is. Pinned because the whole point of putting the stop at
        // this one boundary was that a variant added later needs nobody to remember it.
        val unsupported = IntentOutcome.Unsupported("moveTo")
        val walker = FakeWalker(engineAnswer = unsupported)

        val outcome = movement.route(walker, origin, at(1.0), tick = 1)

        assertEquals(unsupported, outcome)
        assertEquals(1, walker.stops, "an outcome that is not an acceptance must leave the body standing")
    }

    @Test
    @DisplayName("a granted target is searched for without the body being stopped first")
    fun aGrantedTargetIsNotStoppedFirst() {
        val movement = NavigationPolicy()
        val walker = FakeWalker()

        val accepted = movement.route(walker, origin, at(1.0), tick = 1)

        // The navigator's own move control owns the body's position while a route runs, so
        // stopping a body that is about to be given one would fight it for no reason.
        assertEquals(IntentOutcome.Accepted("walking to 1.0,64.0,0.0"), accepted)
        assertEquals(1, walker.routesAsked)
        assertEquals(0, walker.stops)
    }

    @Test
    @DisplayName("a refusal is reported with the reason Loom refused it for")
    fun aRefusalCarriesItsReason() {
        val movement = NavigationPolicy()
        val refused = movement.route(FakeWalker(), origin, at(100.0), tick = 1)
        val reason = describe(PathRequest.OutOfRange(100.0, PATH_REQUEST_REACH_BLOCKS))

        assertEquals(IntentOutcome.Rejected(reason), refused)
    }

    // --- how far a route fell short -------------------------------------------------

    @Test
    @DisplayName("the end of a route is measured against the block the mind named")
    fun theShortfallIsMeasuredAgainstTheNamedBlock() {
        // The engine moves a target vertically before it searches - a solid block becomes
        // the first non-solid block above it - and then reports having reached the block it
        // moved to. The record is measured with this instead, so a body sent three blocks
        // into the ground reads as ending three blocks away rather than as arriving.
        val named = at(x = 114.0, y = -62.0, z = 100.0)
        val routeEnd = at(x = 114.0, y = -59.0, z = 100.0)

        assertEquals(3.0, blocksApart(routeEnd, named))
        assertEquals(0.0, blocksApart(named, named))
    }

    @Test
    @DisplayName("the shortfall counts every axis, and is the same measure as the reach")
    fun theShortfallCountsEveryAxis() {
        // One function serves the reach and the record on purpose: what an outcome says a
        // body fell short by has to be comparable with the bound that let it be asked.
        assertEquals(5.0, blocksApart(origin, at(x = 3.0, y = 64.0 + 4.0)))
        assertEquals(blocksApart(origin, at(x = 7.0)), blocksApart(at(x = 7.0), origin))
    }

    @Test
    @DisplayName("a route ending in the block that was named falls short by nothing")
    fun aRouteEndingInTheNamedBlockFallsShortByNothing() {
        // The shortfall is block against block, because both facts in it are blocks: a
        // route's end is a node, and what Loom asked for is whatever `blockAt` made of the
        // target. Measured instead against the *measured position* a retreat was chosen at -
        // the bottom centre of the very block it was routed to, half a block off on x and on
        // z - an arrival reported `0.71`, which an operator is told to read as the engine
        // having moved the target vertically.
        val named = blockAt(at(x = -8.5, y = -60.0, z = 8.5))

        assertEquals(0.0, blocksApart(named, BodyBlock(-9L, -60L, 8L)))
        // The reading the wording exists for still survives: the engine lifts a target out
        // of solid ground and reports having reached the block it lifted to.
        assertEquals(3.0, blocksApart(named, BodyBlock(-9L, -57L, 8L)))
        assertEquals(blocksApart(named, BodyBlock(-9L, -57L, 8L)), blocksApart(BodyBlock(-9L, -57L, 8L), named))
    }

    // --- which block a place is in --------------------------------------------------

    @Test
    @DisplayName("a block is floored, so a negative target is not pulled back toward the origin")
    fun aBlockIsFlooredRatherThanTruncated() {
        // Truncating toward zero is floor only for a non-negative coordinate. The retreat
        // search answers with the bottom centre of a block it scored, so x and z carry half a
        // block, and truncation named the block one step nearer the origin: in the
        // negative-x arena an in-world death was measured in, that is one step back toward
        // the threat, giving away most of the MEANINGFUL_SEPARATION_BLOCKS gain the choice
        // had just required. Nothing errors and no record contradicts it, which is why the
        // rounding is a rule with a test rather than a conversion at a call site.
        assertEquals(BodyBlock(-9L, -60L, 8L), blockAt(at(x = -8.5, y = -60.0, z = 8.5)))
        assertEquals(BodyBlock(8L, -60L, -9L), blockAt(at(x = 8.5, y = -60.0, z = -8.5)))
        // Every axis, and the fractional part is dropped rather than rounded to nearest.
        assertEquals(BodyBlock(-1L, -1L, -1L), blockAt(at(x = -0.01, y = -0.01, z = -0.01)))
        assertEquals(BodyBlock(0L, 0L, 0L), blockAt(at(x = 0.99, y = 0.99, z = 0.99)))
    }

    @Test
    @DisplayName("a whole-number target names the block it always did")
    fun aWholeTargetNamesTheSameBlock() {
        // The walk path is untouched by the rule above: the contract's moveTo carries three
        // whole numbers, so floor and truncate agree on every target a mind can name.
        assertEquals(BodyBlock(-4L, 64L, -7L), blockAt(at(x = -4.0, y = 64.0, z = -7.0)))
        assertEquals(BodyBlock(4L, 64L, 7L), blockAt(at(x = 4.0, y = 64.0, z = 7.0)))
        assertEquals(BodyBlock(0L, 64L, 0L), blockAt(origin))
    }

    // --- what a refusal says --------------------------------------------------------

    @Test
    @DisplayName("every verdict says something different, and the refusals name their numbers")
    fun everyVerdictSaysSomethingDifferent() {
        val granted = describe(PathRequest.Granted)
        val tooFar = describe(PathRequest.OutOfRange(13.86, 8.0))
        val overBudget = describe(PathRequest.OverBudget(4))

        // A refusal reaches the research record as the reason an intent was rejected, so
        // it has to be readable on its own line: which bound, and what the reading was.
        assertTrue(tooFar.contains("13.9"), tooFar)
        assertTrue(tooFar.contains("8"), tooFar)
        assertTrue(overBudget.contains("4"), overBudget)
        assertNotEquals(granted, tooFar)
        assertNotEquals(tooFar, overBudget)
        assertTrue(granted.isNotBlank())
    }

    // --- observed movement ----------------------------------------------------------

    @Test
    @DisplayName("a body that has not moved has not travelled")
    fun aStillBodyHasNotTravelled() {
        assertFalse(hasTravelled(origin, origin))
    }

    @Test
    @DisplayName("float noise below the threshold is not movement")
    fun floatNoiseIsNotMovement() {
        val jitter = BodyLocation("arena", 0.001, 64.0009, -0.002)

        assertFalse(hasTravelled(origin, jitter))
    }

    @Test
    @DisplayName("a step of the threshold on any one axis is movement")
    fun aStepOnAnyAxisIsMovement() {
        assertTrue(hasTravelled(origin, at(x = NOTICEABLE_MOVE_BLOCKS)))
        // Height matters now that a route can climb: a body stepping up onto a block has
        // gone somewhere even if its footprint on the ground has not changed.
        assertTrue(hasTravelled(origin, at(x = 0.0, y = 64.0 + NOTICEABLE_MOVE_BLOCKS)))
        assertTrue(hasTravelled(origin, at(x = 0.0, z = NOTICEABLE_MOVE_BLOCKS)))
    }

    @Test
    @DisplayName("a change of world is movement whatever the coordinates say")
    fun aChangeOfWorldIsMovement() {
        assertTrue(hasTravelled(origin, at(x = 0.0, world = "nether")))
    }
}
