/*
 * Fair per-agent admission in front of the globally bounded cognition bridge.
 */
package dev.loom.mind

import dev.loom.contract.Decision
import dev.loom.contract.Perception
import java.util.concurrent.CompletableFuture

/**
 * Prevents one agent from consuming every shared cognition worker and queue slot.
 *
 * This budget counts submitted requests until their future settles. It neither
 * changes a perception nor schedules work itself; the wrapped bridge retains
 * the global worker, queue, and deadline bounds.
 */
class PerAgentCognitionBridge(
    private val delegate: CognitionBridge,
    private val maxOutstandingPerAgent: Int,
    private val onFailure: (Perception, CognitionFailure) -> Unit = { _, _ -> },
) : CognitionBridge {
    override val outstandingPerAgent: Int get() = maxOutstandingPerAgent

    private val lock = Any()
    private val outstanding = mutableMapOf<String, Int>()

    init {
        require(maxOutstandingPerAgent > 0) {
            "maxOutstandingPerAgent must be positive"
        }
    }

    override fun submit(perception: Perception): CompletableFuture<Decision> =
        if (reserve(perception.agentId)) {
            submitReserved(perception)
        } else {
            refuse(perception)
        }

    private fun submitReserved(perception: Perception): CompletableFuture<Decision> {
        val submission = runCatching { delegate.submit(perception) }
        val failure = submission.exceptionOrNull()
        if (failure != null) {
            release(perception.agentId)
            return CompletableFuture.failedFuture(failure)
        }
        val decided = submission.getOrThrow()
        decided.whenComplete { _, _ -> release(perception.agentId) }
        return decided
    }

    private fun refuse(perception: Perception): CompletableFuture<Decision> {
        val failure = CognitionFailure.AgentBudgetExhausted(maxOutstandingPerAgent)
        onFailure(perception, failure)
        return CompletableFuture.failedFuture(
            CognitionUnavailableException(
                failure,
                "cognition for agent ${perception.agentId} (request ${perception.requestId}) " +
                    "was refused by its per-agent outstanding-request budget",
            ),
        )
    }

    internal fun outstandingFor(agentId: String): Int = synchronized(lock) { outstanding[agentId] ?: 0 }

    private fun reserve(agentId: String): Boolean =
        synchronized(lock) {
            val current = outstanding[agentId] ?: 0
            if (current >= maxOutstandingPerAgent) {
                false
            } else {
                outstanding[agentId] = current + 1
                true
            }
        }

    private fun release(agentId: String) {
        synchronized(lock) {
            val remaining = (outstanding[agentId] ?: 1) - 1
            if (remaining <= 0) {
                outstanding.remove(agentId)
            } else {
                outstanding[agentId] = remaining
            }
        }
    }
}
