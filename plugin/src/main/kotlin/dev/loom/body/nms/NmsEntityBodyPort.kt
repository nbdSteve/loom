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
import dev.loom.body.AgentVitals
import dev.loom.body.BodyDeparture
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.Metabolism
import dev.loom.body.Mortality
import dev.loom.body.NavigationPolicy
import dev.loom.body.describe
import dev.loom.body.navigableFootprint
import dev.loom.body.tagOf
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import org.bukkit.Server
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.plugin.Plugin

/**
 * Gives agents bodies as custom NMS entities on a vanilla type identity.
 *
 * Every method must be called on the server main thread, as [BodyPort] requires.
 *
 * @param server the Bukkit server, used only to resolve worlds by name.
 * @param owner the plugin whose chunk tickets hold the world loaded around agents.
 * @param eventLog append-only sink every body event is recorded to.
 * @param currentTick supplies the server tick for stamping log records.
 * @param onHoldFailure called when Loom cannot hold the world around a living agent.
 *   Wired to the server log by the plugin: this is a broken invariant rather than an
 *   observation about an agent, so it must be visible during a run and not only to
 *   someone reading the event log back afterwards.
 */
class NmsEntityBodyPort(
    private val server: Server,
    owner: Plugin,
    eventLog: BodyEventLog,
    private val currentTick: () -> Long,
    onHoldFailure: (String) -> Unit = {},
) : BodyPort {
    private val bodies = mutableMapOf<AgentId, LoomBodyEntity>()
    private val journal = BodyEventJournal(eventLog, currentTick)
    private val mortality = Mortality()
    private val metabolism = Metabolism()
    private val vitalsWatch = VitalsWatch(journal)

    /**
     * The one path-request budget, shared by every agent.
     *
     * Server-wide on purpose: a path search is the costliest thing native navigation
     * introduces, so what has to be bounded is the work in a tick rather than the work per
     * agent. A refused request is recorded as a rejected intent. It bounds only the searches
     * Loom asks for; the engine starts others of its own from a block change, which
     * `Navigation.kt` records.
     */
    private val movement = NavigationPolicy()

    /**
     * The world Loom keeps loaded, sized so a path search cannot read past it.
     *
     * Derived rather than chosen here. A search reads unloaded chunks as *air* and
     * answers confidently about ground it never looked at, so the hold has to cover
     * everything a request can *read*, which is wider than anywhere a body can be sent -
     * and that is what `navigableFootprint` works out from the body's own follow range.
     * See `Navigation.kt`.
     */
    private val footprint = navigableFootprint()
    private val heldChunks = HeldChunks(server, owner, footprint, journal, onHoldFailure)

    override fun spawn(
        agentId: AgentId,
        at: BodyLocation,
    ): BodySpawnResult {
        val world = server.getWorld(at.world) as CraftWorld?
        return when {
            // Death is checked first and refused outright: Loom is permadeath, so a
            // dead agent id is never embodied again, whatever else the request says.
            mortality.isDead(agentId) ->
                journal.reject(
                    agentId,
                    "this agent has died, and Loom agents do not return",
                    EventTag.HAS_DIED,
                )
            // The one refusal that leaves the agent embodied, and tagged as such: the
            // spawn is refused *because* the body it asks for is already standing there.
            bodies.containsKey(agentId) ->
                journal.reject(agentId, "a body already exists for this agent", EventTag.ALREADY_EMBODIED)
            world == null -> journal.reject(agentId, "unknown world '${at.world}'", EventTag.UNKNOWN_WORLD)
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
        val hold = heldChunks.holdAround(at)
        // Each refusal carries the tag that says what it leaves the agent as. Both of
        // these leave it with no body at all, unlike the already-embodied refusal above.
        val rejection =
            when {
                !hold.isComplete ->
                    "could not hold the world around the spawn: ${hold.describeFailures()}" to EventTag.WORLD_UNHELD
                !world.handle.addFreshEntity(body, CreatureSpawnEvent.SpawnReason.CUSTOM) ->
                    "the world refused the spawn" to EventTag.WORLD_REFUSED
                else -> null
            }
        if (rejection != null) {
            val (reason, outcome) = rejection
            return journal.reject(agentId, reason, outcome)
        }
        bodies[agentId] = body
        // The spawn is this agent's first energy reading, so it carries one: a survival
        // curve has to start somewhere, and the watch is told about it so the agent's
        // first tick does not restate what this record has just said. The reading is the
        // energy reserve - what living spends and what an unattended agent starves on -
        // with the body's health carried beside it, since a body can be beaten to death
        // on a full reserve and an operator wants both.
        val reserve = reserveVitals(metabolism.energyOf(agentId))
        val health = bodyVitals(body)
        vitalsWatch.noted(agentId, reserve, health)
        journal.record(
            agentId,
            BodyEvent.SPAWNED,
            "at ${at.world} ${position(at.x, at.y, at.z)}",
            vitals = reserve,
            health = health,
        )
        return BodySpawnResult.Spawned(agentId, at)
    }

    /**
     * Takes the body of [agentId] out of the world without killing the agent.
     *
     * Despawning is not death: it is how Loom itself puts a body away, as on plugin
     * unload, so the agent id stays embodiable. Only [die] is terminal.
     */
    override fun despawn(agentId: AgentId): Boolean {
        val body = bodies.remove(agentId) ?: return false
        val reserve = reserveVitals(metabolism.energyOf(agentId))
        val health = bodyVitals(body)
        body.discard()
        vitalsWatch.forget(agentId)
        journal.record(agentId, BodyEvent.DESPAWNED, "removed from world", vitals = reserve, health = health)
        return true
    }

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ): IntentOutcome {
        // Nothing is journaled for a body that is gone: the loss belongs to whoever
        // asked, and for a decision that is BodyEvent.DECISION_DISCARDED, which carries
        // the provenance of the decision that was lost. Recording it here too would
        // double-record every such decision.
        val body = bodies[agentId] ?: return IntentOutcome.NoSuchBody
        // Resting is the one intent with an effect on the agent and not only on its body,
        // so its refill happens here rather than in the entity: a dimension transit
        // replaces a body and must not replace the reserve, so the reserve is held by the
        // port. Standing still is the body flavor's half of it.
        //
        // A reserve that has already run out refuses rather than refills: exhaustion is
        // terminal, and an agent that could rest its way back out of it would not be
        // permadead. Unreachable while tick ends an agent on the tick it runs out, since
        // the body is gone before any decision could arrive - and refused anyway, because
        // that ordering is not something a caller of this port can see.
        val spent = intent is Intent.Rest && !metabolism.restore(agentId)
        val outcome =
            if (spent) {
                IntentOutcome.Rejected("the energy reserve is spent; this agent has ended")
            } else {
                body.applyIntent(intent, movement, currentTick())
            }
        journal.recordAction(
            agentId,
            "${describe(intent)} -> ${describe(outcome)}",
            requestId,
            tagOf(intent),
            tagOf(outcome),
        )
        return outcome
    }

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ): Perception? {
        val body = bodies[agentId] ?: return null
        // One reading, used twice: a decision's cost and the reserve the agent had when it
        // was made belong to the same moment, so the record must state the number the mind
        // was handed rather than a second sample of it. The body's health rides on the change
        // and death records instead of being restated here.
        val energy = metabolism.energyOf(agentId)
        val perception = capturePerception(agentId, body, energy, trigger, currentTick())
        val reserve = reserveVitals(energy)
        vitalsWatch.noted(agentId, reserve)
        journal.recordPerception(
            agentId,
            // The omission is stated rather than left to arithmetic against a bound the
            // record does not carry: a crowded scene trimmed to the bound would otherwise
            // read afterwards as a quiet one, and how often an agent decides on a partial
            // view of its surroundings is one of the numbers a survival run is read for.
            // Read off the perception itself, so the record and the prompt the mind was
            // given can never state different omissions for the same moment.
            "${perception.surroundings.size} observation(s)" +
                omissionOf(perception.surroundingsOmitted),
            perception.requestId,
            tagOf(trigger),
            reserve,
        )
        return perception
    }

    override fun tick(tick: Long) {
        // Actuation only. Cognition is event-driven and is never called from here.
        // The energy reserve is spent from the tick and never from the clock, so the same
        // tick sequence always ends the same agent at the same tick and a paused server
        // starves nobody. It is charged before anything else happens this tick, so a body
        // whose reserve has just run out never takes another step, and the deaths it causes
        // are applied at the end, once every body is where this tick leaves it.
        val exhausted = metabolism.spendUpTo(tick, bodies.keys.toSet())
        // Iterate a snapshot: retiring a body removes it from the map being iterated, and
        // a departure can put a fresh body into it.
        for ((agentId, body) in bodies.entries.toList()) {
            when {
                // A body the world has removed is dealt with before any reserve is read:
                // the engine's leftovers have to be cleared up and a traveller re-embodied
                // wherever it arrived, or an agent starving mid-transit would leave a
                // derelict villager standing in the destination. It still dies below.
                !body.isAlive -> depart(agentId, body)
                // A body whose reserve ran out this tick takes no further step: it is about
                // to be a corpse, and a movement record after a death is a lie.
                agentId in exhausted -> Unit
                // A body Loom drove this tick is a body whose vitals it reads. Both are
                // sampled every tick and VitalsWatch owns the whole of the judgement about
                // which sample deserves a record, so a run's survival curves come out of
                // the log itself without a record per tick.
                //
                // Only this branch reads them, for the same reason a movement record after
                // a death is a lie: the other two have no body of this agent's left to read.
                // A departed body's reading would land *after* the died record that
                // explained it - an in-world run wrote exactly that, twice, off an entity
                // the engine had already removed - and a traveller's would be read off the
                // body it just left behind. Nothing a survival curve needs is lost by it: a
                // died or despawned record states the readings it ended on, and a traveller
                // states its own from the spawn of the body it arrives in.
                else -> {
                    actuate(agentId, body, journal)
                    vitalsWatch.recordChanges(agentId, reserveVitals(metabolism.energyOf(agentId)), bodyVitals(body))
                }
            }
        }
        // Running out of reserve is a certainty rather than an inference about a removal,
        // so it ends the agent wherever this tick left its body - including a body just
        // placed on the far side of a portal. Deaths already recorded stay recorded once.
        for (agentId in exhausted) {
            die(agentId, "spent its energy reserve", bodies[agentId]?.let { bodyVitals(it) })
        }
        // Loom has no human players, so the agents must carry their own loaded world
        // with them, in whichever dimension each one is in. Runs after movement so the
        // footprint follows where bodies are.
        heldChunks.follow(bodies.mapValues { (_, body) -> body.whereItIs() })
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
        // Forgetting matters for the departures that are not deaths: an agent that walks
        // through a portal gets a new body, and its first reading there must not be compared
        // against one from a body it no longer has. It clears what was recorded and reads no
        // entity, so the death branch below still takes the health its body ended on.
        vitalsWatch.forget(agentId)
        // OWED SIGNAL: every branch here is news the mind is owed once cognition is
        // wired - "you have DIED", "you are somewhere else now", "you were nearly turned
        // into a witch" - and the death one is terminal rather than an offer of another
        // body. None is sent from here: tick stays actuation-only and the contract
        // carries no such Trigger yet. Record now, signal later.
        when (departureOf(body)) {
            // Recording the death is what makes it terminal, and it goes through the same
            // funnel as a spent reserve so "died once" does not depend on which cause got
            // there first. The health it died on rides along, since a body can be beaten
            // to death on a full reserve, and is read here rather than above because this is
            // the one branch that has anything to say about it.
            BodyDeparture.DEATH -> die(agentId, "died in-world", bodyVitals(body))

            // The agent walked out of one dimension and into another: the same agent, with
            // its id and everything attached to it, in a new body. The engine leaves Loom
            // nothing it can adopt on the far side, so the arrival is embodied through the
            // ordinary spawn path - which holds the destination before the body counts as
            // live there, and refuses, with its reason recorded, a destination Loom cannot
            // resolve or keep loaded rather than leaving a body somewhere it cannot keep
            // alive.
            BodyDeparture.DIMENSION_TRAVEL -> {
                spawn(agentId, recordTransit(agentId, body, journal))
                // The body arrives standing in the destination portal, so without the
                // traveller's cooldown it would travel straight back, once per tick, forever.
                bodies[agentId]?.let { carryPortalCooldown(body, it) }
            }

            // Reached only if a conversion got past the refusal in BodyConversions and
            // took the body with it. The agent survives it - this is not a death - but it
            // is bodiless until it is embodied again, which is Loom's to do knowingly
            // rather than something to paper over here.
            BodyDeparture.CONVERSION ->
                journal.recordConversion(
                    agentId,
                    "body taken by conversion into ${body.convertedInto}; the agent lives on, unembodied",
                    EventTag.BODY_TAKEN,
                )

            BodyDeparture.STOWED -> Unit
        }
    }

    /**
     * Ends [agentId] for good, because of [cause], recording the death exactly once.
     *
     * The one place an agent ends, whatever ended it. A body killed in-world and a
     * reserve run down to nothing are the same terminal event, so they are recorded by
     * the same code: "died once" must not depend on which cause reached the record
     * first. Recording is what makes it terminal - [spawn] refuses the id from here on
     * and [Mortality] offers no way back - so the death signal the mind is owed is owed
     * exactly once.
     *
     * Taking the body out of the live population is part of ending an agent rather than
     * something each caller remembers to do: past this point nothing perceives, decides
     * or acts for it. A body the world has already removed is simply not there to
     * discard, which is how a death in-world differs from a reserve running out under a
     * body that is still standing.
     *
     * [health] is the body's health at the moment it died, when there was a body to read
     * it from: a starvation happens on a body that is still standing, an in-world death on
     * one the engine has already taken. The reserve is read here, so a death always records
     * both the reserve it had left and the health it had left.
     */
    private fun die(
        agentId: AgentId,
        cause: String,
        health: AgentVitals?,
    ) {
        val reserve = reserveVitals(metabolism.energyOf(agentId))
        bodies.remove(agentId)?.discard()
        vitalsWatch.forget(agentId)
        if (mortality.recordDeath(agentId)) {
            journal.record(
                agentId,
                BodyEvent.DIED,
                "$cause; this agent will not return",
                vitals = reserve,
                health = health,
            )
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
}
