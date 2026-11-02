/*
 * The operator surface for a bounded configured player fleet.
 *
 * Commands start asynchronous work and return immediately. Completion is reported
 * through the plugin logger, because starting a process, waiting for player readiness,
 * cognition, movement and orderly shutdown may each outlive the command invocation.
 */
package dev.loom.plugin

import dev.loom.body.BodyInspection
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.contract.Trigger
import dev.loom.mind.EmbodiedCognition
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

private const val ARG_AGENT = 1
private const val MAX_FAILURE_NAME_CHARS = 48

/**
 * Creates, inspects, thinks for and stops the player identity behind [bodies], begins native
 * cognition when the plugin was started with it held (see [NativeCognitionStart]), and ends a
 * research run's exposure when its cognition admission closes (see [ExposureEnd]).
 *
 * Typed only as [BodyPort], so the command remains an operator surface over the
 * lifecycle seam rather than another route into the Mineflayer implementation.
 */
class BodyCommand(
    private val bodies: BodyPort,
    private val mind: EmbodiedCognition,
    private val report: (String) -> Unit,
    private val begin: () -> Boolean = { false },
    private val endExposure: () -> Boolean = { false },
) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val result = reply(args)
        result?.let {
            sender.sendMessage(it)
            report(it)
        }
        return result != null
    }

    /** The immediate reply to [args], or `null` for an unknown subcommand. */
    internal fun reply(args: Array<out String>): String? =
        when (args.firstOrNull()) {
            "create" -> create(args)
            "inspect" -> inspect(args)
            "think" -> think(args)
            "stop" -> stop(args)
            "begin" ->
                if (begin()) {
                    "native cognition started"
                } else {
                    "native cognition was already running"
                }
            "end-exposure" ->
                if (endExposure()) {
                    "the run's exposure ended: nothing new is perceived, sent or applied"
                } else {
                    "the run's exposure had already ended"
                }
            else -> null
        }

    private fun create(args: Array<out String>): String {
        val raw = args.getOrNull(ARG_AGENT) ?: return "usage: create <agentId>"
        return withAgent(raw) { agent ->
            bodies.spawn(agent).whenComplete { result, failure ->
                report(
                    when {
                        failure != null -> "body startup failed for $agent: ${boundedFailure(failure)}"
                        result is BodySpawnResult.Spawned -> "body ready for ${result.agentId}"
                        result is BodySpawnResult.Rejected ->
                            "body startup rejected for ${result.agentId}: ${result.reason}"
                        else -> "body startup failed for $agent"
                    },
                )
            }
            "starting player body for $agent"
        }
    }

    private fun inspect(args: Array<out String>): String {
        val raw = args.getOrNull(ARG_AGENT) ?: return aggregateInspection()
        return withAgent(raw) { agent ->
            bodies.inspect(agent)?.describe() ?: "no managed body for $agent"
        }
    }

    private fun aggregateInspection(): String {
        val inspections = bodies.inspections()
        if (inspections.isEmpty()) return "no configured player bodies"
        val counts =
            inspections
                .groupingBy { it.state.name.lowercase() }
                .eachCount()
                .toSortedMap()
                .entries
                .joinToString(", ") { (state, count) -> "$state=$count" }
        return "${inspections.size} configured player bodies: $counts"
    }

    private fun think(args: Array<out String>): String {
        val raw = args.getOrNull(ARG_AGENT) ?: return "usage: think <agentId>"
        return withAgent(raw) { agent ->
            mind.consider(agent, Trigger.GoalReview).whenComplete { perception, failure ->
                report(
                    when {
                        failure != null -> "could not perceive for $agent: ${boundedFailure(failure)}"
                        perception == null -> "no ready body for $agent"
                        else ->
                            "sent ${perception.importance} perception ${perception.requestId} " +
                                "for $agent to the mind"
                    },
                )
            }
            "requested a goal review for $agent"
        }
    }

    private fun stop(args: Array<out String>): String {
        val raw = args.getOrNull(ARG_AGENT) ?: return "usage: stop <agentId>"
        return withAgent(raw) { agent ->
            bodies.despawn(agent).whenComplete { stopped, failure ->
                report(
                    when {
                        failure != null -> "body stop failed for $agent: ${boundedFailure(failure)}"
                        stopped == true -> "stopped body for $agent"
                        else -> "no body for $agent"
                    },
                )
            }
            "stopping player body for $agent"
        }
    }

    private fun BodyInspection.describe(): String =
        buildString {
            append(agentId)
            append(": ")
            append(identity)
            append(" is ")
            append(state.name.lowercase())
            endpoint?.let {
                append(" at ")
                append(it)
            }
            append(" (")
            append(detail)
            append(')')
        }

    /** Never quote an unbounded process or transport message into an operator line. */
    private fun boundedFailure(failure: Throwable): String =
        failure::class.simpleName?.take(MAX_FAILURE_NAME_CHARS) ?: "unavailable"
}
