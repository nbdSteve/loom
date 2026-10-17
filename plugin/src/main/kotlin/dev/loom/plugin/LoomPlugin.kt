/*
 * Product wiring for Loom's persistent Mineflayer player body.
 */
package dev.loom.plugin

import dev.loom.body.BodyEventLog
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.FileBodyEventLog
import dev.loom.body.player.BodyControlRequestIds
import dev.loom.body.player.PlayerBodyAuthority
import dev.loom.body.player.PlayerBodyDeployment
import dev.loom.body.player.PlayerBodyFleetSettings
import dev.loom.body.player.PlayerBodyInfrastructure
import dev.loom.body.player.PlayerBodyPort
import dev.loom.body.player.ProcessBodySupervisor
import dev.loom.body.player.SocketBodyControlConnector
import dev.loom.mind.AppliedDecision
import dev.loom.mind.AsyncCognitionBridge
import dev.loom.mind.CognitionCallbacks
import dev.loom.mind.CognitionSettings
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.GoalLedger
import dev.loom.mind.GoalReviewSchedule
import dev.loom.mind.PerAgentCognitionBridge
import dev.loom.mind.StepSettlement
import dev.loom.mind.tcp.TcpCognitionTransport
import dev.loom.mind.watching
import dev.loom.observe.Observatory
import dev.loom.observe.ObservedEventLog
import dev.loom.witness.ClaimAudit
import dev.loom.witness.DurablePermadeath
import dev.loom.witness.FileDeathLedger
import dev.loom.witness.Heard
import dev.loom.witness.ManagedIdentity
import dev.loom.witness.PlayerWitness
import dev.loom.witness.SpeechJournal
import dev.loom.witness.SpeechWitness
import dev.loom.witness.WitnessJournal
import dev.loom.witness.WitnessRegistry
import dev.loom.witness.bukkit.livePlaceOf
import dev.loom.witness.bukkit.playerWitnessSeam
import dev.loom.witness.bukkit.speechWitnessSeam
import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

private const val EVENT_LOG_FILE = "body-events.jsonl"
private const val DEATH_LEDGER_FILE = "player-deaths.jsonl"
private const val BODY_COMMAND = "loombody"
private const val WATCH_COMMAND = "loomwatch"
private const val OBSERVER_REFRESH_TICKS = 5L

internal data class WitnessRuntime(
    val permadeath: DurablePermadeath,
    val claimAudit: ClaimAudit,
    val speech: SpeechWitness,
)

/**
 * Chooses the product body and mind deployments and owns their shutdown order.
 *
 * Every `/loombody create` starts the Mineflayer player process wired here.
 */
class LoomPlugin : JavaPlugin() {
    private var eventLog: FileBodyEventLog? = null
    private var bodyPort: PlayerBodyPort? = null
    private var bridge: AsyncCognitionBridge? = null
    private var transport: TcpCognitionTransport? = null
    private var bodyTicks: BukkitTask? = null
    private var nativeSources: AutoCloseable? = null
    private var observatory: Observatory? = null
    private var display: BukkitTask? = null
    private var hearer: ((Heard) -> Unit)? = null

    // One schedule: the mind tells it when a goal's step settles, and the driver claims from it.
    internal val schedule = GoalReviewSchedule()
    private var cognition: EmbodiedCognition? = null

    /**
     * Exact player identities Loom is authorised to witness.
     *
     * Registration happens before a child is launched, so a fast login cannot
     * arrive in a half-registered state.
     */
    val managedPlayers: WitnessRegistry = WitnessRegistry()

    override fun onEnable() {
        val file =
            FileBodyEventLog(
                dataFolder.toPath().resolve(EVENT_LOG_FILE),
                onWriteFailure = { failure ->
                    logger.warning("body event log write failed: ${failure.message}")
                },
                onRecordsDropped = { count ->
                    logger.warning("body event log is incomplete: $count records never reached the file")
                },
            )
        eventLog = file

        val watched = Observatory()
        observatory = watched
        val goals = GoalLedger(recipes = BukkitRecipeInputs(server))
        val log =
            ObservedEventLog(goals.watching(file), watched::saw) { failure ->
                logger.warning("observer display failed: ${failure.message}")
            }

        // Replay durable death state and register the login/death listener before
        // any command can launch a managed player.
        val speechEndings = SpeechEndings(this)
        val witnessed = speechEndings.watching(log)
        val witness = witnessPlayers(witnessed)
        speechEndings.witness = witness.speech

        val port = createPlayerBody(witnessed, witness)
        bodyPort = port

        val mind = connectMind(port, witnessed, goals, schedule)
        cognition = mind
        hearer = hearerOf(port, mind)
        val native =
            NativeCognitionStart.fromEnvironment { nativeSources = startNativeSources(port, mind, witnessed, witness) }
        bind(
            BODY_COMMAND,
            BodyCommand(port, mind, { message -> logger.info(message) }, native::begin),
            "the body port has no driver",
        )
        bind(WATCH_COMMAND, WatchCommand(watched), "a run cannot be watched")

        bodyTicks =
            server.scheduler.runTaskTimer(
                this,
                Runnable {
                    val now = server.currentTick.toLong()
                    port.tick(now)
                    witness.speech.tick(now)
                },
                1L,
                1L,
            )
        display =
            server.scheduler.runTaskTimer(
                this,
                Runnable { watched.refresh(server.currentTick.toLong()) },
                OBSERVER_REFRESH_TICKS,
                OBSERVER_REFRESH_TICKS,
            )
        native.enable()
        port.reportConfiguredStartup(logger)

        logger.info("Loom observation ready: /loomwatch <agentId> follows one agent, read-only")
    }

    override fun onDisable() {
        // Stop every source of new body or cognition work first.
        nativeSources?.close()
        nativeSources = null
        hearer = null
        bodyTicks?.cancel()
        bodyTicks = null

        display?.cancel()
        display = null
        observatory?.shutDown()
        observatory = null

        // End every chain still waiting for the mind, then release cognition reads before
        // waiting for player shutdown. No decision can arrive after the body boundary has
        // been torn down.
        cognition?.shutDown()
        cognition = null
        transport?.close()
        transport = null
        bridge?.close()
        bridge = null

        // Player shutdown is bounded and complete before the event log closes:
        // control answer, Minecraft disconnect, control listener close, process exit.
        bodyPort?.close()
        bodyPort = null
        eventLog?.close()
        eventLog = null
    }

    private fun bind(
        name: String,
        executor: CommandExecutor,
        consequence: String,
    ) {
        val command = getCommand(name)
        if (command == null) {
            logger.severe("command '$name' is not declared in plugin.yml; $consequence")
        } else {
            command.setExecutor(executor)
        }
    }

    private fun createPlayerBody(
        eventLog: BodyEventLog,
        witness: WitnessRuntime,
    ): PlayerBodyPort {
        val fleet =
            PlayerBodyFleetSettings.fromEnvironment(
                serverPort = server.port,
                dataDirectory = dataFolder.toPath().toAbsolutePath().normalize(),
            )
        val bukkitMain = BukkitMainThread(this)
        val interactionEvents =
            BukkitInteractionEvents(
                server,
                fleet.deployments.map { it.identity.uuid }.toSet(),
            )
        server.pluginManager.registerEvents(interactionEvents, this)
        val ownDrops = BukkitOwnDrops(server, fleet.deployments.map { it.identity.uuid }.toSet())
        server.pluginManager.registerEvents(ownDrops, this)
        val authority =
            PlayerBodyAuthority(
                main = Executor { command -> bukkitMain.execute { command.run() } },
                registerIdentity = { identity ->
                    managedPlayers.register(
                        ManagedIdentity(
                            identity.agentId,
                            identity.username,
                            identity.uuid,
                        ),
                    )
                },
                serverState =
                    BukkitPlayerServerState(
                        server = server,
                        permadeath = witness.permadeath,
                        claimAudit = witness.claimAudit,
                        interactionEvents = interactionEvents,
                    ),
                eventLog = eventLog,
                currentTick = { server.currentTick.toLong() },
                speechClaims = sentSpeechOf(witness.speech, bukkitMain) { server.currentTick.toLong() },
                ground = BukkitBlockGround(server),
                yields = BukkitBlockYields(server),
                ownDrops = ownDrops,
            )
        val infrastructures =
            fleet.deployments.map { deployment ->
                infrastructureFor(deployment, fleet.ioWorkersPerAgent)
            }
        val port =
            PlayerBodyPort(
                infrastructures = infrastructures,
                authority = authority,
                startupConcurrency = fleet.startupConcurrency,
            )
        logger.info(
            "Loom body fleet ready: ${fleet.deployments.size} configured player(s), " +
                "startup concurrency ${fleet.startupConcurrency}, " +
                "${fleet.ioWorkersPerAgent} I/O worker(s) per agent",
        )
        return port
    }

    /**
     * Builds the one perception -> cognition -> action path.
     *
     * The transport remains lazy: a missing mind does not prevent a player body
     * from existing, and its first request fails through the ordinary recorded
     * `cognitionUnavailable` path.
     */
    private fun connectMind(
        bodies: BodyPort,
        eventLog: BodyEventLog,
        goals: GoalLedger,
        settlement: StepSettlement,
    ): EmbodiedCognition {
        val settings = CognitionSettings.fromEnvironment()
        val wire =
            TcpCognitionTransport(
                settings.host,
                settings.port,
                settings.connectTimeout,
                settings.readTimeout,
            )
        transport = wire
        val reportFailure: (dev.loom.contract.Perception, dev.loom.mind.CognitionFailure) -> Unit =
            { perception, failure ->
                logger.warning(
                    "cognition unavailable for ${perception.agentId}: ${failure::class.simpleName}",
                )
            }
        val async =
            AsyncCognitionBridge(
                wire,
                settings.timeout,
                settings.workers,
                settings.queueDepth,
                reportFailure,
            )
        bridge = async
        val fair =
            PerAgentCognitionBridge(
                delegate = async,
                maxOutstandingPerAgent = settings.perAgentPending,
                onFailure = reportFailure,
            )

        logger.info(
            "Loom cognition bridge ready: mind at ${settings.host}:${settings.port}, " +
                "${settings.workers} worker(s), queue ${settings.queueDepth}, " +
                "${settings.perAgentPending} outstanding request(s) per agent",
        )
        return EmbodiedCognition(
            bodies = bodies,
            mind = fair,
            mainThread = BukkitMainThread(this),
            journal = DecisionJournal(eventLog, currentTick = { server.currentTick.toLong() }),
            goals = goals,
            callbacks =
                CognitionCallbacks(
                    onStepSettled = settlement,
                    onApplied = ::reportApplied,
                    onUnavailable = { perception, failure ->
                        logger.warning(
                            "no decision for ${perception.agentId} " +
                                "(request ${perception.requestId}): ${failure::class.simpleName}",
                        )
                    },
                    onBodyUnavailable = { agent, failure ->
                        logger.warning("body unavailable for $agent: ${failure::class.simpleName}")
                    },
                ),
        )
    }

    /**
     * Replays tombstones, installs the server witness, and exposes the same ledger
     * and journal to claim auditing. The listener is registered only after replay.
     */
    private fun witnessPlayers(eventLog: BodyEventLog): WitnessRuntime {
        val permadeath =
            DurablePermadeath(
                FileDeathLedger(dataFolder.toPath().resolve(DEATH_LEDGER_FILE)),
            )
        val journal = WitnessJournal(eventLog)
        val witness =
            PlayerWitness(
                managedPlayers,
                permadeath,
                journal,
                currentTick = { server.currentTick.toLong() },
                alert = { detail -> logger.severe(detail) },
            )
        server.pluginManager.registerEvents(playerWitnessSeam(witness, this), this)
        val claims =
            ClaimAudit(
                registry = managedPlayers,
                journal = journal,
                currentTick = { server.currentTick.toLong() },
            )
        logger.info(
            "Loom player witness ready: ${managedPlayers.size} managed identities, permadeath is durable",
        )
        val speech =
            SpeechWitness(
                registry = managedPlayers,
                journal = SpeechJournal(eventLog),
                placeOf = { identity -> livePlaceOf(server.getPlayer(identity.uuid)) },
                isDead = permadeath::isDead,
                hear = { heard -> hearer?.invoke(heard) },
            )
        server.pluginManager.registerEvents(speechWitnessSeam(managedPlayers, speech, this), this)
        return WitnessRuntime(permadeath, claims, speech)
    }

    private fun reportApplied(applied: AppliedDecision) {
        val decision = applied.decision
        logger.info(
            "decision ${decision.requestId} for ${decision.agentId}: ${decision.intent} " +
                "(${decision.provenance.model}/${decision.provenance.promptVersion} " +
                "at ${decision.provenance.tier}) -> ${applied.outcome}",
        )
    }
}

private fun infrastructureFor(
    deployment: PlayerBodyDeployment,
    ioWorkers: Int,
): PlayerBodyInfrastructure {
    val identity = deployment.identity
    val settings = deployment.settings
    val requestIds = BodyControlRequestIds(scope = identity.username)
    val io =
        Executors.newFixedThreadPool(
            ioWorkers,
            namedDaemonThreads("loom-body-${identity.username}-io"),
        )
    val scheduler =
        Executors.newSingleThreadScheduledExecutor(
            namedDaemonThreads("loom-body-${identity.username}-keepalive"),
        )
    val connector =
        SocketBodyControlConnector(
            connectTimeout = settings.connectTimeout,
            requestTimeout = settings.requestTimeout,
            requestIds = requestIds,
        )
    return PlayerBodyInfrastructure(
        identity = identity,
        settings = settings,
        supervisor = ProcessBodySupervisor(settings, connector, requestIds, io),
        connector = connector,
        requestIds = requestIds,
        io = io,
        scheduler = scheduler,
    )
}

private fun PlayerBodyPort.reportConfiguredStartup(logger: Logger) {
    configuredStartup.whenComplete { startup, failure ->
        if (failure != null || startup == null) {
            logger.severe("configured player fleet startup failed before results were available")
            return@whenComplete
        }
        logger.info(
            "Loom body fleet startup settled: ${startup.ready} ready, " +
                "${startup.rejected} rejected",
        )
        startup.results
            .filterValues { it is BodySpawnResult.Rejected }
            .forEach { (agentId, _) ->
                logger.warning("configured player body did not start for $agentId")
            }
    }
}

private fun namedDaemonThreads(prefix: String): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { work ->
        Thread(work, "$prefix-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
