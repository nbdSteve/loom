/*
 * The operator's way in: `/loomwatch`, which attaches a read-only view to one agent.
 *
 * It is the read-only half of Loom's commands, and the reason `loom.observe` is a
 * permission of its own rather than another use of `loom.body`: nothing here spawns,
 * moves, perceives, spends a model call, or touches the world. All it can do is point a
 * display at the record Loom is already writing, which is why it is safe to hand to
 * somebody who is only meant to watch a run.
 *
 * Written against `Observatory` and `ObserverSink` alone - no adapter type and no NMS -
 * so `reply` is testable with a fake sink and no server at all.
 */
package dev.loom.plugin

import dev.loom.observe.Observatory
import dev.loom.observe.ObserverSink
import dev.loom.observe.WatchAttachment
import dev.loom.observe.WatchStanding
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/** The one subcommand that is a word rather than an agent id. */
private const val OFF = "off"

/**
 * Attaches, reports, and detaches the one operator watch.
 *
 * @param observatory the watch itself. Typed as [Observatory] because that is already the
 *   read-only end of the record stream; this class adds no other reach.
 */
class WatchCommand(
    private val observatory: Observatory,
) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val result = reply(args, BukkitObserverSink(sender))
        result?.let { sender.sendMessage(it) }
        return result != null
    }

    /**
     * The human-readable reply to [args], or `null` to let Bukkit print the usage.
     *
     * [sink] is where the watch would be shown if these arguments start one. It is built
     * before it is known to be needed because it is inert until something is shown on it:
     * a sink that is never watched with costs nothing and leaves nothing behind.
     */
    internal fun reply(
        args: Array<out String>,
        sink: ObserverSink,
    ): String? =
        when {
            args.isEmpty() -> watching(sink.observer)
            args.size > 1 -> null
            args.first() == OFF -> stop(sink.observer)
            else -> start(args.first(), sink)
        }

    /**
     * What the sender is told about the watch that is standing.
     *
     * Answered as the observer who typed it, so a sender who is not the watcher is told
     * that somebody is watching without being told which agent: the display shows a run,
     * and who is looking at it is not part of the run.
     */
    private fun watching(observer: String): String =
        when (val standing = observatory.standing(observer)) {
            is WatchStanding.Mine -> "watching ${standing.agentId.value}"
            WatchStanding.NotMine -> "somebody else is watching. /loomwatch <agentId> to take the watch over."
            WatchStanding.Nobody -> "nobody is being watched. /loomwatch <agentId> to start."
        }

    /**
     * Ends the sender's own watch, and says plainly when there was none to end.
     *
     * The reply is read from what the observatory actually did rather than assumed, because
     * the three cases are three different things to have happened: a refusal that reported
     * a stop would tell one operator they had ended a watch that is still standing on
     * somebody else's screen.
     */
    private fun stop(observer: String): String =
        when (val ended = observatory.stop(observer)) {
            is WatchStanding.Mine -> "stopped watching ${ended.agentId.value}"
            WatchStanding.NotMine -> "somebody else is watching. Only the operator who started a watch can stop it."
            WatchStanding.Nobody -> "nobody was being watched"
        }

    /**
     * Points [sink] at the agent named by [raw], and says which of two things happened.
     *
     * A watch that starts knowing nothing is told the one surprising thing about a read-only
     * display: a watcher is told nothing until the run records something, so an agent that is
     * standing still shows no energy until its next reading. Attaching before the spawn avoids
     * that entirely, since the spawn record carries the first reading.
     *
     * An operator whose own display already has something on it is told that instead, because
     * the same advice would be false there: the record has already stated what that bar is
     * showing, and an operator who retyped the command they were running needs to hear that
     * nothing was lost rather than to be told to attach earlier. A watch re-attached while the
     * bar is still empty is not that case and keeps the advice, since the next reading is still
     * what will fill it - true both before an agent's first record and while nothing recorded
     * about it has reached the line. So does taking a watch over from somebody else, word for
     * word as a first watch of the run is answered: that operator has lost nothing, and a reply
     * that read differently would tell them which agent the operator they displaced had been
     * watching. Which of the two it was is read from [Observatory.watch]'s own answer rather
     * than worked out here, so the continuity rule stays in the one place that can see it.
     */
    private fun start(
        raw: String,
        sink: ObserverSink,
    ): String =
        withAgent(raw) { agent ->
            when (observatory.watch(agent, sink)) {
                WatchAttachment.KEPT ->
                    "watching ${agent.value}, keeping what is already known about it: " +
                        "what the record has said carries over, so nothing was lost."
                WatchAttachment.FRESH ->
                    "watching ${agent.value}. Lines appear as the record does, " +
                        "so attach before spawning to catch the first energy reading."
            }
        }
}
