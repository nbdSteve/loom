/*
 * The acceptance tests for the operator display, written from a viewer's seat.
 *
 * Two acceptance criteria are load-bearing here and each has its own test. Observation
 * must not alter a run, which this file proves the only way a test honestly can: the
 * observatory's *entire* input is a record stream and its entire output is one sink, so
 * a whole survival cycle is driven through it and the sink is the only thing that moves.
 * (The structural half - that nothing in this package can even name a body, a mind, or a
 * world - is `ObserveBoundaryTest`.) And the display must stay bounded and low-noise,
 * which is why a burst is asserted to be capped *and* to admit what it dropped.
 *
 * The awkward cases the criteria call out - death, disappearance, a cognition timeout,
 * and no observer at all - are each their own test below, because each
 * of them is a way a live demo goes wrong in front of an audience.
 */
package dev.loom.observe

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The agent nobody in these tests is watching. */
private const val OTHER = "agent-bo"

/** A second operator, for the cases where whose watch it is decides the answer. */
private const val OTHER_OPERATOR = "operator-2"

/** An arbitrary tick to refresh at, where the exact number does not matter. */
private const val NOW = 100L

class ObservatoryTest {
    private val observatory = Observatory()
    private val sink = RecordingSink()

    /** Attaches [sink] to [WATCHED], as the watch command does. */
    private fun attach() = observatory.watch(AgentId(WATCHED), sink)

    /** Hands [records] to the observatory and then delivers whatever accumulated. */
    private fun deliver(vararg records: BodyEventRecord) {
        records.forEach(observatory::saw)
        observatory.refresh(NOW)
    }

    @Test
    @DisplayName("a full cognition cycle reads perception, then decision, then what the body did")
    fun oneCycleReadsInOrder() {
        attach()

        deliver(
            record(BodyEvent.PERCEIVED).copy(trigger = EventTag.GOAL_REVIEW),
            record(BodyEvent.DECIDED, detail = "walk to the village").copy(provenance = STAMP),
            record(BodyEvent.INTENT_APPLIED).copy(intent = EventTag.MOVE_TO, outcome = EventTag.ACCEPTED),
        )

        assertEquals(
            listOf(
                "perceived the world (goal review)",
                "decided [test-model/test-v1 routine]: walk to the village",
                "the body is doing it",
            ),
            sink.notes.map { it.text },
        )
    }

    @Test
    @DisplayName("the standing line ends the cycle showing energy, activity, and the model that chose it")
    fun theStandingLineIsTheSummaryOfTheCycle() {
        attach()

        deliver(
            record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)),
            record(BodyEvent.PERCEIVED).copy(trigger = EventTag.GOAL_REVIEW),
            record(BodyEvent.DECIDED).copy(provenance = STAMP),
            record(BodyEvent.INTENT_APPLIED).copy(intent = EventTag.REST, outcome = EventTag.ACCEPTED),
        )

        assertEquals(
            "$WATCHED | energy 20/20 | resting | goal review -> test-model/test-v1 routine",
            sink.standing?.text,
        )
    }

    @Test
    @DisplayName("nothing reaches an operator until the display's own timer runs")
    fun everyLineIsDeliveredFromTheRefresh() {
        attach()

        // `saw` can be called from whichever thread appended the record, and Bukkit is a
        // main-thread API. A line shown from here would be a display that crashes a server.
        observatory.saw(record(BodyEvent.SPAWNED))

        assertEquals(emptyList<String>(), sink.everything)

        observatory.refresh(NOW)

        assertTrue(sink.everything.isNotEmpty())
    }

    @Test
    @DisplayName("records about another agent are not shown to a watcher of this one")
    fun anotherAgentIsNotThisAgent() {
        attach()

        deliver(record(BodyEvent.DIED).copy(agentId = OTHER))

        assertTrue(sink.notes.isEmpty(), "${sink.notes.map { it.text }}")
        // The standing line still says what is true of the watched agent: nothing yet.
        assertTrue(sink.standing?.text?.contains("nothing seen yet") ?: false, "${sink.standing}")
    }

    @Test
    @DisplayName("a step and an energy reading move the gauge without saying a word")
    fun theNoisyRecordsAreSilentButNotIgnored() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))
        val notesAfterSpawn = sink.notes.size

        deliver(
            record(BodyEvent.MOVED, tick = 1L),
            record(BodyEvent.ENERGY_CHANGED, tick = 1L).copy(vitals = energy(12.0)),
        )

        assertEquals(notesAfterSpawn, sink.notes.size, "a walking agent must not narrate every tick")
        assertEquals(0.6, sink.standing?.progress, "but the gauge has to follow the energy")
        assertTrue(sink.standing?.text?.contains("declining") ?: false, "${sink.standing}")
    }

    @Test
    @DisplayName("a refresh that has nothing new to say says nothing")
    fun theStandingLineIsPushedOnlyOnChange() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))
        val pushes = sink.overlays.size

        repeat(5) { observatory.refresh(NOW) }

        assertEquals(pushes, sink.overlays.size, "a resting agent costs one comparison per refresh, not one line")
    }

    @Test
    @DisplayName("energy decline and recovery are both legible without reading a file")
    fun survivalIsVisibleAsATrend() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))

        deliver(record(BodyEvent.ENERGY_CHANGED, tick = 1L).copy(vitals = energy(4.0)))
        val hurt = sink.standing

        deliver(record(BodyEvent.ENERGY_CHANGED, tick = 2L).copy(vitals = energy(9.0)))
        val healing = sink.standing

        assertTrue(hurt?.text?.contains("energy 4/20 declining") ?: false, "$hurt")
        assertEquals(ObserverLevel.ALARM, hurt?.level, "a fifth of capacity left is an alarm")
        assertTrue(healing?.text?.contains("energy 9/20 recovering") ?: false, "$healing")
    }

    @Test
    @DisplayName("a death freezes the display on a tombstone, because Loom is permadeath")
    fun deathIsTerminalOnScreenToo() {
        attach()
        deliver(
            record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)),
            record(BodyEvent.DIED, tick = 1L).copy(vitals = energy(0.0)),
        )
        val tombstone = sink.standing

        // Whatever arrives afterwards - a late decision, a world hold changing - the
        // display must not imply the agent came back, because no agent ever does.
        deliver(
            record(BodyEvent.SPAWNED, tick = 2L),
            record(BodyEvent.PERCEIVED, tick = 2L),
            record(BodyEvent.MOVED, tick = 2L),
        )

        assertTrue(tombstone?.text?.contains("DEAD - will not return") ?: false, "$tombstone")
        assertEquals(tombstone?.text, sink.standing?.text)
        assertTrue(
            sink.notes.any { it.text == "DIED. Permadeath: this agent will not return." },
            "${sink.notes.map { it.text }}",
        )
    }

    @Test
    @DisplayName("a mind that did not answer is reported as that, with no reason attached")
    fun aCognitionTimeoutIsVisibleAndRedacted() {
        attach()

        deliver(
            record(BodyEvent.PERCEIVED).copy(trigger = EventTag.DAMAGED),
            record(BodyEvent.COGNITION_UNAVAILABLE, detail = "read timed out after 14500ms", tick = 1L)
                .copy(outcome = "timedOut"),
        )

        assertEquals("its mind did not answer (timed out)", sink.notes.last().text)
        assertTrue(sink.standing?.text?.contains("mind did not answer") ?: false, "${sink.standing}")
        assertFalse(sink.everything.any { it.contains("14500") }, "${sink.everything}")
    }

    @Test
    @DisplayName("with nobody watching, a whole run costs nothing and breaks nothing")
    fun noObserverIsTheNormalCase() {
        // The default state of a headless research run: the records still flow, and the
        // display is a consumer that simply is not there.
        BodyEvent.entries.forEach { observatory.saw(record(it)) }
        observatory.refresh(NOW)

        assertEquals(WatchStanding.Nobody, observatory.standing(OPERATOR))
        assertEquals(emptyList<String>(), sink.everything)
        assertEquals(0, sink.releases)
    }

    @Test
    @DisplayName("an observer who walked away is dropped, and their display is taken down")
    fun aVanishedObserverIsReleased() {
        attach()
        deliver(record(BodyEvent.SPAWNED))

        sink.isPresent = false
        observatory.refresh(NOW + 1)

        assertEquals(
            WatchStanding.Nobody,
            observatory.standing(OPERATOR),
            "a disconnected player must not keep a watch alive",
        )
        // A boss bar survives a reconnect, so the surface is taken down rather than left
        // for whoever comes back to the same name.
        assertEquals(1, sink.releases)
    }

    @Test
    @DisplayName("a second watch displaces the first, and tells it so")
    fun oneWatchAtATime() {
        attach()
        val second = RecordingSink(observer = OTHER_OPERATOR)

        observatory.watch(AgentId(OTHER), second)

        assertEquals(WatchStanding.Mine(AgentId(OTHER)), observatory.standing(OTHER_OPERATOR))
        assertEquals(listOf("no longer watching $WATCHED"), sink.notes.map { it.text })
        assertEquals(1, sink.releases, "the displaced display has to come down")
    }

    @Test
    @DisplayName("stopping says who was being watched, and stopping twice is not an error")
    fun stoppingIsIdempotent() {
        attach()

        assertEquals(WatchStanding.Mine(AgentId(WATCHED)), observatory.stop(OPERATOR))
        assertEquals(1, sink.releases)
        assertEquals(WatchStanding.Nobody, observatory.stop(OPERATOR))
        assertEquals(WatchStanding.Nobody, observatory.standing(OPERATOR))
    }

    @Test
    @DisplayName("one operator cannot stop another's watch, and is told so rather than silently obeyed")
    fun stoppingIsRefusedToAnybodyButTheWatcher() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))
        val watching = sink.notes.size

        val refused = observatory.stop(OTHER_OPERATOR)

        // The bar belongs to the operator who started the watch. Releasing it here took it
        // off their screen mid-run with nothing said to them, and told the operator who
        // typed it that they had stopped a watch that was never theirs.
        assertEquals(WatchStanding.NotMine, refused)
        assertEquals(0, sink.releases, "the watcher's own display must not come down")
        assertEquals(watching, sink.notes.size, "nothing happened, so the watcher is told nothing")
        assertEquals(WatchStanding.Mine(AgentId(WATCHED)), observatory.standing(OPERATOR))
    }

    @Test
    @DisplayName("a non-watcher is not told which agent somebody else is watching")
    fun theReportNamesNoAgentToANonWatcher() {
        attach()

        assertEquals(WatchStanding.NotMine, observatory.standing(OTHER_OPERATOR))
    }

    @Test
    @DisplayName("Loom unloading takes the display down whoever it belongs to")
    fun shuttingDownEndsAnybodysWatch() {
        attach()

        observatory.shutDown()

        // The plugin's disable path, which has nobody to ask on the watcher's behalf: a bar
        // left standing after Loom unloaded would keep stating an energy nothing is reading.
        assertEquals(1, sink.releases)
        assertEquals(WatchStanding.Nobody, observatory.standing(OPERATOR))
        // Idempotent, since the plugin calls it whether or not anybody was watching.
        observatory.shutDown()
        assertEquals(1, sink.releases)
    }

    @Test
    @DisplayName("a record arriving after the watch ended reaches nobody")
    fun aStoppedWatchHearsNothingMore() {
        attach()
        observatory.stop(OPERATOR)
        val quiet = sink.everything.size

        deliver(record(BodyEvent.DIED))

        assertEquals(quiet, sink.everything.size)
    }

    @Test
    @DisplayName("a burst is capped, and the display admits what it dropped rather than hiding it")
    fun theStreamStaysBounded() {
        val tight = Observatory(maxPendingNotes = 3)
        tight.watch(AgentId(WATCHED), sink)

        // Alternating, so every line is news: a run of one repeated line is bounded by the
        // duplicate rule below instead, and would never reach the cap.
        repeat(10) { index ->
            val event = if (index % 2 == 0) BodyEvent.SPAWNED else BodyEvent.DESPAWNED
            tight.saw(record(event, tick = index.toLong()))
        }
        tight.refresh(NOW)

        // Three lines plus the notice: an operator sees what just happened, and is told
        // that they are not seeing all of it. A silent cap would be the worse failure.
        assertEquals(4, sink.notes.size, "${sink.notes.map { it.text }}")
        assertEquals("(7 earlier line(s) skipped)", sink.notes.first().text)
        assertEquals(
            listOf("its body was put away", "has a body now", "its body was put away"),
            sink.notes.drop(1).map { it.text },
            "the newest lines are the ones kept",
        )
    }

    @Test
    @DisplayName("a cap of nothing is refused, since it would drop every line silently")
    fun theCapMustBePositive() {
        assertThrows<IllegalArgumentException> { Observatory(maxPendingNotes = 0) }
    }

    @Test
    @DisplayName("re-running the watch command does not displace the operator who ran it")
    fun rewatchingFromTheSameObserverIsNotADisplacement() {
        attach()
        // What the command actually hands over the second time: a brand new surface for
        // the same person. Comparing sink instances read that as somebody else taking the
        // watch over, so the operator was told "no longer watching" about the watch they
        // had just started and their bar came down under them.
        val retyped = RecordingSink()

        observatory.watch(AgentId(WATCHED), retyped)

        assertEquals(WatchStanding.Mine(AgentId(WATCHED)), observatory.standing(OPERATOR))
        assertEquals(emptyList<String>(), sink.notes.map { it.text })
        assertEquals(0, sink.releases, "an operator re-running the command must not lose their own bar")
        assertEquals(0, retyped.releases)
    }

    @Test
    @DisplayName("the operator's own surface keeps the display, so a re-run leaves no orphaned bar")
    fun rewatchingKeepsPaintingOnTheSurfaceThatIsAlreadyUp() {
        attach()
        val retyped = RecordingSink()

        observatory.watch(AgentId(WATCHED), retyped)
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))

        // Only the sink that put a bar up can take it down, so the standing one keeps the
        // watch: painting on the fresh surface instead would leave two bars on one screen,
        // and the older of them owned by nothing.
        assertTrue(sink.overlays.isNotEmpty(), "the display already up is the one that must be repainted")
        assertEquals(emptyList<Overlay>(), retyped.overlays)
    }

    @Test
    @DisplayName("an operator switching agents keeps their display and is told nothing about it")
    fun theSameObserverSwitchingAgentsIsNotADisplacementEither() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))

        observatory.watch(AgentId(OTHER), RecordingSink())
        deliver(record(BodyEvent.SPAWNED, tick = 1L).copy(agentId = OTHER))

        assertEquals(WatchStanding.Mine(AgentId(OTHER)), observatory.standing(OPERATOR))
        assertEquals(0, sink.releases)
        assertFalse(sink.notes.any { it.text.startsWith("no longer watching") }, "${sink.notes.map { it.text }}")
        assertTrue(sink.standing?.text?.startsWith(OTHER) ?: false, "${sink.standing}")
    }

    @Test
    @DisplayName("an observer whose surface has gone stale is replaced rather than kept")
    fun aStaleSurfaceIsNotKeptForTheSameObserver() {
        attach()
        // The reconnect case: the same account, a surface that can no longer be painted
        // on. Keeping it would leave the operator looking at nothing at all.
        sink.isPresent = false
        val reconnected = RecordingSink()

        observatory.watch(AgentId(WATCHED), reconnected)
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(20.0)))

        assertEquals(1, sink.releases, "a surface that cannot be painted on has to come down")
        assertTrue(reconnected.overlays.isNotEmpty(), "the new surface has to be the one used")
    }

    @Test
    @DisplayName("re-running the command on the same agent keeps what is already known about it")
    fun rewatchingKeepsTheStateItWasAlreadyShowing() {
        attach()
        deliver(
            record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)),
            record(BodyEvent.PERCEIVED, tick = 1L).copy(trigger = EventTag.GOAL_REVIEW),
        )
        val standing = sink.standing

        observatory.watch(AgentId(WATCHED), RecordingSink())
        observatory.refresh(NOW + 1)

        // The state is a projection of the append-only record rather than anything about who
        // is looking, so retyping the command changes nothing about it. Rebuilt blank, an
        // in-world run lost the food reading until the next vitals-carrying record -
        // eighteen seconds - and the activity until the next goal review, up to a whole
        // cadence, with the gauge emptied on an agent that was not empty at all.
        assertEquals("$WATCHED | energy 4.4/20 | thinking | goal review", standing?.text)
        assertEquals(standing, sink.standing)
        assertEquals(0.22, sink.standing?.progress ?: 0.0, 1e-9, "the gauge must not empty on a live agent")
    }

    @Test
    @DisplayName("attaching a watch answers whether it kept what was known or starts blank")
    fun attachingSaysWhichOfTheTwoItWas() {
        // The observatory is the only place that can see whether the state survived, so it says
        // so rather than leaving the command to work it out from the arguments it typed: what
        // an operator is told about their own watch is then what actually happened to it.
        assertEquals(WatchAttachment.FRESH, observatory.watch(AgentId(WATCHED), sink))
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)))

        assertEquals(
            WatchAttachment.KEPT,
            observatory.watch(AgentId(WATCHED), RecordingSink()),
            "retyping the command on the same agent changes nothing at all",
        )
        assertEquals(
            WatchAttachment.FRESH,
            observatory.watch(AgentId(OTHER), RecordingSink()),
            "another agent carries nothing over",
        )

        observatory.stop(OPERATOR)

        assertEquals(
            WatchAttachment.FRESH,
            observatory.watch(AgentId(OTHER), RecordingSink()),
            "a stopped watch keeps no history, so starting again knows nothing",
        )
    }

    @Test
    @DisplayName("re-attaching before the record has said anything has kept nothing, and answers so")
    fun aStateThatSurvivedSayingNothingIsAFreshAttachment() {
        attach()

        val attachment = observatory.watch(AgentId(WATCHED), RecordingSink())
        observatory.refresh(NOW)

        // The window the demo procedure lives in: attached before the spawn, attached again
        // before it. A state object survived, and it says nothing - so an answer read from the
        // object rather than from what it holds claimed readings that were never taken, beside
        // a bar reading `energy not read yet`. Kept exactly as it was all the same: this is
        // about which of the two the operator is told, not about resetting anything.
        assertEquals(WatchAttachment.FRESH, attachment)
        assertEquals("$WATCHED | energy not read yet | nothing seen yet", sink.standing?.text)
        assertEquals(0, sink.releases, "the operator's own surface is still the one being painted")
    }

    @Test
    @DisplayName("taking a watch over is a fresh attachment for whoever took it")
    fun aTakeoverIsFreshForTheOperatorWhoTookIt() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)))
        val takingOver = RecordingSink(observer = OTHER_OPERATOR)

        val attachment = observatory.watch(AgentId(WATCHED), takingOver)
        observatory.refresh(NOW + 1)

        // This operator has never watched anything, so there is nothing of theirs to have kept -
        // and `KEPT` on a first-ever watch is inferable evidence that the operator they
        // displaced was watching this very agent, which `WatchStanding.NotMine` withholds.
        assertEquals(WatchAttachment.FRESH, attachment)
        // The state is still inherited, and the displacement still happens: every field of it
        // derives from records about this agent that the new observer may see anyway.
        assertEquals("$WATCHED | energy 4.4/20 | standing by", takingOver.standing?.text)
        assertEquals("no longer watching $WATCHED", sink.notes.last().text)
        assertEquals(1, sink.releases, "the displaced display still has to come down")
    }

    @Test
    @DisplayName("a step nothing renders leaves the display empty, and the answer says so")
    fun aStateHoldingOnlyAnUnrenderedStepIsAFreshAttachment() {
        attach()

        // An agent already walking when the watch was attached: its `moveTo` landed before this
        // watch existed, so the step arriving now is the only thing the state holds. A step
        // carries no reading, says nothing in chat, and leaves the activity alone - so the bar
        // is as empty as it was, and an answer read off the state claimed otherwise.
        deliver(record(BodyEvent.MOVED, tick = 1L))
        val standing = sink.standing

        val attachment = observatory.watch(AgentId(WATCHED), RecordingSink())

        assertEquals(WatchAttachment.FRESH, attachment)
        assertEquals("$WATCHED | energy not read yet | nothing seen yet", standing?.text)
        observatory.refresh(NOW + 1)
        assertEquals(standing, sink.standing, "the preserved state is kept as it was, not reset")
    }

    @Test
    @DisplayName("a watch whose surface was replaced still kept what was known")
    fun aReplacedSurfaceIsStillAKeptWatch() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)))
        sink.isPresent = false

        val attachment = observatory.watch(AgentId(WATCHED), RecordingSink())

        // The reconnect case. The answer follows the state and not the surface, because it is
        // the state that decides what the display still has to learn: this bar is painted from
        // scratch, and the reading it is painted with is one the record already established.
        assertEquals(WatchAttachment.KEPT, attachment)
    }

    @Test
    @DisplayName("what is known follows the agent onto a new surface, which is painted with it")
    fun aReplacedSurfaceIsPaintedWithWhatIsAlreadyKnown() {
        attach()
        deliver(
            record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)),
            record(BodyEvent.PERCEIVED, tick = 1L).copy(trigger = EventTag.GOAL_REVIEW),
        )
        sink.isPresent = false
        val reconnected = RecordingSink()

        observatory.watch(AgentId(WATCHED), reconnected)
        observatory.refresh(NOW + 1)

        // The reconnect case, where the surface really does have to be replaced. It has no
        // bar up yet, so it is painted from scratch - with the state the record has already
        // established and not with an empty one.
        assertEquals("$WATCHED | energy 4.4/20 | thinking | goal review", reconnected.standing?.text)
    }

    @Test
    @DisplayName("naming a different agent starts blank rather than carrying the last one's readings")
    fun switchingAgentsCarriesNothingOver() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)))

        observatory.watch(AgentId(OTHER), RecordingSink())
        observatory.refresh(NOW + 1)

        // Nothing has been recorded about this agent since the watch was attached, and the
        // display may only say what a record told it: carrying anything over would put one
        // agent's food reading and activity on another agent's line.
        assertEquals("$OTHER | energy not read yet | nothing seen yet", sink.standing?.text)
    }

    @Test
    @DisplayName("a watch that was stopped and started again starts blank")
    fun aStoppedWatchKeepsNoHistory() {
        attach()
        deliver(record(BodyEvent.SPAWNED).copy(vitals = energy(4.4)))
        observatory.stop(OPERATOR)
        val restarted = RecordingSink()

        observatory.watch(AgentId(WATCHED), restarted)
        observatory.refresh(NOW + 1)

        // Continuity belongs to the watch that is standing. This one's bar came down, and
        // nothing here keeps history after that, so a new watch shows what the run tells it
        // from now on.
        assertEquals("$WATCHED | energy not read yet | nothing seen yet", restarted.standing?.text)
    }
}
