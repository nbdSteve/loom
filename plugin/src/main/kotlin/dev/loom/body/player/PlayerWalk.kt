/*
 * A long walk: one decision's bearing walked in consecutive bounded hops through the single-move path.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.describe
import dev.loom.contract.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.hypot

/** The farthest one hop goes; the body's own walk reach is wider. */
private const val WALK_HOP_BLOCKS = 12

/** The shortest hop tried when the cell a full hop ahead has nowhere to stand. */
private const val WALK_SHORTEST_HOP_BLOCKS = 2

/** Less headway than this in one hop is a stall. */
private const val WALK_STALL_BLOCKS = 1.0

private const val HOP_PLAN_TIMEOUT_S = 5L

private const val ARRIVED = "arrived"

private const val CELL_CENTRE = 0.5

/** Moves the body to one destination for [decidedBy], as a single moveTo does. */
internal typealias Hop = (
    runtime: PlayerRuntime,
    destination: List<Double>,
    sense: HeldSense?,
    decidedBy: String?,
) -> ActionExecution

/** How far a walk has come: where the body stands, the headway made, and each hop. */
private class Walked(
    var position: List<Double>,
) {
    var travelled = 0.0
    val steps = mutableListOf<ActionExecution>()
}

/** A dig sequence or a long walk, each run as consecutive single steps. */
internal class PlayerSequences(
    private val context: PlayerBodyContext,
    private val preemption: PlayerPreemption,
    private val interactions: PlayerInteractionPreparer,
    private val hop: Hop,
) {
    fun prepare(
        intent: Intent,
        applied: AppliedDecision,
    ): () -> ActionExecution =
        when (intent) {
            is Intent.DigSequence -> prepareDigSequence(interactions, preemption, applied, intent)
            is Intent.Walk -> prepareWalk(intent, applied)
            else -> error("not a sequence: $intent")
        }

    /** Resolves the bearing on the main thread; each later hop's cell is read there just before it is walked. */
    private fun prepareWalk(
        intent: Intent.Walk,
        applied: AppliedDecision,
    ): () -> ActionExecution {
        val start = applied.sense?.raw?.report?.position
        val cell = applied.sense?.targets?.exploreCells?.get(intent.toward)
        if (start == null || cell == null) {
            return {
                ActionExecution(
                    IntentOutcome.Rejected("that request never named such a cell", EventTag.MOVE_BLOCK_UNNAMED),
                )
            }
        }
        val dx = cell[0] + CELL_CENTRE - start[0]
        val dz = cell[2] + CELL_CENTRE - start[2]
        val length = hypot(dx, dz).coerceAtLeast(WALK_STALL_BLOCKS)
        return { walk(applied, start, dx / length to dz / length, intent.blocks.toDouble()) }
    }

    private fun walk(
        applied: AppliedDecision,
        start: List<Double>,
        heading: Pair<Double, Double>,
        total: Double,
    ): ActionExecution {
        val walked = Walked(start)
        var stop: String? = null
        while (stop == null) {
            stop =
                when {
                    walked.travelled >= total - WALK_STALL_BLOCKS -> ARRIVED
                    preemption.outdated(applied.runtime, applied.requestId) -> "a newer decision took the body"
                    else -> hopOnce(applied, walked, heading, total)
                }
        }
        val summary = "walked ${"%.1f".format(
            walked.travelled,
        )} of ${total.toLong()} blocks in ${walked.steps.size} hops; $stop"
        val outcome =
            if (walked.steps.isEmpty()) {
                IntentOutcome.Rejected(summary, EventTag.MOVE_NOT_STANDABLE)
            } else {
                IntentOutcome.Accepted(summary, reached = stop == ARRIVED)
            }
        return ActionExecution(outcome = outcome, steps = walked.steps)
    }

    /** Walks one hop, or answers why the walk ends here. */
    private fun hopOnce(
        applied: AppliedDecision,
        walked: Walked,
        heading: Pair<Double, Double>,
        total: Double,
    ): String? {
        val cell = planHop(walked.position, heading, total - walked.travelled) ?: return "no ground to stand on ahead"
        val step = hop(applied.runtime, cell.map(Int::toDouble), applied.sense, applied.requestId)
        walked.steps += step
        val report = step.moveReport
        val gained = report?.let { hypot(it.position[0] - it.from[0], it.position[2] - it.from[2]) } ?: 0.0
        walked.travelled += gained
        report?.let { walked.position = it.position }
        return stalled(step, gained)
    }

    private fun stalled(
        step: ActionExecution,
        gained: Double,
    ): String? =
        when {
            step.outcome !is IntentOutcome.Accepted -> "stalled: ${describe(step.outcome)}"
            gained < WALK_STALL_BLOCKS -> "stalled: no headway"
            else -> null
        }

    /** The standable cell up to one hop ahead along [heading], read from the server's ground on the main thread. */
    private fun planHop(
        position: List<Double>,
        heading: Pair<Double, Double>,
        remaining: Double,
    ): List<Int>? =
        CompletableFuture.supplyAsync({
            val feet = floor(position[1]).toInt()
            (minOf(WALK_HOP_BLOCKS, remaining.toInt().coerceAtLeast(1)) downTo WALK_SHORTEST_HOP_BLOCKS)
                .firstNotNullOfOrNull { along ->
                    val x = floor(position[0] + heading.first * along).toInt()
                    val z = floor(position[2] + heading.second * along).toInt()
                    standableNear(context.authority.ground, x, feet, z)
                }
        }, context.authority.main).get(HOP_PLAN_TIMEOUT_S, TimeUnit.SECONDS)
}
