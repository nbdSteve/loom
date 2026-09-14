/*
 * The authoritative server readings a player-body adapter is allowed to use.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.SensedEntity
import java.util.UUID

/**
 * Server-authoritative facts used to reconcile a client-controlled player.
 *
 * The adapter asks this seam only on the server main thread. The body process is
 * evidence about what its client saw; this is where that evidence is checked and
 * where death is learned. A process exit, socket refusal, or Mineflayer `dead`
 * lifecycle answer never reaches [isDead].
 */
interface PlayerServerState {
    fun connected(identity: PlayerIdentity): Boolean

    fun isDead(agentId: AgentId): Boolean

    fun dayOrdinal(): Long

    /**
     * Correlates each client observation with the server entity it describes.
     *
     * The body-control wire deliberately carries no durable entity handle. The
     * request-scoped handle used by `flee` stays an index into that one sense answer,
     * while this server UUID is used only to let the existing threat debounce recognise
     * the same creature in a later answer. An entry is `null` when the server cannot
     * corroborate it; the adapter then keeps that observation request-local.
     */
    fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?>

    fun auditSense(
        identity: PlayerIdentity,
        report: BodyResponse.SenseReport,
    )

    fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.MoveTo,
        report: BodyResponse.MoveReport,
    )

    fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.Flee,
        report: BodyResponse.FleeReport,
    )
}
