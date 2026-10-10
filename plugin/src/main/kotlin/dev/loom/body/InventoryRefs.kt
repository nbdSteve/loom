/*
 * Request-scoped handles for the occupied inventory slots a player body reported.
 *
 * A handle names one slot in one sense answer. It is not an item id: the player body
 * re-reads the slot when applying `eat`, so an emptied or changed stack is refused by
 * the body-control process rather than acted on through stale perception.
 */
package dev.loom.body

/** The complete player inventory window carried by the body-control contract. */
const val BODY_INVENTORY_SLOTS: Int = 46

/** The character a slot handle starts with, so it never reads to a model as a number. */
private const val SLOT_HANDLE_PREFIX = "i"

/** Base 36, so a handle stays short in the width the contract allows it. */
private const val SLOT_HANDLE_RADIX = 36

/** How many base-36 characters follow the prefix, the contract capping a handle at eight. */
private const val SLOT_HANDLE_BODY_CHARS = 7

/** The whole of [SLOT_HANDLE_BODY_CHARS] characters in base [SLOT_HANDLE_RADIX]. */
private val SLOT_HANDLE_SPACE: Long =
    List(SLOT_HANDLE_BODY_CHARS) { SLOT_HANDLE_RADIX.toLong() }.reduce(Long::times)

/** One handle and the slot of the player's inventory it names. */
data class InventorySlotBinding(
    /** The handle, as it travels on the wire in `InventoryStack.ref`. */
    val handle: String,
    /** The slot the handle names, for the player adapter to send back to the body. */
    val slot: Int,
)

/**
 * What a handle offered by a decision resolved to.
 *
 * [Stale] is a decision whose sense answer is no longer retained. [Unknown] is a
 * remembered answer that never issued the handle.
 */
sealed interface SlotResolution {
    /** The handle named this slot, and still does. */
    data class Bound(val slot: Int) : SlotResolution

    /** This agent holds no bindings for that request any more, or never did. */
    data object Stale : SlotResolution

    /** The request is still remembered and issued no such handle. */
    data object Unknown : SlotResolution
}

/**
 * The slot handles a player body has issued, bounded per request and per agent.
 *
 * The lifetime and in-flight bounds are shared with creature handles through
 * [RequestWindow], because every handle kind travels in one perception and is settled
 * by one decision.
 */
class InventoryRefs(
    lifetimeTicks: Long = REQUEST_REFS_LIFETIME_TICKS,
    requestsInFlight: Int = MAX_REQUESTS_IN_FLIGHT_PER_AGENT,
) {
    private val window = RequestWindow<Int>(lifetimeTicks, requestsInFlight)
    private var minted: Long = 0

    /** Mints one handle per slot and makes them answerable under [requestId]. */
    fun issue(
        agent: AgentId,
        requestId: String,
        slots: List<Int>,
        atTick: Long = 0,
    ): List<InventorySlotBinding> = mint(slots).also { remember(agent, requestId, it, atTick) }

    /**
     * Mints handles without opening a request window.
     *
     * The player scene is built before the caller knows whether it will reach cognition;
     * only [remember] makes the handles answerable. The body-control contract reports at
     * most [BODY_INVENTORY_SLOTS] occupied slots.
     */
    fun mint(slots: List<Int>): List<InventorySlotBinding> =
        slots.take(BODY_INVENTORY_SLOTS).map { slot ->
            InventorySlotBinding(handle = nextHandle(), slot = slot)
        }

    /** Makes [bindings] answerable for [agent] under [requestId], as of [atTick]. */
    fun remember(
        agent: AgentId,
        requestId: String,
        bindings: List<InventorySlotBinding>,
        atTick: Long,
    ) = window.remember(agent, requestId, bindings.associate { it.handle to it.slot }, atTick)

    /** What [handle] names for this agent, request, and tick. */
    fun resolve(
        agent: AgentId,
        requestId: String,
        handle: String,
        atTick: Long = 0,
    ): SlotResolution {
        val forRequest = window.bindingsFor(agent, requestId, atTick) ?: return SlotResolution.Stale
        return forRequest[handle]?.let(SlotResolution::Bound) ?: SlotResolution.Unknown
    }

    /** Drops the bindings for one completed decision round trip. */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) = window.settle(agent, requestId)

    /** Drops every handle retained for [agent]. */
    fun forget(agent: AgentId) = window.forget(agent)

    private fun nextHandle(): String {
        val ordinal = minted % SLOT_HANDLE_SPACE
        minted += 1
        return SLOT_HANDLE_PREFIX + ordinal.toString(SLOT_HANDLE_RADIX)
    }
}
