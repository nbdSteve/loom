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
 * How many handles one request may issue in one world-target namespace.
 *
 * Derived from [BODY_SENSE_MAX_OFFERS_PER_KIND] rather than chosen, because entity,
 * block, and dropped-item refs occupy separate namespaces and each kind may fill its
 * whole body list. A lower bound here would send a target the mind can see and cannot
 * name. The combined scene stays bounded separately by [PERCEPTION_MAX_OBSERVATIONS].
 */
const val MAX_REFS_PER_REQUEST: Int = BODY_SENSE_MAX_OFFERS_PER_KIND

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
 * [MAX_REQUESTS_IN_FLIGHT_PER_AGENT] * [MAX_REFS_PER_REQUEST] of them minted within
 * [REQUEST_REFS_LIFETIME_TICKS] of each other, so a wrap cannot put the same handle twice
 * into one agent's retained set.
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
     * Either its decision has already been dealt with, or its window has closed, or it was
     * never this agent's. All three are indistinguishable by construction and all are
     * refused: telling them apart would mean remembering every request an agent ever made,
     * which is the unbounded growth this type exists to avoid.
     */
    data object Stale : RefResolution

    /** The request is still remembered and issued no such handle. */
    data object Unknown : RefResolution
}

/**
 * The handles a body has issued, bounded per request and per agent.
 *
 * The window itself - how long a request stays answerable, and what closes it - is
 * [RequestWindow]'s, shared with the slot handles that ride the same perception.
 *
 * @param maxRefsPerRequest how many handles one request may issue.
 * @param lifetimeTicks how long a request's handles stay answerable.
 * @param requestsInFlight how many unanswered requests one agent may hold handles for.
 */
class EntityRefs(
    private val maxRefsPerRequest: Int = MAX_REFS_PER_REQUEST,
    lifetimeTicks: Long = REQUEST_REFS_LIFETIME_TICKS,
    requestsInFlight: Int = MAX_REQUESTS_IN_FLIGHT_PER_AGENT,
) {
    init {
        require(maxRefsPerRequest > 0) { "maxRefsPerRequest must be positive, got $maxRefsPerRequest" }
    }

    /** What each agent still holds, and every rule about when it stops holding it. */
    private val window = RequestWindow<UUID>(lifetimeTicks, requestsInFlight)

    /** How many handles have ever been minted, which is what makes each one distinct. */
    private var minted: Long = 0

    /**
     * Mints one handle per subject and binds them to this request, in one step.
     *
     * [mint] then [remember], for the caller that already knows the perception it is
     * describing will be handed to a mind.
     *
     * @return the bindings, in the order the subjects were given.
     */
    fun issue(
        agent: AgentId,
        requestId: String,
        subjects: List<UUID>,
        atTick: Long = 0,
    ): List<EntityRefBinding> = mint(subjects).also { remember(agent, requestId, it, atTick) }

    /**
     * Mints one handle per subject, in the order given, and binds them to nothing yet.
     *
     * Separate from [remember] because a perception has to carry its handles before anything
     * knows whether it will be sent: a threat scan reads the world, mints a handle per living
     * thing so it can say *which* hostile it found, and then usually suppresses the sighting -
     * at which point no mind will ever see those handles. Remembering them anyway would hold a
     * window open for a perception nobody was shown, and count it against the ceiling
     * [MAX_REQUESTS_IN_FLIGHT_PER_AGENT] puts on the ones that can still be answered. Minting
     * costs a counter and nothing else.
     *
     * The subjects beyond [maxRefsPerRequest] get none: the caller is expected to have
     * applied the perception's own count bound first, so a truncation here would mean
     * the two bounds had drifted.
     */
    fun mint(subjects: List<UUID>): List<EntityRefBinding> =
        subjects.take(maxRefsPerRequest).map { subject ->
            EntityRefBinding(handle = nextHandle(), subject = subject)
        }

    /**
     * Makes [bindings] answerable for [agent] under [requestId], as of [atTick].
     *
     * The one place the window opens, so it is the one place a handle becomes answerable.
     */
    fun remember(
        agent: AgentId,
        requestId: String,
        bindings: List<EntityRefBinding>,
        atTick: Long,
    ) = window.remember(agent, requestId, bindings.associate { it.handle to it.subject }, atTick)

    /**
     * What [handle] names, for [agent] answering [requestId] at [atTick].
     *
     * Both the agent and the request are required, and neither is a formality: a handle
     * is only ever valid for the one agent it was minted for and the one request that
     * carried it, and resolving without checking either is how a decision would come to
     * act on another agent's creature. [atTick] is required for the same kind of reason:
     * a handle is only valid while its window is open, and the window is measured in ticks.
     */
    fun resolve(
        agent: AgentId,
        requestId: String,
        handle: String,
        atTick: Long = 0,
    ): RefResolution {
        val forRequest = window.bindingsFor(agent, requestId, atTick) ?: return RefResolution.Stale
        return forRequest[handle]?.let(RefResolution::Bound) ?: RefResolution.Unknown
    }

    /** Drops what [agent] holds for [requestId], its decision having been dealt with. */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) = window.settle(agent, requestId)

    /**
     * Drops every handle held for [agent].
     *
     * For an agent that has ended: Loom is permadeath, so its handles can never be
     * answered, and the bound per agent is not a bound across agents.
     */
    fun forget(agent: AgentId) = window.forget(agent)

    /**
     * The next handle, distinct from every other handle in flight.
     *
     * Sequential rather than derived from the creature, so the same creature gets a
     * different handle in the next perception. That is what makes reuse across requests
     * fail loudly as [RefResolution.Unknown] instead of quietly resolving to whatever
     * stands there now.
     */
    private fun nextHandle(): String {
        val ordinal = minted % HANDLE_SPACE
        minted += 1
        return HANDLE_PREFIX + ordinal.toString(HANDLE_RADIX)
    }
}
