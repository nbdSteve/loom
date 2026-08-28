/*
 * Eating one item out of a body's own inventory.
 *
 * The whole of the engine half of `Intent.Eat`: what the named slot holds now, whether
 * the engine calls it food, and the engine's own `LivingEntity.eat`. The rule about what
 * may refuse a meal, and what each refusal is called, is `dev.loom.body.Eating` in the
 * domain, where it is testable without a server.
 *
 * It is an extension on the body rather than a member of it because the body is already
 * as large as it should be, and because eating is a policy over a container rather than
 * anything about being a villager - the same shape of seam `BodyPerception` uses.
 */
package dev.loom.body.nms

import dev.loom.body.EatRefusal
import dev.loom.body.EnergyReserve
import dev.loom.body.IntentOutcome
import dev.loom.body.Meal
import dev.loom.body.SlotContents
import dev.loom.body.SlotResolution
import dev.loom.body.mealFor
import dev.loom.body.refusedMeal
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Eats one item from the slot [handle] names, and credits what it was worth.
 *
 * The slot is re-read here rather than trusted from the perception that offered it: a
 * handle names a *position*, and what stands in it can be eaten, dropped or swapped
 * between the perception and the decision that answers it. So what is in the slot now is
 * what is checked, and anything the engine does not call food is refused rather than
 * eaten - which is also what makes it safe for a mind that cannot tell bread from a
 * pickaxe to name a stack and be told no.
 *
 * The order is load-bearing. Every refusal is settled *before* the engine consumes
 * anything, so a full or spent agent keeps its food; the engine's own `LivingEntity.eat`
 * then does the eating - sound, particles, effects, the game event and the one item - and
 * only what it consumed is credited.
 *
 * Eating is instantaneous in this first version: there is no use time, no animation, and
 * nothing an agent can be caught halfway through.
 */
internal fun LoomBodyEntity.eatFrom(
    handle: String,
    slotOf: (String) -> SlotResolution,
    reserve: EnergyReserve,
): IntentOutcome =
    when (val meal = mealFor(slotOf(handle), reserve.standing(), ::contentsOf)) {
        is Meal.Refused -> refusedMeal(meal.why)
        is Meal.Ready -> eatAt(meal.slot, meal.food.what, reserve)
    }

/**
 * What slot [slot] of this body's own inventory holds, in the three facts the rule reads.
 *
 * The engine is the only authority on what counts as food, which is why the answer is
 * `DataComponents.FOOD` rather than a list of items Loom would have to maintain: a food
 * added or repriced by a version bump is priced correctly here without a change.
 *
 * The name is the engine's own `descriptionId`, the same vocabulary the perception states a
 * carried stack in, so the record's account of a meal is countable rather than a display
 * string an anvil could have rewritten.
 */
private fun LoomBodyEntity.contentsOf(slot: Int): SlotContents {
    val stack = inventory.getItem(slot)
    val food = stack.get(DataComponents.FOOD)
    return when {
        stack.isEmpty -> SlotContents.Empty
        food == null -> SlotContents.Inedible
        else -> SlotContents.Food(what = stack.descriptionId, nutrition = food.nutrition())
    }
}

/**
 * Eats what is in [slot], which the rule has already judged a meal, and credits it.
 *
 * The slot is read a second time rather than the stack being carried through the verdict,
 * so that nothing but this line decides which items are consumed. It is the same slot in
 * the same tick, so the food is still there; a slot that has emptied in between is reported
 * as such rather than asserted away, because the honest answer is that nothing was eaten.
 */
private fun LoomBodyEntity.eatAt(
    slot: Int,
    what: String,
    reserve: EnergyReserve,
): IntentOutcome {
    val stack: ItemStack = inventory.getItem(slot)
    val food = stack.get(DataComponents.FOOD) ?: return refusedMeal(EatRefusal.SlotEmpty)
    // The engine's own eating, so a Loom agent's meal sounds, looks and counts to anything
    // watching exactly like anybody else's - and so exactly one item is consumed, by the
    // rule that owns items rather than by arithmetic here.
    eat(level(), stack, food)
    val gained = reserve.credit(food.nutrition())
    return IntentOutcome.Accepted("ate one $what, worth $gained ticks of reserve")
}
