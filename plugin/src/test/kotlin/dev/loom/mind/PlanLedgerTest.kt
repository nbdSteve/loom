package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.InventoryStack
import dev.loom.contract.ItemChange
import dev.loom.contract.PlanCause
import dev.loom.contract.PlanUpdate
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanLedgerTest {
    private val agent = AgentId("agent-alpha")
    private val plan =
        PlanUpdate.ReplacePlan(
            "survive and build a home",
            listOf("get a wooden pickaxe", "build a shelter"),
            listOf("dig a log", "craft planks"),
            "no plan yet",
        )

    @Test
    fun `the planner sets the plan, steps advance it, and a finished subgoal owes a planning call`() {
        val ledger = PlanLedger()
        assertEquals(Trigger.PlanReview(PlanCause.START), ledger.due(agent, 0))
        // A step call cannot set the plan.
        val step = perceptionNearBush()
        assertFalse(requireNotNull(ledger.resolve(agent, decisionFor(step).copy(plan = plan), false, 0)).applied)
        val planned = ledger.resolve(agent, decisionFor(step).copy(plan = plan), true, 10)
        assertEquals("get a wooden pickaxe", planned?.subgoal)
        assertEquals("dig a log", planned?.step)
        assertNull(ledger.due(agent, 20))
        assertEquals(
            "dig a log",
            ledger.resolve(agent, decisionFor(step).copy(plan = PlanUpdate.StepDone), false, 30)?.done,
        )
        assertNull(ledger.due(agent, 40))
        ledger.resolve(agent, decisionFor(step).copy(plan = PlanUpdate.StepDone), false, 50)
        assertEquals(Trigger.PlanReview(PlanCause.SUBGOAL_DONE), ledger.due(agent, 60))
        // The next plan keeps the finished subgoal, done.
        val next = plan.copy(subgoals = listOf("build a shelter"), steps = listOf("dig down"), reason = "pickaxe made")
        ledger.resolve(agent, decisionFor(step).copy(plan = next), true, 70)
        val shown = requireNotNull(ledger.shown(agent, step).plan)
        assertEquals(listOf("get a wooden pickaxe", "build a shelter"), shown.subgoals)
        assertEquals(1L, shown.subgoal)
        // A replan is a blocked subgoal's planning call, with its reason.
        ledger.resolve(agent, decisionFor(step).copy(plan = PlanUpdate.Replan("no dirt")), false, 80)
        assertEquals(Trigger.PlanReview(PlanCause.BLOCKED, "no dirt"), ledger.due(agent, 90))
        assertNull(ledger.due(agent, 95), "asked once per retry window")
        assertTrue(ledger.due(agent, 90 + PLAN_RETRY_TICKS) != null)
    }

    @Test
    fun `recent actions carry what the inventory gained after them`() {
        val ledger = PlanLedger()
        val before = perceptionNearBush(requestId = "r1")
        ledger.shown(agent, before)
        ledger.acted(agent, "r1", "dig", "accepted")
        val after =
            perceptionNearBush(requestId = "r2").let {
                it.copy(
                    selfState =
                        it.selfState.copy(
                            inventorySummary =
                                it.selfState.inventorySummary + InventoryStack("i9", "item.minecraft.oak_log", 2),
                        ),
                )
            }
        val recent = ledger.shown(agent, after).recent.single()
        assertEquals("dig", recent.action)
        assertEquals(listOf(ItemChange("item.minecraft.oak_log", 2)), recent.changes)
    }
}
