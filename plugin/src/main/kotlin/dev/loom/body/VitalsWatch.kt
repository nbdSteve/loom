/*
 * Player vitals evidence and the step functions that keep it bounded.
 *
 * The established `vitals`/`energyChanged` evidence field carries the player's vanilla
 * food level. Health remains a separate reading because either one can reach zero.
 */
package dev.loom.body

/** Records player food and health changes that are large enough to be durable evidence. */
internal class VitalsWatch(
    private val journal: BodyEventJournal,
) {
    private val foodWatch = EnergyWatch()
    private val healthWatch = EnergyWatch()

    /**
     * Takes readings as already recorded for [agentId], without writing duplicates.
     *
     * A missing health reading leaves the health watch untouched.
     */
    fun noted(
        agentId: AgentId,
        food: AgentVitals,
        health: AgentVitals? = null,
    ) {
        foodWatch.noted(agentId, food)
        health?.let { healthWatch.noted(agentId, it) }
    }

    /** Records whichever of the player's food or health readings crossed its step. */
    fun recordChanges(
        agentId: AgentId,
        food: AgentVitals,
        health: AgentVitals,
    ) {
        foodWatch.sample(agentId, food)?.let {
            journal.record(
                agentId,
                BodyEvent.ENERGY_CHANGED,
                describe(it, "food"),
                vitals = it,
                health = health,
            )
        }
        healthWatch.sample(agentId, health)?.let {
            journal.record(
                agentId,
                BodyEvent.HEALTH_CHANGED,
                describe(it, "health"),
                vitals = food,
                health = it,
            )
        }
    }

    /** Forgets both readings when the player body is released. */
    fun forget(agentId: AgentId) {
        foodWatch.forget(agentId)
        healthWatch.forget(agentId)
    }
}
