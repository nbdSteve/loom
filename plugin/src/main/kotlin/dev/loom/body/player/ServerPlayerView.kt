/*
 * What the server itself holds about a managed player that a body's own account cannot be trusted
 * for: where it stands, and which hotbar slot is in its main hand.
 */
package dev.loom.body.player

/** The server's own reading of [PlayerIdentity]'s live player; `null` where it holds none. */
interface ServerPlayerView {
    /** The feet position the server holds: where the shelter witness reads enclosure. */
    fun feetOf(identity: PlayerIdentity): List<Double>?

    /**
     * The protocol window slot (36 to 44) of the hotbar slot in the main hand: the stack a
     * placement must already be holding.
     */
    fun mainHandSlotOf(identity: PlayerIdentity): Int?

    companion object {
        /** No server to ask: no player is known to stand anywhere or hold anything. */
        val NONE: ServerPlayerView =
            object : ServerPlayerView {
                override fun feetOf(identity: PlayerIdentity): List<Double>? = null

                override fun mainHandSlotOf(identity: PlayerIdentity): Int? = null
            }
    }
}
