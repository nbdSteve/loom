/*
 * Whether a perception is sent to the mind now, or ends here coalesced, and why.
 *
 * Danger episodes, damage preemption and the one-review-one-event rule decide it together, against
 * the requests one agent may have outstanding through its bridge (r30: preempting damage was sent
 * past gamma's two pending slots, and the third found the bridge full). Main thread only.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.contract.reviewsGoal

internal class DangerAdmission(
    private val danger: DangerEpisodes,
    private val preemption: DangerPreemption,
    private val outstandingPerAgent: Int,
    /** Whether the run's exposure has ended: then nothing new is sent at all (`ExposureGate`). */
    private val exposureEnded: () -> Boolean = { false },
) {
    /**
     * The coalescing tag and detail [perception] ends with, or `null` when it is to be sent;
     * [inFlight] is every perception still with the mind, by request.
     */
    fun coalescedOf(
        agentId: AgentId,
        perception: Perception,
        subject: String?,
        inFlight: Map<String, Pair<AgentId, Perception>>,
    ): Pair<String, String>? =
        when {
            exposureEnded() -> EventTag.EXPOSURE_ENDED to "the run's exposure had ended; nothing new is sent"
            // The agent is already answering this threat.
            !danger.admit(agentId, perception, subject) ->
                EventTag.DANGER_COALESCED to "coalesced into the active danger episode for this threat"
            else -> preemptedOf(agentId, perception, subject, inFlight)
        }

    /**
     * Damage from a different attacker, or at low health, preempts the older danger answer still
     * with the mind (r27: alpha's and zeta's hits from new zombies were coalesced behind a creeper
     * answer, and they died), while the agent has a slot for it.
     */
    private fun preemptedOf(
        agentId: AgentId,
        perception: Perception,
        subject: String?,
        inFlight: Map<String, Pair<AgentId, Perception>>,
    ): Pair<String, String>? =
        when (preemption.preempts(agentId, perception, subject, inFlight, outstandingPerAgent)) {
            DangerPreemption.Verdict.PREEMPT -> null
            DangerPreemption.Verdict.DEFER -> {
                danger.withdraw(agentId, perception.requestId, perception.trigger as? Trigger.Damaged, subject)
                EventTag.DANGER_COALESCED to "held: the agent's preempting answer is still with the mind"
            }
            DangerPreemption.Verdict.NONE -> oneAtATime(agentId, perception, inFlight)
        }

    /** One review and one event at a time: the answer already coming covers this one. */
    private fun oneAtATime(
        agentId: AgentId,
        perception: Perception,
        inFlight: Map<String, Pair<AgentId, Perception>>,
    ): Pair<String, String>? {
        val review = perception.trigger.reviewsGoal
        val busy = inFlight.values.any { (agent, held) -> agent == agentId && held.trigger.reviewsGoal == review }
        if (!busy) return null
        danger.withdraw(agentId, perception.requestId)
        return EventTag.COGNITION_COALESCED to
            "coalesced: the agent already has cognition in flight for this kind of trigger"
    }
}
