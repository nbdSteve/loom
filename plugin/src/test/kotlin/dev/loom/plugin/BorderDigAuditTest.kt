package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.body.player.playerIdentityOf
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SensedBlock
import org.bukkit.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * r36 zeta's dig of short grass at (-65, 64, -36), one column past the 128-block world border,
 * replayed through the real server witness. The server refused the dig and put the block back, so
 * no `BlockBreakEvent` names any player (`body:00000552`). The witness is unchanged by the fix:
 * it confirmed the body's `libraryFailure` because no break happened, it confirms the fixed
 * body's `notDiggable` refusal for the same reason, and it disputes a `dug` claim nothing backs.
 */
class BorderDigAuditTest {
    private val zeta = playerIdentityOf(AgentId("tribe-zeta"))
    private val grass = SensedBlock("minecraft:short_grass", listOf(-65, 64, -36), visible = true)

    private fun auditor(): BukkitInteractionAuditor {
        val server = serverAt(AUDITED_TICK)
        return BukkitInteractionAuditor(server, BukkitInteractionEvents(server, setOf(zeta.uuid)))
    }

    private fun claim(
        outcome: DigOutcome,
        attempted: Boolean,
    ): ServerInteractionClaim.Dig {
        val request =
            BodyRequest.Dig(BODY_CONTROL_PROTOCOL_VERSION, CONTROL_ID, zeta.agentId.value, SENSE_ID, 0, DEADLINE_MS)
        val report =
            BodyResponse.DigReport(
                BODY_CONTROL_PROTOCOL_VERSION,
                CONTROL_ID,
                zeta.agentId.value,
                LifecycleState.READY,
                outcome,
                if (attempted) DEADLINE_MS.toLong() else 0,
                attempted,
            )
        return ServerInteractionClaim.Dig(request, report, grass, startedTick = STARTED_TICK)
    }

    @Test
    fun `r36's library failure past the border is confirmed by the absent break, as it was recorded`() {
        val audit = auditor().audit(zeta, claim(DigOutcome.LIBRARY_FAILURE, attempted = true))
        assertTrue(audit.confirmed, audit.detail)
        assertEquals("no matching blockBreak event", audit.detail)
    }

    @Test
    fun `the fixed body's refusal before any dig is confirmed, and a claimed break is disputed`() {
        assertTrue(auditor().audit(zeta, claim(DigOutcome.NOT_DIGGABLE, attempted = false)).confirmed)
        val lie = auditor().audit(zeta, claim(DigOutcome.DUG, attempted = true))
        assertFalse(lie.confirmed)
        assertFalse(lie.brokenByOther)
    }

    private companion object {
        const val CONTROL_ID = "loom3d8263b30e-dig-kx"
        const val SENSE_ID = "loom3d8263b30e-sense-kr"
        const val STARTED_TICK = 6115L
        const val AUDITED_TICK = 6519
        const val DEADLINE_MS = 20_000
    }
}

private fun serverAt(tick: Int): Server =
    Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
        if (method.name == "getCurrentTick") tick else null
    } as Server
