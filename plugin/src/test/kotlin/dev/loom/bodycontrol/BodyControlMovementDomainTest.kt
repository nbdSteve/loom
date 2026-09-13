package dev.loom.bodycontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.math.ceil
import kotlin.math.sqrt

/** How far from the origin a coordinate may be, mirroring the schema's `maximum`. */
private const val MOVEMENT_WORLD_BORDER = 30_000_000.0

/**
 * Focused proofs for the relations and derived domain shared by movement responses.
 *
 * These are separate from the fixture-wide conformance class because they construct boundary
 * values rather than sweep the corpus.
 */
class BodyControlMovementDomainTest {
    private val schemaRoot =
        Json.parseToJsonElement(
            Paths
                .get(System.getProperty("user.dir"), "..", "contract", "body-control.schema.json")
                .toRealPath()
                .readText(),
        ).jsonObject

    @Test
    fun outOfRangeReportsExactlyZeroTravelAtTheTypeBoundary() {
        val walk =
            BodyResponse.MoveReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "move-zero",
                agentId = "agent-alpha",
                state = LifecycleState.READY,
                outcome = MoveOutcome.OUT_OF_RANGE,
                from = listOf(12.5, 71.5, -40.5),
                position = listOf(12.5, 71.5, -40.5),
                travelled = 0.0,
                elapsedMs = 2,
            )
        assertEquals(walk, decodeBodyResponse(encodeBodyResponse(walk)))
        assertThrows<IllegalArgumentException> { walk.copy(travelled = 0.25) }

        val flight =
            BodyResponse.FleeReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "flee-zero",
                agentId = "agent-alpha",
                state = LifecycleState.READY,
                outcome = MoveOutcome.OUT_OF_RANGE,
                from = listOf(12.5, 71.5, -40.5),
                position = listOf(12.5, 71.5, -40.5),
                travelled = 0.0,
                elapsedMs = 2,
                separation = 4.5,
                separationGained = 1.25,
            )
        assertEquals(flight, decodeBodyResponse(encodeBodyResponse(flight)))
        assertThrows<IllegalArgumentException> { flight.copy(travelled = 0.25) }
    }

    @Test
    fun generationTwoDistanceDomainCoversEveryAcceptedPositionPairAndFinalSample() {
        val expected =
            ceil(
                sqrt(3.0) * (MOVEMENT_WORLD_BORDER * 2) +
                    (MAX_RETREAT_DISTANCE * 4),
            ).toInt()
        assertEquals(103_923_177, expected)
        assertEquals(expected, MAX_TRAVEL_DISTANCE)
        val schemaMaximum =
            schemaRoot["\$defs"]
                ?.jsonObject
                ?.get("TravelDistance")
                ?.jsonObject
                ?.get("maximum")
                ?.jsonPrimitive
                ?.double
        assertEquals(expected.toDouble(), schemaMaximum)

        val extreme =
            BodyResponse.FleeReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "flee-extreme",
                agentId = "agent-alpha",
                state = LifecycleState.READY,
                outcome = MoveOutcome.ARRIVED,
                from = List(3) { -MOVEMENT_WORLD_BORDER },
                position = List(3) { MOVEMENT_WORLD_BORDER },
                travelled = MAX_TRAVEL_DISTANCE.toDouble(),
                elapsedMs = 1,
                separation = MAX_TRAVEL_DISTANCE.toDouble(),
                separationGained = -MAX_TRAVEL_DISTANCE.toDouble(),
            )
        val encoded = encodeBodyResponse(extreme)
        assertEquals(extreme, decodeBodyResponse(encoded))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < MAX_BODY_CONTROL_FRAME_BYTES)
        assertThrows<IllegalArgumentException> {
            extreme.copy(travelled = MAX_TRAVEL_DISTANCE + 1.0)
        }
        assertThrows<IllegalArgumentException> {
            extreme.copy(separation = MAX_TRAVEL_DISTANCE + 1.0)
        }
    }
}
