package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.body.WORLD_AGENT_ID
import dev.loom.contract.DayPhase
import dev.loom.contract.DecisionImportance
import dev.loom.contract.ENERGY_FULL
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import dev.loom.contract.WorldState
import dev.loom.mind.CognitionBridge
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.mind.silentJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import java.util.concurrent.CompletableFuture
import dev.loom.contract.Decision as ContractDecision

class BodyCommandTest {
    /** A port no command handling this test exercises may ever reach. */
    private val untouchable =
        object : BodyPort {
            override fun spawn(
                agentId: AgentId,
                at: BodyLocation,
            ): BodySpawnResult = fail("a refused agent id must never reach the port")

            override fun despawn(agentId: AgentId): Boolean = fail("a refused agent id must never reach the port")

            override fun apply(
                agentId: AgentId,
                intent: Intent,
                requestId: String?,
            ): IntentOutcome = fail("a refused agent id must never reach the port")

            override fun perceive(
                agentId: AgentId,
                trigger: Trigger,
            ): Perception? = fail("a refused agent id must never reach the port")

            override fun tick(tick: Long) = fail("a refused agent id must never reach the port")

            override fun liveBodies(): Set<AgentId> = fail("a refused agent id must never reach the port")
        }

    /** A mind that must not be consulted: used where cognition would be a bug. */
    private val unreachableMind =
        EmbodiedCognition(
            untouchable,
            mind = { fail("cognition must not be reached") },
            mainThread = { fail("nothing should be scheduled") },
            journal = silentJournal(),
        )

    @Test
    @DisplayName("the reserved world id gets the domain's own refusal as a reply, on every subcommand")
    fun reservedWorldIdIsRefusedAsAReply() {
        val command = BodyCommand(untouchable, unreachableMind) { }
        val refusal = assertThrows<IllegalArgumentException> { AgentId(WORLD_AGENT_ID) }.message
        val here = BodyLocation("world", 0.0, 0.0, 0.0)
        val subcommands =
            listOf(
                arrayOf("spawn", WORLD_AGENT_ID),
                arrayOf("move", WORLD_AGENT_ID, "1", "2", "3"),
                arrayOf("perceive", WORLD_AGENT_ID),
                arrayOf("think", WORLD_AGENT_ID),
                arrayOf("despawn", WORLD_AGENT_ID),
            )
        for (args in subcommands) {
            assertEquals(refusal, command.reply(args, here), "subcommand ${args.first()}")
        }
    }

    @Test
    @DisplayName("think drives the whole round trip and the decided intent reaches the body")
    fun thinkDrivesARoundTrip() {
        val body = SpyBody()
        val scheduled = mutableListOf<() -> Unit>()
        val mind =
            EmbodiedCognition(
                body,
                mind = FakeMind(Intent.Say("hello")),
                mainThread = MainThread { scheduled.add(it) },
                journal = silentJournal(),
            )
        val reported = mutableListOf<String>()
        val command = BodyCommand(body, mind) { reported.add(it) }

        val reply = command.reply(arrayOf("think", "agent-alpha"), null)

        // The reply describes only what has happened by the time the command returns:
        // a perception is on its way. Nothing has been applied yet, because the whole
        // point is that the command did not wait for the mind.
        assertTrue(reply!!.contains("sent"), reply)
        assertTrue(reply.contains("agent-alpha"), reply)
        assertTrue(body.applied.isEmpty(), "think must not wait for the decision")

        scheduled.forEach { it() }

        assertEquals(listOf<Intent>(Intent.Say("hello")), body.applied)
    }

    @Test
    @DisplayName("think says so when the agent has no body, and does not consult the mind")
    fun thinkNeedsABody() {
        val body = SpyBody(perceives = false)
        val mind = EmbodiedCognition(body, { fail("no body, no cognition") }, { }, silentJournal())
        val command = BodyCommand(body, mind) { }

        assertEquals("no body for agent-alpha", command.reply(arrayOf("think", "agent-alpha"), null))
    }

    @Test
    @DisplayName("think without an agent id explains itself")
    fun thinkStatesItsUsage() {
        assertEquals(
            "usage: think <agentId>",
            BodyCommand(untouchable, unreachableMind) { }.reply(arrayOf("think"), null),
        )
    }

    @Test
    @DisplayName("the reply names the tier the perception was classified at")
    fun thinkReportsTheTier() {
        val body = SpyBody()
        val command = BodyCommand(body, EmbodiedCognition(body, FakeMind(Intent.Idle), { }, silentJournal())) { }

        val reply = command.reply(arrayOf("think", "agent-alpha"), null)

        // Visible in the console so the operator can see what a round trip cost
        // before the decision itself comes back. `think` uses a goal review, which
        // is the routine tier.
        assertTrue(reply!!.contains(DecisionImportance.ROUTINE.name), reply)
    }

    /** A mind that answers instantly with [intent], echoing the perception's ids. */
    private class FakeMind(
        private val intent: Intent,
    ) : CognitionBridge {
        override fun submit(perception: Perception): CompletableFuture<ContractDecision> =
            CompletableFuture.completedFuture(
                ContractDecision(
                    requestId = perception.requestId,
                    agentId = perception.agentId,
                    intent = intent,
                    reasoning = "test",
                    provenance =
                        Provenance(
                            tier = perception.importance,
                            model = "test-model",
                            promptVersion = "test-v1",
                        ),
                ),
            )
    }

    /** A body that perceives on demand and records what it was told to do. */
    private class SpyBody(
        private val perceives: Boolean = true,
    ) : BodyPort {
        val applied = mutableListOf<Intent>()

        override fun spawn(
            agentId: AgentId,
            at: BodyLocation,
        ): BodySpawnResult = fail("not part of this test")

        override fun despawn(agentId: AgentId): Boolean = fail("not part of this test")

        override fun apply(
            agentId: AgentId,
            intent: Intent,
            requestId: String?,
        ): IntentOutcome {
            applied.add(intent)
            return IntentOutcome.Accepted("applied $intent")
        }

        override fun perceive(
            agentId: AgentId,
            trigger: Trigger,
        ): Perception? =
            if (!perceives) {
                null
            } else {
                Perception(
                    requestId = "req-1",
                    agentId = agentId.value,
                    // The trigger it was handed, as a real body reports it.
                    trigger = trigger,
                    importance = DecisionImportance.ROUTINE,
                    capturedTick = 1L,
                    selfState =
                        SelfState(
                            health = 20.0,
                            energy = ENERGY_FULL,
                            position = listOf(0.0, 64.0, 0.0),
                            inventorySummary = emptyList(),
                            world = WorldState(dayPhase = DayPhase.DAY, isDay = true, ticksUntilNightfall = 6_000),
                        ),
                    surroundings = emptyList(),
                )
            }

        override fun tick(tick: Long) = fail("the driver command does not tick")

        override fun liveBodies(): Set<AgentId> = emptySet()
    }
}
