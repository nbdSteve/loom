/*
 * A dig sequence: one decision's blocks dug in order through the single-dig path.
 */
package dev.loom.body.player

import dev.loom.body.IntentOutcome
import dev.loom.contract.Intent

/**
 * Prepares each block's dig as one [Intent.Dig] on the main thread, then runs them in order until
 * one is not dug or a newer decision governs the body.
 */
internal fun prepareDigSequence(
    interactions: PlayerInteractionPreparer,
    preemption: PlayerPreemption,
    applied: AppliedDecision,
    intent: Intent.DigSequence,
): () -> ActionExecution {
    val (runtime, sense, requestId) = applied
    val steps = intent.blocks.map { interactions.prepare(runtime, Intent.Dig(it), requestId, sense) }
    return {
        val done = mutableListOf<ActionExecution>()
        val pending = steps.iterator()
        while (pending.hasNext() && (done.isEmpty() || continues(done.last(), preemption, applied))) {
            done += pending.next()()
        }
        ActionExecution(outcome = done.last().outcome, steps = done)
    }
}

private fun continues(
    last: ActionExecution,
    preemption: PlayerPreemption,
    applied: AppliedDecision,
): Boolean = last.outcome is IntentOutcome.Accepted && !preemption.outdated(applied.runtime, applied.requestId)

/** Audits each dig a sequence ran as its own interaction; the sequence is one action. */
internal fun PlayerEvidenceRecorder.auditSequence(
    runtime: PlayerRuntime,
    intent: Intent.DigSequence,
    requestId: String?,
    steps: List<ActionExecution>,
): IntentOutcome {
    val audited =
        steps.mapIndexed { index, step -> auditInteraction(runtime, Intent.Dig(intent.blocks[index]), requestId, step) }
    val outcomes = audited.map { it.outcome }
    val dug = outcomes.filterIsInstance<IntentOutcome.Accepted>()
    val last = outcomes.last()
    val outcome =
        when {
            dug.isEmpty() -> last
            else -> {
                val stopped = (last as? IntentOutcome.Rejected)?.let { "; stopped: ${it.reason}" }.orEmpty()
                IntentOutcome.Accepted(
                    "dug ${dug.size} of ${intent.blocks.size}$stopped",
                    dropped = dug.flatMap { it.dropped }.toSet(),
                )
            }
        }
    recordAction(runtime.identity.agentId, intent, requestId, outcome)
    audited.forEach { it.record() }
    return outcome
}
