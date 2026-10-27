package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.StepEvidence
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shelter goal end to end: the real JVM ledger, cognition, actuation and fleet over real
 * control sockets to a real Node body on real Mineflayer, with a scripted mind standing in for the
 * model and the shared server answering digs, drops and placements as vanilla does.
 *
 * - constructed cover: on open grass at dusk, three digs down and the dirt they gave placed over
 *   the head seal the agent in, every step one its cover reading named;
 * - natural cover: a walk into an alcove, a resource subgoal for the dirt to close it, and two
 *   placements seal it in, the shelter goal held again when the subgoal is met.
 *
 * r36's ten agents all died at night in the open. Runs whenever `node` and `body/node_modules` exist.
 */
class ShelterEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `three digs down and the dirt they gave placed over the head seal an agent in on open grass`() {
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                timeOfDay = DUSK,
                serveBreaks = true,
                floor = "grass_block",
                depth = listOf("dirt", "dirt", "dirt", "stone"),
                dropsOnBreak = true,
                servePlaces = true,
                columns = 1,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ -> shelterStep(perception) }
        val decided = reviewUntilSealed(world, maxReviews = 6)
        val outcomes = decided.map { it.goal?.outcome }
        assertEquals(
            listOf(
                GoalEvidence.ADOPTED,
                GoalEvidence.CONTINUED,
                GoalEvidence.CONTINUED,
                GoalEvidence.CONTINUED,
                GoalEvidence.COMPLETED,
            ),
            outcomes,
        )
        // Every dig and the placement left the agent more enclosed than the goal had yet been.
        assertEquals(List(4) { StepEvidence.COVERED }, decided.drop(1).map { it.goal?.stepEvidence })
        assertEquals(listOf("dig", "dig", "dig", "place", "rest"), decided.map { it.intent })
        val sealed = world.mind.seen.last().selfState.cover
        assertEquals(true, sealed?.sealed)
        assertEquals(false, sealed?.walkOut)
        assertEquals(List(3) { ALPHA.value }, world.breaks())
        assertTrue(
            world.records.filter { it.event == BodyEvent.WORLD_INTERACTION }.all {
                it.outcome == EventTag.SERVER_EFFECT_CONFIRMED
            },
        )
        // The second and third digs left no walk out; the body dug them as the descent it was offered.
        assertTrue(world.records.none { it.outcome == EventTag.DIG_WOULD_TRAP })
    }

    @Test
    fun `an alcove walked into, fed by a dirt subgoal, is closed by two placements and seals the agent in`() {
        val stone = { x: Int, y: Int, z: Int -> Cell(x, y, z, "stone") }
        val alcove =
            listOf(
                stone(9, 64, 8), stone(8, 64, 7), stone(8, 64, 9),
                stone(9, 65, 8), stone(8, 65, 7), stone(8, 65, 9),
                stone(8, 66, 8),
                Cell(6, 64, 8, "dirt"), Cell(6, 65, 8, "dirt"),
            )
        val scene =
            Scene(
                listOf(Stand(ALPHA, 5.5, 64.0, 6.5)),
                blocks = alcove,
                timeOfDay = DUSK,
                serveBreaks = true,
                dropsOnBreak = true,
                servePlaces = true,
                columns = 1,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ -> naturalStep(perception) }
        val decided = reviewUntilSealed(world, maxReviews = 8)
        val outcomes = decided.map { it.goal?.outcome }
        assertEquals(
            listOf(
                // the shelter goal, by a walk into the alcove, then the dirt subgoal that feeds it
                GoalEvidence.ADOPTED,
                GoalEvidence.ADOPTED,
                GoalEvidence.CONTINUED,
                // met: the shelter is held again, closed at the feet
                GoalEvidence.COMPLETED,
                GoalEvidence.CONTINUED,
                GoalEvidence.COMPLETED,
            ),
            outcomes,
        )
        val fed = decided[1].goal
        assertEquals(decided[0].requestId, fed?.feeds)
        assertEquals(decided[0].requestId, decided[3].goal?.resumed)
        assertEquals(listOf("moveTo", "dig", "dig", "place", "place", "rest"), decided.map { it.intent })
        assertEquals(true, world.mind.seen.last().selfState.cover?.sealed)
    }

    /** Reviews until a review completes a sealed shelter, and answers each review's decided record. */
    private fun reviewUntilSealed(
        world: ScenarioWorld,
        maxReviews: Int,
    ): List<BodyEventRecord> {
        for (review in 1..maxReviews) {
            world.review(ALPHA)
            world.awaitApplied(review)
            val last = world.records.last { it.event == BodyEvent.DECIDED }
            if (last.goal?.outcome == GoalEvidence.COMPLETED && last.goal?.presentedShelter == true) break
            // Let a dropped block reach the hand before the next review senses it.
            Thread.sleep(SETTLE_MS)
        }
        return world.records.filter { it.event == BodyEvent.DECIDED }
    }

    /** The scripted constructed shelter: adopt and dig down, place the roof, complete once sealed. */
    private fun shelterStep(perception: Perception): Pair<Intent, GoalChoice?> {
        val cover = requireNotNull(perception.selfState.cover)
        val goal = perception.goal
        val held = perception.selfState.inventorySummary.firstOrNull { it.solid == true && it.held == true }
        return when {
            goal == null -> Intent.Dig(requireNotNull(cover.descend)) to shelter
            goal.progress?.conditionMet == true -> Intent.Rest to complete
            cover.descend != null -> Intent.Dig(cover.descend!!) to continued
            else -> closing(cover, held) to continued
        }
    }

    /** The scripted natural shelter: walk into the alcove, feed it dirt, close it, complete once sealed. */
    private fun naturalStep(perception: Perception): Pair<Intent, GoalChoice?> {
        val cover = requireNotNull(perception.selfState.cover)
        val goal = perception.goal
        val held = perception.selfState.inventorySummary.firstOrNull { it.solid == true && it.held == true }
        val dirt = perception.surroundings.filter { it.what == "block.minecraft.dirt" && it.visible }
        val site = perception.surroundings.filter { (it.cover ?: 0) >= ALCOVE_COVER }.minByOrNull { it.distance }
        return when {
            goal == null -> Intent.MoveTo(requireNotNull(site).ref) to shelter
            goal.shelter && goal.progress?.conditionMet == true -> Intent.Rest to complete
            goal.feeds != null && goal.progress?.conditionMet == true -> closing(cover, held) to complete
            goal.feeds != null -> Intent.Dig(dirt.maxBy { it.verticalOffset }.ref) to continued
            held == null ->
                Intent.Dig(dirt.maxBy { it.verticalOffset }.ref) to
                    GoalChoice(GoalDisposition.ADOPT, successItem = DIRT, successCount = 2, feeds = true)
            else -> closing(cover, held) to continued
        }
    }

    private fun closing(
        cover: dev.loom.contract.Cover,
        held: dev.loom.contract.InventoryStack?,
    ): Intent = cover.closings.first().let { Intent.Place(it.block, requireNotNull(held).ref, it.side) }

    private val shelter = GoalChoice(GoalDisposition.ADOPT, shelter = true)
    private val continued = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.PROGRESSING)
    private val complete = GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)

    private fun world(scene: Scene): ScenarioWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return ScenarioWorld(requireNotNull(node), scene, temporary, closers)
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
        const val DUSK = 12_500
        const val ALCOVE_COVER = 7L
        const val SETTLE_MS = 250L
        val ALPHA = AgentId("agent-alpha")
    }
}
