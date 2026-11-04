package dev.loom.plugin

import dev.loom.witness.DurablePermadeath
import dev.loom.witness.WitnessRegistry
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val OBSERVE_PERMISSION: String = "loom.observe"

/**
 * Admits only agents and observers to a closed run, and holds every observer to spectating.
 *
 * Spectator mode is set at `LOWEST` on join, which Paper fires before it tracks the new player or
 * tells any client about it, so no client ever sees an observer as anything else. Vanilla never
 * sends a spectator to a non-spectator client and never counts one for mob spawning; hiding it as
 * well keeps it out of every agent's player list.
 */
internal class BukkitSpectators(
    private val plugin: Plugin,
    private val server: Server,
    private val spectators: Spectators,
    private val managed: (UUID, String) -> Boolean,
    private val journal: ObserverJournal,
    private val currentTick: () -> Long,
) : Listener {
    private val observers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Below the witness's `HIGHEST`, so its verdict and record still decide every managed login. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!spectators.admits(event.name, managed(event.uniqueId, event.name))) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(Spectators.REFUSAL))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (spectators.isObserver(player.name, managed(player.uniqueId, player.name))) {
            spectate(player)
            event.joinMessage(null)
            journal.append(recordOf(OBSERVER_JOINED, player))
        } else {
            observers.forEach { uuid -> server.getPlayer(uuid)?.let { player.hidePlayer(plugin, it) } }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (observers.remove(player.uniqueId)) {
            event.quitMessage(null)
            journal.append(recordOf(OBSERVER_LEFT, player))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (event.player.uniqueId in observers && event.newGameMode != GameMode.SPECTATOR) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        if (event.player.uniqueId in observers) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (event.player.uniqueId in observers && !spectators.mayRun(event.message)) {
            event.isCancelled = true
            event.player.sendMessage(Component.text(Spectators.COMMAND_REFUSAL))
        }
    }

    private fun spectate(player: Player) {
        player.isOp = false
        player.gameMode = GameMode.SPECTATOR
        player.affectsSpawning = false
        player.addAttachment(plugin, OBSERVE_PERMISSION, true)
        observers += player.uniqueId
        server.onlinePlayers
            .filter { it.uniqueId !in observers }
            .forEach { it.hidePlayer(plugin, player) }
    }

    private fun recordOf(
        event: String,
        player: Player,
    ): ObserverRecord = ObserverRecord(event, currentTick(), player.name, player.uniqueId.toString())
}

/** Registered beside the witness, before any body can launch, so no login precedes closed admission. */
internal fun spectatorSeam(
    plugin: Plugin,
    registry: WitnessRegistry,
    permadeath: DurablePermadeath,
    logFile: Path,
): Listener {
    val spectators = Spectators.fromEnvironment(System.getenv())
    // A spectator then generates no chunk and ticks none.
    if (spectators.hasObservers) {
        plugin.server.worlds.forEach { it.setGameRule(GameRule.SPECTATORS_GENERATE_CHUNKS, false) }
    }
    return BukkitSpectators(
        plugin = plugin,
        server = plugin.server,
        spectators = spectators,
        managed = { uuid, username ->
            registry.byUuid(uuid) != null ||
                registry.byUsername(username) != null ||
                permadeath.deadIdentityFor(uuid, username) != null
        },
        journal = ObserverJournal(logFile) { plugin.logger.warning("observer log write failed: ${it.message}") },
        currentTick = { plugin.server.currentTick.toLong() },
    )
}
