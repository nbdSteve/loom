/*
 * The two inventory numberings, and the arithmetic between them.
 *
 * Its own file rather than part of the comparison because it is a different kind of fact:
 * `Corroboration.kt` decides whether two accounts agree, and this decides which slot each
 * account is even talking about. One is a rule of Loom's; the other is a property of two
 * APIs that happen to number the same window differently, and it can only ever change
 * because Bukkit or the protocol changed.
 *
 * The protocol's window numbering, which the body reports and `dev.loom.bodycontrol`
 * bounds, is Minecraft's own player window: 0 the crafting output, 1-4 the crafting grid,
 * 5-8 the armour helmet-downward, 9-35 the main inventory, 36-44 the hotbar, 45 the
 * offhand.
 *
 * Bukkit's `PlayerInventory` numbers the same container differently and does not address
 * all of it: 0-8 the hotbar, 9-35 the main inventory, 36-39 the armour boots-upward, 40
 * the offhand, and nothing at all for the five crafting-window slots. So a mistake in the
 * mapping would report every armour piece as being in the wrong slot, which reads as a
 * body that lied rather than as arithmetic - which is why both halves below are pure and
 * pinned by a desk test.
 */
package dev.loom.witness

import dev.loom.bodycontrol.MAX_SLOT_INDEX

/** The first window slot the server's own `PlayerInventory` can be asked about. */
const val FIRST_CORROBORABLE_SLOT: Int = 5

/**
 * The sum Bukkit's armour indices and the window's mirror to.
 *
 * The two run in opposite directions - Bukkit boots-first, the window helmet-first - so
 * one subtraction covers all four pieces: 44 - 39 is the helmet at 5, 44 - 36 the boots
 * at 8.
 */
private const val ARMOUR_MIRROR = 44

/** Bukkit's own offhand index, and the last slot it addresses. */
private const val OFFHAND_BUKKIT_SLOT = 40

/** The window slot Bukkit's hotbar index 0 stands for. */
private const val QUICK_BAR_START = 36

private const val LAST_HOTBAR_BUKKIT_SLOT = 8
private const val FIRST_MAIN_BUKKIT_SLOT = 9
private const val LAST_MAIN_BUKKIT_SLOT = 35
private const val FIRST_ARMOUR_BUKKIT_SLOT = 36
private const val LAST_ARMOUR_BUKKIT_SLOT = 39

private val HOTBAR_BUKKIT_SLOTS = 0..LAST_HOTBAR_BUKKIT_SLOT
private val MAIN_BUKKIT_SLOTS = FIRST_MAIN_BUKKIT_SLOT..LAST_MAIN_BUKKIT_SLOT
private val ARMOUR_BUKKIT_SLOTS = FIRST_ARMOUR_BUKKIT_SLOT..LAST_ARMOUR_BUKKIT_SLOT

/**
 * Whether the server can be asked what stands in [slot] of the protocol's window.
 *
 * False for the five crafting-window slots alone. They are not a gap in the check so
 * much as a gap in what `PlayerInventory` addresses, and a claim about one is therefore
 * outside the comparison rather than agreed with - see `Corroboration.kt`'s header.
 */
fun isCorroborableSlot(slot: Int): Boolean = slot in FIRST_CORROBORABLE_SLOT..MAX_SLOT_INDEX

/**
 * The protocol window slot [bukkitSlot] of a `PlayerInventory` stands for, or `null` for
 * an index that inventory does not address.
 *
 * Pure integer arithmetic, and here rather than in the adapter for that reason: this is
 * the one mapping a desk test can pin without a server.
 */
fun windowSlotOfBukkitSlot(bukkitSlot: Int): Int? =
    when (bukkitSlot) {
        in HOTBAR_BUKKIT_SLOTS -> bukkitSlot + QUICK_BAR_START
        in MAIN_BUKKIT_SLOTS -> bukkitSlot
        in ARMOUR_BUKKIT_SLOTS -> ARMOUR_MIRROR - bukkitSlot
        OFFHAND_BUKKIT_SLOT -> MAX_SLOT_INDEX
        else -> null
    }
