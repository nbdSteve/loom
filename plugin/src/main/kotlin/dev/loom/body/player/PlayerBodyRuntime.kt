/*
 * Shared runtime state for the Mineflayer player-body adapter.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyInspection
import dev.loom.body.BodyLifecycle
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.RequestRefs
import dev.loom.body.ThreatClock
import dev.loom.body.ThreatWatch
import dev.loom.body.VitalsWatch
import dev.loom.body.huntsPlayers
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

/** Process and control-plane dependencies owned by the player adapter. */
data class PlayerBodyInfrastructure(
    val settings: PlayerBodySettings,
    val supervisor: BodyProcessSupervisor,
    val connector: BodyControlConnector,
    val requestIds: BodyControlRequestIds,
    val io: ExecutorService,
    val scheduler: ScheduledExecutorService,
)

/** Server-authoritative dependencies used by the player adapter. */
data class PlayerBodyAuthority(
    val main: Executor,
    val registerIdentity: (PlayerIdentity) -> Unit,
    val serverState: PlayerServerState,
    val eventLog: BodyEventLog,
    val currentTick: () -> Long,
)

internal data class PlayerRuntime(
    val identity: PlayerIdentity,
    var lifecycle: BodyLifecycle,
    var detail: String,
    var process: SupervisedBodyProcess? = null,
    var sensesInFlight: Int = 0,
)

internal class PlayerBodyContext(
    val infrastructure: PlayerBodyInfrastructure,
    val authority: PlayerBodyAuthority,
) {
    var closing = false
    val journal = BodyEventJournal(authority.eventLog, authority.currentTick)
    val refs = RequestRefs()
    val vitalsWatch = VitalsWatch(journal)
    val threatWatch =
        ThreatWatch(
            clock =
                object : ThreatClock {
                    override fun tick(): Long = authority.currentTick()

                    override fun dayOrdinal(): Long = authority.serverState.dayOrdinal()
                },
            hunts = ::huntsPlayers,
        )
}

internal sealed interface StartReservation {
    data class Rejected(
        val result: BodySpawnResult,
    ) : StartReservation

    data class Reserved(
        val runtime: PlayerRuntime,
    ) : StartReservation
}

/**
 * Main-thread registry for the one configured player identity.
 *
 * It owns only identity and visible lifecycle state. Process work, perception
 * sessions and action I/O remain in their dedicated coordinators.
 */
internal class PlayerRuntimeRegistry(
    private val context: PlayerBodyContext,
) {
    private val runtimes = mutableMapOf<AgentId, PlayerRuntime>()
    private var configuredAgent: AgentId? = null

    fun reserve(agentId: AgentId): StartReservation =
        rejectionFor(agentId)?.let(StartReservation::Rejected)
            ?: reserveIdentity(agentId)

    fun runtime(agentId: AgentId): PlayerRuntime? = runtimes[agentId]

    fun isCurrent(runtime: PlayerRuntime): Boolean = runtimes[runtime.identity.agentId] === runtime

    fun ready(agentId: AgentId): PlayerRuntime? =
        runtimes[agentId]?.takeIf {
            it.lifecycle == BodyLifecycle.READY &&
                it.process?.isAlive() == true
        }

    fun all(): List<PlayerRuntime> = runtimes.values.toList()

    fun liveBodies(): Set<AgentId> =
        runtimes.values
            .filter { it.lifecycle == BodyLifecycle.READY && it.process?.isAlive() == true }
            .mapTo(mutableSetOf()) { it.identity.agentId }

    fun inspect(agentId: AgentId): BodyInspection? =
        runtimes[agentId]?.let { runtime ->
            BodyInspection(
                agentId = agentId,
                identity = "${runtime.identity.username} (${runtime.identity.uuid})",
                state = runtime.lifecycle,
                endpoint = context.infrastructure.settings.controlEndpoint.toString(),
                detail = runtime.detail,
            )
        }

    fun beginClosing(): List<PlayerRuntime> {
        context.closing = true
        return all()
    }

    private fun rejectionFor(agentId: AgentId): BodySpawnResult? {
        val journal = context.journal
        return when {
            context.closing ->
                journal.reject(
                    agentId,
                    "the player-body runtime is stopping",
                    EventTag.BODY_START_FAILED,
                )
            context.authority.serverState.isDead(agentId) ->
                journal.reject(
                    agentId,
                    "this agent has died, and Loom agents do not return",
                    EventTag.HAS_DIED,
                )
            configuredAgent != null && configuredAgent != agentId ->
                journal.reject(
                    agentId,
                    "this runtime already owns its one configured player identity",
                    EventTag.BODY_START_FAILED,
                )
            runtimes[agentId]?.lifecycle in
                setOf(BodyLifecycle.STARTING, BodyLifecycle.READY, BodyLifecycle.STOPPING) ->
                journal.reject(
                    agentId,
                    "a body already exists for this agent",
                    EventTag.ALREADY_EMBODIED,
                )
            else -> null
        }
    }

    private fun reserveIdentity(agentId: AgentId): StartReservation {
        val identity =
            try {
                playerIdentityOf(agentId).also(context.authority.registerIdentity)
            } catch (_: IllegalArgumentException) {
                return StartReservation.Rejected(
                    context.journal.reject(
                        agentId,
                        "the player identity could not be registered",
                        EventTag.BODY_START_FAILED,
                    ),
                )
            }
        configuredAgent = agentId
        val runtime =
            PlayerRuntime(
                identity = identity,
                lifecycle = BodyLifecycle.STARTING,
                detail = "starting player process",
            )
        runtimes[agentId] = runtime
        return StartReservation.Reserved(runtime)
    }
}
