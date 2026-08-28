/*
 * Capture of the bounded perception snapshot from a live body.
 *
 * This is the perception boundary in code: it reads world state around a body and
 * returns owned, summarized data. It is the only place engine world state is read
 * for the mind's benefit, and everything it hands back is a contract `Perception` -
 * so there is no overload of it that hands back anything the mind could reach
 * through.
 *
 * What the count bound left out rides *inside* that perception, as
 * `Perception.surroundingsOmitted`. It has two readers - the mind, which must not
 * reason about "everything I can see" over a list that was trimmed, and the research
 * record, which must not read a crowded scene afterwards as a quiet one - and one
 * field is what keeps those two from ever disagreeing about the same moment.
 *
 * What "bounded" means is defined by `PerceptionBound` in the domain, not here.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.AgentVitals
import dev.loom.body.EntityRefs
import dev.loom.body.PerceptionBound.Bounded
import dev.loom.body.PerceptionBound.Measured
import dev.loom.body.RefResolution
import dev.loom.body.ThreatCandidate
import dev.loom.body.WorldClock
import dev.loom.body.bodyPerceptionBound
import dev.loom.body.huntsVillagers
import dev.loom.contract.Bearing
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Observation
import dev.loom.contract.Perception
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import dev.loom.contract.WorldState
import net.minecraft.core.Direction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.UUID

/**
 * How far a body sees, and how much of what it sees is reported.
 *
 * Both numbers come from the domain (`PERCEPTION_RADIUS_BLOCKS` and
 * `PERCEPTION_MAX_OBSERVATIONS`, via `bodyPerceptionBound`) rather than being written
 * down here, because the radius has a second reader: the area Loom holds loaded has to
 * cover what a perception reads as well as what a path search does, and
 * `holdRadiusChunksFor` derives that from the same constant. A literal here would be a
 * copy the hold could fall behind, silently - an unloaded chunk yields no entities
 * rather than an error.
 */
private val BOUND = bodyPerceptionBound()

/**
 * One reading of the world around a body, before anything has said what it is *about*.
 *
 * This exists because of an ordering problem with one honest answer. A threat scan finds
 * its candidates *in* the observations, so the trigger cannot be known until after the
 * world has been read - while a [Perception] is built with its trigger and its
 * observations at once. Capturing with a placeholder trigger and replacing it later would
 * put a `goalReview` that never happened into the record for a moment, and any code that
 * read the perception in between would be reading a fiction.
 *
 * So the scene is captured once, and the trigger names what it turned out to be. Every
 * consumer of a scan - the candidate list, the trigger's own fields, the observation
 * refs, and the perception the mind is sent - therefore comes from the same read of the
 * world, at the same tick, under the same [requestId].
 */
internal class BodyScene(
    /** The request the refs in [surroundings] were minted under. */
    val requestId: String,
    /** What the body saw, bounded and already named. */
    val surroundings: List<Observation>,
    private val perceive: (Trigger) -> Perception,
) {
    /** This same scene, as the perception of [trigger]. */
    fun perceptionOf(trigger: Trigger): Perception = perceive(trigger)
}

/**
 * Reads the world around [this] body once, as a scene no trigger has claimed yet.
 *
 * [energy] is how much of the agent's reserve is left, as the contract's fraction. It
 * is passed in rather than read off the body because the reserve belongs to the agent
 * and outlives any one body; only the port holds it. It reaches the mind here, in the
 * ordinary perception, so an agent reacts to its own need through the cognition loop
 * it already has rather than through a mechanism of its own.
 */
internal fun LoomBodyEntity.captureScene(
    agentId: AgentId,
    energy: Double,
    capturedTick: Long,
    refs: EntityRefs,
): BodyScene {
    val body = this
    val nearby = observationsAround(body)
    val requestId = UUID.randomUUID().toString()
    // One handle per living thing, in the order the bound kept them, so a decision can
    // name one creature back to the body. Minted from the trimmed list rather than the
    // candidates, so nothing is bound that the mind will never be shown.
    val bound = refs.issue(agentId, requestId, nearby.shown.map { it.thing.uuid })
    val surroundings = nearby.shown.mapIndexed { at, seen -> observationOf(body, seen, bound[at].handle) }
    val selfState =
        SelfState(
            health = bodyVitals(body).energy,
            energy = energy,
            position = listOf(body.x, body.y, body.z),
            // The body reveals a summary, never its live inventory handle.
            inventorySummary = emptyList(),
            world = worldStateOf(body.level()),
        )
    return BodyScene(requestId, surroundings) { trigger ->
        Perception(
            requestId = requestId,
            agentId = agentId.value,
            trigger = trigger,
            // Every snapshot captured here is routine; importance is classified by the
            // domain above the port, which is the only thing that knows what a trigger costs.
            importance = DecisionImportance.ROUTINE,
            capturedTick = capturedTick,
            selfState = selfState,
            surroundings = surroundings,
            // Only what the *count* bound dropped. Everything the distance filter and the
            // living-entity filter refused was never an observation, so counting it here
            // would tell the mind it was blind to things it was never able to see.
            surroundingsOmitted = nearby.omitted.toLong(),
        )
    }
}

/**
 * Builds the bounded snapshot the mind receives for [this] body, as the perception of
 * [trigger].
 *
 * Bounded twice over - by distance and by count - and summarized to text, so the
 * perception can never widen into a live world handle. For a trigger that is only known
 * after the world has been read, capture a [BodyScene] instead.
 */
internal fun LoomBodyEntity.capturePerception(
    agentId: AgentId,
    energy: Double,
    trigger: Trigger,
    capturedTick: Long,
    refs: EntityRefs,
): Perception = captureScene(agentId, energy, capturedTick, refs).perceptionOf(trigger)

/**
 * The hostiles in [scene], as the threat rule needs them.
 *
 * Read out of the scene the mind will be shown rather than off the world a second time,
 * so what the watch judges is exactly what the perception states - same bound, same
 * distances, same nearest-first order, same tick, same request. A second read would let a
 * run report a threat at a distance no perception ever stated.
 *
 * Each candidate's subject comes back through [refs], resolved under the scene's own
 * [BodyScene.requestId], which is the only thing that can turn a handle into a creature.
 * An observation whose handle no longer resolves is dropped: without a subject the watch
 * could not tell this hostile from the next one, and guessing is how a body ends up
 * fleeing the wrong zombie.
 */
internal fun threatsIn(
    agentId: AgentId,
    scene: BodyScene,
    refs: EntityRefs,
): List<ThreatCandidate> =
    scene.surroundings
        .filter { huntsVillagers(it.what) }
        .mapNotNull { seen ->
            val handle = seen.ref ?: return@mapNotNull null
            val subject = refs.resolve(agentId, scene.requestId, handle)
            if (subject !is RefResolution.Bound) {
                return@mapNotNull null
            }
            ThreatCandidate(
                subject = subject.subject,
                what = seen.what,
                ref = handle,
                distance = seen.distance,
                direction = seen.direction,
                visible = seen.visible,
            )
        }

/**
 * States what the count bound left out, or nothing if it left nothing out.
 *
 * Phrased for the record rather than for a count so a reader meets the caveat where the
 * observation total is, and empty in the ordinary case so an uncrowded run's records are
 * not padded with a standing "and 0 more".
 */
internal fun omissionOf(omittedNearby: Long): String =
    if (omittedNearby > 0) ", $omittedNearby more nearby not reported" else ""

/**
 * Reads [level]'s own clock as the bounded [WorldState] the mind is told.
 *
 * Both facts come from the level rather than from any count Loom keeps: a `/time set`,
 * a sleeping night, or a dimension with a fixed day must all reach the agent as what
 * the world actually is. What the number *means* is [WorldClock]'s, in the domain.
 */
private fun worldStateOf(level: Level): WorldState {
    val dayTime = level.dayTime
    return WorldState(
        dayPhase = WorldClock.phaseOf(dayTime),
        // The level's own answer, not derived from the phase: a dimension with a fixed
        // time has a day time and no daylight, and the agent needs the difference.
        isDay = level.isDay,
        ticksUntilNightfall = WorldClock.ticksUntilNightfall(dayTime),
    )
}

/**
 * Reads [body]'s health as [AgentVitals].
 *
 * This is the body's hit points, not the agent's energy reserve: since the reserve was
 * added the two are separate vitals, and this reads only the health one. Health is what
 * the world can take from a body in a fight - a body can be beaten to death on a full
 * reserve - while the reserve is what living itself spends and what an unattended agent
 * starves on, and the reserve is held by the port rather than the body. Keeping the health
 * reading here - in the adapter, read through by both the perception and the operator
 * display - means the source of the reading can change without anything that reads it.
 *
 * Clamped at zero because [AgentVitals] refuses a negative energy and the engine is under
 * no obligation to have clamped first.
 */
internal fun bodyVitals(body: LoomBodyEntity): AgentVitals =
    AgentVitals(
        energy = body.health.toDouble().coerceAtLeast(0.0),
        capacity = body.maxHealth.toDouble(),
    )

/**
 * The living things near [body], bounded, each with the distance it was kept for.
 *
 * The distance is measured once, by the bound, and carried: it decides eligibility, it
 * decides order, and it is the number the observation reports, so the three cannot
 * disagree about the same entity in a world where everything is moving.
 */
private fun observationsAround(body: LoomBodyEntity): Bounded<Measured<LivingEntity>> {
    // The box is a cheap pre-filter that the engine can answer from its entity
    // index; PerceptionBound then applies the real distance and count limits.
    val box = AABB.ofSize(body.position(), BOUND.boxExtent, BOUND.boxExtent, BOUND.boxExtent)
    val nearby =
        body.level()
            .getEntitiesOfClass(LivingEntity::class.java, box) { it !== body }
            .asSequence()
    return BOUND.nearest(nearby) { body.distanceTo(it).toDouble() }
}

/**
 * Describes [seen] - a thing near [body], already measured - as [body] perceives it.
 *
 * [handle] is the request-scoped name the mind may use to point back at this creature.
 * Every living thing reported carries one: a description cannot distinguish two zombies
 * the same distance away in the same direction, and an observation the mind can see and
 * cannot name is indistinguishable on the wire from a block.
 */
private fun observationOf(
    body: LoomBodyEntity,
    seen: Measured<LivingEntity>,
    handle: String,
): Observation {
    val other = seen.thing
    return Observation(
        what = other.type.descriptionId,
        // The reading the bound kept it for, not a fresh one.
        distance = seen.blocksAway,
        direction = bearingTo(body, other),
        verticalOffset = other.y - body.y,
        // A real ray trace through the world, and the engine's own: `false` means the
        // body knows something is there and cannot see it, which is the difference
        // between a threat behind a wall and a threat in the open. It is a line, not a
        // field of view: the body's facing is not consulted, and the brief the mind is
        // given says so.
        visible = body.hasLineOfSight(other),
        ref = handle,
    )
}

/**
 * The horizontal bearing from [body] to [other], as the engine's nearest axis.
 *
 * The engine picks the axis, not Loom: `Direction.getNearest` is given a delta with its
 * vertical component zeroed, so it answers with the cardinal direction whose axis the
 * delta points most strongly along. Height is reported separately, as
 * [Observation.verticalOffset], because a bearing is horizontal and a thing on a cliff
 * is not reachable by walking toward it.
 */
private fun bearingTo(
    body: LoomBodyEntity,
    other: LivingEntity,
): Bearing =
    when (Direction.getNearest(other.x - body.x, 0.0, other.z - body.z)) {
        Direction.NORTH -> Bearing.NORTH
        Direction.EAST -> Bearing.EAST
        Direction.SOUTH -> Bearing.SOUTH
        Direction.WEST -> Bearing.WEST
        // Unreachable for a zeroed vertical delta - the engine scores both vertical
        // axes at zero, and a body exactly on top of another falls through to its own
        // default of north. Named rather than left to an `else` so the mapping stays
        // total over `Direction`, and answered with a bearing rather than an exception:
        // a perception must not fail over where something is standing.
        Direction.UP, Direction.DOWN -> Bearing.NORTH
    }
