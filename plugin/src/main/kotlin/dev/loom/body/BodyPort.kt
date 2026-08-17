/*
 * The inbound body port: the stable seam between Loom's domain and whatever
 * mechanism actually gives an agent a physical presence in the world.
 *
 * Everything crossing this port is a Loom domain or contract type. No
 * `net.minecraft` type, no Bukkit `Entity`/`CraftEntity`, no world handle
 * appears in any signature here - that is what makes the body flavor
 * replaceable. Today exactly one adapter implements it (`dev.loom.body.nms`, a
 * custom NMS class on a vanilla entity type); a different flavor would be a new
 * implementation of this interface and nothing else.
 *
 * The boundary is enforced, not merely documented: BodyBoundaryTest fails the
 * build if any package outside the adapter reaches for `net.minecraft` or the
 * concrete entity class.
 */
package dev.loom.body

import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger

/**
 * Spawns, drives, observes, and despawns agent bodies.
 *
 * The surface is deliberately four capability areas and no more: lifecycle
 * ([spawn]/[despawn]), actuation ([apply]), perception ([perceive]), and the
 * server-tick hook ([tick]). Anything an agent needs beyond these belongs in the
 * domain above the port, not in the port.
 *
 * Implementations are **not** thread-safe and every method must be called on the
 * server main thread. Cognition never calls this port directly: it hands back a
 * [dev.loom.contract.Decision] which the domain marshals onto the main thread
 * before calling [apply].
 */
interface BodyPort {
    /**
     * Gives [agentId] a body at [at], and reports how that went.
     *
     * Returns a [BodySpawnResult.Rejected] rather than throwing when the world
     * cannot host the body: a body that fails to spawn must degrade that one
     * agent, never the server. A dead agent id is refused outright - Loom is
     * permadeath, and [Mortality] holds that rule for every body flavor.
     */
    fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult

    /**
     * Removes the body for [agentId] from the world.
     *
     * Despawning is not death: it is how Loom puts a body away, and the agent
     * stays embodiable. Returns `false` if no such body was present. Idempotent:
     * despawning an already-despawned body is not an error.
     */
    fun despawn(agentId: AgentId): Boolean

    /**
     * Applies a contract [Intent] to the body for [agentId].
     *
     * The body validates the intent and decides how (or whether) to carry it
     * out; the mind names an intent and never mutates world state itself. An
     * intent the adapter cannot act on yields [IntentOutcome.Unsupported] rather
     * than an exception.
     *
     * [requestId] is the perception this intent answers, so the record of the
     * action joins the record of the decision that caused it. It is `null` for an
     * intent no perception asked for - one Loom drove itself, from a command - and
     * a record that claimed a request id it did not have would be worse than one
     * without it.
     */
    fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String? = null,
    ): IntentOutcome

    /**
     * Captures a bounded [Perception] of the body for [agentId], attributed to
     * [trigger].
     *
     * The snapshot is taken by value: it carries summarized observations and
     * owned primitives, never a live world handle, so the mind has no path back
     * into world state. Returns `null` if [agentId] has no body.
     */
    fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception?

    /**
     * Advances every live body by one server tick.
     *
     * Called once per server tick with the current [tick]. This drives actuation
     * only - it must never invoke cognition, which is event-driven by
     * construction (the contract's `Trigger` has no per-tick variant).
     */
    fun tick(tick: Long)

    /** Identifiers of every body currently present in the world. */
    fun liveBodies(): Set<AgentId>
}
