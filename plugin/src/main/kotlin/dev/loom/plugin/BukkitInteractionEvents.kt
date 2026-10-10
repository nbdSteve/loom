/*
 * Bounded server-event evidence for native player world interactions.
 */
package dev.loom.plugin

import dev.loom.body.player.MAX_CONFIGURED_AGENTS
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import java.util.ArrayDeque
import java.util.UUID

private const val MAX_EVENTS_PER_PLAYER = 64
private const val EVENT_RETENTION_TICKS = 400L

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
}

/**
 * Records only the server events needed to corroborate an already-requested action.
 *
 * There is no policy or target selection here. Each player's queue is independently
 * capped, old entries expire by server tick, and a matching event is consumed by one
 * completed control request so two requests can never claim one vanilla effect.
 */
class BukkitInteractionEvents(
    private val server: Server,
    managedPlayers: Set<UUID>,
) : Listener {
    private val managedPlayers = managedPlayers.toSet()
    private val recent = mutableMapOf<UUID, ArrayDeque<VanillaInteractionEvent>>()

    init {
        require(this.managedPlayers.size <= MAX_CONFIGURED_AGENTS) {
            "interaction evidence may track at most $MAX_CONFIGURED_AGENTS managed players"
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun broke(event: BlockBreakEvent) {
        if (event.player.uniqueId !in managedPlayers) return
        remember(
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
        remember(
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
                remember(
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

    internal fun takeBreak(
        player: UUID,
        sinceTick: Long,
        block: BlockPoint,
    ): VanillaInteractionEvent.Broke? =
        take(player, sinceTick) {
            it is VanillaInteractionEvent.Broke &&
                it.block == block
        } as? VanillaInteractionEvent.Broke

    internal fun takePlacement(
        player: UUID,
        sinceTick: Long,
        block: BlockPoint,
    ): VanillaInteractionEvent.Placed? =
        take(player, sinceTick) {
            it is VanillaInteractionEvent.Placed &&
                it.block == block
        } as? VanillaInteractionEvent.Placed

    internal fun takeCollection(
        player: UUID,
        sinceTick: Long,
        itemEntity: UUID,
    ): VanillaInteractionEvent.Collected? =
        take(player, sinceTick) {
            it is VanillaInteractionEvent.Collected &&
                it.itemEntity == itemEntity
        } as? VanillaInteractionEvent.Collected

    private fun remember(event: VanillaInteractionEvent) {
        val queue = recent.getOrPut(event.player) { ArrayDeque() }
        prune(queue, event.tick)
        while (queue.size >= MAX_EVENTS_PER_PLAYER) queue.removeFirst()
        queue.addLast(event)
    }

    private fun take(
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
