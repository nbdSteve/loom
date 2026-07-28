/*
 * The one adapter behind the body port: server-side NMS entity bodies.
 *
 * This class is the translation layer. Above it, only Loom domain and contract
 * types exist; below it, `net.minecraft` and CraftBukkit. It is deliberately the
 * only class that knows both vocabularies, so swapping the body flavor means
 * writing a sibling implementation of `BodyPort` and changing one wiring line.
 *
 * All NMS contact in the plugin lives in this package. BodyBoundaryTest fails the
 * build if it leaks out.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.IntentOutcome
import dev.loom.body.Mortality
import dev.loom.body.WorldFootprint
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.bukkit.Server
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.plugin.Plugin

/**
 * How far around each living agent Loom keeps the world loaded, in chunks.
 *
 * One chunk of margin is the smallest that leaves a body room to walk without
 * stepping into an unloaded chunk before the next recompute, and it keeps the
 * footprint small so a spread-out population stays affordable.
 */
private const val FOLLOW_RADIUS_CHUNKS = 1

/**
 * Gives agents bodies as custom NMS entities on a vanilla type identity.
 *
 * Every method must be called on the server main thread, as [BodyPort] requires.
 *
 * @param server the Bukkit server, used only to resolve worlds by name.
 * @param owner the plugin whose chunk tickets hold the world loaded around agents.
 * @param eventLog append-only sink every body event is recorded to.
 * @param currentTick supplies the server tick for stamping log records.
 */
class NmsEntityBodyPort(
    private val server: Server,
    owner: Plugin,
    eventLog: BodyEventLog,
    private val currentTick: () -> Long,
) : BodyPort {
    private val bodies = mutableMapOf<AgentId, LoomBodyEntity>()
    private val journal = BodyEventJournal(eventLog, currentTick)
    private val mortality = Mortality()
    private val heldChunks =
        HeldChunks(server, owner, WorldFootprint(radiusChunks = FOLLOW_RADIUS_CHUNKS), journal)

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult {
        val world = server.getWorld(at.world) as CraftWorld?
        return when {
            // Death is checked first and refused outright: Loom is permadeath, so a
            // dead agent id is never embodied again, whatever else the request says.
            mortality.isDead(agentId) -> reject(agentId, "this agent has died, and Loom agents do not return")
            bodies.containsKey(agentId) -> reject(agentId, "a body already exists for this agent")
            world == null -> reject(agentId, "unknown world '${at.world}'")
            else -> place(agentId, at, world)
        }
    }

    /**
     * Puts a body for [agentId] into [world] at [at].
     *
     * The chunk hold is taken before the body is registered as live and so reapable:
     * a body enters [bodies] only once Loom already holds the world around it, so
     * there is never a live-but-unheld body a chunk unload could turn into a false
     * permadeath. A hold that cannot be fully acquired refuses the spawn rather than
     * leaving a body unheld; the chunks taken are reconciled away by the next follow,
     * which sees no body. The body is likewise only registered once the world has
     * accepted it, so a refused spawn leaves no half-live body behind.
     */
    private fun place(
        agentId: AgentId,
        at: BodyLocation,
        world: CraftWorld,
    ): BodySpawnResult {
        val body = newLoomBody(world.handle, agentId, at)
        // SpawnReason.CUSTOM is how a plugin spawns its own entity: CraftBukkit's
        // add-entity path applies natural-spawn gating to any other reason, which
        // would silently discard the body on a world with spawning restricted. The
        // hold is attempted first, so addFreshEntity never runs for a body Loom could
        // not keep loaded.
        val rejection =
            when {
                !heldChunks.holdAround(at) -> "could not hold the world around the spawn"
                !world.handle.addFreshEntity(body, CreatureSpawnEvent.SpawnReason.CUSTOM) ->
                    "the world refused the spawn"
                else -> null
            }
        if (rejection != null) {
            return reject(agentId, rejection)
        }
        bodies[agentId] = body
        journal.record(agentId, BodyEvent.SPAWNED, "at ${at.world} ${position(at.x, at.y, at.z)}")
        return BodySpawnResult.Spawned(agentId, at)
    }

    /**
     * Takes the body of [agentId] out of the world without killing the agent.
     *
     * Despawning is not death: it is how Loom itself puts a body away, as on plugin
     * unload, so the agent id stays embodiable. Only [reap] - a body that actually
     * died in-world - is terminal.
     */
    override fun despawn(agentId: AgentId): Boolean {
        val body = bodies.remove(agentId) ?: return false
        body.discard()
        journal.record(agentId, BodyEvent.DESPAWNED, "removed from world")
        return true
    }

    override fun apply(
        agentId: AgentId,
        intent: Intent,
    ): IntentOutcome {
        val body = bodies[agentId] ?: return IntentOutcome.NoSuchBody
        val outcome = body.applyIntent(intent)
        journal.record(agentId, BodyEvent.INTENT_APPLIED, "${describe(intent)} -> ${describe(outcome)}")
        return outcome
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? {
        val body = bodies[agentId] ?: return null
        val perception = capturePerception(agentId, body, trigger, currentTick())
        journal.record(agentId, BodyEvent.PERCEIVED, "${perception.surroundings.size} observation(s)")
        return perception
    }

    override fun tick(tick: Long) {
        // Actuation only. Cognition is event-driven and is never called from here.
        // Iterate a snapshot so a retired body can be removed mid-loop.
        for ((agentId, body) in bodies.entries.toList()) {
            if (!body.isAlive) {
                reap(agentId)
                continue
            }
            val step = body.advanceMovement()
            if (step != null) {
                val where = position(body.x, body.y, body.z)
                journal.record(agentId, BodyEvent.MOVED, "to $where yaw ${coordinate(step.yaw.toDouble())}")
            }
        }
        // Loom has no human players, so the agents must carry their own loaded world
        // with them. Runs after movement so the footprint follows where bodies are.
        heldChunks.follow(bodies.values.map { BodyLocation(it.level().world.name, it.x, it.y, it.z) })
    }

    /**
     * Retires the body of an agent that has died in-world.
     *
     * This removes the now-phantom body - keeping [liveBodies] honest and stopping
     * [perceive]/[apply] from working on a dead entity - and records the death.
     * Recording it is what makes death terminal: the agent identity dies with the
     * body, so [spawn] refuses that id from here on. There is no revive path, by
     * design.
     *
     * Treating any removal as a death is sound only because of the world-loading
     * invariant: a living agent always sits in a chunk Loom holds loaded and ticking,
     * so its body cannot be removed by a chunk unload. Weaken that footprint and this
     * turns into a false-death bug, where an agent dies for want of anyone nearby.
     */
    private fun reap(agentId: AgentId) {
        // OWED SIGNAL: this is terminal news the mind is owed once cognition is wired
        // - "this agent has DIED", not "your body is gone, take another". It is
        // deliberately not sent from here: tick stays actuation-only and the contract
        // carries no death Trigger yet. Retire now, signal later.
        bodies.remove(agentId)
        if (mortality.recordDeath(agentId)) {
            journal.record(agentId, BodyEvent.DIED, "died in-world; this agent will not return")
        }
    }

    override fun liveBodies(): Set<AgentId> = bodies.keys.toSet()

    /**
     * Removes every body and lets the world go.
     *
     * Called when the plugin unloads. Releasing the chunks matters as much as removing
     * the bodies: a plugin chunk ticket outlives the tick that added it, so skipping
     * this would leave the world pinned loaded with no Loom running.
     */
    fun despawnAll() {
        for (agentId in bodies.keys.toList()) {
            despawn(agentId)
        }
        heldChunks.releaseAll()
    }

    private fun reject(
        agentId: AgentId,
        reason: String,
    ): BodySpawnResult.Rejected {
        journal.record(agentId, BodyEvent.SPAWN_REJECTED, reason)
        return BodySpawnResult.Rejected(agentId, reason)
    }
}
