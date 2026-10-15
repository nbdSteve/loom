/*
 * One-shot player speech: one bounded say per agent in flight, never a console broadcast.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.SayOutcome
import dev.loom.bodycontrol.SayText
import java.util.concurrent.ConcurrentHashMap

/** One say the body reported as sent; a client acknowledgement, not server attribution. */
data class SpeechClaim(
    val agentId: String,
    val requestId: String?,
    val controlRequestId: String,
    val text: String,
    val outcome: SayOutcome,
)

/** Where a sent say is handed for server-side corroboration. */
fun interface SpeechClaims {
    fun claimed(claim: SpeechClaim)

    companion object {
        val NONE: SpeechClaims = SpeechClaims { }
    }
}

/** Bounds speech to one outstanding say per agent and translates its answer. */
internal class PlayerSpeech(
    private val context: PlayerBodyContext,
    private val claims: SpeechClaims = SpeechClaims.NONE,
) {
    private val speaking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Validates on the calling (main) thread; the returned operation does the I/O. */
    fun prepare(
        runtime: PlayerRuntime,
        text: String,
        requestId: String?,
        send: (BodyRequest.Say) -> BodyResponse,
    ): () -> ActionExecution {
        val agentId = runtime.identity.agentId.value
        val refusal =
            when {
                !SayText.admits(
                    text,
                ) -> IntentOutcome.Rejected("the line is not sendable chat", EventTag.SAY_TEXT_REFUSED)
                !speaking.add(
                    agentId,
                ) -> IntentOutcome.Rejected("this agent is already speaking", EventTag.SAY_OUTSTANDING)
                else -> null
            }
        if (refusal != null) return { ActionExecution(refusal) }
        val request =
            BodyRequest.Say(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = context.infrastructure.requestIds.next("say"),
                agentId = agentId,
                text = text,
            )
        return {
            try {
                val report =
                    send(request) as? BodyResponse.SayReport
                        ?: throw BodyControlFailure.ProtocolViolation()
                outcomeOf(report, request, requestId)
            } finally {
                speaking.remove(agentId)
            }
        }
    }

    /** Whether [agentId] holds an outstanding say. */
    fun isSpeaking(agentId: String): Boolean = agentId in speaking

    private fun outcomeOf(
        report: BodyResponse.SayReport,
        request: BodyRequest.Say,
        requestId: String?,
    ): ActionExecution {
        if (report.requestId != request.requestId || report.agentId != request.agentId) {
            throw BodyControlFailure.ProtocolViolation()
        }
        return when (report.outcome) {
            SayOutcome.SENT -> {
                claims.claimed(SpeechClaim(request.agentId, requestId, request.requestId, request.text, report.outcome))
                ActionExecution(IntentOutcome.Accepted("said"))
            }
            SayOutcome.RATE_LIMITED ->
                ActionExecution(
                    IntentOutcome.Rejected("the body's speech window was full", EventTag.SAY_RATE_LIMITED),
                )
        }
    }
}
