/*
 * Which danger answers still with the mind a newer damage has superseded.
 *
 * Damage from a different attacker than the danger answer still being decided, or at or below
 * LOW_HEALTH, must not wait behind it: it is sent at once, and the older answer is discarded when
 * it arrives, so the newer answer governs the body (r27: alpha's and zeta's hits from new zombies
 * were coalesced behind a creeper answer, iota's at 4.5 and 2.5 health behind its own, and each
 * died). Main thread only.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.contract.reviewsGoal

internal class DangerPreemption {
    /** The danger subject each in-flight event perception was sent about, by request. */
    private val subjects = mutableMapOf<String, String?>()

    /** Event requests still with the mind whose answers a preempting damage has superseded. */
    private val superseded = mutableSetOf<String>()

    /**
     * Whether damage [perception] preempts an older event of [agentId]'s in [inFlight]; when it
     * does, every such older event is marked superseded.
     */
    fun preempts(
        agentId: AgentId,
        perception: Perception,
        subject: String?,
        inFlight: Map<String, Pair<AgentId, Perception>>,
    ): Boolean {
        if (perception.trigger !is Trigger.Damaged) return false
        val older = inFlight.filter { (_, held) -> held.first == agentId && !held.second.trigger.reviewsGoal }.keys
        val preempting =
            older.isNotEmpty() &&
                (perception.selfState.health <= LOW_HEALTH || subject != null && older.any { subjects[it] != subject })
        if (preempting) superseded += older
        return preempting
    }

    /** Records the subject an event perception [sent] was sent about. */
    fun sent(
        sent: Perception,
        subject: String?,
    ) {
        if (!sent.trigger.reviewsGoal) subjects[sent.requestId] = subject
    }

    /** The answer to [requestId] arrived: whether it was superseded, and so is to be discarded. */
    fun settled(requestId: String): Boolean {
        subjects -= requestId
        return superseded.remove(requestId)
    }
}
