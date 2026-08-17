/*
 * Rendering of this flavor's world positions into the append-only log's `detail`
 * text.
 *
 * Kept separate from the adapter so the port implementation stays about spawning,
 * driving, and observing bodies, and so the log's human-readable shape can change
 * without touching engine code. What an intent and its outcome look like in the log
 * is shared with the mind side and lives in the domain instead; see `IntentDetail`.
 */
package dev.loom.body.nms

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
