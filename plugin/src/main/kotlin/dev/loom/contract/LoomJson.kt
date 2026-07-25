package dev.loom.contract

import kotlinx.serialization.json.Json

/**
 * The canonical JSON configuration for the Loom contract on the JVM side.
 *
 * These settings are chosen to produce bytes identical to the Rust side's
 * `serde_json` compact output for the same value: no pretty-printing, defaults
 * are not re-encoded, and unknown keys are rejected so a drifted message fails
 * loudly rather than being silently truncated. Internal union tagging is carried
 * by the `@JsonClassDiscriminator` annotations on [Trigger] and [Intent].
 */
public val LoomJson: Json =
    Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }
