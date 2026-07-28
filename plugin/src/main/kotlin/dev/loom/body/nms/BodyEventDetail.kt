/*
 * Rendering of body events into the append-only log's `detail` text.
 *
 * Kept separate from the adapter so the port implementation stays about spawning,
 * driving, and observing bodies, and so the log's human-readable shape can change
 * without touching engine code.
 */
package dev.loom.body.nms

import dev.loom.body.IntentOutcome
import dev.loom.contract.Intent
import java.util.Locale

/** Two decimal places is enough to see a body move without flooding the log. */
private const val COORDINATE_FORMAT = "%.2f"

/** Formats a coordinate for the log, locale-independently so logs are comparable. */
internal fun coordinate(value: Double): String = String.format(Locale.ROOT, COORDINATE_FORMAT, value)

/** Renders a position triple for the log. */
internal fun position(
    x: Double,
    y: Double,
    z: Double,
): String = "${coordinate(x)},${coordinate(y)},${coordinate(z)}"

/** Names the intent that was applied, without its full payload. */
internal fun describe(intent: Intent): String =
    when (intent) {
        is Intent.MoveTo -> "moveTo ${intent.target.joinToString(",")}"
        is Intent.Say -> "say"
        is Intent.Flee -> "flee from ${intent.from}"
        is Intent.Idle -> "idle"
    }

/** Names what the body did with an intent, so gaps are visible in the log. */
internal fun describe(outcome: IntentOutcome): String =
    when (outcome) {
        is IntentOutcome.Accepted -> "accepted: ${outcome.effect}"
        is IntentOutcome.Rejected -> "rejected: ${outcome.reason}"
        is IntentOutcome.Unsupported -> "unsupported: ${outcome.action}"
        IntentOutcome.NoSuchBody -> "no such body"
    }
