package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.body.player.BlockGround
import dev.loom.body.player.BlockYields
import dev.loom.body.player.PlayerBodyAuthority
import dev.loom.body.player.PlayerBodyPort
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.PlayerServerState
import dev.loom.body.player.ServerInteractionAudit
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.body.player.playerIdentityOf
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.SensedEntity
import dev.loom.contract.BlockYield
import dev.loom.contract.Decision
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.Trigger
import dev.loom.mind.CognitionBridge
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private val SCENE_PEERS: Path = REPO.resolve("body/test/fixtures/goal-scenario-peers.js")

/**
 * The four goal and safety claims the r15 live run never exercised, each driven offline end to
 * end: the real JVM ledger, cognition, actuation and fleet over real control sockets to real Node
 * bodies on real Mineflayer, with a scripted mind standing in for the model.
 *
 * - a goal pursued by idling is refused before the body acts, and the next review is told why;
 * - digging the only floor of a shaft is refused before the body is asked;
 * - a threat flight halts a routine dig in flight and arrives clear of the threat;
 * - two agents digging one block end in the server's one break and one lost race, no dispute.
 *
 * The scene each body stands in and the server ground the JVM reads are one [Scene], so the two
 * sides cannot disagree about a block. Runs whenever `node` and `body/node_modules` exist.
 */
class GoalScenarioEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `a goal pursued by idling is refused before the body acts and told at the next review`() {
        val bush = Cell(10, 64, 8, "dead_bush")
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = listOf(bush)))
        world.mind.answer = { _, index ->
            // Adopted beside an idle, which takes no step (r14 eta, untested in r15).
            val adopted = GoalChoice(GoalDisposition.ADOPT, STICK, 1, step = "wait for a stick")
            Intent.Idle to adopted.takeIf { index == 0 }
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(GoalEvidence.STEP_IDLE, refused.outcome)
        val decided = world.records.single { it.event == BodyEvent.DECIDED }
        assertEquals(GoalEvidence.STEP_IDLE, decided.goal?.outcome)

        world.review(ALPHA)
        world.awaitDecided(2)
        val told = world.mind.seen[1].lastRefusal
        assertEquals(world.mind.seen[0].requestId, told?.requestId)
        assertEquals(GoalEvidence.STEP_IDLE, told?.outcome)
        assertEquals(STICK, told?.successItem)
        assertEquals(0L, told?.held)
        assertEquals(2L, told?.capacity)
        // Told once.
        world.review(ALPHA)
        world.awaitDecided(3)
        assertNull(world.mind.seen[2].lastRefusal)
        assertTrue(world.digWrites().isEmpty(), "the body was never asked to act")
    }

    @Test
    fun `digging the only floor of a shaft is refused before the body is asked`() {
        // r13 eta dug its own support four times into a one-block shaft it could not flee from.
        val walls =
            listOf(64, 65).flatMap { y ->
                listOf(Cell(9, y, 8, "stone"), Cell(7, y, 8, "stone"), Cell(8, y, 9, "stone"), Cell(8, y, 7, "stone"))
            }
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = walls))
        world.mind.answer = { perception, _ ->
            val support =
                perception.surroundings.single {
                    it.kind == ObservationKind.BLOCK && it.digSafe == false
                }
            Intent.Dig(support.ref) to null
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(EventTag.DIG_WOULD_TRAP, refused.outcome)
        val support = world.mind.seen.single().surroundings.single { it.digSafe == false }
        assertTrue(support.verticalOffset < 0, "the unsafe dig is the block underfoot: $support")
        assertTrue(world.digWrites().isEmpty(), "the body was never asked to dig")
    }

    @Test
    fun `a threat flight halts the routine dig in flight and arrives clear of the threat`() {
        // r13 eta's flight was refused behind a dig; r14 and r15 had no flight during routine work.
        val zombie =
            buildJsonObject {
                put("id", ZOMBIE_ID)
                put("what", "zombie")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 14.5)
            }
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(Cell(9, 64, 8, "stone")),
                entities = listOf(zombie),
                timeOfDay = NIGHT,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            when (perception.trigger) {
                is Trigger.Damaged -> {
                    val threat = perception.surroundings.first { it.what == "entity.minecraft.zombie" }
                    Intent.Flee(threat.ref) to null
                }
                else ->
                    Intent.Dig(
                        perception.surroundings.first {
                            it.what == "block.minecraft.stone" && it.verticalOffset > 0
                        }.ref,
                    ) to null
            }
        }
        world.review(ALPHA)
        // A bare-hand dig of stone holds the body for seconds; the flight cuts in while it is held.
        world.awaitWrite { it.status == DIG_STARTED }
        world.onMain { world.cognition.consider(ALPHA, Trigger.Damaged(2, "entity.minecraft.zombie")) }
        val retreat = world.awaitRecord { it.event == BodyEvent.RETREATING }
        assertEquals(EventTag.RETREAT_SAFE, retreat.outcome)
        val applied = world.records.filter { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(setOf(EventTag.DIG_CANCELLED, EventTag.ACCEPTED), applied.map { it.outcome }.toSet(), "$applied")
        assertTrue(world.digWrites().none { it.status == DIG_FINISHED }, "the stone was never broken")
    }

    @Test
    fun `two agents digging one block end in the server's one break and one lost race`() {
        // The v23 rehearsal's tick-3268 race, which r15 never met.
        val dirt = Cell(8, 64, 9, "dirt")
        val scene =
            Scene(
                listOf(Stand(KAPPA, 8.5, 64.0, 8.5), Stand(IOTA, 7.5, 64.0, 9.5)),
                blocks = listOf(dirt),
                serveBreaks = true,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            Intent.Dig(perception.surroundings.first { it.what == "block.minecraft.dirt" }.ref) to null
        }
        world.review(KAPPA)
        world.awaitWrite { it.agentId == KAPPA.value && it.status == DIG_STARTED }
        Thread.sleep(STAGGER_MS)
        world.review(IOTA)
        world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED && it.agentId == IOTA.value }
        world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED && it.agentId == KAPPA.value }
        val outcomes =
            world.records.filter { it.event == BodyEvent.INTENT_APPLIED }.associate { it.agentId to it.outcome }
        assertEquals(mapOf(KAPPA.value to EventTag.ACCEPTED, IOTA.value to EventTag.DIG_BLOCK_GONE), outcomes)
        assertEquals(listOf(KAPPA.value), world.breaks())
        assertTrue(world.records.none { it.event == BodyEvent.WITNESS_DISPUTED })
        assertTrue(
            world.records.filter { it.event == BodyEvent.WORLD_INTERACTION }.all {
                it.outcome == EventTag.SERVER_EFFECT_CONFIRMED
            },
        )
    }

    private fun world(scene: Scene): ScenarioWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return ScenarioWorld(requireNotNull(node), scene, temporary, closers)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val ZOMBIE_ID = 41
        const val NIGHT = 14_000
        const val DIG_STARTED = 0
        const val DIG_FINISHED = 2
        const val STAGGER_MS = 300L
        val ALPHA = AgentId("agent-alpha")
        val KAPPA = AgentId("agent-kappa")
        val IOTA = AgentId("agent-iota")
    }
}

/** Where one agent's body stands at the start. */
private data class Stand(
    val agent: AgentId,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** One block of a scene, by its registry name. */
private data class Cell(
    val x: Int,
    val y: Int,
    val z: Int,
    val name: String,
)

/**
 * One scene: who stands where, every block beside the stone floor at y 63, the creatures in view,
 * the time of day, and whether the peers serve dig breaks as one shared server.
 */
private data class Scene(
    val stands: List<Stand>,
    val blocks: List<Cell> = emptyList(),
    val entities: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val timeOfDay: Int = 6_000,
    val serveBreaks: Boolean = false,
) {
    /** The scene as the peers script reads it. */
    fun json(identities: Map<AgentId, PlayerIdentity>): String =
        buildJsonObject {
            put(
                "agents",
                buildJsonArray {
                    stands.forEach { stand ->
                        add(
                            buildJsonObject {
                                put("agentId", stand.agent.value)
                                put("username", identities.getValue(stand.agent).username)
                                put(
                                    "feet",
                                    buildJsonObject {
                                        put("x", stand.x)
                                        put("y", stand.y)
                                        put("z", stand.z)
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "blocks",
                buildJsonArray {
                    blocks.forEach { cell ->
                        add(
                            buildJsonObject {
                                put("x", cell.x)
                                put("y", cell.y)
                                put("z", cell.z)
                                put("name", cell.name)
                            },
                        )
                    }
                },
            )
            put("entities", JsonArray(entities))
            put("timeOfDay", timeOfDay)
            put("serveBreaks", serveBreaks)
        }.toString()

    /** The server's ground for this scene: the stone floor and every solid block in it. */
    val ground: BlockGround =
        BlockGround { position ->
            val (x, y, z) = position
            val block = blocks.firstOrNull { it.x == x && it.y == y && it.z == z }
            val floor = y == FLOOR_Y && x in -FLOOR_EXTENT until FLOOR_EXTENT && z in -FLOOR_EXTENT until FLOOR_EXTENT
            !floor && (block == null || block.name in PASSABLE)
        }

    /** What digging each block with a bare hand drops: a dead bush drops nought to two sticks. */
    val yields: BlockYields =
        BlockYields { position, _ ->
            val (x, y, z) = position
            val block = blocks.firstOrNull { it.x == x && it.y == y && it.z == z }
            if (block?.name == "dead_bush") listOf(BlockYield("item.minecraft.stick", 0, 2)) else emptyList()
        }

    private companion object {
        const val FLOOR_Y = 63
        const val FLOOR_EXTENT = 48
        val PASSABLE = setOf("dead_bush")
    }
}

/** One dig packet a body wrote, as the fake server saw it. */
private data class DigWrite(
    val agentId: String,
    val status: Int,
)

/** A scripted mind: [answer] picks each perception's intent and goal choice. */
private class ScenarioMind : CognitionBridge {
    val seen = CopyOnWriteArrayList<Perception>()

    @Volatile
    var answer: (Perception, Int) -> Pair<Intent, GoalChoice?> = { _, _ -> Intent.Idle to null }

    override fun submit(perception: Perception): CompletableFuture<Decision> {
        val index = seen.size
        seen += perception
        val (intent, goal) = answer(perception, index)
        return CompletableFuture.completedFuture(
            Decision(
                requestId = perception.requestId,
                agentId = perception.agentId,
                intent = intent,
                reasoning = "scripted scenario",
                provenance = Provenance(perception.importance, "scripted-scenario", "scenario-v1"),
                goal = goal,
            ),
        )
    }
}

/** The bodies of one [Scene], one JVM fleet over them, a scripted mind and one main thread. */
private class ScenarioWorld(
    node: String,
    scene: Scene,
    temporary: Path,
    closers: MutableList<() -> Unit>,
) {
    private val tick = AtomicLong(1_000)
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-server-main") }
    private val main = MainThread { task -> mainExecutor.execute(task) }
    private val kept = CopyOnWriteArrayList<BodyEventRecord>()
    private val writes = CopyOnWriteArrayList<DigWrite>()
    private val broke = CopyOnWriteArrayList<String>()
    private val log = BodyEventLog { record -> kept += record }
    val mind = ScenarioMind()
    val records: List<BodyEventRecord> get() = kept.toList()
    val cognition: EmbodiedCognition

    init {
        val ids = scene.stands.map { it.agent }
        val identities = ids.associateWith(::playerIdentityOf)
        val peers =
            ProcessBuilder(listOf(node, SCENE_PEERS.toString(), scene.json(identities)))
                .directory(REPO.resolve("body").toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        closers += { peers.destroyForcibly() }
        closers += { peers.outputStream.close() }
        val lines = peers.inputStream.bufferedReader()
        val ready = Json.parseToJsonElement(requireNotNull(lines.readLine())).jsonObject.getValue("ready")
        val ports =
            (ready as JsonArray).associate {
                val peer = it.jsonObject
                peer.getValue("agentId").jsonPrimitive.content to peer.getValue("port").jsonPrimitive.int
            }
        Thread {
            lines.lineSequence().forEach { line ->
                val read = Json.parseToJsonElement(line).jsonObject
                read["wrote"]?.jsonObject?.let { wrote ->
                    writes +=
                        DigWrite(
                            wrote.getValue("agentId").jsonPrimitive.content,
                            wrote.getValue("status").jsonPrimitive.int,
                        )
                }
                read["broke"]?.jsonObject?.let { broke += it.getValue("agentId").jsonPrimitive.content }
            }
        }.apply { isDaemon = true }.start()
        val authority =
            PlayerBodyAuthority(
                main = mainExecutor,
                registerIdentity = {},
                serverState = SceneServer(broke),
                eventLog = log,
                currentTick = tick::get,
                ground = scene.ground,
                yields = scene.yields,
            )
        val port =
            PlayerBodyPort(
                ids.map { infrastructure(identities.getValue(it), temporary, ports.getValue(it.value), closers) },
                authority,
                startupConcurrency = 1,
            )
        closers += { port.close() }
        cognition = EmbodiedCognition(port, mind, main, DecisionJournal(log, tick::get))
        ids.forEach { port.spawn(it).toCompletableFuture().await() }
        closers += { mainExecutor.shutdownNow() }
    }

    fun onMain(task: () -> Unit) {
        CompletableFuture.runAsync({ task() }, mainExecutor).await()
    }

    /** A scheduled goal review for [agent], a tick after the last so every review is its own. */
    fun review(agent: AgentId) {
        tick.addAndGet(REVIEW_TICKS)
        onMain { cognition.consider(agent, Trigger.GoalReview) }
    }

    fun digWrites(): List<DigWrite> = writes.toList()

    /** Waits until [count] decisions have been recorded. */
    fun awaitDecided(count: Int) {
        awaited("decision $count", { kept.filter { it.event == BodyEvent.DECIDED }.getOrNull(count - 1) })
    }

    fun breaks(): List<String> = broke.toList()

    fun awaitWrite(condition: (DigWrite) -> Boolean): DigWrite =
        awaited(
            "dig packet",
            { writes.firstOrNull(condition) },
        )

    fun awaitRecord(condition: (BodyEventRecord) -> Boolean): BodyEventRecord =
        awaited("record", { kept.firstOrNull(condition) }) { kept.map { "${it.event}:${it.outcome}:${it.detail}" } }

    private fun <T> awaited(
        what: String,
        found: () -> T?,
        seen: () -> Any = { "" },
    ): T {
        val deadline = System.nanoTime() + E2E_TIMEOUT.toNanos()
        while (true) {
            found()?.let { return it }
            check(System.nanoTime() < deadline) { "$what never arrived; saw ${seen()}" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val REVIEW_TICKS = 100L
        const val POLL_MS = 10L
    }
}

/**
 * The server's side of a scene: every body connected and alive, and a dig confirmed exactly when
 * the shared server took that body's finish, the way Paper's break event attributes one break to
 * one player. A body that lost the race claims no break, which the server confirms as absent.
 */
private class SceneServer(
    private val broke: List<String>,
) : PlayerServerState {
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

    override fun auditInteraction(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim,
    ): ServerInteractionAudit {
        val dug = claim is ServerInteractionClaim.Dig && claim.report.outcome == DigOutcome.DUG
        val ours = identity.agentId.value in broke
        return when {
            dug && ours -> ServerInteractionAudit(true, "found: the server took this player's break")
            dug -> ServerInteractionAudit(true, "absent: another player's break", brokenByOther = true)
            else -> ServerInteractionAudit(!ours, "absent")
        }
    }
}
