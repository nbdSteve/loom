/*
 * The JVM side of one real body-control socket.
 *
 * The peer is deliberately a ServerSocket rather than a connector fake: framing,
 * handshake ordering, correlation, refusal decoding and the byte bound are the
 * properties under test, and none exists in a mock method call.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyError
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.ErrorCode
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SayOutcome
import dev.loom.bodycontrol.decodeBodyRequest
import dev.loom.bodycontrol.encodeBodyError
import dev.loom.bodycontrol.encodeBodyResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val CLIENT_TEST_TIMEOUT: Duration = Duration.ofSeconds(2)

private fun clientIdentity(): PlayerIdentity =
    PlayerIdentity(
        AgentId("agent-client"),
        "loom_0123456789",
        UUID.fromString("00000000-0000-3000-8000-000000000001"),
    )

private class ControlPeer(
    handler: (BufferedReader, BufferedWriter) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val finished = CompletableFuture<Unit>()
    private val thread =
        Thread(
            {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = CLIENT_TEST_TIMEOUT.toMillis().toInt()
                        val input =
                            BufferedReader(
                                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                            )
                        val output =
                            BufferedWriter(
                                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                            )
                        handler(input, output)
                    }
                    finished.complete(Unit)
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            },
            "body-control-test-peer",
        ).apply {
            isDaemon = true
            start()
        }

    val endpoint: BodyControlEndpoint = BodyControlEndpoint("127.0.0.1", server.localPort)

    override fun close() {
        runCatching { server.close() }
        finished.get(CLIENT_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        thread.join(CLIENT_TEST_TIMEOUT.toMillis())
    }
}

private fun BufferedReader.request(): BodyRequest =
    decodeBodyRequest(requireNotNull(readLine()) { "the client closed before its request" })

private fun BufferedWriter.answer(response: BodyResponse) {
    write(encodeBodyResponse(response))
    newLine()
    flush()
}

private fun BufferedWriter.refuse(error: BodyError) {
    write(encodeBodyError(error))
    newLine()
    flush()
}

private fun handshake(
    input: BufferedReader,
    output: BufferedWriter,
    username: String = clientIdentity().username,
): BodyRequest.Handshake {
    val request = input.request() as BodyRequest.Handshake
    output.answer(
        BodyResponse.HandshakeAccepted(
            protocol = request.protocol,
            requestId = request.requestId,
            agentId = request.agentId,
            state = LifecycleState.CONNECTING,
            username = username,
        ),
    )
    return request
}

class BodyControlClientTest {
    private val connector =
        SocketBodyControlConnector(
            connectTimeout = CLIENT_TEST_TIMEOUT,
            requestTimeout = CLIENT_TEST_TIMEOUT,
        )

    @Test
    fun `a connection handshakes before sending its first requested operation`() {
        val identity = clientIdentity()
        ControlPeer { input, output ->
            val agreed = handshake(input, output)
            assertEquals(identity.agentId.value, agreed.agentId)

            val status = input.request() as BodyRequest.Status
            output.answer(
                BodyResponse.StatusReport(
                    protocol = status.protocol,
                    requestId = status.requestId,
                    agentId = status.agentId,
                    state = LifecycleState.READY,
                    uptimeMs = 20,
                    stateMs = 10,
                    requestsHandled = 2,
                ),
            )
        }.use { peer ->
            connector.connect(peer.endpoint, identity).use { connection ->
                val request =
                    BodyRequest.Status(
                        BODY_CONTROL_PROTOCOL_VERSION,
                        "status-1",
                        identity.agentId.value,
                    )
                val response = connection.request(request, CLIENT_TEST_TIMEOUT)

                assertTrue(response is BodyResponse.StatusReport)
                assertEquals("status-1", response.requestId)
                assertEquals(LifecycleState.READY, response.state)
            }
        }
    }

    @Test
    fun `a say report over a real socket is the answer to its say`() {
        val identity = clientIdentity()
        ControlPeer { input, output ->
            handshake(input, output)
            val say = input.request() as BodyRequest.Say
            assertEquals("h\u00e9llo river", say.text)
            output.answer(
                BodyResponse.SayReport(
                    protocol = say.protocol,
                    requestId = say.requestId,
                    agentId = say.agentId,
                    state = LifecycleState.READY,
                    outcome = SayOutcome.SENT,
                ),
            )
        }.use { peer ->
            connector.connect(peer.endpoint, identity).use { connection ->
                val request =
                    BodyRequest.Say(
                        BODY_CONTROL_PROTOCOL_VERSION,
                        "say-1",
                        identity.agentId.value,
                        "h\u00e9llo river",
                    )
                val response = connection.request(request, CLIENT_TEST_TIMEOUT)

                assertEquals(SayOutcome.SENT, (response as BodyResponse.SayReport).outcome)
            }
        }
    }

    @Test
    fun `a closed protocol refusal remains a typed refusal`() {
        val identity = clientIdentity()
        ControlPeer { input, output ->
            handshake(input, output)
            val status = input.request()
            output.refuse(
                BodyError(
                    protocol = status.protocol,
                    requestId = status.requestId,
                    agentId = status.agentId,
                    error = ErrorCode.NOT_READY,
                ),
            )
        }.use { peer ->
            connector.connect(peer.endpoint, identity).use { connection ->
                val failure =
                    assertThrows<BodyControlFailure.Refused> {
                        connection.request(
                            BodyRequest.Status(
                                BODY_CONTROL_PROTOCOL_VERSION,
                                "status-2",
                                identity.agentId.value,
                            ),
                            CLIENT_TEST_TIMEOUT,
                        )
                    }
                assertEquals(ErrorCode.NOT_READY, failure.code)
            }
        }
    }

    @Test
    fun `a handshake for another username is never accepted as this player`() {
        ControlPeer { input, output ->
            handshake(input, output, username = "loom_aaaaaaaaaa")
        }.use { peer ->
            assertThrows<BodyControlFailure.ProtocolViolation> {
                connector.connect(peer.endpoint, clientIdentity())
            }
        }
    }

    @Test
    fun `an answer with another request identity is a protocol violation`() {
        val identity = clientIdentity()
        ControlPeer { input, output ->
            handshake(input, output)
            val status = input.request()
            output.answer(
                BodyResponse.StatusReport(
                    protocol = status.protocol,
                    requestId = "another-request",
                    agentId = status.agentId,
                    state = LifecycleState.READY,
                    uptimeMs = 20,
                    stateMs = 10,
                    requestsHandled = 2,
                ),
            )
        }.use { peer ->
            connector.connect(peer.endpoint, identity).use { connection ->
                assertThrows<BodyControlFailure.ProtocolViolation> {
                    connection.request(
                        BodyRequest.Status(
                            BODY_CONTROL_PROTOCOL_VERSION,
                            "status-3",
                            identity.agentId.value,
                        ),
                        CLIENT_TEST_TIMEOUT,
                    )
                }
            }
        }
    }

    @Test
    fun `a frame past the body-control byte bound is refused before decoding`() {
        val identity = clientIdentity()
        ControlPeer { input, output ->
            handshake(input, output)
            input.request()
            output.write("x".repeat(8_193))
            output.newLine()
            output.flush()
        }.use { peer ->
            connector.connect(peer.endpoint, identity).use { connection ->
                assertThrows<BodyControlFailure.ProtocolViolation> {
                    connection.request(
                        BodyRequest.Status(
                            BODY_CONTROL_PROTOCOL_VERSION,
                            "status-4",
                            identity.agentId.value,
                        ),
                        CLIENT_TEST_TIMEOUT,
                    )
                }
            }
        }
    }
}
