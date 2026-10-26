package dev.loom.body.player

import dev.loom.contract.LoomJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * r35's oak at (-13, 66..70, -6) on seed 20011 (`body/test/fixtures/r35-canopy-log-terrain.json`):
 * eta dug its trunk's two lowest logs and alpha the third, and epsilon and zeta walked to the two
 * left in its canopy, seen from afar, and stood under leaves that hid them. A far block is offered
 * only when some stand on the ground digs it, so a log no stand reaches is no walk at all.
 */
class R35CanopyReplayTest {
    private fun ground(dug: Set<List<Int>>): BlockGround {
        val terrain =
            LoomJson
                .parseToJsonElement(
                    Files.readString(Path.of("..", "body", "test", "fixtures", "r35-canopy-log-terrain.json")),
                ).jsonObject
        val palette = requireNotNull(terrain["palette"]).jsonArray.map { it.jsonPrimitive.content }
        val solid = HashSet<List<Int>>()
        for ((column, cells) in requireNotNull(terrain["columns"]).jsonObject) {
            val (x, z) = column.split(',').map(String::toInt)
            for (cell in cells.jsonArray) {
                val (y, kind) = cell.jsonArray.map { it.jsonPrimitive.int }
                if (palette[kind] !in OPEN) solid += listOf(x, y, z)
            }
        }
        solid.removeAll(dug)
        return BlockGround { cell -> cell !in solid }
    }

    /** Where epsilon's walk toward the tree began, on the fixture's grass south of it. */
    private val epsilon = listOf(-10, 66, 1)

    @Test
    fun `the intact trunk is dug from the grass beside it`() {
        val ground = ground(emptySet())
        assertTrue(diggableFrom(ground, listOf(-13, 66, -6), walkableFrom(ground, epsilon, 20)))
    }

    @Test
    fun `a canopy log seen past the trunk is no source a walk could reach`() {
        // Four blocks up and wrapped in leaves: the only stands that see it are on the leaves.
        val ground = ground(emptySet())
        val walkable = walkableFrom(ground, epsilon, 20)
        assertFalse(diggableFrom(ground, listOf(-13, 70, -6), walkable))
        assertTrue(digsFrom(ground, listOf(-14, 70, -7), listOf(-13, 70, -6)), "a stand on the leaves sees it")
        assertFalse(listOf(-14, 70, -7) in walkable, "and no walk climbs the canopy")
    }

    @Test
    fun `a stand beside the source sees it, not one under its leaves`() {
        // Epsilon's walk ended at (-10.5, 66, -3.3), under the canopy, where no log could be seen.
        val dug = setOf(listOf(-13, 66, -6), listOf(-13, 67, -6), listOf(-13, 68, -6))
        assertFalse(digsFrom(ground(dug), listOf(-11, 66, -4), listOf(-13, 69, -6)))
    }

    private companion object {
        /** The fixture's kinds with no collision box. */
        val OPEN = setOf("short_grass", "tall_grass")
    }
}
