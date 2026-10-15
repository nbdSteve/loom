/*
 * The server's chat event, translated into a speech observation.
 *
 * Paper fires `AsyncChatEvent` off the main thread, so this reads only the event's own
 * immutable sender identity and exact signed plain text there. The speaker's place, the
 * listeners and every witness mutation are read on the main thread. Only managed uuids are
 * forwarded; a stranger's chat is never read further.
 */
package dev.loom.witness.bukkit

import dev.loom.witness.ObservedSpeech
import dev.loom.witness.SpeechWitness
import dev.loom.witness.SpokenPlace
import dev.loom.witness.WitnessRegistry
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Forwards managed players' chat to [witness] on the main thread. */
internal class SpeechListener(
    private val registry: WitnessRegistry,
    private val witness: SpeechWitness,
    private val main: (() -> Unit) -> Unit,
    private val playerOf: (UUID) -> Player?,
    private val currentTick: () -> Long,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        // The exact string the client sent, before any plugin decoration.
        chatted(event.player.uniqueId, event.player.name, event.signedMessage().message())
    }

    /** Async-safe: touches no world state; place and witness are read inside [main]. */
    fun chatted(
        uuid: UUID,
        username: String,
        text: String,
    ) {
        if (registry.byUuid(uuid) == null) return
        main {
            witness.observed(ObservedSpeech(uuid, username, text, livePlaceOf(playerOf(uuid)), currentTick()))
        }
    }
}

/** Where a managed player is now, or null when it is offline or not alive. */
fun livePlaceOf(player: Player?): SpokenPlace? =
    player?.takeIf { it.isOnline && !it.isDead }?.location?.let { SpokenPlace(it.world.name, it.x, it.y, it.z) }

/** Registers the speech seam. The only way the wiring names this adapter. */
fun speechWitnessSeam(
    registry: WitnessRegistry,
    witness: SpeechWitness,
    plugin: Plugin,
): Listener =
    SpeechListener(
        registry,
        witness,
        main = { task -> Bukkit.getScheduler().runTask(plugin, Runnable { task() }) },
        playerOf = Bukkit::getPlayer,
        currentTick = { Bukkit.getCurrentTick().toLong() },
    )
