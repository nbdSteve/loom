package dev.loom.body

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The lifetime of a request-scoped handle.
 *
 * Every test here is about a way a handle could resolve when it should not have. A
 * handle that resolved across requests, across agents, or after eviction would let a
 * decision act on a creature the mind never named, and the world would look as though
 * the agent had simply chosen wrongly - so these are pinned as behaviour rather than
 * left to the adapter that will consume them.
 */
class EntityRefsTest {
    private val ada = AgentId("ada")
    private val bo = AgentId("bo")

    @Test
    fun aHandleMintedForAPerceptionNobodyWasShownCostsTheWindowNothing() {
        // A threat scan reads the world once a second and mints a handle per living thing so it
        // can say which hostile it found; almost all of those sightings are suppressed and reach
        // no mind at all. A window held open for one of those answers nothing, and counts
        // against the ceiling on the requests that can still be answered.
        val refs = EntityRefs(requestsInFlight = 2)
        val review = refs.issue(ada, "req-review", listOf(UUID.randomUUID())).single()

        val scans = (1..5).map { refs.mint(listOf(UUID.randomUUID())).single() }

        assertEquals(
            RefResolution.Bound(review.subject),
            refs.resolve(ada, "req-review", review.handle),
            "a scan nobody was shown must not be able to evict a request a mind is answering",
        )
        for (scan in scans) {
            assertEquals(
                RefResolution.Unknown,
                refs.resolve(ada, "req-review", scan.handle),
                "and a minted handle names nothing until the perception carrying it is handed out",
            )
        }
    }

    @Test
    fun rememberingAMintedHandleIsWhatMakesItAnswerable() {
        // The other half: a scan that *does* earn its sighting hands the perception out, and
        // from then on the handle it carried resolves exactly as an issued one does.
        val refs = EntityRefs()
        val sighting = refs.mint(listOf(UUID.randomUUID())).single()

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-sighting", sighting.handle))

        refs.remember(ada, "req-sighting", listOf(sighting), atTick = 0)

        assertEquals(
            RefResolution.Bound(sighting.subject),
            refs.resolve(ada, "req-sighting", sighting.handle),
        )
    }

    @Test
    fun aGoalReviewOutlastsEveryEarnedSightingWhileItsDecisionIsInFlight() {
        // The scene the eat path exists for: hungry *and* hunted. An earned sighting is a
        // perception a mind really was shown, so it does open a window of its own - and while
        // the window was measured in requests, two of them inside one round trip took the goal
        // review's own handles away. The meal it named then arrived as SlotForgotten and the
        // flight as ThreatForgotten, with the agent starving holding food and nothing in either
        // refusal saying the handle had been valid when the mind chose it.
        val refs = EntityRefs()
        val review = refs.issue(ada, "req-review", listOf(UUID.randomUUID()), atTick = 0).single()

        // A sighting on every scan, not every debounce window: a hostile stepping in and out of
        // line of sight is exempt from the debounce, so the scan cadence is the real worst case
        // and it is five times the one the ceiling used to be justified by. Driven at the
        // shipped bounds rather than a test's own, because the failure was that the two shipped
        // numbers happened to leave a margin of two requests and nothing checked it.
        var at = FASTEST_PERCEPTION_CADENCE_TICKS
        var sightings = 0
        while (at < REQUEST_REFS_LIFETIME_TICKS) {
            refs.issue(ada, "req-sighting-$at", listOf(UUID.randomUUID()), atTick = at)
            sightings += 1
            at += FASTEST_PERCEPTION_CADENCE_TICKS
        }
        assertTrue(
            sightings > 1,
            "the shipped cadence must earn more than one sighting inside one window, got $sightings",
        )

        assertEquals(
            RefResolution.Bound(review.subject),
            refs.resolve(ada, "req-review", review.handle, atTick = REQUEST_REFS_LIFETIME_TICKS - 1),
            "a decision Loom is still waiting for must be answerable however busy the body was",
        )
    }

    @Test
    fun aRequestStaysAnswerableForItsWholeLifetimeAndNoLonger() {
        // The bound is time, so it is the same for a busy agent and a quiet one.
        val refs = EntityRefs(lifetimeTicks = 800)
        val issued = refs.issue(ada, "req-1", listOf(UUID.randomUUID()), atTick = 10).single()

        assertEquals(
            RefResolution.Bound(issued.subject),
            refs.resolve(ada, "req-1", issued.handle, atTick = 809),
        )
        assertEquals(
            RefResolution.Stale,
            refs.resolve(ada, "req-1", issued.handle, atTick = 810),
            "a handle whose window has closed is refused whether or not anything else happened",
        )
    }

    @Test
    fun aRequestWhoseDecisionHasBeenDealtWithIsAnsweredNoFurther() {
        // One perception gets one decision, so holding its handles past that would only widen
        // the window in which a repeat could act on a creature that has since moved.
        val refs = EntityRefs()
        val issued = refs.issue(ada, "req-1", listOf(UUID.randomUUID()), atTick = 0).single()
        val other = refs.issue(ada, "req-2", listOf(UUID.randomUUID()), atTick = 0).single()

        refs.settle(ada, "req-1")

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", issued.handle))
        assertEquals(
            RefResolution.Bound(other.subject),
            refs.resolve(ada, "req-2", other.handle),
            "settling one request must not touch another that is still waiting for its answer",
        )
    }

    @Test
    fun twoCreaturesAlikeInEveryOtherWayGetDifferentHandles() {
        // The case a name cannot express: same distance, same bearing, both a zombie.
        // If these shared a handle the body would have to guess which one to run from.
        val refs = EntityRefs()
        val issued = refs.issue(ada, "req-1", listOf(UUID.randomUUID(), UUID.randomUUID()))

        assertEquals(2, issued.size)
        assertNotEquals(issued[0].handle, issued[1].handle)
        assertEquals(
            RefResolution.Bound(issued[0].subject),
            refs.resolve(ada, "req-1", issued[0].handle),
        )
        assertEquals(
            RefResolution.Bound(issued[1].subject),
            refs.resolve(ada, "req-1", issued[1].handle),
        )
    }

    @Test
    fun aHandleFitsTheWidthTheContractAllowsIt() {
        val refs = EntityRefs()
        val handle = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle

        assertTrue(handle.isNotEmpty(), "a handle must not be blank")
        assertTrue(handle.length <= 8, "the contract caps a handle at 8 characters, got '$handle'")
    }

    @Test
    fun handlesAreNotReusedFromOneRequestToTheNext() {
        // The mind is told never to carry a handle over. This is what makes breaking
        // that rule fail loudly rather than resolve to whatever stands there now.
        val refs = EntityRefs()
        val first = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val second = refs.issue(ada, "req-2", listOf(UUID.randomUUID())).single().handle

        assertNotEquals(first, second)
        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-2", first))
    }

    @Test
    fun aHandleResolvesOnlyForTheRequestThatIssuedIt() {
        val refs = EntityRefs()
        val handle = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        refs.issue(ada, "req-2", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-2", handle))
    }

    @Test
    fun aHandleResolvesOnlyForTheAgentItWasMintedFor() {
        // Both agents perceived in the same tick and hold the same request id. Nothing
        // but the agent distinguishes the two sets of handles.
        val refs = EntityRefs()
        val adasZombie = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single()
        refs.issue(bo, "req-1", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Unknown, refs.resolve(bo, "req-1", adasZombie.handle))
        assertEquals(
            RefResolution.Bound(adasZombie.subject),
            refs.resolve(ada, "req-1", adasZombie.handle),
        )
    }

    @Test
    fun oneAgentsTrafficDoesNotEvictAnothers() {
        // A quiet agent's decision must not be refused because a busy one thought twice.
        val refs = EntityRefs(requestsInFlight = 1)
        val quiet = refs.issue(bo, "req-bo", listOf(UUID.randomUUID())).single()
        refs.issue(ada, "req-1", listOf(UUID.randomUUID()))
        refs.issue(ada, "req-2", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Bound(quiet.subject), refs.resolve(bo, "req-bo", quiet.handle))
    }

    @Test
    fun anAgentHoldsOnlyTheRequestsItIsAllowedTo() {
        // The ceiling is a safety valve rather than the ordinary rule - what usually closes a
        // window is the decision arriving, or the lifetime running out - but it has to bound
        // the store all the same: an agent whose decisions never came back would otherwise
        // hold a window per perception for the rest of the run. The oldest goes first.
        val refs = EntityRefs(requestsInFlight = 2)
        val oldest = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val middle = refs.issue(ada, "req-2", listOf(UUID.randomUUID())).single()
        val newest = refs.issue(ada, "req-3", listOf(UUID.randomUUID())).single()

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", oldest))
        assertEquals(RefResolution.Bound(middle.subject), refs.resolve(ada, "req-2", middle.handle))
        assertEquals(RefResolution.Bound(newest.subject), refs.resolve(ada, "req-3", newest.handle))
    }

    @Test
    fun anEvictedRequestIsStaleAndAnInventedHandleIsUnknown() {
        // The distinction the resolution type exists for: one is a decision that arrived
        // late, the other a mind that named something it was never given.
        val refs = EntityRefs(requestsInFlight = 1)
        val evicted = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        refs.issue(ada, "req-2", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", evicted))
        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-2", "nope"))
    }

    @Test
    fun anAgentThatHasNeverPerceivedResolvesNothing() {
        val refs = EntityRefs()

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", "e0"))
    }

    @Test
    fun reIssuingForOneRequestReplacesItsHandlesRatherThanAddingToThem() {
        // A re-taken perception under the same request id must not leave the handles of
        // the one it replaced resolvable, or a decision could act on the older scene.
        val refs = EntityRefs()
        val first = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val second = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single()

        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-1", first))
        assertEquals(RefResolution.Bound(second.subject), refs.resolve(ada, "req-1", second.handle))
    }

    @Test
    fun reIssuingForOneRequestCountsItAsTheNewestAgain() {
        // Otherwise a request re-taken repeatedly would evict itself.
        val refs = EntityRefs(requestsInFlight = 2)
        refs.issue(ada, "req-1", listOf(UUID.randomUUID()))
        val kept = refs.issue(ada, "req-2", listOf(UUID.randomUUID())).single()
        val reIssued = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single()
        refs.issue(ada, "req-3", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-2", kept.handle))
        assertEquals(RefResolution.Bound(reIssued.subject), refs.resolve(ada, "req-1", reIssued.handle))
    }

    @Test
    fun noMoreHandlesAreIssuedThanOneRequestMayCarry() {
        val refs = EntityRefs(maxRefsPerRequest = 2)
        val subjects = List(5) { UUID.randomUUID() }
        val issued = refs.issue(ada, "req-1", subjects)

        assertEquals(2, issued.size)
        assertEquals(subjects.take(2), issued.map { it.subject })
    }

    @Test
    fun theRefBoundIsTheSameBoundAPerceptionCountsTo() {
        // One namespace can fill its whole per-kind body list. The three namespaces
        // together are what reach the larger combined perception bound.
        assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, MAX_REFS_PER_REQUEST)
        assertEquals(PERCEPTION_MAX_OBSERVATIONS, MAX_REFS_PER_REQUEST * 3)
    }

    @Test
    fun anAgentThatHasEndedIsForgotten() {
        val refs = EntityRefs()
        val handle = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        refs.forget(ada)

        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", handle))
    }

    @Test
    fun forgettingOneAgentLeavesAnothersHandlesAlone() {
        val refs = EntityRefs()
        val kept = refs.issue(bo, "req-1", listOf(UUID.randomUUID())).single()
        refs.issue(ada, "req-1", listOf(UUID.randomUUID()))
        refs.forget(ada)

        assertEquals(RefResolution.Bound(kept.subject), refs.resolve(bo, "req-1", kept.handle))
    }

    @Test
    fun aBlankRequestIdIsRefused() {
        // A blank request id would put every such perception's handles in one bucket.
        val refs = EntityRefs()

        assertFailsWith<IllegalArgumentException> { refs.issue(ada, " ", listOf(UUID.randomUUID())) }
    }

    @Test
    fun aBoundThatWouldIssueNothingIsRefused() {
        assertFailsWith<IllegalArgumentException> { EntityRefs(maxRefsPerRequest = 0) }
        assertFailsWith<IllegalArgumentException> { EntityRefs(requestsInFlight = 0) }
        // A lifetime of nothing would refuse every handle the moment it was issued, so every
        // decision would come back naming something the body would not resolve.
        assertFailsWith<IllegalArgumentException> { EntityRefs(lifetimeTicks = 0) }
    }

    @Test
    fun anEmptySceneIssuesNothingAndStillCountsAsARequestInFlight() {
        // A scene with nothing alive in it is ordinary, not an error - and it is still a
        // perception awaiting a decision, so it takes its place against the ceiling.
        val refs = EntityRefs(requestsInFlight = 1)
        val before = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val issued = refs.issue(ada, "req-2", emptyList())

        assertTrue(issued.isEmpty())
        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", before))
        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-2", "e0"))
    }
}
