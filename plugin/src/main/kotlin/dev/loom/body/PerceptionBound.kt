/*
 * Shared bounds on the perception a player body hands to cognition.
 *
 * The Mineflayer process owns scene capture; these constants are the JVM-side limits
 * used when translating that answer and minting request-scoped handles.
 */
package dev.loom.body

/** How far from the player an observation may be, in blocks. */
const val PERCEPTION_RADIUS_BLOCKS: Double = 24.0

/** How many observations one perception may carry. */
const val PERCEPTION_MAX_OBSERVATIONS: Int = 16
