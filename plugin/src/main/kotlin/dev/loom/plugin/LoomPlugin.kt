/*
 * The plugin entry point: owns the body port and the bridge to the mind, and
 * drives the body from the server tick.
 *
 * This is where the one body flavor and the one mind deployment are chosen. It is
 * the ONLY place that names a concrete adapter; everything else depends on
 * `BodyPort` or on the cognition ports. Swapping either is a change to a single
 * construction line below.
 */
package dev.loom.plugin

import dev.loom.body.BodyEventLog
import dev.loom.body.BodyPort
import dev.loom.body.FileBodyEventLog
import dev.loom.body.nms.NmsEntityBodyPort
import dev.loom.body.nms.bodyConversionSeam
import dev.loom.body.nms.despawnAll
import dev.loom.mind.AppliedDecision
import dev.loom.mind.AsyncCognitionBridge
import dev.loom.mind.AutonomousDriver
import dev.loom.mind.CognitionSettings
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.GoalReviewSchedule
import dev.loom.mind.THREAT_SCAN_CADENCE
import dev.loom.mind.ThreatDriver
import dev.loom.mind.tcp.TcpCognitionTransport
import dev.loom.observe.Observatory
import dev.loom.observe.ObservedEventLog
import dev.loom.witness.DurablePermadeath
import dev.loom.witness.FileDeathLedger
import dev.loom.witness.PlayerWitness
import dev.loom.witness.WitnessJournal
import dev.loom.witness.WitnessRegistry
import dev.loom.witness.bukkit.playerWitnessSeam
import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Name of the append-only body-event log inside the plugin's data folder. */
private const val EVENT_LOG_FILE = "body-events.jsonl"

/**
 * Name of the durable permadeath ledger inside the plugin's data folder.
 *
 * A second file rather than a query over [EVENT_LOG_FILE], because the two answer different
 * questions with different failure modes. The event log is evidence: written off the main
 * thread, bounded so a stalled disk cannot block a tick, and allowed to lose records rather
 * than stall - which is exactly the wrong trade for the input to an authorisation decision.
 * This one is small, written synchronously, and read once at startup.
 */
private const val DEATH_LEDGER_FILE = "player-deaths.jsonl"

/** The command that drives the port. Must match the `commands:` key in plugin.yml. */
private const val BODY_COMMAND = "loombody"

/** The read-only command that watches one agent. Must match plugin.yml too. */
private const val WATCH_COMMAND = "loomwatch"

/**
 * How often the operator display is brought up to date, in ticks.
 *
 * Four times a second: fast enough that an energy bar visibly falls while an agent is
 * being hurt, and slow enough to be nothing next to a tick. It costs one comparison when
 * nobody is watching, which is the usual case.
 */
private const val OBSERVER_REFRESH_TICKS = 5L

/** Milliseconds in one server tick, for stating a schedule's period in ticks. */
private const val MILLIS_PER_TICK = 50L

class LoomPlugin : JavaPlugin() {
    private var eventLog: FileBodyEventLog? = null
    private var bodyPort: NmsEntityBodyPort? = null
    private var bridge: AsyncCognitionBridge? = null
    private var transport: TcpCognitionTransport? = null
    private var autonomy: BukkitTask? = null
    private var vigilance: BukkitTask? = null
    private var observatory: Observatory? = null
    private var display: BukkitTask? = null

    /**
     * The managed set the server-authoritative witness judges against.
     *
     * Deliberately empty, and stays empty until something registers a player body in it. That
     * is what keeps the witness inert: with nothing registered, every login is `Unmanaged`, no
     * record is written and nobody is denied. The runtime cutover that starts a Mineflayer body
     * is what will put an identity here.
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

        // The operator display is a consumer of the record and never a participant in the
        // run: it is handed each record only after the durable log has taken it, and it
        // holds no port, no bridge, and no world handle to disturb anything with.
        val watched = Observatory()
        observatory = watched
        val log =
            ObservedEventLog(file, watched::saw) { failure ->
                logger.warning("observer display failed: ${failure.message}")
            }

        // The single line that binds a body flavor. Everything above the port
        // depends on BodyPort, so a different flavor is a different constructor here.
        val port =
            NmsEntityBodyPort(
                server,
                this,
                log,
                currentTick = { server.currentTick.toLong() },
                // A living agent in a chunk Loom could not hold is the one condition
                // that invalidates a run's permadeath metrics, so it is surfaced here
                // as well as recorded: an operator should not have to read the event
                // log back to learn the footprint was incomplete.
                onHoldFailure = { detail -> logger.warning("world hold failed: $detail") },
            )
        bodyPort = port

        val mind = connectMind(port, log)

        bind(BODY_COMMAND, BodyCommand(port, mind) { message -> logger.info(message) }, "the body port has no driver")
        bind(WATCH_COMMAND, WatchCommand(watched), "a run cannot be watched")

        // A conversion cannot be recognised after it happens, so the attempt is caught
        // as it happens; see BodyConversions.
        server.pluginManager.registerEvents(bodyConversionSeam(), this)

        witnessPlayers(log)

        // Actuation runs every tick; cognition does not, and is not called here.
        server.scheduler.runTaskTimer(this, Runnable { port.tick(server.currentTick.toLong()) }, 1L, 1L)

        // The display is driven from its own timer rather than from the body tick, for the
        // same reason autonomy is: how often an operator's screen is repainted is not the
        // rate anything else in Loom happens at. Every line an observer sees is delivered
        // from here, which is what keeps a main-thread API off the threads that write
        // records.
        display =
            server.scheduler.runTaskTimer(
                this,
                Runnable { watched.refresh(server.currentTick.toLong()) },
                OBSERVER_REFRESH_TICKS,
                OBSERVER_REFRESH_TICKS,
            )

        driveAgents(port, mind)
        watchForThreats(port, mind)

        logger.info("Loom body port ready: server-side entity bodies")
        logger.info("Loom observation ready: /loomwatch <agentId> follows one agent, read-only")
    }

    override fun onDisable() {
        // Stopped before the bridge it feeds: a sweep that ran after the bridge
        // closed would perceive bodies only to fail every request, and one that ran
        // after despawnAll would find no agents at all.
        autonomy?.cancel()
        autonomy = null

        // Vigilance goes down with autonomy and for exactly the same reasons: it is the
        // other thing that submits perceptions of its own accord, so a scan surviving the
        // bridge would fail every request it earned, and one surviving despawnAll would
        // look for threats around bodies that are no longer in the world.
        vigilance?.cancel()
        vigilance = null

        // The display goes down before the bodies do. It is only a consumer of the record,
        // so nothing depends on the order for correctness - but a boss bar left standing
        // after Loom unloaded would go on stating an agent's energy that nothing is
        // watching any more, and `shutDown` is what takes it off the screen. It is the
        // unconditional way out rather than `stop`, which belongs to one observer: Loom is
        // going away for everybody, and there is nobody here to ask on their behalf.
        display?.cancel()
        display = null
        observatory?.shutDown()
        observatory = null

        // The transport goes first: closing its sockets is what releases a worker
        // parked in a read, so the pool shutdown that follows is prompt even when
        // the mind has stopped answering.
        transport?.close()
        transport = null
        bridge?.close()
        bridge = null

        // Bodies are Loom-owned and unsaved, so they must be removed explicitly or
        // they would linger in the world with nothing driving them.
        bodyPort?.despawnAll()
        bodyPort = null
        eventLog?.close()
        eventLog = null
    }

    /**
     * Gives [name] its [executor], or says loudly why the command will not work.
     *
     * Reported rather than ignored: a command missing from the descriptor is registered
     * nowhere and fails silently, so [consequence] names what an operator has just lost.
     */
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

    /**
     * Builds the path from a body to the mind and back.
     *
     * No connection is made here: the transport connects lazily, on the first
     * request, from a bridge worker. That is deliberate - the mind is a separate
     * process with its own lifecycle, and a plugin that refused to enable because
     * cognition was not up yet would make the body depend on the mind for its
     * existence, which is exactly backwards.
     */
    private fun connectMind(
        bodies: BodyPort,
        eventLog: BodyEventLog,
    ): EmbodiedCognition {
        val settings = CognitionSettings()
        // The single line that binds a mind deployment.
        val wire =
            TcpCognitionTransport(
                settings.host,
                settings.port,
                settings.connectTimeout,
                settings.readTimeout,
            )
        transport = wire

        val async =
            AsyncCognitionBridge(
                wire,
                settings.timeout,
                settings.workers,
                settings.queueDepth,
            ) { perception, failure ->
                // Off the main thread, so this only logs.
                logger.warning("cognition unavailable for ${perception.agentId}: $failure")
            }
        bridge = async

        logger.info("Loom cognition bridge ready: mind at ${settings.host}:${settings.port}")
        return EmbodiedCognition(
            bodies,
            async,
            BukkitMainThread(this),
            // The same log the body writes to: one chain per agent, so a decision and
            // the action it caused are read back together.
            DecisionJournal(eventLog, currentTick = { server.currentTick.toLong() }),
            onApplied = ::reportApplied,
            onUnavailable = { perception, failure ->
                logger.warning("no decision for ${perception.agentId} (request ${perception.requestId}): $failure")
            },
        )
    }

    /**
     * Puts the server-authoritative witness on Loom's own player accounts.
     *
     * The construction order is the requirement that tombstones are replayed before login
     * admission begins: [DurablePermadeath]'s constructor reads the ledger, and only once it has
     * returned is a listener registered that could admit anybody. Registering the listener first
     * and replaying afterwards would leave a window in which a dead agent could reconnect, which
     * is a window measured in whatever a server's startup takes.
     *
     * Nothing here starts a player body or registers an identity. With [managedPlayers] empty the
     * witness has no opinion about any connection, which is what makes this inert until the
     * runtime cutover.
     */
    private fun witnessPlayers(eventLog: BodyEventLog) {
        val permadeath = DurablePermadeath(FileDeathLedger(dataFolder.toPath().resolve(DEATH_LEDGER_FILE)))
        val witness =
            PlayerWitness(
                managedPlayers,
                permadeath,
                WitnessJournal(eventLog),
                currentTick = { server.currentTick.toLong() },
                // `severe` rather than `warning`: both conditions it reports mean an invariant
                // this plugin exists to hold is not holding, and an operator has to see it in a
                // log they are already reading rather than only in the event record.
                alert = { detail -> logger.severe(detail) },
            )
        server.pluginManager.registerEvents(playerWitnessSeam(witness, this), this)
        logger.info("Loom player witness ready: ${managedPlayers.size} managed identities, permadeath is durable")
    }

    /**
     * Starts the loop that has agents think and act with nobody driving them.
     *
     * The cadence itself is the schedule's, not this file's - see
     * `DEFAULT_GOAL_REVIEW_CADENCE`. All that happens here is the one conversion an
     * engine-free schedule cannot do: its slice becomes a Bukkit timer period, in
     * ticks, rounded up so a slice can never collapse into "every tick".
     */
    private fun driveAgents(
        bodies: BodyPort,
        mind: EmbodiedCognition,
    ) {
        val schedule = GoalReviewSchedule()
        // Monotonic: a cadence is elapsed time, and a wall clock stepping backwards
        // would stop every agent thinking until it caught up again.
        val driver = AutonomousDriver(bodies, mind, schedule) { System.nanoTime().nanoseconds }
        val period = ticks(schedule.slice)
        autonomy = server.scheduler.runTaskTimer(this, Runnable { driver.sweep() }, period, period)
        logger.info("Loom autonomy running: each agent reviews its goals every ${schedule.cadence}")
    }

    /**
     * Starts the loop that has agents notice something hunting them without being asked.
     *
     * Its own timer rather than a share of autonomy's, because the two answer different
     * questions on cadences three orders of magnitude apart: a goal review is due every
     * `DEFAULT_GOAL_REVIEW_CADENCE`, while a threat is worth noticing within a second of
     * coming into view. The cadence is [THREAT_SCAN_CADENCE]'s, not this file's; all that
     * happens here is the same conversion to a Bukkit period in ticks.
     */
    private fun watchForThreats(
        bodies: BodyPort,
        mind: EmbodiedCognition,
    ) {
        val driver = ThreatDriver(bodies, mind)
        val period = ticks(THREAT_SCAN_CADENCE)
        vigilance = server.scheduler.runTaskTimer(this, Runnable { driver.sweep() }, period, period)
        logger.info("Loom vigilance running: each agent looks for a threat every $THREAT_SCAN_CADENCE")
    }

    /** [duration] as a whole number of server ticks, never rounded down to zero. */
    private fun ticks(duration: Duration): Long {
        val whole = (duration.inWholeMilliseconds + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK
        return maxOf(1L, whole)
    }

    /**
     * Logs one completed decision as the perception -> reasoning -> action -> result
     * chain it is.
     *
     * An operator convenience, not the research record: the durable record is the
     * append-only event log, written from the decision's own path. This exists so a
     * round trip is visible from a console while driving the body by hand.
     */
    private fun reportApplied(applied: AppliedDecision) {
        val decision = applied.decision
        logger.info(
            "decision ${decision.requestId} for ${decision.agentId}: ${decision.intent} " +
                "(${decision.provenance.model}/${decision.provenance.promptVersion} " +
                "at ${decision.provenance.tier}) -> ${applied.outcome}",
        )
    }
}
