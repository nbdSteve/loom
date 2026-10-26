/*
 * The one bounded pickup attempt a model-selected dig makes for its goal's own dropped item.
 *
 * r24: all three of epsilon's logs were taken by idle peers standing at the trunk before its
 * follow-up review could collect them. A dig that left its goal item lying therefore makes at
 * most one explicit collect of exactly that drop, approaching it, on a fresh sense answer, and
 * then returns control to the mind: there is no loop, no other item, and no second attempt.
 * Vanilla's own competition stands, so a peer's pickup first is the body's `itemGone`, a race.
 *
 * The attempt waits for the drop to come to rest, by the server's own position for it, before it
 * senses: a dug block's drop is thrown, and a sense taken while it flew sent the body to where it
 * had been, to time out where it was no longer (path rehearsal 20: eta's pickups were accepted in
 * one run and timed out in two, the same drops collected without trouble once at rest).
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.tagOf
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.DropFate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** How long the one pickup may take, approach included: an interaction's own deadline. */
private const val PICKUP_DEADLINE_MS = 20_000

/** The most server ticks a pickup waits for its drop to come to rest before it senses anyway. */
internal const val PICKUP_SETTLE_MAX_TICKS = 40L

/** One pickup waiting for its drop to come to rest. */
private class PendingPickup(
    val agentId: AgentId,
    val requestId: String,
    val item: String,
    val since: Long,
    val answer: CompletableFuture<String?>,
) {
    var lastPosition: List<Double>? = null
}

internal class OwnDropPickup(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
    private val sessions: PlayerSenseSessions,
    private val perception: PlayerPerception,
) {
    private val pending = mutableListOf<PendingPickup>()

    /**
     * Tries once to pick up the [item] dig [requestId] left lying, once the drop is at rest; answers
     * the terminal tag recorded, or `null` when no pickup was tried because the drop is gone.
     */
    fun attempt(
        agentId: AgentId,
        requestId: String,
        item: String,
    ): CompletionStage<String?> {
        val runtime = registry.ready(agentId)
        runtime?.let { ownDropOf(it, requestId, item) } ?: return CompletableFuture.completedFuture(null)
        val answer = CompletableFuture<String?>()
        pending += PendingPickup(agentId, requestId, item, context.authority.currentTick(), answer)
        return answer
    }

    /**
     * Starts each waiting pickup whose drop the server shows at rest - the same position on two
     * ticks running - or that has waited [PICKUP_SETTLE_MAX_TICKS]; one whose drop is gone is
     * answered `null`. Main thread only, once per server tick.
     */
    fun tick(now: Long) {
        pending.removeAll { waiting ->
            val runtime = registry.ready(waiting.agentId)
            val drop = runtime?.let { ownDropOf(it, waiting.requestId, waiting.item) }
            val position = drop?.position
            val ready = position != null && position == waiting.lastPosition
            waiting.lastPosition = position
            when {
                runtime == null || drop == null -> waiting.answer.complete(null)
                ready || now - waiting.since >= PICKUP_SETTLE_MAX_TICKS -> start(runtime, waiting)
                else -> return@removeAll false
            }
            true
        }
    }

    private fun start(
        runtime: PlayerRuntime,
        waiting: PendingPickup,
    ) {
        val agentId = waiting.agentId
        val requestId = waiting.requestId
        val item = waiting.item
        val answer = waiting.answer
        val drop = ownDropOf(runtime, requestId, item) ?: return run { answer.complete(null) }
        perception.senseForPickup(agentId).whenComplete { sense, failure ->
            context.authority.main.execute {
                val index = sense?.droppedItemSubjects?.entries?.firstOrNull { it.value == drop.entity }?.key
                val unreachable =
                    sense != null && index != null && drop.entity != null &&
                        context.routeRefusals.blocks(
                            drop.entity,
                            sense.raw.report.position,
                            sense.raw.report.items[index].position,
                        )
                when {
                    failure != null || sense == null || index == null -> {
                        sense?.let { sessions.settle(agentId, it.raw.report.requestId, it) }
                        record(
                            agentId,
                            requestId,
                            EventTag.OWN_DROP_NOT_SENSED,
                            "its own drop of $item was not in sight",
                        )
                        answer.complete(EventTag.OWN_DROP_NOT_SENSED)
                    }
                    // Never forced into the stall or the missing route it already met from here.
                    unreachable -> {
                        sessions.settle(agentId, sense.raw.report.requestId, sense)
                        record(
                            agentId,
                            requestId,
                            EventTag.OWN_DROP_UNREACHABLE,
                            "its own drop of $item was not tried: the body already found no way to it from here",
                        )
                        answer.complete(EventTag.OWN_DROP_UNREACHABLE)
                    }
                    else -> collect(runtime, sense, index, requestId, answer)
                }
            }
        }
    }

    private fun ownDropOf(
        runtime: PlayerRuntime,
        requestId: String,
        item: String,
    ) = context.authority.ownDrops
        .of(runtime.identity)
        .firstOrNull { it.requestId == requestId && it.what == item && it.fate == DropFate.LYING && it.entity != null }

    private fun collect(
        runtime: PlayerRuntime,
        sense: HeldSense,
        index: Int,
        digRequestId: String,
        answer: CompletableFuture<String?>,
    ) {
        val agentId = runtime.identity.agentId
        val request =
            BodyRequest.Collect(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = context.infrastructure.requestIds.next("pickup"),
                agentId = agentId.value,
                senseId = sense.raw.report.requestId,
                item = index,
                approach = true,
                timeoutMs = PICKUP_DEADLINE_MS,
                sprint = false,
            )
        val startedTick = context.authority.currentTick()
        CompletableFuture
            .supplyAsync(
                { sense.raw.connection.request(request, PICKUP_REPLY_TIMEOUT) },
                context.infrastructure.io,
            ).whenComplete { response, failure ->
                context.authority.main.execute {
                    sessions.settle(agentId, sense.raw.report.requestId, sense)
                    val report = response as? BodyResponse.CollectReport
                    val tag =
                        if (failure != null || report == null) EventTag.CONTROL_UNAVAILABLE else collectTagOf(report)
                    val claim =
                        report?.let {
                            ServerInteractionClaim.Collect(
                                request,
                                it,
                                requireNotNull(sense.raw.report.items.getOrNull(index)),
                                sense.droppedItemSubjects[index],
                                startedTick,
                            )
                        }
                    // The server's own account of the pickup: what entered the inventory, or who took it.
                    val audit = claim?.let { context.authority.serverState.auditInteraction(runtime.identity, it) }
                    claim?.let(context.routeRefusals::remember)
                    val travelled = report?.travelled?.let { "; approached $it blocks" }.orEmpty()
                    record(
                        agentId,
                        digRequestId,
                        tag,
                        "one pickup of its own drop: $tag$travelled; server ${audit?.detail ?: "unread"}",
                    )
                    answer.complete(tag)
                }
            }
    }

    private fun record(
        agentId: AgentId,
        requestId: String,
        outcome: String,
        detail: String,
    ) = context.authority.eventLog.append(
        dev.loom.body.BodyEventRecord(
            tick = context.authority.currentTick(),
            agentId = agentId.value,
            event = dev.loom.body.BodyEvent.OWN_DROP_PICKUP,
            detail = detail,
            requestId = requestId,
            outcome = outcome,
        ),
    )
}

/** Room for the body's own answer after the pickup's deadline. */
private const val REPLY_MARGIN_MS = 3_000L

/** The pickup's deadline with room for the body's own answer after it. */
private val PICKUP_REPLY_TIMEOUT: java.time.Duration = java.time.Duration.ofMillis(PICKUP_DEADLINE_MS + REPLY_MARGIN_MS)

/** The collect terminal's countable tag. */
private fun collectTagOf(report: BodyResponse.CollectReport): String = tagOf(collectOutcome(report))
