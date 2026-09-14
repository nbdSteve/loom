/*
 * The request-scoped handles a player body has issued.
 *
 * Creature and slot handles use separate stores because they name different kinds of
 * thing, but one perception opens and settles both windows together.
 */
package dev.loom.body

/** Every handle retained for player-body decision round trips. */
class RequestRefs(
    /** Living things observed by the body. */
    val creatures: EntityRefs = EntityRefs(),
    /** Occupied player-inventory slots observed by the body. */
    val slots: InventoryRefs = InventoryRefs(),
) {
    /**
     * Makes the handles from one perception answerable together.
     *
     * A decision may name either kind, so remembering only half of the perception would
     * make one valid action resolve and another valid action fail from the same answer.
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

    /** Drops both kinds of handle once the decision has been dealt with. */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) {
        creatures.settle(agent, requestId)
        slots.settle(agent, requestId)
    }

    /** Drops every retained handle for [agent]. */
    fun forget(agent: AgentId) {
        creatures.forget(agent)
        slots.forget(agent)
    }
}
