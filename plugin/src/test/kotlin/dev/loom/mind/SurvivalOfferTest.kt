package dev.loom.mind

import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The survival offer the ledger judges by, against the mind's (`OfferedRefs::of`): the creature
 * that just hurt the agent is immediate danger and a survival target wherever it stands.
 */
class SurvivalOfferTest {
    private val ranged: Perception =
        LoomJson.decodeFromString(
            Paths
                .get(System.getProperty("user.dir"), "..", "contract", "fixtures", "valid")
                .resolve("perception-damaged-ranged-attacker.json")
                .toFile()
                .readText(),
        )

    @Test
    fun `a skeleton shooting from past the hunters in danger range is still immediate danger`() {
        // v32 plains rehearsal 15: epsilon's skeleton stood 20.5 blocks off, past seventeen sheep.
        val offer = SurvivalOffer.of(ranged)
        assertEquals(setOf("e40"), offer.immediate)
        assertTrue(offer.survives(Intent.Flee("e40")))
        assertTrue(offer.survives(Intent.Attack("e40", "i1", 1, false)))
        // Beside it only a survival response is taken.
        assertTrue(offer.withholds(Intent.Dig("b1")))
        assertFalse(offer.withholds(Intent.Attack("e40", "i1", 1, false)))
    }

    @Test
    fun `damage that names no attacker, or one the scene does not show, reserves nothing`() {
        val unnamed = ranged.copy(trigger = Trigger.Damaged(3, "skeleton"))
        assertEquals(emptySet<String>(), SurvivalOffer.of(unnamed).immediate, "the skeleton is past danger range")
        val unshown = ranged.copy(trigger = Trigger.Damaged(3, "skeleton", attackerRef = "e99"))
        assertEquals(emptySet<String>(), SurvivalOffer.of(unshown).immediate)
    }
}
