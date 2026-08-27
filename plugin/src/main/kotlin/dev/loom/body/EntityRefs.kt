/*
 * Request-scoped handles for the living things a body observed.
 *
 * A decision has to be able to name one particular creature back to the body -
 * `Intent.Flee` is the first intent that can - and a description will not do it: two
 * zombies the same distance away in the same direction are different threats, and a
 * body handed "zombie" would have to guess which one the mind meant. So the body
 * mints a handle per observed creature, sends it with the observation, and the mind
 * names the handle back.
 *
 * The handle is deliberately worth nothing outside the one perception that issued it.
 * It is not an entity id and cannot be turned into one, it means something different
 * in the next perception, and a handle from any other request or any other agent is
 * refused rather than resolved. That refusal is the point of the type: a handle that
 * quietly resolved to whatever creature happened to carry it now would let a stale
 * decision act on the wrong one, and nothing in the record would say so.
 *
 * Engine-free by rule, like the rest of `dev.loom.body`: a binding names its subject
 * by `UUID`, the identity an adapter can look an entity up by without this side ever
 * holding one. Holding an entity here would also keep a creature the world has
 * finished with alive for as long as its binding was retained.
 */
package dev.loom.body

import java.util.UUID

/**
 * How many handles one request may issue.
 *
 * Derived from [PERCEPTION_MAX_OBSERVATIONS] rather than chosen, because a handle is
 * minted for an observation: a lower bound here would send a living thing the mind
 * can see and cannot name, which on the wire is indistinguishable from a block. The
 * count bound upstream is what keeps a scene small; this is the same bound, restated
 * where the minting happens so the two cannot disagree.
 */
const val MAX_REFS_PER_REQUEST: Int = PERCEPTION_MAX_OBSERVATIONS

/**
 * How many requests' worth of handles one agent keeps.
 *
 * Two rather than one because an agent can have more than one cognition in flight, and
 * each perception is its own request: remembering only the newest would make an earlier
 * one's answer unresolvable purely because something else happened first - a flee
 * refused for a reason that has nothing to do with the threat it named. Two rather
 * than many because every retained request is a longer window in
 * which a decision may act on a creature that has since moved or died, and the bound
 * is what makes this a seam rather than a cache.
 */
const val REQUESTS_REMEMBERED_PER_AGENT: Int = 2

/** The character a handle starts with, so it never reads to a model as a number. */
private const val HANDLE_PREFIX = "e"

/** Base 36, so a handle stays short in the width the contract allows it. */
private const val HANDLE_RADIX = 36

/**
 * How many base-36 characters follow [HANDLE_PREFIX].
 *
 * Seven, because the contract caps a handle at eight characters and one is the prefix.
 */
private const val HANDLE_BODY_CHARS = 7

/**
 * How many distinct handles exist: the whole of [HANDLE_BODY_CHARS] characters in base
 * [HANDLE_RADIX], which is `36^7` - a little over 78 billion. Derived rather than
 * written down, so widening the handle cannot leave it behind.
 *
 * The counter wraps here rather than overflowing the width. Two live bindings can only
 * collide if their ordinals are this far apart, and an agent retains at most
 * [REQUESTS_REMEMBERED_PER_AGENT] * [MAX_REFS_PER_REQUEST] of them minted within a
 * goal-review cadence of each other, so a wrap cannot put the same handle twice into
 * one agent's retained set.
 */
private val HANDLE_SPACE: Long = List(HANDLE_BODY_CHARS) { HANDLE_RADIX.toLong() }.reduce(Long::times)

/** One handle and the creature it names. */
data class EntityRefBinding(
    /** The handle, as it travels on the wire in `Observation.ref`. */
    val handle: String,
    /** The creature the handle names, for the adapter to look up. */
    val subject: UUID,
)

/**
 * What a handle offered by a decision resolved to.
 *
 * [Stale] and [Unknown] are kept apart because they are opposite facts about the
 * mind. [Stale] is a timing outcome and no mistake: the handle may well have been
 * valid, and the agent's next perception will issue a fresh one for the same creature.
 * [Unknown] is a mind that named a handle this request never issued - invented, or
 * carried over from an earlier message it was told not to reuse - and cannot be
 * resolved even in principle. Collapsed into one refusal, a run could not tell a
 * decision that arrived late from a decision that was wrong.
 */
sealed interface RefResolution {
    /** The handle named this creature, and still does. */
    data class Bound(val subject: UUID) : RefResolution

    /**
     * This agent holds no bindings for that request any more.
     *
     * Either the request has been evicted by newer ones, or it was never this agent's.
     * The two are indistinguishable by construction and both are refused: telling them
     * apart would mean remembering every request an agent ever made, which is the
     * unbounded growth this type exists to avoid.
     */
    data object Stale : RefResolution

    /** The request is still remembered and issued no such handle. */
    data object Unknown : RefResolution
}

/**
 * The handles a body has issued, bounded per request and per agent.
 *
 * Holds no lock. Both callers are on the server's main thread - a perception is taken
 * there and a decision is applied back there through the `MainThread` port - so a
 * handle minted off it would be a perception taken off it, which no body flavor does.
 *
 * @param maxRefsPerRequest how many handles one request may issue.
 * @param requestsRemembered how many requests' worth of handles one agent keeps.
 */
class EntityRefs(
    private val maxRefsPerRequest: Int = MAX_REFS_PER_REQUEST,
    private val requestsRemembered: Int = REQUESTS_REMEMBERED_PER_AGENT,
) {
    init {
        require(maxRefsPerRequest > 0) { "maxRefsPerRequest must be positive, got $maxRefsPerRequest" }
        require(requestsRemembered > 0) { "requestsRemembered must be positive, got $requestsRemembered" }
    }

    /**
     * Per agent, the requests it still holds handles for, oldest first.
     *
     * Keyed by agent rather than flat so [forget] can drop a whole agent at once and so
     * one agent's traffic cannot evict another's: an agent thinking often would
     * otherwise starve a quiet one of its own refs.
     */
    private val issuedByAgent: MutableMap<AgentId, LinkedHashMap<String, Map<String, UUID>>> = mutableMapOf()

    /** How many handles have ever been minted, which is what makes each one distinct. */
    private var minted: Long = 0

    /**
     * Mints one handle per subject, in the order given, and binds them to this request.
     *
     * The subjects beyond [maxRefsPerRequest] get none: the caller is expected to have
     * applied the perception's own count bound first, so a truncation here would mean
     * the two bounds had drifted.
     *
     * Issuing twice for one [requestId] replaces what it had rather than adding to it,
     * so a re-taken perception cannot leave the handles of the one it replaced
     * resolvable, and the request counts as the newest either way.
     *
     * @return the bindings, in the order the subjects were given.
     */
    fun issue(
        agent: AgentId,
        requestId: String,
        subjects: List<UUID>,
    ): List<EntityRefBinding> {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val issued =
            subjects.take(maxRefsPerRequest).map { subject ->
                EntityRefBinding(handle = mint(), subject = subject)
            }
        val forAgent = issuedByAgent.getOrPut(agent) { LinkedHashMap() }
        forAgent.remove(requestId)
        forAgent[requestId] = issued.associate { it.handle to it.subject }
        while (forAgent.size > requestsRemembered) {
            forAgent.remove(forAgent.keys.first())
        }
        return issued
    }

    /**
     * What [handle] names, for [agent] answering [requestId].
     *
     * Both the agent and the request are required, and neither is a formality: a handle
     * is only ever valid for the one agent it was minted for and the one request that
     * carried it, and resolving without checking either is how a decision would come to
     * act on another agent's creature.
     */
    fun resolve(
        agent: AgentId,
        requestId: String,
        handle: String,
    ): RefResolution {
        val forRequest = issuedByAgent[agent]?.get(requestId) ?: return RefResolution.Stale
        return forRequest[handle]?.let(RefResolution::Bound) ?: RefResolution.Unknown
    }

    /**
     * Drops every handle held for [agent].
     *
     * For an agent that has ended: Loom is permadeath, so its handles can never be
     * answered, and the bound per agent is not a bound across agents. Nothing else is
     * needed, because a live agent's own next request evicts its oldest.
     */
    fun forget(agent: AgentId) {
        issuedByAgent.remove(agent)
    }

    /**
     * The next handle, distinct from every other handle in flight.
     *
     * Sequential rather than derived from the creature, so the same creature gets a
     * different handle in the next perception. That is what makes reuse across requests
     * fail loudly as [RefResolution.Unknown] instead of quietly resolving to whatever
     * stands there now.
     */
    private fun mint(): String {
        val ordinal = minted % HANDLE_SPACE
        minted += 1
        return HANDLE_PREFIX + ordinal.toString(HANDLE_RADIX)
    }
}
