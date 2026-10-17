/*
 * The Bukkit record of what each managed player's own digs dropped, and what became of it.
 */
package dev.loom.plugin

import dev.loom.body.player.MAX_CONFIGURED_AGENTS
import dev.loom.body.player.OwnDropReading
import dev.loom.body.player.OwnDrops
import dev.loom.body.player.PlayerIdentity
import dev.loom.contract.DropFate
import dev.loom.contract.MAX_OWN_DROPS
import org.bukkit.Server
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import java.util.ArrayDeque
import java.util.UUID

/** Drops waiting for their dig to be attributed, per player; the oldest is forgotten past it. */
private const val MAX_PENDING_DROPS = 32

/**
 * How long a drop waits to be attributed, and how long an attributed one is told: two goal
 * review cadences, so a review after the one following the dig still hears of it.
 */
internal const val OWN_DROP_RETENTION_TICKS: Long = 4800

/**
 * Listens to Paper's own `BlockDropItemEvent` for the configured fleet, so a drop is known by
 * the item entities the server spawned for one player's break of one block, never by what lies
 * near it, and follows each of them through pickup, merge and despawn. The bookkeeping is
 * [OwnDropLedger]'s; this class only reads Bukkit events into it.
 */
class BukkitOwnDrops(
    private val server: Server,
    managedPlayers: Set<UUID>,
) : Listener,
    OwnDrops {
    private val managedPlayers = managedPlayers.toSet()
    internal val ledger =
        OwnDropLedger({ server.currentTick.toLong() }) { entity ->
            (server.getEntity(entity) as? Item)
                ?.takeIf { it.isValid && !it.isDead }
                ?.location
                ?.let { listOf(it.x, it.y, it.z) }
        }

    init {
        require(this.managedPlayers.size <= MAX_CONFIGURED_AGENTS) {
            "own drops may track at most $MAX_CONFIGURED_AGENTS managed players"
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun dropped(event: BlockDropItemEvent) {
        val owner = event.player.uniqueId
        if (owner !in managedPlayers) return
        val block = listOf(event.block.x, event.block.y, event.block.z)
        for (item in event.items) {
            val stack = item.itemStack
            if (!stack.type.isAir && stack.amount > 0) {
                ledger.dropped(owner, item.uniqueId, itemNameOf(item), stack.amount.toLong(), block)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun pickedUp(event: EntityPickupItemEvent) {
        if (event.remaining == 0) ledger.pickedUp(event.item.uniqueId, (event.entity as? Player)?.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun merged(event: ItemMergeEvent) {
        ledger.merged(event.entity.uniqueId, event.target.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun despawned(event: ItemDespawnEvent) {
        ledger.gone(event.entity.uniqueId)
    }

    override fun attribute(
        identity: PlayerIdentity,
        requestId: String,
        block: List<Int>,
        sinceTick: Long,
    ): List<OwnDropReading> = ledger.attribute(identity.uuid, requestId, block, sinceTick)

    override fun of(identity: PlayerIdentity): List<OwnDropReading> = ledger.of(identity.uuid)
}

/**
 * Each player's own dig drops, engine-free: what the server dropped for one break of one block
 * waits, bounded in count and server ticks, until that dig's audit attributes it to the dig's
 * request; from then on it is told to its digger, newest first and at most [MAX_OWN_DROPS], with
 * what became of it. [locate] answers where an item entity lies while the server still has it.
 */
internal class OwnDropLedger(
    private val currentTick: () -> Long,
    private val locate: (UUID) -> List<Double>?,
) {
    private class Drop(
        val owner: UUID,
        var entity: UUID,
        val what: String,
        val count: Long,
        val block: List<Int>,
        val tick: Long,
    ) {
        var requestId: String? = null
        var fate: DropFate = DropFate.LYING
    }

    private val pending = mutableMapOf<UUID, ArrayDeque<Drop>>()
    private val attributed = mutableMapOf<UUID, ArrayDeque<Drop>>()

    fun dropped(
        owner: UUID,
        entity: UUID,
        what: String,
        count: Long,
        block: List<Int>,
    ) {
        val queue = pending.getOrPut(owner) { ArrayDeque() }
        while (queue.size >= MAX_PENDING_DROPS) queue.removeFirst()
        queue.addLast(Drop(owner, entity, what, count, block, currentTick()))
    }

    /** A whole stack was picked up, by [picker] when a player did. */
    fun pickedUp(
        entity: UUID,
        picker: UUID?,
    ) {
        val drop = tracked(entity) ?: return
        drop.fate =
            when (picker) {
                drop.owner -> DropFate.COLLECTED
                null -> DropFate.GONE
                else -> DropFate.TAKEN
            }
    }

    /** The stack lives on inside the one it merged into, so that is what is followed. */
    fun merged(
        entity: UUID,
        into: UUID,
    ) {
        tracked(entity)?.entity = into
    }

    fun gone(entity: UUID) {
        tracked(entity)?.fate = DropFate.GONE
    }

    fun attribute(
        owner: UUID,
        requestId: String,
        block: List<Int>,
        sinceTick: Long,
    ): List<OwnDropReading> {
        val waiting = pending[owner] ?: return emptyList()
        prune(waiting)
        val ours = waiting.filter { it.block == block && it.tick >= sinceTick }
        waiting.removeAll(ours.toSet())
        val told = attributed.getOrPut(owner) { ArrayDeque() }
        for (drop in ours) {
            drop.requestId = requestId
            told.addFirst(drop)
            while (told.size > MAX_OWN_DROPS) told.removeLast()
        }
        return ours.map(::readingOf)
    }

    fun of(owner: UUID): List<OwnDropReading> {
        val told = attributed[owner] ?: return emptyList()
        prune(told)
        return told.map(::readingOf)
    }

    private fun tracked(entity: UUID): Drop? =
        (pending.values.asSequence() + attributed.values.asSequence())
            .flatMap { it.asSequence() }
            .firstOrNull { it.entity == entity && it.fate == DropFate.LYING }

    private fun readingOf(drop: Drop): OwnDropReading {
        val place = if (drop.fate == DropFate.LYING) locate(drop.entity) else null
        // An entity the server no longer has, with no event saying where it went, is gone.
        if (drop.fate == DropFate.LYING && place == null) drop.fate = DropFate.GONE
        val entity = drop.entity.takeIf { drop.fate == DropFate.LYING }
        return OwnDropReading(requireNotNull(drop.requestId), drop.what, drop.count, drop.fate, place, entity)
    }

    private fun prune(queue: ArrayDeque<Drop>) {
        val now = currentTick()
        queue.removeAll { now - it.tick > OWN_DROP_RETENTION_TICKS }
    }
}

/** The item a dropped entity holds, as an inventory stack names it. */
private fun itemNameOf(item: Item): String {
    val key = item.itemStack.type.key
    return "item.${key.namespace}.${key.key.replace('/', '.')}"
}
