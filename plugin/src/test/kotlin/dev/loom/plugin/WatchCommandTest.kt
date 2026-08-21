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
import dev.loom.body.WORLD_AGENT_ID
import dev.loom.observe.Note
import dev.loom.observe.Observatory
import dev.loom.observe.ObserverSink
import dev.loom.observe.Overlay
import dev.loom.observe.WatchStanding
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    @DisplayName("naming an agent attaches the watch and says how a fresh watch behaves")
    fun namingAnAgentStartsTheWatch() {
        val reply = typed(AGENT)

        assertEquals(WatchStanding.Mine(AgentId(AGENT)), observatory.standing(OPERATOR))
        assertEquals(
            "watching $AGENT. Lines appear as the record does, " +
                "so attach before spawning to catch the first energy reading.",
            reply,
        )
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

        val reply = retyped(InertSink(), AGENT)

        // The second command hands over a fresh surface for the same person, and the
        // first surface is the one with the bar on it. Reading that as a displacement sent
        // "no longer watching ada" - immediately followed by "watching ada" - and took the
        // operator's own bar down in between.
        assertEquals(WatchStanding.Mine(AgentId(AGENT)), observatory.standing(OPERATOR))
        assertEquals(0, sink.lines, "the operator was told something about their own watch")
        assertEquals(0, sink.releases, "the operator's own display was taken down under them")
        assertEquals(
            "watching $AGENT. Lines appear as the record does, " +
                "so attach before spawning to catch the first energy reading.",
            reply,
        )
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
