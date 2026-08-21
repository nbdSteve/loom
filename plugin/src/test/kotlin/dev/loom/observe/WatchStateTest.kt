/*
 * What a watcher knows, and that it only ever knows what a record told it.
 *
 * Every test here builds a state by handing records to `advanced`, because that is the
 * only way a state can be built at all. Two properties matter most: nothing is invented
 * (an unread energy stays unread rather than becoming a full bar), and death is never
 * left, since Loom is permadeath and a display that recovered from a tombstone would be
 * saying something false about the run.
 */
package dev.loom.observe

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EnergyTrend
import dev.loom.body.EventTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A tick comfortably past any motion decay, for the states where time is irrelevant. */
private const val LATER = 10_000L

class WatchStateTest {
    private val fresh = WatchState(agentId = WATCHED)

    /** The state after every one of [records], in order. */
    private fun after(vararg records: BodyEventRecord): WatchState =
        records.fold(fresh) { state, record -> advanced(state, record) }

    @Test
    @DisplayName("a fresh watch knows nothing, and says so rather than guessing")
    fun aFreshWatchKnowsNothing() {
        assertEquals(AgentActivity.UNSEEN, fresh.activity)
        assertNull(fresh.vitals, "an unread energy must stay unread: a guessed full bar hides a dying agent")
        assertEquals(EnergyTrend.STEADY, fresh.trend)
        assertNull(fresh.provenance)
        assertFalse(fresh.isFinal)
    }

    @Test
    @DisplayName("an energy reading and its trend come from the records, one after another")
    fun energyFollowsTheRecord() {
        val hurt =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)),
                record(BodyEvent.ENERGY_CHANGED).copy(vitals = energy(16.0)),
            )

        assertEquals(energy(16.0), hurt.vitals)
        assertEquals(EnergyTrend.DECLINING, hurt.trend)

        val healing = advanced(hurt, record(BodyEvent.ENERGY_CHANGED).copy(vitals = energy(17.0)))

        assertEquals(EnergyTrend.RECOVERING, healing.trend)
    }

    @Test
    @DisplayName("the two vitals are read separately, so a wound does not spend the reserve")
    fun theVitalsAreReadSeparately() {
        val hurt =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = energy(20.0), health = energy(20.0)),
                record(BodyEvent.HEALTH_CHANGED).copy(vitals = energy(20.0), health = energy(12.0)),
            )

        assertEquals(energy(12.0), hurt.health)
        assertEquals(energy(20.0), hurt.vitals, "being hurt does not spend the reserve")
        // The trend is the reserve's, not the body's: a wound that read as a decline would
        // put a survival curve's word on a number that had not moved.
        assertEquals(EnergyTrend.STEADY, hurt.trend)
    }

    @Test
    @DisplayName("a reserve restated on the same tick is not a second reading")
    fun aRestatedReserveKeepsTheTrendWord() {
        val declining =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(99.0), health = energy(20.0)),
                record(BodyEvent.ENERGY_CHANGED, tick = 10L).copy(vitals = reserve(98.0), health = energy(20.0)),
            )

        // Both vitals stepping on one tick: `energyChanged` and `healthChanged` each state
        // both readings, so the reserve arrives twice with the same number. Read as a second
        // reading it answered "steady" and dropped the word off an agent still draining.
        val wounded =
            advanced(
                declining,
                record(BodyEvent.HEALTH_CHANGED, tick = 10L).copy(vitals = reserve(98.0), health = energy(12.0)),
            )

        assertEquals(EnergyTrend.DECLINING, wounded.trend)
        assertEquals(
            "$WATCHED | energy 98/100 declining | health 12/20 | standing by",
            ObserverView().render(wounded).text,
        )
    }

    @Test
    @DisplayName("a perception restating the reserve on the same tick does not either")
    fun aPerceptionRestatingTheReserveKeepsTheTrendWord() {
        val declining =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(99.0)),
                record(BodyEvent.ENERGY_CHANGED, tick = 10L).copy(vitals = reserve(98.0)),
            )

        // A goal review on the same tick carries the reserve it handed the mind, which is
        // the number the change record has just stated.
        val thinking =
            advanced(
                declining,
                record(BodyEvent.PERCEIVED, tick = 10L).copy(trigger = EventTag.GOAL_REVIEW, vitals = reserve(98.0)),
            )

        assertEquals(
            "$WATCHED | energy 98/100 declining | thinking | goal review",
            ObserverView().render(thinking).text,
        )
    }

    @Test
    @DisplayName("a reserve that has genuinely not moved reads as steady rather than as a trend")
    fun anUnmovedReserveIsSteady() {
        val spawned = after(record(BodyEvent.SPAWNED).copy(vitals = reserve(100.0)))

        val restated = advanced(spawned, record(BodyEvent.PERCEIVED, tick = 10L).copy(vitals = reserve(100.0)))

        // The reading is not news and neither is a trend: an agent that has been looked at
        // twice and has not moved is not declining, and inventing the word would spend the
        // one signal whose absence means something.
        assertEquals(EnergyTrend.STEADY, restated.trend)
        assertEquals("$WATCHED | energy 100/100 | thinking", ObserverView().render(restated).text)
    }

    @Test
    @DisplayName("a body put away keeps its last reading and stops claiming to be spending it")
    fun aPutAwayBodyReportsNoTrend() {
        // The sequence in world: spawn, one goal review a few ticks later, then
        // `/loombody despawn ada`. The despawn's own reading is lower than the perception's,
        // because ticks were charged in between, so the trend is DECLINING at the moment the
        // agent stops having a body.
        val putAway =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(100.0)),
                record(BodyEvent.PERCEIVED, tick = 10L).copy(vitals = reserve(99.5)),
                record(BodyEvent.DESPAWNED, tick = 20L).copy(vitals = reserve(99.4)),
            )

        // Nothing spends a put-away reserve - `Metabolism.spendUpTo` is handed the live
        // bodies only - so no further reading can arrive and "declining" would have stood on
        // the line for the rest of the session about a number that had stopped moving.
        assertEquals(AgentActivity.BODILESS, putAway.activity)
        assertEquals(EnergyTrend.DECLINING, putAway.trend, "the trend the records state is unchanged")
        assertEquals(
            "$WATCHED | energy 99.4/100 | no body",
            ObserverView().render(putAway).text,
            "the last reading stays; only the word about what is happening to it goes",
        )
    }

    @Test
    @DisplayName("the other ways of losing a body freeze the trend word the same way")
    fun everyBodilessStateReportsNoTrend() {
        val declining =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(100.0)),
                record(BodyEvent.PERCEIVED, tick = 10L).copy(vitals = reserve(99.5)),
            )

        val refused =
            advanced(declining, record(BodyEvent.SPAWN_REJECTED, tick = 20L).copy(outcome = EventTag.UNKNOWN_WORLD))
        val taken =
            advanced(declining, record(BodyEvent.CONVERTED, tick = 20L).copy(outcome = EventTag.BODY_TAKEN))

        // A refused spawn and a conversion that took the body reach the same state a despawn
        // does, and a reserve nobody is spending is not declining in any of them.
        for (state in listOf(refused, taken)) {
            assertEquals(AgentActivity.BODILESS, state.activity)
            assertEquals("$WATCHED | energy 99.5/100 | no body", ObserverView().render(state).text)
        }
        // The same records before the body went keep the word, so none of this can be passed
        // by dropping the trend from every line.
        assertEquals("$WATCHED | energy 99.5/100 declining | thinking", ObserverView().render(declining).text)
    }

    @Test
    @DisplayName("a cycle that fails after the body went does not put the trend word back")
    fun aFailedCycleDoesNotUnfreezeAPutAwayReserve() {
        val putAway =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(100.0)),
                record(BodyEvent.PERCEIVED, tick = 10L)
                    .copy(requestId = "req-1", trigger = EventTag.GOAL_REVIEW, vitals = reserve(99.5)),
                record(BodyEvent.DESPAWNED, tick = 20L).copy(vitals = reserve(99.4)),
            )

        // The wait for a silent mind is about half a minute, so an operator who despawns
        // during it has the failure land afterwards. It moves the activity to UNANSWERED, and
        // a bodilessness read off the activity came back to life there - permanently, since
        // nothing about a bodiless agent is recorded again.
        val failed =
            advanced(
                putAway,
                record(BodyEvent.COGNITION_UNAVAILABLE, tick = 25L).copy(requestId = "req-1", outcome = "timedOut"),
            )

        assertEquals(
            "$WATCHED | energy 99.4/100 | mind did not answer | goal review",
            ObserverView().render(failed).text,
        )
    }

    @Test
    @DisplayName("the other ways of losing a body stay frozen when a later record moves the activity")
    fun everyBodilessOriginStaysFrozen() {
        val declining =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = reserve(100.0)),
                record(BodyEvent.PERCEIVED, tick = 10L).copy(trigger = EventTag.GOAL_REVIEW, vitals = reserve(99.5)),
            )
        val losses =
            listOf(
                record(BodyEvent.SPAWN_REJECTED, tick = 20L).copy(outcome = EventTag.UNKNOWN_WORLD),
                record(BodyEvent.CONVERTED, tick = 20L).copy(outcome = EventTag.BODY_TAKEN),
            )

        for (loss in losses) {
            val failed =
                advanced(
                    advanced(declining, loss),
                    record(BodyEvent.COGNITION_UNAVAILABLE, tick = 25L).copy(outcome = "timedOut"),
                )

            assertEquals(
                "$WATCHED | energy 99.5/100 | mind did not answer | goal review",
                ObserverView().render(failed).text,
                "${loss.event} ${loss.outcome}",
            )
        }
    }

    @Test
    @DisplayName("a record that restates nothing leaves what was already known alone")
    fun knowledgeIsCarriedForwardButNeverInvented() {
        val decided =
            after(
                record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)),
                record(BodyEvent.PERCEIVED).copy(trigger = EventTag.GOAL_REVIEW),
                record(BodyEvent.DECIDED).copy(provenance = STAMP),
            )

        // A step is not a reason to forget which model answered, or how much energy the
        // last reading found - the agent did not stop having either.
        val stepped = advanced(decided, record(BodyEvent.MOVED, tick = 5L))

        assertEquals(STAMP, stepped.provenance)
        assertEquals(energy(20.0), stepped.vitals)
        assertEquals(EventTag.GOAL_REVIEW, stepped.trigger)
    }

    @Test
    @DisplayName("what the body did decides the activity, not what the mind chose")
    fun theOutcomeDecidesTheActivity() {
        val walking =
            after(
                record(BodyEvent.INTENT_APPLIED)
                    .copy(intent = EventTag.MOVE_TO, outcome = EventTag.ACCEPTED),
            )
        val refused =
            after(
                record(BodyEvent.INTENT_APPLIED)
                    .copy(intent = EventTag.MOVE_TO, outcome = EventTag.REJECTED),
            )

        assertEquals(AgentActivity.MOVING, walking.activity)
        // The same decision, refused. An operator watching two motionless agents needs to
        // know which of them chose to stand still.
        assertEquals(AgentActivity.BALKED, refused.activity)
    }

    @Test
    @DisplayName("resting and idling are told apart, and fleeing is the loud one")
    fun everyAppliedIntentHasItsOwnActivity() {
        fun applied(intent: String) =
            after(record(BodyEvent.INTENT_APPLIED).copy(intent = intent, outcome = EventTag.ACCEPTED)).activity

        // Resting refills the reserve; idling only passes the cycle. An operator watching a
        // survival run needs to know which of the two a motionless agent is doing.
        assertEquals(AgentActivity.RESTING, applied(EventTag.REST))
        assertEquals(AgentActivity.IDLE, applied(EventTag.IDLE))
        assertEquals(AgentActivity.MOVING, applied(EventTag.MOVE_TO))
        assertEquals(AgentActivity.FLEEING, applied(EventTag.FLEE))
        assertEquals(AgentActivity.SPEAKING, applied(EventTag.SAY))
    }

    @Test
    @DisplayName("a decision on its own changes nothing about what the agent is doing")
    fun aDecisionIsNotYetAnAction() {
        val spawned = after(record(BodyEvent.SPAWNED))

        val decided = advanced(spawned, record(BodyEvent.DECIDED).copy(intent = EventTag.MOVE_TO))

        // Otherwise every refused intent would show as briefly carried out.
        assertEquals(AgentActivity.EMBODIED, decided.activity)
    }

    @Test
    @DisplayName("the states a survival run turns on are each their own")
    fun theInterestingStatesAreDistinct() {
        assertEquals(AgentActivity.EMBODIED, after(record(BodyEvent.SPAWNED)).activity)
        assertEquals(AgentActivity.THINKING, after(record(BodyEvent.PERCEIVED)).activity)
        assertEquals(AgentActivity.UNANSWERED, after(record(BodyEvent.COGNITION_UNAVAILABLE)).activity)
        assertEquals(AgentActivity.TRAVELLING, after(record(BodyEvent.TRAVELLED)).activity)
        assertEquals(AgentActivity.BODILESS, after(record(BodyEvent.DESPAWNED)).activity)
        assertEquals(AgentActivity.BODILESS, after(record(BodyEvent.SPAWN_REJECTED)).activity)
        assertEquals(AgentActivity.DEAD, after(record(BodyEvent.DIED)).activity)
    }

    @Test
    @DisplayName("a spawn refused because the agent already has a body leaves the body where it is")
    fun theAlreadyEmbodiedRefusalIsNotABodilessOne() {
        val spawned = after(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))

        // Typing /loombody spawn twice. The second command is refused *because* the body
        // is standing there, so a display that read every refusal as "no body" labelled a
        // live, visible body "no body" - and turned the bar yellow for a whole goal-review
        // cadence, since no later record restates the activity.
        val again = advanced(spawned, record(BodyEvent.SPAWN_REJECTED).copy(outcome = EventTag.ALREADY_EMBODIED))

        assertEquals(AgentActivity.EMBODIED, again.activity)
        assertEquals(ObserverLevel.CALM, ObserverView().render(again).level)
    }

    @Test
    @DisplayName("the refusals that really do leave an agent bodiless still read that way")
    fun theGenuinelyBodilessRefusalsAreUnchanged() {
        val bodiless =
            listOf(EventTag.UNKNOWN_WORLD, EventTag.WORLD_UNHELD, EventTag.WORLD_REFUSED, EventTag.HAS_DIED)

        for (outcome in bodiless) {
            val refused = after(record(BodyEvent.SPAWN_REJECTED).copy(outcome = outcome))

            assertEquals(AgentActivity.BODILESS, refused.activity, outcome)
        }
    }

    @Test
    @DisplayName("a conversion that took the body reads as bodiless; a refused one changes nothing")
    fun theTwoConversionsAreToldApart() {
        val walking =
            after(
                record(BodyEvent.INTENT_APPLIED).copy(intent = EventTag.MOVE_TO, outcome = EventTag.ACCEPTED),
            )

        val refused = advanced(walking, record(BodyEvent.CONVERTED).copy(outcome = EventTag.CONVERSION_REFUSED))
        val taken = advanced(walking, record(BodyEvent.CONVERTED).copy(outcome = EventTag.BODY_TAKEN))

        // Loom cancels the transform, so the ordinary case is a body that never changed.
        assertEquals(AgentActivity.MOVING, refused.activity)
        // A conversion that got past the refusal leaves the agent alive with nothing to
        // act through, and nothing re-embodies it, so `moving` would be a fiction.
        assertEquals(AgentActivity.BODILESS, taken.activity)
    }

    @Test
    @DisplayName("a cycle no mind answered carries no model stamp, even after one that was answered")
    fun anUnansweredCycleDropsTheStampOfTheOneBeforeIt() {
        val answered =
            after(
                record(BodyEvent.PERCEIVED).copy(trigger = EventTag.GOAL_REVIEW),
                record(BodyEvent.DECIDED).copy(provenance = STAMP),
            )

        val timedOut =
            advanced(
                answered,
                record(BodyEvent.PERCEIVED, tick = 1L).copy(trigger = EventTag.GOAL_REVIEW),
            ).let { advanced(it, record(BodyEvent.COGNITION_UNAVAILABLE, tick = 1L).copy(outcome = "timedOut")) }

        // Carrying the stamp over rendered "goal review -> test-model/test-v1 routine"
        // beside "mind did not answer": a reading no record stated, about a cycle no model
        // answered, from the one function whose rule is that nothing is invented.
        assertNull(timedOut.provenance)
        assertEquals(
            "$WATCHED | energy not read yet | mind did not answer | goal review",
            ObserverView().render(timedOut).text,
        )
    }

    @Test
    @DisplayName("a cycle still waiting for an answer shows no model, not the last one's")
    fun anInFlightCycleDropsTheStampOfTheOneBefore() {
        val answered =
            after(
                record(BodyEvent.PERCEIVED).copy(requestId = "req-1", trigger = EventTag.GOAL_REVIEW),
                record(BodyEvent.DECIDED).copy(requestId = "req-1", provenance = STAMP),
            )

        val waiting =
            advanced(
                answered,
                record(BodyEvent.PERCEIVED, tick = 1L).copy(requestId = "req-2", trigger = EventTag.GOAL_REVIEW),
            )

        // The whole of a silent mind's wait is spent in this state - about twenty-nine
        // seconds of it against a mind that never replies - and carrying the stamp read
        // "this trigger was answered by this model" for all of it. It is the same reading
        // the failure record already refuses, one record earlier.
        assertNull(waiting.provenance)
        assertEquals(
            "$WATCHED | energy not read yet | thinking | goal review",
            ObserverView().render(waiting).text,
        )
    }

    @Test
    @DisplayName("a perception restating the cycle it already stamped keeps that stamp")
    fun theSameCycleKeepsItsStamp() {
        val answered =
            after(
                record(BodyEvent.PERCEIVED).copy(requestId = "req-1", trigger = EventTag.GOAL_REVIEW),
                record(BodyEvent.DECIDED).copy(requestId = "req-1", provenance = STAMP),
            )

        val restated =
            advanced(answered, record(BodyEvent.PERCEIVED, tick = 1L).copy(requestId = "req-1"))

        // A stamp is dropped because a *new* cycle has nothing to show, not because a
        // perception arrived: the model that answered this request still answered it.
        assertEquals(STAMP, restated.provenance)
    }

    @Test
    @DisplayName("death is never left, whatever arrives after it")
    fun deathIsTerminal() {
        val dead = after(record(BodyEvent.DIED).copy(vitals = energy(0.0)))

        assertTrue(dead.isFinal)

        // Records can still arrive for a dead agent: a world hold changing, a decision
        // landing too late. None of them may bring it back to life on the display.
        val afterwards =
            listOf(BodyEvent.SPAWNED, BodyEvent.MOVED, BodyEvent.PERCEIVED, BodyEvent.WORLD_LOADED)
                .fold(dead) { state, event -> advanced(state, record(event)) }

        assertEquals(AgentActivity.DEAD, afterwards.activity)
        assertTrue(afterwards.isFinal)
    }

    @Test
    @DisplayName("world-level records say nothing about what this agent is doing")
    fun worldRecordsDoNotMoveTheAgent() {
        val standing = after(record(BodyEvent.SPAWNED))

        val held = advanced(standing, record(BodyEvent.WORLD_HOLD_FAILED, detail = "chunks 0,0"))

        assertEquals(AgentActivity.EMBODIED, held.activity)
    }

    @Test
    @DisplayName("a moving agent stops being called moving once its steps stop")
    fun motionDecaysWhenTheStepsStop() {
        // A step alone does not claim to know what the agent chose, so the activity comes
        // from the applied intent; what a step carries is the clock.
        val moving =
            after(
                record(BodyEvent.INTENT_APPLIED, tick = 100L)
                    .copy(intent = EventTag.MOVE_TO, outcome = EventTag.ACCEPTED),
                record(BodyEvent.MOVED, tick = 100L),
            )

        assertEquals(AgentActivity.MOVING, decayed(moving, 100L + MOTION_IDLE_TICKS - 1).activity)
        // Without this an arrived agent would be labelled "moving" until its next goal
        // review - a whole cadence of standing perfectly still under a label saying otherwise.
        assertEquals(AgentActivity.EMBODIED, decayed(moving, 100L + MOTION_IDLE_TICKS).activity)
    }

    @Test
    @DisplayName("a movement whose first step never arrives decays like any other")
    fun anAcceptedIntentStartsTheClockToo() {
        val accepted =
            after(
                record(BodyEvent.INTENT_APPLIED, tick = 50L)
                    .copy(intent = EventTag.MOVE_TO, outcome = EventTag.ACCEPTED),
            )

        assertEquals(AgentActivity.EMBODIED, decayed(accepted, 50L + MOTION_IDLE_TICKS).activity)
    }

    @Test
    @DisplayName("time alone changes nothing about an agent that was not moving")
    fun decayTouchesNothingElse() {
        val resting =
            after(record(BodyEvent.INTENT_APPLIED).copy(intent = EventTag.REST, outcome = EventTag.ACCEPTED))
        val dead = after(record(BodyEvent.DIED))

        assertEquals(resting, decayed(resting, LATER))
        assertEquals(dead, decayed(dead, LATER), "a tombstone must not decay into standing by")
    }
}
