/*
 * The server's own reading of each agent at the tick a held research run begins.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.InitialConditionsEvidence
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Journals one [BodyEvent.INITIAL_CONDITIONS_WITNESSED] record per configured agent, all on the
 * tick `/loombody begin` runs and before any native cognition source starts: the server's own
 * position for the agent's player and how many stacks it holds, or that it had no player.
 *
 * That is the one tick a research run's pinned conditions must hold. An agent's first perception
 * can come a whole review stagger later, and vanilla may change an idle body in between, so a
 * first perception is the wrong witness of whether the conditions were applied (r38: gamma, idle
 * beside chickens, no longer matched its pinned conditions 666 ticks after begin, and the run was
 * refused as if they had never been applied).
 */
internal class InitialConditionsWitness(
    private val eventLog: BodyEventLog,
    private val agents: () -> List<AgentId>,
    private val readingOf: (AgentId) -> InitialConditionsEvidence?,
    private val currentTick: () -> Long,
) {
    /** Reads and journals every configured agent now. Main thread only. */
    fun witness() {
        val tick = currentTick()
        for (agent in agents().sortedBy { it.value }) {
            val reading = readingOf(agent)
            eventLog.append(
                BodyEventRecord(
                    tick = tick,
                    agentId = agent.value,
                    event = BodyEvent.INITIAL_CONDITIONS_WITNESSED,
                    detail =
                        if (reading == null) {
                            "the agent had no player on the server when the run began"
                        } else {
                            "the server read the agent's player when the run began"
                        },
                    outcome =
                        if (reading == null) {
                            EventTag.INITIAL_CONDITIONS_ABSENT
                        } else {
                            EventTag.INITIAL_CONDITIONS_WITNESSED
                        },
                    initial = reading,
                ),
            )
        }
    }
}

/**
 * The server's reading of [player] for [InitialConditionsWitness]: its exact position and every
 * stack it holds, the one on its cursor included.
 */
internal fun initialConditionsOf(player: Player): InitialConditionsEvidence {
    val at = player.location
    val stacks = player.inventory.contents.count(::isStack) + if (isStack(player.itemOnCursor)) 1 else 0
    return InitialConditionsEvidence(position = listOf(at.x, at.y, at.z), inventoryStacks = stacks)
}

private fun isStack(item: ItemStack?): Boolean = item != null && !item.isEmpty
