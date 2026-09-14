/*
 * The two vitals an agent is watched on, and when a reading is worth a record.
 *
 * Split out of the adapter for the same reason as BodyEventDetail, BodyPerception and
 * BodyEventJournal: the port implementation stays about spawning, driving and observing
 * bodies, while "which vitals there are, what scale each is read on, and how often either
 * reaches the log" lives here.
 *
 * The two are deliberately not one number. The first is the body flavor's primary survival
 * reserve - vanilla food for the player adapter, the legacy synthetic reserve for the unused
 * Villager adapter. Health is physical damage to the body, and a body can be beaten to death
 * while that reserve is full. Aliasing either to the other would make a run unable to say what
 * ended an agent.
 */
package dev.loom.body

/**
 * The scale the energy reserve is recorded and shown on: a percentage.
 *
 * The reserve is a fraction on the wire ([Metabolism.energyOf]), but the log and the
 * operator display want a number a person reads at a glance, and a percentage is that
 * number - "80/100" rather than "0.8/1". It is also what makes the reserve's step
 * function legible: [EnergyWatch]'s default step of one unit becomes one percent of the
 * reserve, so a slowly-draining reserve records a change roughly every one percent
 * rather than every tick or only at death.
 */
private const val RESERVE_GAUGE = 100.0

/**
 * An energy reserve, given as [Metabolism]'s 0..1 fraction, as [AgentVitals] on the gauge.
 *
 * The reserve is the vital the display's gauge and the survival curve follow - what an
 * unattended agent starves on - so it is the reading carried on every record that takes
 * one. Expressed as a percentage so a reader sees "80/100" and the step function records
 * roughly every percent. The body's health is a separate vital ([bodyVitals]) and travels
 * beside this rather than in it.
 */
internal fun reserveVitals(energyFraction: Double): AgentVitals =
    AgentVitals(energy = energyFraction * RESERVE_GAUGE, capacity = RESERVE_GAUGE)

/**
 * Keeps both of an agent's vitals out of the log's way, and records the changes worth it.
 *
 * One [EnergyWatch] per vital, because the two move for unrelated reasons. A shared step
 * function would let a body's health fall a whole heart while its primary reserve had not
 * moved enough to record anything, delaying the wound until that reserve next stepped.
 *
 * The readings themselves are taken by the caller, which is the half that needs a body and
 * flavor-specific source; what lives here is the judgement about which of them deserves
 * a durable record, and which compatibility event it is recorded under.
 *
 * @param journal where a reading worth recording is written.
 */
internal class VitalsWatch(
    private val journal: BodyEventJournal,
) {
    private val reserveWatch = EnergyWatch()
    private val healthWatch = EnergyWatch()

    /**
     * Takes the given readings as already recorded for [agentId], without recording them again.
     *
     * For the records that carry a reading anyway - a spawn, a perception - so an agent's
     * next tick does not restate a number one of those has just stated.
     *
     * A record that carried no health reading passes none, and the health watch is left
     * exactly as it was: taking a reading as recorded when no record states it is how a
     * wound would be silently dropped instead of being reported on the next tick.
     */
    fun noted(
        agentId: AgentId,
        reserve: AgentVitals,
        health: AgentVitals? = null,
    ) {
        reserveWatch.noted(agentId, reserve)
        health?.let { healthWatch.noted(agentId, it) }
    }

    /**
     * Records whichever of [agentId]'s vitals has moved far enough to be worth a record.
     *
     * Each vital has its own event, and every record carries both readings: which vital
     * moved is the event's name, and what the other one was at that moment is what makes
     * a record readable on its own. A body beaten down on a full reserve and a reserve run
     * down on an unhurt body are the two ways an agent ends, and a run has to be able to
     * tell them apart from the log alone.
     */
    fun recordChanges(
        agentId: AgentId,
        reserve: AgentVitals,
        health: AgentVitals,
    ) {
        reserveWatch.sample(agentId, reserve)?.let {
            // `energyChanged` and the `vitals` field are an established evidence
            // contract. The player adapter projects vanilla food onto that existing
            // scale rather than splitting historical observers in this cutover.
            journal.record(agentId, BodyEvent.ENERGY_CHANGED, describe(it, "energy"), vitals = it, health = health)
        }
        healthWatch.sample(agentId, health)?.let {
            journal.record(agentId, BodyEvent.HEALTH_CHANGED, describe(it, "health"), vitals = reserve, health = it)
        }
    }

    /**
     * Forgets both of [agentId]'s vitals, so a later body's first readings are first readings.
     *
     * Both together, always: an agent left in one watch and not the other would have one
     * vital compared against a reading from a body it no longer has.
     */
    fun forget(agentId: AgentId) {
        reserveWatch.forget(agentId)
        healthWatch.forget(agentId)
    }
}
