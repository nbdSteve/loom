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
 *  - the free coordinates a spawn or move records;
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
            BodyEvent.RETREATING -> retreatNote(record.outcome)
            BodyEvent.WITNESS_JOINED -> Note("connected to the server and has a body now", ObserverLevel.CALM)
            BodyEvent.WITNESS_QUIT ->
                Note("disconnected. It is not dead; the agent continues.", ObserverLevel.CALM)
            BodyEvent.WITNESS_DENIED -> deniedNote(record.outcome)
            BodyEvent.WITNESS_REVIVED ->
                Note(
                    "is in the world again after a terminal DEATH. Permadeath was not enforced.",
                    ObserverLevel.ALARM,
                )
            BodyEvent.WITNESS_DISPUTED -> disputedNote(record.outcome)
            // Every tick for a walking agent: the gauge and the activity already say it.
            BodyEvent.MOVED -> null
            // Several a second while an agent regenerates; the standing line carries it.
            BodyEvent.ENERGY_CHANGED -> null
            // The same, for the other vital: a body under attack would otherwise fill the
            // note stream with a line per half-heart while the line already says the number.
            BodyEvent.HEALTH_CHANGED -> null
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
            BodyEvent.RETREATING,
            // Names the creature and the request-scoped handle it was seen under. Neither
            // is a secret, and neither is a line: the bound that refused it is the whole
            // meaning, and that is a countable tag rather than prose.
            BodyEvent.THREAT_SUPPRESSED,
            // The five witness events carry the one kind of detail in Loom that was written
            // by neither Loom nor a model: a server death message names the weapon that did
            // it, and a weapon can be renamed on an anvil to anything at all. It is bounded
            // on the way into the record, which is what makes it safe to *keep*; showing it
            // would put a stranger's chosen text on an operator's screen, and the meaning of
            // every one of these events is a countable tag anyway. A dispute's detail is the
            // sharpest case: it quotes the item names a *client* claimed, so a renamed stack
            // reaches the screen through the body rather than through the server.
            BodyEvent.WITNESS_JOINED,
            BodyEvent.WITNESS_QUIT,
            BodyEvent.WITNESS_DENIED,
            BodyEvent.WITNESS_REVIVED,
            BodyEvent.WITNESS_DISPUTED,
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
     * The vitals fields for the line: player food always, health only once hurt.
     *
     * The compatibility field carries the player's food level and drives the gauge. Its
     * trend is shown only while a body can still report samples; the last reading remains
     * after disconnect or death because it is the last one actually taken. Health earns a
     * place once it is below full, keeping the common line short.
     */
    private fun vitalFields(state: WatchState): List<String> {
        val vitals = state.vitals ?: return listOf("energy not read yet")
        val settled = state.trend == EnergyTrend.STEADY || state.isVitalsFrozen
        val trend = if (settled) "" else " ${state.trend.name.lowercase()}"
        val primary = "energy ${amountOf(vitals.energy)}/${amountOf(vitals.capacity)}$trend"
        val health =
            state.health
                ?.takeIf { it.fraction < FULL_FRACTION }
                ?.let { "health ${amountOf(it.energy)}/${amountOf(it.capacity)}" }
        return listOfNotNull(primary, health)
    }

    private fun cognitionField(state: WatchState): String? {
        val provenance = state.provenance ?: return state.trigger?.let { words(it) }
        val prompt = state.trigger?.let { "${words(it)} -> " } ?: ""
        return prompt + stampOf(provenance)
    }

    /**
     * How much attention one vital deserves, from how much of it is left.
     *
     * Read from the level and deliberately not from the trend. Applied to food and
     * health alike, since either low reading deserves attention.
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
         * What to say about a refused login, which is not always bad news either.
         *
         * The dead-identity refusal is permadeath working as designed, and is said in the same
         * words the death itself is, for the same reason a spawn refused to a dead agent is:
         * for a watch attached after the death, this may be the only record that will ever say
         * the agent is gone. It is still an alarm, because something is trying to bring a dead
         * agent back and an operator wants to know that a body process is running that should
         * not be. The ledger refusal is a different alarm - it says nothing about this agent and
         * everything about Loom having lost track of which agents are dead, so a run's survival
         * numbers cannot be trusted until it is understood.
         */
        fun deniedNote(outcome: String?): Note =
            when (outcome) {
                EventTag.LOGIN_DEAD_IDENTITY ->
                    Note("was refused a login: it had already DIED and does not return.", ObserverLevel.ALARM)
                EventTag.LOGIN_LEDGER_UNSAFE ->
                    Note("was refused a login: Loom cannot read its own death records.", ObserverLevel.ALARM)
                EventTag.LOGIN_IDENTITY_MISMATCH ->
                    Note("was refused a login: its name was claimed from another account", ObserverLevel.CONCERN)
                else -> Note("was refused a login", ObserverLevel.CONCERN)
            }

        /**
         * What to say about a cross-check the server disagreed with.
         *
         * One event name over two entirely different cross-checks, so the tag is the whole of
         * what tells them apart - the same rule `retreatNote` and `deniedNote` obey. A vitals
         * or inventory dispute is a caveat on the *numbers* beside the agent; a movement
         * dispute is a caveat on *where the agent is*, which is a different thing to doubt and
         * arrives from a different cross-check. Read as one sentence, a movement dispute
         * reached an operator labelled as a vitals disagreement, which is a wrong statement
         * about a run rather than a vague one.
         *
         * Six tags and no shorthand, because the `else` may not borrow either wording: a tag
         * added later is a cross-check nobody has written a sentence for, and the honest line
         * for it says only that the server disagreed. CONCERN throughout: nothing has happened
         * to the agent, and what is in doubt is whether what the body said about it can be
         * trusted. Said while an operator is looking rather than only in the log afterwards.
         */
        fun disputedNote(outcome: String?): Note =
            when (outcome) {
                EventTag.DISPUTED_VITALS, EventTag.DISPUTED_INVENTORY, EventTag.DISPUTED_BOTH ->
                    Note(
                        "reported vitals or inventory the server disagrees with; its readings are suspect",
                        ObserverLevel.CONCERN,
                    )
                EventTag.DISPUTED_POSITION ->
                    Note(
                        "reported a position the server disagrees with; where it is is suspect",
                        ObserverLevel.CONCERN,
                    )
                EventTag.DISPUTED_JOURNEY ->
                    Note(
                        "reported a movement that contradicts its own arithmetic",
                        ObserverLevel.CONCERN,
                    )
                EventTag.DISPUTED_MOVEMENT ->
                    Note(
                        "reported a movement the server disagrees with and which contradicts itself",
                        ObserverLevel.CONCERN,
                    )
                else -> Note("reported something the server disagrees with", ObserverLevel.CONCERN)
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
