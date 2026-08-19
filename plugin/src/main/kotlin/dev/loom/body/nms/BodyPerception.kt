/*
 * Capture of the bounded perception snapshot from a live body.
 *
 * This is the perception boundary in code: it reads world state around a body and
 * returns owned, summarized data. It is the only place engine world state is read
 * for the mind's benefit, and it deliberately returns a contract `Perception` - so
 * there is no overload of it that hands back anything the mind could reach through.
 *
 * What "bounded" means is defined by `PerceptionBound` in the domain, not here.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.PerceptionBound
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Observation
import dev.loom.contract.Perception
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import java.util.UUID

/** How far a body sees, and how much of what it sees is reported. */
private val BOUND = PerceptionBound(radiusBlocks = 12.0, maxObservations = 16)

/**
 * Builds the bounded snapshot the mind receives for [body].
 *
 * Bounded twice over - by distance and by count - and summarized to text, so the
 * perception can never widen into a live world handle.
 *
 * [energy] is how much of the agent's reserve is left, as the contract's fraction. It
 * is passed in rather than read off the body because the reserve belongs to the agent
 * and outlives any one body; only the port holds it. It reaches the mind here, in the
 * ordinary perception, so an agent reacts to its own need through the cognition loop
 * it already has rather than through a mechanism of its own.
 */
internal fun capturePerception(
    agentId: AgentId,
    body: LoomBodyEntity,
    energy: Double,
    trigger: Trigger,
    capturedTick: Long,
): Perception =
    Perception(
        requestId = UUID.randomUUID().toString(),
        agentId = agentId.value,
        trigger = trigger,
        // Every snapshot captured here is routine; importance is classified by the
        // domain above the port once cognition is wired in.
        importance = DecisionImportance.ROUTINE,
        capturedTick = capturedTick,
        selfState =
            SelfState(
                health = body.health.toDouble(),
                energy = energy,
                position = listOf(body.x, body.y, body.z),
                // The body reveals a summary, never its live inventory handle.
                inventorySummary = emptyList(),
            ),
        surroundings = observationsAround(body),
    )

private fun observationsAround(body: LoomBodyEntity): List<Observation> {
    // The box is a cheap pre-filter that the engine can answer from its entity
    // index; PerceptionBound then applies the real distance and count limits.
    val box = AABB.ofSize(body.position(), BOUND.boxExtent, BOUND.boxExtent, BOUND.boxExtent)
    val nearby =
        body.level()
            .getEntitiesOfClass(LivingEntity::class.java, box) { it !== body }
            .asSequence()
            .map { Observation(what = it.type.descriptionId, distance = body.distanceTo(it).toDouble()) }
    return BOUND.nearest(nearby) { it.distance }
}
