/*
 * The engine half of looking at something hunting an agent: where it is, whether the body
 * can see a way to it, and nothing that moves.
 *
 * Kept apart from `BodyNavigation` on purpose. That file is about *walking* - every call
 * in it either puts a route on a body or reads the one it is following - while everything
 * here asks a question and throws the answer away. The distinction matters because the
 * threat rule and a running retreat both call this on creatures they may well do nothing
 * about, so a reader has to be able to see at a glance that looking cannot move anything.
 *
 * The policy is not here. Whether a search may be spent at all is `NavigationPolicy`'s,
 * in the engine-free domain, and this pays into the same per-tick bucket a walk does
 * rather than beside it: vigilance and movement compete for one budget, so no number of
 * agents looking around can buy more searches than a tick allows. What a retreat does
 * with a standing is `ActiveRetreats`'s, in the same engine-free domain.
 */
package dev.loom.body.nms

import dev.loom.body.BodyLocation
import dev.loom.body.NavigationPolicy
import dev.loom.body.PATH_REQUEST_ACCURACY_BLOCKS
import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import dev.loom.body.PathRequest
import dev.loom.body.ThreatCandidate
import dev.loom.body.ThreatStanding
import dev.loom.body.blockAt
import dev.loom.body.blocksApart
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

/**
 * Whether [this] body could actually walk to what [candidate] names, right now.
 *
 * The threat rule asks this last and only for a candidate its free tests have already
 * passed, because this is the expensive one: it is a real path search, and it is taken out
 * of the same [NavigationPolicy] bucket a walk pays into rather than beside it. So a scan
 * can never buy more searches than a tick's whole allowance, and a threat standing in view
 * costs one search per debounce window rather than one per scan.
 *
 * Three ways to answer no, and they are deliberately not distinguished here: the creature
 * is no longer in this level, it is further off than a body may be sent, or the ground
 * offers no route. What the rule does with the answer is "is this worth interrupting a
 * mind for", and for that purpose a threat that cannot be walked to is one fact.
 *
 * The route is computed and dropped. `createPath` searches without touching the
 * navigator - only `moveTo` puts a route on a body - so asking this never moves anything.
 */
internal fun LoomBodyEntity.canReach(
    candidate: ThreatCandidate,
    movement: NavigationPolicy,
    tick: Long,
): Boolean {
    val here = whereItIs()
    val at = livingThreat(candidate.subject)?.whereItIs() ?: return false
    return movement.request(here, at, tick) == PathRequest.Granted && hasRouteTo(at)
}

/**
 * Whether the engine can find a complete route from [this] body to [target].
 *
 * `canReach` rather than "a path came back", because a ground navigator answers an
 * unreachable target with a *best-effort* route to the nearest node it managed - which is
 * how a body ends up pressed against a wall. See `Navigation.kt` on the walk budget.
 *
 * The block searched for is [blockAt]'s, the same rounding a walk request uses, because a
 * flooring here and a truncation there would let the reach this reports disagree with the
 * reach a retreat is later granted at a negative coordinate.
 */
private fun LoomBodyEntity.hasRouteTo(target: BodyLocation): Boolean {
    val named = blockAt(target)
    return navigator
        .createPath(
            BlockPos(named.x.toInt(), named.y.toInt(), named.z.toInt()),
            PATH_REQUEST_ACCURACY_BLOCKS,
        )?.canReach() == true
}

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
