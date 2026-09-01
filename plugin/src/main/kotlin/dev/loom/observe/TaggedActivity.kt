/*
 * What an event means when its name is not enough to say.
 *
 * `WatchState.activityAfter` decides by *event*, and for most events that is the whole answer. Four
 * of them carry more than one fact under one name, and what tells those facts apart is the record's
 * countable `outcome` tag - never its prose, which this package may not look at. Those four answers
 * live here rather than beside the event switch, because they are asking a different question: not
 * "which event was this" but "which of the several things this event can mean was it".
 *
 * The distinction is worth a file because it is where the display gets things wrong. Every one of
 * these was once read as a single fact, and each time the effect was the same shape: an agent shown
 * in the opposite state from the one the world was in, with no later record to correct it. A refused
 * spawn read as bodiless labelled a live body `no body`; a refusal after a death read the same way
 * understated a permadeath as a temporary condition. So each function below states which tag means
 * what, and why the two readings are not interchangeable.
 *
 * [unansweredActivity] is here for the same reason with a different discriminator: what tells its
 * two facts apart is the *cycle* rather than a tag, since a cognition failure names the request it
 * was about and a superseded one is not news. `DecisionStamp.kt` owns that question.
 */
package dev.loom.observe

import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag

/**
 * What a refused spawn leaves the agent in.
 *
 * One event name, three different facts, and only the countable outcome tells them apart.
 *
 * A spawn refused because the agent *already* has a body says the body is there, so an operator who
 * typed the command twice must not be told that the live body in front of them is gone. A spawn
 * refused because the agent has *died* is the terminal state itself: Loom is permadeath, so the
 * refusal is the last word about that agent rather than a request that failed. Read as merely
 * bodiless it understated a permadeath as a temporary condition, and an in-world run left a dead
 * agent reading `no body` in operator yellow indefinitely - a watch attached after the death never
 * sees the `died` record, so the refusal is the only record that will ever say so. Every remaining
 * refusal is a request Loom could not meet, to an agent that has no body and can still be given one.
 */
internal fun refusedActivity(
    state: WatchState,
    outcome: String?,
): AgentActivity =
    when (outcome) {
        EventTag.ALREADY_EMBODIED -> state.activity
        EventTag.HAS_DIED -> AgentActivity.DEAD
        else -> AgentActivity.BODILESS
    }

/**
 * What a refused login leaves the agent in.
 *
 * The same shape of question [refusedActivity] answers, and for the same reason: one event name
 * carrying facts that are opposite from an operator's point of view, told apart only by the
 * countable tag. A login refused because the agent has *died* is the terminal state, and it is
 * the one record a watch attached after the fact will ever get - a dead player body's process
 * keeps trying to reconnect, so the refusals are all there is to read.
 *
 * Every other refusal is a fact about a *connection attempt* and not about the agent, so what the
 * agent was doing stands. A player body has no per-tick record to correct a wrong reading with, and
 * the refusals reach the agent whose identity was named rather than whichever process connected:
 * a second body process for a live agent, or something that took its username, is refused while
 * that agent is standing in the world. Read as bodilessness it labelled a connected agent `no
 * body`, and froze its reserve trend, for the rest of the watch.
 */
internal fun refusedLoginActivity(
    state: WatchState,
    outcome: String?,
): AgentActivity = if (outcome == EventTag.LOGIN_DEAD_IDENTITY) AgentActivity.DEAD else state.activity

/**
 * What a cognition failure leaves the agent in.
 *
 * A mind that did not answer *this* cycle is the one thing an operator watching a silent mind
 * is waiting to be told. A failure naming a cycle the line has stopped asking about is not:
 * the agent is doing whatever the answered cycle had it doing, and `mind did not answer`
 * about a cycle that was answered and applied is a line no record ever stated.
 */
internal fun unansweredActivity(
    state: WatchState,
    record: BodyEventRecord,
): AgentActivity = if (answersTheCycle(state, record)) AgentActivity.UNANSWERED else state.activity

/**
 * What a conversion leaves the agent in.
 *
 * One event name, two opposite facts. Loom refuses a conversion, so the ordinary case is
 * a body that never changed and an agent still doing whatever it was. A conversion that
 * got past the refusal took the body with it and nothing re-embodies the agent, so the
 * display has to stop claiming it is standing anywhere.
 */
internal fun convertedActivity(
    state: WatchState,
    outcome: String?,
): AgentActivity = if (outcome == EventTag.BODY_TAKEN) AgentActivity.BODILESS else state.activity
