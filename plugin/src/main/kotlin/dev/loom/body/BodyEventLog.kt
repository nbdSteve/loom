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
 * The log is part of the domain rather than the Mineflayer adapter, so evidence
 * remains readable without a body process.
 */
package dev.loom.body

import dev.loom.contract.Decision
import dev.loom.contract.GoalRefusal
import dev.loom.contract.GoalSummary
import dev.loom.contract.HorizonGoal
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
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
     * Spawn refusals carry it too because `alreadyEmbodied` means the agent still has a
     * body while the other refusals mean startup did not produce one.
     */
    val outcome: String? = null,
    /**
     * The player's vanilla food level at this point in its life.
     *
     * Kept under the established compatibility field so existing evidence readers do not
     * need a second shape. Absent where no reading was taken.
     */
    val vitals: AgentVitals? = null,
    /**
     * The body's health at this point in its life, when there was a body to read it from.
     *
     * A separate vital from [vitals], because a player can be injured while its food bar
     * is full. Present only on records that actually read it.
     */
    val health: AgentVitals? = null,
    /**
     * Which server-visible player the record is evidence about, when there is one.
     *
     * Present only on the witness records, which are the ones whose whole purpose is to be
     * cross-checked against something outside Loom - a server log line, a `playerdata` file,
     * a Mineflayer process's own account. An agent id alone cannot be cross-checked that
     * way, so the correlation data has to ride the record rather than be reconstructed from
     * a registration that is only in memory.
     */
    val witness: WitnessedPlayer? = null,
    /**
     * The complete bounded perception supplied to cognition.
     *
     * Present on [BodyEvent.PERCEIVED] and [BodyEvent.COGNITION_UNAVAILABLE].
     * The summary fields above remain for compatibility and cheap counting; this
     * field is the audit record for what the mind was actually allowed to see.
     */
    val perception: Perception? = null,
    /**
     * Research-only counts and raw world clock for [perception].
     *
     * Present on player [BodyEvent.PERCEIVED] records. It describes what the body
     * returned and what the JVM selected without changing the cognition contract
     * or model-visible prompt.
     */
    val perceptionAudit: PerceptionAudit? = null,
    /**
     * The complete bounded, typed decision returned by cognition.
     *
     * Present on [BodyEvent.DECIDED] and [BodyEvent.DECISION_DISCARDED]. Keeping
     * the typed intent and bounded reasoning beside the provenance means a public
     * experiment does not have to recover either one from [detail].
     */
    val decision: Decision? = null,
    /** How [decision]'s goal choice was resolved. Present on [BodyEvent.DECIDED] for goal reviews. */
    val goal: GoalEvidence? = null,
    /**
     * The goal and the refusal the answered perception carried to cognition, exactly as the mind
     * was shown them. Present on [BodyEvent.DECIDED] for goal reviews, which is the one place the
     * held goal and a refusal's explanation are added to a perception after it is recorded as
     * [BodyEvent.PERCEIVED] (r16: no record kept either, so what the mind was told could not be
     * audited).
     */
    val shown: ShownGoal? = null,
    /** What a flight measured, on [BodyEvent.RETREATING]: how far from its threat it ended and gained. */
    val retreat: RetreatEvidence? = null,
)

/**
 * A flight's measured separation from the threat it fled: where it ended and how much it gained,
 * both absent when the body could not measure them. Typed so a flight that ran from a threat far
 * out of sight and gained nothing is read from fields (r17: nine flights from mobs 22 blocks away
 * gained under six blocks each).
 */
@Serializable
data class RetreatEvidence(
    val separation: Double? = null,
    val separationGained: Double? = null,
    /** How many other hunters the flight was told to end clear of, beside the one it ran from (r24). */
    val avoided: Int? = null,
    /** How far the flight ended from the nearest of its threat and those hunters, where each was sensed. */
    val leastSeparation: Double? = null,
)

/** What one goal review showed cognition beyond the body's own perception. */
@Serializable
data class ShownGoal(
    /** The held goal the perception carried, or `null` when it carried none. */
    val goal: GoalSummary? = null,
    /** Why the agent's previous goal decision was refused, told once, or `null`. */
    val lastRefusal: GoalRefusal? = null,
    /** The horizon goals the review was offered, past what sight alone gives (r22). */
    val horizon: List<HorizonGoal> = emptyList(),
)

/**
 * The player identity a witness record is evidence about.
 *
 * Both halves are needed and neither substitutes for the other. The [uuid] is what the
 * server files `playerdata` under, so it is the join to vanilla's own durable state; the
 * [username] is what appears in a server log line and in the body process's configuration,
 * so it is the join to everything a human reads. In Loom's offline mode the two are related
 * by a hash, and reconstructing either from the other is exactly the inference the witness
 * refuses to make.
 *
 * Held as a [String] rather than a `java.util.UUID` because this is a wire type and the
 * canonical [dev.loom.contract.LoomJson] has no serializer for the platform class.
 */
@Serializable
data class WitnessedPlayer(
    /** The connection's uuid, in the server's own canonical dashed form. */
    val uuid: String,
    /** The connection's username, exactly as the server saw it. */
    val username: String,
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

    /**
     * The server-authoritative result of one explicit native interaction.
     *
     * Written after [INTENT_APPLIED] with the same perception request id. Its detail
     * names the offered ref, retained sense id, body-control request id, exact body
     * outcome, and the vanilla event or final inventory state used to corroborate it.
     * [BodyEventRecord.outcome] is `serverEffectConfirmed` when those agree and
     * `disputedWorldInteraction` when they do not.
     */
    @SerialName("worldInteraction")
    WORLD_INTERACTION,

    @SerialName("perceived")
    PERCEIVED,

    /**
     * A body saw something that hunts its kind and Loom did not wake its mind for it.
     *
     * The counterpart to a threat sighting that did fire, and the reason a run can tell
     * an agent that decided to stand still from one that was never asked. Without it,
     * the bounds that make an event-driven trigger affordable - the debounce, the state
     * comparison, the nightly ceiling - would each be a silent decision taken on the
     * agent's behalf, and a death by predation would look like a choice.
     *
     * Which bound refused is in [BodyEventRecord.outcome], as one of `EventTag`'s
     * `THREAT_` tags, so the three are countable apart: a run dominated by the debounce
     * is healthy, and one dominated by the nightly cap is a run whose budget shaped its
     * own findings.
     */
    @SerialName("threatSuppressed")
    THREAT_SUPPRESSED,

    /**
     * A perception the body could not take because it already held as many sense answers as
     * it keeps at once. `outcome` is [EventTag.SENSE_AT_CAPACITY] and `detail` names what asked:
     * a goal review, a threat scan or damage. Without it a review at capacity was lost with no
     * record at all (r20 beta's third scheduled review).
     */
    @SerialName("perceptionRefused")
    PERCEPTION_REFUSED,

    /**
     * The server witnessed positive damage to a managed agent's player.
     *
     * One record per witnessed event. `outcome` is one of `EventTag`'s `DAMAGE_` tags;
     * `damageSubmitted` carries the `requestId` of the `damaged` perception it produced.
     */
    @SerialName("damageWitnessed")
    DAMAGE_WITNESSED,

    /**
     * A state change in an agent's flight from a threat.
     *
     * The Mineflayer body reports one bounded flight. This record states how that flight
     * ended; displacement is already captured by [MOVED].
     *
     * Which transition it was is `outcome`, one of `EventTag`'s retreat tags, because the
     * one that matters most to a run is a failure and the rest are resolutions.
     */
    @SerialName("retreating")
    RETREATING,

    /**
     * The one bounded pickup attempt a dig makes for its goal's own dropped item, after the dig
     * settled: its body terminal is `outcome` (a collect tag, or `ownDropNotSensed`), the approach
     * and server-confirmed inventory change are in `detail`, and it joins the dig by `requestId`.
     * It is no decision and no second terminal of the dig: control returns to the mind after it.
     */
    @SerialName("ownDropPickup")
    OWN_DROP_PICKUP,

    /**
     * A scheduled goal review fell due while the agent could not take one, and waits: `outcome`
     * says why (`reviewInFlight`, `fleeing`, `dangerDeciding` or `followUpClaimed`). Once per due
     * review, never per sweep, so the record proves the schedule ran and the review is owed rather
     * than lost. No perception was taken and no mind was asked.
     */
    @SerialName("goalReviewDeferred")
    GOAL_REVIEW_DEFERRED,

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
     * Cognition is asynchronous, so between perceiving and acting an agent can die or its
     * player process can become unavailable. The decision was made and billed all the
     * same, so it gets its own event name.
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
     * The player's food level moved far enough to be worth recording.
     *
     * The serialized name is retained for evidence compatibility. [EnergyWatch] keeps
     * the reading a step function rather than a stream.
     */
    @SerialName("energyChanged")
    ENERGY_CHANGED,

    /**
     * The body's health moved far enough to be worth recording.
     *
     * Its own event rather than a reading folded into [ENERGY_CHANGED], because health
     * and food move for different reasons. A separate [EnergyWatch] gates it.
     */
    @SerialName("healthChanged")
    HEALTH_CHANGED,

    /**
     * Loom lost the process or bounded control health of one configured body.
     *
     * This is explicitly continuity rather than death. The server witness is
     * still the only authority that can write [DIED], while this record makes a
     * process crash or exhausted health budget reconstructable for the exact
     * affected agent.
     */
    @SerialName("bodyDisconnected")
    BODY_DISCONNECTED,

    @SerialName("despawned")
    DESPAWNED,

    @SerialName("died")
    DIED,

    /**
     * A registered agent's player joined the server, and the server saw it arrive.
     *
     * The first of the witness events, which are what a body Loom does not itself construct
     * has instead of a spawn: an external process drives the player, so the only account of
     * it Loom can vouch for is the server's own. Recorded from the main thread on the
     * server's own join event, and carrying [BodyEventRecord.witness] so it joins to
     * vanilla's `playerdata` and to a server log line rather than only to Loom's own memory.
     *
     * Deliberately not [SPAWNED], which says Loom put a body into the world. Nothing here
     * did: the record is a witness statement, not an action.
     */
    @SerialName("witnessJoined")
    WITNESS_JOINED,

    /**
     * A registered agent's player disconnected, and the agent is not dead.
     *
     * Continuity rather than an ending: a body process that was restarted, or a network
     * that dropped, has not killed anybody, and a log that
     * read every disconnect as a death would make permadeath unmeasurable. The distinction is
     * made by the witness rather than by whoever reads the log back - a quit after a recorded
     * death is part of that death and produces no record at all.
     */
    @SerialName("witnessQuit")
    WITNESS_QUIT,

    /**
     * A login was refused, and the identity it named will never connect again.
     *
     * The one record a dead agent may still accumulate, and the reason it may: a permadeath
     * that is enforced silently is indistinguishable from a body process that simply stopped
     * being started, so the refusal is the evidence that the enforcement is working. Which
     * bound refused is [BodyEventRecord.outcome], as one of `EventTag`'s `LOGIN_` tags,
     * because "denied because the agent is dead" and "denied because Loom could not read its
     * own death ledger" are opposite facts about a run: the first is the design working and
     * the second is a run whose survival numbers cannot be trusted.
     */
    @SerialName("witnessDenied")
    WITNESS_DENIED,

    /**
     * A dead agent's player is in the world again, which permadeath forbids.
     *
     * A record of a broken invariant rather than an ordinary lifecycle event. Every layer
     * is meant to make this
     * impossible - the login refusal, the removal on death, the body process's own
     * `respawn: false` - so a run that produced one of these has had a permadeath breached
     * and its findings about survival are suspect until the route is understood. How it got
     * back in is [BodyEventRecord.outcome], as one of `EventTag`'s `REVIVED_` tags.
     */
    @SerialName("witnessRevived")
    WITNESS_REVIVED,

    /**
     * A player body's account of itself did not match the server's own reading of it.
     *
     * Evidence about the *evidence*, which is why it is its own event rather than a field on
     * whatever the body claimed. A Mineflayer body reads its vitals, its inventory and where it
     * is from a client, and a client reports what it was told; the server knows what is true. So
     * a run measuring how much an agent ate, how close to starving it came, or how far a walk
     * actually took it, is reading numbers only the witness can vouch for, and a run that
     * produced one of these is reading some that nothing vouches for at all.
     *
     * Two different cross-checks reach this one event, and the tag is the whole of what tells
     * them apart: three of `EventTag`'s `DISPUTED_` tags doubt the readings beside an agent and
     * three doubt where a movement request left it. Which one it was is
     * [BodyEventRecord.outcome], and anything counting them reads that rather than this prose -
     * `ObserverView.disputedNote` is the display's side of the same rule. Agreement is
     * deliberately silent: the log would otherwise carry a record per cross-check for as long as
     * everything worked, and the number a run wants is how often the two disagreed.
     */
    @SerialName("witnessDisputed")
    WITNESS_DISPUTED,
}

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
