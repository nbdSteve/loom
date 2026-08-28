/*
 * The domain vocabulary that crosses the body port.
 *
 * These types exist so the port can name a place, a body, and the result of
 * driving one without leaking an engine type. `BodyLocation` in particular is
 * why the port needs no Bukkit `Location`: it is the minimum a body needs to be
 * placed, expressed as owned primitives plus a world name.
 */
package dev.loom.body

/** Stable identity of an agent, independent of whether it currently has a body. */
@JvmInline
value class AgentId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "agentId must not be blank" }
        // Reserved for world-level log records. Refused here so no agent can ever be
        // created whose events are indistinguishable from the world's own.
        require(value != WORLD_AGENT_ID) { "agentId '$value' is reserved for world-level records" }
    }

    override fun toString(): String = value
}

/**
 * A place in the world, named without an engine type.
 *
 * [world] is the world's name rather than a world handle, so holding a
 * [BodyLocation] grants no access to world state.
 */
data class BodyLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(world.isNotBlank()) { "world must not be blank" }
    }
}

/** Outcome of a [BodyPort.spawn] request. */
sealed interface BodySpawnResult {
    /** The body is in the world and will tick. */
    data class Spawned(
        val agentId: AgentId,
        val at: BodyLocation,
    ) : BodySpawnResult

    /**
     * The body could not be placed. Carries a human-readable [reason] for the
     * append-only log; a rejected spawn degrades one agent, never the server.
     */
    data class Rejected(
        val agentId: AgentId,
        val reason: String,
    ) : BodySpawnResult
}

/** Outcome of applying a contract `Intent` to a body. */
sealed interface IntentOutcome {
    /** The body accepted the intent; [effect] describes what it will do. */
    data class Accepted(
        val effect: String,
    ) : IntentOutcome

    /** No body exists for the agent the intent was addressed to. */
    data object NoSuchBody : IntentOutcome

    /**
     * The body understood the intent but rejected it, e.g. a target outside the
     * bounds it is willing to act on.
     *
     * [reason] is for a person and [tag] is for a count. One intent can be refused several
     * distinct ways - a meal by six of them - and a run that read them all as `rejected`
     * could not say whether its agents had nothing edible or were already full, since the
     * difference would be in the prose. A refusal that has nothing more specific to say
     * keeps the generic tag, so only the refusals worth counting apart carry one.
     */
    data class Rejected(
        val reason: String,
        val tag: String = EventTag.REJECTED,
    ) : IntentOutcome

    /**
     * This body flavor cannot carry out this intent at all. Distinct from
     * [Rejected]: a different adapter might support it.
     */
    data class Unsupported(
        val action: String,
    ) : IntentOutcome
}
