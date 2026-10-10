package dev.loom.plugin

import dev.loom.body.player.MAX_CONFIGURED_AGENTS
import org.bukkit.Server
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitInteractionEventsTest {
    @Test
    fun `interaction evidence admits only the bounded configured fleet`() {
        assertDoesNotThrow {
            BukkitInteractionEvents(serverStub(), identities(MAX_CONFIGURED_AGENTS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BukkitInteractionEvents(serverStub(), identities(MAX_CONFIGURED_AGENTS + 1))
        }
    }
}

private fun identities(count: Int): Set<UUID> =
    (0 until count)
        .map { index -> UUID.nameUUIDFromBytes("interaction-$index".toByteArray()) }
        .toSet()

private fun serverStub(): Server =
    Proxy.newProxyInstance(
        Server::class.java.classLoader,
        arrayOf(Server::class.java),
    ) { _, _, _ -> null } as Server
