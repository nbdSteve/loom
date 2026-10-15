package dev.loom.witness.bukkit

import dev.loom.body.BodyEvent
import dev.loom.witness.Heard
import dev.loom.witness.RecordingLog
import dev.loom.witness.SpeechClaimed
import dev.loom.witness.SpeechJournal
import dev.loom.witness.SpeechWitness
import dev.loom.witness.SpokenPlace
import dev.loom.witness.managed
import dev.loom.witness.registryOf
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class SpeechListenerTest {
    private val alpha = managed("agent-alpha", "LoomAlpha")
    private val beta = managed("agent-beta", "LoomBeta")
    private val registry = registryOf(alpha, beta)
    private val log = RecordingLog()
    private val heard = ArrayList<Heard>()
    private val mainQueue = ArrayDeque<() -> Unit>()
    private val worldReads = ArrayList<String>()
    private var onMain = false
    private var speakerOnline = true
    private val witness =
        SpeechWitness(
            registry = registry,
            journal = SpeechJournal(log),
            placeOf = { SpokenPlace("world", 1.0, 64.0, 0.0) },
            isDead = { false },
            hear = { heard += it },
        )
    private val listener =
        SpeechListener(
            registry,
            witness,
            main = { task -> mainQueue += task },
            playerOf = { uuid -> if (uuid == alpha.uuid && speakerOnline) player() else null },
            currentTick = { TICK },
        )

    @Test
    fun `the async chat thread reads no world state and the place is read on the main thread`() {
        listener.chatted(alpha.uuid, alpha.username, "hello")
        assertEquals(emptyList<String>(), worldReads, "nothing was read off the main thread")
        assertTrue(log.records.isEmpty())

        drainMain()
        witness.claimed(SpeechClaimed(alpha.agentId, "perception-1", "control-1", "hello", TICK))

        assertEquals(listOf("main"), worldReads.distinct())
        assertEquals(listOf(beta.agentId), heard.map { it.listener })
    }

    @Test
    fun `a stranger's chat is never scheduled`() {
        listener.chatted(UUID.randomUUID(), "Steve", "hello")
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `a speaker gone by the main-thread read is still attributed but nobody hears it`() {
        listener.chatted(alpha.uuid, alpha.username, "hello")
        speakerOnline = false
        drainMain()
        witness.claimed(SpeechClaimed(alpha.agentId, "perception-1", "control-1", "hello", TICK))

        assertEquals(1, log.records.count { it.event == BodyEvent.WORLD_INTERACTION })
        assertTrue(heard.isEmpty())
    }

    private fun drainMain() {
        onMain = true
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
        onMain = false
    }

    private fun player(): Player {
        val world =
            Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                if (method.name == "getName") "world" else null
            } as World
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "isOnline" -> true
                "isDead" -> false
                "getLocation" -> {
                    worldReads += if (onMain) "main" else "async"
                    Location(world, 0.0, 64.0, 0.0)
                }
                else -> null
            }
        } as Player
    }

    private companion object {
        const val TICK = 1_000L
    }
}
