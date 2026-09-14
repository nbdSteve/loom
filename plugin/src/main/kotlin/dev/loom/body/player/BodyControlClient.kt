/*
 * The bounded loopback client for one Mineflayer body-control connection.
 */
package dev.loom.body.player

import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyError
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.ErrorCode
import dev.loom.bodycontrol.MAX_BODY_CONTROL_FRAME_BYTES
import dev.loom.bodycontrol.decodeBodyError
import dev.loom.bodycontrol.decodeBodyResponse
import dev.loom.bodycontrol.encodeBodyRequest
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val REQUEST_PURPOSE_CHARS = 12
private const val REQUEST_ID_RADIX = 36

/** One authenticated connection to one body process. */
interface BodyControlConnection : AutoCloseable {
    val identity: PlayerIdentity

    /**
     * Sends one request and reads its one correlated answer.
     *
     * Implementations serialize calls because the protocol's duplicate-id bound and
     * one-line answers make a connection an ordered request/response session. Long
     * movement calls therefore own their connection; the player adapter keeps the
     * sense connection that issued its handles and uses short-lived connections for
     * lifecycle probes.
     */
    fun request(
        request: BodyRequest,
        timeout: Duration,
    ): BodyResponse

    override fun close()
}

/** Opens and handshakes one [BodyControlConnection]. */
fun interface BodyControlConnector {
    fun connect(
        endpoint: BodyControlEndpoint,
        identity: PlayerIdentity,
    ): BodyControlConnection
}

/** Stable request ids bounded to the control contract's 64-character envelope. */
class BodyControlRequestIds(
    private val sequence: AtomicLong = AtomicLong(),
) {
    fun next(purpose: String): String {
        val safePurpose =
            purpose
                .lowercase()
                .filter { it.isLetterOrDigit() }
                .take(REQUEST_PURPOSE_CHARS)
                .ifEmpty { "request" }
        return "$safePurpose-${sequence.incrementAndGet().toString(REQUEST_ID_RADIX)}"
    }
}

/** A closed, non-peer-controlled reason a control round trip failed. */
sealed class BodyControlFailure(
    message: String,
) : RuntimeException(message) {
    /** The process returned one of the protocol's closed refusal codes. */
    class Refused(
        val code: ErrorCode,
    ) : BodyControlFailure("the body refused the request as ${code.name.lowercase()}")

    /** The connection, timeout, correlation, or frame was unusable. */
    class Unavailable : BodyControlFailure("the body-control connection was unavailable")

    /** The peer answered with a valid frame that did not match the request. */
    class ProtocolViolation : BodyControlFailure("the body-control answer did not match its request")
}

/**
 * Production connector over a canonical loopback TCP socket.
 *
 * A successful return has already completed the mandatory first-frame handshake
 * and checked the body-reported username against the identity the server witness
 * registered. No caller can accidentally send gameplay before authentication.
 */
class SocketBodyControlConnector(
    private val connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val requestIds: BodyControlRequestIds = BodyControlRequestIds(),
) : BodyControlConnector {
    init {
        require(connectTimeout.isPositive()) { "connectTimeout must be positive" }
        require(requestTimeout.isPositive()) { "requestTimeout must be positive" }
    }

    override fun connect(
        endpoint: BodyControlEndpoint,
        identity: PlayerIdentity,
    ): BodyControlConnection {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                connectTimeout.asSocketMillis(),
            )
            val connection = SocketBodyControlConnection(socket, identity)
            val request =
                BodyRequest.Handshake(
                    protocol = BODY_CONTROL_PROTOCOL_VERSION,
                    requestId = requestIds.next("handshake"),
                    agentId = identity.agentId.value,
                )
            val answer = connection.request(request, requestTimeout)
            if (answer !is BodyResponse.HandshakeAccepted || answer.username != identity.username) {
                connection.close()
                throw BodyControlFailure.ProtocolViolation()
            }
            return connection
        } catch (failure: BodyControlFailure) {
            runCatching { socket.close() }
            throw failure
        } catch (_: Exception) {
            runCatching { socket.close() }
            throw BodyControlFailure.Unavailable()
        }
    }
}

private class SocketBodyControlConnection(
    private val socket: Socket,
    override val identity: PlayerIdentity,
) : BodyControlConnection {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val closed = AtomicBoolean()

    @Synchronized
    override fun request(
        request: BodyRequest,
        timeout: Duration,
    ): BodyResponse {
        check(!closed.get()) { "body-control connection is closed" }
        require(timeout.isPositive()) { "request timeout must be positive" }
        if (request.agentId != identity.agentId.value ||
            request.protocol != BODY_CONTROL_PROTOCOL_VERSION
        ) {
            throw BodyControlFailure.ProtocolViolation()
        }

        try {
            socket.soTimeout = timeout.asSocketMillis()
            val bytes = encodeBodyRequest(request).toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_BODY_CONTROL_FRAME_BYTES) {
                throw BodyControlFailure.ProtocolViolation()
            }
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
            val line = input.readBoundedFrame()
            val answer = decodeAnswer(line, request)
            if (!answer.matches(request)) {
                throw BodyControlFailure.ProtocolViolation()
            }
            return answer
        } catch (failure: BodyControlFailure) {
            throw failure
        } catch (_: Exception) {
            throw BodyControlFailure.Unavailable()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }
}

private fun decodeAnswer(
    line: String,
    request: BodyRequest,
): BodyResponse =
    runCatching { decodeBodyResponse(line) }.getOrNull()
        ?: decodeRefusal(line, request)

private fun decodeRefusal(
    line: String,
    request: BodyRequest,
): Nothing {
    val refusal: BodyError =
        runCatching { decodeBodyError(line) }.getOrElse {
            protocolViolation()
        }
    if (refusal.protocol != BODY_CONTROL_PROTOCOL_VERSION ||
        refusal.agentId != request.agentId ||
        refusal.requestId != request.requestId
    ) {
        protocolViolation()
    }
    throw BodyControlFailure.Refused(refusal.error)
}

private fun BodyResponse.matches(request: BodyRequest): Boolean =
    protocol == request.protocol &&
        requestId == request.requestId &&
        agentId == request.agentId &&
        when (request) {
            is BodyRequest.Handshake -> this is BodyResponse.HandshakeAccepted
            is BodyRequest.Status -> this is BodyResponse.StatusReport
            is BodyRequest.Shutdown -> this is BodyResponse.ShutdownAccepted
            is BodyRequest.Sense -> this is BodyResponse.SenseReport
            is BodyRequest.Eat -> this is BodyResponse.EatReport
            is BodyRequest.MoveTo -> this is BodyResponse.MoveReport
            is BodyRequest.Flee -> this is BodyResponse.FleeReport
            is BodyRequest.Halt -> this is BodyResponse.HaltReport
        }

private fun BufferedInputStream.readBoundedFrame(): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) unavailable()
        if (next == '\n'.code) break
        if (bytes.size() == MAX_BODY_CONTROL_FRAME_BYTES) {
            protocolViolation()
        }
        bytes.write(next)
    }
    val raw =
        bytes.toByteArray().let { frame ->
            if (frame.lastOrNull() == '\r'.code.toByte()) frame.copyOf(frame.size - 1) else frame
        }
    return decodeFrame(raw)
}

private fun decodeFrame(raw: ByteArray): String =
    runCatching {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    }.getOrElse { protocolViolation() }

private fun protocolViolation(): Nothing = throw BodyControlFailure.ProtocolViolation()

private fun unavailable(): Nothing = throw BodyControlFailure.Unavailable()

private fun Duration.isPositive(): Boolean = !isNegative && !isZero

private fun Duration.asSocketMillis(): Int =
    toMillis()
        .coerceAtLeast(1)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
