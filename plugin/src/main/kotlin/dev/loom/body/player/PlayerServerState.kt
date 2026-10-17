/*
 * The authoritative server readings a player-body adapter is allowed to use.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedItem
import dev.loom.bodycontrol.SensedRecipe
import dev.loom.bodycontrol.SensedStack
import java.util.UUID

/** The vanilla server effect found for one completed bounded interaction. */
data class ServerInteractionAudit(
    val confirmed: Boolean,
    val detail: String,
    /** A claimed dig whose block the server's break event credits to another managed player. */
    val brokenByOther: Boolean = false,
)

/**
 * Opaque server state captured at the same boundary that timestamps a sense request.
 *
 * The player adapter carries this across the body-control round trip without inspecting
 * it. That lets the server implementation compare the later client report with the
 * authoritative state from the start of that reading, rather than with state an action
 * may already have changed while the request was in flight.
 */
interface SenseAuditSnapshot

/** All request, offered-state and body-result facts needed for one server cross-check. */
sealed interface ServerInteractionClaim {
    val controlRequest: BodyRequest
    val bodyOutcome: String
    val startedTick: Long

    data class Dig(
        val request: BodyRequest.Dig,
        val report: BodyResponse.DigReport,
        val offered: SensedBlock,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Collect(
        val request: BodyRequest.Collect,
        val report: BodyResponse.CollectReport,
        val offered: SensedItem,
        val itemSubject: UUID?,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Place(
        val request: BodyRequest.Place,
        val report: BodyResponse.PlaceReport,
        val offeredBlock: SensedBlock,
        val offeredStack: SensedStack,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Equip(
        val request: BodyRequest.Equip,
        val report: BodyResponse.EquipReport,
        val offeredStack: SensedStack,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Craft(
        val request: BodyRequest.Craft,
        val report: BodyResponse.CraftReport,
        val offeredRecipe: SensedRecipe,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Attack(
        val request: BodyRequest.Attack,
        val report: BodyResponse.AttackReport,
        val offeredTarget: SensedEntity,
        val targetSubject: UUID?,
        val offeredWeapon: SensedStack,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }

    data class Sleep(
        val request: BodyRequest.Sleep,
        val report: BodyResponse.SleepReport,
        val offeredBed: SensedBlock,
        override val startedTick: Long,
    ) : ServerInteractionClaim {
        override val controlRequest: BodyRequest = request
        override val bodyOutcome: String = report.outcome.name
    }
}

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

    /** Correlates each dropped-item offer to the exact server item entity when possible. */
    fun itemSubjectsOf(
        identity: PlayerIdentity,
        items: List<SensedItem>,
    ): List<UUID?> = List(items.size) { null }

    /** Captures the server half of a sense audit immediately before the request is sent. */
    fun captureSenseAudit(identity: PlayerIdentity): SenseAuditSnapshot? = null

    fun auditSense(
        identity: PlayerIdentity,
        snapshot: SenseAuditSnapshot?,
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

    fun auditInteraction(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim,
    ): ServerInteractionAudit = ServerInteractionAudit(true, "server interaction audit not installed")
}
