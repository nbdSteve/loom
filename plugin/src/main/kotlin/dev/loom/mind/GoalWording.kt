// A goal's and a step's wording: the body's, from typed fields, never the mind's.
package dev.loom.mind

import dev.loom.body.describe
import dev.loom.contract.Intent
import dev.loom.contract.MAX_GOAL_STATEMENT_CHARS

/** A discovery goal's wording: the body's, like every goal's. */
const val DISCOVERY_STATEMENT: String = "find a useful resource beyond the edge of sight"

/**
 * A step's wording, from the decision's own intent: the body's, never the mind's, so the text a
 * later review is shown cannot name what the intent did not do (r17 epsilon's step said it dug
 * terracotta for sticks while its intent dug a dead bush).
 */
internal fun stepOf(intent: Intent): String = describe(intent).take(MAX_GOAL_STATEMENT_CHARS)

/** The goal's wording, from its typed success condition: the total of the item to hold. */
internal fun statementOf(
    item: String,
    count: Long,
): String = "hold $count $item"
