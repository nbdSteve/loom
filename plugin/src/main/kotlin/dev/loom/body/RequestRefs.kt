/*
 * The request-scoped handles a player body has issued.
 *
 * Creature, block, dropped-item, recipe, and slot handles use separate stores because they
 * name different kinds of thing, but one perception opens and settles every window
 * together.
 */
package dev.loom.body

/** Every namespace of handles one perception offered to cognition. */
data class RequestRefBindings(
    val creatures: List<EntityRefBinding> = emptyList(),
    val blocks: List<WorldTargetRefBinding> = emptyList(),
    val droppedItems: List<WorldTargetRefBinding> = emptyList(),
    val slots: List<InventorySlotBinding> = emptyList(),
    val recipes: List<WorldTargetRefBinding> = emptyList(),
)

/** Every handle retained for player-body decision round trips. */
class RequestRefs(
    /** Living things observed by the body. */
    val creatures: EntityRefs = EntityRefs(),
    /** Nearby non-air blocks offered by the player body. */
    val blocks: WorldTargetRefs = WorldTargetRefs("b"),
    /** Nearby dropped-item entities offered by the player body. */
    val droppedItems: WorldTargetRefs = WorldTargetRefs("d"),
    /** Occupied player-inventory slots observed by the body. */
    val slots: InventoryRefs = InventoryRefs(),
    /** Exact craftable recipes offered by the player body. */
    val recipes: WorldTargetRefs = WorldTargetRefs("r"),
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
        bindings: RequestRefBindings,
        atTick: Long,
    ) {
        creatures.remember(agent, requestId, bindings.creatures, atTick)
        blocks.remember(agent, requestId, bindings.blocks, atTick)
        droppedItems.remember(agent, requestId, bindings.droppedItems, atTick)
        slots.remember(agent, requestId, bindings.slots, atTick)
        recipes.remember(agent, requestId, bindings.recipes, atTick)
    }

    /** Drops every kind of handle once the decision has been dealt with. */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) {
        creatures.settle(agent, requestId)
        blocks.settle(agent, requestId)
        droppedItems.settle(agent, requestId)
        slots.settle(agent, requestId)
        recipes.settle(agent, requestId)
    }

    /** Drops every retained handle for [agent]. */
    fun forget(agent: AgentId) {
        creatures.forget(agent)
        blocks.forget(agent)
        droppedItems.forget(agent)
        slots.forget(agent)
        recipes.forget(agent)
    }
}
