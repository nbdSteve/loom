package dev.loom.body.player

import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedTime
import dev.loom.bodycontrol.SensedVitals
import dev.loom.contract.Bearing
import dev.loom.contract.BlockFace
import dev.loom.contract.BlockYield
import dev.loom.contract.Closing
import dev.loom.contract.Cover
import dev.loom.contract.CoverFace
import dev.loom.contract.MAX_COVER_GATHERS
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The closers a shelter's cover offers to gather while nothing held can close it: a safe, visible
 * dig in reach that drops a solid block, never one of the shelter's own cells, then a solid drop a
 * collect can route to (r39: alpha's dirt subgoal dug its own shelter's west wall at 5397).
 */
class SceneCoverGathersTest {
    /** The agent stands with its feet in (8, 64, 8). */
    private val position = listOf(8.5, 64.0, 8.5)

    private val solidity =
        ItemSolidity { what ->
            when (what) {
                "minecraft:dirt", "minecraft:oak_log" -> true
                "minecraft:oak_pressure_plate", "minecraft:stick" -> false
                else -> null
            }
        }

    private val cover =
        Cover(
            sealed = false,
            walkOut = true,
            open = listOf(CoverFace.HEAD_WEST, CoverFace.ROOF),
            closings = listOf(Closing(CoverFace.HEAD_WEST, "b0", BlockFace.UP)),
            blockItems = listOf(DIRT),
        )

    private fun dirtAt(
        cell: List<Int>,
        yields: String = DIRT,
        visible: Boolean = true,
    ) = Triple(cell, yields, visible)

    private val cells =
        listOf(
            // The feet-west wall and the floor are the shelter's own: digging either opens it.
            dirtAt(listOf(7, 64, 8)),
            dirtAt(listOf(8, 63, 8)),
            // Dirt two and three blocks east, outside the shelter.
            dirtAt(listOf(11, 64, 8)),
            dirtAt(listOf(10, 64, 8)),
            // A block that drops something no face can be closed with: a pressure plate has no
            // collision (r39, D1), so it is never a closer.
            dirtAt(listOf(10, 64, 10), yields = "item.minecraft.oak_pressure_plate"),
            // Dirt the eyes cannot see.
            dirtAt(listOf(9, 64, 10), visible = false),
        )

    private fun report(inventory: List<SensedStack> = emptyList()) =
        BodyResponse.SenseReport(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = "sense-1",
            agentId = "agent-alpha",
            state = LifecycleState.READY,
            position = position,
            vitals = SensedVitals(health = 20.0, food = 20, saturation = 5.0),
            time = SensedTime(timeOfDay = 12_500, day = 1, isDay = true),
            entities = emptyList(),
            entitiesOmitted = 0,
            blocks = cells.map { (cell, _, visible) -> SensedBlock("minecraft:dirt", cell, visible) },
            blocksOmitted = 0,
            items = emptyList(),
            itemsOmitted = 0,
            inventory = inventory,
            recipes = emptyList(),
            recipesOmitted = 0,
        )

    private val offers =
        cells.mapIndexed { index, (cell, yields, visible) ->
            SceneOffer(
                kind = ObservationKind.BLOCK,
                sourceIndex = index,
                what = "block.minecraft.dirt",
                position = cell.map { it + 0.5 },
                visible = visible,
                yields = listOf(BlockYield(yields, 1, 1, 100)),
                digSafe = true,
            ) to "b${index + 1}"
        }

    private fun drop(
        ref: String,
        what: String,
        distance: Double,
        refused: Boolean? = null,
    ) = Observation(
        kind = ObservationKind.DROPPED_ITEM,
        what = what,
        distance = distance,
        direction = Bearing.EAST,
        verticalOffset = 0.0,
        visible = true,
        ref = ref,
        count = 1,
        routeRefused = refused,
    )

    private val drops =
        listOf(
            drop("d1", DIRT, 3.0),
            drop("d2", DIRT, 4.0, refused = true),
            drop("d3", "item.minecraft.oak_pressure_plate", 2.0),
            drop("d4", DIRT, 40.0),
        )

    @Test
    fun `the digs outside the shelter that drop a solid block, nearest first, then the solid drops a route reaches`() {
        assertEquals(listOf("b4", "b3", "d1"), gathersOf(report(), cover, offers, drops, solidity))
    }

    @Test
    fun `nothing is gathered while a solid block is held, no face can close, or the agent is sealed in`() {
        val holding = report(listOf(SensedStack(slot = 36, what = "minecraft:dirt", count = 1)))
        assertEquals(emptyList<String>(), gathersOf(holding, cover, offers, drops, solidity))
        // A pressure plate held closes nothing, so it leaves the gathering offered.
        val plate = report(listOf(SensedStack(slot = 36, what = "minecraft:oak_pressure_plate", count = 1)))
        assertEquals(listOf("b4", "b3", "d1"), gathersOf(plate, cover, offers, drops, solidity))
        val unclosable = cover.copy(closings = emptyList())
        assertEquals(emptyList<String>(), gathersOf(report(), unclosable, offers, drops, solidity))
        val sealed = cover.copy(sealed = true, walkOut = false, open = emptyList(), closings = emptyList())
        assertEquals(emptyList<String>(), gathersOf(report(), sealed, offers, drops, solidity))
    }

    @Test
    fun `no more gathers are offered than the contract carries`() {
        val many = (1..MAX_COVER_GATHERS + 4).map { drop("d$it", DIRT, it.toDouble()) }
        assertEquals(MAX_COVER_GATHERS, gathersOf(report(), cover, emptyList(), many, solidity).size)
    }

    private companion object {
        const val DIRT = "item.minecraft.dirt"
    }
}
