/*
 * The cover one sense's scene shows: the server's ground around the body, with the sensed blocks a
 * closing or a descent may name and the solid-block items sight could close it with.
 */
package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.contract.Cover
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre. */
private const val SCENE_DIG_REACH_BLOCKS = 5.1

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
    return coverOf(scene.ground, raw.report.position, supports, solid)
}
