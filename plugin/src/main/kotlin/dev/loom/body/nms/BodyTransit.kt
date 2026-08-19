/*
 * Reading the engine's account of a body that left the world.
 *
 * Split out of the port for the same reason as BodyPerception and BodyEventDetail:
 * the port stays about spawning, driving, and observing bodies, while the awkward
 * engine knowledge - what a removal reason means, and what the engine leaves behind
 * when a body travels between dimensions - lives in one place next to the source it
 * was read from.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyDeparture
import dev.loom.body.BodyEvent
import dev.loom.body.BodyLocation
import net.minecraft.world.entity.Entity

/** Where an entity is, as a domain location, including which dimension it is in. */
internal fun Entity.whereItIs(): BodyLocation = BodyLocation(level().world.name, x, y, z)

/**
 * What the removal of [body] means for the agent living in it.
 *
 * A conversion is asked about first and separately, because it cannot be read from the
 * removal at all: it ends in the same `DISCARDED` reason an ordinary discard uses, so
 * [BodyConversions] must already have recorded the attempt as it happened. A dimension
 * transit, by contrast, is honest about itself and says `CHANGED_DIMENSION`.
 *
 * Anything else is a death - which is only sound because Loom holds the world loaded
 * and ticking around every living agent in every dimension, so a removal can never be
 * a chunk unload. Weaken that footprint and this line starts killing agents for want
 * of an observer.
 */
internal fun departureOf(body: LoomBodyEntity): BodyDeparture =
    when {
        body.convertedInto != null -> BodyDeparture.CONVERSION
        body.removalReason == Entity.RemovalReason.CHANGED_DIMENSION -> BodyDeparture.DIMENSION_TRAVEL
        else -> BodyDeparture.DEATH
    }

/**
 * Where a body that travelled between dimensions came out, clearing away what the
 * engine left there.
 *
 * The engine does not carry a travelling body across. It creates a *plain vanilla*
 * entity in the destination, copies the traveller's state onto it, and removes the
 * original. That successor is no use to Loom: it is not a [LoomBodyEntity], so it has
 * vanilla goals, no Loom-owned movement, and nothing driving it - and it cannot be
 * made into one, since the class is fixed when the entity is created. It is therefore
 * discarded rather than adopted; leaving it would litter the destination with a
 * derelict villager wearing the agent's name, which is also exactly what a reader of
 * the world would mistake for the agent.
 *
 * The two share a Bukkit entity - a transit forwards it to the successor - which is
 * how the successor is found from the traveller. A transit that produced no successor
 * (a cancelled teleport, an unreachable destination) leaves the traveller's own last
 * position as the answer, so the agent is re-placed where it set off from rather than
 * nowhere.
 */
internal fun arrivalAfterTransit(traveller: LoomBodyEntity): BodyLocation {
    val successor =
        traveller.bukkitEntityRaw?.handle?.takeIf { it !== traveller && !it.isRemoved }
            ?: return traveller.whereItIs()
    val arrival = successor.whereItIs()
    successor.discard()
    return arrival
}

/**
 * Records that [agentId] travelled, and says where it has to be embodied again.
 *
 * The record is `travelled` on the travelling agent's own chain - never a death followed
 * by a birth - so the research record keeps one continuous life per agent however many
 * dimensions it crosses. What the engine leaves on the far side is no use to Loom (see
 * [arrivalAfterTransit]), so the caller gives the agent a fresh body at the returned
 * arrival through the ordinary spawn path, which holds the destination before the body
 * counts as live there.
 */
internal fun recordTransit(
    agentId: AgentId,
    traveller: LoomBodyEntity,
    journal: BodyEventJournal,
): BodyLocation {
    val arrival = arrivalAfterTransit(traveller)
    journal.record(
        agentId,
        BodyEvent.TRAVELLED,
        "from ${traveller.level().world.name} to ${arrival.world} ${position(arrival.x, arrival.y, arrival.z)}",
    )
    return arrival
}

/**
 * Carries [traveller]'s portal cooldown onto the body continuing its journey.
 *
 * A transit puts the arriving body *inside* the destination portal, so without this
 * the body travels straight back on the next tick, and back again, indefinitely - an
 * agent stuck oscillating between dimensions once per tick. Vanilla avoids that by
 * copying the cooldown onto its successor in `restoreFrom`; Loom does not use that
 * successor, so it must carry the cooldown itself.
 *
 * It is carried rather than set fresh so a body arriving already deep in a cooldown
 * keeps the remainder of it, exactly as the engine's own hand-off does.
 */
internal fun carryPortalCooldown(
    traveller: LoomBodyEntity,
    arrived: LoomBodyEntity,
) {
    arrived.portalCooldown = traveller.portalCooldown
}
