/*
 * The two spellings of one item name, and the rule that makes them one.
 *
 * Its own file for the reason `InventorySlots.kt` is: `Corroboration.kt` decides whether two
 * accounts agree, and this decides whether they are even talking about the same item. One is
 * a rule of Loom's; the other is a property of two APIs that write one registry name
 * differently, and it can only ever change because the contract or Bukkit changed.
 *
 * The contract's `ResourceName` makes the namespace **optional**, and the two sides of the
 * cross-check land on opposite sides of that by construction: the server's reading comes from
 * `stack.type.key.value()`, which is the path alone, while every shared valid fixture spells a
 * claim `minecraft:bread`. Compared as written, every occupied checkable slot of a body that
 * was telling the truth became a fault and the whole cross-check filed a `witnessDisputed`
 * record against it.
 */
package dev.loom.witness

/** The namespace a bare resource path already means, and so the one spelling that is noise. */
private const val VANILLA_NAMESPACE = "minecraft:"

/**
 * A registry name with the vanilla namespace dropped, which is the same rule the body's own
 * `withoutNamespace` follows.
 *
 * The *vanilla* namespace alone, not any namespace: two registries can each hold a `bread`, so
 * dropping an arbitrary prefix would make a modded item compare equal to the vanilla one it
 * merely shares a path with. Only `minecraft:` is redundant, because it is the namespace a bare
 * path already means.
 */
internal fun withoutNamespace(name: String): String = name.removePrefix(VANILLA_NAMESPACE)
