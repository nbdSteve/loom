/*
 * The steps one perception offers now, read as the mind reads them in `offered.rs`: a dig in the
 * interaction reach of the eyes, a collect with a route, a walk onto a source out of reach. The
 * ledger judges a goal's steps by the same reading the strict schema offers them from, so the two
 * runtimes cannot disagree about whether a goal has a step (the r39 offline audit).
 */
package dev.loom.mind

import dev.loom.contract.Bearing
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.Perception
import kotlin.math.sqrt

/** The distance from the eyes to [seen], from its feet-relative reading, as the mind measures it. */
internal fun fromEyes(seen: Observation): Double {
    val horizontal = (seen.distance * seen.distance - seen.verticalOffset * seen.verticalOffset).coerceAtLeast(0.0)
    val rise = seen.verticalOffset - EYE_HEIGHT_BLOCKS
    return sqrt(horizontal + rise * rise)
}

/** Whether [seen] lies within the body's interaction reach of the eyes. */
internal fun inReach(seen: Observation): Boolean = fromEyes(seen) <= INTERACTION_REACH_BLOCKS

/**
 * Whether [seen] is a step one action takes now, as the mind offers steps: a visible dig-safe block
 * in the interaction reach of the eyes, or a dropped item a collect has a route to.
 */
internal fun stepNow(seen: Observation): Boolean =
    when (seen.kind) {
        ObservationKind.BLOCK -> seen.visible && seen.digSafe == true && fromEyes(seen) <= INTERACTION_REACH_BLOCKS
        ObservationKind.DROPPED_ITEM -> seen.routeRefused != true && seen.distance <= COLLECT_REACH_BLOCKS
        else -> false
    }

/** Whether [seen] is a walk the mind offers toward a source: a visible dig-safe block out of reach, stood on. */
internal fun walkStep(seen: Observation): Boolean =
    seen.kind == ObservationKind.BLOCK && seen.visible && seen.digSafe == true && seen.standable == true &&
        seen.direction != Bearing.HERE && fromEyes(seen) > INTERACTION_REACH_BLOCKS

/**
 * The bearings of each item's sources [perception] recalls out of sight or shows beyond dig reach:
 * the directions a walk to the edge of sight steps toward it, as `offered::source_bearings` reads them.
 */
internal fun sourceBearingsOf(perception: Perception): Map<String, Set<Bearing>> =
    (
        perception.recalled.map { it.what to it.direction } +
            perception.surroundings
                .filter { it.kind == ObservationKind.BLOCK && it.visible && it.digSafe == true && !inReach(it) }
                .flatMap { block -> block.yields.orEmpty().map { it.what to block.direction } }
    ).groupBy({ it.first }, { it.second })
        .mapValues { (_, bearings) -> bearings.toSet() }

/** The refs among [seen] a step offered now takes, with the items each gives. */
internal fun stepSourcesOf(seen: List<Observation>): Map<String, Set<String>> =
    seen
        .filter { stepNow(it) || walkStep(it) }
        .associate { it.ref to (it.yields?.map { given -> given.what } ?: listOf(it.what)).toSet() }
