/*
 * Eating: the one thing that refills an energy reserve.
 *
 * Resting holds a position and buys nothing, so an agent that never eats starves
 * however carefully it waits. `Intent.Eat` is therefore the first intent that spends
 * something of the body's own rather than the world's, and everything about what it
 * may name, what may refuse it, and what a refusal is called lives here.
 *
 * A meal names one exact stack, not a food. Two stacks of bread in different slots are
 * different meals - one may be the last of the agent's food and the other a stack it is
 * carrying somewhere - so the body mints a handle per occupied slot, sends it with the
 * perception, and the mind names the handle back. That is the same seam `EntityRefs`
 * gives the creatures a body can see, and it is deliberately a separate type rather
 * than a generalisation of it: a creature binding names something whose identity
 * outlives the request, while a slot binding names a *position* whose contents can
 * change between the perception and the decision. So the apply path must re-read the
 * slot and re-check that what is in it is still food, which is a rule about eating and
 * not about handles.
 *
 * Engine-free by rule, like the rest of `dev.loom.body`: a binding names a slot index,
 * and turning that index into an item is an adapter's job.
 */
package dev.loom.body

/**
 * How many slots a body's own inventory has.
 *
 * Forty-six, the complete player inventory window carried by the perception contract.
 * The obsolete Villager adapter still has eight native slots and naturally offers only
 * those eight; the common ref window must be wide enough not to truncate a player.
 */
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

/** One handle and the slot of the body's own inventory it names. */
data class InventorySlotBinding(
    /** The handle, as it travels on the wire in `InventoryStack.ref`. */
    val handle: String,
    /** The slot the handle names, for the adapter to read. */
    val slot: Int,
)

/**
 * What a handle offered by a decision resolved to.
 *
 * [Stale] and [Unknown] are kept apart for the reason `RefResolution` keeps them apart:
 * a handle from an evicted request is a decision that arrived late, and a handle no
 * request ever issued is a mind that invented one. A run that could not tell them apart
 * could not say whether its agents were thinking slowly or wrongly.
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
 * The slot handles a body has issued, bounded per request and per agent.
 *
 * The window itself - how long a request stays answerable, and what closes it - is
 * [RequestWindow]'s, shared with the creature handles that ride the same perception.
 *
 * @param lifetimeTicks how long a request's handles stay answerable.
 * @param requestsInFlight how many unanswered requests one agent may hold handles for.
 */
class InventoryRefs(
    lifetimeTicks: Long = REQUEST_REFS_LIFETIME_TICKS,
    requestsInFlight: Int = MAX_REQUESTS_IN_FLIGHT_PER_AGENT,
) {
    /** What each agent still holds, and every rule about when it stops holding it. */
    private val window = RequestWindow<Int>(lifetimeTicks, requestsInFlight)

    private var minted: Long = 0

    /**
     * Mints one handle per slot and binds them to this request, in one step.
     *
     * [mint] then [remember], for the caller that already knows the perception it is
     * describing will be handed to a mind.
     */
    fun issue(
        agent: AgentId,
        requestId: String,
        slots: List<Int>,
        atTick: Long = 0,
    ): List<InventorySlotBinding> = mint(slots).also { remember(agent, requestId, it, atTick) }

    /**
     * Mints one handle per slot, in the order given, and binds them to nothing yet.
     *
     * Separate from [remember] for the reason `EntityRefs.mint` is: a perception carries its
     * handles before anything knows whether it will be sent, and a threat scan reads the world
     * every second and usually suppresses what it found. A window held open for a perception no
     * mind was shown answers nothing and counts against the ceiling on the requests that can
     * still be answered - and a meal whose window has gone arrives as
     * [EatRefusal.SlotForgotten], with the agent starving holding food.
     *
     * Bounded by [BODY_INVENTORY_SLOTS], because that is how many slots exist: a
     * request offering more has read something that is not this body's inventory.
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

    /** What [handle] names, for [agent] answering [requestId] at [atTick]. */
    fun resolve(
        agent: AgentId,
        requestId: String,
        handle: String,
        atTick: Long = 0,
    ): SlotResolution {
        val forRequest = window.bindingsFor(agent, requestId, atTick) ?: return SlotResolution.Stale
        return forRequest[handle]?.let(SlotResolution::Bound) ?: SlotResolution.Unknown
    }

    /** Drops what [agent] holds for [requestId], its decision having been dealt with. */
    fun settle(
        agent: AgentId,
        requestId: String,
    ) = window.settle(agent, requestId)

    /** Drops every handle held for [agent], which Loom being permadeath can never answer. */
    fun forget(agent: AgentId) = window.forget(agent)

    private fun nextHandle(): String {
        val ordinal = minted % SLOT_HANDLE_SPACE
        minted += 1
        return SLOT_HANDLE_PREFIX + ordinal.toString(SLOT_HANDLE_RADIX)
    }
}

/**
 * Why a body would not eat what a decision named.
 *
 * Six distinct facts, and none of them collapses into another, because they are about
 * different things: two are about the handle, two about what the slot holds now, and
 * two about the reserve. A run that read them all as "did not eat" could not tell an
 * agent that had nothing edible from one that was already full.
 */
sealed interface EatRefusal {
    /** The ref window passed: no request of this agent's still remembers that handle. */
    data object SlotForgotten : EatRefusal

    /** The request is remembered and never named that handle. */
    data object SlotUnnamed : EatRefusal

    /**
     * The slot is empty now.
     *
     * Not a mistake by either side: a handle names a position, and the last of a stack
     * can be eaten between the perception that offered it and the decision that names it.
     */
    data object SlotEmpty : EatRefusal

    /** The slot holds something, and the engine does not call it food. */
    data object NotFood : EatRefusal

    /** The reserve is already full, so eating would throw the food away. */
    data object ReserveFull : EatRefusal

    /**
     * The reserve has run out, and exhaustion is terminal.
     *
     * Kept apart from [ReserveFull] because they are opposite ends of the same reading
     * and opposite facts about the run: one agent is wasting a meal and the other is
     * already dead.
     */
    data object ReserveSpent : EatRefusal
}

/**
 * Every way a meal can be refused.
 *
 * Enumerated so a reader can sweep them - a refusal missing from here is a refusal nothing
 * proves has a tag of its own, which is the way a countable refusal quietly stops being one.
 */
val EAT_REFUSALS: List<EatRefusal> =
    listOf(
        EatRefusal.SlotForgotten,
        EatRefusal.SlotUnnamed,
        EatRefusal.SlotEmpty,
        EatRefusal.NotFood,
        EatRefusal.ReserveFull,
        EatRefusal.ReserveSpent,
    )

/**
 * What a slot holds, as much of it as the eat rule reads.
 *
 * The whole of what an adapter has to say about an item: the rule needs to know whether
 * there is anything there, whether the engine calls it food, and what that food is worth.
 * Keeping it to those three facts is what makes every refusal decidable without a world.
 */
sealed interface SlotContents {
    /** Nothing in the slot. */
    data object Empty : SlotContents

    /** Something in the slot, and the engine does not call it food. */
    data object Inedible : SlotContents

    /**
     * Food, worth [nutrition] points of the vanilla food bar.
     *
     * [what] is the engine's own id for the item, for the record to state; it is never
     * matched on here, so a body does not have to know one food from another. An id rather
     * than a display name because the two readers it does reach - the mind, and anything
     * counting meals off the append-only record - both need it stable and unrewritable.
     */
    data class Food(
        val what: String,
        val nutrition: Int,
    ) : SlotContents
}

/** What a body should do about the meal a decision named. */
sealed interface Meal {
    /** Eat what is in [slot]; it is [food]. */
    data class Ready(
        val slot: Int,
        val food: SlotContents.Food,
    ) : Meal

    /** Eat nothing, because of [why]. Nothing has been consumed. */
    data class Refused(
        val why: EatRefusal,
    ) : Meal
}

/**
 * The meal [resolution] amounts to, judged against what the slot holds and [standing].
 *
 * The whole eat rule, and engine-free on purpose: an adapter supplies [contentsAt] and the
 * refusals are then decidable at a desk, which is the only way all six of them get proved.
 *
 * The order is load-bearing rather than tidy. The handle is read first because an
 * unresolvable one names no slot to look in; the slot is read next, so a stack eaten
 * between the perception and the decision is reported as an empty slot rather than as a
 * full reserve; and the reserve is asked last, so an agent that cannot use a meal still
 * finds out what it was going to eat. Every one of them is settled before anything is
 * consumed, which is what keeps a refusal from costing an agent its food.
 *
 * Total over [SlotResolution] and [SlotContents] with no default branch, so neither can
 * grow a variant that silently reads as a meal.
 */
fun mealFor(
    resolution: SlotResolution,
    standing: ReserveStanding,
    contentsAt: (Int) -> SlotContents,
): Meal =
    when (resolution) {
        SlotResolution.Stale -> Meal.Refused(EatRefusal.SlotForgotten)
        SlotResolution.Unknown -> Meal.Refused(EatRefusal.SlotUnnamed)
        is SlotResolution.Bound ->
            when (val contents = contentsAt(resolution.slot)) {
                SlotContents.Empty -> Meal.Refused(EatRefusal.SlotEmpty)
                SlotContents.Inedible -> Meal.Refused(EatRefusal.NotFood)
                is SlotContents.Food ->
                    refusalFor(standing)?.let(Meal::Refused) ?: Meal.Ready(resolution.slot, contents)
            }
    }

/**
 * A refused meal as the outcome the record is written from.
 *
 * Built here rather than at the adapter so the prose and the countable tag always travel
 * together: a refusal recorded with one and not the other is either uncountable or unreadable.
 */
fun refusedMeal(why: EatRefusal): IntentOutcome.Rejected = IntentOutcome.Rejected(describe(why), tagOf(why))

/**
 * The eat refusal [standing] amounts to, or `null` if the reserve can take a meal.
 *
 * Asked before anything is consumed, which is the whole reason it is a refusal and not a
 * clamp: a full reserve that ate anyway would have thrown the food away, and a spent one
 * is permadead and cannot eat its way back.
 */
fun refusalFor(standing: ReserveStanding): EatRefusal? =
    when (standing) {
        ReserveStanding.HAS_ROOM -> null
        ReserveStanding.FULL -> EatRefusal.ReserveFull
        ReserveStanding.SPENT -> EatRefusal.ReserveSpent
    }

/** What a refusal is called in the append-only record, so refusals stay countable. */
fun tagOf(refusal: EatRefusal): String =
    when (refusal) {
        EatRefusal.SlotForgotten -> EventTag.EAT_SLOT_FORGOTTEN
        EatRefusal.SlotUnnamed -> EventTag.EAT_SLOT_UNNAMED
        EatRefusal.SlotEmpty -> EventTag.EAT_SLOT_EMPTY
        EatRefusal.NotFood -> EventTag.EAT_NOT_FOOD
        EatRefusal.ReserveFull -> EventTag.EAT_RESERVE_FULL
        EatRefusal.ReserveSpent -> EventTag.EAT_RESERVE_SPENT
    }

/** What a refusal reads as in the record's own text. */
fun describe(refusal: EatRefusal): String =
    when (refusal) {
        EatRefusal.SlotForgotten -> "no request of this agent's named that stack"
        EatRefusal.SlotUnnamed -> "this request named no such stack"
        EatRefusal.SlotEmpty -> "that slot is empty now"
        EatRefusal.NotFood -> "that is not food"
        EatRefusal.ReserveFull -> "the energy reserve is already full"
        EatRefusal.ReserveSpent -> "the energy reserve is spent, and that is terminal"
    }

/** How much room an agent's reserve has for a meal. */
enum class ReserveStanding {
    /** Eating would add something. */
    HAS_ROOM,

    /** Full: eating would throw the food away. */
    FULL,

    /** Spent, which is terminal. */
    SPENT,
}

/**
 * As much of an agent's energy reserve as eating needs.
 *
 * An interface rather than one function because eating asks two things in an order that
 * matters: whether the reserve has room, *before* the engine consumes the item, and then
 * the credit for what was eaten. One combined call would have to credit a reserve before
 * the item was gone, and any refusal after that point would pay an agent for food it
 * still holds.
 *
 * It is a seam rather than the `Metabolism` itself because the reserve belongs to the
 * agent and outlives every body it has: only the port holds one, and a body must not be
 * able to reach an agent's reserve except through the meal it was asked to eat.
 */
interface EnergyReserve {
    /** Whether the reserve can take anything at all. */
    fun standing(): ReserveStanding

    /**
     * Credits what [nutrition] points of vanilla nutrition are worth.
     *
     * @return how many ticks of reserve the agent actually gained, for the record to state.
     *   Less than the food was worth when the reserve filled up on the way, because a
     *   record that stated the food's own value would credit an agent with reserve it
     *   never had, and the survival curve is drawn from those numbers.
     */
    fun credit(nutrition: Int): Long
}

/**
 * [agentId]'s reserve, as much of it as eating needs.
 *
 * The port feeds through this and a body never holds a [Metabolism]: a reserve belongs to
 * the agent and outlives every body it has, so a dimension transit that replaces a body
 * must not replace the reserve. Bound to one agent here rather than taking an id per
 * call, so a body cannot ask about anybody else's.
 */
fun Metabolism.reserveFor(agentId: AgentId): EnergyReserve =
    object : EnergyReserve {
        override fun standing(): ReserveStanding =
            when {
                isExhausted(agentId) -> ReserveStanding.SPENT
                isSated(agentId) -> ReserveStanding.FULL
                else -> ReserveStanding.HAS_ROOM
            }

        override fun credit(nutrition: Int): Long {
            val before = reserveOf(agentId)
            // The gain rather than the food's own worth, because feeding stops at the
            // capacity: a nearly-full agent that ate a whole loaf gained the headroom and
            // no more, and that is the number the record has to state.
            //
            // The reserve was asked for room before the item was consumed, so a refusal
            // here would mean it had changed inside one tick. Reported as no gain rather
            // than assumed away: the record states what the agent actually got.
            return if (feed(agentId, ticksForNutrition(nutrition))) reserveOf(agentId) - before else 0L
        }
    }
