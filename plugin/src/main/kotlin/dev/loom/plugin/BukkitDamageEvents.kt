/*
 * The server-authoritative producer of `damaged` triggers.
 *
 * Listens at MONITOR with `ignoreCancelled`, so it reads the final damage after every
 * other plugin has modified or cancelled the event and never changes it. The managed
 * agent is resolved from the server's own uuid alone; nothing a client says is read.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.WitnessedPlayer
import dev.loom.mind.DamageReport
import dev.loom.mind.sanitizedDamageSource
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import java.util.UUID

/**
 * Hands each final positive damage to a managed player to [sink].
 *
 * @param agentOf the witness registry's uuid lookup; `null` for anybody Loom does not manage.
 */
class BukkitDamageEvents(
    private val agentOf: (UUID) -> AgentId?,
    private val currentTick: () -> Long,
    private val sink: (DamageReport) -> Unit,
) : Listener,
    AutoCloseable {
    @Volatile
    private var closed = false

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun damaged(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val player = event.entity as? Player ?: return
        val attacker = (event as? EntityDamageByEntityEvent)?.damager
        witnessed(
            WitnessedPlayer(player.uniqueId.toString(), player.name),
            WitnessedHit(
                finalDamage = event.finalDamage,
                source = damageSourceOf(event.cause, attacker?.let(::attackerType)),
                playerDead = player.isDead,
                attacker = attacker?.let(::attackerOf)?.uniqueId?.toString(),
                // At MONITOR the hit is not yet applied; its final damage is already net of armour,
                // resistance and absorption, so what is left is the health less it.
                healthBefore = player.health,
            ),
        )
    }

    internal fun witnessed(
        player: WitnessedPlayer,
        hit: WitnessedHit,
    ) {
        if (closed || !(hit.finalDamage > 0.0)) return
        val agent = agentOf(UUID.fromString(player.uuid)) ?: return
        sink(
            DamageReport(
                agentId = agent,
                finalDamage = hit.finalDamage,
                source = hit.source,
                tick = currentTick(),
                witness = player,
                playerDead = hit.playerDead,
                attacker = hit.attacker,
                healthBefore = hit.healthBefore,
                healthAfter = hit.healthBefore?.let { (it - hit.finalDamage).coerceAtLeast(0.0) },
            ),
        )
    }

    /** Unregisters from every handler list; no report is produced afterwards. */
    override fun close() {
        closed = true
        HandlerList.unregisterAll(this)
    }
}

/** What the server witnessed of one hit on a player, before it was applied. */
internal data class WitnessedHit(
    val finalDamage: Double,
    val source: String,
    val playerDead: Boolean,
    val attacker: String? = null,
    val healthBefore: Double? = null,
)

/** A projectile's shooter when it is an entity, otherwise the damager itself. */
private fun attackerOf(damager: Entity): Entity = (damager as? Projectile)?.shooter as? Entity ?: damager

private fun attackerType(damager: Entity): EntityType = attackerOf(damager).type

/**
 * The stable source token: the attacker's vanilla entity key, else the damage cause.
 *
 * Never a name, uuid, death message or custom name, which a player could choose.
 */
internal fun damageSourceOf(
    cause: EntityDamageEvent.DamageCause,
    attacker: EntityType?,
): String {
    val entityKey =
        attacker
            ?.takeIf { it != EntityType.UNKNOWN }
            ?.let { runCatching { it.key.key }.getOrNull() }
    return sanitizedDamageSource(entityKey ?: cause.name.lowercase())
}
