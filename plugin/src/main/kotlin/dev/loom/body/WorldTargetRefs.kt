/*
 * Request-scoped handles for indexed targets in one body-control sense answer.
 *
 * Blocks and dropped items each arrive as a bounded list whose ordinal is meaningful
 * only together with the request id of that answer.  The player adapter exposes an
 * opaque ref instead, remembers the corresponding ordinal for one decision round
 * trip, and sends the ordinal back only after the ref has resolved for the same agent
 * and request.  Separate stores and prefixes keep a block ref from being
 * reinterpreted as a dropped-item ref.
 */
package dev.loom.body

/** One opaque handle and the ordinal it names in a retained sense answer. */
data class WorldTargetRefBinding(
    val handle: String,
    val index: Int,
)

/** Result of resolving one block or dropped-item handle. */
sealed interface WorldTargetResolution {
    /** This request issued the handle and it still names this list ordinal. */
    data class Bound(val index: Int) : WorldTargetResolution

    /** This agent no longer retains the request that could have issued the handle. */
    data object Stale : WorldTargetResolution

    /** The request is retained but never issued this handle in this target namespace. */
    data object Unknown : WorldTargetResolution
}

/**
 * One bounded namespace of refs onto ordinals in a body-control sense list.
 *
 * [prefix] is deliberately part of the opaque handle rather than a decoder: it keeps
 * evidence readable and prevents the independently increasing counters of two stores
 * from producing the same spelling.  Resolution still consults the store; callers
 * never parse the prefix or ordinal.
 */
class WorldTargetRefs(
    private val prefix: String,
    private val maxRefsPerRequest: Int = MAX_REFS_PER_REQUEST,
    lifetimeTicks: Long = REQUEST_REFS_LIFETIME_TICKS,
    requestsInFlight: Int = MAX_REQUESTS_IN_FLIGHT_PER_AGENT,
) {
    private val window = RequestWindow<Int>(lifetimeTicks, requestsInFlight)
    private var minted: Long = 0

    init {
        require(prefix.length == 1) { "world target prefix must be one character" }
        require(prefix[0].isLetter()) { "world target prefix must be a letter" }
        require(maxRefsPerRequest > 0) { "maxRefsPerRequest must be positive" }
    }

    /** Mints refs for the exact source-list ordinals that will be shown to cognition. */
    fun mint(indices: List<Int>): List<WorldTargetRefBinding> =
        indices.take(maxRefsPerRequest).map { index ->
            require(index >= 0) { "world target index must be non-negative" }
            WorldTargetRefBinding(nextHandle(), index)
        }

    /** Makes one perception's bindings answerable. */
    fun remember(
        agent: AgentId,
        requestId: String,
        bindings: List<WorldTargetRefBinding>,
        atTick: Long,
    ) = window.remember(agent, requestId, bindings.associate { it.handle to it.index }, atTick)

    /** Resolves only inside the exact agent/request window that issued [handle]. */
    fun resolve(
        agent: AgentId,
        requestId: String,
        handle: String,
        atTick: Long = 0,
    ): WorldTargetResolution {
        val forRequest =
            window.bindingsFor(agent, requestId, atTick) ?: return WorldTargetResolution.Stale
        return forRequest[handle]?.let(WorldTargetResolution::Bound)
            ?: WorldTargetResolution.Unknown
    }

    fun settle(
        agent: AgentId,
        requestId: String,
    ) = window.settle(agent, requestId)

    fun forget(agent: AgentId) = window.forget(agent)

    private fun nextHandle(): String {
        val ordinal = minted % HANDLE_SPACE
        minted += 1
        return prefix + ordinal.toString(HANDLE_RADIX)
    }

    private companion object {
        const val HANDLE_RADIX = 36
        const val HANDLE_BODY_CHARS = 7
        val HANDLE_SPACE: Long =
            List(HANDLE_BODY_CHARS) { HANDLE_RADIX.toLong() }.reduce(Long::times)
    }
}
