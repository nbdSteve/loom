/*
 * The tiering table, pinned.
 *
 * The reason these are tests and not just a `when` is cost: tiering is what keeps
 * routine traffic off an expensive model, and a silently changed mapping would
 * show up as a bill rather than as a failure.
 */
package dev.loom.mind

import dev.loom.contract.Bearing
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A zombie in the open, close enough that the agent's own options are still real. */
private val ZOMBIE_ON_TOP_OF_IT =
    Trigger.ThreatSighted(
        threat = "entity.minecraft.zombie",
        ref = "e1",
        distance = 2.0,
        direction = Bearing.NORTH,
        visible = true,
        reachable = true,
    )

class ImportanceTest {
    private val everyTrigger =
        listOf(
            Trigger.GoalReview,
            Trigger.BlockSighted("diamond_ore"),
            Trigger.Spoken(from = "player-steve", text = "want to trade?"),
            Trigger.Damaged(amount = 3L, source = "zombie"),
            ZOMBIE_ON_TOP_OF_IT,
            ZOMBIE_ON_TOP_OF_IT.copy(distance = 20.0, ref = "e2"),
        )

    @Test
    @DisplayName("the ordinary, frequent events are routine")
    fun theFrequentEventsAreCheap() {
        assertEquals(DecisionImportance.ROUTINE, importanceOf(Trigger.GoalReview))
        assertEquals(DecisionImportance.ROUTINE, importanceOf(Trigger.BlockSighted("diamond_ore")))
    }

    @Test
    @DisplayName("being spoken to is notable: another party is waiting on the answer")
    fun socialEventsAreNotable() {
        assertEquals(
            DecisionImportance.NOTABLE,
            importanceOf(Trigger.Spoken(from = "player-steve", text = "want to trade?")),
        )
    }

    @Test
    @DisplayName("damage is pivotal: it is the only trigger that can end a permadeath agent")
    fun damageIsPivotal() {
        assertEquals(DecisionImportance.PIVOTAL, importanceOf(Trigger.Damaged(amount = 1L, source = "fall")))
    }

    @Test
    @DisplayName("a hostile close by and in sight is pivotal: it is the other way an agent ends")
    fun aCloseThreatIsPivotal() {
        assertEquals(DecisionImportance.PIVOTAL, importanceOf(ZOMBIE_ON_TOP_OF_IT))
    }

    @Test
    @DisplayName("a threat with no route to it is pivotal all the same")
    fun anUnreachableThreatIsStillPivotal() {
        // The engine refuses a route whenever the body is off the ground, and a body being
        // hit is off the ground - so pricing on reachability would charge the cheap tier in
        // the worst danger an agent will ever be in. `ThreatWatch`'s nightly ceiling counts
        // the same conjunction, and the two have to agree or the budget stops counting what
        // it pays for.
        assertEquals(DecisionImportance.PIVOTAL, importanceOf(ZOMBIE_ON_TOP_OF_IT.copy(reachable = false)))
    }

    @Test
    @DisplayName("a threat behind a wall, or across the view, is notable rather than pivotal")
    fun aDistantOrHiddenThreatIsNotable() {
        assertEquals(DecisionImportance.NOTABLE, importanceOf(ZOMBIE_ON_TOP_OF_IT.copy(visible = false)))
        assertEquals(DecisionImportance.NOTABLE, importanceOf(ZOMBIE_ON_TOP_OF_IT.copy(distance = 20.0)))
    }

    @Test
    @DisplayName("nothing but danger reaches the pivotal tier")
    fun onlyDangerIsPivotal() {
        // The property that matters for cost: everything that is not damage or a hostile
        // within reach stays off the frontier model, whatever the rest of the table says.
        val dangers = everyTrigger.filter { it is Trigger.Damaged || it == ZOMBIE_ON_TOP_OF_IT }
        for (trigger in everyTrigger - dangers.toSet()) {
            assertNotEquals(DecisionImportance.PIVOTAL, importanceOf(trigger), "trigger $trigger")
        }
    }

    @Test
    @DisplayName("classifying a perception replaces whatever tier it arrived with")
    fun classifyingOverwritesTheReportedTier() {
        val misreported =
            perception(
                trigger = Trigger.Damaged(amount = 9L, source = "creeper"),
                importance = DecisionImportance.ROUTINE,
            )

        val classified = misreported.classified()

        assertEquals(DecisionImportance.PIVOTAL, classified.importance)
        // Nothing else about the perception is touched: the classification is the
        // only thing this side gets to decide.
        assertEquals(misreported, classified.copy(importance = misreported.importance))
    }

    @Test
    @DisplayName("classifying is idempotent")
    fun classifyingTwiceChangesNothing() {
        for (trigger in everyTrigger) {
            val once = perception(trigger = trigger).classified()
            assertEquals(once, once.classified(), "trigger $trigger")
        }
    }

    @Test
    @DisplayName("a goal review beside a hunter in sight and in pivotal range is pivotal")
    fun aReviewBesideAHunterIsPivotal() {
        // r22: four of four routine reviews beside a hunter chose a dig, one at health 1.0.
        fun creature(
            distance: Double,
            visible: Boolean = true,
            hunts: Boolean? = true,
        ) = dev.loom.contract.Observation(
            kind = dev.loom.contract.ObservationKind.ENTITY,
            what = "entity.minecraft.spider",
            distance = distance,
            direction = Bearing.NORTH,
            verticalOffset = 0.0,
            visible = visible,
            ref = "e1",
            hunts = hunts,
        )
        val review = perception(requestId = "r1", trigger = Trigger.GoalReview)
        assertEquals(DecisionImportance.ROUTINE, review.classified().importance)
        assertEquals(
            DecisionImportance.PIVOTAL,
            review.copy(surroundings = listOf(creature(1.4))).classified().importance,
        )
        assertEquals(
            DecisionImportance.PIVOTAL,
            review.copy(trigger = Trigger.StepSettled, surroundings = listOf(creature(6.0))).classified().importance,
        )
        assertEquals(
            DecisionImportance.ROUTINE,
            review.copy(surroundings = listOf(creature(12.0))).classified().importance,
        )
        assertEquals(
            DecisionImportance.ROUTINE,
            review.copy(surroundings = listOf(creature(3.0, visible = false))).classified().importance,
        )
        assertEquals(
            DecisionImportance.ROUTINE,
            review.copy(surroundings = listOf(creature(3.0, hunts = null))).classified().importance,
        )
    }
}
