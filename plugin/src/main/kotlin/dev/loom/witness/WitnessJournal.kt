/*
 * How a witness statement reaches the research record.
 *
 * The append-only body event log is already the one place a run's history lives, and these
 * records go into it rather than into a file of their own. That is deliberate: "how many agents
 * died in this run" has to be one query, and a player body's death recorded somewhere else
 * would make it two - which is the kind of split that shows up as a disagreement between two
 * numbers in a paper months later.
 *
 * Every detail is rendered here, except the two whose reason is a fact only the caller has - a
 * refused login and a breached permadeath - and every one of them, rendered or passed, goes
 * through [boundedDetail]. A witness detail is the one place in Loom where text from outside
 * both Loom and its models reaches a record: a vanilla death message names the weapon that did
 * it, and a weapon can be renamed on an anvil to anything of any length with any character in
 * it. Bounding it at the single point every record passes through is what makes "bounded
 * detail" a property of the type rather than a habit of its callers.
 *
 * The journal writes; it decides nothing. Whether a quit is a death, whether a login may be
 * admitted, whether a death is the first - all of that is settled before anything gets here.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.WitnessedPlayer
import java.util.UUID

/**
 * The most characters a witness record's detail may carry.
 *
 * Sized to hold a full vanilla death message with its killer and weapon named, and no more. The
 * countable half of every witness record is already a field - the event name, the outcome tag,
 * the identity - so the detail is only ever the human-readable summary, and nothing derived
 * from a run should be reading it.
 */
const val MAX_WITNESS_DETAIL_CHARS: Int = 192

private const val ELLIPSIS = "..."

/** Whitespace, control characters, and Minecraft's own formatting sign. */
private val NOT_ONE_LINE = Regex("[\\p{C}\\p{Z}\\s§]+")

/**
 * Who a witness statement is about.
 *
 * The agent a record is filed under and the player the server actually saw are one fact and always
 * travel together, and they are deliberately *not* always two halves of the same identity: a
 * refusal is filed under the agent whose death the ledger states while naming the connection that
 * turned up, and for a tombstone that only partly read back there is no stored identity to name at
 * all. Carrying them as a pair is what lets that be said without a `ManagedIdentity` Loom would
 * have had to invent.
 *
 * Which of the two each half answers is fixed: the agent is Loom's own resolution, and the player is
 * the server's report. Every statement in this file therefore takes one of these rather than a
 * `ManagedIdentity`, so there is no second way to file a record and no way to file one that names a
 * player nobody saw.
 */
data class WitnessSubject(
    val agentId: AgentId,
    val player: WitnessedPlayer,
)

/**
 * The subject of a statement about the agent [agentId], made by the connection [uuid]/[username].
 *
 * The only way the witness builds a subject, on every path: the agent is whatever Loom resolved the
 * connection to - a registration, or a tombstone - and the two player fields are always what the
 * server presented, since they are the join to a server log line and to a `playerdata` file.
 */
fun subjectOf(
    agentId: AgentId,
    uuid: UUID,
    username: String,
): WitnessSubject = WitnessSubject(agentId, WitnessedPlayer(uuid = uuid.toString(), username = username))

/**
 * The subject of a statement about [identity] itself, where the agent and the player agree.
 *
 * For a caller that has no connection to name and means the registration as both, which after a
 * login is nothing the witness itself does: the lookup there is by uuid alone, so the registration's
 * username is a stored value rather than a presented one.
 */
fun subjectOf(identity: ManagedIdentity): WitnessSubject = subjectOf(identity.agentId, identity.uuid, identity.username)

/**
 * Writes witness statements to the append-only record.
 *
 * Holds no clock. Every method takes the tick from its caller, so a death's tombstone and its
 * `died` record - two durable records of one event, written to two files - always state the same
 * tick rather than two readings taken a moment apart.
 *
 * @param eventLog where records go. The same log the rest of Loom writes to.
 */
class WitnessJournal(
    private val eventLog: BodyEventLog,
) {
    /** Records that a managed player arrived in the world. */
    fun joined(
        subject: WitnessSubject,
        world: String,
        tick: Long,
    ) = write(
        subject,
        BodyEvent.WITNESS_JOINED,
        "${subject.player.username} joined the server in $world",
        tick,
    )

    /**
     * Records that a managed player left, with the agent alive.
     *
     * Only ever called for a departure the witness has established is not part of a death, so
     * the detail may state that outright.
     */
    fun quit(
        subject: WitnessSubject,
        tick: Long,
    ) = write(
        subject,
        BodyEvent.WITNESS_QUIT,
        "${subject.player.username} disconnected with the agent alive; continuity, not a death",
        tick,
    )

    /**
     * Records the one authoritative death of a managed agent.
     *
     * Reuses [BodyEvent.DIED], which is Loom's own permadeath record, rather than adding a
     * second death event beside it. [cause] is the server's account of what killed the player.
     */
    fun died(
        subject: WitnessSubject,
        cause: String,
        tick: Long,
    ) = write(
        subject,
        BodyEvent.DIED,
        "$cause. Permadeath: this agent will not return.",
        tick,
    )

    /**
     * Records a refused login. [tag] is one of `EventTag`'s `LOGIN_` values.
     *
     * Takes a [WitnessSubject] rather than an identity, because a refusal is the one statement whose
     * agent and whose player need not be two halves of one registration: it is filed under the agent
     * the ledger or the registry names, and its `witness` fields say what actually connected. For a
     * tombstone that only partly read back there is no stored identity at all, and anything put
     * there would be a player Loom invented and filed against a real agent.
     */
    fun denied(
        subject: WitnessSubject,
        tag: String,
        why: String,
        tick: Long,
    ) = write(subject, BodyEvent.WITNESS_DENIED, why, tick, tag)

    /**
     * Records that a dead agent's player is in the world again.
     *
     * [tag] is one of `EventTag`'s `REVIVED_` values, naming which layer let it back in.
     *
     * Takes a [WitnessSubject] for the same reason [denied] does: a breach is reachable with nothing
     * registered, which is exactly the restart the durable ledger is written to survive, and there
     * all Loom holds is the agent a tombstone names and the connection the server actually saw.
     */
    fun revived(
        subject: WitnessSubject,
        tag: String,
        why: String,
        tick: Long,
    ) = write(subject, BodyEvent.WITNESS_REVIVED, why, tick, tag)

    private fun write(
        subject: WitnessSubject,
        event: BodyEvent,
        detail: String,
        tick: Long,
        outcome: String? = null,
    ) = eventLog.append(
        BodyEventRecord(
            tick = tick,
            agentId = subject.agentId.value,
            event = event,
            detail = boundedDetail(detail),
            outcome = outcome,
            witness = subject.player,
        ),
    )
}

/**
 * Reduces [text] to one bounded line fit for a record's detail.
 *
 * Deliberately not `ObserverBound`'s equivalent, which is sized for a boss bar and lives in the
 * package that *reads* records: a writer reaching into the display layer for a helper would
 * invert the one-way flow that makes watching a run provably unable to disturb it.
 */
internal fun boundedDetail(text: String): String {
    val flattened = text.replace(NOT_ONE_LINE, " ").trim()
    if (flattened.length <= MAX_WITNESS_DETAIL_CHARS) {
        return flattened
    }
    return flattened.take(MAX_WITNESS_DETAIL_CHARS - ELLIPSIS.length).trimEnd() + ELLIPSIS
}
