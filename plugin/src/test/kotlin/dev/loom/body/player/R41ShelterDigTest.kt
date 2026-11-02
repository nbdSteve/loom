package dev.loom.body.player

import dev.loom.body.WorldClock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R41 alpha's shelter: it dug down into the grass at (4, 63, 13), placed dirt around its head and
 * over it, and was sealed in at tick 7520, at dusk. It then adopted dirt goals and dug its own walls
 * four times (body:00000561 at 7726 the first, which left it exposed at 7740), and was killed in the
 * open at 12066. A dig of one of those walls is no safe dig through the shelter window.
 */
class R41ShelterDigTest {
    private val feet = listOf(4, 63, 13)

    /** Flat plains under y 64, open air above, the pit's two cells open, and dirt placed round the head. */
    private fun ground(dug: Set<List<Int>> = emptySet()): BlockGround {
        val placed =
            setOf(listOf(3, 64, 13), listOf(5, 64, 13), listOf(4, 64, 12), listOf(4, 64, 14), listOf(4, 65, 13))
        val pit = setOf(feet, listOf(4, 64, 13))
        return object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean =
                position in pit || position in dug || (position[1] >= 64 && position !in placed)

            override fun takesPlacement(position: List<Int>): Boolean = standsIn(position)
        }
    }

    @Test
    fun `a wall between the sealed pit and the open air opens it, and the plains beside the feet do not`() {
        val sealed = ground()
        assertTrue(sealedAt(sealed, feet))
        assertTrue(digOpensShelter(sealed, feet, listOf(4, 64, 14)), "a placed wall with open air behind it")
        assertTrue(digOpensShelter(sealed, feet, listOf(4, 65, 13)), "the roof")
        assertFalse(digOpensShelter(sealed, feet, listOf(5, 63, 13)), "ground beside the feet leaves the pit sealed")
        // Opened once, a space is no longer sealed, so nothing more is withheld as opening it.
        val open = ground(dug = setOf(listOf(4, 64, 14)))
        assertFalse(sealedAt(open, feet))
        assertFalse(digOpensShelter(open, feet, listOf(4, 65, 13)))
    }

    @Test
    fun `the shelter window is the night and the lead before nightfall, as the shelter goal reads it`() {
        // R41: alpha's first wall dig at tick 7707, about 170 ticks before nightfall, at dusk.
        assertTrue(WorldClock.shelterWindow(WorldClock.NIGHT_START - 170))
        assertTrue(WorldClock.shelterWindow(WorldClock.NIGHT_START - WorldClock.SHELTER_LEAD_TICKS))
        assertTrue(WorldClock.shelterWindow(WorldClock.NIGHT_START + 4_000))
        assertFalse(WorldClock.shelterWindow(WorldClock.NIGHT_START - WorldClock.SHELTER_LEAD_TICKS - 1))
        assertFalse(WorldClock.shelterWindow(1_000))
    }
}
