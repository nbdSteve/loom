/*
 * Research-grade append-only logging for body events.
 *
 * One JSON object per line, append-only, never rewritten - so a run's body
 * history is replayable and every metric derives from the log rather than from
 * separately-tracked counters.
 *
 * The decision half of a round trip lands here too, on the same chain as the
 * perception that prompted it and the action it caused: a decision recorded apart
 * from what it did, or an action recorded apart from what decided it, leaves a run
 * that cannot say why an agent acted.
 *
 * The log is part of the domain, not the adapter: any body flavor writes the
 * same records, so metrics stay comparable across flavors.
 */
package dev.loom.body

import dev.loom.contract.LoomJson
import dev.loom.contract.Provenance
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
 *
 * Everything else is a field rather than prose for one reason: it is joined and
 * counted across records - which perception an action answers, which model was billed
 * for a run, what prompted a decision, what the body did about it, how much energy an
 * agent had left - and reading any of that back out of rendered text would make every
 * such metric a parsing exercise. Every one of them is absent from records that have
 * no value for it, so a record only ever claims what it knows.
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
    /**
     * The perception this record belongs to, when it belongs to one.
     *
     * Carried by the perception, the decision it produced, and the action the body
     * took, so the three join by id rather than by tick - several agents can think
     * in one tick, and one agent can have several decisions land in it.
     */
    val requestId: String? = null,
    /**
     * Which mind decided, at which tier, on which prompt version.
     *
     * Present on decision records only, and copied from the stamp the mind put on
     * the decision - never re-derived on this side, since a body has no standing to
     * say which model answered it.
     */
    val provenance: Provenance? = null,
    /**
     * Which event prompted cognition, as [tagOf] names it.
     *
     * On the perception records, and on the ones that say a perception went
     * unanswered. What share of a run's inference a scheduled goal review accounts
     * for, against damage or being spoken to, is the question this answers - and it
     * is the same question that decides what a run costs, since the trigger is what
     * routes a decision to a model.
     */
    val trigger: String? = null,
    /**
     * The action the mind chose, as [tagOf] names it.
     *
     * On the decision records and on the action ones, so "what do these minds
     * actually do" is a count over one field rather than a scan of [detail], whose
     * text carries the model's own reasoning alongside it.
     */
    val intent: String? = null,
    /**
     * How the record ended, as a short stable tag.
     *
     * Two things end a decision, and both land here: what the body did with an intent
     * ([tagOf] over the outcome), and, on [BodyEvent.COGNITION_UNAVAILABLE], why the
     * mind never named one. Kept as one field because both answer the same question -
     * of the decisions this run started, how many became an action - and a run that had
     * to ask it in two shapes would keep getting two different answers.
     *
     * Two events that end no decision carry it too, for a reason worth stating: their
     * name alone does not say what happened to the agent. A [BodyEvent.SPAWN_REJECTED] of
     * `alreadyEmbodied` was refused *because* the agent still has its body, where every
     * other refusal happens to an agent that has none; a [BodyEvent.CONVERTED] of
     * `bodyTaken` left the agent with no body, where a refused conversion changed nothing.
     * Both distinctions used to live only in [detail], which no operator display may read.
     */
    val outcome: String? = null,
    /**
     * How much of its energy reserve the agent had at this point in its life.
     *
     * The reserve is what living spends and what an unattended agent starves on, so it is
     * the vital a survival curve is drawn from. Present wherever the reading was taken
     * anyway - a spawn, a perception, a change worth recording, a despawn, a death - which
     * is what makes that curve derivable from the log rather than from a counter kept
     * beside it. Absent elsewhere rather than carried forward, so no record states a
     * reading that was not actually taken.
     */
    val vitals: AgentVitals? = null,
    /**
     * The body's health at this point in its life, when there was a body to read it from.
     *
     * A separate vital from the [vitals] reserve, and recorded beside it, because a body
     * can be beaten to death on a full reserve: the reserve says whether the agent is
     * starving, health says whether it is being killed. Present on the records that read a
     * body - a spawn, either vitals change, a despawn, a death - and absent from a
     * perception, which carries the reserve it handed the mind and nothing read off the
     * body itself.
     */
    val health: AgentVitals? = null,
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

    /**
     * The mind answered a perception, and the body was about to be asked to act.
     *
     * The record the run is reconstructed from: it carries the perception it answers
     * and the stamp of the model that answered, which together are the only durable
     * evidence of *why* an agent did something. Written before the intent reaches the
     * body, so each one is followed by exactly one [INTENT_APPLIED] or
     * [DECISION_DISCARDED] with the same request id.
     */
    @SerialName("decided")
    DECIDED,

    /**
     * A decision arrived for an agent whose body was gone, and could not be applied.
     *
     * Cognition is asynchronous, so between perceiving and acting an agent can die or
     * cross a portal - the very events Loom exists to study. The decision was made and
     * billed all the same, so it gets its own event name rather than a rendered phrase
     * inside another one: how often the world outruns the mind is a number a run should
     * be able to state without parsing text.
     */
    @SerialName("decisionDiscarded")
    DECISION_DISCARDED,

    /**
     * A perception reached the mind and no decision came back.
     *
     * The mind timed out, was saturated, or failed. It is a body event because it is
     * something that happened to an agent - a cycle in which it perceived the world
     * and then did nothing, for a reason that was never its own - and a run whose log
     * showed only the cycles that succeeded would overstate how much of an agent's
     * behavior its mind actually accounts for. The classification is in `outcome`; the
     * failure's own text stays in [detail], where an operator display must not go
     * looking for it, because a transport failure's message is not bounded and has
     * never been anything a viewer needs.
     */
    @SerialName("cognitionUnavailable")
    COGNITION_UNAVAILABLE,

    /**
     * An agent's energy reserve moved far enough to be worth recording.
     *
     * Energy is continuous and sampled every tick, so this is a step function over it
     * rather than a stream - see `EnergyWatch`. Sampled by the body and never by the
     * mind: how much life an agent has left is a fact about its body.
     */
    @SerialName("energyChanged")
    ENERGY_CHANGED,

    /**
     * The body's health moved far enough to be worth recording.
     *
     * Its own event rather than a reading folded into [ENERGY_CHANGED], because the two
     * vitals move for different reasons and on different timescales: the reserve drains
     * on a half-hour ramp, while health drops in one hit and can end a body in seconds.
     * Sharing one event would leave a survival query unable to say which vital moved,
     * and - worse in world - would leave a body being beaten to death invisible until
     * the reserve's own next step, which an in-world run measured at up to eighteen
     * seconds. A separate step function ([`EnergyWatch`]) gates it the same way.
     */
    @SerialName("healthChanged")
    HEALTH_CHANGED,

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
