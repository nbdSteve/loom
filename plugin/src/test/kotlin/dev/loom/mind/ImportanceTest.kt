/*
 * The tiering table, pinned.
 *
 * The reason these are tests and not just a `when` is cost: tiering is what keeps
 * routine traffic off an expensive model, and a silently changed mapping would
 * show up as a bill rather than as a failure.
 */
package dev.loom.mind

import dev.loom.contract.DecisionImportance
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ImportanceTest {
    private val everyTrigger =
        listOf(
            Trigger.GoalReview,
            Trigger.BlockSighted("diamond_ore"),
            Trigger.Spoken(from = "player-steve", text = "want to trade?"),
            Trigger.Damaged(amount = 3L, source = "zombie"),
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
    @DisplayName("no trigger but damage reaches the pivotal tier")
    fun onlyDamageIsPivotal() {
        // The property that matters for cost: everything else stays off the frontier
        // model, whatever the rest of the table says.
        for (trigger in everyTrigger.filterNot { it is Trigger.Damaged }) {
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
}
