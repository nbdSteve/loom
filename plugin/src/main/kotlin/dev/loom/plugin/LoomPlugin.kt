/*
 * The plugin entry point: owns the body port and drives it from the server tick.
 *
 * This is where the one body flavor is chosen. It is the ONLY place that names a
 * concrete adapter; everything else depends on `BodyPort`. Swapping the flavor is
 * a change to the single construction line below.
 */
package dev.loom.plugin

import dev.loom.body.FileBodyEventLog
import dev.loom.body.nms.NmsEntityBodyPort
import dev.loom.body.nms.bodyConversionSeam
import org.bukkit.plugin.java.JavaPlugin

/** Name of the append-only body-event log inside the plugin's data folder. */
private const val EVENT_LOG_FILE = "body-events.jsonl"

/** The command that drives the port. Must match the `commands:` key in plugin.yml. */
private const val BODY_COMMAND = "loombody"

class LoomPlugin : JavaPlugin() {
    private var eventLog: FileBodyEventLog? = null
    private var bodyPort: NmsEntityBodyPort? = null

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
        val port = NmsEntityBodyPort(server, this, log) { server.currentTick.toLong() }
        bodyPort = port

        // Reported rather than ignored: a command missing from the descriptor would
        // otherwise leave the port with no driver and no sign of why.
        val command = getCommand(BODY_COMMAND)
        if (command == null) {
            logger.severe("command '$BODY_COMMAND' is not declared in plugin.yml; the body port has no driver")
        } else {
            command.setExecutor(BodyCommand(port) { message -> logger.info(message) })
        }

        // A conversion cannot be recognised after it happens, so the attempt is caught
        // as it happens; see BodyConversions.
        server.pluginManager.registerEvents(bodyConversionSeam(), this)

        // Actuation runs every tick; cognition does not, and is not called here.
        server.scheduler.runTaskTimer(this, Runnable { port.tick(server.currentTick.toLong()) }, 1L, 1L)

        logger.info("Loom body port ready: server-side entity bodies")
    }

    override fun onDisable() {
        // Bodies are Loom-owned and unsaved, so they must be removed explicitly or
        // they would linger in the world with nothing driving them.
        bodyPort?.despawnAll()
        bodyPort = null
        eventLog?.close()
        eventLog = null
    }
}
