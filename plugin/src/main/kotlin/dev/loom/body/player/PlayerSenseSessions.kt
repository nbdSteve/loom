/*
 * Request-scoped Mineflayer sense sessions and their handles.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.EntityRefBinding
import dev.loom.body.InventorySlotBinding
import dev.loom.body.REQUEST_REFS_LIFETIME_TICKS
import dev.loom.body.RequestRefBindings
import dev.loom.body.ThreatCandidate
import dev.loom.body.WorldTargetRefBinding
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Observation
import dev.loom.contract.Perception
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

internal data class RawSense(
    val identity: PlayerIdentity,
    val capturedTick: Long,
    val auditSnapshot: SenseAuditSnapshot?,
    val connection: BodyControlConnection,
    val report: BodyResponse.SenseReport,
)

internal data class PlayerScene(
    val raw: RawSense,
    val creatureBindings: List<EntityRefBinding>,
    val blockBindings: List<WorldTargetRefBinding>,
    val droppedItemBindings: List<WorldTargetRefBinding>,
    val slotBindings: List<InventorySlotBinding>,
    val entityIndex: Map<UUID, Int>,
    val droppedItemSubjects: Map<Int, UUID>,
    val surroundings: List<Observation>,
    val surroundingsOmitted: Long,
    val candidates: List<ThreatCandidate>,
    val selfState: SelfState,
) {
    fun perception(trigger: Trigger): Perception =
        Perception(
            requestId = raw.report.requestId,
            agentId = raw.identity.agentId.value,
            trigger = trigger,
            importance = DecisionImportance.ROUTINE,
            capturedTick = raw.capturedTick,
            selfState = selfState,
            surroundings = surroundings,
            surroundingsOmitted = surroundingsOmitted,
        )
}

internal class HeldSense(
    val raw: RawSense,
    val entityIndex: Map<UUID, Int>,
    val droppedItemSubjects: Map<Int, UUID>,
    val rememberedAt: Long,
) {
    private val closed = AtomicBoolean()
    var keepalive: ScheduledFuture<*>? = null

    fun stopKeepalive() {
        keepalive?.cancel(false)
        keepalive = null
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopKeepalive()
        raw.connection.close()
    }

    fun isClosed(): Boolean = closed.get()
}

/** Owns the bounded memory of sense connections and request-scoped refs. */
internal class PlayerSenseSessions(
    private val context: PlayerBodyContext,
) {
    private val held = mutableMapOf<AgentId, LinkedHashMap<String, HeldSense>>()

    fun openCount(agentId: AgentId): Int = held[agentId]?.size ?: 0

    fun get(
        agentId: AgentId,
        requestId: String,
    ): HeldSense? = held[agentId]?.get(requestId)

    fun remember(scene: PlayerScene): HeldSense {
        val agentId = scene.raw.identity.agentId
        val requestId = scene.raw.report.requestId
        context.refs.remember(
            agent = agentId,
            requestId = requestId,
            bindings =
                RequestRefBindings(
                    creatures = scene.creatureBindings,
                    blocks = scene.blockBindings,
                    droppedItems = scene.droppedItemBindings,
                    slots = scene.slotBindings,
                ),
            atTick = scene.raw.capturedTick,
        )
        return HeldSense(
            scene.raw,
            scene.entityIndex,
            scene.droppedItemSubjects,
            scene.raw.capturedTick,
        ).also { sense ->
            held.getOrPut(agentId) { LinkedHashMap() }[requestId] = sense
        }
    }

    fun settle(
        agentId: AgentId,
        requestId: String,
        expected: HeldSense?,
    ) {
        val requests = held[agentId]
        val current = requests?.get(requestId)
        if (expected == null || current === expected) {
            requests?.remove(requestId)?.close()
            if (requests?.isEmpty() == true) held.remove(agentId)
            context.refs.settle(agentId, requestId)
        }
    }

    fun release(agentId: AgentId) {
        held.remove(agentId)?.forEach { (requestId, sense) ->
            sense.close()
            context.refs.settle(agentId, requestId)
        }
    }

    fun expire(tick: Long) {
        for ((agentId, requests) in held.toMap()) {
            for ((requestId, sense) in requests.toMap()) {
                if (tick - sense.rememberedAt >= REQUEST_REFS_LIFETIME_TICKS) {
                    settle(agentId, requestId, sense)
                }
            }
        }
    }
}
