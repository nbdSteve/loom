/*
 * The whole eat rule, at a desk.
 *
 * Eating is the only thing that refills a reserve, so every way it can be refused is a way
 * an agent can starve holding food - and none of those ways is provable on a server without
 * standing there watching one. That is why the rule is engine-free: what is driven here is
 * the same `mealFor` the adapter drives, over a slot the test describes rather than one the
 * engine holds, so all six refusals and the acceptance are pinned in one place.
 *
 * What is left to the live server is exactly the two engine calls the adapter makes: reading
 * `DataComponents.FOOD` and `LivingEntity.eat`. Nothing here can prove that one item is
 * consumed, because consuming it is the engine's own rule and deliberately not Loom's.
 */
package dev.loom.body

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bread, as the engine prices it in `FoodProperties`. */
private const val BREAD_NUTRITION = 5

/** A whole reserve, and the number every gain here is a fraction of. */
private const val LIFESPAN_TICKS = 36_000L

/** What bread is worth at that capacity: a quarter of a life. */
private const val BREAD_TICKS = 9_000L

private val BREAD = SlotContents.Food(what = "Bread", nutrition = BREAD_NUTRITION)

class EatingTest {
    private val ada = AgentId("ada")

    @Test
    fun aMealIsTakenWhenTheSlotHoldsFoodAndTheReserveHasRoom() {
        val meal = mealFor(SlotResolution.Bound(3), ReserveStanding.HAS_ROOM) { BREAD }

        assertEquals(Meal.Ready(slot = 3, food = BREAD), meal)
    }

    @Test
    fun everyRefusalIsReachableAndSaysWhichItIs() {
        // Six refusals, six scenes, and each of them a different fact about the run: a
        // decision that arrived late, a mind that invented a handle, a stack eaten in
        // between, a mind that named a pickaxe, an agent wasting food, and a dead one.
        assertEquals(
            Meal.Refused(EatRefusal.SlotForgotten),
            mealFor(SlotResolution.Stale, ReserveStanding.HAS_ROOM) { BREAD },
        )
        assertEquals(
            Meal.Refused(EatRefusal.SlotUnnamed),
            mealFor(SlotResolution.Unknown, ReserveStanding.HAS_ROOM) { BREAD },
        )
        assertEquals(
            Meal.Refused(EatRefusal.SlotEmpty),
            mealFor(SlotResolution.Bound(0), ReserveStanding.HAS_ROOM) { SlotContents.Empty },
        )
        assertEquals(
            Meal.Refused(EatRefusal.NotFood),
            mealFor(SlotResolution.Bound(0), ReserveStanding.HAS_ROOM) { SlotContents.Inedible },
        )
        assertEquals(
            Meal.Refused(EatRefusal.ReserveFull),
            mealFor(SlotResolution.Bound(0), ReserveStanding.FULL) { BREAD },
        )
        assertEquals(
            Meal.Refused(EatRefusal.ReserveSpent),
            mealFor(SlotResolution.Bound(0), ReserveStanding.SPENT) { BREAD },
        )
    }

    @Test
    fun anEmptySlotIsToldApartFromAFullReserve() {
        // Both would read as "did not eat". They are opposite facts: one agent has run out
        // of food and the other is throwing it away, and the reserve is asked last so the
        // agent that has neither food nor room is reported as having no food.
        val meal = mealFor(SlotResolution.Bound(0), ReserveStanding.FULL) { SlotContents.Empty }

        assertEquals(Meal.Refused(EatRefusal.SlotEmpty), meal)
    }

    @Test
    fun anUnresolvableHandleReadsNoSlotAtAll() {
        // A handle that names no slot must not send the adapter looking in one: the slot it
        // would look in is whatever number a stale binding happens to leave behind.
        val read = mutableListOf<Int>()
        for (resolution in listOf(SlotResolution.Stale, SlotResolution.Unknown)) {
            mealFor(resolution, ReserveStanding.HAS_ROOM) { slot ->
                read += slot
                BREAD
            }
        }

        assertEquals(emptyList(), read, "a refused handle read a slot it had no business reading")
    }

    @Test
    fun theRuleReadsOnlyTheSlotItWasPointedAt() {
        val read = mutableListOf<Int>()
        mealFor(SlotResolution.Bound(6), ReserveStanding.HAS_ROOM) { slot ->
            read += slot
            BREAD
        }

        assertEquals(listOf(6), read)
    }

    @Test
    fun everyRefusalHasATagOfItsOwn() {
        // The tag is what a run counts, so two refusals sharing one would collapse two
        // findings into a number that means neither.
        val tags = EAT_REFUSALS.map(::tagOf)

        assertEquals(tags.size, tags.toSet().size, "two refusals share a tag: $tags")
        for (tag in tags) {
            assertTrue(tag.isNotBlank(), "a refusal filed under a blank tag is uncountable")
            assertTrue(tag.none { it.isWhitespace() }, "a tag is a stable token, not prose: '$tag'")
        }
    }

    @Test
    fun everyRefusalIsEnumeratedAndDescribed() {
        // EAT_REFUSALS is what the sweeps above run over, so a refusal missing from it is a
        // refusal nothing proves has a tag - the way a countable refusal stops being one.
        // Named again here rather than counted, so removing one from the list fails; adding
        // one to the type is caught by `tagOf` and `describe`, which are exhaustive.
        assertEquals(
            setOf(
                EatRefusal.SlotForgotten,
                EatRefusal.SlotUnnamed,
                EatRefusal.SlotEmpty,
                EatRefusal.NotFood,
                EatRefusal.ReserveFull,
                EatRefusal.ReserveSpent,
            ),
            EAT_REFUSALS.toSet(),
        )
        val prose = EAT_REFUSALS.map(::describe)
        assertEquals(prose.size, prose.toSet().size, "two refusals read the same to a person: $prose")
        for (line in prose) {
            assertTrue(line.isNotBlank(), "a refusal a person cannot read is a refusal nobody acts on")
        }
    }

    @Test
    fun aRefusedMealCarriesBothTheProseAndTheTag() {
        for (why in EAT_REFUSALS) {
            val refused = refusedMeal(why)

            assertEquals(describe(why), refused.reason, "the prose is for a person")
            assertEquals(tagOf(why), tagOf(refused), "and the tag is for a count")
        }
    }

    @Test
    fun aNonEatRefusalKeepsTheGenericTag() {
        // Only the refusals worth counting apart carry a tag of their own; everything else
        // stays `rejected`, so adding the field changed no record that already existed.
        assertEquals(EventTag.REJECTED, tagOf(IntentOutcome.Rejected("target out of range")))
    }

    @Test
    fun oneLoafIsWorthAQuarterOfALife() {
        // The conversion is the engine's own nutrition against the vanilla food bar, so it
        // is arithmetic rather than a table of Loom's: 20 nutrition is one whole reserve.
        val metabolism = Metabolism(LIFESPAN_TICKS)
        metabolism.spendUpTo(tick = 0, living = setOf(ada))
        metabolism.spendUpTo(tick = LIFESPAN_TICKS / 2, living = setOf(ada))
        val reserve = metabolism.reserveFor(ada)
        val before = metabolism.reserveOf(ada)

        val gained = reserve.credit(BREAD_NUTRITION)

        assertEquals(BREAD_TICKS, gained)
        assertEquals(before + BREAD_TICKS, metabolism.reserveOf(ada), "the gain is ticks, never a fraction")
    }

    @Test
    fun aNearlyFullReserveIsCreditedWithWhatItGainedAndNoMore() {
        // The record states this number, and a survival curve is drawn from those numbers.
        // Crediting the food's own worth would show reserve the agent never had.
        val metabolism = Metabolism(LIFESPAN_TICKS)
        metabolism.spendUpTo(tick = 0, living = setOf(ada))
        metabolism.spendUpTo(tick = 100, living = setOf(ada))
        val headroom = LIFESPAN_TICKS - metabolism.reserveOf(ada)

        val gained = metabolism.reserveFor(ada).credit(BREAD_NUTRITION)

        assertEquals(headroom, gained)
        assertTrue(gained < BREAD_TICKS, "a whole loaf could not fit, so it was not all credited")
        assertEquals(LIFESPAN_TICKS, metabolism.reserveOf(ada), "and a reserve is never banked past one life")
    }

    @Test
    fun aFullReserveRefusesAMealRatherThanThrowItAway() {
        val metabolism = Metabolism(LIFESPAN_TICKS)
        val reserve = metabolism.reserveFor(ada)

        assertEquals(ReserveStanding.FULL, reserve.standing())
        assertEquals(
            Meal.Refused(EatRefusal.ReserveFull),
            mealFor(SlotResolution.Bound(0), reserve.standing()) { BREAD },
        )
    }

    @Test
    fun aSpentReserveIsTerminalAndCannotBeEatenBackOutOf() {
        // Exhaustion is a permadeath. An agent that could eat its way out of one would not
        // be permadead, so the refusal is in the type and not only in the record.
        val metabolism = Metabolism(LIFESPAN_TICKS)
        metabolism.spendUpTo(tick = 0, living = setOf(ada))
        metabolism.spendUpTo(tick = LIFESPAN_TICKS, living = setOf(ada))
        val reserve = metabolism.reserveFor(ada)

        assertEquals(ReserveStanding.SPENT, reserve.standing())
        assertEquals(0L, reserve.credit(BREAD_NUTRITION), "nothing was credited")
        assertEquals(0L, metabolism.reserveOf(ada), "and the reserve is still spent")
        assertTrue(metabolism.isExhausted(ada))
    }

    @Test
    fun anAgentThatEatsOutlivesOneThatDoesNot() {
        // The whole point of the drive, as two replays of one tick sequence. Resting buys
        // nothing, so the only difference between these two agents is the meal.
        val fed = AgentId("fed")
        val starving = AgentId("starving")
        val metabolism = Metabolism(LIFESPAN_TICKS)
        val living = setOf(fed, starving)
        metabolism.spendUpTo(tick = 0, living = living)
        // Both spend the same three quarters of a life, and one of them eats twice.
        metabolism.spendUpTo(tick = LIFESPAN_TICKS / 2, living = living)
        metabolism.reserveFor(fed).credit(BREAD_NUTRITION)
        metabolism.spendUpTo(tick = LIFESPAN_TICKS - LIFESPAN_TICKS / 4, living = living)
        metabolism.reserveFor(fed).credit(BREAD_NUTRITION)

        val ranOut = metabolism.spendUpTo(tick = LIFESPAN_TICKS, living = living)

        assertEquals(setOf(starving), ranOut, "the agent that never ate is the one that ran out")
        assertTrue(metabolism.reserveOf(fed) > 0, "and the one that ate is still alive")
    }

    @Test
    fun theSameTicksAndTheSameMealsAlwaysEndTheSameWay() {
        // A reserve is counted in ticks so a run is replayable. Two identical replays that
        // disagreed would mean a permadeath depended on something other than the sequence.
        assertEquals(replay(), replay())
    }

    /** One short life: spend, eat, spend, and report what was left. */
    private fun replay(): Long {
        val metabolism = Metabolism(LIFESPAN_TICKS)
        val living = setOf(ada)
        for (tick in 0..3) {
            metabolism.spendUpTo(tick = tick * (LIFESPAN_TICKS / 4), living = living)
            metabolism.reserveFor(ada).credit(BREAD_NUTRITION)
        }
        return metabolism.reserveOf(ada)
    }

    @Test
    fun aReserveWithRoomIsTheOnlyStandingThatDoesNotRefuse() {
        // Total over the standing, so a fourth one added later cannot quietly read as room.
        assertNull(refusalFor(ReserveStanding.HAS_ROOM))
        assertNotNull(refusalFor(ReserveStanding.FULL))
        assertNotNull(refusalFor(ReserveStanding.SPENT))
    }
}
