/*
 * Which danger answers still with the mind a newer damage has superseded.
 *
 * Damage from a different attacker than the danger answer still being decided, or at or below
 * LOW_HEALTH, must not wait behind it: it is sent at once, and the older answer is discarded when
 * it arrives, so the newer answer governs the body (r27: alpha's and zeta's hits from new zombies
 * were coalesced behind a creeper answer, iota's at 4.5 and 2.5 health behind its own, and each
 * died). Main thread only.
 *
 * Preemption is bounded by the agent's own outstanding requests: an answer it supersedes is still
 * with the mind until it arrives, so a damage preempts only while the agent has a free slot and no
 * other preemption is already waiting on a superseded answer; any other such damage is [Verdict.DEFER],
 * held and told when the answer in flight settles (r30: gamma's damage at 6548 and again at 6568
 * each preempted, and the one at 6588 found the agent's two pending slots full).
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.contract.reviewsGoal

internal class DangerPreemption {
    /** What a perception's arrival means for the danger answers still with the mind. */
    enum class Verdict {
        /** Not a preempting damage: the ordinary one-event-at-a-time rule applies. */
        NONE,

        /** Sent at once, every older event of the agent's superseded. */
        PREEMPT,

        /** A preempting damage that cannot be sent now, held for when the answer in flight settles. */
        DEFER,
    }

    /** The danger subject each in-flight event perception was sent about, by request. */
    private val subjects = mutableMapOf<String, String?>()

    /** What a superseded answer's arrival means, by whether the answer that superseded it has come. */
    enum class Supersession {
        /** Never superseded: the ordinary path applies it. */
        NONE,

        /**
         * Superseded, but the newer answer is still with the mind: a ready flight is applied rather
         * than thrown away, and the newer answer replaces it when it is ready (r33: gamma, kappa, iota
         * and delta each lost a correct ready flight to a newer perception, and gamma livelocked).
         */
        NEWER_PENDING,

        /** Superseded, and the newer answer already governs the body: this one is discarded. */
        NEWER_ANSWERED,
    }

    /** Event requests still with the mind that a preempting damage superseded, to the one that did. */
    private val superseded = mutableMapOf<String, String>()

    /**
     * Whether damage [perception] preempts an older event of [agentId]'s in [inFlight]; when it
     * does, every such older event is marked superseded.
     */
    fun preempts(
        agentId: AgentId,
        perception: Perception,
        subject: String?,
        inFlight: Map<String, Pair<AgentId, Perception>>,
        pendingPerAgent: Int = Int.MAX_VALUE,
    ): Verdict {
        if (perception.trigger !is Trigger.Damaged) return Verdict.NONE
        val own = inFlight.filter { (_, held) -> held.first == agentId }.keys
        val older = own.filter { key -> inFlight.getValue(key).second.trigger.reviewsGoal.not() }
        val preempting =
            older.isNotEmpty() &&
                (perception.selfState.health <= LOW_HEALTH || subject != null && older.any { subjects[it] != subject })
        return when {
            !preempting -> Verdict.NONE
            own.size >= pendingPerAgent || older.any { it in superseded } -> Verdict.DEFER
            else -> {
                older.forEach { superseded[it] = perception.requestId }
                Verdict.PREEMPT
            }
        }
    }

    /** Records the subject an event perception [sent] was sent about. */
    fun sent(
        sent: Perception,
        subject: String?,
    ) {
        if (!sent.trigger.reviewsGoal) subjects[sent.requestId] = subject
    }

    /** The answer to [requestId] arrived: whether it was superseded, and whether its successor has come. */
    fun settled(requestId: String): Supersession {
        subjects -= requestId
        val newer = superseded.remove(requestId) ?: return Supersession.NONE
        return if (newer in subjects) Supersession.NEWER_PENDING else Supersession.NEWER_ANSWERED
    }
}
