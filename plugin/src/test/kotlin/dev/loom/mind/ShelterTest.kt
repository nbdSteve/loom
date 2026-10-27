package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.Bearing
import dev.loom.contract.BlockFace
import dev.loom.contract.BlockYield
import dev.loom.contract.Closing
import dev.loom.contract.Cover
import dev.loom.contract.CoverFace
import dev.loom.contract.DayPhase
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.GoalStepResult
import dev.loom.contract.Intent
import dev.loom.contract.InventoryStack
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.StepEvidence
import dev.loom.contract.Trigger
import dev.loom.contract.WorldState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * r36: all ten agents died at night in the open, every death under hostile attack, and none ever
 * held a goal a hiding place could meet. A shelter goal is the model's own choice, met only when the
 * server's ground seals the agent in, stepped only by what its cover reading names, and fed by a
 * resource subgoal when nothing it holds can close it.
 */
class ShelterTest {
    private val agent = AgentId("agent-zeta")
    private val shelter = GoalChoice(GoalDisposition.ADOPT, shelter = true)
    private val continued = GoalChoice(GoalDisposition.CONTINUE, stepResult = GoalStepResult.PROGRESSING)
    private val dusk = WorldState(dayPhase = DayPhase.DUSK, isDay = true, ticksUntilNightfall = 900)
    private val dirt = InventoryStack("s1", DIRT, 3, solid = true, held = true)

    private val allOpen = CoverFace.entries.toList()

    private fun block(
        ref: String,
        bearing: Bearing,
        cover: Long? = null,
        item: String = DIRT,
    ) = Observation(
        kind = ObservationKind.BLOCK,
        what = "block.minecraft.dirt",
        distance = 1.1,
        direction = bearing,
        verticalOffset = -0.5,
        visible = true,
        ref = ref,
        standable = cover != null,
        yields = listOf(BlockYield(item, 1, 1, 100)),
        digSafe = true,
        cover = cover,
    )

    private fun scene(
        requestId: String,
        tick: Long,
        cover: Cover,
        stacks: List<InventoryStack> = emptyList(),
        seen: List<Observation> = listOf(block("b1", Bearing.HERE), block("b2", Bearing.NORTH)),
    ): Perception {
        val base = perception(requestId = requestId, trigger = Trigger.GoalReview)
        return base.copy(
            capturedTick = tick,
            surroundings = seen,
            selfState =
                base.selfState.copy(
                    position = listOf(3.5, 64.0, 3.5),
                    inventorySummary = stacks,
                    world = dusk,
                    cover = cover,
                ),
        )
    }

    /** [this] perception with the agent's feet at [y]. */
    private fun Perception.at(y: Double): Perception = copy(selfState = selfState.copy(position = listOf(3.5, y, 3.5)))

    /** A pit's cover: only the roof open, closed by [closing], and no walk out. */
    private fun pit(closing: Closing) = open(listOf(CoverFace.ROOF), closings = listOf(closing), walkOut = false)

    private fun open(
        faces: List<CoverFace>,
        descend: String? = null,
        closings: List<Closing> = emptyList(),
        walkOut: Boolean = true,
        sealed: Boolean = false,
    ) = Cover(sealed, walkOut, faces, closings, descend, listOf(DIRT))

    private fun GoalLedger.answer(
        perception: Perception,
        intent: Intent,
        choice: GoalChoice?,
    ): GoalEvidence? {
        present(agent, perception.requestId, perception.capturedTick, GoalObservation.of(perception))
        return resolve(agent, decisionFor(perception, intent).copy(goal = choice))
    }

    private fun GoalLedger.step(
        requestId: String,
        action: String,
        outcome: String = EventTag.ACCEPTED,
    ) {
        stepped(agent, requestId, action, outcome)
        settledAt(agent, requestId, 0)
    }

    @Test
    fun `a shelter goal is offered only as night nears and only beside a step its cover names`() {
        val ledger = GoalLedger()
        val midday = WorldState(DayPhase.DAY, true, 6_000)
        val noon =
            scene(
                "r0",
                50,
                open(allOpen, descend = "b1"),
            ).let { it.copy(selfState = it.selfState.copy(world = midday)) }
        assertEquals(GoalEvidence.SHELTER_NOT_OFFERED, ledger.answer(noon, Intent.Dig("b1"), shelter)?.outcome)
        val grass = scene("r1", 100, open(allOpen, descend = "b1"))
        // A dig beside the feet deepens nothing, and a rest is no step at all.
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.answer(grass, Intent.Dig("b2"), shelter)?.outcome)
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.answer(grass, Intent.Rest, shelter)?.outcome)
        val adopted = ledger.answer(grass, Intent.Dig("b1"), shelter)
        assertEquals(GoalEvidence.ADOPTED, adopted?.outcome)
        assertEquals(true, adopted?.shelter)
        // Set once, a shelter goal is not set again over itself.
        val again = scene("r2", 150, open(allOpen, descend = "b1"))
        ledger.step("r1", "dig")
        assertEquals(GoalEvidence.SHELTER_NOT_OFFERED, ledger.answer(again, Intent.Dig("b1"), shelter)?.outcome)
    }

    @Test
    fun `each dig down is credited as covered, a return to ground already reached is not, and sealing meets it`() {
        val ledger = GoalLedger()
        assertEquals(
            GoalEvidence.ADOPTED,
            ledger.answer(scene("r1", 100, open(allOpen, descend = "b1")), Intent.Dig("b1"), shelter)?.outcome,
        )
        ledger.step("r1", "dig")
        val oneDown = scene("r2", 200, open(allOpen - CoverFace.entries.take(4).toSet(), descend = "b1")).at(63.0)
        val first = ledger.present(agent, "r2", 200, GoalObservation.of(oneDown))
        assertEquals(SHELTER_STATEMENT, first?.statement)
        assertEquals(StepEvidence.COVERED, first?.progress?.stepEvidence)
        assertEquals(4L, first?.progress?.cover?.closed)
        assertFalse(first?.progress?.conditionMet == true)
        val onward = ledger.resolve(agent, decisionFor(oneDown, Intent.Dig("b1")).copy(goal = continued))
        assertEquals(GoalEvidence.CONTINUED, onward?.outcome)
        // The second dig was refused: nothing changed, so nothing is credited and the stall count grows.
        ledger.step("r2", "dig", EventTag.DIG_NOT_DIGGABLE)
        val same = scene("r3", 300, open(allOpen - CoverFace.entries.take(4).toSet(), descend = "b1")).at(63.0)
        val refused = ledger.present(agent, "r3", 300, GoalObservation.of(same))
        assertEquals(StepEvidence.NONE, refused?.progress?.stepEvidence)
        assertEquals(1L, refused?.progress?.stepsWithoutProgress)
        // Sealed in by a placement against the roof's support: met.
        val roof = Closing(CoverFace.ROOF, "b2", BlockFace.WEST)
        val pit = scene("r4", 400, pit(roof), listOf(dirt)).at(61.0)
        ledger.resolve(agent, decisionFor(same, Intent.Dig("b1")).copy(goal = continued))
        ledger.step("r3", "dig")
        ledger.answer(pit, Intent.Place("b2", "s1", BlockFace.WEST), continued)
        ledger.step("r4", "place")
        val sealed = scene("r5", 500, open(emptyList(), walkOut = false, sealed = true), listOf(dirt)).at(61.0)
        val met = ledger.present(agent, "r5", 500, GoalObservation.of(sealed))
        assertEquals(true, met?.progress?.conditionMet)
        assertEquals(true, met?.progress?.cover?.sealed)
        // A sealed shelter is completed to be stayed in, even beside a rest and with dirt in sight.
        val complete = GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)
        val done = ledger.resolve(agent, decisionFor(sealed, Intent.Rest).copy(goal = complete))
        assertEquals(GoalEvidence.COMPLETED, done?.outcome)
        assertEquals(true, done?.presentedShelter)
    }

    @Test
    fun `a shelter goal has no item to revise, and an unsealed one is not completed`() {
        val ledger = GoalLedger()
        val grass = scene("r1", 100, open(allOpen, descend = "b1"))
        ledger.answer(grass, Intent.Dig("b1"), shelter)
        ledger.step("r1", "dig")
        val next = scene("r2", 200, open(allOpen, descend = "b1"))
        val revise =
            GoalChoice(
                GoalDisposition.REVISE,
                successItem = DIRT,
                successCount = 2,
                stepResult = GoalStepResult.NO_PROGRESS,
            )
        assertEquals(GoalEvidence.REVISE_ITEM_CHANGED, ledger.answer(next, Intent.Dig("b1"), revise)?.outcome)
        val complete = GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)
        assertEquals(GoalEvidence.COMPLETION_UNMET, ledger.answer(next, Intent.Dig("b1"), complete)?.outcome)
    }

    @Test
    fun `with nothing solid to close natural cover, a resource subgoal feeds it and hands it back once met`() {
        val ledger = GoalLedger()
        val headWest = Closing(CoverFace.HEAD_WEST, "b2", BlockFace.UP)
        val alcove = open(listOf(CoverFace.HEAD_WEST, CoverFace.ROOF), closings = listOf(headWest))
        val site = scene("r1", 100, alcove)
        // Natural cover reached by a walk is a shelter step, as a site the cover reading names.
        val grass =
            scene(
                "r0",
                50,
                open(allOpen),
                seen = listOf(block("b1", Bearing.HERE), block("b9", Bearing.SOUTH, cover = 7)),
            )
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(grass, Intent.MoveTo("b9"), shelter)?.outcome)
        ledger.step("r0", "moveTo")
        val reached = ledger.present(agent, "r1", 100, GoalObservation.of(site))
        assertEquals(StepEvidence.COVERED, reached?.progress?.stepEvidence)
        ledger.forget(agent, "r1")
        // Nothing solid is held, so the closing cannot be placed: a dirt subgoal feeds the shelter.
        val feeds = GoalChoice(GoalDisposition.ADOPT, successItem = DIRT, successCount = 1, feeds = true)
        val fed = ledger.answer(site, Intent.Dig("b2"), feeds)
        assertEquals(GoalEvidence.ADOPTED, fed?.outcome)
        assertEquals("r0", fed?.feeds)
        ledger.step("r1", "dig")
        // A stick closes nothing, so it is no subgoal; nor is one replaced once held.
        val met = scene("r2", 200, alcove, listOf(dirt))
        val replaced =
            GoalChoice(GoalDisposition.REPLACE, successItem = DIRT, successCount = 2, stepResult = GoalStepResult.DONE)
        assertEquals(GoalEvidence.SUBGOAL_REPLACED, ledger.answer(met, Intent.Dig("b2"), replaced)?.outcome)
        // Met, completed beside the closing: the shelter goal is held again, with the placement as its step.
        val complete = GoalChoice(GoalDisposition.COMPLETE, stepResult = GoalStepResult.DONE)
        val resumed = ledger.answer(met, Intent.Place("b2", "s1", BlockFace.UP), complete)
        assertEquals(GoalEvidence.COMPLETED, resumed?.outcome)
        assertEquals("r0", resumed?.resumed)
        assertEquals("r0", resumed?.goalId)
        ledger.step("r2", "place")
        val after = scene("r3", 300, open(listOf(CoverFace.ROOF)), listOf(dirt.copy(count = 2)))
        val shown = ledger.present(agent, "r3", 300, GoalObservation.of(after))
        assertTrue(shown?.shelter == true)
        assertNull(shown?.feeds)
        assertEquals(StepEvidence.COVERED, shown?.progress?.stepEvidence)
    }

    @Test
    fun `a subgoal is fed only where nothing solid is held and the item closes a shelter`() {
        val ledger = GoalLedger()
        val headWest = Closing(CoverFace.HEAD_WEST, "b2", BlockFace.UP)
        val grass = scene("r0", 50, open(allOpen, descend = "b1"))
        ledger.answer(grass, Intent.Dig("b1"), shelter)
        ledger.step("r0", "dig")
        val alcove = open(listOf(CoverFace.HEAD_WEST, CoverFace.ROOF), closings = listOf(headWest))
        val holding = scene("r1", 100, alcove, listOf(dirt))
        val feeds = GoalChoice(GoalDisposition.ADOPT, successItem = DIRT, successCount = 4, feeds = true)
        assertEquals(GoalEvidence.FEEDS_NOT_OFFERED, ledger.answer(holding, Intent.Dig("b2"), feeds)?.outcome)
        // A solid block carried but not in hand is equipped as a shelter step, not fed for.
        val pocketed = scene("r1b", 100, alcove, listOf(dirt.copy(held = null)))
        val equip = Intent.Equip("s1", EquipmentDestination.HAND)
        assertEquals(GoalEvidence.CONTINUED, ledger.answer(pocketed, equip, continued)?.outcome)
        // Feeding an item goal held without a shelter is no subgoal either.
        val other = GoalLedger()
        val elsewhere = other.answer(holding.copy(requestId = "r9"), Intent.Dig("b2"), feeds)
        assertEquals(GoalEvidence.FEEDS_NOT_OFFERED, elsewhere?.outcome)
    }

    @Test
    fun `sealed in, no hunter is danger and no flight is a survival response`() {
        val sealed = scene("r1", 100, open(emptyList(), walkOut = false, sealed = true))
        val zombie =
            Observation(
                ObservationKind.ENTITY,
                "entity.minecraft.zombie",
                2.0,
                Bearing.NORTH,
                0.0,
                true,
                "e1",
                hunts = true,
            )
        val offer = SurvivalOffer.of(sealed.copy(surroundings = sealed.surroundings + zombie))
        assertTrue(offer.immediate.isEmpty() && offer.hunters.isEmpty())
        // Trapped in a pit beside a hunter, the closing with the block in hand is the response, and no flight is.
        val roof = Closing(CoverFace.ROOF, "b2", BlockFace.WEST)
        val pit = scene("r2", 100, pit(roof), listOf(dirt))
        val trapped = SurvivalOffer.of(pit.copy(surroundings = pit.surroundings + zombie))
        assertTrue(trapped.immediate.isNotEmpty())
        assertFalse(trapped.fleeable)
        assertTrue(trapped.survives(Intent.Place("b2", "s1", BlockFace.WEST)))
        assertFalse(trapped.survives(Intent.Flee("e1")))
        assertTrue(trapped.withholds(Intent.Flee("e1")))
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
    }
}
