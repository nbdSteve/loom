/*
 * The hard ceilings on a run's model calls.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Perception
import java.util.concurrent.CompletableFuture

/**
 * Keeps a run's calls inside its hard ceilings - [perAgentCeiling] for one agent, [globalCeiling]
 * for all of them, [pivotalCeiling] for the costly tier - and nothing more: no calls are kept back
 * for later reviews. An agent past its own ceiling borrows from a configured global one, and a
 * pivotal call past the pivotal ceiling falls back to the ordinary tier. A run without a plan is
 * [UNBOUNDED].
 */
class CallReserve(
    private val perAgentCeiling: Int,
    private val globalCeiling: Int = Int.MAX_VALUE,
    /** The most pivotal calls the run may make, the costly tier's own ceiling; unbounded when absent. */
    private val pivotalCeiling: Int = Int.MAX_VALUE,
) {
    init {
        require(perAgentCeiling >= 0 && globalCeiling >= 0) { "a call reserve counts calls, never fewer than none" }
    }

    /** What the reserve makes of one perception. */
    enum class Admission {
        /** Sent at its own tier. */
        SENT,

        /** Pivotal, past the pivotal ceiling: sent at the ordinary tier inside the other ceilings. */
        TIER_FALLBACK,

        /** Not sent: a hard ceiling. */
        REFUSED,
    }

    private val sent = mutableMapOf<AgentId, Int>()
    private var pivotalSent = 0L

    /** Whether [perception] may be sent now, at its tier or the ordinary one; records it when it may. */
    @Synchronized
    fun admit(
        agentId: AgentId,
        perception: Perception,
    ): Admission {
        val calls = sent[agentId] ?: 0
        val total = sent.values.sum()
        val pivotal = perception.importance == DecisionImportance.PIVOTAL
        val borrows = globalCeiling != Int.MAX_VALUE
        val admission =
            when {
                total >= globalCeiling || (calls >= perAgentCeiling && !borrows) -> Admission.REFUSED
                !pivotal || pivotalSent < pivotalCeiling -> Admission.SENT
                else -> Admission.TIER_FALLBACK
            }
        if (admission != Admission.REFUSED) {
            if (pivotal && admission == Admission.SENT) pivotalSent += 1
            sent[agentId] = calls + 1
        }
        return admission
    }

    companion object {
        /** No plan: every perception is sent. */
        val UNBOUNDED: CallReserve = CallReserve(Int.MAX_VALUE)

        /** The environment setting for one agent's ceiling on the run's calls. */
        const val CALLS_ENV: String = "LOOM_COGNITION_CALLS_PER_AGENT"

        /** The environment setting for the run's global ceiling on calls. */
        const val GLOBAL_ENV: String = "LOOM_COGNITION_CALLS_GLOBAL"

        /** The environment setting for the run's ceiling on pivotal calls. */
        const val PIVOTAL_ENV: String = "LOOM_COGNITION_CALLS_PIVOTAL"

        /** The reserve [environment] states, or [UNBOUNDED] when it states none. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CallReserve {
            val calls = environment[CALLS_ENV]?.trim() ?: return UNBOUNDED
            val ceiling = requireNotNull(calls.toIntOrNull()) { "$CALLS_ENV must be a count, was \"$calls\"" }

            fun count(name: String) = environment[name]?.trim()?.toIntOrNull()
            return CallReserve(
                perAgentCeiling = ceiling,
                globalCeiling = count(GLOBAL_ENV) ?: Int.MAX_VALUE,
                pivotalCeiling = count(PIVOTAL_ENV) ?: Int.MAX_VALUE,
            )
        }
    }
}

/**
 * Sends to [delegate] only what [reserve] admits, and fails the rest at once as
 * [CognitionFailure.BudgetRefused], so its chain ends as an ordinary unavailable perception.
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
                        CognitionFailure.BudgetRefused,
                        "cognition for agent ${perception.agentId} (request ${perception.requestId}) " +
                            "was not sent: a call ceiling is spent",
                    ),
                )
        }
}
