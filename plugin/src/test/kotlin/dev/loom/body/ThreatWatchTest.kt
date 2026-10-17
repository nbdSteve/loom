package dev.loom.body

import dev.loom.contract.Bearing
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The threat rule's bounds, pinned.
 *
 * These are tests rather than comments for two different reasons, and both are expensive to
 * get wrong. The debounce and the change tests are what keep an event-driven trigger from
 * costing more than the cadence it beats, so a silently loosened one arrives as a bill
 * rather than as a failure; the nightly ceiling is what keeps one bad night from becoming
 * that bill outright, and it is per agent, so a counter accidentally shared would starve
 * exactly the agent the trigger exists for.
 *
 * No server, no engine and no wall clock: the watch takes both clock readings and the
 * reachability answer from its caller, which is the whole reason every rule here is
 * provable without a run. The clock is moved by hand, so a day boundary is a boundary
 * rather than a wait.
 */
class ThreatWatchTest {
    private val alpha = AgentId("agent-alpha")
    private val beta = AgentId("agent-beta")
    private val clock = FakeThreatClock()
    private val watch = ThreatWatch(clock)

    /** Searches asked for, so "a look is only paid for when it has to be" is an observation. */
    private var probes = 0

    private fun aRoute(): (ThreatCandidate) -> Boolean = probing(true)

    private fun noRoute(): (ThreatCandidate) -> Boolean = probing(false)

    private fun probing(answer: Boolean): (ThreatCandidate) -> Boolean =
        {
            probes++
            answer
        }

    @Test
    fun `the first sight of a hostile is reported at once, as the body saw it`() {
        val outcome = watch.consider(alpha, listOf(zombie("e1", distance = 3.0)), aRoute())

        val sighted = assertNotNull(outcome.sighted, "a hostile in view must not wait for a cadence")
        assertEquals("e1", sighted.ref, "the handle this perception minted is the only name a flight can use")
        assertEquals("entity.minecraft.zombie", sighted.threat)
        assertEquals(3.0, sighted.distance)
        assertTrue(sighted.visible)
        assertEquals(true, sighted.reachable, "the engine's own answer is carried across, not re-derived")
        assertEquals(emptyList(), outcome.suppressed)
    }

    @Test
    fun `a hostile out of sight and far off raises no sighting, and one in view or near does`() {
        // r17: nine flights ran from mobs behind walls 21.9 to 23.5 blocks away and gained under six.
        val far = watch.consider(alpha, listOf(zombie("e3", distance = 22.4, visible = false)), aRoute())
        assertNull(far.sighted)
        assertEquals(listOf(ThreatSuppressionReason.DISTANT_UNSEEN), far.suppressed.map { it.reason })
        assertEquals(EventTag.THREAT_DISTANT_UNSEEN, ThreatSuppressionReason.DISTANT_UNSEEN.tag)
        assertNotNull(watch.consider(beta, listOf(zombie("e4", distance = 22.4, visible = true)), aRoute()).sighted)
        val near = watch.consider(alpha, listOf(zombie("e5", distance = THREAT_NEAR_BLOCKS, visible = false)), aRoute())
        assertNotNull(near.sighted)
    }

    @Test
    fun `a hostile standing still is not reported again inside the cadence`() {
        watch.consider(alpha, listOf(zombie("e1")), aRoute())

        clock.now += THREAT_DEBOUNCE_TICKS - 1
        val again = watch.consider(alpha, listOf(zombie("e1")), aRoute())

        assertNull(again.sighted, "twenty model calls a second is the failure the debounce exists to stop")
        assertEquals(
            listOf(ThreatSuppression(ZOMBIE, "e1", ThreatSuppressionReason.DEBOUNCED)),
            again.suppressed,
            "and a bound that refused is evidence rather than silence",
        )
    }

    @Test
    fun `a debounced hostile costs no path search at all`() {
        watch.consider(alpha, listOf(zombie("e1")), aRoute())
        assertEquals(1, probes, "the first sight is worth the search")

        repeat(3) { watch.consider(alpha, listOf(zombie("e1")), aRoute()) }

        assertEquals(
            1,
            probes,
            "a threat in view for a whole cadence costs one search and not one per look: the free " +
                "tests run first precisely so vigilance cannot drain the tick's own path budget",
        )
    }

    @Test
    fun `a hostile that steps out from behind a wall is news inside the cadence`() {
        watch.consider(alpha, listOf(zombie("e1", visible = false)), aRoute())

        clock.now += 1
        val stepped = watch.consider(alpha, listOf(zombie("e1", visible = true)), aRoute())

        assertNotNull(stepped.sighted, "being seen is a different situation from being known about")
    }

    @Test
    fun `a hostile crossing inside the pivotal distance is news inside the cadence`() {
        watch.consider(alpha, listOf(zombie("e1", distance = FAR_OFF_BLOCKS)), aRoute())

        clock.now += 1
        val closed = watch.consider(alpha, listOf(zombie("e1", distance = 4.0)), aRoute())

        assertNotNull(closed.sighted, "arrival is the moment, and it happens well inside one window")
    }

    @Test
    fun `the cadence elapsing is not itself news`() {
        watch.consider(alpha, listOf(zombie("e1")), aRoute())

        clock.now += THREAT_DEBOUNCE_TICKS
        val same = watch.consider(alpha, listOf(zombie("e1")), aRoute())

        assertNull(same.sighted)
        assertEquals(
            listOf(ThreatSuppression(ZOMBIE, "e1", ThreatSuppressionReason.UNCHANGED)),
            same.suppressed,
            "the clock allowed the look and the look found nothing the mind had not been told - " +
                "a different fact from the clock refusing, and counted apart from it",
        )
    }

    @Test
    fun `every suppression names the tag the append-only log counts by`() {
        assertEquals(EventTag.THREAT_DEBOUNCED, ThreatSuppressionReason.DEBOUNCED.tag)
        assertEquals(EventTag.THREAT_UNCHANGED, ThreatSuppressionReason.UNCHANGED.tag)
        assertEquals(EventTag.THREAT_OVER_NIGHTLY_CAP, ThreatSuppressionReason.OVER_NIGHTLY_CAP.tag)
        // Three distinct facts about why an agent was not asked. Sharing one tag they would
        // be a single uncountable reason, and a run could not say whether it was its bounds
        // or its budget that kept an agent from reacting to something hunting it.
        assertEquals(
            ThreatSuppressionReason.entries.size,
            ThreatSuppressionReason.entries.map { it.tag }.toSet().size,
        )
    }

    @Test
    fun `an agent gets exactly eight pivotal sightings a night, and the ninth is refused`() {
        assertEquals(
            8,
            MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT,
            "this number is the run's inference bill per agent per night; changing it is a decision",
        )
        assertEquals(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT, watch.pivotalCallsRemaining(alpha))

        // A different zombie each time, which is the scene the ceiling exists for: every one
        // is a first sight, so the debounce refuses none of them and only the ceiling can.
        repeat(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT) { met ->
            val outcome = watch.consider(alpha, listOf(zombie("e$met")), aRoute())
            assertNotNull(outcome.sighted, "sighting ${met + 1} is inside the night's allowance")
        }

        assertEquals(0, watch.pivotalCallsRemaining(alpha))
        val ninth = watch.consider(alpha, listOf(zombie("e-ninth")), aRoute())
        assertNull(ninth.sighted, "the ninth is refused outright rather than answered by a cheaper model")
        assertEquals(
            listOf(ThreatSuppression(ZOMBIE, "e-ninth", ThreatSuppressionReason.OVER_NIGHTLY_CAP)),
            ninth.suppressed,
            "and the record says Loom stopped paying, not that the agent decided to stand there",
        )
    }

    @Test
    fun `one agent spending its night leaves every other agent's untouched`() {
        spendTheNight(alpha, handles = "a")

        assertEquals(0, watch.pivotalCallsRemaining(alpha))
        assertEquals(
            MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT,
            watch.pivotalCallsRemaining(beta),
            "a model call is a cost to the run, and beta has done nothing to earn a refusal",
        )
        assertNotNull(
            watch.consider(beta, listOf(zombie("e-beta")), aRoute()).sighted,
            "under one shared ceiling this is the agent that dies unasked, in exactly the scene " +
                "the trigger was added for",
        )
    }

    @Test
    fun `the day turning over restores every agent's allowance`() {
        spendTheNight(alpha, handles = "a")
        spendTheNight(beta, handles = "b")

        clock.day += 1

        assertEquals(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT, watch.pivotalCallsRemaining(alpha))
        assertEquals(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT, watch.pivotalCallsRemaining(beta))
        assertNotNull(watch.consider(alpha, listOf(zombie("e-dawn")), aRoute()).sighted)
    }

    @Test
    fun `the reset is a day boundary and not an elapsed interval`() {
        spendTheNight(alpha, handles = "a")

        // A whole day of ticks with the day ordinal held still: an agent that met its first
        // zombie at dusk must not come into a second allowance before its neighbour does.
        clock.now += TICKS_IN_A_WORLD_DAY

        assertEquals(0, watch.pivotalCallsRemaining(alpha))
        assertNull(watch.consider(alpha, listOf(zombie("e-late")), aRoute()).sighted)
    }

    @Test
    fun `a hostile with no route to it still spends the night's allowance`() {
        // The engine refuses a route whenever the body is off the ground, and being hit is
        // one such moment - so a ceiling that only counted reachable threats would refuse
        // the agent exactly while it was being killed. `dev.loom.mind`'s `importanceOf`
        // leaves `reachable` out of its conjunction for the same reason, and the two have to
        // agree or the budget stops counting the calls it pays for.
        spendTheNight(alpha, handles = "a", reach = noRoute())

        assertEquals(0, watch.pivotalCallsRemaining(alpha))
    }

    @Test
    fun `a hostile too far off to act over never touches the nightly allowance`() {
        repeat(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT * 2) { seen ->
            val outcome = watch.consider(alpha, listOf(zombie("e$seen", distance = FAR_OFF_BLOCKS)), aRoute())
            assertNotNull(outcome.sighted, "news is still worth telling")
        }

        assertEquals(
            MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT,
            watch.pivotalCallsRemaining(alpha),
            "the ceiling is on the frontier tier, and a threat across the whole view is not it",
        )
    }

    @Test
    fun `a body put away loses the scene it stood in but not what it spent`() {
        watch.consider(alpha, listOf(zombie("e1")), aRoute())
        assertEquals(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT - 1, watch.pivotalCallsRemaining(alpha))

        watch.forget(alpha)

        assertEquals(
            MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT - 1,
            watch.pivotalCallsRemaining(alpha),
            "tonight's spend is a fact about the night: a body put away and spawned again must " +
                "not come back with a fresh allowance",
        )
        assertNotNull(
            watch.consider(alpha, listOf(zombie("e1")), aRoute()).sighted,
            "what it had been told, though, went with the body",
        )
    }

    @Test
    fun `a hostile that leaves view and returns is a first sight again`() {
        watch.consider(alpha, listOf(zombie("e1")), aRoute())

        clock.now += 1
        watch.consider(alpha, emptyList(), aRoute())
        val returned = watch.consider(alpha, listOf(zombie("e1")), aRoute())

        assertNotNull(returned.sighted, "it re-entered the scene, so this is not something the mind was told")
    }

    @Test
    fun `something that does not hunt an agent is neither reported nor recorded`() {
        val cow = zombie("e1").copy(what = "entity.minecraft.cow")

        val outcome = watch.consider(alpha, listOf(cow), aRoute())

        assertNull(outcome.sighted)
        assertEquals(
            emptyList(),
            outcome.suppressed,
            "a suppression is a threat Loom saw and did not report; a cow was never one",
        )
        assertEquals(0, probes, "and nothing that is not hunting an agent is worth a path search")
    }

    @Test
    fun `the nearest hostile is the one reported, and the rest are not examined`() {
        val outcome =
            watch.consider(
                alpha,
                listOf(zombie("e-near", distance = 2.0), zombie("e-far", distance = 6.0)),
                aRoute(),
            )

        assertEquals("e-near", assertNotNull(outcome.sighted).ref, "a flight is from one creature")
        assertEquals(
            emptyList(),
            outcome.suppressed,
            "the further one was not refused: the walk stopped, so nothing was decided about it",
        )
        assertEquals(1, probes, "and only the candidate that earned the sighting cost a search")
    }

    /**
     * Spends the whole of [agent]'s night, on eight separate first sights.
     *
     * [handles] is the one-letter stem the refs are built from, kept short because a ref is
     * bounded to eight code points on the wire and a `Trigger.ThreatSighted` refuses a longer
     * one at construction - which is the bound doing its job, and worth stating here rather
     * than working around silently.
     */
    private fun spendTheNight(
        agent: AgentId,
        handles: String,
        reach: (ThreatCandidate) -> Boolean = aRoute(),
    ) {
        repeat(MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT) { met ->
            watch.consider(agent, listOf(zombie("$handles$met")), reach)
        }
    }
}

/** The tick and the day, both moved by hand. */
private class FakeThreatClock(
    var now: Long = 0L,
    var day: Long = 0L,
) : ThreatClock {
    override fun tick(): Long = now

    override fun dayOrdinal(): Long = day
}

/** The engine's own description of the hunter every case here uses. */
private const val ZOMBIE = "entity.minecraft.zombie"

/** Comfortably outside [PIVOTAL_THREAT_DISTANCE_BLOCKS], and well inside what a body sees. */
private const val FAR_OFF_BLOCKS: Double = 20.0

/** A Minecraft day, used only to show that ticks alone restore no allowance. */
private const val TICKS_IN_A_WORLD_DAY: Long = 24_000L

/**
 * One hostile, named deterministically so the same scene always replays the same.
 *
 * [UUID.nameUUIDFromBytes] rather than a random one because two threats are told apart by
 * that field alone: generated randomly, a failure here would not be reproducible from the
 * source it failed in.
 */
private fun zombie(
    ref: String,
    distance: Double = 2.0,
    visible: Boolean = true,
): ThreatCandidate =
    ThreatCandidate(
        subject = UUID.nameUUIDFromBytes(ref.toByteArray()),
        what = ZOMBIE,
        ref = ref,
        distance = distance,
        direction = Bearing.NORTH,
        visible = visible,
    )
