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
import dev.loom.body.BodyDeparture
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
            mortality.isDead(agentId) -> journal.reject(agentId, "this agent has died, and Loom agents do not return")
            bodies.containsKey(agentId) -> journal.reject(agentId, "a body already exists for this agent")
            world == null -> journal.reject(agentId, "unknown world '${at.world}'")
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
            return journal.reject(agentId, rejection)
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
        // Iterate a snapshot so a departed body can be handled mid-loop.
        for ((agentId, body) in bodies.entries.toList()) {
            if (!body.isAlive) {
                depart(agentId, body)
                continue
            }
            // Without this a body goes inactive after ten seconds and stops reaching the
            // vanilla hook that carries out portal travel. See readyForTheEngineTick.
            val nearlyBecame = body.readyForTheEngineTick()
            if (nearlyBecame != null) {
                // The agent is alive and still in the body Loom made for it, which is the
                // whole point: being struck by lightning happens to an agent rather than
                // ending one. Recorded on that agent's own chain.
                journal.record(agentId, BodyEvent.CONVERTED, "refused becoming $nearlyBecame; body unchanged")
            }
            val step = body.advanceMovement()
            if (step != null) {
                val where = position(body.x, body.y, body.z)
                journal.record(agentId, BodyEvent.MOVED, "to $where yaw ${coordinate(step.yaw.toDouble())}")
            }
        }
        // Loom has no human players, so the agents must carry their own loaded world
        // with them, in whichever dimension each one is in. Runs after movement so the
        // footprint follows where bodies are.
        heldChunks.follow(bodies.values.map { it.whereItIs() })
    }

    /**
     * Handles a body that is no longer in the world, according to why it left.
     *
     * A body vanishing used to mean one thing, because a body could not leave the
     * world alive: Loom holds every living agent's surroundings loaded and ticking, so
     * a removal could not be a chunk unload, and death was the only explanation left.
     * Travel breaks that reasoning rather than that invariant - a dimension transit
     * removes the travelling body deliberately, alive - so the removal is classified
     * before it is charged to the agent.
     *
     * Only a departure that ends the agent reaches [Mortality]; the rest are
     * continuity, and the agent keeps its id and everything attached to it.
     */
    private fun depart(
        agentId: AgentId,
        body: LoomBodyEntity,
    ) {
        bodies.remove(agentId)
        // OWED SIGNAL: every branch here is news the mind is owed once cognition is
        // wired - "you have DIED", "you are somewhere else now", "you were nearly turned
        // into a witch" - and the death one is terminal rather than an offer of another
        // body. None is sent from here: tick stays actuation-only and the contract
        // carries no such Trigger yet. Record now, signal later.
        when (departureOf(body)) {
            // Recording the death is what makes it terminal: the agent identity dies
            // with the body, so spawn refuses that id from here on, and Mortality offers
            // no way back. Recorded once, so the owed signal is owed once.
            BodyDeparture.DEATH ->
                if (mortality.recordDeath(agentId)) {
                    journal.record(agentId, BodyEvent.DIED, "died in-world; this agent will not return")
                }

            BodyDeparture.DIMENSION_TRAVEL -> resume(agentId, body)

            // Reached only if a conversion got past the refusal in BodyConversions and
            // took the body with it. The agent survives it - this is not a death - but it
            // is bodiless until it is embodied again, which is Loom's to do knowingly
            // rather than something to paper over here.
            BodyDeparture.CONVERSION ->
                journal.record(
                    agentId,
                    BodyEvent.CONVERTED,
                    "body taken by conversion into ${body.convertedInto}; the agent lives on, unembodied",
                )

            BodyDeparture.STOWED -> Unit
        }
    }

    override fun liveBodies(): Set<AgentId> = bodies.keys.toSet()

    /**
     * Gives the agent a body again on the far side of a portal.
     *
     * The engine does not carry a travelling body across: it creates a *plain vanilla*
     * entity in the destination, copies the old body's state onto it, and removes the
     * original. That successor is not a Loom body - it has vanilla goals, no Loom
     * movement, and nothing driving it - so Loom cannot adopt it. It is removed, and
     * Loom places its own body where the traveller arrived, holding the destination
     * before the body is live there exactly as an ordinary spawn does.
     *
     * The agent id, and so the agent's memory and relationships, is untouched by all of
     * this: the same agent walked in one side and walks out the other.
     */
    private fun resume(
        agentId: AgentId,
        traveller: LoomBodyEntity,
    ) {
        val arrival = arrivalAfterTransit(traveller)
        val world = server.getWorld(arrival.world) as CraftWorld?
        if (world == null) {
            journal.record(agentId, BodyEvent.TRAVELLED, "arrived in unknown world '${arrival.world}'; body lost")
            return
        }
        journal.record(
            agentId,
            BodyEvent.TRAVELLED,
            "from ${traveller.level().world.name} to ${arrival.world} ${position(arrival.x, arrival.y, arrival.z)}",
        )
        // Re-placed through the ordinary spawn path so the destination is held before the
        // body counts as live there, which is what keeps every living agent in a ticking
        // chunk in every dimension.
        place(agentId, arrival, world)
        // The body arrives standing in the destination portal, so without the traveller's
        // cooldown it would travel straight back, once per tick, forever.
        bodies[agentId]?.let { carryPortalCooldown(traveller, it) }
    }

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
}
