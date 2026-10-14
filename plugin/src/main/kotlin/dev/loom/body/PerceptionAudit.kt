/*
 * Research-only facts about one bounded player perception.
 *
 * These values describe the body/JVM selection boundary and never enter the
 * cognition contract or prompt. They ride the append-only evidence record so a
 * published experiment can detect category starvation without changing what the
 * agent was allowed to see.
 */
package dev.loom.body

import kotlinx.serialization.Serializable

private const val MAX_TIME_OF_DAY = 23_999

/**
 * Counts for one body-control observation kind.
 *
 * [rawBodyListCount] is the list the body actually returned. The body's own
 * omission count describes candidates it cut before that list crossed the wire;
 * [jvmCutOmittedCount] describes entries from the returned list that the shared
 * JVM scene bound then removed. The two cuts are deliberately separate.
 */
@Serializable
data class PerceptionKindAudit(
    val rawBodyListCount: Long,
    val bodyReportedOmittedCount: Long,
    val jvmSelectedCount: Long,
    val jvmCutOmittedCount: Long,
) {
    init {
        require(rawBodyListCount in 0..BODY_SENSE_MAX_OFFERS_PER_KIND.toLong()) {
            "rawBodyListCount must fit the body list bound"
        }
        require(bodyReportedOmittedCount >= 0) {
            "bodyReportedOmittedCount must not be negative"
        }
        require(
            bodyReportedOmittedCount == 0L ||
                rawBodyListCount == BODY_SENSE_MAX_OFFERS_PER_KIND.toLong(),
        ) {
            "body-reported omissions require a saturated body list"
        }
        require(jvmSelectedCount in 0..rawBodyListCount) {
            "jvmSelectedCount must be drawn from the raw body list"
        }
        require(jvmCutOmittedCount == rawBodyListCount - jvmSelectedCount) {
            "jvmCutOmittedCount must equal rawBodyListCount minus jvmSelectedCount"
        }
    }
}

/** The exact body-reported world clock beside one perception audit. */
@Serializable
data class PerceptionWorldClockAudit(
    val timeOfDay: Int,
    val day: Long,
    val isDay: Boolean,
) {
    init {
        require(timeOfDay in 0..MAX_TIME_OF_DAY) {
            "timeOfDay must be a vanilla day tick"
        }
        require(day >= 0) { "day must not be negative" }
    }
}

/**
 * Complete research audit for one model-visible scene.
 *
 * The category counts are complete as a unit: serializers cannot construct a
 * partial value, and the shared selected total remains capped at the existing
 * model-visible scene bound.
 */
@Serializable
data class PerceptionAudit(
    val worldClock: PerceptionWorldClockAudit,
    val entities: PerceptionKindAudit,
    val blocks: PerceptionKindAudit,
    val droppedItems: PerceptionKindAudit,
) {
    init {
        val selected =
            entities.jvmSelectedCount +
                blocks.jvmSelectedCount +
                droppedItems.jvmSelectedCount
        require(selected <= PERCEPTION_MAX_OBSERVATIONS) {
            "JVM-selected observations exceed the perception scene bound"
        }
        requireRepresentative("entities", entities)
        requireRepresentative("blocks", blocks)
        requireRepresentative("dropped items", droppedItems)
    }
}

private fun requireRepresentative(
    kind: String,
    audit: PerceptionKindAudit,
) {
    require(audit.rawBodyListCount == 0L || audit.jvmSelectedCount > 0L) {
        "a non-empty $kind body list must keep a JVM-selected representative"
    }
}
