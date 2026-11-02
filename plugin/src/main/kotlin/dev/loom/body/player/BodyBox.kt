/*
 * The cells a player's own body fills, which vanilla places no block into.
 */
package dev.loom.body.player

/** A player's own collision box: half its 0.6 width either side of where it stands, and 1.8 tall. */
private const val PLAYER_HALF_WIDTH_BLOCKS = 0.3
private const val PLAYER_HEIGHT_BLOCKS = 1.8

/** Whether the box of a body standing at [standing] reaches into the cell at [cell]; touching a face does not. */
internal fun bodyReaches(
    standing: List<Double>,
    cell: List<Int>,
): Boolean {
    val (x, y, z) = standing
    val (cx, cy, cz) = cell
    return x - PLAYER_HALF_WIDTH_BLOCKS < cx + 1 && x + PLAYER_HALF_WIDTH_BLOCKS > cx &&
        y < cy + 1 && y + PLAYER_HEIGHT_BLOCKS > cy &&
        z - PLAYER_HALF_WIDTH_BLOCKS < cz + 1 && z + PLAYER_HALF_WIDTH_BLOCKS > cz
}
