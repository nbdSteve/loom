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
 * server's ground seals the agent in, stepped only by what its cover reading names - gathering a
 * closer among them when nothing it holds can close it - and held under one identity throughout.
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
    fun `with nothing solid to close natural cover, gathering a closer is a step of the shelter itself`() {
        val ledger = GoalLedger()
        val headWest = Closing(CoverFace.HEAD_WEST, "b2", BlockFace.UP)
        val alcove = open(listOf(CoverFace.HEAD_WEST, CoverFace.ROOF), closings = listOf(headWest))
        val nearby = listOf(block("b1", Bearing.HERE), block("b2", Bearing.NORTH), block("b3", Bearing.EAST))
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
        // Nothing solid is held, so the closing cannot be placed: the reading offers the dirt beside
        // it to gather, and that dig is the shelter's own step (r39: alpha's dirt subgoal changed the
        // objective's identity twice and dug its own shelter's wall).
        val site = scene("r1", 100, alcove.copy(gathers = listOf("b3")), seen = nearby)
        val reached = ledger.present(agent, "r1", 100, GoalObservation.of(site))
        assertEquals(StepEvidence.COVERED, reached?.progress?.stepEvidence)
        ledger.forget(agent, "r1")
        // A dig the reading does not offer is no step of it, and a resource subgoal is no goal of its own.
        assertEquals(GoalEvidence.STEP_UNPRODUCTIVE, ledger.answer(site, Intent.Dig("b2"), continued)?.outcome)
        val feeds = GoalChoice(GoalDisposition.ADOPT, successItem = DIRT, successCount = 2, feeds = true)
        assertEquals(GoalEvidence.FEEDS_NOT_OFFERED, ledger.answer(site, Intent.Dig("b3"), feeds)?.outcome)
        assertEquals(GoalEvidence.CONTINUED, ledger.answer(site, Intent.Dig("b3"), continued)?.outcome)
        ledger.step("r1", "dig")
        // The dirt it gave is the shelter's progress, under the one goal it has held throughout.
        val holding = scene("r2", 200, alcove, listOf(dirt), seen = nearby)
        val gained = ledger.present(agent, "r2", 200, GoalObservation.of(holding))
        assertEquals("r0", gained?.goalId)
        assertEquals(StepEvidence.GAINED_INPUT, gained?.progress?.stepEvidence)
        assertEquals(0L, gained?.progress?.stepsWithoutProgress)
        ledger.forget(agent, "r2")
        // The closing is its next step, and the placement is credited as cover.
        val placed = ledger.answer(holding, Intent.Place("b2", "s1", BlockFace.UP), continued)
        assertEquals(GoalEvidence.CONTINUED, placed?.outcome)
        ledger.step("r2", "place")
        val after = scene("r3", 300, open(listOf(CoverFace.ROOF)), listOf(dirt.copy(count = 2)))
        val shown = ledger.present(agent, "r3", 300, GoalObservation.of(after))
        assertEquals("r0", shown?.goalId)
        assertNull(shown?.feeds)
        assertEquals(StepEvidence.COVERED, shown?.progress?.stepEvidence)
    }

    @Test
    fun `a gather is the shelter's step only where the mind offers it, a dig in reach or a collect with a route`() {
        // The r39 offline audit: the mind narrowed the reading's gathers to its own digs and collects,
        // while the ledger took the reading's list whole.
        val far = block("b4", Bearing.EAST).copy(distance = 9.0)
        val refused =
            Observation(
                kind = ObservationKind.DROPPED_ITEM,
                what = DIRT,
                distance = 4.0,
                direction = Bearing.WEST,
                verticalOffset = 0.0,
                visible = true,
                ref = "d1",
                count = 1,
                routeRefused = true,
            )
        val headWest = Closing(CoverFace.HEAD_WEST, "b2", BlockFace.UP)
        val alcove = open(listOf(CoverFace.HEAD_WEST, CoverFace.ROOF), closings = listOf(headWest))
        val site =
            scene(
                "r1",
                100,
                alcove.copy(gathers = listOf("b3", "b4", "d1")),
                seen = listOf(block("b2", Bearing.NORTH), block("b3", Bearing.EAST), far, refused),
            )
        assertEquals(setOf("b3"), CoverObservation.of(site)?.gathers)
    }

    @Test
    fun `a closing is credited against the most the goal has been where the agent stands, not where it stood`() {
        // r39 alpha 5922: after a descent the goal's best cover was the cell above's, so the closing
        // it then placed below was credited nothing, and the goal stalled into an abandon at 6011.
        val ledger = GoalLedger()

        fun Perception.cell(
            y: Double,
            z: Double,
        ) = copy(selfState = selfState.copy(position = listOf(3.5, y, z)))
        val grass =
            scene(
                "r0",
                50,
                open(allOpen),
                seen = listOf(block("b1", Bearing.HERE), block("b9", Bearing.SOUTH, cover = 7)),
            )
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(grass, Intent.MoveTo("b9"), shelter)?.outcome)
        ledger.step("r0", "moveTo")
        // The walk south reached seven closed sides.
        val alcove = scene("r1", 100, open(allOpen.take(2), descend = "b1")).cell(64.0, 4.5)
        assertEquals(
            StepEvidence.COVERED,
            ledger.present(agent, "r1", 100, GoalObservation.of(alcove))?.progress?.stepEvidence,
        )
        ledger.forget(agent, "r1")
        assertEquals(GoalEvidence.CONTINUED, ledger.answer(alcove, Intent.Dig("b1"), continued)?.outcome)
        ledger.step("r1", "dig")
        // Down a block, five closed: less enclosed than where it left, so the descent is no step.
        val roof = Closing(CoverFace.ROOF, "b3", BlockFace.DOWN)
        val openBelow = listOf(CoverFace.FEET_NORTH, CoverFace.FEET_EAST, CoverFace.HEAD_NORTH, CoverFace.ROOF)
        val pit = open(openBelow, closings = listOf(roof))
        val below = scene("r2", 200, pit, listOf(dirt)).cell(63.0, 4.5)
        val descended = ledger.present(agent, "r2", 200, GoalObservation.of(below))
        assertEquals(StepEvidence.NONE, descended?.progress?.stepEvidence)
        ledger.forget(agent, "r2")
        assertEquals(
            GoalEvidence.CONTINUED,
            ledger.answer(below, Intent.Place("b3", "s1", BlockFace.DOWN), continued)?.outcome,
        )
        ledger.step("r2", "place")
        // Six closed where it now stands: past the five this cell has shown, so the closing counts.
        val closed = scene("r3", 300, open(openBelow - CoverFace.ROOF), listOf(dirt.copy(count = 2))).cell(63.0, 4.5)
        val credited = ledger.present(agent, "r3", 300, GoalObservation.of(closed))
        assertEquals(StepEvidence.COVERED, credited?.progress?.stepEvidence)
        assertEquals(0L, credited?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `cover the agent was carried into is no step, and rebuilt where it landed is credited only past its best`() {
        // The r39 offline audit: cover improved by a flight credited the shelter, and a closing in a
        // cell a flight had carried the agent into was credited against that cell alone.
        val ledger = GoalLedger()

        fun Perception.cell(z: Double) = copy(selfState = selfState.copy(position = listOf(3.5, 64.0, z)))
        val north = Closing(CoverFace.FEET_NORTH, "b2", BlockFace.SOUTH)
        // Six faces open, the north one among them, and three closed.
        val six = allOpen.take(1) + allOpen.drop(4)
        val start = scene("r0", 50, open(six, closings = listOf(north)), listOf(dirt))
        val closing = Intent.Place("b2", "s1", BlockFace.SOUTH)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(start, closing, shelter)?.outcome)
        ledger.step("r0", "place")
        // Carried five blocks south by a flight, into a cell with less cover: the closing it placed
        // back there cannot be read here, so it is no step, and the stall is not reset.
        val landed = scene("r1", 100, open(allOpen, closings = listOf(north)), listOf(dirt)).cell(8.5)
        val carried = ledger.present(agent, "r1", 100, GoalObservation.of(landed))?.progress
        assertEquals(StepEvidence.NONE, carried?.stepEvidence)
        assertEquals(false, carried?.conditionAdvanced)
        assertEquals(1L, carried?.stepsWithoutProgress)
        ledger.forget(agent, "r1")
        assertEquals(
            GoalEvidence.CONTINUED,
            ledger.answer(landed, Intent.Place("b2", "s1", BlockFace.SOUTH), continued)?.outcome,
        )
        ledger.step("r1", "place")
        // Rebuilt to the three sides it had closed before the flight: no more enclosed than the goal
        // has been, so no step either.
        val rebuilt = scene("r2", 200, open(six, closings = listOf(north)), listOf(dirt)).cell(8.5)
        val measured = ledger.present(agent, "r2", 200, GoalObservation.of(rebuilt))?.progress
        assertEquals(StepEvidence.NONE, measured?.stepEvidence)
    }

    @Test
    fun `a side dig moves no one, so cover a flight found after it is not the shelter's`() {
        // The r39 offline audit, round 2: any accepted dig counted as the agent's own move, so a
        // gather beside it and then a flight into an overhang were credited as cover.
        val ledger = GoalLedger()
        val headWest = Closing(CoverFace.HEAD_WEST, "b2", BlockFace.UP)
        val nearby = listOf(block("b1", Bearing.HERE), block("b2", Bearing.NORTH), block("b3", Bearing.EAST))
        val site =
            scene("r0", 50, open(allOpen, closings = listOf(headWest)).copy(gathers = listOf("b3")), seen = nearby)
        assertEquals(GoalEvidence.ADOPTED, ledger.answer(site, Intent.Dig("b3"), shelter)?.outcome)
        ledger.step("r0", "dig")
        val overhang =
            scene("r1", 100, open(listOf(CoverFace.HEAD_WEST, CoverFace.ROOF)), listOf(dirt), seen = nearby)
                .copy(
                    selfState = site.selfState.copy(position = listOf(9.5, 64.0, 3.5), inventorySummary = listOf(dirt)),
                )
        val progress = ledger.present(agent, "r1", 100, GoalObservation.of(overhang))?.progress
        // The dirt it gave is the gather's own credit; the cover the flight found is no step.
        assertEquals(StepEvidence.GAINED_INPUT, progress?.stepEvidence)
        assertEquals(false, progress?.conditionAdvanced)
    }

    @Test
    fun `a descent that gives a solid block is credited only as cover, never as a gather`() {
        val ledger = GoalLedger()
        val grass = scene("r0", 50, open(allOpen, descend = "b1"))
        ledger.answer(grass, Intent.Dig("b1"), shelter)
        ledger.step("r0", "dig")
        // The descent deepened nothing, though it gave dirt: no gather was offered, so no progress.
        val same = scene("r1", 100, open(allOpen, descend = "b1"), listOf(dirt))
        val shown = ledger.present(agent, "r1", 100, GoalObservation.of(same))
        assertEquals(StepEvidence.NONE, shown?.progress?.stepEvidence)
        assertEquals(1L, shown?.progress?.stepsWithoutProgress)
    }

    @Test
    fun `a resource subgoal is never offered, and a solid block carried is equipped as a shelter step`() {
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
        // Nor is one under an item goal held without a shelter.
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
