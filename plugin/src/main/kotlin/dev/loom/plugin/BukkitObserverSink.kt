/*
 * The one surface an operator actually watches: a boss bar and chat, for one sender.
 *
 * The engine half of `ObserverSink`, and it lives here for the same reason
 * `BukkitMainThread` does - `dev.loom.observe` is engine-free by rule, so the package
 * that knows what a Bukkit sender is has to be this one.
 *
 * WHY A BOSS BAR RATHER THAN THE ACTION BAR. An action bar fades after a few seconds, so
 * a standing status line on one would need repainting on a timer, and a repaint rate that
 * drifted out of step with the fade would leave an operator looking at nothing. A boss bar
 * stays until it is taken down, which is exactly the lifetime `Overlay` describes, and its
 * bar is a real bar: `Overlay.progress` moves it, so energy decline and recovery are
 * visible without reading the number beside them. Taking it down again is why
 * `ObserverSink.release` exists.
 *
 * NOTHING HERE IS VISIBLE TO AN AGENT. A boss bar and a chat message are sent to one
 * client; neither is world state, and no body can perceive either. The one way a human
 * watching *can* perturb a run is by standing next to the agent, because a body perceives
 * the living entities around it and a player is one - see DEMO.md.
 */
package dev.loom.plugin

import dev.loom.observe.Note
import dev.loom.observe.ObserverLevel
import dev.loom.observe.ObserverSink
import dev.loom.observe.Overlay
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Marks Loom's own lines in a chat window that has other things in it. */
private val NOTE_PREFIX = Component.text("[loom] ", NamedTextColor.DARK_GRAY)

/**
 * Shows one operator's view of one agent to [sender].
 *
 * Every method must be called on the server main thread, which is where
 * `Observatory.refresh` runs it from.
 *
 * A sender with a screen gets the standing line as a boss bar; the console gets it as an
 * ordinary message, which is why it is only ever pushed on change. Notes go to chat either
 * way.
 */
class BukkitObserverSink(
    private val sender: CommandSender,
) : ObserverSink {
    private val player = sender as? Player
    private var gauge: BossBar? = null

    /**
     * Who this surface belongs to: a player's account, or the console.
     *
     * The account and not the connection, and deliberately not this object: the watch
     * command builds a new sink every time it is typed, so this is the only thing that can
     * tell "the same operator asking again" from "somebody else taking the watch over".
     * A player's uuid rather than their name because a name can be reused and a uuid
     * cannot, and because that is the id the rest of the server means by a player.
     */
    override val observer: String = player?.uniqueId?.toString() ?: "console:${sender.name}"

    /**
     * True while the sender is still there to be shown anything.
     *
     * The console never leaves, so a console watch ends only when it is stopped.
     */
    override val isPresent: Boolean get() = player?.isOnline ?: true

    override fun show(overlay: Overlay) {
        val line = Component.text(overlay.text, textColourOf(overlay.level))
        val target = player
        if (target == null) {
            sender.sendMessage(line)
        } else {
            barFor(target).apply {
                name(line)
                progress(progressOf(overlay))
                color(barColourOf(overlay.level))
            }
        }
    }

    override fun note(note: Note) {
        sender.sendMessage(NOTE_PREFIX.append(Component.text(note.text, textColourOf(note.level))))
    }

    override fun release() {
        gauge?.let { bar -> player?.hideBossBar(bar) }
        gauge = null
    }

    /**
     * The boss bar for [target], created and shown on first use.
     *
     * Created empty and immediately filled in by the caller, so there is one place that
     * decides what the bar says and it is not this one.
     */
    private fun barFor(target: Player): BossBar =
        gauge ?: BossBar
            .bossBar(Component.empty(), BossBar.MIN_PROGRESS, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
            .also {
                gauge = it
                target.showBossBar(it)
            }

    private companion object {
        /**
         * How full the bar is drawn.
         *
         * An agent whose energy has not been read yet gets an empty bar rather than a full
         * one: the line beside it says so, and guessing full would show a dying agent as
         * healthy for as long as the guess lasted.
         */
        fun progressOf(overlay: Overlay): Float =
            overlay.progress?.toFloat()?.coerceIn(BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS) ?: BossBar.MIN_PROGRESS

        /** The bar's colour, so how alarming the line is registers before it is read. */
        fun barColourOf(level: ObserverLevel): BossBar.Color =
            when (level) {
                ObserverLevel.CALM -> BossBar.Color.GREEN
                ObserverLevel.CONCERN -> BossBar.Color.YELLOW
                ObserverLevel.ALARM -> BossBar.Color.RED
            }

        /** The same three levels in text, for the title and for chat. */
        fun textColourOf(level: ObserverLevel): TextColor =
            when (level) {
                ObserverLevel.CALM -> NamedTextColor.WHITE
                ObserverLevel.CONCERN -> NamedTextColor.YELLOW
                ObserverLevel.ALARM -> NamedTextColor.RED
            }
    }
}
