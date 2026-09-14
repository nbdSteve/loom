/*
 * What makes an agent autonomous: nobody has to ask it to think.
 *
 * Cognition is event-driven by construction, and a scheduled goal review is one of
 * those events - the routine one, the agent noticing time has passed and reviewing
 * where it is. This class is the only thing that raises it. It is not hung off the
 * body's per-tick timer: `BodyPort.tick` drives actuation and must never invoke
 * cognition, and the contract has no per-tick `Trigger` so that the distinction
 * cannot quietly erode. A sweep here runs once per schedule slice - orders of
 * magnitude rarer than a tick - and hands out only the agents the schedule says are
 * due.
 *
 * Nothing here waits for a mind. `EmbodiedCognition.consider` captures perception
 * on the main thread and leaves cognition to the bridge, so a sweep costs a look at
 * who is alive plus an enqueue per due agent, whatever the mind is doing.
 *
 * Engine-free, like the rest of `dev.loom.mind`: the driver knows a body port, a
 * schedule, and a clock, so the loop is testable with no server at all.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyPort
import dev.loom.contract.Trigger

/**
 * Reviews the situation of every living agent, on its own, on a cadence.
 *
 * @param bodies the body port, asked which agents are alive. Only ever read from,
 *   and only on the main thread.
 * @param mind the round trip a due agent is handed to.
 * @param schedule which of the living agents are due right now. Its
 *   [GoalReviewSchedule.sliceTicks] is how often [sweep] must be called.
 * @param currentTick the authoritative server tick. A paused server advances no
 *   reviews, and a server sprinting ticks advances exactly the same review sequence.
 */
class AutonomousDriver(
    private val bodies: BodyPort,
    private val mind: EmbodiedCognition,
    private val schedule: GoalReviewSchedule = GoalReviewSchedule(),
    private val currentTick: () -> Long,
) {
    /**
     * Sets one slice's worth of agents thinking, and returns.
     *
     * Must be called on the main thread, every [GoalReviewSchedule.sliceTicks]. Agents
     * with no body are never reached: only the living are asked, and one whose body
     * went away between being listed and being perceived is skipped by
     * [EmbodiedCognition.consider] itself.
     */
    fun sweep() {
        for (agent in due()) {
            mind.consider(agent, Trigger.GoalReview)
        }
    }

    /**
     * The agents to review this sweep.
     *
     * Living agents only, because a body is what a perception is taken with and
     * what a decision is applied to. A dead agent never appears: Loom is
     * permadeath, so it has no body and will never have another one.
     */
    private fun due(): List<AgentId> = schedule.claim(bodies.liveBodies(), currentTick())
}
