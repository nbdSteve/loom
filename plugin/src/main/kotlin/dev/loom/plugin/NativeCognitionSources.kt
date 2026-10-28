/*
 * Loom's three native cognition sources - the goal-review schedule, the threat scan and the
 * damage witness - started together and stopped together.
 *
 * They are one unit because they are what [NativeCognitionStart] holds: a research run needs
 * all of them off until its initial conditions are applied, and a source left out of the hold
 * would be a perception predating those conditions.
 */
package dev.loom.plugin

import dev.loom.body.BodyEventLog
import dev.loom.body.BodyPort
import dev.loom.mind.AutonomousDriver
import dev.loom.mind.DAMAGE_EPISODE_TICKS
import dev.loom.mind.DamageDriver
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.THREAT_SCAN_CADENCE
import dev.loom.mind.ThreatDriver
import dev.loom.witness.DurablePermadeath
import org.bukkit.scheduler.BukkitTask
import kotlin.time.Duration

private const val MILLIS_PER_TICK = 50L

/**
 * The latch over the native sources: [start] runs at enable, or at `/loombody begin` for a held
 * run, which first has the server read every configured agent at the tick its pinned initial
 * conditions must hold (`InitialConditionsWitness`).
 */
internal fun LoomPlugin.nativeCognitionStart(
    bodies: BodyPort,
    eventLog: BodyEventLog,
    start: () -> Unit,
): NativeCognitionStart {
    val witness =
        InitialConditionsWitness(
            eventLog = eventLog,
            agents = { bodies.inspections().map { it.agentId } },
            readingOf = { agent ->
                managedPlayers.byAgent(agent)?.let { server.getPlayer(it.uuid) }?.let(::initialConditionsOf)
            },
            currentTick = { server.currentTick.toLong() },
        )
    return NativeCognitionStart.fromEnvironment(witnessBegin = witness::witness, start = start)
}

/**
 * Starts all three native sources on the main thread and answers the handle that stops them,
 * schedule first, then the threat scan, then the damage witness.
 */
internal fun LoomPlugin.startNativeSources(
    bodies: BodyPort,
    mind: EmbodiedCognition,
    eventLog: BodyEventLog,
    witness: WitnessRuntime,
): AutoCloseable {
    val autonomy = driveAgents(bodies, mind)
    val vigilance = watchForThreats(bodies, mind)
    val damage = witnessDamage(bodies, mind, eventLog, witness.permadeath)
    return AutoCloseable {
        autonomy.cancel()
        vigilance.cancel()
        damage.close()
    }
}

/**
 * Schedules goal reviews by authoritative simulation ticks.
 *
 * Both the timer period and the elapsed reading are server ticks, so a paused
 * server advances no review and a tick sprint advances the same sequence with
 * no dependence on how much wall time it consumed.
 */
private fun LoomPlugin.driveAgents(
    bodies: BodyPort,
    mind: EmbodiedCognition,
): BukkitTask {
    val driver =
        AutonomousDriver(
            bodies = bodies,
            mind = mind,
            schedule = schedule,
            currentTick = { server.currentTick.toLong() },
        )
    val period = schedule.sliceTicks
    val task =
        server.scheduler.runTaskTimer(
            this,
            Runnable { driver.sweep() },
            period,
            period,
        )
    logger.info(
        "Loom autonomy running: each agent reviews its goals every " +
            "${schedule.cadenceTicks} server ticks",
    )
    return task
}

private fun LoomPlugin.watchForThreats(
    bodies: BodyPort,
    mind: EmbodiedCognition,
): BukkitTask {
    val driver = ThreatDriver(bodies, mind)
    val period = ticks(THREAT_SCAN_CADENCE)
    val task =
        server.scheduler.runTaskTimer(
            this,
            Runnable { driver.sweep() },
            period,
            period,
        )
    logger.info("Loom vigilance running: each agent looks for a threat every $THREAT_SCAN_CADENCE")
    return task
}

private fun ticks(duration: Duration): Long {
    val whole = (duration.inWholeMilliseconds + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK
    return maxOf(1L, whole)
}

/**
 * Registers the server-authoritative `damaged` producer after the witness.
 *
 * The returned handle unregisters the listener, then stops the driver.
 */
private fun LoomPlugin.witnessDamage(
    bodies: BodyPort,
    mind: EmbodiedCognition,
    eventLog: BodyEventLog,
    permadeath: DurablePermadeath,
): AutoCloseable {
    val currentTick = { server.currentTick.toLong() }
    val driver =
        DamageDriver(
            bodies = bodies,
            mind = mind,
            mainThread = BukkitMainThread(this),
            isDead = permadeath::isDead,
            eventLog = eventLog,
            currentTick = currentTick,
        )
    val listener =
        BukkitDamageEvents(
            agentOf = { uuid -> managedPlayers.byUuid(uuid)?.agentId },
            currentTick = currentTick,
            sink = driver::witnessed,
        )
    server.pluginManager.registerEvents(listener, this)
    logger.info("Loom damage witness running: one pivotal review per agent per $DAMAGE_EPISODE_TICKS-tick episode")
    return AutoCloseable {
        listener.close()
        driver.close()
    }
}
