package dev.loom.body.player

import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedGround
import dev.loom.bodycontrol.SensedTime
import dev.loom.bodycontrol.SensedVitals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.sqrt

class SenseRadiusTest {
    private val request =
        BodyRequest.Sense(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = "s1",
            agentId = "agent-radius",
            radius = 24,
            limit = 48,
        )

    private fun reportWith(entity: List<Double>): BodyResponse.SenseReport =
        BodyResponse.SenseReport(
            protocol = request.protocol,
            requestId = request.requestId,
            agentId = request.agentId,
            state = LifecycleState.READY,
            position = listOf(0.5, 100.0, 0.5),
            vitals = SensedVitals(health = 20.0, food = 20, saturation = 5.0),
            time = SensedTime(timeOfDay = 15_000, day = 0, isDay = false),
            ground = SensedGround(standingIn = "minecraft:air", standingOn = "minecraft:stone"),
            entities = listOf(SensedEntity(what = "minecraft:zombie", position = entity, visible = true)),
            entitiesOmitted = 0,
            blocks = emptyList(),
            blocksOmitted = 0,
            items = emptyList(),
            itemsOmitted = 0,
            inventory = emptyList(),
            recipes = emptyList(),
            recipesOmitted = 0,
        )

    @Test
    fun `a creature the body admitted from its eyes up a slope is inside the request`() {
        // Three blocks above the eyes and 23.9 from them, so the body admitted it, yet more
        // than 24 from the feet, where a feet-measured check refused it as a violation.
        val eyes = 100.0 + MAX_PLAYER_EYE_HEIGHT_BLOCKS
        val horizontal = sqrt(23.9 * 23.9 - 9.0)
        val position = listOf(0.5 + horizontal, eyes + 3.0, 0.5)
        assertTrue(sqrt(horizontal * horizontal + (position[1] - 100.0).pow(2)) > request.radius)
        assertTrue(matchesSenseRequest(request, reportWith(position)))
    }

    @Test
    fun `a creature beyond the radius even from the eyes is still a violation`() {
        assertFalse(matchesSenseRequest(request, reportWith(listOf(0.5 + 26.0, 100.0, 0.5))))
    }
}
