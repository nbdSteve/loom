/*
 * Whether an item the body could hold places as a solid block, as the server itself answers it.
 */
package dev.loom.body.player

/**
 * Whether the item a body describes (`minecraft:dirt`) places as a block no creature passes, read
 * from the server's own registry; `null` when no server can say, which a shelter reads as no.
 */
fun interface ItemSolidity {
    fun solid(what: String): Boolean?

    /** Whether the block [what] places falls when nothing is under it, as sand and gravel do. */
    fun falls(what: String): Boolean = false

    companion object {
        /** No server to ask: nothing is known to be solid. */
        val NONE: ItemSolidity = ItemSolidity { null }
    }
}
