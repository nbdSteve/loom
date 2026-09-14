package dev.loom.body.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlayerBodyFleetSettingsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `allocation is stable across restart and independent of configured order`() {
        val first = settings("charlie,alpha,bravo")
        val restarted = settings("bravo,charlie,alpha")

        assertEquals(
            first.deployments.map(PlayerBodyDeployment::allocation),
            restarted.deployments.map(PlayerBodyDeployment::allocation),
        )
        assertEquals(
            listOf("alpha", "bravo", "charlie"),
            first.deployments.map { it.identity.agentId.value },
        )
        assertEquals(listOf(31_000, 31_001, 31_002), first.deployments.map { it.settings.controlEndpoint.port })
        assertEquals(3, first.deployments.map { it.identity.username }.distinct().size)
        assertEquals(3, first.deployments.map { it.identity.uuid }.distinct().size)
        assertEquals(3, first.deployments.map { it.settings.processDirectory }.distinct().size)
        assertTrue(first.deployments.all { it.settings.processDirectory.parent == temporary.resolve("run") })
    }

    @Test
    fun `adding an agent cannot silently share any allocated resource`() {
        val planned = settings("alpha,bravo")
        val alpha = planned.deployments.first()
        val bravo = planned.deployments.last()

        assertNotEquals(alpha.identity.username, bravo.identity.username)
        assertNotEquals(alpha.identity.uuid, bravo.identity.uuid)
        assertNotEquals(alpha.settings.controlEndpoint, bravo.settings.controlEndpoint)
        assertNotEquals(alpha.settings.processDirectory, bravo.settings.processDirectory)

        assertThrows<IllegalArgumentException> {
            PlayerBodyFleetSettings(
                deployments =
                    listOf(
                        alpha,
                        bravo.copy(
                            settings =
                                bravo.settings.copy(
                                    controlEndpoint = alpha.settings.controlEndpoint,
                                ),
                        ),
                    ),
                startupConcurrency = 1,
                ioWorkersPerAgent = 2,
            )
        }
    }

    @Test
    fun `population ports and resource knobs are bounded at configuration time`() {
        assertThrows<IllegalArgumentException> { settings("alpha,alpha") }
        assertThrows<IllegalArgumentException> {
            settings((1..17).joinToString(",") { "agent-$it" })
        }
        assertThrows<IllegalArgumentException> {
            settings("alpha,bravo", "LOOM_BODY_CONTROL_PORT" to "65535")
        }
        assertThrows<IllegalArgumentException> {
            settings("alpha", "LOOM_BODY_CONTROL_PORT" to "25565")
        }
        assertThrows<IllegalArgumentException> {
            settings("alpha", "LOOM_BODY_STARTUP_CONCURRENCY" to "2")
        }
        assertThrows<IllegalArgumentException> {
            settings("alpha", "LOOM_BODY_STARTUP_ATTEMPTS" to "4")
        }
        assertThrows<IllegalArgumentException> {
            settings("alpha", "LOOM_BODY_HEALTH_FAILURES" to "0")
        }
    }

    private fun settings(
        agents: String,
        vararg overrides: Pair<String, String>,
    ): PlayerBodyFleetSettings {
        val environment =
            mutableMapOf(
                "LOOM_BODY_AGENTS" to agents,
                "LOOM_BODY_DIR" to temporary.resolve("body").toString(),
                "LOOM_BODY_PROCESS_ROOT" to temporary.resolve("run").toString(),
                "LOOM_BODY_CONTROL_PORT" to "31000",
            )
        environment.putAll(overrides)
        return PlayerBodyFleetSettings.fromEnvironment(
            serverPort = 25_565,
            dataDirectory = temporary.resolve("data").toAbsolutePath(),
            workingDirectory = temporary.toAbsolutePath(),
            environment = environment,
        )
    }
}

private fun PlayerBodyDeployment.allocation(): List<String> =
    listOf(
        identity.agentId.value,
        identity.username,
        identity.uuid.toString(),
        settings.controlEndpoint.toString(),
        settings.processDirectory.toString(),
    )
