/*
 * The single point where a raw command argument becomes an `AgentId`.
 *
 * Shared by every Loom command rather than written out in each, because it is the one
 * place a typed refusal turns back into something a person reading a console can act on -
 * and two of those places would eventually disagree about the wording.
 */
package dev.loom.plugin

import dev.loom.body.AgentId

/**
 * Runs [body] with [raw] as an [AgentId], or explains why it is not one.
 *
 * [AgentId] refuses ids by design (blank, the reserved world id), and a typed argument
 * must get the same friendly reply as every other invalid input rather than escaping the
 * command as an exception. The refusal message comes from the domain rule itself, so it is
 * never restated here.
 */
internal fun withAgent(
    raw: String,
    body: (AgentId) -> String,
): String {
    val agent =
        try {
            AgentId(raw)
        } catch (refused: IllegalArgumentException) {
            return refused.message ?: "invalid agentId: $raw"
        }
    return body(agent)
}
