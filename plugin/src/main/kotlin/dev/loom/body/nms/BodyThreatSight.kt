/*
 * The engine half of looking at something an agent is running from: where it is, and
 * nothing that moves.
 *
 * Kept apart from `BodyNavigation` on purpose. That file is about *walking* - every call
 * in it either puts a route on a body or reads the one it is following - while everything
 * here asks a question and throws the answer away. The distinction matters because a
 * running retreat calls this every tick on a creature it may well do nothing about, so a
 * reader has to be able to see at a glance that looking cannot move anything.
 *
 * The policy is not here. What a retreat does with a standing is `ActiveRetreats`'s, in
 * the engine-free domain, and so is whether a search may be spent at all.
 */
package dev.loom.body.nms

import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import dev.loom.body.ThreatStanding
import dev.loom.body.blocksApart
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

/**
 * Where [threat] stands relative to [this] body: the one world read a retreat needs per tick.
 *
 * The range is [PERCEPTION_RADIUS_BLOCKS] because that is the whole of what a body can
 * honestly say anything about, so "got away" means "out of everything this body can see"
 * rather than some private safety number. Measured with the same three-dimensional
 * [blocksApart] every other bound in Loom is, since a walk is never shorter than the
 * straight line and a thing on a cliff is not near because its footprint is.
 *
 * Free of the path budget on purpose: this is two positions and a subtraction, so a retreat
 * can be asked every cadence whether it is over without spending a search on the question.
 */
internal fun LoomBodyEntity.standingOf(threat: UUID): ThreatStanding {
    val found = livingThreat(threat) ?: return ThreatStanding.GONE
    return if (blocksApart(whereItIs(), found.whereItIs()) <= PERCEPTION_RADIUS_BLOCKS) {
        ThreatStanding.IN_RANGE
    } else {
        ThreatStanding.OUT_OF_RANGE
    }
}

/**
 * The living creature [subject] names in this body's own world, or `null`.
 *
 * Scoped to the body's level on purpose: `ServerLevel.getEntity` answers only for entities
 * that level holds, so a threat in another dimension comes back as an absence rather than as
 * a position in a world the body cannot walk in. A creature the world still holds but has
 * finished with is refused too - a body cannot run from something that is already dead.
 */
internal fun LoomBodyEntity.livingThreat(subject: UUID): LivingEntity? {
    val here = level() as? ServerLevel ?: return null
    val found = here.getEntity(subject)
    return if (found is LivingEntity && found.isAlive) found else null
}
