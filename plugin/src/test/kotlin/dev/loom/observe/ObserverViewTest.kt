/*
 * The redaction rule, tested as a sweep rather than as a list of cases.
 *
 * The central test here puts one improbable marker into every record's `detail` and then
 * asserts the marker cannot be found in anything an observer is shown. That shape is the
 * point: a body event added later is covered the day it is added, whereas a test naming
 * today's events one by one would pass forever while a new event quietly leaked its
 * detail. `decided` is the one deliberate exception and is asserted separately, in both
 * directions - it must carry the reasoning, and it must still be bounded.
 */
package dev.loom.observe

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EnergyTrend
import dev.loom.body.EventTag
import dev.loom.body.INTENT_REFUSAL_TAGS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A string no phrase in the view contains, so finding it means a detail was echoed. */
private const val MARKER = "zzsecretzz"

/** The events the view is deliberately silent about, and the reason each one is. */
private val DELIBERATELY_SILENT =
    mapOf(
        BodyEvent.MOVED to "one a tick for a walking agent",
        BodyEvent.WORLD_INTERACTION to
            "confirmed server effects stay in evidence; only a disagreement needs a note",
        BodyEvent.THREAT_SUPPRESSED to
            "the append-only record is the evidence; a note once a second for as long as " +
            "something hostile stood in view would fill all eight lines with the thing that " +
            "did not happen",
        BodyEvent.ENERGY_CHANGED to "several a second while an agent regenerates",
        BodyEvent.HEALTH_CHANGED to "one a half-heart for a body under attack",
    )

class ObserverViewTest {
    private val view = ObserverView()

    private val walking =
        WatchState(
            agentId = WATCHED,
            vitals = energy(16.0),
            trend = EnergyTrend.DECLINING,
            activity = AgentActivity.MOVING,
            trigger = EventTag.GOAL_REVIEW,
            stamp = DecisionStamp(requestId = "req-1", provenance = STAMP),
        )

    /** The note [record] produces, which this test expects there to be one of. */
    private fun spoken(record: BodyEventRecord): Note =
        checkNotNull(view.noteFor(record)) { "${record.event} said nothing to an operator" }

    @Test
    @DisplayName("no event but the decision may put its own detail on an operator's screen")
    fun onlyADecisionShowsItsDetail() {
        for (event in BodyEvent.entries) {
            assertEquals(
                event == BodyEvent.DECIDED,
                view.showsDetail(event),
                "$event must not decide for itself whether its detail is shown",
            )
        }
    }

    @Test
    @DisplayName("a detail an observer may not see is not echoed by any line about it")
    fun noNoteEchoesADetailItMayNotShow() {
        val leaky = BodyEvent.entries.filter { it != BodyEvent.DECIDED }

        for (event in leaky) {
            val note = view.noteFor(record(event, detail = "at 128,64,-512 because $MARKER"))

            assertFalse(note?.text?.contains(MARKER) ?: false, "$event echoed its detail: ${note?.text}")
        }
    }

    @Test
    @DisplayName("a cognition failure's reason stays where it was already refused")
    fun aFailureReasonNeverReachesAnObserver() {
        // The detail a real timeout carries is written a layer down, and a provider
        // failure's text can quote back the perception - or the credential - it was
        // given. The tag is what an operator needs; the prose is not.
        val failed =
            record(BodyEvent.COGNITION_UNAVAILABLE, detail = "Bearer $MARKER refused: $MARKER")
                .copy(outcome = "timedOut")

        val note = spoken(failed)

        assertEquals("its mind did not answer (timed out)", note.text)
        assertEquals(ObserverLevel.CONCERN, note.level)
    }

    @Test
    @DisplayName("a decision carries the reasoning and the stamp that produced it")
    fun aDecisionSaysWhichMindSaidWhat() {
        val decided = record(BodyEvent.DECIDED, detail = "walk north to the village").copy(provenance = STAMP)

        assertEquals("decided [test-model/test-v1 routine]: walk north to the village", spoken(decided).text)
    }

    @Test
    @DisplayName("a model that answered with a page still costs exactly one bounded line")
    fun reasoningIsBounded() {
        val page = "because ".repeat(500)
        val decided = record(BodyEvent.DECIDED, detail = page).copy(provenance = STAMP)

        val shown = spoken(decided).text

        assertTrue(shown.length <= ObserverBound().noteChars, "was ${shown.length}")
        assertTrue(shown.endsWith("..."), shown)
    }

    @Test
    @DisplayName("an ordinary answer keeps all of its reasoning, which a boss bar's width did not allow")
    fun anOrdinaryAnswerIsNotClipped() {
        // Verbatim what the scripted mind answers a goal review with, and the first
        // in-world run showed it losing its second half to the standing line's bound.
        // A note is a chat message, so it has room the standing line does not.
        val reasoning = "idle: reviewed goals at the Routine tier; nothing outranks what is already underway"
        val decided = record(BodyEvent.DECIDED, detail = reasoning).copy(provenance = STAMP)

        val shown = spoken(decided).text

        assertTrue(shown.endsWith(reasoning), shown)
    }

    @Test
    @DisplayName("a decision with no stamp says so by omission rather than by inventing one")
    fun anUnstampedDecisionClaimsNoModel() {
        assertEquals("decided: rest", spoken(record(BodyEvent.DECIDED, detail = "rest")).text)
    }

    @Test
    @DisplayName("the events worth a line get one, and the noisy ones are silent on purpose")
    fun theStreamIsBoundedByChoice() {
        for (event in BodyEvent.entries) {
            val note = view.noteFor(record(event))
            val why = DELIBERATELY_SILENT[event]

            if (why == null) {
                assertNotNull(note, "$event says nothing; give it a line or record why it is silent")
            } else {
                assertNull(note, "$event must stay silent: $why")
            }
        }
    }

    @Test
    @DisplayName("a refused login says which bound refused it, because they are opposite facts")
    fun everyLoginRefusalSaysWhichOneItWas() {
        // One event name over four different situations, and the tag is the whole of what tells
        // them apart. Read as one sentence, an operator could not tell an agent that had died
        // from a run whose ledger is unreadable - one of which is the experiment's result and
        // the other a fault to go and fix.
        val died = spoken(record(BodyEvent.WITNESS_DENIED).copy(outcome = EventTag.LOGIN_DEAD_IDENTITY))
        assertEquals("was refused a login: it had already DIED and does not return.", died.text)
        assertEquals(ObserverLevel.ALARM, died.level)

        val sealed = spoken(record(BodyEvent.WITNESS_DENIED).copy(outcome = EventTag.LOGIN_LEDGER_UNSAFE))
        assertEquals("was refused a login: Loom cannot read its own death records.", sealed.text)
        assertEquals(ObserverLevel.ALARM, sealed.level, "a sealed ledger stops every agent and needs a human")

        val claimed = spoken(record(BodyEvent.WITNESS_DENIED).copy(outcome = EventTag.LOGIN_IDENTITY_MISMATCH))
        assertEquals("was refused a login: its name was claimed from another account", claimed.text)
        assertEquals(ObserverLevel.CONCERN, claimed.level, "somebody else's account is not this agent's death")

        // A refusal with no tag at all is the one case with nothing more to say, and it must
        // not borrow the wording of the case that reports a permadeath.
        val bare = spoken(record(BodyEvent.WITNESS_DENIED))
        assertEquals("was refused a login", bare.text)
        assertEquals(ObserverLevel.CONCERN, bare.level)
    }

    @Test
    @DisplayName("a disputed cross-check says which kind of evidence the server disagreed with")
    fun everyDisputeSaysWhichCrossCheckFoundIt() {
        // One event name over two entirely different cross-checks. A vitals dispute doubts the
        // numbers beside the agent; a movement dispute doubts where the agent is. Read as one
        // sentence, every movement dispute reached an operator labelled as a vitals
        // disagreement - a wrong statement about the run rather than a vague one.
        val vitals = spoken(record(BodyEvent.WITNESS_DISPUTED).copy(outcome = EventTag.DISPUTED_VITALS))
        assertEquals(
            "reported vitals or inventory the server disagrees with; its readings are suspect",
            vitals.text,
        )
        assertEquals(ObserverLevel.CONCERN, vitals.level)

        val both = spoken(record(BodyEvent.WITNESS_DISPUTED).copy(outcome = EventTag.DISPUTED_BOTH))
        assertEquals(vitals.text, both.text, "both kinds of reading are still readings")

        val movementTags =
            listOf(EventTag.DISPUTED_POSITION, EventTag.DISPUTED_JOURNEY, EventTag.DISPUTED_MOVEMENT)
        for (tag in movementTags) {
            val note = spoken(record(BodyEvent.WITNESS_DISPUTED).copy(outcome = tag))

            assertFalse(note.text.contains("vitals"), "$tag must not read as a vitals dispute: ${note.text}")
            assertFalse(note.text.contains("inventory"), "$tag must not read as an inventory dispute")
            assertTrue(note.text.contains("movement") || note.text.contains("position"), note.text)
            assertEquals(ObserverLevel.CONCERN, note.level)
        }
        // Three sentences for three faults, because which of them it was is what an operator
        // would go and look at: a position the server disagrees with is a simulation that has
        // diverged, and a journey fault is a report contradicting its own arithmetic.
        assertEquals(
            movementTags.size,
            movementTags.map { spoken(record(BodyEvent.WITNESS_DISPUTED).copy(outcome = it)).text }.distinct().size,
        )

        // A tag nobody has written a sentence for may borrow neither wording, because it is a
        // cross-check this view has never heard of.
        val later = spoken(record(BodyEvent.WITNESS_DISPUTED).copy(outcome = "disputedSomethingElse"))
        assertEquals("reported something the server disagrees with", later.text)
    }

    @Test
    @DisplayName("a managed player arriving and leaving is said plainly, and a quit is not a death")
    fun aPlayerBodyComingAndGoingIsNotAnAlarm() {
        val joined = spoken(record(BodyEvent.WITNESS_JOINED))
        assertEquals("connected to the server and has a body now", joined.text)
        assertEquals(ObserverLevel.CALM, joined.level)

        // The one thing this line has to do is stop an operator reading a disconnect as the
        // end of the agent: the process reconnects, and Loom writes no tombstone for a quit.
        val quit = spoken(record(BodyEvent.WITNESS_QUIT))
        assertEquals("disconnected. It is not dead; the agent continues.", quit.text)
        assertEquals(ObserverLevel.CALM, quit.level)
    }

    @Test
    @DisplayName("a body in the world after a terminal death is an alarm about permadeath itself")
    fun aBreachedPermadeathIsTheLoudestThingOnTheScreen() {
        val revived = spoken(record(BodyEvent.WITNESS_REVIVED).copy(outcome = EventTag.REVIVED_AFTER_RESPAWN))

        // This one should be impossible, which is exactly why it has a line: an operator whose
        // run has produced it is looking at numbers that no longer measure permadeath.
        assertEquals("is in the world again after a terminal DEATH. Permadeath was not enforced.", revived.text)
        assertEquals(ObserverLevel.ALARM, revived.level)
    }

    @Test
    @DisplayName("death is stated as permanent, because that is the whole experiment")
    fun deathIsUnambiguous() {
        val note = spoken(record(BodyEvent.DIED))

        assertEquals("DIED. Permadeath: this agent will not return.", note.text)
        assertEquals(ObserverLevel.ALARM, note.level)
    }

    @Test
    @DisplayName("the standing line names the agent, its energy, what it is doing, and which mind")
    fun theStandingLineCarriesEveryFieldAViewerNeeds() {
        val overlay = view.render(walking)

        assertEquals(
            "$WATCHED | energy 16/20 declining | moving | goal review -> test-model/test-v1 routine",
            overlay.text,
        )
    }

    @Test
    @DisplayName("health joins the line only once a body is hurt, so a full food bar stays short")
    fun healthAppearsOnlyBelowCapacity() {
        // Food owns the gauge, but a body can be beaten to death on a full food bar,
        // so health earns a place on the line - only once there is something to say.
        val whole = walking.copy(health = energy(20.0))
        val hurt = walking.copy(health = energy(12.0))

        assertFalse(view.render(whole).text.contains("health"), view.render(whole).text)
        assertTrue(view.render(hurt).text.contains("health 12/20"), view.render(hurt).text)
    }

    @Test
    @DisplayName("an energy nothing has reported yet is shown as unread, not as a full bar")
    fun anUnreadEnergyIsSaidToBeUnread() {
        val overlay = view.render(WatchState(agentId = WATCHED))

        assertTrue(overlay.text.contains("energy not read yet"), overlay.text)
        assertNull(overlay.progress, "a full gauge for an unknown energy would hide a dying agent")
    }

    @Test
    @DisplayName("a tombstone reports no trend, because nothing is happening to it any more")
    fun aDeadAgentHasNoTrend() {
        val dead = walking.copy(vitals = energy(0.0), activity = AgentActivity.DEAD)

        // The last reading a dying agent leaves is always a falling one, so without this
        // the tombstone would read "declining" for the rest of the run.
        assertTrue(dead.isFinal)
        assertEquals(
            "$WATCHED | energy 0/20 | DEAD - will not return | goal review -> test-model/test-v1 routine",
            view.render(dead).text,
        )
    }

    @Test
    @DisplayName("the gauge is the energy, so a viewer reads survival without reading a number")
    fun theGaugeFollowsTheEnergy() {
        assertEquals(0.8, view.render(walking).progress)
        assertEquals(0.0, view.render(walking.copy(vitals = energy(0.0))).progress)
    }

    @Test
    @DisplayName("the loudest of what is happening, food, and the body's health sets the level")
    fun theLevelIsTheLoudestOfTheThree() {
        val full = walking.copy(vitals = energy(20.0))

        assertEquals(ObserverLevel.CALM, view.render(full).level)
        // Half of the food bar gone is worth attention, whatever the agent is doing.
        assertEquals(ObserverLevel.CONCERN, view.render(walking.copy(vitals = energy(10.0))).level)
        // A quarter left is an alarm however calm the activity looks.
        assertEquals(ObserverLevel.ALARM, view.render(walking.copy(vitals = energy(5.0))).level)
        // And so is fleeing, on a full bar.
        assertEquals(ObserverLevel.ALARM, view.render(full.copy(activity = AgentActivity.FLEEING)).level)
        // A body beaten down on a full food bar is the other way an agent ends, so the
        // health reading raises the level exactly as the food reading would.
        assertEquals(ObserverLevel.CONCERN, view.render(full.copy(health = energy(9.0))).level)
        assertEquals(ObserverLevel.ALARM, view.render(full.copy(health = energy(4.0))).level)
    }

    @Test
    @DisplayName("a nearly full food reading is not an alarm, whatever its trend")
    fun livingIsNotAnEmergency() {
        // A trend names a change, while the colour names present survival risk. A nearly
        // full food bar remains calm even when its latest reading declined.
        val nearlyFull = walking.copy(vitals = energy(19.9), activity = AgentActivity.IDLE)

        assertTrue(view.render(nearlyFull).text.contains("declining"), view.render(nearlyFull).text)
        assertEquals(ObserverLevel.CALM, view.render(nearlyFull).level)
    }

    @Test
    @DisplayName("a line stays one line however long the agent id or the model name is")
    fun theStandingLineIsBoundedToo() {
        val overlay =
            view.render(
                WatchState(
                    agentId = "agent-" + "x".repeat(200),
                    vitals = energy(20.0),
                    activity = AgentActivity.MOVING,
                ),
            )

        assertTrue(overlay.text.length <= ObserverBound().lineChars, "was ${overlay.text.length}")
    }

    @Test
    @DisplayName("a trigger with no decision yet is still named, since that is why cognition ran")
    fun theTriggerIsShownBeforeAnyAnswerArrives() {
        val thinking =
            WatchState(
                agentId = WATCHED,
                vitals = energy(20.0),
                activity = AgentActivity.THINKING,
                trigger = EventTag.DAMAGED,
            )

        assertEquals("$WATCHED | energy 20/20 | thinking | damaged", view.render(thinking).text)
    }

    @Test
    @DisplayName("a refusal is distinguishable from an agent that chose to stand still")
    fun aRefusalReadsAsARefusal() {
        val outcomes =
            mapOf(
                EventTag.ACCEPTED to "the body is doing it",
                EventTag.REJECTED to "the body refused it",
                EventTag.UNSUPPORTED to "the body cannot do that yet",
                EventTag.NO_SUCH_BODY to "the body did not act on it",
            )

        for ((outcome, expected) in outcomes) {
            assertEquals(expected, spoken(record(BodyEvent.INTENT_APPLIED).copy(outcome = outcome)).text, outcome)
        }
    }

    @Test
    @DisplayName("a refusal that says why it refused still reads as a refusal")
    fun everyRefusalTagReadsAsARefusal() {
        // A refusal now carries the reason in the same field, so `rejected` is only what a
        // refusal with nothing more specific to say arrives as. Read as an unrecognised tag,
        // every eat refusal said "the body did not act on it" - the sentence reserved for a
        // decision that reached no body at all - so an operator watching the survival run this
        // path enables could not tell an agent that was already full from one whose decision
        // never arrived anywhere. Swept over the derived set, so a refusal added later is
        // covered the day it is added.
        for (tag in INTENT_REFUSAL_TAGS) {
            val refused = spoken(record(BodyEvent.INTENT_APPLIED).copy(outcome = tag))

            assertEquals("the body refused it", refused.text, tag)
            assertEquals(ObserverLevel.CONCERN, refused.level, tag)
        }
        assertTrue(
            EventTag.EAT_RESERVE_FULL in INTENT_REFUSAL_TAGS,
            "the eat refusals are what made this a set rather than one tag",
        )
    }

    @Test
    @DisplayName("a spawn refused because the body is already there is not reported as bad news")
    fun theAlreadyEmbodiedRefusalIsSaidCalmly() {
        val again = record(BodyEvent.SPAWN_REJECTED).copy(outcome = EventTag.ALREADY_EMBODIED)
        val bodiless = record(BodyEvent.SPAWN_REJECTED).copy(outcome = EventTag.BODY_START_FAILED)

        assertEquals("already has a body, so a second one was refused", spoken(again).text)
        assertEquals(ObserverLevel.CALM, spoken(again).level)
        assertEquals("was refused a body", spoken(bodiless).text)
        assertEquals(ObserverLevel.CONCERN, spoken(bodiless).level)
    }

    @Test
    @DisplayName("a spawn refused because the agent has died says the death, not that a request failed")
    fun theHasDiedRefusalIsSaidAsAPermadeath() {
        val gone = record(BodyEvent.SPAWN_REJECTED).copy(outcome = EventTag.HAS_DIED)

        // A watch attached after the death never sees the `died` record, so this refusal is
        // the only line that will ever tell that operator the agent is not coming back. Said
        // as an ordinary refusal it read as a request to try again.
        assertEquals("had already DIED. Permadeath: this agent will not return.", spoken(gone).text)
        assertEquals(ObserverLevel.ALARM, spoken(gone).level)
    }
}
