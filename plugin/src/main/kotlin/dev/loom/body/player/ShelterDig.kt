/*
 * A shelter's two unsafe steps: a dig that would open the sealed space a body stands in, which no
 * item goal is offered through the shelter window (`PlayerSceneTranslator` states it as the block's
 * `digSafe`), and a closing a falling block would drop out of onto the body.
 */
package dev.loom.body.player

import dev.loom.contract.Cover
import kotlin.math.floor

/**
 * Whether digging [block] would open the sealed space a body with feet in [feet] stands in: sealed
 * by [ground] now, and not once that block is dug (R41 alpha sealed itself in at dusk, dug its own
 * walls four times for dirt goals, and was killed in the open).
 */
internal fun digOpensShelter(
    ground: BlockGround,
    feet: List<Int>,
    block: List<Int>,
): Boolean = sealedAt(ground, feet) && !sealedAt(ground.opening(block), feet)

/**
 * [cover] without the closings a block from the main hand would fall out of, when [heldFalls]: a face
 * whose cell has nothing under it, which sand or gravel placed there leaves (R45 zeta placed sand in
 * its roof, over its own head, and suffocated in it).
 */
internal fun withoutFallingClosings(
    cover: Cover,
    ground: BlockGround,
    position: List<Double>,
    heldFalls: Boolean,
): Cover {
    if (!heldFalls) return cover
    val feet = position.map { floor(it).toInt() }
    val unsupported =
        COVER_OFFSETS.filter {
                (_, offset) ->
            ground.standsIn(feet.offset(offset).offset(listOf(0, -1, 0)))
        }.map { it.first }
    return cover.copy(closings = cover.closings.filter { it.face !in unsupported })
}
