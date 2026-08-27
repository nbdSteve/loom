package dev.loom.body

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The flight policy, without a world.
 *
 * The engine half of running away is a few calls and needs a live `Level`, so what is
 * pinned here is everything that decides *whether* a retreat is even askable: which of the
 * offered spots is worth walking to, the search box against the reach that will judge it,
 * the mapping from a lost handle to a stated refusal, and the speed that decides whether
 * the gap actually opens.
 *
 * The choice tests are measured in the coordinates of a real death. An in-world run put a
 * body at -4.5,-60,5.5 and a zombie seven blocks north of it; the vanilla search offered
 * one spot 0.71 blocks away, the flee was accepted, and the agent was killed inside twelve
 * seconds. That scene is the fixture, so the regression is pinned as the thing it was.
 */
class RetreatTest {
    /** Somewhere in the arena the whole of this file measures in. */
    private fun at(
        x: Double,
        y: Double,
        z: Double,
    ) = BodyLocation("arena", x, y, z)

    /** Where the in-world death happened: the body, and a zombie 7 blocks north of it. */
    private val body = at(-4.5, -60.0, 5.5)
    private val zombie = at(-4.5, -60.0, -1.5)

    @Test
    fun `the retreat that killed an agent in world is refused here`() {
        // The exact live regression. The vanilla search's one answer was the body's own
        // doorstep: a route two nodes long, finished in a tick, followed by five seconds
        // of standing still while a zombie closed from seven blocks. It is not a retreat.
        val doorstep = at(-4.5, -60.0, 6.5)
        assertTrue(blocksApart(body, doorstep) < MEANINGFUL_RETREAT_BLOCKS, "this is the offer that was taken")

        val choice = chooseRetreat(body, zombie, listOf(doorstep))

        assertEquals(RetreatChoice.Refuse(RetreatRefusal.NoMeaningfulRetreat), choice)
    }

    @Test
    fun `a spot that actually gains ground is taken over one that does not`() {
        val doorstep = at(-4.5, -60.0, 6.5)
        val away = at(-4.5, -60.0, 10.5)

        val choice = chooseRetreat(body, zombie, listOf(doorstep, away))

        assertEquals(RetreatChoice.Take(away), choice, "the offer that opens the gap is the one to walk to")
    }

    @Test
    fun `nothing offered and nothing worth taking are different refusals`() {
        // Two facts about the ground, not one: a body walled in has nowhere to stand, and a
        // body in the open whose offers were all underfoot has plenty. A run that could not
        // tell them apart could not say whether its agents died cornered.
        assertEquals(
            RetreatChoice.Refuse(RetreatRefusal.NoRetreat),
            chooseRetreat(body, zombie, emptyList()),
        )
        assertEquals(
            RetreatChoice.Refuse(RetreatRefusal.NoMeaningfulRetreat),
            chooseRetreat(body, zombie, listOf(at(-4.5, -60.0, 6.5), at(-3.5, -60.0, 6.5))),
        )
    }

    @Test
    fun `a long walk that ends no further from the threat is not a retreat`() {
        // Distance covered is not distance gained. This spot is a full four blocks off and
        // sideways, so it ends about as close to the zombie as the body already was.
        val sideways = at(-0.5, -60.0, 5.5)
        assertTrue(blocksApart(body, sideways) >= MEANINGFUL_RETREAT_BLOCKS, "far enough to walk to")

        assertEquals(
            RetreatChoice.Refuse(RetreatRefusal.NoMeaningfulRetreat),
            chooseRetreat(body, zombie, listOf(sideways)),
        )
    }

    @Test
    fun `the furthest from the threat wins, and ties keep the order the ground offered`() {
        val near = at(-4.5, -60.0, 9.5)
        val far = at(-4.5, -60.0, 11.5)
        assertEquals(RetreatChoice.Take(far), chooseRetreat(body, zombie, listOf(near, far)))
        assertEquals(RetreatChoice.Take(far), chooseRetreat(body, zombie, listOf(far, near)))

        // Deterministic on a tie: the same scene has to yield the same retreat, or a run
        // cannot be replayed. Both of these are exactly 13 blocks from the zombie, and the
        // one offered first is the one taken.
        val straightBack = at(-4.5, -60.0, 11.5)
        val offToTheSide = at(0.5, -60.0, 10.5)
        assertEquals(blocksApart(straightBack, zombie), blocksApart(offToTheSide, zombie), 1e-9)
        assertEquals(RetreatChoice.Take(straightBack), chooseRetreat(body, zombie, listOf(straightBack, offToTheSide)))
        assertEquals(RetreatChoice.Take(offToTheSide), chooseRetreat(body, zombie, listOf(offToTheSide, straightBack)))
    }

    @Test
    fun `the block a chosen retreat is routed to is the block it was scored in`() {
        // The ground offers the bottom centre of a block - x and z carry half a block - and
        // the route is asked for the block that position is in. In this arena x is negative,
        // so a rounding that truncated toward zero named the block one step back toward the
        // zombie and handed back most of the gain this choice had just insisted on.
        val offer = at(-8.5, -60.0, 8.5)
        val gained = blocksApart(offer, zombie) - blocksApart(body, zombie)
        assertTrue(gained >= MEANINGFUL_SEPARATION_BLOCKS, "this offer is taken for the ground it gains")

        assertEquals(RetreatChoice.Take(offer), chooseRetreat(body, zombie, listOf(offer)))
        assertEquals(BodyBlock(-9L, -60L, 8L), blockAt(offer))
    }

    @Test
    fun `a spot past the reach is refused here rather than by the router`() {
        // Nothing the search offers should be out of reach today - the box corner is inside
        // it - but the filter is what keeps that true if either bound moves: proposing a
        // target NavigationPolicy then refuses spends a search token on a body left
        // standing, and the refusal would read as ground rather than as arithmetic.
        val tooFar = at(-4.5, -60.0, 5.5 + PATH_REQUEST_REACH_BLOCKS + 1)

        assertEquals(
            RetreatChoice.Refuse(RetreatRefusal.NoMeaningfulRetreat),
            chooseRetreat(body, zombie, listOf(tooFar)),
        )
    }

    @Test
    fun `the ground is asked more than once and a bounded number of times`() {
        // One answer was a coin flip on the agent's life; an unbounded loop would be a
        // per-tick cost nothing states. The samples cost block probes rather than path
        // searches, so this number does not touch the per-tick search budget.
        assertTrue(RETREAT_SEARCH_ATTEMPTS > 1, "asking once is what the in-world death was")
        assertTrue(RETREAT_SEARCH_ATTEMPTS <= 8, "the sample has to stay a small constant")
    }

    @Test
    fun `a meaningful retreat is one the search box can actually offer`() {
        // A bound nothing can satisfy is a bound that only ever refuses, so both minima
        // have to sit inside what the vanilla search may propose.
        assertTrue(
            MEANINGFUL_RETREAT_BLOCKS < retreatCornerBlocks(),
            "the step has to fit the box the search looks in",
        )
        assertTrue(
            MEANINGFUL_SEPARATION_BLOCKS <= MEANINGFUL_RETREAT_BLOCKS,
            "a partly sideways step is still worth taking, so the gain asks for less than the step",
        )
    }

    @Test
    fun `the furthest spot a retreat can propose is still inside the reach`() {
        // The retreat target is routed to through NavigationPolicy like any other, so a
        // search box whose corner sticks out past the reach would have Loom proposing
        // retreats its own bound then refuses - a body standing still while the record
        // says `rejected` about a target Loom itself chose, and only on the diagonals.
        assertTrue(
            retreatCornerBlocks() <= PATH_REQUEST_REACH_BLOCKS,
            "the retreat search corner (${retreatCornerBlocks()}) must fit the reach " +
                "($PATH_REQUEST_REACH_BLOCKS) or Loom refuses its own retreats",
        )
    }

    @Test
    fun `widening the search without widening the reach is caught here rather than in world`() {
        // The relationship is what matters, not today's numbers: this is the arithmetic a
        // future change to either search bound has to keep true.
        assertTrue(
            retreatCornerBlocks(horizontalBlocks = 8, verticalBlocks = 2) > PATH_REQUEST_REACH_BLOCKS,
            "a search box this wide cannot be routed to, and the test that says so is the point",
        )
    }

    @Test
    fun `a body runs faster than it walks and still short of a sprint`() {
        // What hunts a villager is slower than one, so the modifier is what decides
        // whether the gap opens; fleeing at a stroll would be caught.
        assertTrue(FLEE_SPEED_MODIFIER > WALK_SPEED_MODIFIER, "fleeing must be faster than walking")
        assertTrue(FLEE_SPEED_MODIFIER < 1.0, "fleeing is not a sprint")
    }

    @Test
    fun `a handle that still names a creature is no refusal at all`() {
        assertNull(refusalFor(RefResolution.Bound(UUID.randomUUID())))
    }

    @Test
    fun `a lost handle and a handle that was never issued are different refusals`() {
        // Two different facts about the run: one says Loom's own ref window has passed,
        // the other says the mind named something that was never there. A run that could
        // not tell them apart could not say why its agents failed to flee.
        assertEquals(RetreatRefusal.ThreatForgotten, refusalFor(RefResolution.Stale))
        assertEquals(RetreatRefusal.ThreatUnnamed, refusalFor(RefResolution.Unknown))
    }

    @Test
    fun `every refusal says why in words a record can carry, and names a tag to be counted by`() {
        // Total over the sealed interface by construction: a variant added later has no
        // entry here and `describe` will not compile without one.
        val said = everyRefusal.map(::describe)

        // The sentence is what a person reads and the tag is what a run counts, so a refusal
        // that gained one without the other is a transition nothing can total up.
        assertTrue(
            everyRefusal.map(::tagOf).none { it.isBlank() },
            "a refusal with no tag is one nothing can count",
        )
        assertNotEquals(
            tagOf(RetreatRefusal.ThreatForgotten),
            tagOf(RetreatRefusal.ThreatUnnamed),
            "Loom's ref window passing and a mind naming a handle it was never given are different facts",
        )
        assertEquals(said.size, said.distinct().size, "two refusals must never read the same")
        assertTrue(said.none { it.isBlank() }, "a refusal with nothing to say is a refusal nothing can count")
        assertTrue(
            describe(RetreatRefusal.NoRetreat).contains("$RETREAT_SEARCH_HORIZONTAL_BLOCKS"),
            "the bound that refused has to be in the words, or the record cannot be read against it",
        )
    }

    /** Every reason a body is not sent running, in the order `RetreatRefusal` states them. */
    private val everyRefusal =
        listOf(
            RetreatRefusal.ThreatForgotten,
            RetreatRefusal.ThreatUnnamed,
            RetreatRefusal.ThreatGone,
            RetreatRefusal.NoRetreat,
            RetreatRefusal.NoMeaningfulRetreat,
        )
}
