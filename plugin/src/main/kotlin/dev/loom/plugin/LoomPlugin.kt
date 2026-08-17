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
import dev.loom.mind.AppliedDecision
import dev.loom.mind.AsyncCognitionBridge
import dev.loom.mind.CognitionSettings
import dev.loom.mind.DecisionJournal
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.tcp.TcpCognitionTransport
import org.bukkit.plugin.java.JavaPlugin

/** Name of the append-only body-event log inside the plugin's data folder. */
private const val EVENT_LOG_FILE = "body-events.jsonl"

/** The command that drives the port. Must match the `commands:` key in plugin.yml. */
private const val BODY_COMMAND = "loombody"

class LoomPlugin : JavaPlugin() {
    private var eventLog: FileBodyEventLog? = null
    private var bodyPort: NmsEntityBodyPort? = null
    private var bridge: AsyncCognitionBridge? = null
    private var transport: TcpCognitionTransport? = null

    override fun onEnable() {
        val log =
            FileBodyEventLog(
                dataFolder.toPath().resolve(EVENT_LOG_FILE),
                onWriteFailure = { failure ->
                    logger.warning("body event log write failed: ${failure.message}")
                },
                onRecordsDropped = { count ->
                    logger.warning("body event log is incomplete: $count records never reached the file")
                },
            )
        eventLog = log

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

        // Reported rather than ignored: a command missing from the descriptor would
        // otherwise leave the port with no driver and no sign of why.
        val command = getCommand(BODY_COMMAND)
        if (command == null) {
            logger.severe("command '$BODY_COMMAND' is not declared in plugin.yml; the body port has no driver")
        } else {
            command.setExecutor(BodyCommand(port, mind) { message -> logger.info(message) })
        }

        // A conversion cannot be recognised after it happens, so the attempt is caught
        // as it happens; see BodyConversions.
        server.pluginManager.registerEvents(bodyConversionSeam(), this)

        // Actuation runs every tick; cognition does not, and is not called here.
        server.scheduler.runTaskTimer(this, Runnable { port.tick(server.currentTick.toLong()) }, 1L, 1L)

        logger.info("Loom body port ready: server-side entity bodies")
    }

    override fun onDisable() {
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
