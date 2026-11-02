package dev.loom.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The horizon goal's own rules, the ones the schema states, held by the JVM binding too. */
class HorizonGoalTest {
    private val log = "item.minecraft.oak_log"

    @Test
    fun `a horizon goal asks 1 to 64, is never air, and names its input exactly when a recipe grounds it`() {
        val sight = HorizonGoal(log, HorizonGrounding.SIGHT, null, 9, true)
        val recipe = HorizonGoal("item.minecraft.oak_planks", HorizonGrounding.RECIPE, log, 8, false)
        assertEquals(log, recipe.via)
        assertThrows(IllegalArgumentException::class.java) { sight.copy(most = 0) }
        assertThrows(IllegalArgumentException::class.java) { sight.copy(most = MAX_SUCCESS_COUNT + 1) }
        assertThrows(IllegalArgumentException::class.java) { sight.copy(what = AIR_ITEM) }
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(via = AIR_ITEM) }
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(via = null) }
        assertThrows(IllegalArgumentException::class.java) { sight.copy(via = log) }
        assertThrows(IllegalArgumentException::class.java) {
            HorizonGoal(log, HorizonGrounding.RECALLED, log, 4, true)
        }
    }

    @Test
    fun `a perception carries at most eight horizon goals`() {
        val goals =
            (0..MAX_HORIZON_GOALS).map {
                HorizonGoal(
                    "item.minecraft.item$it",
                    HorizonGrounding.SIGHT,
                    null,
                    4,
                    true,
                )
            }
        val base = LoomJson.decodeFromString(Perception.serializer(), FIXTURE)
        assertEquals(MAX_HORIZON_GOALS, base.copy(horizon = goals.take(MAX_HORIZON_GOALS)).horizon.size)
        assertThrows(IllegalArgumentException::class.java) { base.copy(horizon = goals) }
    }

    private companion object {
        val FIXTURE: String =
            java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "contract", "fixtures", "valid", "perception-goal-review-horizon.json"),
            )
    }
}
