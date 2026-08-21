/*
 * The console/in-game driver that exercises the body port and the mind end to end.
 *
 * It is written against `BodyPort` and `EmbodiedCognition` alone - no adapter
 * type, no NMS, no transport - which is what makes it evidence that both ports are
 * real seams rather than decorative ones: the same commands would drive any future
 * body flavor or mind deployment unchanged.
 *
 * `move` still supplies a hardcoded contract `Intent.MoveTo`, which is how a body
 * is driven with the mind deliberately out of the loop. `think` is the round trip:
 * it perceives, sends that perception to the real cognition service, and the
 * decision that comes back is applied to the body on a later tick.
 */
package dev.loom.plugin

import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Trigger
import dev.loom.mind.EmbodiedCognition
import kotlinx.serialization.encodeToString
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Argument count for `move <agentId> <x> <y> <z>`. */
private const val MOVE_ARG_COUNT = 5

private const val ARG_AGENT = 1
private const val ARG_X = 2
private const val ARG_Y = 3
private const val ARG_Z = 4

/** Number of coordinate components in a `move` target. */
private const val COORDINATE_COUNT = 3

/**
 * Drives the body port and the mind from a command.
 *
 * @param bodies the port. Typed as [BodyPort]: this class must never know which
 *   flavor is behind it.
 * @param mind the perception -> decision -> action round trip. Typed as
 *   [EmbodiedCognition] for the same reason: which mind is behind it, and how it
 *   is reached, is not this class's business.
 * @param report where human-readable results go in addition to the sender.
 */
class BodyCommand(
    private val bodies: BodyPort,
    private val mind: EmbodiedCognition,
    private val report: (String) -> Unit,
) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val senderLocation = (sender as? Player)?.location?.let { BodyLocation(it.world.name, it.x, it.y, it.z) }
        val result = reply(args, senderLocation)
        result?.let {
            sender.sendMessage(it)
            report(it)
        }
        return result != null
    }

    /**
     * The human-readable reply to [args], or `null` for an unknown subcommand.
     *
     * [senderLocation] is where the sender stands, if the sender has a place in
     * the world at all; `spawn` places the body there. Everything below here is
     * engine-free, so command handling is testable without a server.
     */
    internal fun reply(
        args: Array<out String>,
        senderLocation: BodyLocation?,
    ): String? =
        when (args.firstOrNull()) {
            "spawn" -> spawn(senderLocation, args)
            "move" -> move(args)
            "perceive" -> perceive(args)
            "think" -> think(args)
            "despawn" -> despawn(args)
            "list" -> "live bodies: ${bodies.liveBodies().joinToString(", ").ifEmpty { "none" }}"
            else -> null
        }

    private fun spawn(
        at: BodyLocation?,
        args: Array<out String>,
    ): String {
        val agentId = args.getOrNull(ARG_AGENT)
        return when {
            agentId == null -> "usage: spawn <agentId>"
            at == null -> "spawn must be run by a player, whose location the body is placed at"
            else ->
                withAgent(agentId) { agent ->
                    when (val result = bodies.spawn(agent, at)) {
                        is BodySpawnResult.Spawned -> "spawned body for ${result.agentId}"
                        is BodySpawnResult.Rejected -> "spawn rejected for ${result.agentId}: ${result.reason}"
                    }
                }
        }
    }

    private fun move(args: Array<out String>): String {
        val target =
            if (args.size < MOVE_ARG_COUNT) {
                emptyList()
            } else {
                listOfNotNull(
                    args[ARG_X].toLongOrNull(),
                    args[ARG_Y].toLongOrNull(),
                    args[ARG_Z].toLongOrNull(),
                )
            }
        return when {
            args.size < MOVE_ARG_COUNT -> "usage: move <agentId> <x> <y> <z>"
            target.size < COORDINATE_COUNT -> "x, y and z must be whole numbers"
            // A hardcoded contract Intent stands in for the mind for now.
            else -> withAgent(args[ARG_AGENT]) { "move outcome: ${bodies.apply(it, Intent.MoveTo(target))}" }
        }
    }

    private fun perceive(args: Array<out String>): String {
        val agentId = args.getOrNull(ARG_AGENT) ?: return "usage: perceive <agentId>"
        return withAgent(agentId) { agent ->
            // Printed as contract JSON: the exact bytes the mind will receive.
            bodies.perceive(agent, Trigger.GoalReview)
                ?.let { LoomJson.encodeToString(it) }
                ?: "no body for $agentId"
        }
    }

    /**
     * The one full round trip: perceive, send to the mind, act on what comes back.
     *
     * The reply says only that the perception is on its way, because that is all
     * that has happened by the time this returns - the whole point of the bridge
     * is that this call does not wait for the mind. The decision itself arrives in
     * [report] a moment later, from the main thread.
     */
    private fun think(args: Array<out String>): String {
        val agentId = args.getOrNull(ARG_AGENT) ?: return "usage: think <agentId>"
        return withAgent(agentId) { agent ->
            mind.consider(agent, Trigger.GoalReview)
                ?.let { "sent ${it.importance} perception ${it.requestId} for $agentId to the mind" }
                ?: "no body for $agentId"
        }
    }

    private fun despawn(args: Array<out String>): String {
        val agentId = args.getOrNull(ARG_AGENT) ?: return "usage: despawn <agentId>"
        return withAgent(agentId) { agent ->
            if (bodies.despawn(agent)) {
                "despawned body for $agentId"
            } else {
                "no body for $agentId"
            }
        }
    }
}
