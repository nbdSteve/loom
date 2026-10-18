/*
 * BodyPort adapter for a bounded fleet of independent Mineflayer players.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyInspection
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.HeldPerceptions
import dev.loom.body.IntentOutcome
import dev.loom.body.ThreatWatch
import dev.loom.body.completedBodyOperation
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Fleet adapter over isolated copies of the Mineflayer player runtime.
 *
 * This remains the sole concrete [BodyPort]. Every configured identity receives
 * one [PlayerBodyInstance], and therefore its own refs, sessions, threat/vitals
 * watches, process supervisor, endpoint, working directory, I/O pool, scheduler,
 * lifecycle, and shutdown state. The fleet only routes calls and bounds startup
 * concurrency; one member has no stateful path into another.
 */
class PlayerBodyPort(
    infrastructures: Collection<PlayerBodyInfrastructure>,
    private val authority: PlayerBodyAuthority,
    private val startupConcurrency: Int,
) : BodyPort {
    private val journal = BodyEventJournal(authority.eventLog, authority.currentTick)
    private val instances: Map<AgentId, PlayerBodyInstance>
    private val lifecycleLock = Any()
    private var closing = false

    init {
        require(infrastructures.isNotEmpty()) { "at least one player body infrastructure is required" }
        require(startupConcurrency in 1..infrastructures.size) {
            "startupConcurrency must be in 1..${infrastructures.size}"
        }
        val ordered = infrastructures.sortedBy { it.identity.agentId.value }
        require(ordered.map { it.identity.agentId }.distinct().size == ordered.size) {
            "player body infrastructure contains a duplicate agent id"
        }
        ordered.forEach { authority.registerIdentity(it.identity) }
        instances =
            ordered.associate { infrastructure ->
                infrastructure.identity.agentId to PlayerBodyInstance(infrastructure, authority)
            }
    }

    /** The one automatic configured-fleet startup, exposed for aggregate reporting. */
    val configuredStartup: CompletionStage<PlayerFleetStartup> by lazy {
        FleetStarter(instances.values.toList(), startupConcurrency).start()
    }

    override fun spawn(agentId: AgentId): CompletionStage<BodySpawnResult> =
        instances[agentId]?.spawn(agentId)
            ?: completedBodyOperation(
                journal.reject(
                    agentId,
                    "this agent is not in the configured player-body fleet",
                    EventTag.BODY_START_FAILED,
                ),
            )

    override fun despawn(agentId: AgentId): CompletionStage<Boolean> =
        instances[agentId]?.despawn(agentId) ?: completedBodyOperation(false)

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletionStage<IntentOutcome> =
        instances[agentId]?.apply(agentId, intent, requestId)
            ?: completedBodyOperation(IntentOutcome.NoSuchBody)

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletionStage<Perception?> = instances[agentId]?.perceive(agentId, trigger) ?: completedBodyOperation(null)

    override fun senseThreat(agentId: AgentId): CompletionStage<Perception?> =
        instances[agentId]?.senseThreat(agentId) ?: completedBodyOperation(null)

    override val held: HeldPerceptions =
        object : HeldPerceptions {
            override fun release(
                agentId: AgentId,
                requestId: String,
            ) {
                instances[agentId]?.release(agentId, requestId)
            }

            override fun unsent(
                agentId: AgentId,
                perception: Perception,
            ) {
                (perception.trigger as? Trigger.ThreatSighted)?.let {
                    instances[agentId]?.threatWatch?.refund(
                        agentId,
                        it,
                    )
                }
            }
        }

    override fun tick(tick: Long) {
        instances.values.forEach { it.tick(tick) }
    }

    override fun liveBodies(): Set<AgentId> = instances.values.flatMapTo(mutableSetOf()) { it.liveBodies() }

    override fun inspect(agentId: AgentId): BodyInspection? = instances[agentId]?.inspect(agentId)

    override fun inspections(): List<BodyInspection> = instances.values.mapNotNull { it.inspect(it.agentId) }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closing) return
            closing = true
        }
        instances.values.sortedBy { it.agentId.value }.forEach(PlayerBodyInstance::close)
    }
}

/** Per-agent startup results, retained so an aggregate cannot hide one failure. */
data class PlayerFleetStartup(
    val results: Map<AgentId, BodySpawnResult>,
) {
    val ready: Int get() = results.values.count { it is BodySpawnResult.Spawned }
    val rejected: Int get() = results.size - ready
}

private class PlayerBodyInstance(
    private val infrastructure: PlayerBodyInfrastructure,
    authority: PlayerBodyAuthority,
) {
    private val context = PlayerBodyContext(infrastructure, authority)
    private val registry = PlayerRuntimeRegistry(context)
    private val sessions = PlayerSenseSessions(context)
    private val transitions = PlayerLifecycleTransitions(context, sessions)
    private val health = PlayerHealthMonitor(context, registry, transitions)
    private val lifecycle = PlayerLifecycle(context, registry, sessions, transitions, health)
    private val perception =
        PlayerPerception(
            context,
            registry,
            sessions,
            transitions,
            PlayerSceneTranslator(context),
        )
    private val actuation =
        PlayerActuation(
            context,
            registry,
            sessions,
            transitions,
            PlayerActionPreparer(context, speech = PlayerSpeech(context, authority.speechClaims)),
            PlayerEvidenceRecorder(context),
        )

    val agentId: AgentId get() = infrastructure.identity.agentId

    /** This agent's threat watch, so a sighting that never reached the mind is refunded. */
    val threatWatch: ThreatWatch get() = context.threatWatch

    fun spawn(agentId: AgentId): CompletionStage<BodySpawnResult> = lifecycle.spawn(agentId)

    fun despawn(agentId: AgentId): CompletionStage<Boolean> = lifecycle.despawn(agentId)

    fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletionStage<IntentOutcome> = actuation.apply(agentId, intent, requestId)

    fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletionStage<Perception?> = perception.perceive(agentId, trigger)

    fun senseThreat(agentId: AgentId): CompletionStage<Perception?> = perception.senseThreat(agentId)

    fun release(
        agentId: AgentId,
        requestId: String,
    ) = sessions.settle(agentId, requestId, expected = null)

    fun tick(tick: Long) {
        lifecycle.tick(tick)
    }

    fun liveBodies(): Set<AgentId> = registry.liveBodies()

    fun inspect(agentId: AgentId): BodyInspection? = registry.inspect(agentId)

    fun close() {
        lifecycle.close()
    }
}

/** Starts at most [limit] members at once and never lets one failure stop the rest. */
private class FleetStarter(
    instances: List<PlayerBodyInstance>,
    private val limit: Int,
) {
    private val waiting = ArrayDeque(instances)
    private val results = linkedMapOf<AgentId, BodySpawnResult>()
    private val answer = CompletableFuture<PlayerFleetStartup>()
    private var active = 0

    fun start(): CompletionStage<PlayerFleetStartup> {
        pump()
        return answer
    }

    private fun pump() {
        val launches = mutableListOf<PlayerBodyInstance>()
        var completed: PlayerFleetStartup? = null
        synchronized(this) {
            while (active < limit && waiting.isNotEmpty()) {
                launches += waiting.removeFirst()
                active += 1
            }
            if (active == 0 && waiting.isEmpty()) {
                completed = PlayerFleetStartup(results.toMap())
            }
        }
        completed?.let(answer::complete)
        launches.forEach { instance ->
            instance.spawn(instance.agentId).whenComplete { result, _ ->
                synchronized(this) {
                    results[instance.agentId] =
                        result
                            ?: BodySpawnResult.Rejected(
                                instance.agentId,
                                "player body startup failed unexpectedly",
                            )
                    active -= 1
                }
                pump()
            }
        }
    }
}
