/*
 * What a live body's flight from a threat does on one server tick.
 *
 * Beside `BodyActuation.kt` rather than inside it, and driven from the same tick, because
 * the two answer different questions about the same body: actuation *observes* where the
 * engine left it, and this asks whether the flight it is in the middle of needs another hop.
 *
 * Every decision about that is `ActiveRetreats` in the engine-free domain - the cadence, the
 * live route, the ground, when a flight is over - and everything here is the three engine
 * facts it needs and the two engine calls a hop is made of. That split is what lets a
 * durative retreat be unit-tested without a server.
 *
 * Nothing here reaches cognition, and it could not: a continuation costs a path search out
 * of the same per-tick bucket a walk pays into and never a model call, which is what makes
 * running away something an agent keeps doing between goal reviews rather than something it
 * has to decide again twenty times a second. The boundary test forbids this package from
 * depending on `dev.loom.mind` at all, so the tick path cannot reach a mind by accident.
 */
package dev.loom.body.nms

import dev.loom.body.ActiveRetreats
import dev.loom.body.AgentId
import dev.loom.body.NavigationPolicy
import dev.loom.body.RetreatStep
import dev.loom.body.describe
import dev.loom.body.tagOf
import java.util.UUID

/**
 * Advances the flights in progress, one bounded hop at a time.
 *
 * A class rather than a function because it holds the three collaborators every hop needs -
 * the state, the routing policy whose per-tick budget it pays into, and the log - and the
 * tick supplies only what changes. The port constructs one and calls it for each live body.
 *
 * @param journal where each transition is recorded, once.
 * @param retreats the flights in progress; the only thing that decides what a tick may ask.
 * @param movement the same routing policy a walk the mind named goes through.
 */
internal class RetreatDrive(
    private val journal: BodyEventJournal,
    private val retreats: ActiveRetreats,
    private val movement: NavigationPolicy,
) {
    /**
     * Advances [agentId]'s flight, if it has one, by at most one hop on [tick].
     *
     * The three facts read from the engine are the ones `ActiveRetreats.next` cannot know:
     * where the threat stands now, whether the body is still walking a route, and whether it
     * is on the ground. Only the first costs a world lookup, and only for an agent that is
     * actually fleeing - an agent with no retreat leaves on the first line.
     *
     * Journalled once per transition, never per tick. A continuation states the route it
     * earned, an ending states which of the four it was, and a held flight waiting for the
     * ground states nothing at all: the attempt that put it into that state already said so,
     * and a record per cadence would be the same sentence for as long as something kept
     * knocking the body about.
     */
    fun advance(
        agentId: AgentId,
        body: LoomBodyEntity,
        tick: Long,
    ) {
        val threat = retreats.threatOf(agentId) ?: return
        val step =
            retreats.next(
                agentId,
                tick,
                body.standingOf(threat),
                routeRunning = !body.navigator.isDone,
                grounded = body.onGround(),
            )
        when (step) {
            RetreatStep.Hold -> Unit
            is RetreatStep.Ended -> journal.recordRetreat(agentId, describe(step.why), tagOf(step.why))
            is RetreatStep.Route -> continued(agentId, body, step.threat, tick)
        }
    }

    /** Asks the ground for another hop away from [threat], and records what it came to. */
    private fun continued(
        agentId: AgentId,
        body: LoomBodyEntity,
        threat: UUID,
        tick: Long,
    ) {
        val attempt = body.retreatAgainFrom(threat, movement, tick)
        retreats.attempted(agentId, attempt, tick)
        journal.recordRetreat(agentId, describe(attempt, continued = true), tagOf(attempt, continued = true))
    }
}
