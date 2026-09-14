package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyInspection
import dev.loom.body.BodyLifecycle
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.Trigger
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.mind.perception
import dev.loom.mind.silentJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture

class BodyCommandTest {
    private val scheduled = ArrayDeque<() -> Unit>()
    private val main = MainThread { task -> scheduled.add(task) }

    private fun drain() {
        while (scheduled.isNotEmpty()) scheduled.removeFirst().invoke()
    }

    @Test
    @DisplayName("create starts the identity asynchronously and reports the ready boundary")
    fun createManagesThePlayerIdentity() {
        val body = SpyBody()
        val reported = mutableListOf<String>()
        val command = commandFor(body, reported::add)

        assertEquals("starting player body for agent-alpha", command.reply(arrayOf("create", "agent-alpha")))
        assertEquals(listOf("spawn:agent-alpha"), body.calls)
        assertEquals(listOf("body ready for agent-alpha"), reported)
    }

    @Test
    @DisplayName("inspect reports the stable username uuid endpoint and lifecycle")
    fun inspectReportsIdentity() {
        val body = SpyBody()
        body.inspection =
            BodyInspection(
                agentId = AgentId("agent-alpha"),
                identity = "loom_1234567890 (00000000-0000-0000-0000-000000000001)",
                state = BodyLifecycle.READY,
                endpoint = "127.0.0.1:25585",
                detail = "ready",
            )

        val reply = commandFor(body).reply(arrayOf("inspect", "agent-alpha"))

        assertEquals(
            "agent-alpha: loom_1234567890 (00000000-0000-0000-0000-000000000001) " +
                "is ready at 127.0.0.1:25585 (ready)",
            reply,
        )
    }

    @Test
    @DisplayName("think starts one goal review without waiting and applies its decision later")
    fun thinkDrivesTheRoundTrip() {
        val body = SpyBody()
        val reported = mutableListOf<String>()
        val command = commandFor(body, reported::add)

        assertEquals(
            "requested a goal review for agent-alpha",
            command.reply(arrayOf("think", "agent-alpha")),
        )
        assertTrue(body.applied.isEmpty(), "the command must not wait for cognition")

        drain()

        assertEquals(listOf<Intent>(Intent.Idle), body.applied)
        assertTrue(reported.single().contains("sent ROUTINE perception req-1"), reported.toString())
    }

    @Test
    @DisplayName("stop requests orderly process shutdown and reports completion")
    fun stopOwnsCleanup() {
        val body = SpyBody()
        val reported = mutableListOf<String>()
        val command = commandFor(body, reported::add)

        assertEquals("stopping player body for agent-alpha", command.reply(arrayOf("stop", "agent-alpha")))
        assertEquals(listOf("despawn:agent-alpha"), body.calls)
        assertEquals(listOf("stopped body for agent-alpha"), reported)
    }

    @Test
    @DisplayName("the four identity operations state their own usage")
    fun missingIdentityStatesUsage() {
        val command = commandFor(SpyBody())
        assertEquals("usage: create <agentId>", command.reply(arrayOf("create")))
        assertEquals("usage: inspect <agentId>", command.reply(arrayOf("inspect")))
        assertEquals("usage: think <agentId>", command.reply(arrayOf("think")))
        assertEquals("usage: stop <agentId>", command.reply(arrayOf("stop")))
        assertEquals(null, command.reply(arrayOf("move")))
    }

    private fun commandFor(
        body: SpyBody,
        report: (String) -> Unit = {},
    ): BodyCommand {
        val cognition =
            EmbodiedCognition(
                bodies = body,
                mind = { seen ->
                    CompletableFuture.completedFuture(
                        Decision(
                            requestId = seen.requestId,
                            agentId = seen.agentId,
                            intent = Intent.Idle,
                            reasoning = "test",
                            provenance =
                                Provenance(
                                    tier = seen.importance,
                                    model = "test",
                                    promptVersion = "test-v1",
                                ),
                        ),
                    )
                },
                mainThread = main,
                journal = silentJournal(),
            )
        return BodyCommand(body, cognition, report)
    }

    private class SpyBody : BodyPort {
        val calls = mutableListOf<String>()
        val applied = mutableListOf<Intent>()
        var inspection: BodyInspection? = null

        override fun spawn(agentId: AgentId): CompletableFuture<BodySpawnResult> {
            calls += "spawn:$agentId"
            return CompletableFuture.completedFuture(BodySpawnResult.Spawned(agentId))
        }

        override fun despawn(agentId: AgentId): CompletableFuture<Boolean> {
            calls += "despawn:$agentId"
            return CompletableFuture.completedFuture(true)
        }

        override fun apply(
            agentId: AgentId,
            intent: Intent,
            requestId: String?,
        ): CompletableFuture<IntentOutcome> {
            applied += intent
            return CompletableFuture.completedFuture(IntentOutcome.Accepted("applied"))
        }

        override fun perceive(
            agentId: AgentId,
            trigger: Trigger,
        ): CompletableFuture<Perception?> =
            CompletableFuture.completedFuture(
                perception(
                    requestId = "req-1",
                    agentId = agentId.value,
                    trigger = trigger,
                    importance = DecisionImportance.ROUTINE,
                ),
            )

        override fun senseThreat(agentId: AgentId): CompletableFuture<Perception?> =
            fail("the command never performs a threat sweep")

        override fun tick(tick: Long) = fail("the command never drives the tick")

        override fun liveBodies(): Set<AgentId> = emptySet()

        override fun inspect(agentId: AgentId): BodyInspection? = inspection
    }
}
