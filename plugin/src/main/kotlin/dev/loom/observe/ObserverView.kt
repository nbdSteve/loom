/*
 * The words an operator actually reads, and the one rule about which words those are.
 *
 * THE REDACTION RULE. A record's `detail` is rendered prose, and one kind of it - the
 * mind's own reasoning - is text a language model wrote. It reaches an observer in
 * exactly one place, [ObserverView.showsDetail], and everywhere else the view says its
 * own fixed sentence about the event instead. That is not squeamishness about model
 * output; it is what keeps four different things off an operator's screen at once:
 *
 *  - a transport or provider failure's message, which is unbounded, can quote back the
 *    perception it was given, and belongs to the layer that already refuses to log it;
 *  - the chunk lists a world-hold failure names;
 *  - the free coordinates a spawn, a move, or a dimension arrival records;
 *  - anything at all that a later event adds to `detail` without thinking about who
 *    might be looking.
 *
 * The one exception is deliberate and is the point of the whole display: *why* an agent
 * chose what it chose is the single most valuable thing a viewer can be shown, and the
 * only coordinates it can contain are the whole-block target the mind itself named,
 * which the viewer is meant to see. It is bounded by `ObserverBound.oneNote` like every
 * other note, so "the model wrote a page" costs one line here too.
 *
 * NOTHING HERE IS AGENT-FACING. Every phrase below exists for an operator: it names
 * models, tiers, prompt versions, refusals, and permanent death. None of it is ever
 * written back into a `Perception`, and the one-way `ObserverSink` is what makes that
 * structural rather than careful - a sink cannot be read from, so nothing an operator
 * is shown can find its way into what an agent is told.
 */
package dev.loom.observe

import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EnergyTrend
import dev.loom.body.EventTag
import dev.loom.body.INTENT_REFUSAL_TAGS
import dev.loom.body.amountOf
import dev.loom.contract.Provenance

/** At or below this share of its capacity a vital is an alarm on its own. */
private const val LOW_VITAL_FRACTION = 0.25

/** At or below this share of its capacity a vital is worth an operator's attention. */
private const val HALF_VITAL_FRACTION = 0.5

/** A vital at or above this share of its capacity is full, and needs no line of its own. */
private const val FULL_FRACTION = 1.0

/** Separates the fields of the standing status line. */
private const val FIELD = " | "

/**
 * Turns records and view state into bounded lines.
 *
 * Stateless: given the same state it renders the same line, which is what lets the
 * observatory compare a freshly rendered line against the last one it pushed and stay
 * quiet when nothing has changed.
 */
class ObserverView(
    private val bound: ObserverBound = ObserverBound(),
) {
    /** The standing one-line status for [state]. */
    fun render(state: WatchState): Overlay =
        Overlay(
            text = bound.oneLine(line(state)),
            level = maxOf(state.activity.level, vitalLevel(state.vitals), vitalLevel(state.health)),
            progress = state.vitals?.fraction,
        )

    /**
     * The one thing worth saying about [record], or `null` to stay quiet about it.
     *
     * Total over [BodyEvent] with no default branch: a new body event must decide
     * whether an operator hears about it, because the alternative - a new event
     * defaulting into the stream - is how a bounded display becomes a firehose.
     */
    @Suppress("CyclomaticComplexMethod")
    fun noteFor(record: BodyEventRecord): Note? =
        when (record.event) {
            BodyEvent.SPAWNED -> Note("has a body now", ObserverLevel.CALM)
            BodyEvent.SPAWN_REJECTED -> refusedNote(record.outcome)
            BodyEvent.PERCEIVED -> Note("perceived the world (${triggerWords(record.trigger)})", ObserverLevel.CALM)
            BodyEvent.DECIDED -> decidedNote(record)
            BodyEvent.INTENT_APPLIED -> appliedNote(record.outcome)
            BodyEvent.DECISION_DISCARDED -> Note("its decision arrived too late to act on", ObserverLevel.CONCERN)
            BodyEvent.COGNITION_UNAVAILABLE -> unansweredNote(record.outcome)
            BodyEvent.DESPAWNED -> Note("its body was put away", ObserverLevel.CALM)
            BodyEvent.DIED -> Note("DIED. Permadeath: this agent will not return.", ObserverLevel.ALARM)
            BodyEvent.TRAVELLED -> Note("crossed into another dimension", ObserverLevel.CONCERN)
            BodyEvent.CONVERTED -> convertedNote(record.outcome)
            BodyEvent.RETREATING -> retreatNote(record.outcome)
            BodyEvent.WORLD_HOLD_FAILED -> Note(HOLD_FAILURE_CAVEAT, ObserverLevel.ALARM)
            // Every tick for a walking agent: the gauge and the activity already say it.
            BodyEvent.MOVED -> null
            // Several a second while an agent regenerates; the standing line carries it.
            BodyEvent.ENERGY_CHANGED -> null
            // The same, for the other vital: a body under attack would otherwise fill the
            // note stream with a line per half-heart while the line already says the number.
            BodyEvent.HEALTH_CHANGED -> null
            // World-level bookkeeping, and true of every agent at once rather than this one.
            BodyEvent.WORLD_LOADED -> null
            // Deliberately quiet, and not an oversight. A suppressed sighting is evidence
            // about a bound Loom applied, which belongs in the append-only record where a
            // run's numbers are read from; recorded once a second for as long as anything
            // hostile stands in view, it would fill all eight lines of a bounded display
            // with the one thing that did *not* happen. What an operator needs to see is
            // the sighting that fired, which arrives as the `perceived` note above.
            BodyEvent.THREAT_SUPPRESSED -> null
        }

    /**
     * Whether an observer may be shown this event's `detail`.
     *
     * The whole of the redaction rule in this file's header, as one total function so a
     * new body event cannot inherit a permission nobody granted it.
     */
    fun showsDetail(event: BodyEvent): Boolean =
        when (event) {
            // The mind's own reasoning: the reason an operator is watching at all.
            BodyEvent.DECIDED -> true
            BodyEvent.SPAWNED,
            BodyEvent.SPAWN_REJECTED,
            BodyEvent.INTENT_APPLIED,
            BodyEvent.MOVED,
            BodyEvent.PERCEIVED,
            BodyEvent.DECISION_DISCARDED,
            BodyEvent.COGNITION_UNAVAILABLE,
            BodyEvent.ENERGY_CHANGED,
            BodyEvent.HEALTH_CHANGED,
            BodyEvent.DESPAWNED,
            BodyEvent.DIED,
            BodyEvent.TRAVELLED,
            BodyEvent.CONVERTED,
            BodyEvent.WORLD_LOADED,
            BodyEvent.WORLD_HOLD_FAILED,
            BodyEvent.RETREATING,
            // Names the creature and the request-scoped handle it was seen under. Neither
            // is a secret, and neither is a line: the bound that refused it is the whole
            // meaning, and that is a countable tag rather than prose.
            BodyEvent.THREAT_SUPPRESSED,
            -> false
        }

    private fun decidedNote(record: BodyEventRecord): Note {
        val stamp = record.provenance?.let { " [${stampOf(it)}]" } ?: ""
        val said = if (showsDetail(record.event)) ": ${record.detail}" else ""
        return Note(bound.oneNote("decided$stamp$said"), ObserverLevel.CALM)
    }

    private fun unansweredNote(outcome: String?): Note {
        val why = outcome?.let { " (${words(it)})" } ?: ""
        return Note(bound.oneNote("its mind did not answer$why"), ObserverLevel.CONCERN)
    }

    private fun line(state: WatchState): String {
        val fields =
            buildList {
                add(state.agentId)
                addAll(vitalFields(state))
                add(state.activity.label)
                cognitionField(state)?.let { add(it) }
            }
        return fields.joinToString(FIELD)
    }

    /**
     * The vitals fields for the line: the energy reserve always, health only once hurt.
     *
     * The reserve is the vital the gauge follows and the one an unattended agent dies of,
     * so it is always on the line, with its trend while there is still a trend to have. A
     * reserve nothing is spending has none: a dead agent's last reading is on its way to
     * zero and a put-away body's is simply frozen, so a live trend would sit on the
     * tombstone, or beside `no body`, reading "declining" for the rest of the session. The
     * reading itself stays either way, because it is the last one actually taken. Health is
     * the other way a body can end, beaten down on a full reserve, so it earns a place only
     * once there is something to say; a body at full health keeps the common survival case
     * short and the most valuable line on the screen at its width.
     */
    private fun vitalFields(state: WatchState): List<String> {
        val vitals = state.vitals ?: return listOf("energy not read yet")
        val settled = state.trend == EnergyTrend.STEADY || state.isReserveFrozen
        val trend = if (settled) "" else " ${state.trend.name.lowercase()}"
        val reserve = "energy ${amountOf(vitals.energy)}/${amountOf(vitals.capacity)}$trend"
        val health =
            state.health
                ?.takeIf { it.fraction < FULL_FRACTION }
                ?.let { "health ${amountOf(it.energy)}/${amountOf(it.capacity)}" }
        return listOfNotNull(reserve, health)
    }

    private fun cognitionField(state: WatchState): String? {
        val provenance = state.provenance ?: return state.trigger?.let { words(it) }
        val prompt = state.trigger?.let { "${words(it)} -> " } ?: ""
        return prompt + stampOf(provenance)
    }

    /**
     * How much attention one vital deserves, from how much of it is left.
     *
     * Read from the level and deliberately not from the trend. A reserve is spent by
     * living, so `declining` is the standing condition of every living agent rather than
     * news about one, and colouring it left an in-world run yellow four seconds after the
     * spawn and yellow for the rest of the agent's life - the one channel an operator
     * reads at a glance, spent on the fact that time passes. What is worth a colour is
     * how much is left, because that is what runs out.
     *
     * Applied to both vitals, since either can end the agent: a body beaten down on a
     * full reserve deserves the same colour as a reserve run down on an unhurt body.
     */
    private fun vitalLevel(vitals: AgentVitals?): ObserverLevel =
        when {
            vitals == null -> ObserverLevel.CALM
            vitals.fraction <= LOW_VITAL_FRACTION -> ObserverLevel.ALARM
            vitals.fraction <= HALF_VITAL_FRACTION -> ObserverLevel.CONCERN
            else -> ObserverLevel.CALM
        }

    /**
     * The phrases that read nothing but a record's own tags.
     *
     * Here rather than in the class because that is exactly what they need: no view
     * state and, most of all, no `bound` - a phrase written here is a fixed sentence,
     * and only the two notes that repeat something a model or a transport wrote
     * ([decidedNote], [unansweredNote]) have to be bounded.
     */
    private companion object {
        /**
         * Said in full whenever Loom failed to hold the world.
         *
         * An operator watching a run needs to know that its permadeath numbers may be
         * wrong, and needs to know it while watching rather than afterwards in the log.
         */
        const val HOLD_FAILURE_CAVEAT = "Loom could not hold the world: a death shown here may be a chunk unload."

        /** The model, tier, and prompt version, as one short field. */
        fun stampOf(provenance: Provenance): String =
            "${provenance.model}/${provenance.promptVersion} ${provenance.tier.name.lowercase()}"

        /** A trigger tag as words, or an honest blank when a record carried none. */
        fun triggerWords(trigger: String?): String = trigger?.let { words(it) } ?: "unnamed trigger"

        /**
         * What the body did with an intent, in the words a viewer reads.
         *
         * A refusal is recognised from [INTENT_REFUSAL_TAGS] rather than from
         * [EventTag.REJECTED] alone, because a refusal now says *why* in the same field: a meal
         * refused six distinct ways arrives as `eatReserveFull` and its kin, and read as an
         * unrecognised tag every one of them said "the body did not act on it" - the sentence
         * that belongs to a decision which reached no body at all. Which refusal it was is the
         * countable tag's business and the record's; the line says the one thing a watcher
         * needs, which is that the agent chose something its body would not do.
         */
        fun appliedNote(outcome: String?): Note =
            when {
                outcome == EventTag.ACCEPTED -> Note("the body is doing it", ObserverLevel.CALM)
                outcome in INTENT_REFUSAL_TAGS -> Note("the body refused it", ObserverLevel.CONCERN)
                outcome == EventTag.UNSUPPORTED -> Note("the body cannot do that yet", ObserverLevel.CONCERN)
                else -> Note("the body did not act on it", ObserverLevel.CONCERN)
            }

        /**
         * What to say about a refused spawn, which is not always bad news.
         *
         * A second spawn command for an agent that already has a body is refused
         * *because* the agent is fine, so it is said calmly and without implying that the
         * body an operator can see has gone. A spawn refused because the agent has died is
         * the opposite extreme and is said in the same words the death itself is: for a
         * watch attached after the death, this refusal is the only record that will ever
         * say the agent is gone for good. Everything else is a request Loom could not meet,
         * to an agent that has no body and can still be given one.
         */
        fun refusedNote(outcome: String?): Note =
            when (outcome) {
                EventTag.ALREADY_EMBODIED ->
                    Note("already has a body, so a second one was refused", ObserverLevel.CALM)
                EventTag.HAS_DIED ->
                    Note("had already DIED. Permadeath: this agent will not return.", ObserverLevel.ALARM)
                else -> Note("was refused a body", ObserverLevel.CONCERN)
            }

        /**
         * What to say about a conversion, according to whether it took the body.
         *
         * A refusal is the ordinary case, and "something tried" is exactly right for it.
         * A conversion that got past the refusal is not: the agent is alive with nothing
         * to act through, nothing re-embodies it, and "something tried" would be the
         * display understating the one thing an operator would need to intervene about.
         */
        fun convertedNote(outcome: String?): Note =
            if (outcome == EventTag.BODY_TAKEN) {
                Note("a conversion took its body. It is alive with nothing to act through.", ObserverLevel.ALARM)
            } else {
                Note("something tried to turn it into another creature", ObserverLevel.CONCERN)
            }

        /**
         * What an operator is told about a change in an agent's flight.
         *
         * Read from the countable tag rather than from the detail, like every other event
         * whose one name carries opposite facts: getting away and being left standing in
         * range are the two halves of what a survival run is measuring, and they are the
         * same record apart from this tag. A flight in progress is an alarm because the
         * agent is being hunted; a flight that resolved is not, and a flight refused over a
         * handle rather than over the ground is a decision that could not be acted on -
         * which is the same news a discarded decision is, and no more than that.
         */
        fun retreatNote(outcome: String?): Note =
            when (outcome) {
                EventTag.RETREAT_STARTED -> Note("is running from a threat", ObserverLevel.ALARM)
                EventTag.RETREAT_CONTINUED -> Note("is still running from a threat", ObserverLevel.ALARM)
                EventTag.RETREAT_PENDING_GROUND ->
                    Note("cannot run until it is back on the ground", ObserverLevel.ALARM)
                EventTag.RETREAT_PENDING_BUDGET ->
                    Note("is waiting for a path search to run from a threat", ObserverLevel.ALARM)
                EventTag.RETREAT_SAFE -> Note("got away from the threat", ObserverLevel.CALM)
                // Worded to hold for a flight that never started as well as one that did:
                // this tag is also what a Flee naming a creature the world no longer holds
                // is filed under, and there the agent was never running from anything.
                EventTag.RETREAT_THREAT_GONE ->
                    Note("the threat is gone; there is nothing to run from", ObserverLevel.CALM)
                EventTag.RETREAT_THREAT_FORGOTTEN ->
                    Note("was told to run from a threat it could no longer name", ObserverLevel.CONCERN)
                EventTag.RETREAT_THREAT_UNNAMED ->
                    Note("was told to run from something it was never told about", ObserverLevel.CONCERN)
                EventTag.RETREAT_SUPERSEDED ->
                    Note("stopped running: a later decision took over", ObserverLevel.CONCERN)
                // RETREAT_FAILED, and anything a later version adds without saying so here:
                // the agent is still in range of something hunting it and has been left
                // standing, which is the reading an unrecognised tag must not be softer than.
                else -> Note("could not run from a threat, and is still in range of it", ObserverLevel.ALARM)
            }
    }
}
