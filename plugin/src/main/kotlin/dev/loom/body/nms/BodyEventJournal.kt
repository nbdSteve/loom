/*
 * Stamping and appending of body events to the append-only log.
 *
 * Split out of the adapter for the same reason as BodyEventDetail and
 * BodyPerception: the port implementation stays about spawning, driving, and
 * observing bodies, while the "stamp with the current tick and append" concern -
 * and the one place that knows a record carries the tick and the agent id - lives
 * here.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodySpawnResult
import dev.loom.body.WORLD_AGENT_ID

/**
 * Records body events against [eventLog], stamped with the current server tick.
 *
 * @param eventLog append-only sink every body event is written to.
 * @param currentTick supplies the server tick for stamping records.
 */
internal class BodyEventJournal(
    private val eventLog: BodyEventLog,
    private val currentTick: () -> Long,
) {
    /** Stamps [event] for [agentId] with the current tick and appends it. */
    fun record(
        agentId: AgentId,
        event: BodyEvent,
        detail: String,
    ) {
        eventLog.append(BodyEventRecord(currentTick(), agentId.value, event, detail))
    }

    /**
     * Records a refused spawn and returns the refusal to hand back to the caller.
     *
     * Every refusal is logged, so pairing the two here means a spawn cannot be refused
     * without the reason reaching the research record.
     */
    fun reject(
        agentId: AgentId,
        reason: String,
    ): BodySpawnResult.Rejected {
        record(agentId, BodyEvent.SPAWN_REJECTED, reason)
        return BodySpawnResult.Rejected(agentId, reason)
    }

    /**
     * Records an [event] that belongs to the world rather than to one agent.
     *
     * Filed under [WORLD_AGENT_ID] so the record keeps the same shape as every other
     * one without attributing a world-level fact to some arbitrary agent.
     */
    fun recordForWorld(
        event: BodyEvent,
        detail: String,
    ) {
        eventLog.append(BodyEventRecord(currentTick(), WORLD_AGENT_ID, event, detail))
    }
}
