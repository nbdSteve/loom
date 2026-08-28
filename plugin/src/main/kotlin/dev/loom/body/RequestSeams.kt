/*
 * The request-scoped seams a body acts through.
 *
 * A body may not resolve a handle by itself. What a handle means is scoped to the one
 * agent and the one request that minted it, and neither is a body's to know: a body that
 * could turn a name into a creature or a slot would let a stale decision act on whatever
 * holds that name now, and nothing in the record would say so.
 *
 * So the port keeps the handles ([RequestRefs]) and hands a body exactly the questions
 * one applied intent may ask ([IntentSeam]), already bound to the agent and the request
 * that asked them. Bundling them is not tidying: it is what makes it impossible for an
 * adapter to pass one agent's ref store while answering for another's reserve.
 */
package dev.loom.body

/**
 * Every handle a body has issued: one store per kind of thing a handle can name.
 *
 * Two stores rather than one, because a creature handle and a slot handle name different
 * kinds of thing. One store would let a decision eat the handle of a zombie, or run from
 * a stack of bread, if the two handle spaces ever met - and they would, since both mint
 * from a counter.
 */
class RequestRefs(
    /** The living things a body has observed. */
    val creatures: EntityRefs = EntityRefs(),
    /** The occupied slots of a body's own inventory. */
    val slots: InventoryRefs = InventoryRefs(),
) {
    /**
     * The questions applying one intent for [agent] under [requestId] at [atTick] may ask.
     *
     * [reserve] is passed in rather than held, because a reserve belongs to the agent and
     * outlives every body it has: only the port can answer for one. [atTick] is what a ref
     * window is measured in, so a handle whose window has closed is refused at the moment
     * the question is asked rather than whenever something else next happened.
     */
    fun seamFor(
        agent: AgentId,
        requestId: String,
        reserve: EnergyReserve,
        atTick: Long,
    ): IntentSeam =
        IntentSeam(
            threatOf = { handle -> creatures.resolve(agent, requestId, handle, atTick) },
            slotOf = { handle -> slots.resolve(agent, requestId, handle, atTick) },
            reserve = reserve,
        )

    /**
     * Makes the handles one perception carried resolvable for [agent] under [requestId].
     *
     * Both kinds at once, and that is the invariant rather than tidiness: one perception carries
     * creature handles and slot handles together, and the decision answering it may name either,
     * so a request whose creatures were remembered and whose slots were not would resolve a
     * flight and refuse a meal off the very same perception.
     *
     * Minting is separate from remembering, and this is the seam that decides *when*. A window
     * is only opened for a perception a mind is actually given: a threat scan reads the world
     * every second and mints handles to say which hostile it found, and the scans whose sighting
     * is suppressed reach no mind at all. A window held open for one of those answers nothing,
     * and counts against the ceiling on the requests that can still be answered.
     *
     * How long the window then stays open, and what closes it, is [RequestWindow]'s: until
     * [settle] or [REQUEST_REFS_LIFETIME_TICKS] from [atTick], whichever comes first.
     */
    fun remember(
        agent: AgentId,
        requestId: String,
        creatures: List<EntityRefBinding>,
        slots: List<InventorySlotBinding>,
        atTick: Long,
    ) {
        this.creatures.remember(agent, requestId, creatures, atTick)
        this.slots.remember(agent, requestId, slots, atTick)
    }

    /**
     * Drops what [agent] holds for [requestId], its decision having been dealt with.
     *
     * One perception gets one decision, so a request whose intent has been applied - accepted
     * or refused - has nothing left to answer. Closing it here rather than waiting for the
     * lifetime to run out is what keeps the window as narrow as the round trip actually was.
     *
     * Both kinds at once, for the reason [remember] commits both: one decision answers one
     * perception, and that perception carried handles of both kinds.
     */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) {
        creatures.settle(agent, requestId)
        slots.settle(agent, requestId)
    }

    /**
     * Drops every handle held for [agent].
     *
     * For an agent that has ended: Loom is permadeath, so no handle of its can ever be
     * answered again.
     */
    fun forget(agent: AgentId) {
        creatures.forget(agent)
        slots.forget(agent)
    }
}

/**
 * What a body may ask while it carries out one intent.
 *
 * An intent with no request of its own resolves against none, so both handle questions
 * answer that nothing was ever named - which is the honest answer rather than a refusal
 * of convenience: no request of that agent's did name it.
 */
class IntentSeam(
    /** What a creature handle names, for the one intent that runs from something. */
    val threatOf: (String) -> RefResolution,
    /** What a slot handle names, for the one intent that eats something. */
    val slotOf: (String) -> SlotResolution,
    /** The agent's own energy reserve, as much of it as eating needs. */
    val reserve: EnergyReserve,
)
