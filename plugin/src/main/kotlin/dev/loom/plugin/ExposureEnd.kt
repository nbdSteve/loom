/*
 * The Bukkit half of ending a research run's experiment exposure, and the one step that ends it.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.mind.ExposureGate
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import java.util.UUID

/**
 * Once exposure has ended, cancels every damage to a managed player and every creature taking one
 * as its target, first of any listener, so no new damage, death or targeting can count after the
 * end (r34: delta and gamma were shot dead by skeletons in the drain after admission closed). A
 * player Loom does not manage is never touched, and nothing changes before the end.
 */
class BukkitExposureGuard(
    private val exposure: ExposureGate,
    private val managed: (UUID) -> Boolean,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun damaged(event: EntityDamageEvent) {
        if (exposure.ended && (event.entity as? Player)?.let { managed(it.uniqueId) } == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun targeted(event: EntityTargetLivingEntityEvent) {
        if (exposure.ended && (event.target as? Player)?.let { managed(it.uniqueId) } == true) event.isCancelled = true
    }
}

/**
 * Ends the run's exposure in one main-thread step: the gate, the stop of every native cognition
 * source, and one `exposureEnded` record per configured agent, all on one tick, so nothing new is
 * perceived, sent, applied or owed after it and each agent's chain states its own end.
 */
internal class ExposureEnd(
    private val exposure: ExposureGate,
    private val eventLog: BodyEventLog,
    private val agents: () -> List<AgentId>,
    private val currentTick: () -> Long,
    private val stopSources: () -> Unit,
) {
    /** Ends the exposure now, answering whether this call was the one that did. */
    fun end(): Boolean {
        val tick = currentTick()
        if (!exposure.end(tick)) return false
        stopSources()
        for (agent in agents().sortedBy { it.value }) {
            eventLog.append(
                BodyEventRecord(
                    tick = tick,
                    agentId = agent.value,
                    event = BodyEvent.EXPOSURE_ENDED,
                    detail = "the run's exposure ended at tick $tick: nothing new counts from here",
                    outcome = EventTag.EXPOSURE_ENDED,
                ),
            )
        }
        return true
    }
}
