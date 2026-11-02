/*
 * The server's own record of when an agent stands sealed in: how long a shelter held, and whether
 * damage reached an agent inside one, are facts the readiness gate counts, so they are sampled from
 * the server's ground at the player's server position rather than read from any perception.
 */
package dev.loom.body.player

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.ShelterEvidence
import kotlin.math.floor

/** How often each live agent's enclosure is read, in server ticks: once a second. */
internal const val SHELTER_SAMPLE_TICKS = 20L

/**
 * Journals one [BodyEvent.SHELTER_CHANGED] record each time a live agent's sealed state, read from
 * the server's ground around the server's own position for its player, changes - a session that
 * starts sealed in included - so time sheltered is a sum of intervals rather than a sampled guess.
 * Main thread only, once per server tick; engine-free, since both reads come through the authority.
 */
internal class ShelterWitness(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
) {
    private var sealed: Boolean? = null

    fun tick(now: Long) {
        val authority = context.authority
        if (now % SHELTER_SAMPLE_TICKS != 0L || authority.ground === BlockGround.UNKNOWN) return
        val runtime = registry.all().firstOrNull { registry.ready(it.identity.agentId) != null }
        val cell = runtime?.let { authority.players.feetOf(it.identity) }?.map { floor(it).toInt() }
        val ground = authority.ground.cached()
        val reading = cell?.let { sealedAt(ground, it) }
        val was = sealed
        sealed = reading
        // A session that starts in the open has nothing to report until it is first sealed in.
        val changed = reading != null && reading != was && (was != null || reading)
        if (runtime != null && cell != null && changed) {
            authority.eventLog.append(
                BodyEventRecord(
                    tick = authority.currentTick(),
                    agentId = runtime.identity.agentId.value,
                    event = BodyEvent.SHELTER_CHANGED,
                    detail = if (reading) "sealed in" else "no longer sealed in",
                    outcome = if (reading) EventTag.SHELTER_SEALED else EventTag.SHELTER_OPEN,
                    shelter = ShelterEvidence(sealed = reading, closed = closedFacesAt(ground, cell).toLong()),
                ),
            )
        }
    }
}
