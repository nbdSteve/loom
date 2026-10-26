package dev.loom.body.player

import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CollectOutcome
import dev.loom.contract.LoomJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * r34's three `collectNoRoute`s - delta's own-drop pickup at body:00000242, kappa's collect at
 * body:00000383 and theta's at body:00001893 - all named one oak log, ce29f989, lying at
 * (-4.86, 67, -29.15) in a one-high gap under an oak's canopy. Replayed on the run's seed-20011
 * ground (`body/test/fixtures/v32-beta-canopy-terrain.json`, with the three logs delta had dug),
 * the server finds no cell a body could stand in and pick it up from: the ground's answer was
 * right, it is now verified rather than claimed, and no later agent is offered the log.
 */
class R34TerrainReplayTest {
    private val ground: BlockGround by lazy {
        val terrain =
            LoomJson
                .parseToJsonElement(
                    Files.readString(Path.of("..", "body", "test", "fixtures", "v32-beta-canopy-terrain.json")),
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
        solid.removeAll(DUG.toSet())
        BlockGround { cell -> cell !in solid }
    }

    @Test
    fun `the lodged log has no cell to be picked up from, and is no item to offer`() {
        assertEquals(emptyList<List<Int>>(), pickupCellsOf(ground, LODGED))
        val refusals = RouteRefusals({ 0L }, ground)
        assertTrue(refusals.blocks(UUID.randomUUID(), listOf(1.5, 68.0, -15.5), LODGED), "kappa was offered it")
        // A log dropped on the open grass beside the tree is picked up from cells round it.
        assertTrue(pickupCellsOf(ground, listOf(-1.5, 67.0, -32.5)).isNotEmpty())
    }

    @Test
    fun `a no-route collect states the server's count of cells, and none verifies the terrain`() {
        val item = dev.loom.bodycontrol.SensedItem("oak_log", 1, LODGED, visible = true)
        val claim =
            ServerInteractionClaim.Collect(
                BodyRequest.Collect(
                    protocol = dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION,
                    requestId = "kappa-collect-rt",
                    agentId = "tribe-kappa",
                    senseId = "kappa-sense-rl",
                    item = 0,
                    approach = true,
                    timeoutMs = 10_000,
                    sprint = false,
                ),
                BodyResponse.CollectReport(
                    protocol = dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION,
                    requestId = "kappa-collect-rt",
                    agentId = "tribe-kappa",
                    state = dev.loom.bodycontrol.LifecycleState.READY,
                    outcome = CollectOutcome.NO_ROUTE,
                    from = listOf(1.5, 68.0, -15.5),
                    position = listOf(1.5, 68.0, -15.5),
                    travelled = 0.0,
                    elapsedMs = 40,
                ),
                item,
                null,
                8218,
            )
        assertEquals(0, terrainOf(ground, claim)?.pickupCells)
    }

    private companion object {
        val LODGED = listOf(-4.86328125, 67.0, -29.15185546875)

        /** Delta's dug logs by tick 5291 (`body/test/native-r34-delta-terrain.test.js`). */
        val DUG = listOf(listOf(-5, 67, -27), listOf(-6, 67, -30), listOf(-5, 68, -27))

        /** The fixture's kinds with no collision box. */
        val OPEN = setOf("dandelion", "short_grass", "tall_grass")
    }
}
