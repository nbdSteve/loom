package dev.loom.plugin

import com.destroystokyo.paper.profile.PlayerProfile
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private val AGENT = UUID.nameUUIDFromBytes("agent".toByteArray())
private val OBSERVER = UUID.nameUUIDFromBytes("observer".toByteArray())
private val STRANGER = UUID.nameUUIDFromBytes("stranger".toByteArray())

private inline fun <reified T> stub(crossinline answer: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> T::class.java.simpleName
            else -> answer(method.name, args ?: emptyArray())
        }
    } as T

/** A live player that records what was done to it. */
private class FakePlayer(
    val uuid: UUID,
    val username: String,
) {
    var op = true
    var mode = GameMode.SURVIVAL
    var affectsSpawning = true
    val permissions = mutableListOf<String>()
    val hidden = mutableListOf<UUID>()
    val told = mutableListOf<Component>()

    val player: Player =
        stub { name, args ->
            when (name) {
                "getUniqueId" -> uuid
                "getName" -> username
                "isOp" -> op
                "setOp" -> op = args[0] as Boolean
                "getGameMode" -> mode
                "setGameMode" -> mode = args[0] as GameMode
                "setAffectsSpawning" -> affectsSpawning = args[0] as Boolean
                "addAttachment" -> {
                    if (args.size == 3 && args[2] == true) permissions += args[1] as String
                    null
                }
                "hidePlayer" -> hidden += (args[1] as Player).uniqueId
                "sendMessage" -> told += args[0] as Component
                else -> null
            }
        }
}

private class Rig(
    closed: Boolean = true,
    observers: List<String> = listOf("nbdSteve"),
    journalFile: Path,
) {
    val online = mutableListOf<FakePlayer>()
    val server: Server =
        stub { name, args ->
            when (name) {
                "getOnlinePlayers" -> online.map { it.player }
                "getPlayer" -> online.firstOrNull { it.uuid == args[0] }?.player
                else -> null
            }
        }
    val listener =
        BukkitSpectators(
            plugin = stub<Plugin> { _, _ -> null },
            server = server,
            spectators = Spectators(closed, observers),
            managed = { uuid, username -> uuid == AGENT || username.equals("loom_agent", ignoreCase = true) },
            journal = ObserverJournal(journalFile) { throw it },
            currentTick = { 42L },
        )

    fun join(player: FakePlayer): PlayerJoinEvent {
        online += player
        return PlayerJoinEvent(player.player, Component.text("joined")).also(listener::onJoin)
    }
}

// Every public constructor is deprecated; the profile one needs no running server.
@Suppress("DEPRECATION")
private fun preLogin(
    name: String,
    uuid: UUID,
): AsyncPlayerPreLoginEvent {
    val profile: PlayerProfile =
        stub { method, _ ->
            when (method) {
                "getName" -> name
                "getId" -> uuid
                else -> null
            }
        }
    val loopback = InetAddress.getLoopbackAddress()
    return AsyncPlayerPreLoginEvent(name, loopback, loopback, uuid, false, profile)
}

private fun chat(player: Player): AsyncChatEvent =
    AsyncChatEvent(
        true,
        player,
        mutableSetOf(),
        ChatRenderer.defaultRenderer(),
        Component.text("hello"),
        Component.text("hello"),
        stub<SignedMessage> { _, _ -> null },
    )

class SpectatorsTest {
    @Test
    fun `closed admission admits agents and observers and refuses everyone else`() {
        val spectators = Spectators(closed = true, observers = listOf("nbdSteve"))
        assertTrue(spectators.admits("loom_agent", managed = true))
        assertTrue(spectators.admits("NBDSTEVE", managed = false))
        assertFalse(spectators.admits("stranger", managed = false))
    }

    @Test
    fun `open admission leaves every login to the witness`() {
        assertTrue(Spectators(closed = false, observers = emptyList()).admits("stranger", managed = false))
    }

    @Test
    fun `a managed identity is never an observer`() {
        val spectators = Spectators(closed = true, observers = listOf("loom_agent"))
        assertFalse(spectators.isObserver("loom_agent", managed = true))
    }

    @Test
    fun `an observer may run only loomwatch`() {
        val spectators = Spectators(closed = true, observers = listOf("nbdSteve"))
        assertTrue(spectators.mayRun("/loomwatch alpha"))
        assertTrue(spectators.mayRun("/LoomWatch"))
        assertTrue(spectators.mayRun("/loom:loomwatch off"))
        val worldCommands =
            listOf("/say hi", "/me waves", "/msg loom_agent hi", "/gamemode survival", "/loombody stop a")
        for (command in worldCommands) {
            assertFalse(spectators.mayRun(command), command)
        }
    }

    @Test
    fun `settings read the environment and refuse a malformed observer list`() {
        val spectators =
            Spectators.fromEnvironment(mapOf(ADMISSION_ENV to "closed", OBSERVERS_ENV to "nbdSteve, other_1"))
        assertTrue(spectators.closed)
        assertTrue(spectators.isObserver("other_1", managed = false))
        val none = Spectators.fromEnvironment(emptyMap())
        assertFalse(none.closed)
        assertFalse(none.hasObservers)
        assertThrows<IllegalArgumentException> { Spectators(true, listOf("a b")) }
        assertThrows<IllegalArgumentException> { Spectators(true, listOf("Steve", "steve")) }
        assertThrows<IllegalArgumentException> { Spectators(true, List(MAX_OBSERVERS + 1) { "observer$it" }) }
    }

    @Test
    fun `a stranger is refused before the witness and an agent or observer is not`(
        @TempDir directory: Path,
    ) {
        val rig = Rig(journalFile = directory.resolve("observers.jsonl"))
        val stranger = preLogin("stranger", STRANGER).also(rig.listener::onPreLogin)
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, stranger.loginResult)
        val agent = preLogin("loom_agent", AGENT).also(rig.listener::onPreLogin)
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, agent.loginResult)
        val observer = preLogin("nbdSteve", OBSERVER).also(rig.listener::onPreLogin)
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, observer.loginResult)
    }

    @Test
    fun `an observer joins as a hidden, deopped spectator holding only loom observe`(
        @TempDir directory: Path,
    ) {
        val journal = directory.resolve("observers.jsonl")
        val rig = Rig(journalFile = journal)
        val agent = FakePlayer(AGENT, "loom_agent")
        rig.join(agent)
        val observer = FakePlayer(OBSERVER, "nbdSteve")
        val joined = rig.join(observer)

        assertEquals(GameMode.SPECTATOR, observer.mode)
        assertFalse(observer.op)
        assertFalse(observer.affectsSpawning)
        assertEquals(listOf("loom.observe"), observer.permissions)
        assertEquals(listOf(OBSERVER), agent.hidden)
        assertNull(joined.joinMessage())

        val later = FakePlayer(UUID.nameUUIDFromBytes("later".toByteArray()), "loom_later")
        rig.join(later)
        assertEquals(listOf(OBSERVER), later.hidden)
        assertEquals(GameMode.SURVIVAL, agent.mode)
        assertTrue(agent.permissions.isEmpty())

        val quit = PlayerQuitEvent(observer.player, Component.text("left"), PlayerQuitEvent.QuitReason.DISCONNECTED)
        rig.listener.onQuit(quit)
        assertNull(quit.quitMessage())
        assertEquals(
            listOf(
                """{"event":"observerJoined","tick":42,"username":"nbdSteve","uuid":"$OBSERVER"}""",
                """{"event":"observerLeft","tick":42,"username":"nbdSteve","uuid":"$OBSERVER"}""",
            ),
            Files.readAllLines(journal),
        )
    }

    @Test
    fun `an observer cannot chat, leave spectator mode, or run a world command`(
        @TempDir directory: Path,
    ) {
        val rig = Rig(journalFile = directory.resolve("observers.jsonl"))
        val agent = FakePlayer(AGENT, "loom_agent")
        rig.join(agent)
        val observer = FakePlayer(OBSERVER, "nbdSteve")
        rig.join(observer)

        assertTrue(chat(observer.player).also(rig.listener::onChat).isCancelled)
        assertFalse(chat(agent.player).also(rig.listener::onChat).isCancelled)

        val toSurvival =
            PlayerGameModeChangeEvent(
                observer.player,
                GameMode.SURVIVAL,
                PlayerGameModeChangeEvent.Cause.COMMAND,
                null,
            ).also(rig.listener::onGameModeChange)
        assertTrue(toSurvival.isCancelled)

        fun typed(
            by: FakePlayer,
            line: String,
        ) = PlayerCommandPreprocessEvent(by.player, line, mutableSetOf()).also(rig.listener::onCommand)
        assertTrue(typed(observer, "/say hi").isCancelled)
        assertEquals(1, observer.told.size)
        assertFalse(typed(observer, "/loomwatch alpha").isCancelled)
        assertFalse(typed(agent, "/say hi").isCancelled)
    }

    @Test
    fun `a spectator is never a candidate for an agent's observation`() {
        val observer = FakePlayer(OBSERVER, "nbdSteve").apply { mode = GameMode.SPECTATOR }
        assertTrue(isSpectator(observer.player))
        assertFalse(isSpectator(FakePlayer(AGENT, "loom_agent").player))
    }
}
