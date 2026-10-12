/*
 * Rendering of an intent and its outcome into the append-only log's `detail` text.
 *
 * In the domain rather than in a body flavor because both sides of the round trip
 * render them: the mind side records what was decided, the body records what it did
 * about it, and two renderings of one intent would make the two halves of a chain
 * read as different actions.
 */
package dev.loom.body

import dev.loom.contract.Intent

/** Names the intent, without its full payload. */
fun describe(intent: Intent): String =
    when (intent) {
        is Intent.MoveTo -> "moveTo ${intent.target.joinToString(",")}"
        is Intent.Say -> "say"
        is Intent.Flee -> "flee from ${intent.from}"
        is Intent.Eat -> "eat ${intent.ref}"
        is Intent.Dig -> "dig ${intent.block}"
        is Intent.Collect -> "collect ${intent.item}"
        is Intent.Place -> "place ${intent.item} at ${intent.block}/${intent.face.name.lowercase()}"
        is Intent.Equip -> "equip ${intent.item} as ${intent.destination.name.lowercase()}"
        is Intent.Craft -> "craft ${intent.crafts} of ${intent.recipe}"
        is Intent.Attack -> "attack ${intent.target} with ${intent.weapon} up to ${intent.attacks} times"
        is Intent.Sleep -> "sleep in ${intent.bed}"
        is Intent.Rest -> "rest"
        is Intent.Idle -> "idle"
    }

/** Names what the body did with an intent, so gaps are visible in the log. */
fun describe(outcome: IntentOutcome): String =
    when (outcome) {
        is IntentOutcome.Accepted -> "accepted: ${outcome.effect}"
        is IntentOutcome.Rejected -> "rejected: ${outcome.reason}"
        is IntentOutcome.Unsupported -> "unsupported: ${outcome.action}"
        IntentOutcome.NoSuchBody -> "no such body"
    }
