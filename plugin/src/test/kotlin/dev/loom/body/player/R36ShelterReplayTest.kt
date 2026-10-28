package dev.loom.body.player

import dev.loom.contract.BlockFace
import dev.loom.contract.CoverFace
import dev.loom.contract.LoomJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * r36 zeta's own ground on seed 20011 (`body/test/fixtures/r36-zeta-plains-terrain.json`): it
 * stood on grass at (-63.5, 64, -35.5), beside the 128-block border, at tick 6082 with 1807 ticks
 * to nightfall, and died in the open at 11516. The ground there would have sheltered it: three digs
 * down from where it stood and one placement over its head seal it in, every step a cover reading
 * names, and nothing about the ground needs anything the agent did not dig itself.
 */
class R36ShelterReplayTest {
    /** The scene's border: column -64 is the last inside it, and the server lets no body past. */
    private val borderWest = -64

    private fun ground(changed: Map<List<Int>, Boolean>): BlockGround {
        val terrain =
            LoomJson
                .parseToJsonElement(
                    Files.readString(Path.of("..", "body", "test", "fixtures", "r36-zeta-plains-terrain.json")),
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
        return object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean =
                position[0] >= borderWest && (changed[position] ?: (position !in solid))

            override fun holdsNothing(position: List<Int>): Boolean = standsIn(position)
        }
    }

    /** Every solid block within a few cells of zeta's column, offered as a sense would offer it. */
    private fun supports(ground: BlockGround): Map<List<Int>, CoverSupport> =
        (-66..-61).flatMap { x -> (58..67).flatMap { y -> (-39..-33).map { z -> listOf(x, y, z) } } }
            .filter { !ground.standsIn(it) && it[0] >= borderWest }
            .withIndex()
            .associate { (index, cell) -> cell to CoverSupport("b$index", cell, visible = true) }

    private fun at(y: Double): List<Double> = listOf(-63.5, y, -35.5)

    @Test
    fun `where zeta stood the border already closes its west, and the grass underfoot is the descent`() {
        val ground = ground(emptyMap())
        val supports = supports(ground)
        val cover = requireNotNull(coverOf(ground, at(64.0), supports, listOf("item.minecraft.dirt")))
        assertFalse(cover.sealed)
        assertTrue(cover.walkOut)
        assertEquals(listOf(CoverFace.FEET_WEST, CoverFace.HEAD_WEST), CoverFace.entries - cover.open.toSet())
        assertEquals(supports.getValue(listOf(-64, 63, -36)).ref, cover.descend)
    }

    @Test
    fun `three digs down and one placement over the head seal zeta in, every step one its cover names`() {
        val opened = mutableMapOf<List<Int>, Boolean>()
        var feet = 64.0
        val ranks = mutableListOf<CoverReading>()
        repeat(3) {
            val ground = ground(opened)
            val supports = supports(ground)
            val reading = coverAt(ground, at(feet).map { kotlin.math.floor(it).toInt() }, at(feet), supports)
            ranks += reading
            val descend = requireNotNull(descendOf(ground, at(feet), supports, reading))
            assertEquals(supports.entries.single { it.value.ref == descend }.key, listOf(-64, feet.toInt() - 1, -36))
            opened[listOf(-64, feet.toInt() - 1, -36)] = true
            feet -= 1
        }
        val pit = ground(opened)
        val supports = supports(pit)
        val bottom = coverAt(pit, listOf(-64, 61, -36), at(61.0), supports)
        ranks += bottom
        // Each dig left zeta more enclosed than the last, the last by making the roof closable.
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct(), ranks)
        assertFalse(bottom.sealed)
        assertEquals(listOf(CoverFace.ROOF), bottom.open)
        assertNull(descendOf(pit, at(61.0), supports, bottom), "the fourth dig deepens nothing")
        val roof = bottom.closings.single()
        assertEquals(CoverFace.ROOF, roof.face)
        assertTrue(roof.side != BlockFace.DOWN)
        // Placed, the dirt the digs gave closes the roof, and nothing can reach zeta.
        val sealed = ground(opened + (listOf(-64, 63, -36) to false))
        assertTrue(sealedAt(sealed, listOf(-64, 61, -36)))
        assertFalse(escapesFrom(sealed, listOf(-64, 61, -36)))
    }

    private companion object {
        /** The palette's blocks a body walks through. */
        val OPEN = setOf("short_grass", "tall_grass")
    }
}
