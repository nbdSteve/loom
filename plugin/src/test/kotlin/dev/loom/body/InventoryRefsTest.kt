/*
 * The lifetime of a handle that names a slot.
 *
 * `EntityRefsTest` owns the same questions for the creatures a body can see, and the two
 * are deliberately separate suites because the two stores answer about different things: a
 * creature handle names something whose identity outlives the request, and a slot handle
 * names a *position* whose contents change. What they share is the failure that matters -
 * a handle resolving when it should not - because a decision that ate the wrong slot would
 * look in the world exactly like an agent that had chosen wrongly.
 */
package dev.loom.body

import dev.loom.contract.DayPhase
import dev.loom.contract.InventoryStack
import dev.loom.contract.SelfState
import dev.loom.contract.WorldState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The width the contract allows a ref, as `SlotRef` states it. */
private const val REF_MAX_CHARS = 8

class InventoryRefsTest {
    private val ada = AgentId("ada")
    private val bo = AgentId("bo")

    @Test
    fun aSlotHandleOutlivesEveryScanNobodyWasShown() {
        // The failure this pins is an agent starving with bread in its hand. A goal review
        // offers the stack and its handle and goes off to a mind; the threat scan reads the
        // world once a second and mints handles of its own, and almost every one of those
        // sightings is suppressed and reaches no mind at all. Remembering them evicted the
        // review's own request within a couple of seconds, so the `eat` that came back was
        // refused as `eatSlotForgotten` - "no request of this agent's named that stack" about
        // a stack the perception had offered.
        val refs = InventoryRefs(requestsInFlight = 2)
        val review = refs.issue(ada, "req-review", listOf(0)).single().handle

        repeat(5) { refs.mint(listOf(0)) }

        assertEquals(
            SlotResolution.Bound(0),
            refs.resolve(ada, "req-review", review),
            "a meal named by a mind that took its time must still find the slot it was offered",
        )
    }

    @Test
    fun bothKindsOfHandleBecomeAnswerableTogether() {
        // One perception carries creature handles and slot handles at once, and the decision
        // answering it may name either. A request whose creatures were remembered and whose
        // slots were not would resolve a flight and refuse a meal off the very same perception.
        val refs = RequestRefs()
        val creature = refs.creatures.mint(listOf(UUID.randomUUID())).single()
        val slot = refs.slots.mint(listOf(4)).single()
        assertEquals(RefResolution.Stale, refs.creatures.resolve(ada, "req-1", creature.handle, atTick = 0))
        assertEquals(SlotResolution.Stale, refs.slots.resolve(ada, "req-1", slot.handle, atTick = 0))

        refs.remember(
            ada,
            "req-1",
            RequestRefBindings(creatures = listOf(creature), slots = listOf(slot)),
            atTick = 0,
        )

        assertEquals(
            RefResolution.Bound(creature.subject),
            refs.creatures.resolve(ada, "req-1", creature.handle, atTick = 0),
        )
        assertEquals(SlotResolution.Bound(4), refs.slots.resolve(ada, "req-1", slot.handle, atTick = 0))
    }

    @Test
    fun twoStacksOfTheSameFoodGetDifferentHandles() {
        // The case a name cannot express: two slots of bread, one of them the last food the
        // agent has. Sharing a handle would make the body guess which stack to eat.
        val refs = InventoryRefs()
        val issued = refs.issue(ada, "req-1", listOf(0, 3))

        assertEquals(2, issued.size)
        assertNotEquals(issued[0].handle, issued[1].handle)
        assertEquals(SlotResolution.Bound(0), refs.resolve(ada, "req-1", issued[0].handle))
        assertEquals(SlotResolution.Bound(3), refs.resolve(ada, "req-1", issued[1].handle))
    }

    @Test
    fun aHandleFitsTheWidthTheContractAllowsIt() {
        val refs = InventoryRefs()
        val handle = refs.issue(ada, "req-1", listOf(0)).single().handle

        assertTrue(handle.isNotBlank(), "a blank ref names nothing the body can resolve")
        assertTrue(handle.length <= REF_MAX_CHARS, "the contract caps a ref at 8 characters, got '$handle'")
    }

    @Test
    fun aHandleFromAnotherRequestOfTheSameAgentNamesNothing() {
        // The window is what makes a handle mean something. A decision that arrived after a
        // second perception must not act on the slot the first one happened to describe.
        val refs = InventoryRefs()
        val first = refs.issue(ada, "req-1", listOf(0)).single().handle
        refs.issue(ada, "req-2", listOf(0))

        assertEquals(SlotResolution.Unknown, refs.resolve(ada, "req-2", first))
    }

    @Test
    fun aHandleOfOneAgentNamesNothingForAnother() {
        // Both agents have a request of that name and a stack in that slot, so nothing but
        // the scoping keeps ada's handle from naming bo's food.
        val refs = InventoryRefs()
        val ofAda = refs.issue(ada, "req-1", listOf(0)).single().handle
        val ofBo = refs.issue(bo, "req-1", listOf(0)).single().handle

        assertNotEquals(ofAda, ofBo, "handles are minted from one counter, so no two ever coincide")
        assertEquals(SlotResolution.Unknown, refs.resolve(bo, "req-1", ofAda))
        assertEquals(SlotResolution.Unknown, refs.resolve(ada, "req-1", ofBo))
    }

    @Test
    fun anAgentThatHasIssuedNothingResolvesNothing() {
        val refs = InventoryRefs()
        val ofAda = refs.issue(ada, "req-1", listOf(0)).single().handle

        // Stale rather than unknown: bo has no request of that name at all, which is a
        // different fact from a remembered request that named no such stack.
        assertEquals(SlotResolution.Stale, refs.resolve(bo, "req-1", ofAda))
    }

    @Test
    fun anEvictedRequestNamesNothing() {
        val refs = InventoryRefs(requestsInFlight = 2)
        val oldest = refs.issue(ada, "req-1", listOf(0)).single().handle
        refs.issue(ada, "req-2", listOf(1))
        refs.issue(ada, "req-3", listOf(2))

        assertEquals(
            SlotResolution.Stale,
            refs.resolve(ada, "req-1", oldest),
            "a decision this late has to be told its whole request is gone, not that its stack moved",
        )
    }

    @Test
    fun reissuingForOneRequestReplacesWhatItHad() {
        // A re-taken perception describes the inventory as it is now. The handles of the
        // one it replaced must not still resolve, or a decision would eat by an old map.
        val refs = InventoryRefs()
        val first = refs.issue(ada, "req-1", listOf(0)).single().handle
        val second = refs.issue(ada, "req-1", listOf(5)).single().handle

        assertEquals(SlotResolution.Unknown, refs.resolve(ada, "req-1", first))
        assertEquals(SlotResolution.Bound(5), refs.resolve(ada, "req-1", second))
    }

    @Test
    fun noMoreThanTheBodyOwnSlotsAreEverBound() {
        // A request offering more slots than the body has has read something that is not
        // this body's inventory, so the bound is enforced here rather than trusted.
        val refs = InventoryRefs()
        val issued = refs.issue(ada, "req-1", (0..60).toList())

        assertEquals(BODY_INVENTORY_SLOTS, issued.size)
        assertEquals((0 until BODY_INVENTORY_SLOTS).toList(), issued.map { it.slot })
    }

    @Test
    fun aBodyWithEverySlotFullStillFitsTheWire() {
        // Two bounds have to agree: how many slots a body has, and how many stacks the
        // contract lets a perception state. They are the same eight for the same reason, but
        // they are stated in different packages, so a flavor that outgrew the wire would
        // otherwise only be found by a `SelfState` refusing a perception at a live socket.
        val refs = InventoryRefs()
        val issued = refs.issue(ada, "req-1", (0 until BODY_INVENTORY_SLOTS).toList())

        val state =
            SelfState(
                health = 20.0,
                food = 20,
                saturation = 5.0,
                position = listOf(0.0, 64.0, 0.0),
                inventorySummary =
                    issued.map { InventoryStack(ref = it.handle, what = "item.minecraft.bread", count = 1) },
                world = WorldState(dayPhase = DayPhase.DAY, isDay = true, ticksUntilNightfall = 1),
            )

        assertEquals(BODY_INVENTORY_SLOTS, state.inventorySummary.size)
    }

    @Test
    fun forgettingAnAgentDropsEveryHandleOfIts() {
        // Loom is permadeath, so a handle of an ended agent can never be answered again -
        // and a successor body must not inherit a dead agent's map of its inventory.
        val refs = InventoryRefs()
        val handle = refs.issue(ada, "req-1", listOf(0)).single().handle
        refs.forget(ada)

        assertEquals(SlotResolution.Stale, refs.resolve(ada, "req-1", handle))
    }

    @Test
    fun aRequestMustHaveAName() {
        // An intent with no request resolves against none. Issuing under a blank name would
        // give every such intent one shared window instead.
        val refs = InventoryRefs()

        assertFailsWith<IllegalArgumentException> { refs.issue(ada, "  ", listOf(0)) }
        assertEquals(SlotResolution.Stale, refs.resolve(ada, "", "i0"))
    }

    @Test
    fun aCreatureHandleIsNotASlotHandleAndTheOtherWayRound() {
        // The one reason the port keeps two stores. Sharing one would let a decision eat
        // the handle of a zombie, or run away from a stack of bread.
        val refs = RequestRefs()
        val creature = refs.creatures.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val slot = refs.slots.issue(ada, "req-1", listOf(0)).single().handle
        assertEquals(SlotResolution.Unknown, refs.slots.resolve(ada, "req-1", creature))
        assertEquals(RefResolution.Unknown, refs.creatures.resolve(ada, "req-1", slot))
        assertEquals(SlotResolution.Bound(0), refs.slots.resolve(ada, "req-1", slot))
    }

    @Test
    fun forgettingAnAgentDropsBothKindsOfHandle() {
        val refs = RequestRefs()
        val creature = refs.creatures.issue(ada, "req-1", listOf(UUID.randomUUID())).single().handle
        val slot = refs.slots.issue(ada, "req-1", listOf(0)).single().handle
        refs.forget(ada)

        assertEquals(SlotResolution.Stale, refs.slots.resolve(ada, "req-1", slot))
        assertEquals(RefResolution.Stale, refs.creatures.resolve(ada, "req-1", creature))
    }

    @Test
    fun oneDecisionSettlesBothKindsOfHandleAtOnce() {
        // A decision answers a whole perception, so the window that perception opened closes
        // whole. Settling one kind and not the other would leave a stale decision able to
        // eat by an old map of the inventory while its flight was already refused.
        val refs = RequestRefs()
        val creature = refs.creatures.mint(listOf(UUID.randomUUID())).single()
        val slot = refs.slots.mint(listOf(2)).single()
        refs.remember(
            ada,
            "req-1",
            RequestRefBindings(creatures = listOf(creature), slots = listOf(slot)),
            atTick = 0,
        )

        refs.settle(ada, "req-1")

        assertEquals(SlotResolution.Stale, refs.slots.resolve(ada, "req-1", slot.handle))
        assertEquals(RefResolution.Stale, refs.creatures.resolve(ada, "req-1", creature.handle))
    }

    @Test
    fun aRequestNoDecisionEverAnsweredRunsOutOfItsOwnAccord() {
        // Nothing settles a request whose decision never arrives - a saturated bridge, a mind
        // that timed out - so the lifetime is what keeps the window from being held for the
        // rest of the run. Both kinds run out together, off the one tick the seam is asked at.
        val refs = RequestRefs()
        val creature = refs.creatures.mint(listOf(UUID.randomUUID())).single()
        val slot = refs.slots.mint(listOf(2)).single()
        refs.remember(
            ada,
            "req-1",
            RequestRefBindings(creatures = listOf(creature), slots = listOf(slot)),
            atTick = 100,
        )

        val inTime = 100 + REQUEST_REFS_LIFETIME_TICKS - 1
        val tooLate = 100 + REQUEST_REFS_LIFETIME_TICKS

        assertEquals(SlotResolution.Bound(2), refs.slots.resolve(ada, "req-1", slot.handle, inTime))
        assertEquals(
            RefResolution.Bound(creature.subject),
            refs.creatures.resolve(ada, "req-1", creature.handle, inTime),
        )
        assertEquals(SlotResolution.Stale, refs.slots.resolve(ada, "req-1", slot.handle, tooLate))
        assertEquals(RefResolution.Stale, refs.creatures.resolve(ada, "req-1", creature.handle, tooLate))
    }
}
