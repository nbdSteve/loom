/*
 * Per-agent bounded body-control health monitoring.
 */
package dev.loom.body.player

import dev.loom.body.BodyLifecycle
import dev.loom.body.EventTag

/** Trips one runtime only after its configured consecutive status failures. */
internal class PlayerHealthMonitor(
    private val context: PlayerBodyContext,
    private val registry: PlayerRuntimeRegistry,
    private val transitions: PlayerLifecycleTransitions,
) {
    fun check(
        runtime: PlayerRuntime,
        tick: Long,
    ) {
        if (runtime.healthCheckInFlight || tick < runtime.nextHealthCheckTick) return
        val process = runtime.process ?: return
        runtime.healthCheckInFlight = true
        runtime.nextHealthCheckTick = tick + context.infrastructure.settings.healthCheckIntervalTicks
        context.infrastructure.supervisor.health(process).whenComplete { healthy, failure ->
            context.authority.main.execute {
                settle(runtime, process, healthy, failure)
            }
        }
    }

    private fun settle(
        runtime: PlayerRuntime,
        process: SupervisedBodyProcess,
        healthy: Boolean?,
        failure: Throwable?,
    ) {
        if (!registry.isCurrent(runtime) ||
            runtime.process !== process ||
            runtime.lifecycle != BodyLifecycle.READY
        ) {
            return
        }
        runtime.healthCheckInFlight = false
        if (failure == null && healthy == true) {
            runtime.consecutiveHealthFailures = 0
            return
        }
        runtime.consecutiveHealthFailures += 1
        if (runtime.consecutiveHealthFailures >= context.infrastructure.settings.healthFailureLimit) {
            transitions.markDisconnected(
                runtime,
                "body-control health failed ${runtime.consecutiveHealthFailures} consecutive checks",
                EventTag.BODY_HEALTH_FAILED,
            )
            context.infrastructure.supervisor.stop(process)
        }
    }
}
