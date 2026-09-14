/*
 * How long a handle a body issued stays answerable.
 *
 * A handle is worth nothing outside the one request that minted it, so something has to
 * decide when that request is over. The first rule here counted requests - an agent kept
 * the newest two - and it was wrong in the one scene the eat path exists for: hungry and
 * hunted at once. A threat scan runs every second for every living agent, and a sighting
 * it *does* earn is a perception handed to a mind, so two earned sightings inside one
 * cognition round trip evicted the goal review whose decision was still in flight. The
 * meal it named then arrived as `eatSlotForgotten` and the flight as
 * `retreatThreatForgotten`: an agent with food in its hands, and nothing in either
 * refusal saying the handle had been valid when the mind chose it.
 *
 * So the window is bounded by *time* rather than by count, and closed by the answer when
 * one arrives. A request stays answerable until its decision has been dealt with
 * ([RequestWindow.settle]) or [REQUEST_REFS_LIFETIME_TICKS] have passed, whichever comes
 * first, and no amount of traffic in between can take it away. That is what makes the
 * bound a statement about the mind Loom is waiting for rather than about how busy the
 * body happened to be.
 *
 * The ceiling is still there ([MAX_REQUESTS_IN_FLIGHT_PER_AGENT]) and is deliberately
 * generous: it is a safety valve against unbounded growth, not the rule. Reaching it
 * means an agent has more perceptions outstanding than any mind could be answering, and
 * evicting the oldest is then the least wrong thing left to do.
 *
 * Stated once and shared by both ref stores, because a creature handle and a slot handle
 * ride the same perception and are answered by the same decision: two copies of this rule
 * could drift, and a request whose creatures were still answerable and whose slots were
 * not would resolve a flight and refuse a meal off the very same perception.
 */
package dev.loom.body

/**
 * How long a request's handles stay answerable, in ticks.
 *
 * Sized to outlast a whole cognition round trip: `CognitionSettings.timeout` gives a
 * request 30 s before the bridge gives up on it, which is 600 ticks at twenty a second,
 * and this is that with room for the tick the perception was taken on and the tick its
 * answer is applied on. A handle that expired first would refuse a decision Loom itself
 * was still waiting for, which is the failure this bound exists to rule out.
 *
 * Counted in ticks rather than wall-clock time so the same tick sequence answers the
 * same handle however fast it is replayed, and a paused server expires nothing. It is
 * a constant here rather than derived from
 * `dev.loom.mind`, because `dev.loom.body` may not depend on that package -
 * `CognitionSettingsTest` pins the two against each other from the side that can see both.
 */
const val REQUEST_REFS_LIFETIME_TICKS: Long = 800

/**
 * The shortest interval at which one agent can be handed a perception, in ticks.
 *
 * A threat scan, which reads the world for every living agent every `THREAT_SCAN_CADENCE` -
 * one second, so twenty ticks. Every other source is slower: a goal review comes once per
 * `DEFAULT_GOAL_REVIEW_CADENCE_TICKS`, and `/loombody think` is an operator typing.
 *
 * The tempting bound is `THREAT_DEBOUNCE_TICKS`, and it is the wrong one: a debounce is a
 * floor on *repeats of an unchanged threat*, and `ThreatWatch.mayLookAgain` deliberately
 * re-fires inside it whenever a hostile steps in or out of line of sight or crosses inside
 * `PIVOTAL_THREAT_DISTANCE_BLOCKS` - which an ordinary mob pathing round a fence does on
 * every scan. So a hunted agent can earn a sighting, and therefore a perception, at the
 * scan cadence and not at the debounce, which is five times more often.
 *
 * A body-side constant in ticks for the reason [REQUEST_REFS_LIFETIME_TICKS] is one:
 * `dev.loom.body` may not depend on `dev.loom.mind`, where the cadence is a `Duration`.
 * `CognitionSettingsTest` pins the two against each other from the side that sees both.
 */
const val FASTEST_PERCEPTION_CADENCE_TICKS: Long = 20

/**
 * How many unanswered requests one agent may hold handles for at once.
 *
 * A safety valve rather than a policy, and derived so that it stays one. What normally
 * closes a window is the decision arriving, or [REQUEST_REFS_LIFETIME_TICKS] running out;
 * this only bites when an agent has more perceptions outstanding than a lifetime can even
 * produce, which means its decisions are not coming back at all. Evicting the oldest is
 * then the least wrong thing left to do.
 *
 * The count is the lifetime divided by [FASTEST_PERCEPTION_CADENCE_TICKS], plus one for a
 * goal review landing on a tick a scan already used, plus the one being remembered now.
 * Written down instead, the number was justified by the debounce and was five times too
 * small in the worst case, leaving a margin of two requests that nothing guarded: raising
 * the bridge deadline - and with it the lifetime - would have silently brought back the
 * eviction this file exists to prevent, a goal review's window dropped while its decision
 * was still in flight. Derived, the ceiling follows the lifetime by construction.
 */
const val MAX_REQUESTS_IN_FLIGHT_PER_AGENT: Int =
    (REQUEST_REFS_LIFETIME_TICKS / FASTEST_PERCEPTION_CADENCE_TICKS).toInt() + 2

/**
 * The handles one body has issued and not yet answered, per agent and per request.
 *
 * Holds no lock. Both callers are on the server's main thread - a perception is taken
 * there and a decision is applied back there through the `MainThread` port - so a handle
 * minted off it would be a perception taken off it, which the player adapter never does.
 *
 * @param T what a handle names: a creature's identity, or a slot of the body's own inventory.
 * @param lifetimeTicks how long a request's handles stay answerable.
 * @param maxInFlight how many unanswered requests one agent may hold handles for.
 */
internal class RequestWindow<T>(
    private val lifetimeTicks: Long = REQUEST_REFS_LIFETIME_TICKS,
    private val maxInFlight: Int = MAX_REQUESTS_IN_FLIGHT_PER_AGENT,
) {
    init {
        require(lifetimeTicks > 0) { "lifetimeTicks must be positive, got $lifetimeTicks" }
        require(maxInFlight > 0) { "maxInFlight must be positive, got $maxInFlight" }
    }

    /** One request's handles and the tick they became answerable on. */
    private class Held<T>(
        val bindings: Map<String, T>,
        val rememberedAt: Long,
    )

    /**
     * Per agent, the requests it still holds handles for, oldest remembered first.
     *
     * Keyed by agent rather than flat so [forget] can drop a whole agent at once and so one
     * agent's traffic cannot evict another's: an agent thinking often would otherwise starve
     * a quiet one of its own refs.
     */
    private val heldByAgent: MutableMap<AgentId, LinkedHashMap<String, Held<T>>> = mutableMapOf()

    /**
     * Makes [bindings] answerable for [agent] under [requestId], as of [atTick].
     *
     * Remembering twice for one [requestId] replaces what it had rather than adding to it, so a
     * re-taken perception cannot leave the handles of the one it replaced answerable, and the
     * request's clock restarts from [atTick] either way.
     */
    fun remember(
        agent: AgentId,
        requestId: String,
        bindings: Map<String, T>,
        atTick: Long,
    ) {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val forAgent = heldByAgent.getOrPut(agent) { LinkedHashMap() }
        forAgent.remove(requestId)
        forAgent[requestId] = Held(bindings, atTick)
        // Expiry first, so a request that has merely outlived its window is dropped in
        // preference to one that is still inside it. The ceiling then bites only on requests
        // that could all still be answered, which is the case it is a safety valve for.
        forAgent.entries.removeAll { expired(it.value, atTick) }
        while (forAgent.size > maxInFlight) {
            forAgent.remove(forAgent.keys.first())
        }
    }

    /**
     * What [agent] still holds for [requestId] as of [atTick], or null if it holds nothing.
     *
     * Null covers three facts a store may not tell apart: the request was never this agent's,
     * its answer has already been dealt with, and its window has closed. Telling the first
     * from the others would mean remembering every request an agent ever made, which is the
     * unbounded growth this type exists to avoid.
     *
     * Expiry is answered here rather than only swept on [remember], so a decision that
     * arrives after its window has closed is refused whether or not anything else has
     * happened since - the window is a promise about time, and a quiet agent must not get a
     * longer one than a busy one.
     */
    fun bindingsFor(
        agent: AgentId,
        requestId: String,
        atTick: Long,
    ): Map<String, T>? = heldByAgent[agent]?.get(requestId)?.takeUnless { expired(it, atTick) }?.bindings

    /**
     * Drops what [agent] holds for [requestId], its decision having been dealt with.
     *
     * One perception gets one decision, so there is nothing left to answer: holding the
     * handles any longer would only widen the window in which a repeat of that decision
     * could act on a creature that has since moved or a slot that now holds something else.
     * Called for a request whether its intent was accepted or refused, because either way
     * the answer has arrived.
     */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) {
        heldByAgent[agent]?.let { forAgent ->
            forAgent.remove(requestId)
            if (forAgent.isEmpty()) heldByAgent.remove(agent)
        }
    }

    /**
     * Drops every handle held for [agent].
     *
     * For an agent that has ended: Loom is permadeath, so no handle of its can ever be
     * answered again.
     */
    fun forget(agent: AgentId) {
        heldByAgent.remove(agent)
    }

    /**
     * Whether [held] has outlived its window by [atTick].
     *
     * A tick before the one it was remembered on cannot expire it: the tick a body reads is
     * the server's own and only moves forward, so a smaller one is a test asking about the
     * past rather than a window that has closed.
     */
    private fun expired(
        held: Held<T>,
        atTick: Long,
    ): Boolean = atTick - held.rememberedAt >= lifetimeTicks
}
