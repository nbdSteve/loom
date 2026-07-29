/*
 * Research-grade append-only logging for body events.
 *
 * One JSON object per line, append-only, never rewritten - so a run's body
 * history is replayable and every metric derives from the log rather than from
 * separately-tracked counters. This mirrors the decision log's shape on the mind
 * side: a stamped, structured record of what happened and why.
 *
 * The log is part of the domain, not the adapter: any body flavor writes the
 * same records, so metrics stay comparable across flavors.
 */
package dev.loom.body

import dev.loom.contract.LoomJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * One append-only body-event record.
 *
 * Every field is owned data. [detail] carries the event-specific summary (the
 * spawn location, the intent applied and its outcome, the despawn reason) as
 * already-rendered text, which keeps the record schema stable as new body events
 * are added.
 */
@Serializable
data class BodyEventRecord(
    /** The server tick the event was observed on. */
    val tick: Long,
    /** The agent whose body the event concerns. */
    val agentId: String,
    /** What happened. */
    val event: BodyEvent,
    /** Event-specific summary, already rendered to text. */
    val detail: String,
)

/** The body events worth a durable record. */
@Serializable
enum class BodyEvent {
    @SerialName("spawned")
    SPAWNED,

    @SerialName("spawnRejected")
    SPAWN_REJECTED,

    @SerialName("intentApplied")
    INTENT_APPLIED,

    @SerialName("moved")
    MOVED,

    @SerialName("perceived")
    PERCEIVED,

    @SerialName("despawned")
    DESPAWNED,

    @SerialName("died")
    DIED,

    /**
     * The agent's body crossed into another dimension, and the agent continued in it.
     *
     * Recorded on the travelling agent's own chain, deliberately as one event rather
     * than as a death followed by a birth: the agent that arrives is the agent that
     * left, and a log that said otherwise would make every portal look like the end
     * of a life to anyone reading the run back.
     */
    @SerialName("travelled")
    TRAVELLED,

    /**
     * Something tried to convert the body into another kind of creature.
     *
     * Also continuity, and also on the same agent's chain: lightning striking an
     * agent is an event in that agent's life, not the end of it.
     */
    @SerialName("converted")
    CONVERTED,

    /**
     * The set of chunks Loom holds loaded around its living agents changed.
     *
     * A world-level event rather than one agent's, so it is recorded against
     * [WORLD_AGENT_ID] rather than misattributed to whichever body moved last.
     */
    @SerialName("worldLoaded")
    WORLD_LOADED,

    /**
     * Loom failed to hold a chunk a living agent is standing in.
     *
     * The one body event that records a broken invariant rather than something that
     * happened to an agent. A living body in an unheld chunk can be removed by a chunk
     * unload, which the departure classification reads as a death, so a run that
     * produced any of these records has metrics that cannot be trusted about
     * permadeath. Also world-level, and searchable by itself for exactly that reason.
     */
    @SerialName("worldHoldFailed")
    WORLD_HOLD_FAILED,
}

/**
 * The agent id world-level records are filed under.
 *
 * Some events - the loaded footprint changing - belong to the world rather than to
 * any one agent, but every record carries an agent id so the log stays one shape.
 * The reservation is structural, not conventional: [AgentId] refuses this value, so
 * no agent's records can ever be mistaken for the world's.
 */
const val WORLD_AGENT_ID: String = "loom:world"

/**
 * Append-only sink for [BodyEventRecord]s.
 *
 * Implementations must only ever append. The interface is deliberately one
 * method so a test can collect records in memory and production can write
 * newline-delimited JSON to a file with the same code path under test.
 */
fun interface BodyEventLog {
    /** Appends one record. Must never block the server main thread on I/O. */
    fun append(record: BodyEventRecord)
}

/** Renders a [BodyEventRecord] as the single JSON line the log stores. */
fun BodyEventRecord.toJsonLine(): String = LoomJson.encodeToString(this)
