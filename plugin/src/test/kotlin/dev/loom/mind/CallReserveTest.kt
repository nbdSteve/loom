package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Test
    fun `events spend only what the scheduled reviews still due leave`() {
        // A share of 15 calls for 5 scheduled reviews: ten events or follow-ups, then none.
        val reserve = CallReserve(callsPerAgent = 15, scheduledPerAgent = 5)
        repeat(10) { assertTrue(reserve.admit(alpha, damage("d$it")), "event $it") }
        assertFalse(reserve.admit(alpha, damage("d10")), "the last five calls are the reviews'")
        repeat(5) { assertTrue(reserve.admit(alpha, review("r$it")), "review $it") }
        assertFalse(reserve.admit(alpha, review("r5")), "nothing past the share")
        assertFalse(reserve.admit(alpha, perception(requestId = "s", agentId = "alpha", trigger = Trigger.StepSettled)))
    }

    @Test
    fun `a review done frees nothing, and an unbounded run sends everything`() {
        val reserve = CallReserve(callsPerAgent = 3, scheduledPerAgent = 2)
        assertTrue(reserve.admit(alpha, review("r1")))
        assertTrue(reserve.admit(alpha, damage("d1")))
        assertFalse(reserve.admit(alpha, damage("d2")), "one call left, and one review still due")
        assertTrue(reserve.admit(alpha, review("r2")))
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
                CallReserve(callsPerAgent = 1, scheduledPerAgent = 1),
            )
        val held = bridge.submit(damage("d1"))
        val thrown = assertThrows(java.util.concurrent.ExecutionException::class.java) { held.get() }
        assertEquals(CognitionFailure.CallReserved, (thrown.cause as CognitionUnavailableException).failure)
        assertEquals("callReserved", CognitionFailure.CallReserved.tag)
        assertTrue(bridge.submit(review("r1")).isDone)
        assertEquals(1, sent)
    }

    @Test
    fun `the reserve is read from the environment, and a bad share stops the plugin`() {
        val read = CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "2", CallReserve.SCHEDULED_ENV to "1"))
        assertTrue(read.admit(alpha, damage("d1")))
        assertFalse(read.admit(alpha, damage("d2")))
        assertEquals(true, CallReserve.fromEnvironment(emptyMap()).admit(alpha, damage("d1")))
        assertThrows(IllegalArgumentException::class.java) {
            CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "many"))
        }
    }
}
