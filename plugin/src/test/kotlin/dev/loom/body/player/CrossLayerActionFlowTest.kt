/*
 * Provider-free cross-layer action flow.
 *
 * One path crosses every seam an agent's action crosses in production except the
 * Mineflayer process itself: body-control sense bytes on a real loopback socket,
 * the production SocketBodyControlConnector and its handshake, JVM selection and
 * request-ref minting, the perception contract encoded and decoded through
 * LoomJson, a strict decision decoded from a canonical decision fixture's bytes,
 * JVM ref resolution, body-control action bytes and the body's outcome frame,
 * DecisionJournal, and research/lib/evidence.js normalization.
 *
 * The peer answers only with canonical body-control fixture frames re-stamped
 * with the request's identity and re-validated through the production codec, so
 * nothing here can agree with itself outside the shared contract.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.IntentOutcome
import dev.loom.body.REQUEST_REFS_LIFETIME_TICKS
import dev.loom.body.toJsonLine
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.SensedBlock
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedGround
import dev.loom.bodycontrol.SensedItem
import dev.loom.bodycontrol.SensedRecipe
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedTime
import dev.loom.bodycontrol.SensedVitals
import dev.loom.bodycontrol.decodeBodyRequest
import dev.loom.bodycontrol.decodeBodyResponse
import dev.loom.bodycontrol.encodeBodyResponse
import dev.loom.contract.BlockFace
import dev.loom.contract.Decision
import dev.loom.contract.EquipmentDestination
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import dev.loom.mind.CognitionBridge
import dev.loom.mind.CognitionCallbacks
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val FLOW_TIMEOUT: Duration = Duration.ofSeconds(5)
private val CONTRACT_DIR: Path = Paths.get(System.getProperty("user.dir"), "..", "contract").toRealPath()
private val BODY_FIXTURES: Path = CONTRACT_DIR.resolve("fixtures/body-control/valid")
private val DECISION_FIXTURES: Path = CONTRACT_DIR.resolve("fixtures/valid")
private val RESEARCH_DIR: Path = Paths.get(System.getProperty("user.dir"), "..", "research").toRealPath()

private fun senseIdOf(request: BodyRequest): String? =
    when (request) {
        is BodyRequest.Eat -> request.senseId
        is BodyRequest.Flee -> request.senseId
        is BodyRequest.Dig -> request.senseId
        is BodyRequest.Collect -> request.senseId
        is BodyRequest.Place -> request.senseId
        is BodyRequest.Equip -> request.senseId
        is BodyRequest.Craft -> request.senseId
        is BodyRequest.Attack -> request.senseId
        is BodyRequest.Sleep -> request.senseId
        else -> null
    }

/** One frame the peer saw, on the connection it arrived on. */
private data class WireFrame(
    val connection: Int,
    val line: String,
    val request: BodyRequest,
    val answer: String,
)

/** The unchanged scene every sense reports, until a test mutates it. */
private fun sceneReport(
    request: BodyRequest.Sense,
    version: Int,
): BodyResponse.SenseReport =
    BodyResponse.SenseReport(
        protocol = request.protocol,
        requestId = request.requestId,
        agentId = request.agentId,
        state = LifecycleState.READY,
        position = listOf(1.5, 64.0, 2.5),
        vitals = SensedVitals(health = 17.0, food = 9, saturation = 2.5),
        time = SensedTime(timeOfDay = 14_000, day = 2, isDay = false),
        ground = SensedGround(standingIn = "minecraft:air", standingOn = "minecraft:stone"),
        entities =
            listOf(
                SensedEntity(what = "minecraft:zombie", position = listOf(4.5, 64.0, 2.5), visible = true),
            ),
        entitiesOmitted = 0,
        blocks =
            listOf(
                SensedBlock(
                    what = if (version == 0) "minecraft:stone" else "minecraft:cobblestone",
                    position = listOf(2, 63, 2),
                    visible = true,
                ),
                SensedBlock(what = "minecraft:red_bed", position = listOf(3, 64, 2), visible = true),
            ),
        blocksOmitted = 0,
        items =
            listOf(
                SensedItem(what = "minecraft:apple", count = 1, position = listOf(3.5, 64.0, 2.5), visible = true),
            ),
        itemsOmitted = 0,
        inventory =
            listOf(
                SensedStack(slot = 36, what = "minecraft:dirt", count = 2),
                SensedStack(slot = 37, what = "minecraft:bread", count = 3),
                SensedStack(slot = 38, what = "minecraft:iron_helmet", count = 1),
                SensedStack(slot = 39, what = "minecraft:iron_sword", count = 1),
                SensedStack(slot = 40, what = "minecraft:oak_log", count = 2),
            ),
        recipes =
            listOf(
                SensedRecipe(what = "minecraft:oak_planks", resultCount = 4, maxCrafts = 2, requiresTable = false),
            ),
        recipesOmitted = 0,
    )

/**
 * A loopback body-control peer that speaks only canonical fixture frames.
 *
 * It keeps the body's per-connection sense memory: an action naming a sense
 * this connection never answered is `senseForgotten`, one naming an ordinal the
 * answer never offered is the operation's `*Unnamed` fixture, and one against a
 * scene that changed after the sense is the operation's changed fixture.
 */
private class ScenePeer(
    private val identity: PlayerIdentity,
) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val nextConnection = AtomicInteger()
    private val running = AtomicBoolean(true)
    private val senses = ConcurrentHashMap<Pair<Int, String>, Pair<Int, BodyResponse.SenseReport>>()
    val frames = CopyOnWriteArrayList<WireFrame>()
    val sceneVersion = AtomicInteger()

    val endpoint: BodyControlEndpoint = BodyControlEndpoint("127.0.0.1", server.localPort)

    init {
        Thread({ acceptLoop() }, "scene-peer-accept").apply {
            isDaemon = true
            start()
        }
    }

    /** Drops the body's whole sense memory, as a superseded bounded memory would. */
    fun forgetSenses() = senses.clear()

    fun actions(): List<WireFrame> =
        frames.filter {
            it.request !is BodyRequest.Handshake &&
                it.request !is BodyRequest.Sense &&
                it.request !is BodyRequest.Status
        }

    fun openConnections(): Int = sockets.count { !it.isClosed }

    private fun acceptLoop() {
        while (running.get()) {
            val socket =
                try {
                    server.accept()
                } catch (_: SocketException) {
                    return
                }
            sockets += socket
            val connection = nextConnection.incrementAndGet()
            Thread({ serve(socket, connection) }, "scene-peer-$connection").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(
        socket: Socket,
        connection: Int,
    ) {
        socket.use {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = socket.getOutputStream()
            while (true) {
                val line = runCatching { input.readLine() }.getOrNull() ?: return
                val request = decodeBodyRequest(line)
                val answer = answer(connection, request)
                frames += WireFrame(connection, line, request, answer)
                output.write((answer + "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun answer(
        connection: Int,
        request: BodyRequest,
    ): String =
        when (request) {
            is BodyRequest.Handshake -> stamped("response-handshake", request, username = identity.username)
            is BodyRequest.Status -> stamped("response-status", request)
            is BodyRequest.Shutdown -> stamped("response-shutdown", request)
            is BodyRequest.Halt -> stamped("response-halt", request)
            is BodyRequest.MoveTo -> stamped("response-moveTo", request)
            is BodyRequest.Sense -> {
                val version = sceneVersion.get()
                val report = sceneReport(request, version)
                senses[connection to request.requestId] = version to report
                encodeBodyResponse(report)
            }
            is BodyRequest.Eat ->
                offered(connection, request, "eat" to "slot-changed") {
                    it.inventory.any { stack -> stack.slot == request.slot }
                }
            is BodyRequest.Flee ->
                offered(connection, request, "flee" to "threat-gone") {
                    request.entity in it.entities.indices
                }
            is BodyRequest.Dig ->
                offered(connection, request, "dig" to "block-changed") {
                    request.block in it.blocks.indices
                }
            is BodyRequest.Collect ->
                offered(connection, request, "collect" to "item-changed") {
                    request.item in it.items.indices
                }
            is BodyRequest.Place ->
                offered(connection, request, "place" to "block-changed") {
                    request.block in it.blocks.indices && it.inventory.any { stack -> stack.slot == request.slot }
                }
            is BodyRequest.Equip ->
                offered(connection, request, "equip" to "slot-changed") {
                    it.inventory.any { stack -> stack.slot == request.slot }
                }
            is BodyRequest.Craft ->
                offered(connection, request, "craft" to "ingredients-changed") {
                    request.recipe in it.recipes.indices
                }
            is BodyRequest.Attack ->
                offered(connection, request, "attack" to "target-changed") {
                    request.entity in it.entities.indices && it.inventory.any { stack -> stack.slot == request.slot }
                }
            is BodyRequest.Sleep ->
                offered(connection, request, "sleep" to "bed-changed") {
                    request.block in it.blocks.indices && it.blocks[request.block].what.endsWith("_bed")
                }
        }

    private fun offered(
        connection: Int,
        request: BodyRequest,
        fixtures: Pair<String, String>,
        names: (BodyResponse.SenseReport) -> Boolean,
    ): String {
        val (operation, changed) = fixtures
        val senseId = requireNotNull(senseIdOf(request))
        val (version, report) =
            senses[connection to senseId]
                ?: return stamped("response-$operation-sense-forgotten", request)
        return when {
            !names(report) -> stamped("response-$operation-${unnamedSuffix(operation)}", request)
            version != sceneVersion.get() -> stamped("response-$operation-$changed", request)
            else -> stamped("response-$operation", request)
        }
    }

    private fun unnamedSuffix(operation: String): String =
        when (operation) {
            "eat", "equip" -> "slot-unnamed"
            "flee" -> "threat-unnamed"
            "dig", "place" -> "block-unnamed"
            "collect" -> "item-unnamed"
            "craft" -> "recipe-unnamed"
            "attack" -> "target-unnamed"
            else -> "bed-unnamed"
        }

    private fun stamped(
        fixture: String,
        request: BodyRequest,
        username: String? = null,
    ): String {
        val raw = Files.readString(BODY_FIXTURES.resolve("$fixture.json"))
        val fields = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        fields["requestId"] = JsonPrimitive(request.requestId)
        fields["agentId"] = JsonPrimitive(request.agentId)
        username?.let { fields["username"] = JsonPrimitive(it) }
        // Re-validate through the production codec so a stamped frame is still a contract frame.
        return encodeBodyResponse(decodeBodyResponse(JsonObject(fields).toString()))
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}

private class LoopbackProcess(
    override val identity: PlayerIdentity,
    override val endpoint: BodyControlEndpoint,
) : SupervisedBodyProcess {
    private val alive = AtomicBoolean(true)
    override val exitCode: CompletionStage<Int> = CompletableFuture()

    override fun isAlive(): Boolean = alive.get()

    fun stop() = alive.set(false)
}

private class LoopbackSupervisor(
    private val endpoint: BodyControlEndpoint,
) : BodyProcessSupervisor {
    private val processes = CopyOnWriteArrayList<LoopbackProcess>()

    override fun start(identity: PlayerIdentity): CompletionStage<SupervisedBodyProcess> =
        CompletableFuture.completedFuture(LoopbackProcess(identity, endpoint).also(processes::add))

    override fun stop(process: SupervisedBodyProcess): CompletionStage<Unit> {
        (process as LoopbackProcess).stop()
        return CompletableFuture.completedFuture(Unit)
    }

    override fun close() = processes.forEach(LoopbackProcess::stop)
}

private class AcceptingServerState : PlayerServerState {
    val interactionAudits = CopyOnWriteArrayList<ServerInteractionClaim>()

    override fun connected(identity: PlayerIdentity): Boolean = true

    override fun isDead(agentId: AgentId): Boolean = false

    override fun dayOrdinal(): Long = 2

    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> =
        entities.mapIndexed { index, entity ->
            UUID.nameUUIDFromBytes("${identity.username}:$index:${entity.what}".toByteArray(StandardCharsets.UTF_8))
        }

    override fun itemSubjectsOf(
        identity: PlayerIdentity,
        items: List<SensedItem>,
    ): List<UUID?> =
        items.mapIndexed { index, item ->
            UUID.nameUUIDFromBytes("${identity.username}:item:$index:${item.what}".toByteArray(StandardCharsets.UTF_8))
        }

    override fun captureSenseAudit(identity: PlayerIdentity): SenseAuditSnapshot? = null

    override fun auditSense(
        identity: PlayerIdentity,
        snapshot: SenseAuditSnapshot?,
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
        interactionAudits += claim
        return ServerInteractionAudit(true, "offline scene agrees with ${claim.controlRequest.requestId}")
    }
}

private class Flow(
    val port: PlayerBodyPort,
    val peers: Map<AgentId, ScenePeer>,
    val server: AcceptingServerState,
    val records: CopyOnWriteArrayList<BodyEventRecord>,
    val pools: List<ExecutorService>,
    val schedulers: List<ScheduledExecutorService>,
)

/** Picks the next intent from the decoded perception the mind was handed. */
private typealias Choice = (Perception) -> Intent

class CrossLayerActionFlowTest {
    @TempDir
    lateinit var temporary: Path

    private val tick = AtomicLong(1_000)
    private val flows = mutableListOf<Flow>()

    @AfterEach
    fun close() {
        flows.forEach { flow ->
            runCatching { flow.port.close() }
            flow.peers.values.forEach(ScenePeer::close)
            flow.pools.forEach(ExecutorService::shutdownNow)
            flow.schedulers.forEach(ScheduledExecutorService::shutdownNow)
        }
    }

    @Test
    fun `rest and idle stay distinct, each send one bounded halt, and change no vital`() {
        val agent = AgentId("flow-rest")
        val flow = flow(listOf(agent))
        val peer = flow.peers.getValue(agent)
        val before = perceive(flow, agent)

        val rest = flow.port.apply(agent, Intent.Rest, before.requestId).await()
        val afterRest = perceive(flow, agent)
        val idle = flow.port.apply(agent, Intent.Idle, afterRest.requestId).await()
        val afterIdle = perceive(flow, agent)

        assertEquals(IntentOutcome.Accepted("resting"), rest)
        assertEquals(IntentOutcome.Accepted("standing still"), idle)
        val halts = peer.actions().map(WireFrame::request)
        assertEquals(2, halts.size, "rest and idle must each send exactly one request: $halts")
        assertTrue(halts.all { it is BodyRequest.Halt }, "rest and idle must be halts: $halts")
        assertEquals(2, halts.map(BodyRequest::requestId).distinct().size)
        listOf(afterRest, afterIdle).forEach { reading ->
            assertEquals(before.selfState.health, reading.selfState.health)
            assertEquals(before.selfState.food, reading.selfState.food)
            assertEquals(before.selfState.saturation, reading.selfState.saturation)
        }
        val applied = flow.records.filter { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(listOf(before.requestId, afterRest.requestId), applied.map(BodyEventRecord::requestId))
        assertTrue(applied[0].detail != applied[1].detail, "rest and idle must stay distinct in evidence")
        val sinceFirstAction = flow.records.dropWhile { it.event != BodyEvent.INTENT_APPLIED }
        assertTrue(
            sinceFirstAction.none { it.event == BodyEvent.ENERGY_CHANGED || it.event == BodyEvent.HEALTH_CHANGED },
            "neither rest nor idle may report a vital change: ${sinceFirstAction.map(BodyEventRecord::event)}",
        )
    }

    @Test
    fun `every model-visible actionable ref drives its matching action through every layer`() {
        val agent = AgentId("flow-enumerate")
        val flow = flow(listOf(agent))
        val peer = flow.peers.getValue(agent)
        val census = perceive(flow, agent)
        val plans = actionPlans(census)
        val covered = plans.map { it.first }.toSet()
        val everyRef =
            census.surroundings.map { "${it.kind}:${it.what}" } +
                census.selfState.inventorySummary.map { "inventory:${it.what}" } +
                census.selfState.craftingOptions.map { "recipe:${it.what}" }
        assertEquals(
            everyRef.toSet(),
            covered.map { it.substringBefore('#') }.toSet(),
            "an offered ref has no matching action",
        )

        val outcomes = mutableListOf<Pair<String, IntentOutcome>>()
        val cognition = cognition(flow) { perception -> plans[outcomes.size].second(perception) }
        plans.forEach { (label, _) ->
            val actionsBefore = peer.actions().size
            val perception = requireNotNull(cognition.consider(agent, Trigger.GoalReview).await())
            val appliedTo = { record: BodyEventRecord ->
                record.requestId == perception.requestId && record.event == BodyEvent.INTENT_APPLIED
            }
            awaitFlow { flow.records.any(appliedTo) }
            val applied = flow.records.last(appliedTo)
            val outcome = applied.outcome
            outcomes += label to IntentOutcome.Accepted(applied.detail.ifEmpty { label })
            val wire = peer.actions().drop(actionsBefore)
            assertEquals(1, wire.size, "$label must send exactly one body action: $wire")
            val sent = wire.single()
            assertTrue(
                peer.frames.any {
                    it.connection == sent.connection &&
                        it.request is BodyRequest.Sense &&
                        it.request.requestId == senseIdOf(sent.request)
                },
                "$label must use the connection whose sense offered its ref",
            )
            val answer = Json.parseToJsonElement(sent.answer).jsonObject
            val success =
                Json.parseToJsonElement(
                    Files.readString(BODY_FIXTURES.resolve("response-${answer["op"]?.toString()?.trim('"')}.json")),
                )
                    .jsonObject
            assertEquals(
                success["outcome"],
                answer["outcome"],
                "$label against the unchanged scene must be the body's success frame, got ${sent.answer}",
            )
            assertTrue(outcome == null || !outcome.contains("unsupported"), "$label was unsupported: $applied")
        }
        assertDecisionChains(flow, agent)
        normalize(flow)
    }

    @Test
    fun `every action a decision fixture can carry is supported by the production player body`() {
        val agent = AgentId("flow-supported")
        val flow = flow(listOf(agent))
        val actions =
            Files.list(DECISION_FIXTURES).use { paths ->
                paths
                    .filter { it.fileName.toString().startsWith("decision-") }
                    .map { LoomJson.decodeFromString(Decision.serializer(), Files.readString(it)).intent }
                    .toList()
            }
        val unsupported =
            actions.mapNotNull { fixtureIntent ->
                val perception = perceive(flow, agent)
                val intent = executable(fixtureIntent, perception)
                val outcome = flow.port.apply(agent, intent, perception.requestId).await()
                (outcome as? IntentOutcome.Unsupported)?.let { fixtureIntent::class.simpleName }
            }
        // The one shared classification names which contract intents are dormant.
        val support =
            Json.parseToJsonElement(Files.readString(CONTRACT_DIR.resolve("fixtures/capability-support.json")))
        val dormant =
            support.jsonObject.getValue("intents").jsonObject
                .filterValues { it.jsonObject.getValue("status").jsonPrimitive.content != "supported" }
                .keys
                .map { it.replaceFirstChar(Char::uppercase) }
                .sorted()
        assertEquals(
            dormant,
            unsupported.distinct().sorted(),
            "the production player body must support exactly the actions the shared classification calls supported",
        )
    }

    @Test
    fun `refs fail closed across agents, stale senses, mutated worlds and settled requests`() {
        val alpha = AgentId("flow-alpha")
        val beta = AgentId("flow-beta")
        val flow = flow(listOf(alpha, beta))
        val alphaPeer = flow.peers.getValue(alpha)
        val betaPeer = flow.peers.getValue(beta)

        // Cross-agent leakage: beta cannot spend alpha's ref, even under its own fresh request.
        val alphaSeen = perceive(flow, alpha)
        val betaSeen = perceive(flow, beta)
        val alphaStone = alphaSeen.surroundings.first { it.kind == ObservationKind.BLOCK }.ref
        val leaked = flow.port.apply(beta, Intent.Dig(alphaStone), alphaSeen.requestId).await()
        assertTrue(leaked is IntentOutcome.Rejected, "beta spent alpha's request: $leaked")
        assertEquals(0, betaPeer.actions().size, "a leaked ref must never reach beta's body")
        val betaStone = betaSeen.surroundings.first { it.kind == ObservationKind.BLOCK }.ref
        if (betaStone != alphaStone) {
            val crossed = flow.port.apply(beta, Intent.Dig(alphaStone), betaSeen.requestId).await()
            assertTrue(crossed is IntentOutcome.Rejected, "alpha's ref resolved in beta's request: $crossed")
        }

        // World mutation after sense: the body's changed answer is a refusal, never success.
        val mutated = perceive(flow, alpha)
        alphaPeer.sceneVersion.incrementAndGet()
        val dig = mutated.surroundings.first { it.kind == ObservationKind.BLOCK }
        val changed = flow.port.apply(alpha, Intent.Dig(dig.ref), mutated.requestId).await()
        assertTrue(changed is IntentOutcome.Rejected, "a dig after the block changed was $changed")
        assertTrue(alphaPeer.actions().last().answer.contains("blockChanged"))

        // Stale sense: the body forgot the sense its ref came from.
        val stale = perceive(flow, alpha)
        alphaPeer.forgetSenses()
        val bread = stale.selfState.inventorySummary.first { it.what.endsWith("bread") }
        val forgotten = flow.port.apply(alpha, Intent.Eat(bread.ref), stale.requestId).await()
        assertTrue(forgotten is IntentOutcome.Rejected, "a forgotten sense was spent: $forgotten")
        assertTrue(alphaPeer.actions().last().answer.contains("senseForgotten"))

        // Expired request: refs outlive neither their window nor reach the wire.
        val expired = perceive(flow, alpha)
        tick.addAndGet(REQUEST_REFS_LIFETIME_TICKS + 1)
        flow.port.tick(tick.get())
        val before = alphaPeer.actions().size
        val late =
            flow.port
                .apply(
                    alpha,
                    Intent.Dig(expired.surroundings.first { it.kind == ObservationKind.BLOCK }.ref),
                    expired.requestId,
                )
                .await()
        assertTrue(late is IntentOutcome.Rejected, "an expired request's ref was spent: $late")
        assertEquals(before, alphaPeer.actions().size, "an expired ref reached the body")

        // Settled request: one decision per request; the second spend never reaches the wire.
        val settled = perceive(flow, alpha)
        val stone = settled.surroundings.first { it.kind == ObservationKind.BLOCK }.ref
        assertTrue(flow.port.apply(alpha, Intent.Dig(stone), settled.requestId).await() is IntentOutcome.Accepted)
        val afterFirst = alphaPeer.actions().size
        val replay = flow.port.apply(alpha, Intent.Dig(stone), settled.requestId).await()
        assertTrue(replay is IntentOutcome.Rejected, "a settled request's ref was spent twice: $replay")
        assertEquals(afterFirst, alphaPeer.actions().size)
        assertTrue(alphaPeer.frames.all { it.request.agentId == alpha.value })
        assertTrue(betaPeer.frames.all { it.request.agentId == beta.value })
    }

    @Test
    fun `concurrent decisions each spend their own request on their own connection and clean up`() {
        val agent = AgentId("flow-concurrent")
        val flow = flow(listOf(agent))
        val peer = flow.peers.getValue(agent)
        val first = perceive(flow, agent)
        val second = perceive(flow, agent)
        val firstRef = first.surroundings.first { it.kind == ObservationKind.BLOCK }.ref
        val secondRecipe = second.selfState.craftingOptions.single().ref

        val outcomes =
            listOf(
                flow.port.apply(agent, Intent.Dig(firstRef), first.requestId),
                flow.port.apply(agent, Intent.Craft(secondRecipe, 1), second.requestId),
            ).map { it.await() }
        assertTrue(outcomes.all { it is IntentOutcome.Accepted }, "concurrent decisions were $outcomes")

        val dig = peer.actions().single { it.request is BodyRequest.Dig }
        val craft = peer.actions().single { it.request is BodyRequest.Craft }
        assertEquals(first.requestId, (dig.request as BodyRequest.Dig).senseId)
        assertEquals(second.requestId, (craft.request as BodyRequest.Craft).senseId)
        listOf(dig, craft).forEach { frame ->
            assertTrue(
                peer.frames.any {
                    it.connection == frame.connection &&
                        it.request is BodyRequest.Sense &&
                        it.request.requestId == senseIdOf(frame.request)
                },
                "${frame.request::class.simpleName} crossed onto another sense's connection",
            )
        }
        awaitFlow { peer.openConnections() <= 1 }
        flow.port.close()
        awaitFlow { peer.openConnections() == 0 }
    }

    private fun actionPlans(perception: Perception): List<Pair<String, Choice>> {
        val plans = mutableListOf<Pair<String, Choice>>()

        fun ref(
            p: Perception,
            kind: ObservationKind,
            what: String,
        ) = p.surroundings.first { it.kind == kind && it.what == what }.ref

        fun stack(
            p: Perception,
            what: String,
        ) = p.selfState.inventorySummary.first { it.what == what }.ref
        val weapon = perception.selfState.inventorySummary.first { it.what.endsWith("sword") }.what
        val support = perception.selfState.inventorySummary.first { it.what.endsWith("dirt") }.what
        perception.surroundings.forEach { seen ->
            val label = "${seen.kind}:${seen.what}"
            when (seen.kind) {
                ObservationKind.ENTITY -> {
                    plans += "$label#flee" to { p -> Intent.Flee(ref(p, seen.kind, seen.what)) }
                    plans += "$label#attack" to { p ->
                        Intent.Attack(ref(p, seen.kind, seen.what), stack(p, weapon), 1, false)
                    }
                }
                ObservationKind.BLOCK ->
                    if (seen.what.endsWith("_bed")) {
                        plans += "$label#sleep" to { p -> Intent.Sleep(ref(p, seen.kind, seen.what)) }
                    } else {
                        plans += "$label#dig" to { p -> Intent.Dig(ref(p, seen.kind, seen.what)) }
                        plans += "$label#place" to { p ->
                            Intent.Place(ref(p, seen.kind, seen.what), stack(p, support), BlockFace.UP)
                        }
                    }
                ObservationKind.DROPPED_ITEM -> {
                    plans += "$label#collect" to { p -> Intent.Collect(ref(p, seen.kind, seen.what), true) }
                    plans += "$label#collect-in-reach" to { p -> Intent.Collect(ref(p, seen.kind, seen.what), false) }
                }
            }
        }
        perception.selfState.inventorySummary.forEach { stack ->
            val label = "inventory:${stack.what}"
            plans +=
                when {
                    stack.what.endsWith("bread") -> "$label#eat" to { p -> Intent.Eat(stack(p, stack.what)) }
                    stack.what.endsWith("helmet") ->
                        "$label#equip" to { p -> Intent.Equip(stack(p, stack.what), EquipmentDestination.HEAD) }
                    else -> "$label#equip" to { p -> Intent.Equip(stack(p, stack.what), EquipmentDestination.HAND) }
                }
        }
        perception.selfState.craftingOptions.forEach { option ->
            plans += "recipe:${option.what}#craft" to { p ->
                Intent.Craft(p.selfState.craftingOptions.first { it.what == option.what }.ref, 1)
            }
        }
        return plans
    }

    private fun executable(
        fixture: Intent,
        p: Perception,
    ): Intent {
        val block = p.surroundings.first { it.kind == ObservationKind.BLOCK && !it.what.endsWith("_bed") }.ref
        val bed = p.surroundings.first { it.kind == ObservationKind.BLOCK && it.what.endsWith("_bed") }.ref
        val entity = p.surroundings.first { it.kind == ObservationKind.ENTITY }.ref
        val item = p.surroundings.first { it.kind == ObservationKind.DROPPED_ITEM }.ref

        fun stack(suffix: String) = p.selfState.inventorySummary.first { it.what.endsWith(suffix) }.ref
        return when (fixture) {
            is Intent.Say, Intent.Rest, Intent.Idle, is Intent.MoveTo -> fixture
            is Intent.Flee -> Intent.Flee(entity)
            is Intent.Eat -> Intent.Eat(stack("bread"))
            is Intent.Dig -> Intent.Dig(block)
            is Intent.Collect -> Intent.Collect(item, fixture.approach)
            is Intent.Place -> Intent.Place(block, stack("dirt"), BlockFace.UP)
            is Intent.Equip -> Intent.Equip(stack("helmet"), EquipmentDestination.HEAD)
            is Intent.Craft -> Intent.Craft(p.selfState.craftingOptions.single().ref, 1)
            is Intent.Attack -> Intent.Attack(entity, stack("sword"), 1, false)
            is Intent.Sleep -> Intent.Sleep(bed)
        }
    }

    /** A mind that sees only contract bytes and answers only from a canonical fixture's bytes. */
    private fun cognition(
        flow: Flow,
        choose: Choice,
    ): EmbodiedCognition =
        EmbodiedCognition(
            bodies = flow.port,
            mind =
                CognitionBridge { sent ->
                    val wire = LoomJson.encodeToString(Perception.serializer(), sent)
                    val perception = LoomJson.decodeFromString(Perception.serializer(), wire)
                    assertEquals(wire, LoomJson.encodeToString(Perception.serializer(), perception))
                    val template = Files.readString(DECISION_FIXTURES.resolve("decision-rest.json"))
                    val fields = Json.parseToJsonElement(template).jsonObject.toMutableMap()
                    fields["requestId"] = JsonPrimitive(perception.requestId)
                    fields["agentId"] = JsonPrimitive(perception.agentId)
                    fields["intent"] = LoomJson.encodeToJsonElement(Intent.serializer(), choose(perception))
                    CompletableFuture.completedFuture(
                        LoomJson.decodeFromString(Decision.serializer(), JsonObject(fields).toString()),
                    )
                },
            mainThread = MainThread { task -> task() },
            journal = DecisionJournal(BodyEventLog(flow.records::add), tick::get),
            callbacks = CognitionCallbacks(),
        )

    private fun assertDecisionChains(
        flow: Flow,
        agent: AgentId,
    ) {
        val decided = flow.records.filter { it.event == BodyEvent.DECIDED && it.agentId == agent.value }
        assertTrue(decided.isNotEmpty())
        decided.forEach { decision ->
            val chain = flow.records.filter { it.requestId == decision.requestId }.map(BodyEventRecord::event)
            assertTrue(BodyEvent.PERCEIVED in chain, "decision ${decision.requestId} has no perception")
            assertEquals(
                1,
                chain.count { it == BodyEvent.INTENT_APPLIED || it == BodyEvent.DECISION_DISCARDED },
                "decision ${decision.requestId} must settle exactly once: $chain",
            )
            assertTrue(chain.indexOf(BodyEvent.DECIDED) < chain.indexOf(BodyEvent.INTENT_APPLIED))
        }
    }

    /** Runs the research normalizer over the JVM's own evidence lines. */
    private fun normalize(flow: Flow) {
        val body = temporary.resolve("body-events.jsonl")
        Files.writeString(body, flow.records.joinToString("") { it.toJsonLine() + "\n" })
        val harness = Files.writeString(temporary.resolve("harness.jsonl"), "")
        val output = temporary.resolve("normalized.jsonl")
        val script =
            """
            const { normalizeEvidence, deriveMetrics } = require(process.argv[1] + '/lib/evidence.js')
            const result = normalizeEvidence({
              experimentId: 'offline-cross-layer', bodyFile: process.argv[2], harnessFile: process.argv[3],
              outputFile: process.argv[4], maxInputBytes: 1 << 24, maxOutputBytes: 1 << 24, maxRecords: 10000,
              requirePerceptionAudit: true
            })
            const metrics = deriveMetrics(result.records, [])
            process.stdout.write(JSON.stringify({ records: result.records.length, redactions: result.redactions, metrics }))
            """.trimIndent()
        val process =
            ProcessBuilder(
                "node",
                "-e",
                script,
                RESEARCH_DIR.toString(),
                body.toString(),
                harness.toString(),
                output.toString(),
            )
                .redirectErrorStream(true)
                .start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(FLOW_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
        assertEquals(0, process.exitValue(), "evidence normalization failed: $text")
        val summary = Json.parseToJsonElement(text).jsonObject
        assertEquals(flow.records.size.toString(), summary["records"].toString())
        val normalized = Files.readAllLines(output)
        assertEquals(flow.records.size, normalized.size)
        flow.records
            .filter { it.event == BodyEvent.DECIDED }
            .forEach { decided ->
                assertNotNull(
                    normalized.firstOrNull {
                        it.contains("\"decided\"") && it.contains(requireNotNull(decided.requestId))
                    },
                    "normalized evidence lost decision ${decided.requestId}",
                )
            }
    }

    private fun perceive(
        flow: Flow,
        agent: AgentId,
    ): Perception = requireNotNull(flow.port.perceive(agent, Trigger.GoalReview).await())

    private fun flow(agents: List<AgentId>): Flow {
        val server = AcceptingServerState()
        val records = CopyOnWriteArrayList<BodyEventRecord>()
        val peers = linkedMapOf<AgentId, ScenePeer>()
        val pools = mutableListOf<ExecutorService>()
        val schedulers = mutableListOf<ScheduledExecutorService>()
        val infrastructures =
            agents.map { agent ->
                val identity = playerIdentityOf(agent)
                val peer = ScenePeer(identity).also { peers[agent] = it }
                val io = Executors.newFixedThreadPool(4).also(pools::add)
                val scheduler = Executors.newSingleThreadScheduledExecutor().also(schedulers::add)
                val requestIds = BodyControlRequestIds(scope = identity.username)
                PlayerBodyInfrastructure(
                    identity = identity,
                    settings =
                        PlayerBodySettings(
                            bodyDirectory = temporary.toAbsolutePath(),
                            processDirectory = temporary.resolve(identity.username).toAbsolutePath(),
                            controlEndpoint = peer.endpoint,
                            connectTimeout = Duration.ofSeconds(1),
                            requestTimeout = Duration.ofSeconds(2),
                            movementTimeout = Duration.ofSeconds(2),
                            startupTimeout = Duration.ofSeconds(2),
                            shutdownTimeout = Duration.ofSeconds(1),
                            healthCheckIntervalTicks = 100_000,
                            healthFailureLimit = 3,
                        ),
                    supervisor = LoopbackSupervisor(peer.endpoint),
                    connector = SocketBodyControlConnector(Duration.ofSeconds(1), Duration.ofSeconds(2), requestIds),
                    requestIds = requestIds,
                    io = io,
                    scheduler = scheduler,
                )
            }
        val port =
            PlayerBodyPort(
                infrastructures = infrastructures,
                authority =
                    PlayerBodyAuthority(
                        main = { task -> synchronized(this) { task.run() } },
                        registerIdentity = {},
                        serverState = server,
                        eventLog = BodyEventLog(records::add),
                        currentTick = tick::get,
                    ),
                startupConcurrency = 1,
            )
        val flow = Flow(port, peers, server, records, pools, schedulers)
        flows += flow
        agents.forEach { port.spawn(it).await() }
        return flow
    }

    private fun awaitFlow(condition: () -> Boolean) {
        val deadline = System.nanoTime() + FLOW_TIMEOUT.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not hold before the flow deadline" }
            Thread.sleep(5)
        }
    }

    private fun <T> CompletionStage<T>.await(): T =
        toCompletableFuture().get(
            FLOW_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS,
        )
}
