/*
 * Bounded server-event evidence for native player world interactions.
 */
package dev.loom.plugin

import dev.loom.body.REQUEST_REFS_LIFETIME_TICKS
import dev.loom.body.player.MAX_CONFIGURED_AGENTS
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBedLeaveEvent
import java.util.ArrayDeque
import java.util.UUID

private const val MAX_EVENTS_PER_PLAYER = 64
private const val EVENT_RETENTION_TICKS = REQUEST_REFS_LIFETIME_TICKS

internal data class BlockPoint(
    val x: Int,
    val y: Int,
    val z: Int,
)

internal sealed interface VanillaInteractionEvent {
    val player: UUID
    val tick: Long

    data class Broke(
        override val player: UUID,
        override val tick: Long,
        val block: BlockPoint,
        val what: String,
    ) : VanillaInteractionEvent

    data class Placed(
        override val player: UUID,
        override val tick: Long,
        val block: BlockPoint,
        val what: String,
    ) : VanillaInteractionEvent

    data class Collected(
        override val player: UUID,
        override val tick: Long,
        val itemEntity: UUID,
        val what: String,
        val stackCount: Int,
        val count: Int,
    ) : VanillaInteractionEvent

    data class Crafted(
        override val player: UUID,
        override val tick: Long,
        val what: String,
        val count: Int,
    ) : VanillaInteractionEvent

    data class Damaged(
        override val player: UUID,
        override val tick: Long,
        val target: UUID,
        val targetWhat: String,
        val weapon: String,
        val damage: Double,
    ) : VanillaInteractionEvent

    data class Killed(
        override val player: UUID,
        override val tick: Long,
        val target: UUID,
        val targetWhat: String,
    ) : VanillaInteractionEvent

    data class BedEntered(
        override val player: UUID,
        override val tick: Long,
        val bed: BlockPoint,
        val otherHalf: BlockPoint?,
        val what: String,
        val result: PlayerBedEnterEvent.BedEnterResult,
    ) : VanillaInteractionEvent

    data class BedLeft(
        override val player: UUID,
        override val tick: Long,
        val bed: BlockPoint,
        val otherHalf: BlockPoint?,
        val what: String,
    ) : VanillaInteractionEvent
}

/**
 * Records only the server events needed to corroborate an already-requested action.
 *
 * There is no policy or target selection here. Each player's queue is independently
 * capped, old entries expire by server tick, and an audit consumes the relevant event
 * kind from its request window so two requests can never claim one vanilla effect or
 * hide an effect on the wrong target.
 */
class BukkitInteractionEvents(
    private val server: Server,
    managedPlayers: Set<UUID>,
) : Listener {
    private val managedPlayers = managedPlayers.toSet()
    internal val evidence = BoundedInteractionEvidence(server)

    init {
        require(this.managedPlayers.size <= MAX_CONFIGURED_AGENTS) {
            "interaction evidence may track at most $MAX_CONFIGURED_AGENTS managed players"
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun broke(event: BlockBreakEvent) {
        if (event.player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.Broke(
                player = event.player.uniqueId,
                tick = server.currentTick.toLong(),
                block = event.block.point(),
                what = event.block.type.key.toString(),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun placed(event: BlockPlaceEvent) {
        if (event.player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.Placed(
                player = event.player.uniqueId,
                tick = server.currentTick.toLong(),
                block = event.blockPlaced.point(),
                what = event.blockPlaced.type.key.toString(),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun collected(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (player.uniqueId in managedPlayers) {
            val count = event.item.itemStack.amount - event.remaining
            if (count > 0) {
                evidence.remember(
                    VanillaInteractionEvent.Collected(
                        player = player.uniqueId,
                        tick = server.currentTick.toLong(),
                        itemEntity = event.item.uniqueId,
                        what = event.item.itemStack.type.key.toString(),
                        stackCount = event.item.itemStack.amount,
                        count = count,
                    ),
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun crafted(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId !in managedPlayers) return
        val result = event.recipe.result
        evidence.remember(
            VanillaInteractionEvent.Crafted(
                player = player.uniqueId,
                tick = server.currentTick.toLong(),
                what = result.type.key.toString(),
                count = result.amount,
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun damaged(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.Damaged(
                player = player.uniqueId,
                tick = server.currentTick.toLong(),
                target = event.entity.uniqueId,
                targetWhat = event.entity.type.key.toString(),
                weapon = player.inventory.itemInMainHand.type.key.toString(),
                damage = event.finalDamage,
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun killed(event: EntityDeathEvent) {
        val player = event.entity.killer ?: return
        if (player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.Killed(
                player = player.uniqueId,
                tick = server.currentTick.toLong(),
                target = event.entity.uniqueId,
                targetWhat = event.entity.type.key.toString(),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun enteredBed(event: PlayerBedEnterEvent) {
        if (event.player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.BedEntered(
                player = event.player.uniqueId,
                tick = server.currentTick.toLong(),
                bed = event.bed.point(),
                otherHalf = event.bed.otherBedHalf(),
                what = event.bed.type.key.toString(),
                result = event.bedEnterResult,
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun leftBed(event: PlayerBedLeaveEvent) {
        if (event.player.uniqueId !in managedPlayers) return
        evidence.remember(
            VanillaInteractionEvent.BedLeft(
                player = event.player.uniqueId,
                tick = server.currentTick.toLong(),
                bed = event.bed.point(),
                otherHalf = event.bed.otherBedHalf(),
                what = event.bed.type.key.toString(),
            ),
        )
    }
}

internal class BoundedInteractionEvidence(
    server: Server,
) {
    private val journal = BoundedInteractionEventJournal(server)

    fun remember(event: VanillaInteractionEvent) {
        journal.remember(event)
    }

    fun takeBreak(
        player: UUID,
        sinceTick: Long,
        block: BlockPoint,
    ): VanillaInteractionEvent.Broke? =
        journal.take(player, sinceTick) {
            it is VanillaInteractionEvent.Broke &&
                it.block == block
        } as? VanillaInteractionEvent.Broke

    fun takePlacement(
        player: UUID,
        sinceTick: Long,
        block: BlockPoint,
    ): VanillaInteractionEvent.Placed? =
        journal.take(player, sinceTick) {
            it is VanillaInteractionEvent.Placed &&
                it.block == block
        } as? VanillaInteractionEvent.Placed

    fun takeCollection(
        player: UUID,
        sinceTick: Long,
        itemEntity: UUID,
    ): VanillaInteractionEvent.Collected? =
        journal.take(player, sinceTick) {
            it is VanillaInteractionEvent.Collected &&
                it.itemEntity == itemEntity
        } as? VanillaInteractionEvent.Collected

    fun takeCrafts(
        player: UUID,
        sinceTick: Long,
    ): List<VanillaInteractionEvent.Crafted> =
        journal.takeAll(player, sinceTick) {
            it is VanillaInteractionEvent.Crafted
        }.filterIsInstance<VanillaInteractionEvent.Crafted>()

    fun takeDamage(
        player: UUID,
        sinceTick: Long,
    ): List<VanillaInteractionEvent.Damaged> =
        journal.takeAll(player, sinceTick) {
            it is VanillaInteractionEvent.Damaged
        }.filterIsInstance<VanillaInteractionEvent.Damaged>()

    fun takeKills(
        player: UUID,
        sinceTick: Long,
    ): List<VanillaInteractionEvent.Killed> =
        journal.takeAll(player, sinceTick) {
            it is VanillaInteractionEvent.Killed
        }.filterIsInstance<VanillaInteractionEvent.Killed>()

    fun takeBedEntries(
        player: UUID,
        sinceTick: Long,
    ): List<VanillaInteractionEvent.BedEntered> =
        journal.takeAll(player, sinceTick) {
            it is VanillaInteractionEvent.BedEntered
        }.filterIsInstance<VanillaInteractionEvent.BedEntered>()

    fun takeBedLeaves(
        player: UUID,
        sinceTick: Long,
    ): List<VanillaInteractionEvent.BedLeft> =
        journal.takeAll(player, sinceTick) {
            it is VanillaInteractionEvent.BedLeft
        }.filterIsInstance<VanillaInteractionEvent.BedLeft>()
}

private class BoundedInteractionEventJournal(
    private val server: Server,
) {
    private val recent = mutableMapOf<UUID, ArrayDeque<VanillaInteractionEvent>>()

    fun remember(event: VanillaInteractionEvent) {
        val queue = recent.getOrPut(event.player) { ArrayDeque() }
        prune(queue, event.tick)
        while (queue.size >= MAX_EVENTS_PER_PLAYER) queue.removeFirst()
        queue.addLast(event)
    }

    fun take(
        player: UUID,
        sinceTick: Long,
        matches: (VanillaInteractionEvent) -> Boolean,
    ): VanillaInteractionEvent? {
        val queue = recent[player]
        val found =
            queue?.let {
                prune(it, server.currentTick.toLong())
                it.firstOrNull { event -> event.tick >= sinceTick && matches(event) }
            }
        if (found != null) {
            queue.remove(found)
        }
        if (queue?.isEmpty() == true) recent.remove(player)
        return found
    }

    fun takeAll(
        player: UUID,
        sinceTick: Long,
        matches: (VanillaInteractionEvent) -> Boolean,
    ): List<VanillaInteractionEvent> {
        val queue = recent[player] ?: return emptyList()
        prune(queue, server.currentTick.toLong())
        val found = queue.filter { it.tick >= sinceTick && matches(it) }
        found.forEach(queue::remove)
        if (queue.isEmpty()) recent.remove(player)
        return found
    }

    private fun prune(
        queue: ArrayDeque<VanillaInteractionEvent>,
        now: Long,
    ) {
        while (queue.isNotEmpty() && now - queue.first.tick > EVENT_RETENTION_TICKS) {
            queue.removeFirst()
        }
    }
}

private fun Block.point(): BlockPoint = BlockPoint(x, y, z)

/**
 * The other half of this exact server bed, if Paper can still verify the pair.
 *
 * Bukkit may report either half on the enter and leave events even when the client
 * activated the other. The pair is captured at event time, including its material,
 * facing, and complementary part, so a neighboring bed or a later replacement cannot
 * satisfy evidence for the offered ref.
 */
private fun Block.otherBedHalf(): BlockPoint? {
    val bed = blockData as? Bed
    return if (bed == null) {
        null
    } else {
        val towardOther =
            when (bed.part) {
                Bed.Part.FOOT -> bed.facing
                Bed.Part.HEAD -> bed.facing.oppositeFace
            }
        val other = getRelative(towardOther)
        val otherBed = other.blockData as? Bed
        val complementary =
            when (bed.part) {
                Bed.Part.FOOT -> Bed.Part.HEAD
                Bed.Part.HEAD -> Bed.Part.FOOT
            }
        otherBed?.let {
            other
                .takeIf {
                    it.type == type &&
                        otherBed.part == complementary &&
                        otherBed.facing == bed.facing
                }?.point()
        }
    }
}
