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
        repeat(8) { assertTrue(reserve.admits(alpha, event("e$it")), "event $it") }
        assertFalse(reserve.admits(alpha, event("e8")), "the last calls are the reviews' and danger's")
        repeat(5) { assertTrue(reserve.admits(alpha, review("r$it")), "review $it") }
        repeat(2) { assertTrue(reserve.admits(alpha, damage("d$it")), "urgent $it") }
        assertFalse(reserve.admits(alpha, review("r5")), "nothing past the share")
        assertFalse(reserve.admits(alpha, damage("d2")), "nor any danger")
    }

    @Test
    fun `damage comes before sightings and follow-ups inside what the reviews leave`() {
        // v29 rehearsal gamma: sightings and follow-ups spent its share, and the bites were refused.
        val reserve = CallReserve(perAgentCeiling = 15, scheduledPerAgent = 5)
        val sighting =
            Trigger.ThreatSighted("entity.minecraft.zombie", "e1", 6.0, dev.loom.contract.Bearing.NORTH, true)
        repeat(
            8,
        ) { assertTrue(reserve.admits(alpha, perception(requestId = "t$it", agentId = "alpha", trigger = sighting))) }
        assertFalse(reserve.admits(alpha, perception(requestId = "t8", agentId = "alpha", trigger = sighting)))
        assertFalse(
            reserve.admits(alpha, perception(requestId = "s", agentId = "alpha", trigger = Trigger.StepSettled)),
        )
        // Urgent danger is held only to the ceiling, ahead of the five reviews still due (r24: theta's
        // damage at health 1 was refused to keep calls for reviews it never lived to hold).
        repeat(7) { assertTrue(reserve.admits(alpha, damage("d$it")), "damage $it") }
        assertFalse(reserve.admits(alpha, damage("d7")), "the agent's own ceiling still holds")
        assertFalse(reserve.admits(alpha, review("r1")), "and the review it preempted is not sent")
        assertEquals(2, URGENT_CALL_RESERVE)
    }

    @Test
    fun `a review done frees nothing, and an unbounded run sends everything`() {
        val reserve = CallReserve(perAgentCeiling = 3, scheduledPerAgent = 2)
        assertTrue(reserve.admits(alpha, review("r1")))
        assertFalse(reserve.admits(alpha, event("e1")), "one review still due and the urgent calls keep the rest")
        assertTrue(reserve.admits(alpha, damage("d1")), "damage is held only to the ceiling")
        assertTrue(reserve.admits(alpha, review("r2")))
        assertFalse(reserve.admits(alpha, damage("d2")), "the ceiling is spent")
        repeat(100) { assertTrue(CallReserve.UNBOUNDED.admits(alpha, damage("u$it"))) }
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
        assertFalse(read.admits(alpha, event("e1")))
        assertTrue(read.admits(alpha, damage("d1")))
        assertTrue(read.admits(alpha, review("r1")))
        assertFalse(read.admits(alpha, damage("d2")))
        assertEquals(true, CallReserve.fromEnvironment(emptyMap()).admits(alpha, event("e1")))
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
        assertTrue(reserve.admits(beta, perception(requestId = "b1", agentId = "beta", trigger = Trigger.GoalReview)))
        repeat(6) { assertTrue(reserve.admits(alpha, event("d$it")), "event $it") }
        assertFalse(reserve.admits(alpha, event("d6")), "the living's reviews and urgent calls keep the rest")
        // beta dies: its four reviews still due and its urgent calls are no longer owed.
        betaDead = true
        repeat(6) { assertTrue(reserve.admits(alpha, event("e$it")), "freed $it") }
        assertFalse(reserve.admits(alpha, event("e6")), "alpha's own five reviews are still kept globally")
        assertTrue(reserve.admits(alpha, damage("u1")), "while damage is held only to the ceiling")
    }

    @Test
    fun `an agent may spend past an even share of the global ceiling, up to its own`() {
        val reserve = CallReserve(perAgentCeiling = 24, scheduledPerAgent = 5, globalCeiling = 150, agents = 10)
        repeat(17) { assertTrue(reserve.admits(alpha, event("d$it")), "event $it") }
        assertFalse(reserve.admits(alpha, event("d17")), "24 less its own five reviews and two urgent calls")
        val read =
            CallReserve.fromEnvironment(
                mapOf(
                    CallReserve.CALLS_ENV to "24",
                    CallReserve.GLOBAL_ENV to "150",
                    CallReserve.AGENTS_ENV to "10",
                    CallReserve.SCHEDULED_ENV to "5",
                ),
            )
        repeat(17) { assertTrue(read.admits(alpha, event("r$it"))) }
        assertFalse(read.admits(alpha, event("r17")))
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
                reserve.admits(alpha, sighted("f$it", far, Importance.NOTABLE)),
                "far $it",
            )
        }
        assertFalse(reserve.admits(alpha, sighted("f4", far, Importance.NOTABLE)))
        assertTrue(reserve.admits(alpha, sighted("n1", near, Importance.PIVOTAL)))
        assertTrue(reserve.admits(alpha, damage("d1")))
    }

    @Test
    fun `a fallback is routed to the ordinary tier and told, once the pivotal ceiling is spent`() {
        val routed = mutableListOf<Importance>()
        val told = mutableListOf<String>()
        val bridge =
            ReservingCognitionBridge(
                {
                    routed += it.importance
                    java.util.concurrent.CompletableFuture<dev.loom.contract.Decision>()
                },
                CallReserve(
                    perAgentCeiling = 40,
                    scheduledPerAgent = 0,
                    globalCeiling = 378,
                    agents = 1,
                    pivotalCeiling = 1,
                ),
            ) { told += it.requestId }
        val bite = { id: String ->
            perception(
                requestId = id,
                agentId = "alpha",
                trigger = Trigger.Damaged(3, "zombie"),
                importance = Importance.PIVOTAL,
            )
        }
        bridge.submit(bite("d0"))
        bridge.submit(bite("d1"))
        assertEquals(listOf(Importance.PIVOTAL, Importance.NOTABLE), routed)
        assertEquals(listOf("d1"), told)
    }

    @Test
    fun `a tier fallback spends the ordinary ceilings, and a hard ceiling still refuses`() {
        val reserve =
            CallReserve(perAgentCeiling = 3, scheduledPerAgent = 0, globalCeiling = 3, agents = 1, pivotalCeiling = 1)
        val bite = { id: String ->
            perception(
                requestId = id,
                agentId = "alpha",
                trigger = Trigger.Damaged(3, "zombie"),
                importance = Importance.PIVOTAL,
            )
        }
        assertEquals(CallReserve.Admission.SENT, reserve.admit(alpha, bite("d0")))
        assertEquals(CallReserve.Admission.TIER_FALLBACK, reserve.admit(alpha, bite("d1")))
        assertEquals(CallReserve.Admission.TIER_FALLBACK, reserve.admit(alpha, bite("d2")))
        // The per-agent and global ceilings are never raised: the fourth call is a typed refusal.
        assertEquals(CallReserve.Admission.REFUSED, reserve.admit(alpha, bite("d3")))
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
        repeat(2) { assertTrue(reserve.admits(alpha, pivotal("p$it")), "pivotal $it") }
        assertFalse(reserve.admits(alpha, pivotal("p2")), "the rest of the pivotal pool is damage's")
        val bite = {
                id: String ->
            perception(
                requestId = id,
                agentId = "alpha",
                trigger = Trigger.Damaged(3, "zombie"),
                importance = Importance.PIVOTAL,
            )
        }
        repeat(2) { assertTrue(reserve.admits(alpha, bite("d$it")), "damage $it") }
        // Past the pivotal ceiling, damage falls back to the ordinary tier inside the other two
        // ceilings (r33: alpha's damage was refused there with 138 global calls unspent).
        assertEquals(
            CallReserve.Admission.TIER_FALLBACK,
            reserve.admit(alpha, bite("d2")),
            "the pivotal ceiling itself",
        )
        // A pivotal review or follow-up is no danger: it is still refused there, and visibly.
        assertEquals(CallReserve.Admission.REFUSED, reserve.admit(alpha, pivotal("p3")))
        assertTrue(reserve.admits(alpha, review("r1")), "an ordinary review is still sent")
        assertEquals(
            4,
            CallReserve.fromEnvironment(mapOf(CallReserve.CALLS_ENV to "40", CallReserve.PIVOTAL_ENV to "4")).let {
                    read ->
                (0 until 6).count { read.admits(alpha, bite("e$it")) }
            },
        )
    }

    @Test
    fun `urgent danger past its own ceiling borrows unused global calls and leaves the others theirs`() {
        // R41 at alpha's refused damage call (body:00002881, tick 11947): every agent had held its five
        // scheduled reviews, alpha had sent 40 (two of them flights from danger), its peers 27 to 38, and
        // 347 of the 378 global calls were spent. Every bite after that was refused until it died.
        val sent =
            mapOf(
                "alpha" to 40, "beta" to 27, "delta" to 38, "epsilon" to 30, "eta" to 33,
                "gamma" to 38, "iota" to 27, "kappa" to 38, "theta" to 38, "zeta" to 38,
            )
        val reserve =
            CallReserve(
                perAgentCeiling = 40,
                scheduledPerAgent = 5,
                globalCeiling = 378,
                agents = 10,
                pivotalCeiling = 130,
            )
        for ((agent, calls) in sent) {
            val id = AgentId(agent)
            repeat(5) {
                val scheduled = perception(requestId = "$agent-r$it", agentId = agent, trigger = Trigger.GoalReview)
                assertTrue(reserve.admits(id, scheduled))
            }
            val urgent = if (agent == "alpha") 2 else 0
            repeat(calls - 5 - urgent) {
                val settled = perception(requestId = "$agent-s$it", agentId = agent, trigger = Trigger.StepSettled)
                assertTrue(reserve.admits(id, settled))
            }
            repeat(urgent) { assertTrue(reserve.admits(id, damage("$agent-d$it"))) }
        }
        // The spider's first bite past alpha's 40 is sent: 347 + 1 leaves the nine others their 18.
        assertTrue(reserve.admits(alpha, damage("bite-11947")))
        // It borrows only down to what every other living agent keeps for its own danger.
        var borrowed = 1
        while (reserve.admits(alpha, damage("bite-$borrowed"))) borrowed += 1
        assertEquals(378 - 347 - 2 * 9, borrowed)
        // A follow-up past the ceiling borrows nothing, and the others' urgent calls are still there.
        assertFalse(reserve.admits(alpha, event("after")))
        val bite = perception(requestId = "beta-bite", agentId = "beta", trigger = Trigger.Damaged(3, "zombie"))
        assertTrue(reserve.admits(AgentId("beta"), bite))
    }
}
