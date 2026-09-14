/*
 * The unused legacy adapter behind the body port: server-side NMS entity bodies.
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

import dev.loom.body.ActiveRetreats
import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.BodyDeparture
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventJournal
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyLocation
import dev.loom.body.BodyPort
import dev.loom.body.BodySpawnResult
import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.body.Metabolism
import dev.loom.body.Mortality
import dev.loom.body.NavigationPolicy
import dev.loom.body.RequestRefs
import dev.loom.body.RetreatAttempt
import dev.loom.body.RetreatEnd
import dev.loom.body.ThreatClock
import dev.loom.body.ThreatWatch
import dev.loom.body.VitalsWatch
import dev.loom.body.completedBodyOperation
import dev.loom.body.describe
import dev.loom.body.navigableFootprint
import dev.loom.body.reserveFor
import dev.loom.body.reserveVitals
import dev.loom.body.tagOf
import dev.loom.body.takesTheBodyOver
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
     * The handles this port has issued for the creatures its agents observed.
     *
     * Held by the port rather than by a body because a handle belongs to the agent's
     * request: a body replaced by a dimension transit must not invalidate a decision
     * already in flight about a creature the agent saw before it crossed.
     */
    private val refs = RequestRefs()

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
     * Whether a hostile in view is worth waking a mind for.
     *
     * The rule is engine-free and this adapter only feeds it engine facts: who is in view
     * (out of the perception the mind would be shown), whether the body has a line to
     * them, and whether they could be walked to. Every bound it applies - the debounce,
     * the pivotal distance, the night's ceiling - is its own, so the adapter cannot widen
     * one by accident. Held here rather than per body because the state it keeps is the
     * agent's, and an agent outlives any one body.
     */
    private val threatWatch = ThreatWatch(ServerThreatClock(server, currentTick))

    /**
     * The flights in progress, one per agent at most.
     *
     * Here rather than in the entity for the same reason the reserve is: a dimension transit
     * replaces a body, and an agent that walked through a portal while running from
     * something is still running from it. See `ActiveRetreat.kt`.
     */
    private val retreats = ActiveRetreats()

    /** The tick's half of a flight: one bounded hop per eligible retreat, and no cognition. */
    private val retreatDrive = RetreatDrive(journal, retreats, movement)

    /**
     * The world Loom keeps loaded, sized so neither a path search nor a perception can
     * read past it.
     *
     * Derived rather than chosen here. A search reads unloaded chunks as *air*, and a
     * perception finds no entities in one and so reports a *quiet scene* rather than
     * failing; each answers confidently about what it never looked at. So the hold has to
     * cover everything either one can *read*, which is wider than anywhere a body can be
     * sent, and that is what `navigableFootprint` works out from the follow range and the
     * perception radius. See `Navigation.kt`.
     */
    private val footprint = navigableFootprint()

    /**
     * The world Loom is holding open around its agents.
     *
     * Visible to the file rather than to the class alone so [despawnAll] can let it go: a
     * ticket outlives the tick that added it, so releasing is as much part of unloading as
     * removing the bodies is.
     */
    internal val heldChunks = HeldChunks(server, owner, footprint, journal, onHoldFailure)
    private val spawner = Spawner()

    override fun spawn(agentId: AgentId) =
        completedBodyOperation(
            server.worlds.firstOrNull()?.spawnLocation?.let { location ->
                spawner.spawn(
                    agentId,
                    BodyLocation(location.world.name, location.x, location.y, location.z),
                )
            } ?: journal.reject(
                agentId,
                "the server has no world in which to place a body",
                EventTag.UNKNOWN_WORLD,
            ),
        )

    private inner class Spawner {
        fun spawn(
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
                    journal.reject(
                        agentId,
                        "a body already exists for this agent",
                        EventTag.ALREADY_EMBODIED,
                    )
                world == null ->
                    journal.reject(
                        agentId,
                        "unknown world '${at.world}'",
                        EventTag.UNKNOWN_WORLD,
                    )
                else -> place(agentId, at, world)
            }
        }

        /**
         * Puts a body for [agentId] into [world] at [at].
         *
         * The chunk hold is taken before the body is registered as live and so reapable:
         * a body enters [bodies] only once Loom already holds the world around it.
         */
        private fun place(
            agentId: AgentId,
            at: BodyLocation,
            world: CraftWorld,
        ): BodySpawnResult {
            val body = newLoomBody(world.handle, agentId, at)
            val hold = heldChunks.holdAround(at)
            val rejection =
                when {
                    !hold.isComplete ->
                        "could not hold the world around the spawn: ${hold.describeFailures()}" to
                            EventTag.WORLD_UNHELD
                    !world.handle.addFreshEntity(body, CreatureSpawnEvent.SpawnReason.CUSTOM) ->
                        "the world refused the spawn" to EventTag.WORLD_REFUSED
                    else -> null
                }
            if (rejection != null) {
                val (reason, outcome) = rejection
                return journal.reject(agentId, reason, outcome)
            }
            bodies[agentId] = body
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
    }

    /**
     * Takes the body of [agentId] out of the world without killing the agent.
     *
     * Despawning is not death: it is how Loom itself puts a body away, as on plugin
     * unload, so the agent id stays embodiable. Only [die] is terminal.
     */
    override fun despawn(agentId: AgentId) =
        completedBodyOperation(
            run {
                val body = bodies.remove(agentId) ?: return@run false
                val reserve = reserveVitals(metabolism.energyOf(agentId))
                val health = bodyVitals(body)
                body.discard()
                vitalsWatch.forget(agentId)
                threatWatch.forget(agentId)
                retreats.forget(agentId)
                journal.record(
                    agentId,
                    BodyEvent.DESPAWNED,
                    "removed from world",
                    vitals = reserve,
                    health = health,
                )
                true
            },
        )

    override fun apply(
        agentId: AgentId,
        intent: Intent,
        requestId: String?,
    ) = completedBodyOperation(
        run {
            // Nothing is journaled for a body that is gone: the loss belongs to whoever
            // asked, and for a decision that is BodyEvent.DECISION_DISCARDED, which carries
            // the provenance of the decision that was lost. Recording it here too would
            // double-record every such decision.
            val body = bodies[agentId] ?: return@run IntentOutcome.NoSuchBody
            // What a flight attempt came to, handed out by the entity because only the ref seam
            // above can resolve the threat it names, and needed here because an accepted flight
            // is a state rather than one hop. Null for every other intent.
            var flight: RetreatAttempt? = null
            // The ref seam is passed as a function rather than the store itself: what a
            // handle means is scoped to the agent and the request that minted it, and
            // the body has no business knowing either. An intent no perception asked
            // for carries no request, so it resolves against none and is refused as
            // stale - the honest answer, since no request of this agent's ever named
            // the threat it is asking to run from.
            val outcome =
                body.applyIntent(
                    intent,
                    movement,
                    currentTick(),
                    refs.seamFor(agentId, requestId.orEmpty(), metabolism.reserveFor(agentId), currentTick()),
                    { attempt -> flight = attempt },
                )
            // The request has had its answer, so its handles have nothing left to name. Closing
            // the window here rather than leaving it to run out keeps it as narrow as the round
            // trip actually was, and it is done for a refusal as well as an acceptance: either
            // way this perception's one decision has arrived.
            requestId?.let { refs.settle(agentId, it) }
            journal.recordAction(
                agentId,
                "${describe(intent)} -> ${describe(outcome)}",
                requestId,
                tagOf(intent),
                tagOf(outcome),
            )
            // A flight is the one intent that outlives the tick that applied it, so an accepted
            // one becomes a state this port keeps and drives from its own tick. Recorded after
            // the action it belongs to, so a reader meets the decision before its consequence.
            flight?.let { attempt ->
                retreats.attempted(agentId, attempt, currentTick())
                journal.recordRetreat(agentId, describe(attempt, continued = false), tagOf(attempt, continued = false))
            }
            // Something else driving the body has taken the flight over, so the flight is over: an
            // agent has one body and cannot walk somewhere and run away at once.
            //
            // Both halves are required and neither implies the other. An intent the body *refused*
            // took nothing over, so an unsupported `say` cannot end a flight the mind never meant
            // to end, and a refused move leaves the retreat to re-route at its own cadence. And an
            // accepted intent that displaces nothing - eating, which touches neither the navigator
            // nor the position - is not a supersession either; `takesTheBodyOver` is the rule for
            // which is which, stated in the domain where it is total over the intents.
            if (takesTheBodyOver(intent) && outcome is IntentOutcome.Accepted && retreats.superseded(agentId)) {
                journal.recordRetreat(agentId, describe(RetreatEnd.SUPERSEDED), tagOf(RetreatEnd.SUPERSEDED))
            }
            outcome
        },
    )

    override fun senseThreat(agentId: AgentId) =
        completedBodyOperation(
            run {
                val body = bodies[agentId] ?: return@run null
                val energy = metabolism.energyOf(agentId)
                val tick = currentTick()
                // One read of the world, and everything downstream comes from it: the candidates,
                // the trigger's own fields, the refs in the observations, and the perception the
                // mind is sent. See BodyScene for why the scene is captured before the trigger.
                val scene = body.captureScene(agentId, energy, tick, refs)
                val outcome =
                    threatWatch.consider(agentId, threatsIn(scene)) { candidate ->
                        // Asked last and only for a candidate the free tests passed: this is a
                        // real search, out of the same per-tick budget a walk pays into.
                        body.canReach(candidate, movement, tick)
                    }
                // Every refusal is recorded, once, with the bound that refused it - a suppressed
                // sighting is a fact about the run, not an absence of one. Nothing here reaches a
                // mind, which is the point of the whole method.
                for (suppression in outcome.suppressed) {
                    journal.recordSuppressedThreat(agentId, suppression)
                }
                outcome.sighted?.let {
                    recorded(journal, vitalsWatch, agentId, reserveVitals(energy), scene.perceptionOf(it))
                }
            },
        )

    override fun perceive(
        agentId: AgentId,
        trigger: Trigger,
    ) = completedBodyOperation(
        run {
            val body = bodies[agentId] ?: return@run null
            // One reading, used twice: a decision's cost and the reserve the agent had when it
            // was made belong to the same moment, so the record must state the number the mind
            // was handed rather than a second sample of it. The body's health rides on the change
            // and death records instead of being restated here.
            val energy = metabolism.energyOf(agentId)
            recorded(
                journal,
                vitalsWatch,
                agentId,
                reserveVitals(energy),
                body.capturePerception(agentId, energy, trigger, currentTick(), refs),
            )
        },
    )

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
                    // Still actuation: a flight the mind already decided on gets its next
                    // bounded hop when the last one ends, and never a model call. See
                    // RetreatDrive.kt.
                    retreatDrive.advance(agentId, body, tick)
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
                spawner.spawn(agentId, recordTransit(agentId, body, journal))
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
        refs.forget(agentId)
        // Loom is permadeath, so nothing this agent was told about a threat can ever
        // matter again, and per-agent state must not outlive the agent it is about.
        threatWatch.forget(agentId)
        retreats.forget(agentId)
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

    override fun close() = despawnAll()
}

/**
 * Removes every body and lets the world go.
 *
 * Called when the plugin unloads. Releasing the chunks matters as much as removing the
 * bodies: a plugin chunk ticket outlives the tick that added it, so skipping this would
 * leave the world pinned loaded with no Loom running.
 *
 * An extension over the port's own operations rather than a method with private access,
 * because that is all it is: putting every body away is the ordinary [BodyPort.despawn]
 * done once per living agent, and a privileged path could quietly stop being. What is left
 * inside the adapter is the one obligation that is not the port's - the world hold this
 * engine took.
 */
fun NmsEntityBodyPort.despawnAll() {
    for (agentId in liveBodies()) {
        despawn(agentId).toCompletableFuture().join()
    }
    heldChunks.releaseAll()
}

/**
 * Records [perception] as [agentId]'s own, and hands it back.
 *
 * The one place a perception reaches the log, whether a goal review asked for it or a
 * threat scan earned it: a scan that recorded its perception differently would make a
 * run's perceptions uncountable, and one that recorded none would hide exactly the cycles
 * a survival study is about.
 *
 * A function of the two things that hold a reading rather than a method of the port,
 * because nothing about it is the port's lifecycle: it is how a perception becomes a
 * record, and the port merely has the journal it goes to.
 */
private fun recorded(
    journal: BodyEventJournal,
    vitals: VitalsWatch,
    agentId: AgentId,
    reserve: AgentVitals,
    perception: Perception,
): Perception {
    vitals.noted(agentId, reserve)
    journal.recordPerception(
        agentId,
        // The omission is stated rather than left to arithmetic against a bound the record
        // does not carry: a crowded scene trimmed to the bound would otherwise read
        // afterwards as a quiet one, and how often an agent decides on a partial view of
        // its surroundings is one of the numbers a survival run is read for. Read off the
        // perception itself, so the record and the prompt the mind was given can never
        // state different omissions for the same moment.
        "${perception.surroundings.size} observation(s)" + omissionOf(perception.surroundingsOmitted),
        perception.requestId,
        tagOf(perception.trigger),
        reserve,
    )
    return perception
}

/**
 * The two clock readings the threat rule needs, read off the running server.
 *
 * The tick comes from the same supplier every record is stamped with, so a suppression and
 * the debounce that caused it agree. The day is the *world's* own, because the night's
 * ceiling on model calls is a fact about the world the agents are in and not about how long
 * the process has been up: a `/time set` moves it, exactly as it moves everything else an
 * agent perceives.
 *
 * The first world is the one asked, and that is deliberate even though the ceiling is spent
 * per agent. What one clock buys is that every agent's allowance is restored by the same
 * dawn wherever it is standing - and the nether and the end have no day at all, so an agent
 * in either would otherwise pin its own budget to a day that never turns over.
 */
private class ServerThreatClock(
    private val server: Server,
    private val currentTick: () -> Long,
) : ThreatClock {
    override fun tick(): Long = currentTick()

    override fun dayOrdinal(): Long = (server.worlds.firstOrNull()?.fullTime ?: 0L) / TICKS_PER_DAY
}

/** Ticks in one Minecraft day, which is what makes a day ordinal out of a world's clock. */
private const val TICKS_PER_DAY: Long = 24_000L
