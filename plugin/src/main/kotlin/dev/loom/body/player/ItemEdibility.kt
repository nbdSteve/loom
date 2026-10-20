/*
 * Whether an item the body holds can be eaten, as the server itself answers it.
 */
package dev.loom.body.player

/**
 * Whether the item a body describes (`minecraft:bread`) is food, read from the server's own item
 * registry; `null` when no server can say, which a meal offer reads as no.
 */
fun interface ItemEdibility {
    fun edible(what: String): Boolean?

    companion object {
        /** No server to ask: nothing is known to be food. */
        val NONE: ItemEdibility = ItemEdibility { null }
    }
}
