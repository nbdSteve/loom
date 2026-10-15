package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SpeechWitnessTest {
    private val alpha = managed("agent-alpha", "LoomAlpha")
    private val beta = managed("agent-beta", "LoomBeta")
    private val gamma = managed("agent-gamma", "LoomGamma")
    private val log = RecordingLog()
    private val places = HashMap<AgentId, SpokenPlace?>()
    private val dead = HashSet<AgentId>()
    private val heard = ArrayList<Heard>()
    private val witness =
        SpeechWitness(
            registry = registryOf(alpha, beta, gamma),
            journal = SpeechJournal(log),
            placeOf = { places[it.agentId] },
            isDead = { it in dead },
            hear = { heard += it },
        )

    init {
        places[alpha.agentId] = place(0.0)
        places[beta.agentId] = place(5.0)
        places[gamma.agentId] = place(500.0)
    }

    @Test
    fun aClaimedAndObservedLineIsConfirmedAndHeardOnlyByNearbyOthers() {
        witness.claimed(claim(alpha, "hello there"))
        witness.observed(observed(alpha, "hello there"))

        val confirmed = log.of(BodyEvent.WORLD_INTERACTION).single()
        assertEquals(EventTag.SERVER_EFFECT_CONFIRMED, confirmed.outcome)
        assertEquals("perception-1", confirmed.requestId)
        assertEquals(EventTag.SAY, confirmed.intent)
        assertEquals(alpha.uuid.toString(), confirmed.witness?.uuid)
        assertTrue("control-1" in confirmed.detail)
        assertEquals(listOf(Heard(beta.agentId, alpha.agentId, "LoomAlpha", "hello there")), heard)
    }

    @Test
    fun observationBeforeClaimStillCorroboratesExactlyOnce() {
        witness.observed(observed(alpha, "hi"))
        witness.claimed(claim(alpha, "hi"))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)

        assertEquals(1, log.of(BodyEvent.WORLD_INTERACTION).size)
        assertEquals(emptyList<Any>(), log.of(BodyEvent.WITNESS_DISPUTED))
        assertEquals(1, heard.size)
    }

    @Test
    fun differentTextIsOneTextDisputeAndNobodyHears() {
        witness.claimed(claim(alpha, "hello"))
        witness.observed(observed(alpha, "hellp"))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)

        assertEquals(listOf(EventTag.DISPUTED_SPEECH_TEXT), disputeTags())
        assertEquals(emptyList<Heard>(), heard)
    }

    @Test
    fun aLineObservedFromAnotherAgentIsASenderDispute() {
        witness.claimed(claim(alpha, "trade?"))
        witness.observed(observed(beta, "trade?"))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)

        val dispute = log.of(BodyEvent.WITNESS_DISPUTED).single()
        assertEquals(EventTag.DISPUTED_SPEECH_SENDER, dispute.outcome)
        assertEquals("agent-alpha", dispute.agentId)
        assertEquals(emptyList<Heard>(), heard)
    }

    @Test
    fun managedChatWithNoClaimIsUnclaimedAndAClaimNeverSeenIsUnobserved() {
        witness.observed(observed(beta, "spoof"))
        witness.claimed(claim(gamma, "lost"))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS)
        assertEquals(emptyList<String?>(), disputeTags())
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)

        assertEquals(setOf(EventTag.UNCLAIMED_SPEECH, EventTag.UNOBSERVED_SPEECH), disputeTags().toSet())
        assertEquals(emptyList<Heard>(), heard)
    }

    @Test
    fun aStrangerIsRecordedNowhere() {
        witness.observed(ObservedSpeech(UUID.randomUUID(), "Steve", "hello", place(1.0), TICK))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)

        assertEquals(emptyList<Any>(), log.records)
        assertEquals(emptyList<Heard>(), heard)
    }

    @Test
    fun deadOfflineAndDistantListenersHearNothingAndTheSpeakerNeverHearsItself() {
        dead += beta.agentId
        places[gamma.agentId] = null
        witness.claimed(claim(alpha, "anyone?"))
        witness.observed(observed(alpha, "anyone?"))
        assertEquals(emptyList<Heard>(), heard)

        dead.clear()
        places[gamma.agentId] = SpokenPlace("world_nether", 0.0, 64.0, 0.0)
        witness.claimed(claim(beta, "me"))
        witness.observed(observed(beta, "me"))
        assertEquals(listOf(alpha.agentId), heard.map { it.listener })
    }

    @Test
    fun pendingSpeechIsBoundedPerAgent() {
        repeat(MAX_PENDING_SPEECH_PER_AGENT + 2) { witness.claimed(claim(alpha, "line $it")) }

        assertEquals(2, disputeTags().size)
        assertTrue(disputeTags().all { it == EventTag.UNOBSERVED_SPEECH })
    }

    @Test
    fun forgettingAnAgentDropsItsPendingSpeechWithoutAVerdict() {
        witness.claimed(claim(alpha, "bye"))
        witness.forget(alpha.agentId)
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS + 1)
        witness.observed(observed(alpha, "bye"))

        assertEquals(emptyList<Any>(), log.of(BodyEvent.WORLD_INTERACTION))
    }

    @Test
    fun twoAgentsExchangeOneLineEachWithNoReplyLoop() {
        witness.claimed(claim(alpha, "hello beta"))
        witness.observed(observed(alpha, "hello beta"))
        witness.observed(observed(beta, "hello alpha"))
        witness.claimed(claim(beta, "hello alpha"))
        witness.tick(TICK + SPEECH_MATCH_WINDOW_TICKS * 2)

        assertEquals(
            listOf(
                Heard(beta.agentId, alpha.agentId, "LoomAlpha", "hello beta"),
                Heard(alpha.agentId, beta.agentId, "LoomBeta", "hello alpha"),
            ),
            heard,
        )
        assertEquals(2, log.of(BodyEvent.WORLD_INTERACTION).size)
        assertEquals(emptyList<Any>(), log.of(BodyEvent.WITNESS_DISPUTED))
    }

    private fun disputeTags(): List<String?> = log.of(BodyEvent.WITNESS_DISPUTED).map { it.outcome }

    private fun claim(
        who: ManagedIdentity,
        text: String,
    ) = SpeechClaimed(who.agentId, "perception-1", "control-1", text, TICK)

    private fun observed(
        who: ManagedIdentity,
        text: String,
    ) = ObservedSpeech(who.uuid, who.username, text, places[who.agentId] ?: place(0.0), TICK)

    private fun place(x: Double) = SpokenPlace("world", x, 64.0, 0.0)

    private companion object {
        const val TICK = 1_000L
    }
}
