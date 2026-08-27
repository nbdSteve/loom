/*
 * What makes an agent react to something hunting it before its next goal review.
 *
 * The sibling of `AutonomousDriver`, and for the same reason: cognition is event-driven,
 * and a threat coming into view is one of those events - the urgent one. It is not hung
 * off the body's per-tick timer, because `BodyPort.tick` drives actuation and must never
 * invoke cognition; and it cannot be reached from inside the body at all, since the body
 * domain is forbidden to depend on the mind. So the one thing that can put the two
 * together is a coordinator above both ports, which is this.
 *
 * The division of labour is exact. The body port looks, applies the engine-free threat
 * rule, records what the rule refused, and hands back the *one* perception a sighting was
 * earned in. This class does not decide whether a threat matters, does not read the world,
 * and does not journal: all it does is submit what it is given, and only when it is given
 * something.
 *
 * Engine-free, like the rest of `dev.loom.mind`: a body port and a cognition seam, so the
 * whole loop is testable with no server at all.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyPort
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often every living agent looks around for something hunting it.
 *
 * This is the *only* bound on how often a scan can happen, so it is a named constant with
 * the arithmetic stated. A scan is cheap when nothing is in view - one bounded entity
 * query - and the expensive parts are already bounded elsewhere: `ThreatWatch`'s debounce
 * means a threat standing in view earns at most one model call per five seconds however
 * often it is looked at, and the reachability search comes out of the per-tick path budget
 * a walk pays into.
 *
 * One second is a compromise between the two things that get worse in opposite directions.
 * Scanning rarely makes a sighting stale - a zombie crosses the pivotal distance in a
 * couple of seconds - while scanning every tick would write a suppression record twenty
 * times a second for as long as anything hostile stood in view, which is the record spam
 * this cadence exists to bound rather than the model spend, which the debounce already
 * bounds.
 */
val THREAT_SCAN_CADENCE: Duration = 1.seconds

/**
 * Has every living agent look around for a threat, and wakes the minds of those that
 * found one.
 *
 * @param bodies the body port, asked which agents are alive and what each one can see.
 *   Only ever read from, and only on the main thread.
 * @param mind the round trip an earned sighting is handed to.
 */
class ThreatDriver(
    private val bodies: BodyPort,
    private val mind: EmbodiedCognition,
) {
    /**
     * Scans every living agent once and submits whatever was earned, then returns.
     *
     * Must be called on the main thread, every [THREAT_SCAN_CADENCE]. Nothing here waits
     * for a mind: [EmbodiedCognition.submit] enqueues the round trip and returns, so a
     * scan costs a look per living agent whatever the mind is doing.
     *
     * Only the living are asked, which is what keeps this off a dead or put-away agent:
     * [BodyPort.liveBodies] is the same list `AutonomousDriver` reviews, and Loom is
     * permadeath, so an agent that has left it will never appear in it again.
     */
    fun sweep() {
        for (agent in bodies.liveBodies()) {
            watch(agent)
        }
    }

    /**
     * Looks around [agent], and submits only if the look earned it.
     *
     * The perception is submitted exactly as the port captured it, with the trigger the
     * port put on it, and is classified on the way past - importance is this side's call,
     * never the body's, and a sighting is not a goal review. Submitting it rather than
     * re-perceiving is not a shortcut but the invariant: the trigger names a creature by a
     * handle only its own request holds, so a second capture would send the mind a name it
     * cannot resolve and every flight it prompted would be refused.
     *
     * A suppressed scan is silent here on purpose: the port has already recorded what its
     * rule refused, and a mind that was not woken has not decided anything.
     */
    private fun watch(agent: AgentId) {
        val sighting = bodies.senseThreat(agent) ?: return
        mind.submit(agent, sighting.classified())
    }
}
