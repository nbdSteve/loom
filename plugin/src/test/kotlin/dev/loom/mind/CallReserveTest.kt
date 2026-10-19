package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.loom.contract.DecisionImportance as Importance

/** Each agent's share of a run's calls, kept for its scheduled reviews (r20 stopped at tick 8465). */
class CallReserveTest {
    private val alpha = AgentId("alpha")

    private fun review(id: String) = perception(requestId = id, agentId = "alpha", trigger = Trigger.GoalReview)

    private fun damage(id: String) =
        perception(
            requestId = id,
            agentId = "alpha",
            trigger = Trigger.Damaged(3, "zombie"),
        )

    private fun event(id: String) = perception(requestId = id, agentId = "alpha", trigger = Trigger.StepSettled)

    @Test
    fun `events spend only what the scheduled reviews still due leave`() {
        // A share of 15 calls for 5 scheduled reviews: ten events or follow-ups, then none.
        val reserve = CallReserve(perAgentCeiling = 15, scheduledPerAgent = 5)
        // Eight events, past which the last five calls are the reviews' and two are kept urgent.
        repeat(8) { assertTrue(reserve.admit(alpha, event("e$it")), "event $it") }
        assertFalse(reserve.admit(alpha, event("e8")), "the last calls are the reviews' and danger's")
        repeat(5) { assertTrue(reserve.admit(alpha, review("r$it")), "review $it") }
        repeat(2) { assertTrue(reserve.admit(alpha, damage("d$it")), "urgent $it") }
        assertFalse(reserve.admit(alpha, review("r5")), "nothing past the share")
        assertFalse(reserve.admit(alpha, damage("d2")), "nor any danger")
    }

    @Test
    fun `damage comes before sightings and follow-ups inside what the reviews leave`() {
        // v29 rehearsal gamma: sightings and follow-ups spent its share, and the bites were refused.
        val reserve = CallReserve(perAgentCeiling = 15, scheduledPerAgent = 5)
        val sighting =
            Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.0, dev.loom.contract.Bearing.NORTH, true)
        repeat(
            8,
        ) { assertTrue(reserve.admit(alpha, perception(requestId = "t$it", agentId = "alpha", trigger = sighting))) }
        assertFalse(reserve.admit(alpha, perception(requestId = "t8", agentId = "alpha", trigger = sighting)))
        assertFalse(reserve.admit(alpha, perception(requestId = "s", agentId = "alpha", trigger = Trigger.StepSettled)))
        // Urgent danger is held only to the ceiling, ahead of the five reviews still due (r24: theta's
        // damage at health 1 was refused to keep calls for reviews it never lived to hold).
        repeat(7) { assertTrue(reserve.admit(alpha, damage("d$it")), "damage $it") }
        assertFalse(reserve.admit(alpha, damage("d7")), "the agent's own ceiling still holds")
        assertFalse(reserve.admit(alpha, review("r1")), "and the review it preempted is not sent")
        assertEquals(2, URGENT_CALL_RESERVE)
    }

    @Test
    fun `a review done frees nothing, and an unbounded run sends everything`() {
        val reserve = CallReserve(perAgentCeiling = 3, scheduledPerAgent = 2)
        assertTrue(reserve.admit(alpha, review("r1")))
        assertFalse(reserve.admit(alpha, event("e1")), "one review still due and the urgent calls keep the rest")
        assertTrue(reserve.admit(alpha, damage("d1")), "damage is held only to the ceiling")
        assertTrue(reserve.admit(alpha, review("r2")))
        assertFalse(reserve.admit(alpha, damage("d2")), "the ceiling is spent")
        repeat(100) { assertTrue(CallReserve.UNBOUNDED.admit(alpha, damage("u$it"))) }
    }

    @Test
    fun `a perception the reserve holds back ends as a typed unavailable call`() {
        var sent = 0
        val bridge =
            ReservingCognitionBridge(
                { perception ->
                    sent += 1
                    java.util.concurrent.CompletableFuture.completedFuture(decisionFor(perception))
                },
                CallReserve(perAgentCeiling = 1, scheduledPerAgent = 1),
            )
        val held = bridge.submit(event("e1"))
        val thrown = assertThrows(java.util.concurrent.ExecutionException::class.java) { held.get() }
        assertEquals(CognitionFailure.CallReserved, (thrown.cause as CognitionUnavailableException).failure)
        assertEquals("callReserved", CognitionFailure.CallReserved.tag)
        assertTrue(bridge.submit(review("r1")).isDone)
        assertEquals(1, sent)
    }

    @Test
    fun `the reserve is read from the environment, and a bad share stops the plugin`() {
        val read = CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "2", CallReserve.SCHEDULED_ENV to "1"))
        assertFalse(read.admit(alpha, event("e1")))
        assertTrue(read.admit(alpha, damage("d1")))
        assertTrue(read.admit(alpha, review("r1")))
        assertFalse(read.admit(alpha, damage("d2")))
        assertEquals(true, CallReserve.fromEnvironment(emptyMap()).admit(alpha, event("e1")))
        assertThrows(IllegalArgumentException::class.java) {
            CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "many"))
        }
    }

    @Test
    fun `the global ceiling keeps only what the living still owe, so a dead agent's reviews are freed`() {
        // r22: fixed shares of 15 refused 21 danger calls while 22 of the 150 went unused.
        val beta = AgentId("beta")
        var betaDead = false
        val reserve =
            CallReserve(
                perAgentCeiling = 24,
                scheduledPerAgent = 5,
                globalCeiling = 20,
                agents = 2,
                isDead = { it == beta && betaDead },
            )
        // beta reviews once, so the two agents still owe nine reviews of the twenty calls.
        assertTrue(reserve.admit(beta, perception(requestId = "b1", agentId = "beta", trigger = Trigger.GoalReview)))
        repeat(6) { assertTrue(reserve.admit(alpha, event("d$it")), "event $it") }
        assertFalse(reserve.admit(alpha, event("d6")), "the living's reviews and urgent calls keep the rest")
        // beta dies: its four reviews still due and its urgent calls are no longer owed.
        betaDead = true
        repeat(6) { assertTrue(reserve.admit(alpha, event("e$it")), "freed $it") }
        assertFalse(reserve.admit(alpha, event("e6")), "alpha's own five reviews are still kept globally")
        assertTrue(reserve.admit(alpha, damage("u1")), "while damage is held only to the ceiling")
    }

    @Test
    fun `an agent may spend past an even share of the global ceiling, up to its own`() {
        val reserve = CallReserve(perAgentCeiling = 24, scheduledPerAgent = 5, globalCeiling = 150, agents = 10)
        repeat(17) { assertTrue(reserve.admit(alpha, event("d$it")), "event $it") }
        assertFalse(reserve.admit(alpha, event("d17")), "24 less its own five reviews and two urgent calls")
        val read =
            CallReserve.fromEnvironment(
                mapOf(
                    CallReserve.CALLS_ENV to "24",
                    CallReserve.GLOBAL_ENV to "150",
                    CallReserve.AGENTS_ENV to "10",
                    CallReserve.SCHEDULED_ENV to "5",
                ),
            )
        repeat(17) { assertTrue(read.admit(alpha, event("r$it"))) }
        assertFalse(read.admit(alpha, event("r17")))
    }

    @Test
    fun `a pivotal sighting is urgent like damage, and distant ones leave every living agent its urgent calls`() {
        // r23: creepers at 3.8 and 6.7 blocks were refused after notable flights from hunters
        // more than twelve blocks off had spent a fifth of the ceiling.
        val reserve = CallReserve(perAgentCeiling = 24, scheduledPerAgent = 0, globalCeiling = 10, agents = 3)
        val far = Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 14.0, dev.loom.contract.Bearing.NORTH, true)
        val near = far.copy(distance = 3.8)
        val sighted = { id: String, trigger: Trigger, importance: Importance ->
            perception(requestId = id, agentId = "alpha", trigger = trigger, importance = importance)
        }
        // Three living agents keep two urgent calls each: six of the ten.
        repeat(4) {
            assertTrue(
                reserve.admit(alpha, sighted("f$it", far, Importance.NOTABLE)),
                "far $it",
            )
        }
        assertFalse(reserve.admit(alpha, sighted("f4", far, Importance.NOTABLE)))
        assertTrue(reserve.admit(alpha, sighted("n1", near, Importance.PIVOTAL)))
        assertTrue(reserve.admit(alpha, damage("d1")))
    }

    @Test
    fun `pivotal calls are held to their own ceiling inside the JVM, damage last`() {
        // The survival envelope's 130 pivotal calls: the proxy refuses past them and that ends the
        // run, so the JVM holds the bound first, as a typed callReserved.
        val reserve =
            CallReserve(
                perAgentCeiling = 40,
                scheduledPerAgent = 0,
                globalCeiling = 378,
                agents = 1,
                pivotalCeiling = 4,
            )
        val pivotal = {
                id: String ->
            perception(
                requestId = id,
                agentId = "alpha",
                trigger = Trigger.StepSettled,
                importance = Importance.PIVOTAL,
            )
        }
        // One living agent keeps two pivotal calls for damage: two sightings or reviews, then damage.
        repeat(2) { assertTrue(reserve.admit(alpha, pivotal("p$it")), "pivotal $it") }
        assertFalse(reserve.admit(alpha, pivotal("p2")), "the rest of the pivotal pool is damage's")
        val bite = {
                id: String ->
            perception(
                requestId = id,
                agentId = "alpha",
                trigger = Trigger.Damaged(3, "zombie"),
                importance = Importance.PIVOTAL,
            )
        }
        repeat(2) { assertTrue(reserve.admit(alpha, bite("d$it")), "damage $it") }
        assertFalse(reserve.admit(alpha, bite("d2")), "the pivotal ceiling itself")
        assertTrue(reserve.admit(alpha, review("r1")), "an ordinary review is still sent")
        assertEquals(
            4,
            CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "40", CallReserve.PIVOTAL_ENV to "4")).let {
                    read ->
                (0 until 6).count { read.admit(alpha, bite("e$it")) }
            },
        )
    }
}
