/*
 * A dig that would open the sealed space a body stands in, which no item goal is offered through the
 * shelter window (`PlayerSceneTranslator` states it as the block's `digSafe`).
 */
package dev.loom.body.player

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
