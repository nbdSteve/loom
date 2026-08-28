package dev.loom.mind

import dev.loom.body.FASTEST_PERCEPTION_CADENCE_TICKS
import dev.loom.body.MAX_REQUESTS_IN_FLIGHT_PER_AGENT
import dev.loom.body.REQUEST_REFS_LIFETIME_TICKS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One server tick, at the twenty a second a server runs at. */
private val SERVER_TICK = 50.milliseconds

class CognitionSettingsTest {
    @Test
    @DisplayName("the mind is loopback by default: reaching it over a network takes saying so")
    fun theDefaultMindIsLocal() {
        assertEquals("127.0.0.1", CognitionSettings().host)
    }

    @Test
    @DisplayName("the read timeout expires before the bridge deadline")
    fun theReadGivesUpFirst() {
        // Load-bearing ordering, not a nicety: the bridge's watchdog frees the caller
        // but cannot free a worker parked in a socket read, so a read that outlived
        // the deadline would let a stalled mind consume every worker.
        for (timeout in listOf(50.milliseconds, 1.seconds, 30.seconds)) {
            val settings = CognitionSettings(timeout = timeout, connectTimeout = 10.milliseconds)
            assertTrue(
                settings.readTimeout < settings.timeout,
                "readTimeout ${settings.readTimeout} must be shorter than timeout $timeout",
            )
            assertTrue(settings.readTimeout.isPositive(), "readTimeout must remain usable at timeout $timeout")
        }
    }

    @Test
    @DisplayName("the default deadline outlasts the mind's own wait for a model")
    fun theDefaultDeadlineLeavesTheMindRoomToAnswer() {
        // A cross-runtime ordering no single side can check. The mind gives one
        // provider call 12s and then reports a bounded "the provider did not answer",
        // naming the setting to raise. That answer only reaches an operator if the
        // body is still waiting for it, so the body's read must give up second.
        // Restated here rather than read from the mind, because the two runtimes
        // share a contract and not a configuration; the Rust default it mirrors is
        // DEFAULT_TIMEOUT_MS in cognition/crates/cognition/src/openai/settings.rs.
        val mindWaitsForTheProvider = 12.seconds
        val settings = CognitionSettings()
        assertTrue(
            settings.readTimeout > mindWaitsForTheProvider,
            "readTimeout ${settings.readTimeout} must outlast the mind's own $mindWaitsForTheProvider, " +
                "or a stalled provider is reported as a stalled mind",
        )
    }

    @Test
    @DisplayName("a request's handles outlast the deadline its own decision is allowed")
    fun theRefWindowOutlastsTheBridgeDeadline() {
        // The other half of the same cross-package ordering, and the one relation neither
        // side can state alone: `dev.loom.body` may not depend on `dev.loom.mind`, so the
        // window's lifetime is a tick constant over there, and this is the only place both
        // numbers are visible at once.
        //
        // A window that closed first would refuse a decision Loom itself was still waiting
        // for - a meal or a flight naming a handle that was valid when the mind chose it,
        // recorded as a slot the body had forgotten. Raising the bridge deadline without
        // raising the lifetime fails here rather than in a run.
        val deadline = CognitionSettings().timeout
        val windowCloses = SERVER_TICK * REQUEST_REFS_LIFETIME_TICKS.toDouble()
        assertTrue(
            windowCloses > deadline,
            "handles held for $windowCloses must outlast the bridge deadline $deadline",
        )
    }

    @Test
    @DisplayName("the ceiling on held requests allows for a perception every scan")
    fun theInFlightCeilingCoversTheFastestPerceptionCadence() {
        // The other cross-package number `dev.loom.body` has to restate, and the one that
        // was wrong: the ceiling used to be justified by `THREAT_DEBOUNCE_TICKS`, but a
        // sighting is only debounced while nothing about it changes - a hostile stepping in
        // and out of line of sight earns one on every scan. So the fastest a perception can
        // reach one agent is the scan cadence, and the ceiling is derived from it.
        //
        // Shortening the scan without shortening this constant would let a hunted agent
        // outrun the ceiling and lose the window its goal review's decision needs.
        val restated = SERVER_TICK * FASTEST_PERCEPTION_CADENCE_TICKS.toDouble()
        assertTrue(
            restated <= THREAT_SCAN_CADENCE,
            "the body's $restated must be no longer than the scan cadence $THREAT_SCAN_CADENCE",
        )

        // And the ceiling has to hold every request that cadence can produce inside one
        // window, or it stops being a safety valve and becomes the rule again.
        val perLifetime = REQUEST_REFS_LIFETIME_TICKS / FASTEST_PERCEPTION_CADENCE_TICKS
        assertTrue(
            MAX_REQUESTS_IN_FLIGHT_PER_AGENT > perLifetime,
            "a ceiling of $MAX_REQUESTS_IN_FLIGHT_PER_AGENT must exceed the $perLifetime " +
                "perceptions one lifetime can hand out",
        )
    }

    @Test
    @DisplayName("a connect timeout that could eat the whole deadline is refused")
    fun aConnectTimeoutMustFitInsideTheDeadline() {
        assertThrows<IllegalArgumentException> {
            CognitionSettings(timeout = 1.seconds, connectTimeout = 1.seconds)
        }
        assertThrows<IllegalArgumentException> {
            CognitionSettings(timeout = 1.seconds, connectTimeout = 2.seconds)
        }
    }

    @Test
    @DisplayName("the read budget covers a whole ask, both attempts and both connects")
    fun theReadBudgetCoversEveryAttemptOfOneAsk() {
        // A transport may connect and read twice for one ask - it retries a request
        // that never got written. If the budget covered only one read, an ask could
        // outlive the deadline that already failed its caller, leaving a worker busy
        // while later requests are refused as saturated with capacity to spare.
        val settings = CognitionSettings(timeout = 5.seconds, connectTimeout = 500.milliseconds)
        // The shared constant rather than a restated 2, so this stays an assertion about
        // the attempts a transport may actually make.
        val worstCase = (settings.connectTimeout + settings.readTimeout) * ATTEMPTS_PER_ASK
        assertTrue(
            worstCase <= settings.timeout,
            "two connects plus two reads ($worstCase) must fit the deadline ${settings.timeout}",
        )
    }

    @Test
    @DisplayName("a timeout that narrows to a socket zero is refused rather than becoming unbounded")
    fun aTimeoutThatWouldMeanForeverIsRefused() {
        // A socket takes whole milliseconds and reads zero as "wait forever", so a
        // sub-millisecond setting inverts the property this class exists for: the
        // tightest-looking configuration would be the only unbounded one.
        assertThrows<IllegalArgumentException> {
            CognitionSettings(timeout = 1.seconds, connectTimeout = 999.microseconds)
        }
        // Positive, but its read share still rounds down to zero.
        assertThrows<IllegalArgumentException> {
            CognitionSettings(timeout = 3.milliseconds, connectTimeout = 1.milliseconds)
        }
        assertThrows<IllegalArgumentException> {
            CognitionSettings(timeout = 1.seconds, connectTimeout = Duration.ZERO)
        }
    }

    @Test
    @DisplayName("an unreachable address is refused at construction, not at the first request")
    fun anUnusableAddressIsRefusedUpFront() {
        assertThrows<IllegalArgumentException> { CognitionSettings(host = " ") }
        for (port in listOf(0, -1, 65_536)) {
            assertThrows<IllegalArgumentException>("port $port") { CognitionSettings(port = port) }
        }
    }
}
