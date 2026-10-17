/*
 * What the server saw one agent's own digs drop, attributed to the request that dug.
 */
package dev.loom.body.player

import dev.loom.contract.DropFate
import java.util.UUID

/**
 * One stack the server dropped for one of an agent's digs: the perception request whose dig
 * dropped it, the item, how many, what became of it, and - while it is still lying - where, and
 * the server's uuid for the item entity, which the body names it by and Loom offers it by.
 */
data class OwnDropReading(
    val requestId: String,
    val what: String,
    val count: Long,
    val fate: DropFate,
    val position: List<Double>?,
    val entity: UUID? = null,
)

/**
 * The server's record of an agent's own dig drops, attributed to the request whose dig made
 * them. The digger is told of each whether or not it is in sight and whatever became of it,
 * because in r14 three of four collects took another agent's drop and the diggers, never seeing
 * their sticks, judged digs that had yielded as having yielded nothing. Main thread only.
 */
interface OwnDrops {
    /**
     * Attributes to [requestId] every stack the server dropped for [identity]'s break of [block]
     * since [sinceTick], and answers them.
     */
    fun attribute(
        identity: PlayerIdentity,
        requestId: String,
        block: List<Int>,
        sinceTick: Long,
    ): List<OwnDropReading>

    /** [identity]'s attributed drops, newest first. */
    fun of(identity: PlayerIdentity): List<OwnDropReading>

    companion object {
        /** No server to ask: no dig is known to have dropped anything. */
        val NONE: OwnDrops =
            object : OwnDrops {
                override fun attribute(
                    identity: PlayerIdentity,
                    requestId: String,
                    block: List<Int>,
                    sinceTick: Long,
                ): List<OwnDropReading> = emptyList()

                override fun of(identity: PlayerIdentity): List<OwnDropReading> = emptyList()
            }
    }
}
