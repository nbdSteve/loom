/*
 * What an agent is visibly doing, as one word an operator can read at a glance.
 *
 * The acceptance the demo has to meet is that resting, moving, fleeing, and dying are
 * *distinguishable without reading raw JSON*. That is what this enum is: the finite set
 * of states a watcher can be in, each with a fixed label and a fixed level, derived from
 * the record stream rather than from the world.
 *
 * Derived from the countable tags (`EventTag`) rather than from rendered detail text, so
 * a reworded phrase in the log cannot silently change what a viewer is shown.
 */
package dev.loom.observe

import dev.loom.body.EventTag

/**
 * One legible state of an agent, from a watcher's point of view.
 *
 * @property label what the operator reads.
 * @property level how much attention the state deserves.
 */
enum class AgentActivity(
    val label: String,
    val level: ObserverLevel,
) {
    /** Nothing about this agent has been recorded since the watch was attached. */
    UNSEEN("nothing seen yet", ObserverLevel.CALM),

    /**
     * It has a body and is standing by: nothing decided yet, or the last thing finished.
     *
     * Distinct from [RESTING], which is a *choice* the mind made. An agent that has
     * arrived where it was going and one that decided to do nothing look the same in
     * world, and an operator watching a survival run needs to know which they are seeing.
     */
    EMBODIED("standing by", ObserverLevel.CALM),

    /** Thinking: a perception has gone to the mind and no decision has come back yet. */
    THINKING("thinking", ObserverLevel.CALM),

    /** Resting: the mind chose to stop and hold position on purpose. It restores nothing. */
    RESTING("resting", ObserverLevel.CALM),

    /**
     * Idle: the mind chose to do nothing this cycle.
     *
     * Distinct from [RESTING], which is a *deliberate* wait somewhere - sealed in for
     * the night, say. Neither restores anything, so what separates them is intent, and
     * telling a considered wait from an unconsidered one is most of what an operator
     * watching a survival run is reading for.
     */
    IDLE("idle", ObserverLevel.CALM),

    /** Walking toward somewhere it chose. */
    MOVING("moving", ObserverLevel.CALM),

    /** Running from something. The most interesting thing a survival agent can do. */
    FLEEING("fleeing", ObserverLevel.ALARM),

    /**
     * Eating raises the player's vanilla food level.
     */
    EATING("eating", ObserverLevel.CALM),

    /** Speaking. */
    SPEAKING("speaking", ObserverLevel.CALM),

    /**
     * The body would not do what the mind decided.
     *
     * Its own state rather than a shade of resting: an agent standing still because it
     * chose to and one standing still because its body refused it look identical in
     * world, and telling them apart is most of what an operator is watching for.
     */
    BALKED("body refused it", ObserverLevel.CONCERN),

    /** The mind never answered. Not the agent's doing, and not a decision. */
    UNANSWERED("mind did not answer", ObserverLevel.CONCERN),

    /** Gone for good. Loom is permadeath, so this state is never left. */
    DEAD("DEAD - will not return", ObserverLevel.ALARM),

    /** Alive as an agent, with no body in the world. */
    BODILESS("no body", ObserverLevel.CONCERN),
}

/**
 * The activity a decision record's tags describe.
 *
 * Reads the outcome first and the intent second, deliberately: what the body *did* is
 * what a watcher sees, and an accepted `flee` and a refused one look nothing alike in
 * world however identical the decision was. An unrecognised tag falls back to
 * [AgentActivity.THINKING] rather than to a guess, since the one thing certainly true
 * of a decision this side cannot read is that the agent has been thinking.
 */
fun activityOf(
    intent: String?,
    outcome: String?,
): AgentActivity =
    when {
        outcome == null -> AgentActivity.THINKING
        outcome != EventTag.ACCEPTED -> AgentActivity.BALKED
        intent == EventTag.MOVE_TO -> AgentActivity.MOVING
        intent == EventTag.FLEE -> AgentActivity.FLEEING
        intent == EventTag.SAY -> AgentActivity.SPEAKING
        intent == EventTag.EAT -> AgentActivity.EATING
        intent == EventTag.REST -> AgentActivity.RESTING
        intent == EventTag.IDLE -> AgentActivity.IDLE
        else -> AgentActivity.THINKING
    }
