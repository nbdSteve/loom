/*
 * The one place the witness meets a running server.
 *
 * Everything here is translation: a Bukkit event becomes a question for `PlayerWitness`, and the
 * verdict that comes back becomes at most one act. No decision is taken in this file - not which
 * players are Loom's, not whether a death is the first, not whether a login may be admitted - so
 * a Minecraft version bump can change every one of these five events without any of the
 * permadeath rules moving with it.
 *
 * Loom deliberately rebuilds none of the mechanics these events report. A death is whatever the
 * server calls a death, a disconnect is whatever the server calls a disconnect, and the cause of
 * death is the server's own message rather than an inference from damage events. That is the
 * whole reason Paper is the authoritative witness in the player-body design: it is the one
 * participant that cannot be lied to by the process driving the account.
 *
 * Four choices here are load-bearing and none is guessable from the code.
 *
 * A login is judged at `EventPriority.HIGHEST`, which is the last priority before MONITOR, so a
 * refusal Loom issues is the one that survives. It is deliberately not conditional on whether
 * something else has already refused the connection: Loom's own verdict is recorded either way,
 * because "did Loom turn this dead agent away" must not depend on which other plugins are
 * installed, and its notice is the one that matters to whoever is looking at the client.
 *
 * A death is judged at `EventPriority.MONITOR`, after every other plugin has had its say about
 * the death message, since that message is what the record quotes as the cause.
 *
 * **A respawn is only evidence of a death when the server says the death is what caused it.**
 * `PlayerRespawnEvent` is not the after-a-death event its name suggests: `RespawnReason.END_PORTAL`
 * fires it for a perfectly *live* player stepping into the End's exit portal, because vanilla sends
 * that player to its respawn position rather than through an ordinary portal, and
 * `RespawnReason.PLUGIN` fires it for anything that respawns a player itself. Read as a death,
 * an agent walking home from the End was recorded dead, tombstoned synchronously and kicked for
 * good - a false permadeath, and an irreversible one. So [respawnCauseOf] translates the reason and
 * nothing more: a dimension return is continuity for a living agent, and what that continuity
 * *means* is the witness's answer rather than this file's. What remains true of the event is that
 * it cannot be cancelled, which is
 * why the handler is a failsafe rather than a gate.
 *
 * Note that the reason alone is not the whole rule, which is why the decision is not here. A
 * respawn with no death behind it is still a breach when the agent is *already* tombstoned -
 * something else on the server has put a dead agent back in the world - so the witness records that
 * one and stays silent for a live agent. Both halves need the durable death state, which this file
 * deliberately cannot see.
 *
 * And a removal happens on the *next* tick rather than inside the event. Paper fires
 * `PlayerDeathEvent` from the middle of `ServerPlayer.die`, which then goes on to broadcast the
 * message, drop the inventory and set health, and disconnecting a player from inside that is a
 * long-standing way to leave the server holding a half-dead player. The cost of waiting a tick is
 * that a client can ask to respawn in the gap, which is exactly what the respawn handler below is
 * for.
 */
package dev.loom.witness.bukkit

import dev.loom.witness.LoginVerdict
import dev.loom.witness.PlayerWitness
import dev.loom.witness.RespawnCause
import dev.loom.witness.WitnessAction
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/** What the record says when the server offered no death message at all. */
private const val NO_DEATH_MESSAGE = "died"

/**
 * Turns the five player lifecycle events into witness statements.
 *
 * @param witness the whole of the judgement. This class adds none of its own.
 * @param remove how a player is taken out of the world. A parameter so the scheduling - which is
 *   the one thing here that must not happen inline - is stated once, beside the reason.
 */
internal class WitnessListener(
    private val witness: PlayerWitness,
    private val remove: (Player, String) -> Unit,
) : Listener {
    /**
     * Refuses a login the witness will not admit.
     *
     * Runs off the main thread, which is what async pre-login is for: the durable death state has
     * already been replayed by the time any listener can run, so this needs to read no file and
     * take no lock the main thread holds.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        when (val verdict = witness.admitLogin(event.uniqueId, event.name)) {
            LoginVerdict.Unmanaged, is LoginVerdict.Admitted -> Unit
            is LoginVerdict.Denied ->
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text(verdict.notice),
                )
        }
    }

    /** Records a managed arrival, and removes a player that should not have got in. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        act(player, witness.sawJoin(player.uniqueId, player.name, player.world.name))
    }

    /** Records a managed departure, when the witness judges it to be news. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        witness.sawQuit(player.uniqueId, player.name)
    }

    /** Records the one authoritative death, and removes the player without a respawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        // The server's own account of the death, flattened to text. Read here rather than
        // reconstructed from damage, so it names whatever actually happened - including a cause
        // Loom knows nothing about.
        val cause =
            event.deathMessage()?.let { PlainTextComponentSerializer.plainText().serialize(it) }
                ?.takeIf { it.isNotBlank() }
                ?: NO_DEATH_MESSAGE
        act(player, witness.sawDeath(player.uniqueId, player.name, cause))
    }

    /**
     * Catches a respawn that should have been impossible, and passes the rest through.
     *
     * `PlayerRespawnEvent` cannot be cancelled, so this is not a gate: wherever it is a breach, the
     * server has already put a live player back in the world. The reason is translated by
     * [respawnCauseOf] and the meaning is the witness's to decide, because a live player leaving the
     * End arrives here too and is not a death at all.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        act(player, respawnStatement(witness, event.respawnReason, player.uniqueId, player.name))
    }

    private fun act(
        player: Player,
        action: WitnessAction,
    ) {
        when (action) {
            WitnessAction.LeaveAlone -> Unit
            is WitnessAction.Remove -> remove(player, action.notice)
        }
    }
}

/**
 * As much of [reason] as the witness needs, which is only whether a death is behind it.
 *
 * Translation and nothing else: what a respawn *means* for an agent is the witness's to decide, and
 * this is the one line that has to know the server's own vocabulary. Total over the enum with no
 * `else`, so a reason Paper adds later cannot default into being read as a death - that default is
 * exactly how this went wrong, and the cost of getting it wrong was an irreversible false
 * permadeath rather than a missed record.
 *
 * `DEATH` is the only reason that reports a death. `END_PORTAL` is a live player leaving the End,
 * which vanilla implements by sending it to its respawn position, and `PLUGIN` is something else on
 * the server respawning a player for its own reasons.
 */
internal fun respawnCauseOf(reason: PlayerRespawnEvent.RespawnReason): RespawnCause =
    when (reason) {
        PlayerRespawnEvent.RespawnReason.DEATH -> RespawnCause.AFTER_DEATH
        PlayerRespawnEvent.RespawnReason.END_PORTAL,
        PlayerRespawnEvent.RespawnReason.PLUGIN,
        -> RespawnCause.WITHOUT_DEATH
    }

/**
 * What the witness says about a respawn of [reason] for the player behind [uuid] and [username].
 *
 * Exists so the translation and the question can be driven together without a `Player` to build an
 * event around, which is the only way this path is provable at a desk. Every reason reaches the
 * witness; whether it means anything is `PlayerWitness.sawRespawn`'s answer, not this function's.
 */
internal fun respawnStatement(
    witness: PlayerWitness,
    reason: PlayerRespawnEvent.RespawnReason,
    uuid: UUID,
    username: String,
): WitnessAction = witness.sawRespawn(uuid, username, respawnCauseOf(reason))

/**
 * Removes a player on the tick after the one that asked for it.
 *
 * Checks the player is still connected first: the ordinary case is a death whose player is about
 * to be kicked, and a client that disconnected in the intervening tick has already gone.
 */
internal fun nextTickRemoval(plugin: Plugin): (Player, String) -> Unit =
    { player, notice ->
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                if (player.isOnline) {
                    player.kick(Component.text(notice))
                }
            },
        )
    }

/**
 * The witness seam, for the plugin's wiring to register.
 *
 * Returned as a bare [Listener] because that is all the wiring needs to know: which events a
 * server-authoritative witness has to hear, and why, is this package's business.
 */
fun playerWitnessSeam(
    witness: PlayerWitness,
    plugin: Plugin,
): Listener = WitnessListener(witness, nextTickRemoval(plugin))
