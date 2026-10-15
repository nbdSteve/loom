package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.FileBodyEventLog
import dev.loom.body.player.BodyControlEndpoint
import dev.loom.body.player.BodyControlRequestIds
import dev.loom.body.player.BodyProcessSupervisor
import dev.loom.body.player.PlayerBodyAuthority
import dev.loom.body.player.PlayerBodyInfrastructure
import dev.loom.body.player.PlayerBodyPort
import dev.loom.body.player.PlayerBodySettings
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.PlayerServerState
import dev.loom.body.player.SocketBodyControlConnector
import dev.loom.body.player.SupervisedBodyProcess
import dev.loom.body.player.playerIdentityOf
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.SensedEntity
import dev.loom.contract.Decision
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.mind.CognitionBridge
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.witness.ManagedIdentity
import dev.loom.witness.SpeechJournal
import dev.loom.witness.SpeechWitness
import dev.loom.witness.WitnessRegistry
import dev.loom.witness.bukkit.SpeechListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val REPO: Path = Paths.get("").toAbsolutePath().parent
private val PEERS: Path = REPO.resolve("body/test/fixtures/speech-peers.js")
private val E2E_TIMEOUT: Duration = Duration.ofSeconds(20)

/**
 * The offline speech acceptance: a strict decision, the real JVM actuation over a real
 * control socket, two real Node bodies writing real `chat_message` packets, the server's
 * chat seam, attribution, the nearby `Trigger.Spoken`, the journal and normalized evidence.
 *
 * No model, account or server: the fake protocol client is the server's wire, and its
 * packets enter [SpeechListener] off the main thread exactly as Paper's async chat does.
 * Runs whenever `node` and `body/node_modules` exist; CI's `speech` job requires it ran.
 */
class SpeechEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `one strict say is sent once, attributed by the server, heard nearby, journalled and counted`() {
        val world = world()
        world.alpha.cognition.consider(world.alpha.id, Trigger.GoalReview).toCompletableFuture().await()

        val chat = world.nextChat()
        assertEquals(SAID, chat.text)
        assertEquals(world.alpha.identity.username, chat.username)
        // The fake server hands the packet to the chat seam from its own thread.
        world.listener.chatted(world.alpha.identity.uuid, chat.username, chat.text)

        world.awaitRecord { it.event == BodyEvent.DECIDED && it.agentId == world.beta.id.value }
        world.settleQuietly()

        assertEquals(emptyList<ChatLine>(), world.moreChats(), "exactly one chat packet, and no reply")
        val spoken = world.perceptions.filter { it.trigger is Trigger.Spoken }
        assertEquals(listOf(world.beta.id.value), spoken.map { it.agentId }, "only the nearby other agent hears")
        assertEquals(Trigger.Spoken(world.alpha.identity.username, SAID), spoken.single().trigger)

        val records = world.records
        val alphaReview = world.perceptions.first { it.agentId == world.alpha.id.value }.requestId
        val applied = records.single { it.event == BodyEvent.INTENT_APPLIED && it.agentId == world.alpha.id.value }
        assertEquals(alphaReview, applied.requestId)
        assertEquals(EventTag.SAY, applied.intent)
        val confirmed = records.single { it.event == BodyEvent.WORLD_INTERACTION }
        assertEquals(world.alpha.id.value, confirmed.agentId)
        assertEquals(alphaReview, confirmed.requestId)
        assertEquals(EventTag.SERVER_EFFECT_CONFIRMED, confirmed.outcome)
        assertEquals(world.alpha.identity.uuid.toString(), confirmed.witness?.uuid)
        assertTrue(records.none { it.event == BodyEvent.WITNESS_DISPUTED })
        assertTrue(
            records.none {
                it.event == BodyEvent.PERCEIVED && it.agentId == world.alpha.id.value && it.trigger == EventTag.SPOKEN
            },
        )

        val speech = world.normalizedSpeechMetrics()
        assertEquals(1, speech.getValue("applied").jsonPrimitive.int)
        assertEquals(1, speech.getValue("confirmed").jsonPrimitive.int)
        assertEquals(0, speech.getValue("disputed").jsonPrimitive.int)
        assertEquals(1, speech.getValue("heard").jsonPrimitive.int)
        assertEquals(1, speech.getValue("heardByAgent").jsonObject.getValue(world.beta.id.value).jsonPrimitive.int)
    }

    @Test
    fun `a line the server attributes to another agent is a sender dispute and nobody hears it`() {
        val world = world()
        world.alpha.cognition.consider(world.alpha.id, Trigger.GoalReview).toCompletableFuture().await()
        val chat = world.nextChat()
        // Spoofed: the packet alpha's body wrote arrives under beta's server identity.
        world.listener.chatted(world.beta.identity.uuid, world.beta.identity.username, chat.text)
        world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        world.onMain { world.witness.tick(world.tick.get() + 101) }
        world.awaitRecord { it.event == BodyEvent.WITNESS_DISPUTED }
        world.settleQuietly()

        assertTrue(world.perceptions.none { it.trigger is Trigger.Spoken })
        assertTrue(world.records.none { it.event == BodyEvent.WORLD_INTERACTION })
        assertTrue(
            world.records.any {
                it.event == BodyEvent.WITNESS_DISPUTED && it.outcome == EventTag.DISPUTED_SPEECH_SENDER
            },
            "records were ${world.records.map { it.event to it.outcome }}",
        )
        val speech = world.normalizedSpeechMetrics()
        assertEquals(0, speech.getValue("confirmed").jsonPrimitive.int)
        assertEquals(0, speech.getValue("heard").jsonPrimitive.int)
        assertTrue(speech.getValue("disputed").jsonPrimitive.int >= 1)
    }

    private fun world(): SpeechWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return SpeechWorld(requireNotNull(node), temporary, closers)
    }

    private companion object {
        const val SAID = "Hello, player-steve."
    }
}

private data class ChatLine(
    val agentId: String,
    val username: String,
    val text: String,
)

private class Member(
    val id: AgentId,
    val identity: PlayerIdentity,
    val cognition: EmbodiedCognition,
)

/** Two real body processes, one JVM fleet, one witness, one main thread. */
private class SpeechWorld(
    node: String,
    temporary: Path,
    closers: MutableList<() -> Unit>,
) {
    val tick = AtomicLong(1_000)
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-server-main") }
    private val main = MainThread { task -> mainExecutor.execute(task) }
    private val chats = LinkedBlockingQueue<ChatLine>()
    private val logFile = temporary.resolve("body-events.jsonl")
    private val file = FileBodyEventLog(logFile, { throw it }, { error("dropped $it records") })
    private val kept = CopyOnWriteArrayList<BodyEventRecord>()
    private val log = BodyEventLog { record -> file.append(record).also { kept += record } }
    val perceptions = CopyOnWriteArrayList<Perception>()
    val records: List<BodyEventRecord> get() = kept.toList()
    private val node = node

    val alpha: Member
    val beta: Member
    val witness: SpeechWitness
    val listener: SpeechListener

    init {
        val ids = listOf(AgentId("agent-alpha"), AgentId("agent-beta"))
        val identities = ids.map(::playerIdentityOf)
        val peers =
            ProcessBuilder(listOf(node, PEERS.toString()) + identities.map { "${it.agentId.value}=${it.username}" })
                .directory(REPO.resolve("body").toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        closers += { peers.destroyForcibly() }
        closers += { peers.outputStream.close() }
        val lines = peers.inputStream.bufferedReader()
        val ready = Json.parseToJsonElement(requireNotNull(lines.readLine())).jsonObject.getValue("ready")
        val ports =
            (ready as kotlinx.serialization.json.JsonArray).associate {
                val peer = it.jsonObject
                peer.getValue("agentId").jsonPrimitive.content to peer.getValue("port").jsonPrimitive.int
            }
        Thread {
            lines.lineSequence().forEach { line ->
                val chat = Json.parseToJsonElement(line).jsonObject["chat"]?.jsonObject ?: return@forEach
                chats +=
                    ChatLine(
                        chat.getValue("agentId").jsonPrimitive.content,
                        chat.getValue("username").jsonPrimitive.content,
                        chat.getValue("text").jsonPrimitive.content,
                    )
            }
        }.apply { isDaemon = true }.start()

        val registry = WitnessRegistry()
        identities.forEach { registry.register(ManagedIdentity(it.agentId, it.username, it.uuid)) }
        lateinit var heardBy: (AgentId, Trigger) -> Unit
        witness =
            SpeechWitness(
                registry = registry,
                journal = SpeechJournal(log),
                placeOf = { dev.loom.witness.SpokenPlace("world", 0.0, 64.0, 0.0) },
                isDead = { false },
                hear = { heard -> heardBy(heard.listener, Trigger.Spoken(heard.from, heard.text)) },
            )
        listener =
            SpeechListener(
                registry,
                witness,
                main = { task -> mainExecutor.execute(task) },
                playerOf = { livePlayer() },
                currentTick = tick::get,
            )
        val authority =
            PlayerBodyAuthority(
                main = mainExecutor,
                registerIdentity = {},
                serverState = AlwaysConnected,
                eventLog = log,
                currentTick = tick::get,
                speechClaims = sentSpeechOf(witness, main, tick::get),
            )
        val port =
            PlayerBodyPort(
                identities.map { infrastructure(it, temporary, ports.getValue(it.agentId.value), closers) },
                authority,
                startupConcurrency = 1,
            )
        closers += { port.close() }
        val mind = ScriptedSpeechMind(perceptions)
        val cognition = EmbodiedCognition(port, mind, main, DecisionJournal(log, tick::get))
        heardBy = { listener, trigger -> cognition.submitWhenPerceived(listener, port.perceive(listener, trigger)) }
        ids.forEach { port.spawn(it).toCompletableFuture().await() }
        alpha = Member(ids[0], identities[0], cognition)
        beta = Member(ids[1], identities[1], cognition)
        closers += { mainExecutor.shutdownNow() }
        closers += { file.close() }
    }

    fun onMain(task: () -> Unit) {
        CompletableFuture.runAsync({ task() }, mainExecutor).await()
    }

    fun nextChat(): ChatLine = requireNotNull(chats.poll(E2E_TIMEOUT.seconds, TimeUnit.SECONDS)) { "no chat packet" }

    fun moreChats(): List<ChatLine> = ArrayList<ChatLine>().also(chats::drainTo)

    fun awaitRecord(condition: (BodyEventRecord) -> Boolean) {
        val deadline = System.nanoTime() + E2E_TIMEOUT.toNanos()
        while (kept.none(condition)) {
            check(System.nanoTime() < deadline) {
                "record never arrived; saw ${kept.map { "${it.event}:${it.outcome}:${it.detail}" }}"
            }
            Thread.sleep(10)
        }
    }

    /** Long enough for any reply a loop would send to have reached the fake server. */
    fun settleQuietly() {
        Thread.sleep(QUIET_MS)
        onMain {}
    }

    /** Normalizes the journal with the research library and returns its `speech` metrics. */
    fun normalizedSpeechMetrics(): JsonObject {
        file.close()
        val harness = Files.writeString(logFile.resolveSibling("harness.jsonl"), "")
        val script =
            """
            const { normalizeEvidence, deriveMetrics } = require(process.argv[1])
            const out = process.argv[4]
            const { records } = normalizeEvidence({ experimentId: 'speech-e2e', bodyFile: process.argv[2],
              harnessFile: process.argv[3], outputFile: out, maxInputBytes: 1 << 20, maxOutputBytes: 1 << 20,
              maxRecords: 1000 })
            process.stdout.write(JSON.stringify(deriveMetrics(records).speech ?? null))
            """.trimIndent()
        val process =
            ProcessBuilder(
                node,
                "-e",
                script,
                REPO.resolve("research/lib/evidence.js").toString(),
                logFile.toString(),
                harness.toString(),
                logFile.resolveSibling("evidence.jsonl").toString(),
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(E2E_TIMEOUT.seconds, TimeUnit.SECONDS) && process.exitValue() == 0) { "normalize failed" }
        return Json.parseToJsonElement(output).jsonObject
    }

    private companion object {
        const val QUIET_MS = 750L
    }
}

/** A strict decision for alpha's review; beta's mind answers speech with nothing. */
private class ScriptedSpeechMind(
    private val seen: MutableList<Perception>,
) : CognitionBridge {
    private val say = LoomJson.decodeFromString<Decision>(fixture("decision-say.json"))
    private val idle = LoomJson.decodeFromString<Decision>(fixture("decision-idle.json"))

    override fun submit(perception: Perception): CompletableFuture<Decision> {
        seen += perception
        val chosen = if (perception.agentId == "agent-alpha" && perception.trigger == Trigger.GoalReview) say else idle
        return CompletableFuture.completedFuture(
            chosen.copy(requestId = perception.requestId, agentId = perception.agentId, goal = null),
        )
    }
}

private fun fixture(name: String): String = Files.readString(REPO.resolve("contract/fixtures/valid/$name"))

private fun infrastructure(
    identity: PlayerIdentity,
    temporary: Path,
    port: Int,
    closers: MutableList<() -> Unit>,
): PlayerBodyInfrastructure {
    val endpoint = BodyControlEndpoint("127.0.0.1", port)
    val io = Executors.newFixedThreadPool(2)
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    closers += { io.shutdownNow() }
    closers += { scheduler.shutdownNow() }
    return PlayerBodyInfrastructure(
        identity = identity,
        settings =
            PlayerBodySettings(
                bodyDirectory = temporary.toAbsolutePath(),
                processDirectory = temporary.resolve(identity.username).toAbsolutePath(),
                controlEndpoint = endpoint,
                connectTimeout = Duration.ofSeconds(2),
                requestTimeout = Duration.ofSeconds(5),
                movementTimeout = Duration.ofSeconds(5),
                startupTimeout = Duration.ofSeconds(10),
                shutdownTimeout = Duration.ofSeconds(2),
                healthCheckIntervalTicks = 100_000,
                healthFailureLimit = 3,
            ),
        supervisor = AlreadyRunning(endpoint),
        connector = SocketBodyControlConnector(Duration.ofSeconds(2), Duration.ofSeconds(5)),
        requestIds = BodyControlRequestIds(scope = identity.username),
        io = io,
        scheduler = scheduler,
    )
}

/** The Node peers are already running; the supervisor only names them. */
private class AlreadyRunning(
    private val endpoint: BodyControlEndpoint,
) : BodyProcessSupervisor {
    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> {
        val alive = AtomicBoolean(true)
        return CompletableFuture.completedFuture(
            object : SupervisedBodyProcess {
                override val identity = identity
                override val endpoint = this@AlreadyRunning.endpoint
                override val exitCode: CompletionStage<Int> = CompletableFuture()

                override fun isAlive(): Boolean = alive.get()
            },
        )
    }

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)

    override fun close() = Unit
}

private object AlwaysConnected : PlayerServerState {
    override fun connected(identity: PlayerIdentity): Boolean = true

    override fun isDead(agentId: AgentId): Boolean = false

    override fun dayOrdinal(): Long = 1

    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> = List(entities.size) { null }

    override fun auditSense(
        identity: PlayerIdentity,
        snapshot: dev.loom.body.player.SenseAuditSnapshot?,
        report: BodyResponse.SenseReport,
    ) = Unit

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.MoveTo,
        report: BodyResponse.MoveReport,
    ) = Unit

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.Flee,
        report: BodyResponse.FleeReport,
    ) = Unit
}

/** An online, live player standing at the shared spot, as the main thread would read it. */
private fun livePlayer(): Player {
    val world =
        Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
            if (method.name == "getName") "world" else null
        } as World
    return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "isOnline" -> true
            "isDead" -> false
            "getLocation" -> Location(world, 0.0, 64.0, 0.0)
            else -> null
        }
    } as Player
}

private fun <T> CompletionStage<T>.await(): T = toCompletableFuture().get(E2E_TIMEOUT.seconds, TimeUnit.SECONDS)

private fun <T> CompletableFuture<T>.await(): T = get(E2E_TIMEOUT.seconds, TimeUnit.SECONDS)

private fun nodeOrNull(): String? =
    runCatching {
        val probe = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        probe.inputStream.readAllBytes()
        "node".takeIf { probe.waitFor(E2E_TIMEOUT.seconds, TimeUnit.SECONDS) && probe.exitValue() == 0 }
    }.getOrNull()
