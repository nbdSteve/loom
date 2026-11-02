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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The v28 plains rehearsal's tick-2982 dispute, replayed through the real server witness: epsilon
 * was offered dirt at the bottom of its own pit, grass spread onto it, and the server's one
 * `BlockBreakEvent` names a grass_block. The witness is unchanged: a `dug` of dirt beside a break
 * of grass is still a dispute. What changed is the body: it stops a dig whose block the world
 * turned into another, and answers `blockChanged`, attempted, which no break contradicts.
 */
class ChangedDigAuditTest {
    private val epsilon = playerIdentityOf(AgentId("tribe-epsilon"))
    private val dirt = SensedBlock("minecraft:dirt", listOf(-10, 63, 7), visible = true)

    private fun auditor(broke: String?): BukkitInteractionAuditor {
        val server = serverNow(2982)
        val events = BukkitInteractionEvents(server, setOf(epsilon.uuid))
        broke?.let {
            events.evidence.remember(VanillaInteractionEvent.Broke(epsilon.uuid, 2982, BlockPoint(-10, 63, 7), it))
        }
        return BukkitInteractionAuditor(server, events)
    }

    private fun claim(outcome: DigOutcome): ServerInteractionClaim.Dig {
        val request = BodyRequest.Dig(BODY_CONTROL_PROTOCOL_VERSION, "dig-a7", "tribe-epsilon", "sense-a3", 0, 20_000)
        val report =
            BodyResponse.DigReport(
                BODY_CONTROL_PROTOCOL_VERSION,
                "dig-a7",
                "tribe-epsilon",
                LifecycleState.READY,
                outcome,
                40,
                attempted = true,
            )
        return ServerInteractionClaim.Dig(request, report, dirt, startedTick = 2964)
    }

    @Test
    fun `a dug of the offered dirt beside a break of grass is still a dispute`() {
        assertFalse(auditor("minecraft:grass_block").audit(epsilon, claim(DigOutcome.DUG)).confirmed)
        assertTrue(auditor("minecraft:dirt").audit(epsilon, claim(DigOutcome.DUG)).confirmed)
    }

    @Test
    fun `a dig stopped when the world changed its block is confirmed by the break that never came`() {
        assertTrue(auditor(null).audit(epsilon, claim(DigOutcome.BLOCK_CHANGED)).confirmed)
        // And it is no cover for a break this body did make.
        assertFalse(auditor("minecraft:grass_block").audit(epsilon, claim(DigOutcome.BLOCK_CHANGED)).confirmed)
    }
}

private fun serverNow(tick: Int): Server =
    Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
        if (method.name == "getCurrentTick") tick else null
    } as Server
