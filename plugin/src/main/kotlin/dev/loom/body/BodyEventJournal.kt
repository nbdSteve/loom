/*
 * Stamping and appending of body events to the append-only log.
 *
 * The player adapter supplies the facts; this class stamps and appends them.
 */
package dev.loom.body

/**
 * Records body events against [eventLog], stamped with the current server tick.
 *
 * @param eventLog append-only sink every body event is written to.
 * @param currentTick supplies the server tick for stamping records.
 */
class BodyEventJournal(
    private val eventLog: BodyEventLog,
    private val currentTick: () -> Long,
) {
    /**
     * Stamps [event] for [agentId] with the current tick and appends it.
     *
     * [vitals] carries the player's vanilla food reading and [health] its hit points.
     * They stay absent where no reading was taken.
     *
     * The events that answer a perception carry a request id, and they have their own
     * methods - [recordPerception] and [recordAction] - because each also carries a fact
     * only it has; this generic path is for the events an agent produces of its own accord.
     */
    fun record(
        agentId: AgentId,
        event: BodyEvent,
        detail: String,
        vitals: AgentVitals? = null,
        health: AgentVitals? = null,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = event,
                detail = detail,
                vitals = vitals,
                health = health,
            ),
        )
    }

    /**
     * Records that a perception was taken, naming what prompted it.
     *
     * Its own method rather than another optional argument because the trigger is the
     * one fact only a perception has, and it is the field a run's inference cost is
     * apportioned by. The primary survival reading rides along because the perception
     * took it anyway. The body's health is left to the change and death records, which
     * carry it wherever it moved.
     */
    fun recordPerception(
        agentId: AgentId,
        detail: String,
        requestId: String?,
        trigger: String,
        vitals: AgentVitals?,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = BodyEvent.PERCEIVED,
                detail = detail,
                requestId = requestId,
                trigger = trigger,
                vitals = vitals,
            ),
        )
    }

    /**
     * Records what the body did with an intent, and whether it did it.
     *
     * The pair is what makes "how often does the body refuse what the mind decided" a
     * count rather than a scan of prose, so the two are written together or not at all.
     */
    fun recordAction(
        agentId: AgentId,
        detail: String,
        requestId: String?,
        intent: String,
        outcome: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = BodyEvent.INTENT_APPLIED,
                detail = detail,
                requestId = requestId,
                intent = intent,
                outcome = outcome,
            ),
        )
    }

    /**
     * Records a refused spawn and returns the refusal to hand back to the caller.
     *
     * Every refusal is logged, so pairing the two here means a spawn cannot be refused
     * without the reason reaching the research record.
     *
     * [outcome] is that reason as one of `EventTag`'s refusal tags, and it is required
     * rather than optional because the refusals do not all mean the same thing about the
     * agent: `alreadyEmbodied` is refused *because* the agent still has its body, while
     * the rest happen to an agent that has none. Only the tag can tell them apart - the
     * prose in [reason] is for a person - so a new refusal has to say which it is.
     */
    fun reject(
        agentId: AgentId,
        reason: String,
        outcome: String,
    ): BodySpawnResult.Rejected {
        classified(agentId, BodyEvent.SPAWN_REJECTED, reason, outcome)
        return BodySpawnResult.Rejected(agentId, reason)
    }

    /**
     * Records a threat the body saw and Loom did not wake its mind for.
     *
     * Takes the whole [ThreatSuppression] rather than its parts because the reason is the
     * point of the record and its tag travels with it: a suppression logged without one
     * would leave a run unable to say whether the cadence, the state comparison or the
     * night's ceiling is what kept an agent from reacting to something hunting it.
     */
    fun recordSuppressedThreat(
        agentId: AgentId,
        suppression: ThreatSuppression,
    ) {
        classified(
            agentId,
            BodyEvent.THREAT_SUPPRESSED,
            "${suppression.what} in view as ${suppression.ref}, not reported",
            suppression.reason.tag,
        )
    }

    /**
     * Records one transition in an agent's flight from a threat.
     *
     * [outcome] is required for the same reason a refused spawn's is: the transitions are
     * opposite facts about the run - an agent that got away, one whose threat vanished,
     * and one left standing in range - and only the tag tells them apart.
     */
    fun recordRetreat(
        agentId: AgentId,
        detail: String,
        outcome: String,
    ) {
        classified(agentId, BodyEvent.RETREATING, detail, outcome)
    }

    /** Appends an [event] whose meaning is carried by a short stable [outcome] tag. */
    private fun classified(
        agentId: AgentId,
        event: BodyEvent,
        detail: String,
        outcome: String,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = agentId.value,
                event = event,
                detail = detail,
                outcome = outcome,
            ),
        )
    }
}
