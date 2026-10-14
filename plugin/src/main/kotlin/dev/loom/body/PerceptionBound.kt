/*
 * Shared bounds on the perception a player body hands to cognition.
 *
 * The Mineflayer process owns scene capture; these constants are the JVM-side limits
 * used when translating that answer and minting request-scoped handles.
 */
package dev.loom.body

/** How far from the player an observation may be, in blocks. */
const val PERCEPTION_RADIUS_BLOCKS: Double = 24.0

/** How many offers Mineflayer may return for each world-observation kind. */
const val BODY_SENSE_MAX_OFFERS_PER_KIND: Int = 48

/** How many observations one perception may carry across all three kinds. */
const val PERCEPTION_MAX_OBSERVATIONS: Int = BODY_SENSE_MAX_OFFERS_PER_KIND * 3
