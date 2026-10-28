package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyLifecycle
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BlockFace
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The newest decision governs its agent's body for every action, against r39 iota's three refused placements. */
class PlayerPreemptionTest {
    private val runtime = PlayerRuntime(playerIdentityOf(AgentId("tribe-iota")), BodyLifecycle.READY, "ready")
    private val place =
        BodyRequest.Place(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = "place-1",
            agentId = "tribe-iota",
            senseId = "sense-v0",
            block = 0,
            slot = 36,
            face = BlockFace.UP,
            timeoutMs = 20_000,
        )

    private var ids = 1
    private val halts = mutableListOf<String>()

    /** How many times [code] was answered before the refusal stood. */
    private fun refusedTries(
        code: ErrorCode,
        newest: NewestDecisions = NewestDecisions(),
    ): Int {
        var tries = 0
        assertThrows(BodyControlFailure.Refused::class.java) {
            preemption(newest).send(runtime, "sense-v0", place) {
                tries += 1
                refuse(code)
            }
        }
        return tries
    }

    private fun refuse(code: ErrorCode): Nothing = throw BodyControlFailure.Refused(code)

    private fun preemption(newest: NewestDecisions = NewestDecisions()) =
        PlayerPreemption(newest, { purpose -> "$purpose-${++ids}" }) { halts += it.identity.agentId.value }

    @Test
    fun `a request its agent's own earlier work refuses halts that work once and is sent again under fresh ids`() {
        val sent = mutableListOf<String>()
        val (request, answer) =
            preemption().send(runtime, "sense-v0", place) {
                sent += it.requestId
                if (sent.size < 3) refuse(ErrorCode.NOT_READY)
                "placed"
            }
        assertEquals("placed", answer)
        assertEquals(listOf("place-1", "place-2", "place-3"), sent)
        assertEquals("place-3", request.requestId)
        assertEquals(listOf("tribe-iota"), halts, "the earlier work is halted once, then given time to settle")
    }

    @Test
    fun `a refusal stands once a newer decision of the agent's own governs, for any other code, or past the bound`() {
        val newest =
            NewestDecisions().also {
                it.asked("sense-v0")
                it.asked("sense-vb")
                it.applied(AgentId("tribe-iota"), "sense-vb")
            }
        assertEquals(1, refusedTries(ErrorCode.NOT_READY, newest), "the newer decision holds the body")
        assertEquals(1, refusedTries(ErrorCode.UNKNOWN_OPERATION))
        assertEquals(PREEMPT_ATTEMPTS, refusedTries(ErrorCode.NOT_READY))
    }

    @Test
    fun `a decision is dated by when its perception was asked, never by when its answer arrived`() {
        // The r39 offline audit: review v0 was asked before sighting vb, vb's flight was applied, and
        // v0's answer arrived after it. Dated by arrival v0 governed and halted the flight.
        val iota = AgentId("tribe-iota")
        val newest =
            NewestDecisions().also {
                it.asked("sense-v0")
                it.asked("sense-vb")
                it.applied(iota, "sense-vb")
                it.applied(iota, "sense-v0")
            }
        assertEquals(true, newest.outdated(iota, "sense-v0"))
        assertEquals(false, newest.outdated(iota, "sense-vb"), "the later-asked flight still governs")
        assertEquals(1, refusedTries(ErrorCode.NOT_READY, newest), "the late answer is refused, never retried")
        assertEquals(emptyList<String>(), halts, "and halts nothing of the newer flight's")
        newest.asked("sense-vc")
        newest.applied(iota, "sense-vc")
        assertEquals(true, newest.outdated(iota, "sense-vb"), "a decision asked later governs once applied")
    }
}
