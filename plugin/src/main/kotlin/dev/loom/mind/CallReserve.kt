/*
 * Each agent's share of a run's model calls, kept for the scheduled reviews still due.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletableFuture

/**
 * The calls each living agent keeps for urgent danger - damage, or a pivotal sighting - beyond its
 * scheduled reviews still due, in its own ceiling and in the pooled global one.
 */
const val URGENT_CALL_RESERVE: Int = 2

/**
 * Keeps a run's calls inside its two ceilings - [perAgentCeiling] for one agent and
 * [globalCeiling] for all [agents] - with room for every scheduled review still due: a
 * perception that is not a scheduled review is sent only when, after it, the agent's own
 * ceiling still holds its own reviews still due and the global ceiling still holds every
 * living agent's.
 *
 * Urgent danger - damage, or a pivotal sighting - is held only to the two ceilings themselves, and
 * so comes before every review still due (r24).
 *
 * The research harness refuses calls at its ceilings, and the run ends there: in r20 the night's
 * 49 danger calls and the follow-ups spent all 150 by tick 8465, so the last scheduled reviews
 * never ran. Inside what is left, urgent danger comes before every other event: a distant sighting
 * or a follow-up is sent only while it also leaves [URGENT_CALL_RESERVE] calls for damage and
 * pivotal sightings, for itself and for every other living agent (r23: notable flights from
 * hunters more than twelve blocks off spent a fifth of the ceiling, and creepers at 3.8 and 6.7
 * blocks were refused in the last 1100 ticks).
 *
 * Urgent danger past an agent's own ceiling borrows unused calls from the global one, while it
 * still leaves every living agent's reviews still due and every other living agent's urgent calls
 * (R41: alpha spent its 40 calls and took 120 ticks of a spider's hits with every damage call
 * refused, while 31 of the 378 global calls went unused). The global and pivotal ceilings, and so
 * the run's cost bound, are unchanged.
 *
 * The reserve is pooled, and a dead agent owes nothing: r22 split the global ceiling into fixed
 * shares of 15, refused 21 danger calls (four of them damage, the last of theta's at health 1.5)
 * while 22 of the 150 went unused, and alpha's and iota's unspent shares were stranded. So an
 * agent may spend up to its own ceiling, and what the global ceiling keeps is only the reviews
 * the living still owe. A run without a plan is [UNBOUNDED].
 */
class CallReserve(
    private val perAgentCeiling: Int,
    private val scheduledPerAgent: Int,
    private val globalCeiling: Int = Int.MAX_VALUE,
    private val agents: Int = 0,
    private val isDead: (AgentId) -> Boolean = { false },
    /** The most pivotal calls the run may make, the costly tier's own ceiling; unbounded when absent. */
    private val pivotalCeiling: Int = Int.MAX_VALUE,
) {
    init {
        require(perAgentCeiling >= 0 && scheduledPerAgent >= 0 && globalCeiling >= 0 && agents >= 0) {
            "a call reserve counts calls, never fewer than none"
        }
    }

    /** What the reserve makes of one perception. */
    enum class Admission {
        /** Sent at its own tier. */
        SENT,

        /**
         * Urgent danger the pivotal ceiling no longer fits, sent at the ordinary tier inside the
         * per-agent and global ceilings, which still do: a cheaper answer, never a refused one (r33:
         * alpha, the last agent alive, had three damage calls and five sightings refused at the
         * pivotal ceiling while 138 of the 378 global calls were unspent).
         */
        TIER_FALLBACK,

        /** Not sent: a hard ceiling, or the calls kept for reviews still due. */
        REFUSED,
    }

    private val sent = mutableMapOf<AgentId, Int>()
    private var pivotalSent = 0L
    private val scheduled = mutableMapOf<AgentId, Int>()

    /** Whether [perception] may be sent now, at its tier or the ordinary one; records it when it may. */
    @Synchronized
    fun admit(
        agentId: AgentId,
        perception: Perception,
    ): Admission {
        val review = perception.trigger == Trigger.GoalReview
        val calls = sent[agentId] ?: 0
        val total = sent.values.sum()
        val urgent = perception.trigger is Trigger.Damaged || perception.importance == DecisionImportance.PIVOTAL
        // Urgent danger preempts the reviews still due: a scheduled review that never runs costs a
        // goal step, and a refused damage call has cost an agent its life (r24: six damage calls and
        // eight pivotal sightings were refused to keep calls for reviews their agents never lived to
        // hold, theta's at health 1).
        val pivotal = perception.importance == DecisionImportance.PIVOTAL
        val pivotalFits = !pivotal || pivotalFits(agentId, perception)
        val ceilingsFit = ceilingsFit(agentId, review || urgent, calls, total) || (urgent && borrowFits(agentId, total))
        // Damage and threat sightings past the pivotal ceiling fall back to the ordinary tier.
        val admission =
            when {
                !ceilingsFit -> Admission.REFUSED
                pivotalFits -> Admission.SENT
                fallbackEligible(perception) -> Admission.TIER_FALLBACK
                else -> Admission.REFUSED
            }
        if (admission != Admission.REFUSED) record(agentId, review, pivotal && admission == Admission.SENT)
        return admission
    }

    private fun record(
        agentId: AgentId,
        review: Boolean,
        pivotal: Boolean,
    ) {
        if (pivotal) pivotalSent += 1
        sent[agentId] = (sent[agentId] ?: 0) + 1
        if (review) scheduled[agentId] = (scheduled[agentId] ?: 0) + 1
    }

    /**
     * Whether one more pivotal call fits the pivotal pool, the run's dearest: damage may spend all of
     * it, and a pivotal sighting or review leaves every living agent its urgent calls (r24 envelope:
     * the proxy refuses past it, and a refusal there ends the run, so the JVM holds the bound first).
     */
    private fun pivotalFits(
        agentId: AgentId,
        perception: Perception,
    ): Boolean {
        val keptPivotal =
            if (perception.trigger is Trigger.Damaged) 0L else URGENT_CALL_RESERVE.toLong() * livingCount(agentId)
        return pivotalSent + 1 + keptPivotal <= pivotalCeiling
    }

    /**
     * Whether one more call by [agentId] fits its own and the global ceiling: held only to the
     * ceilings themselves when [unreserved], else leaving every review still due and the living
     * agents' urgent calls.
     */
    private fun ceilingsFit(
        agentId: AgentId,
        unreserved: Boolean,
        calls: Int,
        total: Int,
    ): Boolean {
        if (unreserved) return calls < perAgentCeiling && total < globalCeiling
        val keptForAll = owedByTheLiving(agentId) + URGENT_CALL_RESERVE.toLong() * livingCount(agentId)
        val ownFits = calls + 1 + owedBy(agentId) + URGENT_CALL_RESERVE <= perAgentCeiling
        return ownFits && total.toLong() + 1 + keptForAll <= globalCeiling
    }

    /**
     * Whether urgent danger past [agentId]'s own ceiling fits a configured global one: after it, the
     * global ceiling still holds every living agent's reviews still due and every other living
     * agent's urgent calls. With no global ceiling there is nothing to borrow from.
     */
    private fun borrowFits(
        agentId: AgentId,
        total: Int,
    ): Boolean {
        if (globalCeiling == Int.MAX_VALUE) return false
        val keptForOthers = owedByTheLiving(agentId) + URGENT_CALL_RESERVE.toLong() * (livingCount(agentId) - 1)
        return total.toLong() + 1 + keptForOthers <= globalCeiling
    }

    /** Urgent damage and a close threat sighting, the danger a cheaper answer still serves. */
    private fun fallbackEligible(perception: Perception): Boolean =
        perception.trigger is Trigger.Damaged || perception.trigger is Trigger.ThreatSighted

    private fun owedBy(agentId: AgentId): Int =
        if (isDead(agentId)) 0 else (scheduledPerAgent - (scheduled[agentId] ?: 0)).coerceAtLeast(0)

    /** How many agents are alive, counting configured agents not yet heard from. */
    private fun livingCount(asking: AgentId): Long {
        val known = sent.keys + asking
        return known.count { !isDead(it) }.toLong() + (agents - known.size).coerceAtLeast(0)
    }

    /** The reviews every living agent still owes, counting configured agents not yet heard from. */
    private fun owedByTheLiving(asking: AgentId): Long {
        val known = sent.keys + asking
        val unheard = (agents - known.size).coerceAtLeast(0).toLong() * scheduledPerAgent
        return known.sumOf { owedBy(it).toLong() } + unheard
    }

    companion object {
        /** No plan to keep a reserve for: every perception is sent. */
        val UNBOUNDED: CallReserve = CallReserve(Int.MAX_VALUE, 0)

        /** The environment setting for one agent's ceiling on the run's calls. */
        const val CALLS_ENV: String = "LOOM_COGNITION_CALLS_PER_AGENT"

        /** The environment setting for the run's global ceiling on calls. */
        const val GLOBAL_ENV: String = "LOOM_COGNITION_CALLS_GLOBAL"

        /** The environment setting for how many agents share the global ceiling. */
        const val AGENTS_ENV: String = "LOOM_COGNITION_AGENTS"

        /** The environment setting for the run's ceiling on pivotal calls. */
        const val PIVOTAL_ENV: String = "LOOM_COGNITION_CALLS_PIVOTAL"

        /** The environment setting for the scheduled reviews the plan expects of each agent. */
        const val SCHEDULED_ENV: String = "LOOM_EXPECTED_REVIEWS_PER_AGENT"

        /** The reserve [environment] states, or [UNBOUNDED] when it states none. */
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            isDead: (AgentId) -> Boolean = { false },
        ): CallReserve {
            val calls = environment[CALLS_ENV]?.trim() ?: return UNBOUNDED
            val ceiling = requireNotNull(calls.toIntOrNull()) { "$CALLS_ENV must be a count, was \"$calls\"" }

            fun count(name: String) = environment[name]?.trim()?.toIntOrNull()
            return CallReserve(
                perAgentCeiling = ceiling,
                scheduledPerAgent = count(SCHEDULED_ENV) ?: 0,
                globalCeiling = count(GLOBAL_ENV) ?: Int.MAX_VALUE,
                agents = count(AGENTS_ENV) ?: 0,
                isDead = isDead,
                pivotalCeiling = count(PIVOTAL_ENV) ?: Int.MAX_VALUE,
            )
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
    /** Told of each perception sent at the ordinary tier because the pivotal ceiling no longer fits it. */
    private val onTierFallback: (Perception) -> Unit = {},
) : CognitionBridge {
    override val outstandingPerAgent: Int get() = delegate.outstandingPerAgent

    override fun submit(perception: Perception): CompletableFuture<Decision> =
        when (reserve.admit(AgentId(perception.agentId), perception)) {
            CallReserve.Admission.SENT -> delegate.submit(perception)
            CallReserve.Admission.TIER_FALLBACK -> {
                onTierFallback(perception)
                // Routed to the ordinary model, whose provenance the mind then stamps on the answer.
                delegate.submit(perception.copy(importance = DecisionImportance.NOTABLE))
            }
            CallReserve.Admission.REFUSED ->
                CompletableFuture.failedFuture(
                    CognitionUnavailableException(
                        CognitionFailure.CallReserved,
                        "cognition for agent ${perception.agentId} (request ${perception.requestId}) " +
                            "was not sent: its remaining calls are kept for its scheduled reviews",
                    ),
                )
        }
}
