/*
 * The cover one sense's scene shows: the server's ground around the body, with the sensed blocks a
 * closing or a descent may name and the solid-block items sight could close it with.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.bodycontrol.BodyResponse
import dev.loom.contract.Cover
import dev.loom.contract.MAX_COVER_GATHERS
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import kotlin.math.floor

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
private const val SCENE_DIG_REACH_BLOCKS = 5.1

/** `MOVE_REACH_BLOCKS` in `body/src/movement.js`: the farthest a collect with approach may route. */
private const val SCENE_ROUTE_REACH_BLOCKS = 32.0

/**
 * The cover the body a [raw] sense reports stands in, from [scene]'s ground: the selected [blocks]
 * with their refs are what a closing or a descent may name, and the solid-block items, by
 * [solidity], that a visible dig in reach or a drop in sight among [surroundings] could give are
 * what it could be closed with.
 */
internal fun sceneCover(
    raw: RawSense,
    scene: SceneGround,
    blocks: List<Pair<SceneOffer, String>>,
    surroundings: List<Observation>,
    solidity: ItemSolidity,
): Cover? {
    val supports =
        blocks.associate { (offer, ref) ->
            val cell = raw.report.blocks[offer.sourceIndex].position
            cell to CoverSupport(ref, cell, offer.visible)
        }
    val eyes = eyesAt(raw.report.position)
    val dug =
        blocks
            .map { it.first }
            .filter { it.visible && it.digSafe == true && blocksApart(eyes, it.position) <= SCENE_DIG_REACH_BLOCKS }
            .flatMap { it.yields.orEmpty() }
            .filter { it.most >= 1 }
            .map { it.what }
    val dropped =
        surroundings.filter { it.kind == ObservationKind.DROPPED_ITEM && it.routeRefused != true }.map { it.what }
    val solid = (dug + dropped).filter { solidity.solid(resourceOfItem(it)) == true }
    val cover = coverOf(scene.ground, raw.report.position, supports, solid) ?: return null
    return cover.copy(gathers = gathersOf(raw.report, cover, blocks, surroundings, solidity))
}

/**
 * The refs whose gathering gives the shelter [cover] reads a closer, while a face can close and
 * nothing solid is held: visible blocks in dig reach that a safe dig drops a solid block from,
 * nearest first - never one of the shelter's own faces, its roof or its floor, which a dig would
 * open (r39 alpha dug its shelter's west wall for the dirt to close it) - then solid drops in
 * sight a collect has a route to.
 */
internal fun gathersOf(
    report: BodyResponse.SenseReport,
    cover: Cover,
    blocks: List<Pair<SceneOffer, String>>,
    surroundings: List<Observation>,
    solidity: ItemSolidity,
): List<String> {
    val holdsSolid = report.inventory.any { solidity.solid(it.what) == true }
    if (cover.sealed || cover.closings.isEmpty() || holdsSolid) return emptyList()
    val eyes = eyesAt(report.position)
    val feet = report.position.map { floor(it).toInt() }
    val shelter =
        COVER_OFFSETS.map { (_, offset) -> feet.offset(offset) }.toSet() +
            setOf(feet, feet.offset(listOf(0, 1, 0)), feet.offset(listOf(0, -1, 0)))
    val solidYield = { offer: SceneOffer ->
        offer.yields.orEmpty().any { it.most >= 1 && solidity.solid(resourceOfItem(it.what)) == true }
    }
    val digs =
        blocks
            .filter { (offer, _) ->
                offer.visible && offer.digSafe == true && blocksApart(eyes, offer.position) <= SCENE_DIG_REACH_BLOCKS &&
                    report.blocks[offer.sourceIndex].position !in shelter && solidYield(offer)
            }.sortedBy { (offer, _) -> blocksApart(eyes, offer.position) }
            .map { it.second }
    val drops =
        surroundings
            .filter { it.kind == ObservationKind.DROPPED_ITEM && it.routeRefused != true }
            .filter { it.distance <= SCENE_ROUTE_REACH_BLOCKS && solidity.solid(resourceOfItem(it.what)) == true }
            .map { it.ref }
    return (digs + drops).take(MAX_COVER_GATHERS)
}
