/*
 * The operator's way in, tested without a server.
 *
 * `reply` is the whole command apart from the two Bukkit lines that send its answer, so a
 * fake sink is enough to exercise every path. The one case worth stating outright is a
 * refused agent id: it must come back as the domain's own sentence rather than as an
 * exception escaping the command, and it must never leave a watch half-attached.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.WORLD_AGENT_ID
import dev.loom.observe.Note
import dev.loom.observe.Observatory
import dev.loom.observe.ObserverSink
import dev.loom.observe.Overlay
import dev.loom.observe.WatchStanding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The agent the operator asks to watch. */
private const val AGENT = "agent-ada"

/** The one operator typing in these tests, as `BukkitObserverSink` would identify them. */
private const val OPERATOR = "operator-1"

/** A second opped account, since `loom.observe` can be held by more than one. */
private const val OTHER_OPERATOR = "operator-2"

/** A second agent, for the case where a watch really does start knowing nothing. */
private const val OTHER_AGENT = "agent-bo"

/** What a watch with nothing known yet is told, since the record is all it may read. */
private const val FRESH_ADVICE =
    "Lines appear as the record does, so attach before spawning to catch the first energy reading."

/** What a watch the record has already told something is told instead, where that advice is false. */
private const val KEPT_NOTE =
    "keeping what is already known about it: what the record has said carries over, so nothing was lost."

class WatchCommandTest {
    private val observatory = Observatory()
    private val command = WatchCommand(observatory)
    private val sink = InertSink()

    /** [WatchCommand.reply] for the given words, as a player would type them. */
    private fun typed(vararg args: String): String? = command.reply(args, sink)

    /**
     * The same words, on a surface built fresh for the same operator.
     *
     * `onCommand` builds a new `BukkitObserverSink` on every invocation, so this - and not
     * [typed] - is what a second command from one player actually looks like.
     */
    private fun retyped(
        surface: InertSink,
        vararg args: String,
    ): String? = command.reply(args, surface)

    /**
     * Lets the run state a reserve for [AGENT], which is what a watch can come to know.
     *
     * Offered rather than shown: `saw` accumulates and only a refresh delivers, so this moves
     * what the display knows without touching a sink.
     */
    private fun theRunRecordsASpawn() =
        observatory.saw(
            BodyEventRecord(
                tick = 0L,
                agentId = AGENT,
                event = BodyEvent.SPAWNED,
                detail = "",
                vitals = AgentVitals(energy = 98.5, capacity = 100.0),
            ),
        )

    /**
     * Lets the run record a step for [AGENT], which is nothing a watcher can see.
     *
     * A walking body records one every tick, carrying no reading and changing no activity, so
     * this moves what the watch holds without putting anything on the display.
     */
    private fun theRunRecordsAStep() =
        observatory.saw(
            BodyEventRecord(
                tick = 1L,
                agentId = AGENT,
                event = BodyEvent.MOVED,
                detail = "",
            ),
        )

    @Test
    @DisplayName("naming an agent attaches the watch and says how a fresh watch behaves")
    fun namingAnAgentStartsTheWatch() {
        val reply = typed(AGENT)

        assertEquals(WatchStanding.Mine(AgentId(AGENT)), observatory.standing(OPERATOR))
        assertEquals("watching $AGENT. $FRESH_ADVICE", reply)
    }

    @Test
    @DisplayName("with no argument the command answers who is being watched")
    fun theCommandReportsTheCurrentWatch() {
        assertEquals("nobody is being watched. /loomwatch <agentId> to start.", typed())

        typed(AGENT)

        assertEquals("watching $AGENT", typed())
    }

    @Test
    @DisplayName("off ends the watch, and off again is answered rather than refused")
    fun offEndsTheWatch() {
        typed(AGENT)

        assertEquals("stopped watching $AGENT", typed("off"))
        assertEquals(WatchStanding.Nobody, observatory.standing(OPERATOR))
        assertEquals("nobody was being watched", typed("off"))
    }

    @Test
    @DisplayName("one operator cannot end another's watch, and is answered truthfully")
    fun offFromANonWatcherIsRefusedAndSaysSo() {
        typed(AGENT)

        val reply = retyped(InertSink(observer = OTHER_OPERATOR), "off")

        // The whole sequence the finding described: P watches, Q types off. Q used to be
        // told they had stopped watching ada, and P's bar came down with nothing said.
        assertEquals("somebody else is watching. Only the operator who started a watch can stop it.", reply)
        assertEquals(0, sink.releases, "P's display must not come down for a command P did not type")
        assertEquals(0, sink.lines, "and nothing happened to P, so P is told nothing")
        assertEquals(WatchStanding.Mine(AgentId(AGENT)), observatory.standing(OPERATOR))
    }

    @Test
    @DisplayName("a non-watcher asking who is watched is not told which agent it is")
    fun theReportToANonWatcherNamesNoAgent() {
        typed(AGENT)

        val reply = retyped(InertSink(observer = OTHER_OPERATOR))

        assertEquals("somebody else is watching. /loomwatch <agentId> to take the watch over.", reply)
    }

    @Test
    @DisplayName("a refused agent id is answered in the domain's own words, and starts no watch")
    fun aRefusedAgentIdIsAReplyAndNotAnException() {
        val refusal = assertThrows<IllegalArgumentException> { AgentId(WORLD_AGENT_ID) }.message

        assertEquals(refusal, typed(WORLD_AGENT_ID))
        assertEquals(
            WatchStanding.Nobody,
            observatory.standing(OPERATOR),
            "a refused id must never leave a watch half-attached",
        )
    }

    @Test
    @DisplayName("a blank agent id is refused the same way, since the domain refuses it")
    fun aBlankAgentIdIsRefusedToo() {
        assertEquals("agentId must not be blank", typed(" "))
        assertEquals(WatchStanding.Nobody, observatory.standing(OPERATOR))
    }

    @Test
    @DisplayName("too many words gets Bukkit's own usage line rather than a guess")
    fun extraArgumentsFallBackToTheUsage() {
        // `null` is how this command asks Bukkit to print the usage from plugin.yml, so
        // the one place the syntax is written down stays the one place it is shown.
        assertNull(typed(AGENT, "please"))
        assertEquals(
            WatchStanding.Nobody,
            observatory.standing(OPERATOR),
            "an unparsed command must not attach anything",
        )
    }

    @Test
    @DisplayName("the command paints nothing itself; the display's own timer does that")
    fun theCommandOnlyHandsTheSinkOver() {
        typed(AGENT)
        typed()

        // Every line an operator sees is delivered from the observatory's refresh, which
        // runs on the server timer. A command that painted a bar itself would be doing it
        // from whichever thread typed it, and Bukkit is a main-thread API.
        assertEquals(0, sink.lines, "the command showed something on its own")

        typed("off")

        // Taking the display down is the exception, and is the observatory's doing.
        assertEquals(1, sink.releases)
        assertEquals(0, sink.lines)
    }

    @Test
    @DisplayName("a sink built for a command that starts no watch is left inert")
    fun anUnusedSinkIsNeverTouched() {
        // The command builds a sink before it knows one is needed; that is only safe
        // because an unwatched sink costs nothing and leaves nothing behind.
        typed()
        typed(WORLD_AGENT_ID)
        typed(AGENT, "please")

        assertEquals(0, sink.lines)
        assertEquals(0, sink.releases)
    }

    @Test
    @DisplayName("typing the command twice does not tell the operator they stopped watching")
    fun rewatchingDoesNotDisplaceTheOperatorWhoTyped() {
        typed(AGENT)
        theRunRecordsASpawn()

        val reply = retyped(InertSink(), AGENT)

        // The second command hands over a fresh surface for the same person, and the
        // first surface is the one with the bar on it. Reading that as a displacement sent
        // "no longer watching ada" - immediately followed by "watching ada" - and took the
        // operator's own bar down in between.
        assertEquals(WatchStanding.Mine(AgentId(AGENT)), observatory.standing(OPERATOR))
        assertEquals(0, sink.lines, "the operator was told something about their own watch")
        assertEquals(0, sink.releases, "the operator's own display was taken down under them")
        assertEquals("watching $AGENT, $KEPT_NOTE", reply)
    }

    @Test
    @DisplayName("retyping the command on the same agent says it kept what it knew")
    fun retypingSaysWhatWasKeptInsteadOfAdvisingAFreshStart() {
        typed(AGENT)
        theRunRecordsASpawn()

        val reply = retyped(InertSink(), AGENT)

        // Nothing at all happened here: the surface stays, and so does the state, because what
        // is known follows the agent. Advising this operator to attach before spawning is
        // advice about an agent whose reserve their bar is already showing.
        assertEquals("watching $AGENT, $KEPT_NOTE", reply)
        assertFalse(reply?.contains("attach before spawning") ?: true, "$reply")
    }

    @Test
    @DisplayName("retyping mid-walk keeps the advice, since a step puts nothing on the display")
    fun retypingWhileOnlyStepsHaveLandedKeepsThePreSpawnAdvice() {
        // The other window an operator meets: the agent was spawned and walking before this
        // watch existed, so the only record it has heard is a step. A step states no reading,
        // says nothing in chat and leaves the activity alone, so this bar is still empty and the
        // advice about the next reading is the one thing worth saying.
        typed(AGENT)
        theRunRecordsAStep()

        val reply = retyped(InertSink(), AGENT)

        assertEquals("watching $AGENT. $FRESH_ADVICE", reply)
    }

    @Test
    @DisplayName("retyping before the run has said anything is still a fresh watch")
    fun retypingBeforeTheFirstRecordKeepsThePreSpawnAdvice() {
        // Exactly the demo procedure: attach first, then attach again before spawning - a
        // retype, a second client window, or simply not being sure the first one took. The
        // state survives, but it says nothing at all, so there is nothing to have kept and the
        // advice is the one thing this operator needs: the spawn is what gives them a reading.
        typed(AGENT)

        val reply = retyped(InertSink(), AGENT)

        assertEquals("watching $AGENT. $FRESH_ADVICE", reply)
    }

    @Test
    @DisplayName("taking a watch over says nothing about the watch it displaced")
    fun aTakeoverIsAnsweredAsAFirstWatchWouldBe() {
        typed(AGENT)
        theRunRecordsASpawn()
        val takingOver = InertSink(observer = OTHER_OPERATOR)

        val reply = retyped(takingOver, AGENT)

        // This operator has watched nothing, so "nothing was lost" would be a sentence about a
        // watch they never had - and told they kept something, they could infer that the
        // operator they displaced had been watching this very agent, which the no-argument
        // report deliberately refuses to say. The answer is the one a first watch is given.
        assertEquals("watching $AGENT. $FRESH_ADVICE", reply)
        assertEquals(
            WatchCommand(Observatory()).reply(arrayOf(AGENT), takingOver),
            reply,
            "a takeover must be indistinguishable from a first watch of the run",
        )
        assertEquals(1, sink.lines, "the displaced operator is still told")
        assertEquals(1, sink.releases, "and their own display still comes down")
    }

    @Test
    @DisplayName("a watch on a different agent is fresh again, and gets the pre-spawn advice")
    fun switchingAgentsIsAFreshWatch() {
        typed(AGENT)

        val reply = retyped(InertSink(), OTHER_AGENT)

        // Naming another agent carries nothing over, so this watch really does know nothing
        // and the advice is worth having: the spawn record is what gives it a first reading.
        assertEquals("watching $OTHER_AGENT. $FRESH_ADVICE", reply)
    }

    @Test
    @DisplayName("attaching again after off is fresh too, since a stopped watch keeps no history")
    fun attachingAfterOffIsFreshAgain() {
        typed(AGENT)
        typed("off")

        val reply = retyped(InertSink(), AGENT)

        // The bar came down, and the observatory keeps no history past that - which is the
        // step the demo procedure takes to see what a watch attached after a death shows.
        assertEquals("watching $AGENT. $FRESH_ADVICE", reply)
    }

    @Test
    @DisplayName("a different operator taking the watch over is still a displacement")
    fun anotherOperatorStillDisplacesTheWatch() {
        typed(AGENT)

        retyped(InertSink(observer = OTHER_OPERATOR), AGENT)

        // The one-watch-at-a-time rule is unchanged: what identifies a watch is who is
        // looking at it, so somebody else asking is still told the first operator so.
        assertEquals(1, sink.lines, "the displaced operator has to be told")
        assertEquals(1, sink.releases, "the displaced display has to come down")
    }
}

/** A sink that only counts being touched, since the command is not supposed to paint. */
private class InertSink(
    override val observer: String = OPERATOR,
) : ObserverSink {
    var lines = 0
        private set
    var releases = 0
        private set

    override val isPresent = true

    override fun show(overlay: Overlay) {
        lines++
    }

    override fun note(note: Note) {
        lines++
    }

    override fun release() {
        releases++
    }
}
