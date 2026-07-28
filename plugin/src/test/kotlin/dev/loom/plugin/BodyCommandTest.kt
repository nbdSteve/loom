package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.body.WORLD_AGENT_ID
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

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
            ): IntentOutcome = fail("a refused agent id must never reach the port")

            override fun perceive(
                agentId: AgentId,
                trigger: Trigger,
            ): Perception? = fail("a refused agent id must never reach the port")

            override fun tick(tick: Long) = fail("a refused agent id must never reach the port")

            override fun liveBodies(): Set<AgentId> = fail("a refused agent id must never reach the port")
        }

    @Test
    @DisplayName("the reserved world id gets the domain's own refusal as a reply, on every subcommand")
    fun reservedWorldIdIsRefusedAsAReply() {
        val command = BodyCommand(untouchable) { }
        val refusal = assertThrows<IllegalArgumentException> { AgentId(WORLD_AGENT_ID) }.message
        val here = BodyLocation("world", 0.0, 0.0, 0.0)
        val subcommands =
            listOf(
                arrayOf("spawn", WORLD_AGENT_ID),
                arrayOf("move", WORLD_AGENT_ID, "1", "2", "3"),
                arrayOf("perceive", WORLD_AGENT_ID),
                arrayOf("despawn", WORLD_AGENT_ID),
            )
        for (args in subcommands) {
            assertEquals(refusal, command.reply(args, here), "subcommand ${args.first()}")
        }
    }
}
