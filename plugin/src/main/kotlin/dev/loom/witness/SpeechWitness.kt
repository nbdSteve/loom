/*
 * Server attribution of managed player speech, and who nearby heard it.
 *
 * A body's `sent` answer says only that its client wrote a chat packet. The server's own
 * chat event is the attribution, so a line counts as spoken only when a claim and an
 * observation from the same registered uuid carry identical text inside one bounded window.
 * Anything else is one countable dispute. Only a corroborated line is heard, by other live
 * managed agents in the same world within [PERCEPTION_RADIUS_BLOCKS]; the speaker never hears
 * itself and nothing here replies.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import java.util.UUID

/** How long a claim and its server observation may wait for each other, in server ticks. */
const val SPEECH_MATCH_WINDOW_TICKS: Long = 100

/** How many unmatched claims, and separately observations, one agent may hold. */
const val MAX_PENDING_SPEECH_PER_AGENT: Int = 8

/** Mirrors the fleet bound, so one line can wake at most this many listeners. */
const val MAX_SPEECH_HEARERS: Int = 16

/** Where a player was, as the server reported it. */
data class SpokenPlace(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** One line the server's chat event attributed to a connection. */
data class ObservedSpeech(
    val uuid: UUID,
    val username: String,
    val text: String,
    /** Read on the main thread; null when the speaker is no longer online and alive, so nobody hears it. */
    val place: SpokenPlace?,
    val tick: Long,
)

/** One line a managed body reported as `sent`, for one Loom request. */
data class SpeechClaimed(
    val agentId: AgentId,
    val requestId: String?,
    val controlRequestId: String,
    val text: String,
    val tick: Long,
)

/** One listener woken by one corroborated line. */
data class Heard(
    val listener: AgentId,
    val speaker: AgentId,
    val from: String,
    val text: String,
)

/**
 * Correlates speech claims with server observations. Main thread only.
 *
 * @param placeOf where a managed agent is now, or null when it is offline or not alive.
 * @param isDead whether an agent is durably dead, so a tombstoned player hears nothing.
 * @param hear delivers one listener's trigger. Called at most once per listener per line.
 */
class SpeechWitness(
    private val registry: WitnessRegistry,
    private val journal: SpeechJournal,
    private val placeOf: (ManagedIdentity) -> SpokenPlace?,
    private val isDead: (AgentId) -> Boolean,
    private val hear: (Heard) -> Unit,
) {
    private val claims = HashMap<AgentId, ArrayDeque<SpeechClaimed>>()
    private val observations = HashMap<AgentId, ArrayDeque<ObservedSpeech>>()

    /** Records a server chat event. A stranger is recorded nowhere. */
    fun observed(speech: ObservedSpeech) {
        val identity = registry.byUuid(speech.uuid) ?: return
        val agent = identity.agentId
        val pending = claims[agent]
        val claim = pending?.firstOrNull { it.text == speech.text }
        if (claim != null) {
            pending.remove(claim)
            corroborated(identity, claim, speech)
            return
        }
        bounded(observations.getOrPut(agent) { ArrayDeque() }, speech) { unclaimed(it) }
    }

    /** Records one body report of a line `sent`. Refused and rate-limited lines are not claims. */
    fun claimed(claim: SpeechClaimed) {
        val identity = registry.byAgent(claim.agentId) ?: return
        val pending = observations[claim.agentId]
        val speech = pending?.firstOrNull { it.text == claim.text }
        if (speech != null) {
            pending.remove(speech)
            corroborated(identity, claim, speech)
            return
        }
        bounded(claims.getOrPut(claim.agentId) { ArrayDeque() }, claim) { unobserved(it, emptyList()) }
    }

    /** Settles every claim and observation older than the window. */
    fun tick(now: Long) {
        val staleObservations =
            observations.values.flatMap { queue -> queue.filter { now - it.tick > SPEECH_MATCH_WINDOW_TICKS } }
        val staleClaims = claims.values.flatMap { queue -> queue.filter { now - it.tick > SPEECH_MATCH_WINDOW_TICKS } }
        observations.values.forEach { queue -> queue.removeAll(staleObservations::contains) }
        claims.values.forEach { queue -> queue.removeAll(staleClaims::contains) }
        val spent = HashSet<ObservedSpeech>()
        staleClaims.forEach { claim ->
            val sameAgent = staleObservations.filter { registry.byUuid(it.uuid)?.agentId == claim.agentId }
            val elsewhere = staleObservations.filter { it.text == claim.text && it !in sameAgent }
            val partner = (elsewhere + sameAgent).firstOrNull { it !in spent }
            if (partner != null) spent += partner
            unobserved(claim, listOfNotNull(partner))
        }
        staleObservations.filterNot(spent::contains).forEach(::unclaimed)
        observations.values.removeIf { it.isEmpty() }
        claims.values.removeIf { it.isEmpty() }
    }

    /** Drops an agent's pending speech without a verdict, on disconnect, death or shutdown. */
    fun forget(agentId: AgentId) {
        claims.remove(agentId)
        observations.remove(agentId)
    }

    private fun corroborated(
        identity: ManagedIdentity,
        claim: SpeechClaimed,
        speech: ObservedSpeech,
    ) {
        journal.spoke(
            subjectOf(identity.agentId, speech.uuid, speech.username),
            requestId = claim.requestId,
            detail = "said ${speech.text.length} chars; control ${claim.controlRequestId}; server attributed",
            tick = speech.tick,
        )
        val spokenAt = speech.place ?: return
        registry.registrations.byAgent.values
            .asSequence()
            .filter { it.agentId != identity.agentId && !isDead(it.agentId) }
            .filter { listener -> placeOf(listener)?.let { within(spokenAt, it) } == true }
            .sortedBy { it.agentId.value }
            .take(MAX_SPEECH_HEARERS)
            .forEach { hear(Heard(it.agentId, identity.agentId, speech.username, speech.text)) }
    }

    private fun unclaimed(speech: ObservedSpeech) {
        val identity = registry.byUuid(speech.uuid) ?: return
        journal.speechDisputed(
            subjectOf(identity.agentId, speech.uuid, speech.username),
            EventTag.UNCLAIMED_SPEECH,
            "server saw ${speech.text.length} chars with no say claim",
            speech.tick,
        )
    }

    private fun unobserved(
        claim: SpeechClaimed,
        partner: List<ObservedSpeech>,
    ) {
        val identity = registry.byAgent(claim.agentId) ?: return
        val seen = partner.firstOrNull()
        val seenAgent = seen?.let { registry.byUuid(it.uuid)?.agentId }
        val (tag, why) =
            when {
                seen == null -> EventTag.UNOBSERVED_SPEECH to "no server chat attributed"
                seenAgent != claim.agentId ->
                    EventTag.DISPUTED_SPEECH_SENDER to "server attributed it to ${seen.username}"
                else -> EventTag.DISPUTED_SPEECH_TEXT to "server saw ${seen.text.length} different chars"
            }
        journal.speechDisputed(
            subjectOf(identity),
            tag,
            "claimed ${claim.text.length} chars; control ${claim.controlRequestId}; $why",
            seen?.tick ?: claim.tick,
            requestId = claim.requestId,
        )
    }

    private fun within(
        spoken: SpokenPlace,
        listener: SpokenPlace,
    ): Boolean {
        if (spoken.world != listener.world) return false
        val dx = spoken.x - listener.x
        val dy = spoken.y - listener.y
        val dz = spoken.z - listener.z
        return dx * dx + dy * dy + dz * dz <= PERCEPTION_RADIUS_BLOCKS * PERCEPTION_RADIUS_BLOCKS
    }

    private fun <T> bounded(
        queue: ArrayDeque<T>,
        item: T,
        evicted: (T) -> Unit,
    ) {
        queue.addLast(item)
        while (queue.size > MAX_PENDING_SPEECH_PER_AGENT) evicted(queue.removeFirst())
    }
}

/** Writes speech attribution records to the append-only log, with bounded detail. */
class SpeechJournal(
    private val eventLog: BodyEventLog,
) {
    /** One line the server attributed to the agent that claimed it. */
    fun spoke(
        subject: WitnessSubject,
        requestId: String?,
        detail: String,
        tick: Long,
    ) = eventLog.append(
        recordOf(subject, detail, tick, requestId)
            .copy(event = BodyEvent.WORLD_INTERACTION, outcome = EventTag.SERVER_EFFECT_CONFIRMED),
    )

    /** One attribution disagreement. [tag] is one of `EventTag`'s speech dispute tags. */
    fun speechDisputed(
        subject: WitnessSubject,
        tag: String,
        detail: String,
        tick: Long,
        requestId: String? = null,
    ) = eventLog.append(recordOf(subject, detail, tick, requestId).copy(outcome = tag))

    private fun recordOf(
        subject: WitnessSubject,
        detail: String,
        tick: Long,
        requestId: String?,
    ) = BodyEventRecord(
        tick = tick,
        agentId = subject.agentId.value,
        event = BodyEvent.WITNESS_DISPUTED,
        detail = boundedDetail(detail),
        requestId = requestId,
        intent = EventTag.SAY,
        witness = subject.player,
    )
}
