// The domain vocabulary that crosses the player body port.
package dev.loom.body

/** Stable identity of an agent, independent of whether it currently has a body. */
@JvmInline
value class AgentId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "agentId must not be blank" }
    }

    override fun toString(): String = value
}

/** Outcome of a [BodyPort.spawn] request. */
sealed interface BodySpawnResult {
    /** The body is ready to be perceived and acted through. */
    data class Spawned(
        val agentId: AgentId,
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

/** A body lifecycle state as the coordinator exposes it to operators. */
enum class BodyLifecycle {
    /** A process has been launched but the player is not ready yet. */
    STARTING,

    /** The body can be perceived and acted through. */
    READY,

    /** The player or its process went away without server evidence of death. */
    DISCONNECTED,

    /** An orderly stop has begun and has not finished yet. */
    STOPPING,

    /** Startup or control failed before the body became usable. */
    FAILED,

    /** The server witness has recorded terminal death. */
    DEAD,
}

/**
 * The operator-facing state of one body identity.
 *
 * [identity] is the stable server-visible identity when the flavor has one (the
 * Mineflayer username), and a flavor description otherwise. [endpoint] is present
 * only for a separately controlled process.
 */
data class BodyInspection(
    val agentId: AgentId,
    val identity: String,
    val state: BodyLifecycle,
    val endpoint: String? = null,
    val detail: String,
)

/** Outcome of applying a contract `Intent` to a body. */
sealed interface IntentOutcome {
    /**
     * The body accepted the intent; [effect] describes what it will do. [reached] says, for a
     * walk or flight, whether it arrived where it was sent; `null` for anything else.
     */
    data class Accepted(
        val effect: String,
        val reached: Boolean? = null,
        /** The items the server dropped for this step's own dig that still lie where it can see. */
        val dropped: Set<String> = emptySet(),
    ) : IntentOutcome

    /** No body exists for the agent the intent was addressed to. */
    data object NoSuchBody : IntentOutcome

    /**
     * The body understood the intent but rejected it, e.g. a target outside the
     * bounds it is willing to act on.
     *
     * [reason] is for a person and [tag] is for a count. A refusal that has nothing
     * more specific to say keeps the generic tag.
     */
    data class Rejected(
        val reason: String,
        val tag: String = EventTag.REJECTED,
        /**
         * Whether the server nonetheless saw the whole effect the step asked for, as a craft the
         * body refused after every application had been made (r19 theta body:00000310): a step
         * of its goal settled, and owed its follow-up, all the same.
         */
        val effected: Boolean = false,
    ) : IntentOutcome

    /**
     * The player body cannot carry out this intent. Distinct from [Rejected], which
     * means the operation exists but cannot be applied in the current state.
     */
    data class Unsupported(
        val action: String,
    ) : IntentOutcome
}
