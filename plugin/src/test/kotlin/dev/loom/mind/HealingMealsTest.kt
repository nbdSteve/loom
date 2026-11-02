package dev.loom.mind

import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The JVM's half of `cognition/tests/healing_meals.rs`, against the same contract fixture. */
class HealingMealsTest {
    private val hurt: Perception =
        LoomJson.decodeFromString(
            Perception.serializer(),
            Files.readString(
                Path.of("..", "contract", "fixtures", "valid", "perception-damaged-hurt-holding-food.json"),
            ),
        )

    @Test
    fun `a hurt agent below the healing threshold may eat its food, and only its food`() {
        // r33: every agent's food fell below 18 and stayed there, so none healed.
        val offer = SurvivalOffer.of(hurt)
        assertEquals(setOf("i1"), offer.meals)
        assertTrue(offer.survives(Intent.Eat("i1")))
        assertFalse(offer.survives(Intent.Eat("i2")), "dirt is no meal")
    }

    @Test
    fun `no meal is offered once food heals or health is no longer low`() {
        assertTrue(
            SurvivalOffer.of(
                hurt.copy(selfState = hurt.selfState.copy(food = HEALING_FOOD_LEVEL.toInt())),
            ).meals.isEmpty(),
        )
        assertTrue(SurvivalOffer.of(hurt.copy(selfState = hurt.selfState.copy(health = 16.0))).meals.isEmpty())
    }
}
