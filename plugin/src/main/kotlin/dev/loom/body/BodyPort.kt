/*
 * The inbound body port: the stable seam between Loom's domain and whatever
 * mechanism actually gives an agent a physical presence in the world.
 *
 * Everything crossing this port is a Loom domain or contract type. No
 * `net.minecraft` type, no Bukkit `Entity`/`CraftEntity`, no world handle
 * appears in any signature here. The product adapter is `dev.loom.body.player`,
 * which coordinates a bounded configured fleet of one-process-one-player bodies.
 *
 * The boundary is enforced by BodyBoundaryTest.
 */
package dev.loom.body

import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Spawns, drives, observes, and despawns agent bodies.
 *
 * The surface is deliberately four capability areas and no more: lifecycle
 * ([spawn]/[despawn]), actuation ([apply]), perception ([perceive]/[senseThreat]),
 * and the server-tick hook ([tick]). Anything an agent needs beyond these belongs in
 * the domain above the port, not in the port.
 *
 * Calls begin on the server main thread. Lifecycle, actuation and perception are
 * asynchronous because a player body crosses a loopback socket and movement may
 * legitimately take seconds; none of those waits may park a server tick. The
 * coordinator marshals every completion that mutates Loom state back onto the
 * main thread.
 */
interface BodyPort : AutoCloseable {
    /**
     * Gives [agentId] its configured body identity, and reports when it is ready.
     *
     * Returns a [BodySpawnResult.Rejected] rather than throwing when the world
     * cannot host the body: a body that fails to spawn must degrade that one
     * agent, never the server. A dead agent id is refused outright - Loom is
     * permadeath, and [Mortality] holds that rule independently of the process.
     */
    fun spawn(agentId: AgentId): CompletionStage<BodySpawnResult>

    /**
     * Removes the body for [agentId] from the world.
     *
     * Despawning is not death: it is how Loom puts a body away, and the agent
     * stays embodiable. Returns `false` if no such body was present. Idempotent:
     * despawning an already-despawned body is not an error.
     */
    fun despawn(agentId: AgentId): CompletionStage<Boolean>

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
    ): CompletionStage<IntentOutcome>

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
    ): CompletionStage<Perception?>

    /**
     * Advances every live body by one server tick.
     *
     * Called once per server tick with the current [tick]. This drives actuation
     * only - it must never invoke cognition, which is event-driven by
     * construction (the contract's `Trigger` has no per-tick variant).
     *
     * The active player adapter uses this hook to expire request-scoped handles and
     * reconcile process liveness with the server witness. It never infers death from a
     * process or socket outcome: only the server-authoritative mortality seam may make
     * the lifecycle terminal.
     */
    fun tick(tick: Long)

    /**
     * Looks around [agentId]'s body for a threat worth interrupting its mind for, and
     * answers with the perception that found one - or `null` if none was earned.
     *
     * Perception, not actuation: this only reads. It belongs to the port rather than to
     * the caller because the whole of it is engine facts - what is in view, whether the
     * body has a line to it, whether it could be walked to - and the rule applied to them
     * is engine-free and lives beside them ([ThreatWatch]). What this must *not* do is
     * call a mind: only the coordinator above the port does that, which is why the answer
     * is a perception handed back rather than a decision requested.
     *
     * The perception returned is already the perception *of* that sighting - its trigger
     * is a [Trigger.ThreatSighted] naming a creature that appears in its own observations,
     * under its own request. That is the whole reason this is one call rather than "are
     * there threats?" followed by [perceive]: a ref is meaningful only inside the request
     * that minted it, so a trigger from one capture submitted with a second capture's
     * perception would name a handle the mind cannot resolve, and every flight it prompted
     * would be refused as a threat that was never named.
     *
     * A scan that earns nothing costs no cognition. Whatever the rule refused is recorded
     * with the reason it was refused, so a run can say which of its bounds kept an agent
     * from reacting to something hunting it.
     *
     * Returns `null` for an agent with no body, exactly as [perceive] does.
     */
    fun senseThreat(agentId: AgentId): CompletionStage<Perception?>

    /** Where a dig's one pickup of its goal's own dropped item is made; see [OwnDropCollector]. */
    val pickups: OwnDropCollector get() = OwnDropCollector.NONE

    /**
     * How this body perceives damage that names the creature that dealt it; see [DamagePerception].
     * A port that cannot name it perceives the damage as [perceive] does.
     */
    val damage: DamagePerception get() = DamagePerception { agentId, trigger, _ -> perceive(agentId, trigger) }

    /** The perceptions this body holds open until their decisions settle; see [HeldPerceptions]. */
    val held: HeldPerceptions get() = HeldPerceptions.NONE

    /** Identifiers of every body currently present in the world. */
    fun liveBodies(): Set<AgentId>

    /**
     * Operator-facing state for [agentId], or `null` when the port has never owned it.
     *
     * The default keeps lightweight test doubles small. The player adapter overrides it
     * with its identity, endpoint, and exact lifecycle state.
     */
    fun inspect(agentId: AgentId): BodyInspection? =
        if (agentId in liveBodies()) {
            BodyInspection(
                agentId = agentId,
                identity = "body",
                state = BodyLifecycle.READY,
                detail = "ready",
            )
        } else {
            null
        }

    /**
     * Operator-facing state for every configured body.
     *
     * The aggregate contains only bounded deployment metadata and lifecycle
     * state. It never includes cognition prompts, transport errors, or
     * credentials. The default keeps lightweight test doubles source-compatible.
     */
    fun inspections(): List<BodyInspection> = liveBodies().sortedBy { it.value }.mapNotNull(::inspect)

    /**
     * Stops every body and releases every resource owned by the adapter.
     *
     * Plugin disable is already a synchronous terminal boundary, so implementations
     * complete their bounded shutdown before returning. The default keeps light-weight
     * test doubles source-compatible.
     */
    override fun close() = Unit
}

/** A completed body-port operation, mainly for lightweight test doubles. */
internal fun <T> completedBodyOperation(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)

/**
 * Ends a held perception without an action: its chain ended before the body was asked for
 * anything - coalesced, refused against its goal, or lost by the mind.
 *
 * A perception holds what its request minted until its decision settles, and a body holds only
 * a few at once, so one that is never acted on must still give them back (r20: 28 coalesced
 * danger perceptions held the body's four senses for 800 ticks each, and 17 hits on the three
 * agents that died went unperceived). Main thread only; a no-op for a body that holds nothing
 * under that request.
 */
fun interface HeldPerceptions {
    fun release(
        agentId: AgentId,
        requestId: String,
    )

    /**
     * Told that [perception] never reached the mind, so whatever its trigger spent on being asked
     * is given back. Main thread only; a no-op by default.
     */
    fun unsent(
        agentId: AgentId,
        perception: Perception,
    ) = Unit

    /** Told the perception the mind is now shown, classified and with its horizon, to record it so. */
    fun shown(
        agentId: AgentId,
        perception: Perception,
    ) = Unit

    /**
     * The server's own identity for the creature a threat sighting [requestId] is about, so danger
     * is tracked by the creature rather than its kind; `null` when the body cannot say.
     */
    fun dangerSubjectOf(
        agentId: AgentId,
        requestId: String,
    ): String? = null

    companion object {
        /** A body that holds nothing between a perception and its decision. */
        val NONE: HeldPerceptions = HeldPerceptions { _, _ -> }
    }
}

/**
 * Captures a perception of damage [Trigger.Damaged] that names the creature that dealt it: the
 * attacker, by the server uuid the witness saw (a projectile's shooter first), is reported however
 * many creatures stand nearer, and the trigger carries the ref this perception gives it, so the
 * mind can flee from or strike the one creature that hurt the agent (v32 plains rehearsal 15).
 */
fun interface DamagePerception {
    fun perceive(
        agentId: AgentId,
        trigger: Trigger.Damaged,
        attacker: String?,
    ): CompletionStage<Perception?>
}

/** Makes the one bounded pickup attempt a model-selected dig owes its goal's own dropped item. */
fun interface OwnDropCollector {
    /** Tries once for the [item] dig [requestId] left lying; answers its terminal tag, or `null` for none. */
    fun attempt(
        agentId: AgentId,
        requestId: String,
        item: String,
    ): CompletionStage<String?>

    companion object {
        /** A body that never tries. */
        val NONE: OwnDropCollector =
            OwnDropCollector { _, _, _ -> java.util.concurrent.CompletableFuture.completedFuture(null) }
    }
}
