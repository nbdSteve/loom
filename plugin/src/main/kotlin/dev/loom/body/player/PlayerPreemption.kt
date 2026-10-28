/*
 * One request against a body its agent's own earlier work may still hold.
 */
package dev.loom.body.player

import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.ErrorCode

/** Attempts against a body its agent's own earlier work holds: the first, one after a halt, then settling retries. */
internal const val PREEMPT_ATTEMPTS = 6

/** How long halted or settling work is given between attempts. */
internal const val PREEMPT_SETTLE_MS = 100L

/**
 * The newest decision applied governs its agent's body, for every action as for a flight (r13 eta):
 * a request the body refuses as not ready halts the agent's own earlier work once, and is asked for
 * again under a fresh id while that work settles, a bounded number of times. Work a halt cannot stop
 * - a placement packet already sent - settles at the server's answer within a tick or two. A request
 * whose decision a newer one of the same agent's has since replaced is never retried: the newer one
 * holds the body, and the refusal stands (r39: iota's three survival placements were refused behind
 * its own unsettled placement).
 */
internal class PlayerPreemption(
    private val newest: NewestDecisions,
    private val nextRequestId: (String) -> String,
    private val halt: (PlayerRuntime) -> Unit,
) {
    /** Sends [request] for [runtime]'s decision [decidedBy] with [send]: the request finally sent, and its answer. */
    fun <R> send(
        runtime: PlayerRuntime,
        decidedBy: String?,
        request: BodyRequest,
        send: (BodyRequest) -> R,
    ): Pair<BodyRequest, R> {
        var attempt = request
        var halted = false
        repeat(PREEMPT_ATTEMPTS) { tries ->
            try {
                return attempt to send(attempt)
            } catch (busy: BodyControlFailure.Refused) {
                val replaced = decidedBy != null && newest.outdated(runtime.identity.agentId, decidedBy)
                if (busy.code != ErrorCode.NOT_READY || replaced || tries == PREEMPT_ATTEMPTS - 1) throw busy
                if (!halted) {
                    halt(runtime)
                    halted = true
                } else {
                    Thread.sleep(PREEMPT_SETTLE_MS)
                }
                attempt = attempt.withRequestId(nextRequestId(purposeOf(attempt)))
            }
        }
        error("unreachable: the last attempt returns or throws")
    }
}

/**
 * One decision being applied to a body: the runtime it acts through, the sense that offered its
 * refs, and the decision's own request id.
 */
internal data class AppliedDecision(
    val runtime: PlayerRuntime,
    val sense: HeldSense?,
    val requestId: String?,
)

/** The id purpose a request of this kind is minted under. */
private fun purposeOf(request: BodyRequest): String =
    when (request) {
        is BodyRequest.MoveTo -> "move"
        is BodyRequest.Dig -> "dig"
        is BodyRequest.Collect -> "collect"
        is BodyRequest.Place -> "place"
        is BodyRequest.Equip -> "equip"
        is BodyRequest.Craft -> "craft"
        is BodyRequest.Attack -> "attack"
        is BodyRequest.Sleep -> "sleep"
        else -> throw BodyControlFailure.ProtocolViolation()
    }

/** [this] request under [id], everything else unchanged. */
internal fun BodyRequest.withRequestId(id: String): BodyRequest =
    when (this) {
        is BodyRequest.MoveTo -> copy(requestId = id)
        is BodyRequest.Dig -> copy(requestId = id)
        is BodyRequest.Collect -> copy(requestId = id)
        is BodyRequest.Place -> copy(requestId = id)
        is BodyRequest.Equip -> copy(requestId = id)
        is BodyRequest.Craft -> copy(requestId = id)
        is BodyRequest.Attack -> copy(requestId = id)
        is BodyRequest.Sleep -> copy(requestId = id)
        else -> throw BodyControlFailure.ProtocolViolation()
    }
