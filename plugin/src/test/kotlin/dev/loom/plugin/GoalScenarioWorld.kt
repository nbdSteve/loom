/*
 * The offline goal-scenario world: one scene for real Node bodies and the JVM fleet over them.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.player.BlockGround
import dev.loom.body.player.BlockYields
import dev.loom.body.player.OwnDropReading
import dev.loom.body.player.OwnDrops
import dev.loom.body.player.PlayerBodyAuthority
import dev.loom.body.player.PlayerBodyPort
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.PlayerServerState
import dev.loom.body.player.ServerInteractionAudit
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.body.player.playerIdentityOf
import dev.loom.body.toJsonLine
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.DigOutcome
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedItem
import dev.loom.contract.BlockYield
import dev.loom.contract.Decision
import dev.loom.contract.DropFate
import dev.loom.contract.GoalChoice
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.Trigger
import dev.loom.mind.CognitionBridge
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.mind.RecipeInputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal val SCENE_PEERS: Path = REPO.resolve("body/test/fixtures/goal-scenario-peers.js")

/** Where one agent's body stands at the start. */
internal data class Stand(
    val agent: AgentId,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** One block of a scene, by its registry name. */
internal data class Cell(
    val x: Int,
    val y: Int,
    val z: Int,
    val name: String,
)

/** One dropped stack, spawned as entity [id] whose server uuid is [uuid]. */
internal data class SceneItem(
    val id: Int,
    val what: String,
    val count: Int,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    /** The uuid the fake server spawns this entity under (`H.spotted`). */
    val uuid: UUID get() = UUID.fromString("00000000-0000-4000-8000-%012d".format(id))
}

/** One stack the body carries at the start, in its protocol window [slot]. */
internal data class SceneStack(
    val slot: Int,
    val what: String,
    val count: Int,
)

/** The world border the server states, centred on ([x], [z]) and [diameter] blocks across. */
internal data class SceneBorder(
    val x: Double,
    val z: Double,
    val diameter: Double,
)

/**
 * One scene: who stands where, every block beside the stone floor at y 63, the creatures and
 * dropped items in view, what the bodies carry, the time of day, whether the peers serve dig
 * breaks as one shared server, and which dropped items the server attributed to whose dig - or,
 * with [trackDrops], the real [OwnDropLedger] on the world's own server clock, fed by the test.
 */
internal data class Scene(
    val stands: List<Stand>,
    val blocks: List<Cell> = emptyList(),
    val entities: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val timeOfDay: Int = 6_000,
    val serveBreaks: Boolean = false,
    val items: List<SceneItem> = emptyList(),
    val inventory: List<SceneStack> = emptyList(),
    val ownDrops: Map<String, SceneItem> = emptyMap(),
    val columns: Int = 2,
    val serveCrafts: Boolean = false,
    val trackDrops: Boolean = false,
    val servePickups: Boolean = false,
    /** The bodies' health and food at spawn, vanilla's full bars when absent. */
    val health: Double? = null,
    /** The world border the server states before the bodies are placed, none when absent. */
    val border: SceneBorder? = null,
    /** The floor's block at y 63, and the layers under it, top first. */
    val floor: String = "stone",
    val depth: List<String> = emptyList(),
    /** Whether a served break hands the breaker its drop at once, and a placement is served. */
    val dropsOnBreak: Boolean = false,
    val servePlaces: Boolean = false,
    /** Whether the ledger knows vanilla's wood recipes, so a craft's horizon reaches past it. */
    val woodRecipes: Boolean = false,
) {
    /** The scene as the peers script reads it. */
    fun json(identities: Map<AgentId, PlayerIdentity>): String =
        jsonOf(
            "agents" to
                JsonArray(
                    stands.map { stand ->
                        jsonOf(
                            "agentId" to stand.agent.value,
                            "username" to identities.getValue(stand.agent).username,
                            "feet" to jsonOf("x" to stand.x, "y" to stand.y, "z" to stand.z),
                        )
                    },
                ),
            "blocks" to
                JsonArray(blocks.map { jsonOf("x" to it.x, "y" to it.y, "z" to it.z, "name" to it.name) }),
            "entities" to JsonArray(entities),
            "timeOfDay" to timeOfDay,
            "serveBreaks" to serveBreaks,
            "columns" to columns,
            "serveCrafts" to serveCrafts,
            "servePickups" to servePickups,
            "vitals" to health?.let { jsonOf("health" to it, "food" to 20, "foodSaturation" to 5) },
            "border" to border?.let { jsonOf("x" to it.x, "z" to it.z, "diameter" to it.diameter) },
            "floor" to floor,
            "depth" to JsonArray(depth.map { JsonPrimitive(it) }).takeIf { depth.isNotEmpty() },
            "dropsOnBreak" to dropsOnBreak,
            "servePlaces" to servePlaces,
            "items" to
                JsonArray(
                    items.map {
                        val place = arrayOf("x" to it.x, "y" to it.y, "z" to it.z)
                        jsonOf("id" to it.id, "what" to it.what, "count" to it.count, *place)
                    },
                ),
            "inventory" to
                JsonArray(inventory.map { jsonOf("slot" to it.slot, "what" to it.what, "count" to it.count) })
                    .takeIf { inventory.isNotEmpty() },
        ).toString()

    /** The own drops the server attributed to a dig of each agent's earlier request, still lying. */
    val drops: OwnDrops =
        object : OwnDrops {
            override fun attribute(
                identity: PlayerIdentity,
                requestId: String,
                block: List<Int>,
                sinceTick: Long,
            ): List<OwnDropReading> = emptyList()

            override fun of(identity: PlayerIdentity): List<OwnDropReading> =
                ownDrops.map { (requestId, item) ->
                    OwnDropReading(
                        requestId,
                        "item.minecraft.${item.what}",
                        item.count.toLong(),
                        DropFate.LYING,
                        listOf(item.x, item.y, item.z),
                        item.uuid,
                    )
                }
        }

    /** The scene block at [position]: a placed one, else the floor and its layers within the columns. */
    fun blockAt(position: List<Int>): String? {
        val (x, y, z) = position
        val block = blocks.firstOrNull { it.x == x && it.y == y && it.z == z }
        val extent = -CHUNK * columns until CHUNK * (columns + 1)
        val layer =
            (listOf(floor) + depth).getOrNull(FLOOR_Y - y)?.takeIf { y <= FLOOR_Y && x in extent && z in extent }
        return block?.name ?: layer
    }

    /** The server's ground for this scene: the floor, its layers and every solid block in it. */
    val ground: BlockGround =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean = blockAt(position).let { it == null || it in PASSABLE }

            override fun holdsNothing(position: List<Int>): Boolean = blockAt(position) == null
        }

    /**
     * What digging each block with a bare hand drops: a dead bush, dirt or a log one, so no one dig
     * meets the two more an objective asks.
     */
    val yields: BlockYields =
        BlockYields { position, _ ->
            val (x, y, z) = position
            val block = blocks.firstOrNull { it.x == x && it.y == y && it.z == z }
            when (block?.name ?: blockAt(position)) {
                "dead_bush" -> listOf(BlockYield("item.minecraft.stick", 1, 1, 100))
                "dirt", "grass_block" -> listOf(BlockYield("item.minecraft.dirt", 1, 1, 100))
                "oak_log" -> listOf(BlockYield("item.minecraft.oak_log", 1, 1, 100))
                else -> emptyList()
            }
        }

    private companion object {
        const val FLOOR_Y = 63
        const val CHUNK = 16
        val PASSABLE = setOf("dead_bush")
    }
}

/** [OwnDrops] read from a real [OwnDropLedger], as [BukkitOwnDrops] reads it. */
private class LedgerDrops(
    private val ledger: OwnDropLedger,
) : OwnDrops {
    override fun attribute(
        identity: PlayerIdentity,
        requestId: String,
        block: List<Int>,
        sinceTick: Long,
    ): List<OwnDropReading> = ledger.attribute(identity.uuid, requestId, block, sinceTick)

    override fun of(identity: PlayerIdentity): List<OwnDropReading> = ledger.of(identity.uuid)
}

/** One dig packet a body wrote, as the fake server saw it. */
internal data class DigWrite(
    val agentId: String,
    val status: Int,
    /** The block the packet names. */
    val at: List<Int>,
)

/** A scripted mind: [answer] picks each perception's intent and goal choice. */
internal class ScenarioMind : CognitionBridge {
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
internal class ScenarioWorld(
    private val node: String,
    scene: Scene,
    private val temporary: Path,
    closers: MutableList<() -> Unit>,
) {
    private val tick = AtomicLong(1_000)
    private val lying = ConcurrentHashMap<UUID, List<Double>>()
    private val identities = scene.stands.map { it.agent }.associateWith(::playerIdentityOf)

    /** The server's own-drop bookkeeping when the scene tracks drops; touched on the main thread only. */
    private val dropLedger = OwnDropLedger(tick::get) { lying[it] }
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-server-main") }
    private val main = MainThread { task -> mainExecutor.execute(task) }
    private val kept = CopyOnWriteArrayList<BodyEventRecord>()
    private val writes = CopyOnWriteArrayList<DigWrite>()
    private val broke = CopyOnWriteArrayList<String>()
    private val placers = CopyOnWriteArrayList<String>()

    /** The cells the shared server changed, by what now stands in each: [AIR] where a break left nothing. */
    private val changed = ConcurrentHashMap<List<Int>, String>()

    /** The server's ground as the shared server left it: the scene's, under every break and placement. */
    private fun groundOf(scene: Scene): BlockGround =
        object : BlockGround {
            override fun standsIn(position: List<Int>): Boolean =
                changed[position]?.let { it == AIR } ?: scene.ground.standsIn(position)

            override fun holdsNothing(position: List<Int>): Boolean =
                changed[position]?.let { it == AIR } ?: scene.ground.holdsNothing(position)
        }

    private val log = BodyEventLog { record -> kept += record }
    val mind = ScenarioMind()
    val records: List<BodyEventRecord> get() = kept.toList()
    val cognition: EmbodiedCognition
    private val bodies: PlayerBodyPort

    init {
        val ids = scene.stands.map { it.agent }
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
                            listOf("x", "y", "z").map { wrote.getValue(it).jsonPrimitive.int },
                        )
                }
                read["broke"]?.jsonObject?.let {
                    changed[listOf("x", "y", "z").map { axis -> it.getValue(axis).jsonPrimitive.int }] = AIR
                    broke += it.getValue("agentId").jsonPrimitive.content
                }
                read["placed"]?.jsonObject?.let {
                    changed[listOf("x", "y", "z").map { axis -> it.getValue(axis).jsonPrimitive.int }] =
                        it.getValue("name").jsonPrimitive.content
                    placers += it.getValue("agentId").jsonPrimitive.content
                }
            }
        }.apply { isDaemon = true }.start()
        val authority =
            PlayerBodyAuthority(
                main = mainExecutor,
                registerIdentity = {},
                serverState = SceneServer(broke, scene.items, scene.entities, placers),
                eventLog = log,
                currentTick = tick::get,
                ground = groundOf(scene),
                yields = scene.yields,
                ownDrops = if (scene.trackDrops) LedgerDrops(dropLedger) else scene.drops,
                solidity = { what -> what.removePrefix("minecraft:") in SOLID },
                players = ScenePlayers,
            )
        val port =
            PlayerBodyPort(
                ids.map { infrastructure(identities.getValue(it), temporary, ports.getValue(it.value), closers) },
                authority,
                startupConcurrency = 1,
            )
        closers += { port.close() }
        bodies = port
        cognition =
            EmbodiedCognition(
                port,
                mind,
                main,
                DecisionJournal(log, tick::get),
                dev.loom.mind.CognitionCallbacks(
                    onStepSettled = { agent, at, owed -> settlements += Triple(agent, at, owed) },
                ),
                goals = dev.loom.mind.GoalLedger(recipes = if (scene.woodRecipes) WOOD_RECIPES else RecipeInputs.NONE),
            )
        ids.forEach { port.spawn(it).toCompletableFuture().await() }
        // The body's per-tick hook, as Paper's timer calls it, at the world's own tick: the tests
        // move time themselves, so this advances nothing.
        val ticker =
            Executors.newSingleThreadScheduledExecutor {
                Thread(
                    it,
                    "fake-server-tick",
                ).apply { isDaemon = true }
            }
        ticker.scheduleAtFixedRate(
            { runCatching { mainExecutor.execute { port.tick(tick.get()) } } },
            SERVER_TICK_MILLIS,
            SERVER_TICK_MILLIS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        closers += { ticker.shutdownNow() }
        closers += { mainExecutor.shutdownNow() }
    }

    fun onMain(task: () -> Unit) {
        CompletableFuture.runAsync({ task() }, mainExecutor).await()
    }

    /** Every follow-up review the cognition owed, by agent, server tick and whether it was owed past the allowance. */
    val settlements = java.util.concurrent.CopyOnWriteArrayList<Triple<AgentId, Long, Boolean>>()

    /**
     * The server witnessing [amount] damage to [agent] from [attacker], a scene entity's uuid,
     * through the real [dev.loom.mind.DamageDriver] as Paper's damage listener feeds it.
     */
    fun damaged(
        agent: AgentId,
        amount: Double,
        source: String,
        attacker: UUID,
    ) {
        val identity = identities.getValue(agent)
        onMain {
            dev.loom.mind
                .DamageDriver(bodies, cognition, main, { false }, log, tick::get)
                .witnessed(
                    dev.loom.mind.DamageReport(
                        agentId = agent,
                        finalDamage = amount,
                        source = source,
                        tick = tick.get(),
                        witness = dev.loom.body.WitnessedPlayer(identity.uuid.toString(), identity.username),
                        attacker = attacker.toString(),
                    ),
                )
        }
    }

    /** A scheduled goal review for [agent], a tick after the last so every review is its own. */
    fun review(agent: AgentId) {
        tick.addAndGet(REVIEW_TICKS)
        onMain { cognition.consider(agent, Trigger.GoalReview) }
    }

    /** Lets [ticks] of server time pass with nothing reviewed. */
    fun advance(ticks: Long) {
        tick.addAndGet(ticks)
    }

    /** The server dropped [item] for [agent]'s break of [block], and attributed it to [requestId]. */
    fun dropped(
        agent: AgentId,
        requestId: String,
        item: SceneItem,
        block: List<Int>,
    ) = onMain {
        val owner = identities.getValue(agent).uuid
        lying[item.uuid] = listOf(item.x, item.y, item.z)
        dropLedger.dropped(owner, item.uuid, "item.minecraft.${item.what}", item.count.toLong(), block)
        dropLedger.attribute(owner, requestId, block, sinceTick = 0)
    }

    /** The server dropped [item] for [agent]'s break of [block], for the dig's own audit to attribute. */
    fun droppedAt(
        agent: AgentId,
        item: SceneItem,
        block: List<Int>,
    ) = onMain {
        val owner = identities.getValue(agent).uuid
        lying[item.uuid] = listOf(item.x, item.y, item.z)
        dropLedger.dropped(owner, item.uuid, "item.minecraft.${item.what}", item.count.toLong(), block)
    }

    /** [picker] took [item], as Paper's pickup event reports it. */
    fun taken(
        item: SceneItem,
        picker: AgentId,
    ) = onMain {
        lying.remove(item.uuid)
        dropLedger.pickedUp(item.uuid, identities.getValue(picker).uuid)
    }

    fun digWrites(): List<DigWrite> = writes.toList()

    /** Waits until [count] intents have been applied or refused. */
    fun awaitApplied(count: Int) {
        awaited("applied $count", { kept.filter { it.event == BodyEvent.INTENT_APPLIED }.getOrNull(count - 1) })
    }

    /** Waits until [count] decisions have been recorded. */
    fun awaitDecided(count: Int) {
        awaited("decision $count", { kept.filter { it.event == BodyEvent.DECIDED }.getOrNull(count - 1) })
    }

    fun breaks(): List<String> = broke.toList()

    /**
     * The research readiness gate over this world's journal, as a published run would derive it:
     * normalized by `research/lib/evidence.js` and classified by `research/lib/readiness.js` under
     * gate [generation].
     */
    fun readiness(generation: Int): JsonObject {
        val body = temporary.resolve("scenario-body.jsonl")
        Files.writeString(body, kept.joinToString("") { it.toJsonLine() + "\n" })
        val harness = Files.writeString(temporary.resolve("scenario-harness.jsonl"), "")
        val script =
            """
            const { normalizeEvidence } = require(process.argv[1])
            const { readinessMetrics } = require(process.argv[2])
            const { records } = normalizeEvidence({ experimentId: 'scenario-e2e', bodyFile: process.argv[3],
              harnessFile: process.argv[4], outputFile: process.argv[5], maxInputBytes: 1 << 22,
              maxOutputBytes: 1 << 22, maxRecords: 4000 })
            const bodyRecords = records.filter((record) => record.source === 'body')
            process.stdout.write(JSON.stringify(readinessMetrics({ bodyRecords, harnessRecords: [], metrics: {},
              generation: Number(process.argv[6]) })))
            """.trimIndent()
        val process =
            ProcessBuilder(
                node,
                "-e",
                script,
                REPO.resolve("research/lib/evidence.js").toString(),
                REPO.resolve("research/lib/readiness.js").toString(),
                body.toString(),
                harness.toString(),
                temporary.resolve("scenario-evidence.jsonl").toString(),
                generation.toString(),
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(E2E_TIMEOUT.seconds, TimeUnit.SECONDS) && process.exitValue() == 0) { "readiness failed" }
        return Json.parseToJsonElement(output).jsonObject
    }

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
internal class SceneServer(
    private val broke: List<String>,
    private val items: List<SceneItem>,
    private val entities: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    private val placers: List<String> = emptyList(),
) : PlayerServerState {
    /** Each sensed item correlated to the scene item it lies within a block of, as Paper does. */
    override fun itemSubjectsOf(
        identity: PlayerIdentity,
        items: List<SensedItem>,
    ): List<UUID?> =
        items.map { sensed ->
            this.items
                .firstOrNull { item ->
                    val (x, y, z) = sensed.position
                    abs(item.x - x) <= 1.0 && abs(item.y - y) <= 1.0 && abs(item.z - z) <= 1.0
                }?.uuid
        }

    override fun connected(identity: PlayerIdentity): Boolean = true

    override fun isDead(agentId: AgentId): Boolean = false

    override fun dayOrdinal(): Long = 1

    /** Each sensed entity correlated to the scene entity of its kind standing within a block of it, as Paper does. */
    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> =
        entities.map { sensed ->
            this.entities
                .firstOrNull { entity ->
                    val (x, y, z) = sensed.position
                    val at = listOf("x", "y", "z").map { entity.getValue(it).jsonPrimitive.content.toDouble() }
                    entity.getValue("what").jsonPrimitive.content == sensed.what &&
                        abs(at[0] - x) <= 1.0 && abs(at[1] - y) <= 1.0 && abs(at[2] - z) <= 1.0
                }?.let { sceneEntityUuid(it.getValue("id").jsonPrimitive.int) }
        }

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
        if (claim is ServerInteractionClaim.Place) {
            val placed = claim.report.outcome == dev.loom.bodycontrol.PlaceOutcome.PLACED
            val served = identity.agentId.value in placers
            return ServerInteractionAudit(placed == served, if (served) "found: the server set the block" else "absent")
        }
        return when {
            dug && ours -> ServerInteractionAudit(true, "found: the server took this player's break")
            dug -> ServerInteractionAudit(true, "absent: another player's break", brokenByOther = true)
            else -> ServerInteractionAudit(!ours, "absent")
        }
    }
}

/** A JSON object of [fields], leaving out any whose value is `null`. */
private fun jsonOf(vararg fields: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        for ((key, value) in fields) {
            when (value) {
                is Number -> put(key, value)
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is JsonElement -> put(key, value)
                null -> Unit
                else -> error("no JSON for $key")
            }
        }
    }

/** The uuid the peers spawn scene entity [id] with (`body/test/harness.js` `spotted`). */
internal fun sceneEntityUuid(id: Int): UUID =
    UUID.fromString("00000000-0000-4000-8000-${id.toString().padStart(12, '0')}")

/** What a break leaves in a cell the shared server changed. */
private const val AIR = "air"

/** The scene items that place as a solid block, as Paper's `Material.isSolid` says. */
private val SOLID = setOf("dirt", "grass_block", "stone", "cobblestone", "oak_log", "oak_planks")

/**
 * Where the server has a scene's players, as far as a scene says: no position of its own, and the
 * hand in the first hotbar slot, where the bodies keep it.
 */
private object ScenePlayers : dev.loom.body.player.ServerPlayerView {
    override fun feetOf(identity: PlayerIdentity): List<Double>? = null

    override fun mainHandSlotOf(identity: PlayerIdentity): Int = FIRST_HOTBAR_SLOT
}

/** The protocol window slot of the first hotbar slot. */
private const val FIRST_HOTBAR_SLOT = 36

/** Vanilla's tick, at which the fake server drives the body's per-tick hook. */
private const val SERVER_TICK_MILLIS = 50L

/** Vanilla's wood recipes: planks from a log, and sticks and a crafting table from planks. */
private val WOOD_RECIPES =
    object : RecipeInputs {
        private val planks = "item.minecraft.oak_planks"
        private val log = "item.minecraft.oak_log"

        override fun inputsOf(item: String): Set<String> =
            mapOf(
                planks to setOf(log),
                "item.minecraft.stick" to setOf(planks),
                "item.minecraft.crafting_table" to setOf(planks),
            )[item].orEmpty()

        override fun madeFrom(input: String): List<String> =
            mapOf(
                log to listOf(planks),
                planks to listOf("item.minecraft.stick", "item.minecraft.crafting_table"),
            )[input].orEmpty()
    }
