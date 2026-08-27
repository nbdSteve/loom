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
        val refs = EntityRefs(requestsRemembered = 1)
        val quiet = refs.issue(bo, "req-bo", listOf(UUID.randomUUID())).single()
        refs.issue(ada, "req-1", listOf(UUID.randomUUID()))
        refs.issue(ada, "req-2", listOf(UUID.randomUUID()))

        assertEquals(RefResolution.Bound(quiet.subject), refs.resolve(bo, "req-bo", quiet.handle))
    }

    @Test
    fun anAgentHoldsOnlyTheRequestsItIsAllowedTo() {
        // The bound is replacement, not growth: the oldest request goes when a new one
        // arrives, and its handles stop resolving from then on.
        val refs = EntityRefs(requestsRemembered = 2)
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
        val refs = EntityRefs(requestsRemembered = 1)
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
        val refs = EntityRefs(requestsRemembered = 2)
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
        // Derived rather than chosen: a lower bound here would send a living thing the
        // mind can see and cannot name, which on the wire looks exactly like a block.
        assertEquals(PERCEPTION_MAX_OBSERVATIONS, MAX_REFS_PER_REQUEST)
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
        assertFailsWith<IllegalArgumentException> { EntityRefs(requestsRemembered = 0) }
    }

    @Test
    fun anEmptySceneIssuesNothingAndIsStillTheAgentsNewestRequest() {
        // A scene with nothing alive in it is ordinary, not an error - and it still
        // supersedes what came before, because that is the perception being answered.
        val refs = EntityRefs(requestsRemembered = 1)
        val before = refs.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val issued = refs.issue(ada, "req-2", emptyList())

        assertTrue(issued.isEmpty())
        assertEquals(RefResolution.Stale, refs.resolve(ada, "req-1", before))
        assertEquals(RefResolution.Unknown, refs.resolve(ada, "req-2", "e0"))
    }
}
