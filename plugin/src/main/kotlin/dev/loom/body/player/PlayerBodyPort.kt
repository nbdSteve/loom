/*
 * BodyPort adapter for one persistent Mineflayer-controlled player.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyInspection
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletionStage

/**
 * Hard-cut runtime adapter for the Mineflayer player body.
 *
 * This class is deliberately only the inbound port. Lifecycle, perception
 * sessions, actuation and evidence each have one coordinator below it, sharing
 * the same main-thread-owned runtime state.
 */
class PlayerBodyPort(
    infrastructure: PlayerBodyInfrastructure,
    authority: PlayerBodyAuthority,
) : BodyPort {
    private val context = PlayerBodyContext(infrastructure, authority)
    private val registry = PlayerRuntimeRegistry(context)
    private val sessions = PlayerSenseSessions(context)
    private val transitions = PlayerLifecycleTransitions(context, sessions)
    private val lifecycle = PlayerLifecycle(context, registry, sessions, transitions)
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
            PlayerActionPreparer(context),
            PlayerEvidenceRecorder(context),
        )

    override fun spawn(agentId: AgentId): CompletionStage<BodySpawnResult> = lifecycle.spawn(agentId)

    override fun despawn(agentId: AgentId): CompletionStage<Boolean> = lifecycle.despawn(agentId)

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): CompletionStage<IntentOutcome> = actuation.apply(agentId, intent, requestId)

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): CompletionStage<Perception?> = perception.perceive(agentId, trigger)

    override fun senseThreat(agentId: AgentId): CompletionStage<Perception?> = perception.senseThreat(agentId)

    override fun tick(tick: Long) {
        lifecycle.tick(tick)
    }

    override fun liveBodies(): Set<AgentId> = registry.liveBodies()

    override fun inspect(agentId: AgentId): BodyInspection? = registry.inspect(agentId)

    override fun close() {
        lifecycle.close()
    }
}
