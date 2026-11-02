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
 * The r23 rehearsal's tick-3268 disputes, replayed through the real server witness: kappa and
 * iota dug one dead bush, and the server's one `BlockBreakEvent` names kappa. The witness is
 * unchanged by the fix - each body's claim is still held to the break event of its own player -
 * and what changed is what the bodies claim: kappa `dug` its own break, iota `blockGone` attempted.
 */
class ContestedDigAuditTest {
    private val kappa = playerIdentityOf(AgentId("tribe-kappa"))
    private val iota = playerIdentityOf(AgentId("tribe-iota"))
    private val bush = SensedBlock("minecraft:dead_bush", listOf(9, 107, -5), visible = true)

    private fun auditorWithKappasBreak(): BukkitInteractionAuditor {
        val server = serverAt(3268)
        val events = BukkitInteractionEvents(server, setOf(kappa.uuid, iota.uuid))
        events.evidence.remember(
            VanillaInteractionEvent.Broke(kappa.uuid, 3268, BlockPoint(9, 107, -5), "minecraft:dead_bush"),
        )
        return BukkitInteractionAuditor(server, events)
    }

    private fun claim(
        who: String,
        outcome: DigOutcome,
        attempted: Boolean = true,
    ): ServerInteractionClaim.Dig {
        val request = BodyRequest.Dig(BODY_CONTROL_PROTOCOL_VERSION, "dig-$who", who, "sense-$who", 0, 20_000)
        val report =
            BodyResponse.DigReport(
                BODY_CONTROL_PROTOCOL_VERSION,
                "dig-$who",
                who,
                LifecycleState.READY,
                outcome,
                40,
                attempted,
            )
        return ServerInteractionClaim.Dig(request, report, bush, startedTick = 3200)
    }

    @Test
    fun `the break is the breaker's alone, and each body's claim is held to its own player's events`() {
        // What the fixed bodies claim: confirmed on both sides.
        val auditor = auditorWithKappasBreak()
        val kappas = auditor.audit(kappa, claim("kappa", DigOutcome.DUG))
        assertTrue(kappas.confirmed)
        assertFalse(kappas.brokenByOther)
        assertTrue(auditorWithKappasBreak().audit(iota, claim("iota", DigOutcome.BLOCK_GONE)).confirmed)
        // A halt cannot cancel a break the server took: still a dispute.
        assertFalse(auditorWithKappasBreak().audit(kappa, claim("kappa", DigOutcome.CANCELLED)).confirmed)
    }

    @Test
    fun `crossed finishes are the server's to attribute, even after the winner's own audit`() {
        // The second v23 rehearsal, tick 3262: iota's finish on an instant-break bush crossed
        // kappa's in the server. iota's client saw the block go after its own finish, exactly as
        // kappa's did; only the server's break event says it was kappa's.
        val auditor = auditorWithKappasBreak()
        assertTrue(auditor.audit(kappa, claim("kappa", DigOutcome.DUG)).confirmed)
        val iotas = auditor.audit(iota, claim("iota", DigOutcome.DUG))
        assertTrue(iotas.confirmed, iotas.detail)
        assertTrue(iotas.brokenByOther)
        // A dug claim with no break by anyone is still a dispute.
        val nobody = BukkitInteractionAuditor(serverAt(3268), BukkitInteractionEvents(serverAt(3268), setOf(iota.uuid)))
        val lie = nobody.audit(iota, claim("iota", DigOutcome.DUG))
        assertFalse(lie.confirmed)
        assertFalse(lie.brokenByOther)
        // And a break of another block, or one before the dig began, is no contest.
        val elsewhere = auditorWithKappasBreak()
        val earlier = claim("iota", DigOutcome.DUG).copy(startedTick = 3269)
        assertFalse(elsewhere.audit(iota, earlier).confirmed)
    }
}

private fun serverAt(tick: Int): Server =
    Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
        if (method.name == "getCurrentTick") tick else null
    } as Server
