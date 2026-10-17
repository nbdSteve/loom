/*
 * Each agent's share of a run's model calls, kept for the scheduled reviews still due.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Decision
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletableFuture

/** The calls each agent's share keeps for damage beyond its scheduled reviews still due. */
const val DAMAGE_CALL_RESERVE: Int = 2

/**
 * Keeps each agent's calls inside its share of a run's ceiling with room for every scheduled
 * review still due: a perception that is not a scheduled review is sent only when, after it,
 * the agent's remaining share still holds its remaining scheduled reviews.
 *
 * The research harness refuses calls only at its own global ceiling, and the run ends there: in
 * r20 the night's 49 danger calls and the follow-ups spent all 150 by tick 8465, and the v29
 * rehearsal's 80 by tick 8964, so the last scheduled reviews never ran. With [callsPerAgent] the
 * global ceiling divided among the agents and [scheduledPerAgent] the plan's reviews, no agent's
 * events can cost another agent - or itself - a scheduled review. Inside what is left, damage
 * comes before every other event: a sighting or a follow-up is sent only while it also leaves
 * [DAMAGE_CALL_RESERVE] calls for damage, because in the v29 rehearsal gamma's sightings and
 * follow-ups spent its share and the zombie villager's bites that killed it were refused. A run
 * without a plan is [UNBOUNDED]. Main thread only.
 */
class CallReserve(
    private val callsPerAgent: Int,
    private val scheduledPerAgent: Int,
) {
    init {
        require(callsPerAgent >= 0 && scheduledPerAgent >= 0) { "a call reserve counts calls, never fewer than none" }
    }

    private val sent = mutableMapOf<AgentId, Int>()
    private val scheduled = mutableMapOf<AgentId, Int>()

    /** Whether [perception] may be sent now; records it when it may. */
    @Synchronized
    fun admit(
        agentId: AgentId,
        perception: Perception,
    ): Boolean {
        val review = perception.trigger == Trigger.GoalReview
        val calls = sent[agentId] ?: 0
        val owed = (scheduledPerAgent - (scheduled[agentId] ?: 0)).coerceAtLeast(0)
        val kept = if (perception.trigger is Trigger.Damaged) owed else owed + DAMAGE_CALL_RESERVE
        val admitted = if (review) calls < callsPerAgent else calls + 1 + kept <= callsPerAgent
        if (admitted) {
            sent[agentId] = calls + 1
            if (review) scheduled[agentId] = (scheduled[agentId] ?: 0) + 1
        }
        return admitted
    }

    companion object {
        /** No plan to keep a reserve for: every perception is sent. */
        val UNBOUNDED: CallReserve = CallReserve(Int.MAX_VALUE, 0)

        /** The environment setting for one agent's share of the run's calls. */
        const val CALLS_ENV: String = "LOOM_COGNITION_CALLS_PER_AGENT"

        /** The environment setting for the scheduled reviews the plan expects of each agent. */
        const val SCHEDULED_ENV: String = "LOOM_EXPECTED_REVIEWS_PER_AGENT"

        /** The reserve [environment] states, or [UNBOUNDED] when it states none. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CallReserve {
            val calls = environment[CALLS_ENV]?.trim() ?: return UNBOUNDED
            val share = requireNotNull(calls.toIntOrNull()) { "$CALLS_ENV must be a count, was \"$calls\"" }
            val reviews = environment[SCHEDULED_ENV]?.trim()?.toIntOrNull() ?: 0
            return CallReserve(share, reviews)
        }
    }
}

/**
 * Sends to [delegate] only what [reserve] admits, and fails the rest at once as
 * [CognitionFailure.CallReserved], so its chain ends as an ordinary unavailable perception.
 */
class ReservingCognitionBridge(
    private val delegate: CognitionBridge,
    private val reserve: CallReserve,
) : CognitionBridge {
    override fun submit(perception: Perception): CompletableFuture<Decision> =
        if (reserve.admit(AgentId(perception.agentId), perception)) {
            delegate.submit(perception)
        } else {
            CompletableFuture.failedFuture(
                CognitionUnavailableException(
                    CognitionFailure.CallReserved,
                    "cognition for agent ${perception.agentId} (request ${perception.requestId}) " +
                        "was not sent: its remaining calls are kept for its scheduled reviews",
                ),
            )
        }
}
