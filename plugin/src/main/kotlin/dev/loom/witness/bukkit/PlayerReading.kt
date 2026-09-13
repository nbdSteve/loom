/*
 * Reading a managed player's vitals, inventory and position off the server itself.
 *
 * The whole engine half of the cross-check, and deliberately nothing more: it translates
 * and it does not compare, because comparing needs no server and belongs where a desk
 * test can reach it (`dev.loom.witness.Corroboration`).
 *
 * Two things it does not do, on purpose.
 *
 * It reads no bound and refuses nothing. A `SensedVitals` refuses a reading the protocol
 * may not carry, which is right for a *claim*; the server's reading is the truth, and a
 * witness that threw rather than reporting an unusual truth would have stopped
 * witnessing exactly where it matters. So this answers `ObservedVitals`, unbounded.
 *
 * It names an item with the game's own registry key rather than with a display name or a
 * table of Loom's, which is the same vocabulary the body reports and the same rule
 * `BodyEating.kt` follows for the villager flavour: a stack renamed on an anvil has to
 * compare equal to itself, and a mapping of Loom's would fall behind a version bump.
 */
package dev.loom.witness.bukkit

import dev.loom.witness.ObservedPosition
import dev.loom.witness.ObservedStack
import dev.loom.witness.ObservedVitals
import dev.loom.witness.ServerReading
import dev.loom.witness.ServerStanding
import dev.loom.witness.isCorroborableSlot
import dev.loom.witness.windowSlotOfBukkitSlot
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The server's own reading of [player]'s vitals and of what it is carrying.
 *
 * Vanilla's three separate facts stay three: health is damage to the body, food is the
 * hunger bar, and saturation is the hidden reserve behind it. Collapsing any pair of them
 * would make the cross-check unable to say which one a body got wrong.
 */
internal fun serverReadingOf(player: Player): ServerReading =
    ServerReading(
        vitals =
            ObservedVitals(
                health = player.health,
                food = player.foodLevel,
                saturation = player.saturation.toDouble(),
            ),
        inventory = heldStacksOf(player),
    )

/**
 * The server's own standing of a managed player, for a movement cross-check to read.
 *
 * Takes a nullable [Player] because that is what `Bukkit.getPlayer(uuid)` answers, and an absent
 * player is one of the three standings rather than a case every caller has to remember: a body
 * that quit mid-route leaves no position to compare, and the reading is what says so.
 *
 * A dead player is reported as dead rather than as its position, though the engine will happily
 * give one. That position is where the player fell, with a client on the death screen, so
 * comparing a movement report against it would dispute a journey that ended in a death - which
 * is a fact about the run rather than a body that lied.
 *
 * The location is read once. Bukkit answers a fresh snapshot per call, so three reads of one
 * player would be three positions and the record would name a place the player never was.
 */
internal fun playerStandingOf(player: Player?): ServerStanding =
    when {
        player == null -> ServerStanding.PlayerAbsent
        player.isDead -> ServerStanding.PlayerDead
        else -> player.location.let { ServerStanding.Standing(ObservedPosition(x = it.x, y = it.y, z = it.z)) }
    }

/**
 * Every occupied slot of [player]'s inventory the server can address, in window numbering.
 *
 * Empty slots are omitted rather than reported as a count of zero, which is what makes an
 * omission and a zero the same statement on both sides of the comparison. The slots are
 * translated by `windowSlotOfBukkitSlot` and then filtered by `isCorroborableSlot`, so a
 * numbering change on either side cannot quietly put a stack in the crafting grid's range.
 */
private fun heldStacksOf(player: Player): List<ObservedStack> {
    val inventory = player.inventory
    return (0 until inventory.size).mapNotNull { bukkitSlot ->
        val stack = inventory.getItem(bukkitSlot) ?: return@mapNotNull null
        val slot = windowSlotOfBukkitSlot(bukkitSlot) ?: return@mapNotNull null
        if (!isCorroborableSlot(slot) || stack.amount <= 0) {
            return@mapNotNull null
        }
        ObservedStack(slot = slot, what = registryNameOf(stack), count = stack.amount)
    }
}

/** The item's own registry name, unnamespaced, as the body reports it. */
private fun registryNameOf(stack: ItemStack): String = stack.type.key.value()
