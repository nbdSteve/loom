package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.ShownGoal
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.StepEvidence
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The goal refusals and the terrain refusal r16 never met, each driven offline end to end through
 * the real JVM ledger, cognition, actuation and fleet to real Node bodies, and then classified by
 * the research readiness gate the way a published run would be.
 *
 * - an unobtainable total, a total already held and an acting answer that ignores its goal are
 *   each refused before the body acts and told, with their numbers, at the next review, and every
 *   review's record states exactly the goal and refusal the mind was shown;
 * - a collect that finds no route is the ground's answer, `terrain`, and the item is marked so it
 *   is never offered again;
 * - a continue that walks away from the agent's own lying drop of its goal's item is refused;
 * - a goal at the stall bound is replaced, in one answer, by a walk onto a source of its item,
 *   which is credited as the new goal's arrival.
 */
class GoalRefusalEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `refused goal decisions are told once with their numbers, and every review records what it showed`() {
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(BUSH, TERRACOTTA),
                inventory = listOf(SceneStack(36, "stick", 2)),
            )
        val world = world(scene)
        world.mind.answer = { seen, index ->
            when (index) {
                // Five sticks: two held and a bush expected to give one cannot reach it.
                0 -> walkTo(seen, "dead_bush") to sticks(GoalDisposition.ADOPT, 5)
                // Two sticks are already held.
                1 -> walkTo(seen, "dead_bush") to sticks(GoalDisposition.ADOPT, 2)
                2 -> walkTo(seen, "dead_bush") to sticks(GoalDisposition.ADOPT, 3)
                // An action beside no goal choice while a goal is held.
                3 -> walkTo(seen, "terracotta") to null
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
                GoalEvidence.SUCCESS_UNOBTAINABLE,
                GoalEvidence.ALREADY_MET,
                GoalEvidence.ADOPTED,
                GoalEvidence.CHOICE_MISSING,
                GoalEvidence.ABANDONED,
            ),
            outcomes,
        )
        val told = world.mind.seen.map { it.lastRefusal }
        assertEquals(null, told[0])
        assertEquals(GoalEvidence.SUCCESS_UNOBTAINABLE, told[1]?.outcome)
        assertEquals(listOf(5L, 2L, 1L), listOf(told[1]?.asked, told[1]?.held, told[1]?.capacity))
        assertEquals(GoalEvidence.ALREADY_MET, told[2]?.outcome)
        assertEquals(null, told[3])
        assertEquals(GoalEvidence.CHOICE_MISSING, told[4]?.outcome)
        // Exactly what each review showed the mind, on its own decided record.
        val shown = world.records.filter { it.event == BodyEvent.DECIDED }.map { it.shown }
        assertEquals(world.mind.seen.map { ShownGoal(it.goal, it.lastRefusal) }, shown)
        // Three of the five answers are refused, and no refusal reached the body.
        val readiness = world.readiness(GATE_GENERATION)
        val classes = readiness.getValue("actions").jsonObject.getValue("byClass").jsonObject
        assertEquals(3, classes.getValue("goalIncoherent").jsonPrimitive.int)
    }

    @Test
    fun `a collect with no route is the ground's answer, and the item is never offered again`() {
        // r15: five collects of one stick no body could stand beside; r16 never met one.
        val pillar = (64..66).map { Cell(12, it, 8, "stone") }
        val stick = SceneItem(STICK_ID, "stick", 1, 12.5, 67.0, 8.5)
        // The stick rests out of sight on top of the pillar, so it is reported as the agent's own
        // drop; the only cell whose pickup box covers it is the pillar top no route can reach.
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = pillar,
                items = listOf(stick),
                ownDrops = mapOf("req-old" to stick),
                columns = 0,
            )
        val world = world(scene)
        world.mind.answer = { seen, index ->
            val item = seen.surroundings.firstOrNull { it.kind == ObservationKind.DROPPED_ITEM }
            if (index == 0 && item != null) Intent.Collect(item.ref, approach = true) to null else Intent.Idle to null
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(EventTag.COLLECT_NO_ROUTE, refused.outcome)
        world.review(ALPHA)
        world.awaitDecided(2)
        val again = world.mind.seen[1].surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
        assertEquals(true, again.routeRefused)
        val readiness = world.readiness(GATE_GENERATION)
        val classes = readiness.getValue("actions").jsonObject.getValue("byClass").jsonObject
        assertEquals(1, classes.getValue("terrain").jsonPrimitive.int)
        val rates = readiness.getValue("rates").jsonObject
        assertEquals("1", rates.getValue("decisionValidity").jsonPrimitive.content)
    }

    @Test
    fun `a continue that walks away from the agent's own lying stick is refused`() {
        // r16: in 11 of 21 reviews with its own stick on offer the agent dug again or idled.
        val stick = SceneItem(STICK_ID, "stick", 1, 10.5, 64.0, 10.5)
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(BUSH, TERRACOTTA),
                items = listOf(stick),
                ownDrops = mapOf("req-old" to stick),
            )
        val world = world(scene)
        world.mind.answer = { seen, index ->
            when (index) {
                0 -> walkTo(seen, "terracotta") to sticks(GoalDisposition.ADOPT, 2)
                1 -> walkTo(seen, "dead_bush") to goalChoice(GoalDisposition.CONTINUE)
                else -> Intent.Idle to GoalChoice(GoalDisposition.ABANDON, stepResult = GoalStepResult.NO_PROGRESS)
            }
        }
        for (review in 1..2) {
            world.review(ALPHA)
            world.awaitDecided(review)
            world.awaitApplied(review)
        }
        val second = world.mind.seen[1]
        val own = second.selfState.ownDrops.single()
        val offered = second.surroundings.single { it.kind == ObservationKind.DROPPED_ITEM }
        assertEquals(offered.ref, own.ref, "the own drop is offered by the ref of its observation")
        val refused = world.records.filter { it.event == BodyEvent.DECIDED }[1]
        assertEquals(GoalEvidence.OWN_DROP_IGNORED, refused.goal?.outcome)
    }

    @Test
    fun `a goal at the stall bound is replaced by a walk onto a source of its item`() {
        // r16 alpha body:00000113 and kappa body:00000148 lost a review cycle at the bound.
        val terracotta = (0..2).map { Cell(8 + 2 * it, 64, 11, "terracotta") }
        val scene = Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = listOf(BUSH) + terracotta)
        val world = world(scene)
        world.mind.answer = { seen, index ->
            val nth = seen.surroundings.filter { it.what == "block.minecraft.terracotta" }.sortedBy { it.ref }
            when (index) {
                0 -> Intent.MoveTo(nth[0].ref) to sticks(GoalDisposition.ADOPT, 1)
                1, 2 -> Intent.MoveTo(nth[index].ref) to goalChoice(GoalDisposition.CONTINUE)
                3 -> walkTo(seen, "dead_bush") to sticks(GoalDisposition.REPLACE, 1)
                else -> Intent.Idle to GoalChoice(GoalDisposition.ABANDON, stepResult = GoalStepResult.NO_PROGRESS)
            }
        }
        for (review in 1..5) {
            world.review(ALPHA)
            world.awaitDecided(review)
            world.awaitApplied(review)
        }
        val decided = world.records.filter { it.event == BodyEvent.DECIDED }.map { it.goal }
        assertEquals(
            listOf(1L, 2L, 3L),
            decided.subList(1, 4).map { it?.stepsWithoutProgress },
            "walks onto terracotta are no step toward sticks",
        )
        assertEquals(GoalEvidence.REPLACED, decided[3]?.outcome)
        assertEquals(StepEvidence.ARRIVED, decided[4]?.stepEvidence)
        assertEquals(0L, decided[4]?.stepsWithoutProgress)
    }

    private fun world(scene: Scene): ScenarioWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return ScenarioWorld(requireNotNull(node), scene, temporary, closers)
    }

    private companion object {
        const val GATE_GENERATION = 8
        const val STICK_ID = 50
        val ALPHA = AgentId("agent-alpha")
        val BUSH = Cell(10, 64, 8, "dead_bush")
        val TERRACOTTA = Cell(8, 64, 11, "terracotta")

        fun sticks(
            disposition: GoalDisposition,
            count: Long,
        ) = goalChoice(disposition, success = count)

        /** A choice carrying every field [disposition] needs, with a stick total of [success]. */
        fun goalChoice(
            disposition: GoalDisposition,
            success: Long = 1,
        ): GoalChoice {
            val targeted = disposition in setOf(GoalDisposition.ADOPT, GoalDisposition.REVISE, GoalDisposition.REPLACE)
            return GoalChoice(
                disposition = disposition,
                successItem = "item.minecraft.stick".takeIf { targeted },
                successCount = success.takeIf { targeted },
                step = "the scenario's step",
                stepResult = GoalStepResult.NO_PROGRESS.takeIf { disposition != GoalDisposition.ADOPT },
            )
        }

        /** A walk onto the first block of [what] the perception offers. */
        fun walkTo(
            seen: Perception,
            what: String,
        ): Intent = Intent.MoveTo(seen.surroundings.first { it.what == "block.minecraft.$what" }.ref)
    }
}
