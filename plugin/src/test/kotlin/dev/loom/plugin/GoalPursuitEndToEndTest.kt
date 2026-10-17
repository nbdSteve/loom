package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.DropFate
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The goal-pursuit paths r17 never exercised, each driven offline end to end through the real JVM
 * ledger, cognition, actuation and fleet to real Node bodies, provider-free and with no goal
 * injected into any live profile: a scripted mind stands in for the model, and the scene is the
 * whole of what it is given.
 *
 * - two obtainable items and a crafting step: planks crafted from held logs, then sticks crafted
 *   from those planks, each goal completed at its original total and the second adopted in the
 *   same answer that completed the first;
 * - an agent with nothing in sight worth doing walks to a cell at the edge of sight to look
 *   further, rather than idling;
 * - a goal at the stall bound with nothing in sight to replace it by is abandoned, and an
 *   achievable one may not be;
 * - a collect without approach of an item a block below the feet is refused by the body, because
 *   vanilla would never pick it up from there;
 * - an own drop still lying past the 4800-tick retention is still told and offered, then told as
 *   taken when a peer takes it, and forgotten a retention after that.
 */
class GoalPursuitEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `planks then sticks are two obtainable items and two crafts, each goal met at its original total`() {
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(Cell(10, 64, 8, "dead_bush")),
                inventory = listOf(SceneStack(36, "oak_log", 2)),
                serveCrafts = true,
            )
        val world = world(scene)
        world.mind.answer = { seen, index ->
            when (index) {
                // Planks from the held logs: two crafts of four, a total of eight from none.
                0 -> craft(seen, "item.minecraft.oak_planks", 2) to target(GoalDisposition.ADOPT, PLANKS, 8)
                // Eight planks held: complete that goal and adopt four sticks, crafted from them.
                1 -> craft(seen, "item.minecraft.stick", 1) to target(GoalDisposition.REPLACE, STICK, 4)
                else -> Intent.Idle to GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)
            }
        }
        for (review in 1..3) {
            world.review(ALPHA)
            world.awaitDecided(review)
            world.awaitApplied(review)
        }
        val first = world.mind.seen[0]
        val obtainable = first.selfState.craftingOptions.map { it.what }.toSet()
        assertTrue(PLANKS in obtainable, "planks are craftable from the held logs: $obtainable")
        assertTrue(first.surroundings.any { it.yields.orEmpty().any { yielded -> yielded.what == STICK } })
        val outcomes = world.records.filter { it.event == BodyEvent.DECIDED }.map { it.goal?.outcome }
        assertEquals(listOf(GoalEvidence.ADOPTED, GoalEvidence.COMPLETED_ADOPTED, GoalEvidence.COMPLETED), outcomes)
        val applied = world.records.filter { it.event == BodyEvent.INTENT_APPLIED }.map { it.outcome }
        assertEquals(listOf(EventTag.ACCEPTED, EventTag.ACCEPTED, EventTag.ACCEPTED), applied)
        val held = world.mind.seen[2].selfState.inventorySummary.associate { it.what to it.count }
        // Four sticks take two of the eight planks.
        assertEquals(6L, held[PLANKS])
        assertEquals(4L, held[STICK])
        // Both goals were met at their original totals, two or more above what was held.
        val pursuit =
            world
                .readiness(GATE_GENERATION)
                .getValue("goalCoherence")
                .jsonObject
                .getValue("pursuit")
                .jsonObject
                .getValue("measured")
                .jsonObject
        assertEquals(2, pursuit.getValue("originalTargetCompletions").jsonPrimitive.int)
    }

    @Test
    fun `an agent with nothing in sight worth doing walks to the edge of sight to look further`() {
        // r17 eta idled three reviews with a dead bush 5.4 blocks off, just past what it saw.
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5))))
        world.mind.answer = { seen, _ -> Intent.MoveTo(seen.explore.first().ref) to null }
        world.review(ALPHA)
        world.awaitDecided(1)
        val moved = world.awaitRecord { it.event == BodyEvent.MOVED }
        assertEquals(EventTag.MOVEMENT_ARRIVED, moved.outcome)
        val offered = world.mind.seen.single().explore
        assertEquals(8, offered.size, "one cell per compass bearing on open ground")
        assertTrue(offered.all { it.distance > 8.0 }, "every cell lies past what the body sees: $offered")
    }

    @Test
    fun `a stalled goal with nothing to replace it by is abandoned, and an achievable one may not be`() {
        val terracotta = (0..2).map { Cell(8 + 2 * it, 64, 11, "terracotta") }
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = listOf(BUSH) + terracotta))
        world.mind.answer = { seen, index ->
            val nth = seen.surroundings.filter { it.what == "block.minecraft.terracotta" }.sortedBy { it.ref }
            when (index) {
                0 -> Intent.MoveTo(nth[0].ref) to target(GoalDisposition.ADOPT, STICK, 1)
                // An abandon of a goal the bush can still meet is refused.
                1 -> Intent.Idle to GoalChoice(GoalDisposition.ABANDON, stepResult = GoalStepResult.NO_PROGRESS)
                2, 3 -> Intent.MoveTo(nth[index - 1].ref) to continuing()
                // At the bound the bush's one stick cannot raise the total, so nothing replaces it.
                else -> Intent.Idle to GoalChoice(GoalDisposition.ABANDON, stepResult = GoalStepResult.NO_PROGRESS)
            }
        }
        for (review in 1..5) {
            world.review(ALPHA)
            world.awaitDecided(review)
            world.awaitApplied(review)
        }
        val outcomes = world.records.filter { it.event == BodyEvent.DECIDED }.map { it.goal?.outcome }
        assertEquals(
            listOf(
                GoalEvidence.ADOPTED,
                GoalEvidence.ABANDONED_ACHIEVABLE,
                GoalEvidence.CONTINUED,
                GoalEvidence.CONTINUED,
                GoalEvidence.ABANDONED,
            ),
            outcomes,
        )
        assertEquals(3L, world.mind.seen[4].goal?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `a collect without approach of an item a block below the feet is refused by the body`() {
        // The v25 rehearsal's theta: 1.9 away, one block below, never picked up by vanilla.
        val stick = SceneItem(STICK_ID, "stick", 1, 8.5, 63.0, 9.9)
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(Cell(8, 63, 9, "air"), Cell(8, 62, 9, "stone")),
                items = listOf(stick),
            )
        val world = world(scene)
        world.mind.answer = { seen, _ ->
            val item = seen.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
            Intent.Collect(item.ref, approach = false) to null
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(EventTag.COLLECT_OUT_OF_RANGE, refused.outcome)
    }

    @Test
    fun `an own drop still lying is told past retention, then its fate, then forgotten`() {
        // r17 epsilon's stick lay 2322 ticks, under the 4800 boundary no run has crossed.
        val stick = SceneItem(STICK_ID, "stick", 2, 10.5, 64.0, 10.5)
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5), Stand(BETA, 4.5, 64.0, 4.5)),
                items = listOf(stick),
                trackDrops = true,
            )
        val world = world(scene)
        world.mind.answer = { _, _ -> Intent.Idle to null }
        world.dropped(ALPHA, "req-dig", stick, listOf(10, 64, 10))
        world.advance(OWN_DROP_RETENTION_TICKS + 200)
        world.review(ALPHA)
        world.awaitDecided(1)
        val lying = world.mind.seen[0]
        val own = lying.selfState.ownDrops.single()
        assertEquals(DropFate.LYING, own.fate)
        assertEquals(2L, own.count)
        val offered = lying.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
        assertEquals(offered.ref, own.ref, "a drop still lying stays offered by its observation's ref")
        // beta takes it, as gamma took beta's in r17: told as taken, from when its fate was known.
        world.taken(stick, BETA)
        world.advance(OWN_DROP_RETENTION_TICKS - 200)
        world.review(ALPHA)
        world.awaitDecided(2)
        assertEquals(listOf(DropFate.TAKEN), world.mind.seen[1].selfState.ownDrops.map { it.fate })
        world.advance(OWN_DROP_RETENTION_TICKS)
        world.review(ALPHA)
        world.awaitDecided(3)
        assertEquals(emptyList<DropFate>(), world.mind.seen[2].selfState.ownDrops.map { it.fate })
    }

    private fun world(scene: Scene): ScenarioWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return ScenarioWorld(requireNotNull(node), scene, temporary, closers)
    }

    private companion object {
        const val GATE_GENERATION = 9
        const val STICK_ID = 70
        const val STICK = "item.minecraft.stick"
        const val PLANKS = "item.minecraft.oak_planks"
        val ALPHA = AgentId("agent-alpha")
        val BETA = AgentId("agent-beta")
        val BUSH = Cell(10, 64, 8, "dead_bush")

        fun target(
            disposition: GoalDisposition,
            item: String,
            count: Long,
        ) = GoalChoice(
            disposition = disposition,
            successItem = item,
            successCount = count,
            stepResult = GoalStepResult.DONE.takeIf { disposition != GoalDisposition.ADOPT },
        )

        fun continuing() = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.NO_PROGRESS)

        /** A craft of the recipe [seen] offers for [what], [crafts] times. */
        fun craft(
            seen: Perception,
            what: String,
            crafts: Long,
        ): Intent = Intent.Craft(seen.selfState.craftingOptions.first { it.what == what }.ref, crafts)
    }
}
