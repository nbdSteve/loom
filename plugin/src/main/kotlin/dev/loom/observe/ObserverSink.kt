/*
 * Where an operator's view of one agent goes.
 *
 * The port half of the observation path, and engine-free like the rest of this
 * package: an observer is somebody who can be shown a line of text and who may stop
 * being there, and nothing here knows that today that somebody is a Bukkit player.
 *
 * Two channels rather than one, because they answer different questions and have
 * very different budgets. The overlay is a single line that is always true *now* -
 * pushed only when it changes, so it costs nothing while an agent rests. A note is
 * one thing that just happened, shown once and never repeated, which is why the
 * things that happen every tick are deliberately never noted.
 */
package dev.loom.observe

/**
 * How alarming what an observer is being shown is.
 *
 * Carried as a level rather than as colour codes so this package stays engine-free:
 * what red means is the sink's business, and a sink with no colour at all (a console)
 * can ignore it entirely without losing the text.
 */
enum class ObserverLevel {
    /** Nothing is wrong. */
    CALM,

    /** Worth an operator's attention: the agent is losing ground, or something balked. */
    CONCERN,

    /** The agent is in danger, or is gone for good. */
    ALARM,
}

/**
 * The one-line status an observer keeps in view.
 *
 * [progress] is the agent's energy as a share of its capacity, or `null` while no reading
 * has reached the record yet. It is carried beside the text rather than drawn into it so a
 * sink with a real bar can move a real bar - which is what makes energy decline and
 * recovery legible at a glance instead of legible by reading a number.
 */
data class Overlay(
    val text: String,
    val level: ObserverLevel,
    val progress: Double? = null,
)

/** One thing that just happened, shown once. */
data class Note(
    val text: String,
    val level: ObserverLevel,
)

/**
 * An operator watching one agent.
 *
 * Deliberately write-only from Loom's side: a sink is told things and is never asked
 * anything about the world, which is what makes attaching one incapable of changing
 * what an agent perceives or decides.
 */
interface ObserverSink {
    /**
     * Who is being shown things here, as a stable id for that *person*.
     *
     * Not an id for the sink: a surface is built fresh every time the watch command is
     * typed, so two sinks with the same [observer] are two surfaces onto one operator.
     * That is the difference the observatory needs, and comparing sink instances instead
     * made re-running the command look like somebody else taking the watch over - the
     * operator was told "no longer watching" about the watch they had just started, and
     * their own display was torn down under them.
     *
     * A string rather than a session or a player, so this package stays engine-free: what
     * identifies an observer is the adapter's business, and only equality is asked of it.
     */
    val observer: String

    /**
     * True while there is still somebody there to show anything to.
     *
     * An observer can leave without saying so - a player disconnects - so the
     * observatory checks this rather than assuming a watch outlives the watcher.
     */
    val isPresent: Boolean

    /** Replaces the standing one-line status. */
    fun show(overlay: Overlay)

    /** Adds one line about something that just happened. */
    fun note(note: Note)

    /**
     * Says that nothing more will be shown here, so the display can be taken down.
     *
     * Not optional, and not the same as the observer leaving. A standing overlay is
     * standing: a surface that keeps showing a line until told otherwise would leave the
     * last thing it heard about a no-longer-watched agent on somebody's screen for the
     * rest of the session. Called exactly once per watch, and safe to call twice.
     */
    fun release()
}
