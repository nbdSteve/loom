/*
 * Closed outcome translation for crafting, combat, and sleeping.
 */
package dev.loom.body.player

import dev.loom.body.EventTag
import dev.loom.body.IntentOutcome
import dev.loom.bodycontrol.AttackOutcome
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.CraftOutcome
import dev.loom.bodycontrol.SleepOutcome

private val CRAFT_REFUSALS: Map<CraftOutcome, IntentOutcome.Rejected> =
    mapOf(
        CraftOutcome.SENSE_FORGOTTEN to rejected("craft sense forgotten", EventTag.CRAFT_SENSE_FORGOTTEN),
        CraftOutcome.RECIPE_UNNAMED to rejected("craft recipe unnamed", EventTag.CRAFT_RECIPE_UNNAMED),
        CraftOutcome.INGREDIENTS_CHANGED to
            rejected("craft ingredients changed", EventTag.CRAFT_INGREDIENTS_CHANGED),
        CraftOutcome.RECIPE_UNAVAILABLE to
            rejected("craft recipe unavailable", EventTag.CRAFT_RECIPE_UNAVAILABLE),
        CraftOutcome.WORKSTATION_GONE to
            rejected("craft workstation gone", EventTag.CRAFT_WORKSTATION_GONE),
        CraftOutcome.WORKSTATION_CHANGED to
            rejected("craft workstation changed", EventTag.CRAFT_WORKSTATION_CHANGED),
        CraftOutcome.WORKSTATION_OUT_OF_REACH to
            rejected("craft workstation out of reach", EventTag.CRAFT_WORKSTATION_OUT_OF_REACH),
        CraftOutcome.WORKSTATION_NOT_VISIBLE to
            rejected("craft workstation not visible", EventTag.CRAFT_WORKSTATION_NOT_VISIBLE),
        CraftOutcome.TIMED_OUT to rejected("craft timed out", EventTag.CRAFT_TIMED_OUT),
        CraftOutcome.CANCELLED to rejected("craft cancelled", EventTag.CRAFT_CANCELLED),
        CraftOutcome.BODY_DIED to rejected("body died during craft", EventTag.CRAFT_BODY_DIED),
        CraftOutcome.DISCONNECTED to
            rejected("body disconnected during craft", EventTag.CRAFT_DISCONNECTED),
        CraftOutcome.LIBRARY_FAILURE to
            rejected("craft library failure", EventTag.CRAFT_LIBRARY_FAILURE),
    )

private val ATTACK_REFUSALS: Map<AttackOutcome, IntentOutcome.Rejected> =
    mapOf(
        AttackOutcome.SENSE_FORGOTTEN to rejected("attack sense forgotten", EventTag.ATTACK_SENSE_FORGOTTEN),
        AttackOutcome.TARGET_UNNAMED to rejected("attack target unnamed", EventTag.ATTACK_TARGET_UNNAMED),
        AttackOutcome.TARGET_GONE to rejected("attack target gone", EventTag.ATTACK_TARGET_GONE),
        AttackOutcome.TARGET_CHANGED to rejected("attack target changed", EventTag.ATTACK_TARGET_CHANGED),
        AttackOutcome.SLOT_UNNAMED to rejected("attack weapon unnamed", EventTag.ATTACK_SLOT_UNNAMED),
        AttackOutcome.SLOT_EMPTY to rejected("attack weapon slot empty", EventTag.ATTACK_SLOT_EMPTY),
        AttackOutcome.SLOT_CHANGED to rejected("attack weapon changed", EventTag.ATTACK_SLOT_CHANGED),
        AttackOutcome.HEALTH_BOUND to rejected("attack stopped at health bound", EventTag.ATTACK_HEALTH_BOUND),
        AttackOutcome.OUT_OF_RANGE to rejected("attack target out of range", EventTag.ATTACK_OUT_OF_RANGE),
        AttackOutcome.NO_ROUTE to rejected("attack route unavailable", EventTag.ATTACK_NO_ROUTE),
        AttackOutcome.SEARCH_OVER_BUDGET to
            rejected("attack search over budget", EventTag.ATTACK_SEARCH_OVER_BUDGET),
        AttackOutcome.STALLED to rejected("attack approach stalled", EventTag.ATTACK_STALLED),
        AttackOutcome.OVER_TRAVELLED to
            rejected("attack reached pursuit bound", EventTag.ATTACK_OVER_TRAVELLED),
        AttackOutcome.TIMED_OUT to rejected("attack timed out", EventTag.ATTACK_TIMED_OUT),
        AttackOutcome.CANCELLED to rejected("attack cancelled", EventTag.ATTACK_CANCELLED),
        AttackOutcome.BODY_DIED to rejected("body died during attack", EventTag.ATTACK_BODY_DIED),
        AttackOutcome.DISCONNECTED to
            rejected("body disconnected during attack", EventTag.ATTACK_DISCONNECTED),
        AttackOutcome.LIBRARY_FAILURE to
            rejected("attack library failure", EventTag.ATTACK_LIBRARY_FAILURE),
    )

private val SLEEP_REFUSALS: Map<SleepOutcome, IntentOutcome.Rejected> =
    mapOf(
        SleepOutcome.SENSE_FORGOTTEN to rejected("sleep sense forgotten", EventTag.SLEEP_SENSE_FORGOTTEN),
        SleepOutcome.BED_UNNAMED to rejected("sleep bed unnamed", EventTag.SLEEP_BED_UNNAMED),
        SleepOutcome.BED_GONE to rejected("sleep bed gone", EventTag.SLEEP_BED_GONE),
        SleepOutcome.BED_CHANGED to rejected("sleep bed changed", EventTag.SLEEP_BED_CHANGED),
        SleepOutcome.NOT_BED to rejected("offered block is not a bed", EventTag.SLEEP_NOT_BED),
        SleepOutcome.WRONG_DIMENSION to
            rejected("sleep is unsafe in this dimension", EventTag.SLEEP_WRONG_DIMENSION),
        SleepOutcome.OUT_OF_REACH to rejected("sleep bed out of reach", EventTag.SLEEP_OUT_OF_REACH),
        SleepOutcome.NOT_VISIBLE to rejected("sleep bed not visible", EventTag.SLEEP_NOT_VISIBLE),
        SleepOutcome.OCCUPIED to rejected("sleep bed occupied", EventTag.SLEEP_OCCUPIED),
        SleepOutcome.NOT_NIGHT to rejected("sleep refused outside night", EventTag.SLEEP_NOT_NIGHT),
        SleepOutcome.UNSAFE to rejected("sleep refused by nearby danger", EventTag.SLEEP_UNSAFE),
        SleepOutcome.SLEEP_REFUSED to rejected("vanilla refused sleep", EventTag.SLEEP_REFUSED),
        SleepOutcome.TIMED_OUT to rejected("sleep timed out", EventTag.SLEEP_TIMED_OUT),
        SleepOutcome.CANCELLED to rejected("sleep cancelled", EventTag.SLEEP_CANCELLED),
        SleepOutcome.BODY_DIED to rejected("body died during sleep", EventTag.SLEEP_BODY_DIED),
        SleepOutcome.DISCONNECTED to
            rejected("body disconnected during sleep", EventTag.SLEEP_DISCONNECTED),
        SleepOutcome.LIBRARY_FAILURE to
            rejected("sleep library failure", EventTag.SLEEP_LIBRARY_FAILURE),
    )

internal fun craftOutcome(report: BodyResponse.CraftReport): IntentOutcome =
    if (report.outcome == CraftOutcome.CRAFTED) {
        IntentOutcome.Accepted(
            "crafted ${requireNotNull(report.count)} ${requireNotNull(report.what)} " +
                "in ${requireNotNull(report.crafts)} application(s)",
        )
    } else {
        requireNotNull(CRAFT_REFUSALS[report.outcome])
    }

internal fun attackOutcome(report: BodyResponse.AttackReport): IntentOutcome =
    when {
        report.outcome == AttackOutcome.ATTACKED ->
            IntentOutcome.Accepted("completed ${report.attacks} bounded attack(s)")
        report.outcome == AttackOutcome.TARGET_DIED ->
            IntentOutcome.Accepted("target died after ${report.attacks} bounded attack(s)")
        // The body re-checks range before every strike, so out of range after one landed is the
        // target leaving it, not a target that was never in reach (r22 delta, tick 7743).
        report.outcome == AttackOutcome.OUT_OF_RANGE && report.attacks > 0 ->
            rejected("attack target escaped after ${report.attacks} strike(s)", EventTag.ATTACK_TARGET_ESCAPED)
        else -> requireNotNull(ATTACK_REFUSALS[report.outcome])
    }

internal fun sleepOutcome(report: BodyResponse.SleepReport): IntentOutcome =
    if (report.outcome == SleepOutcome.WOKE) {
        IntentOutcome.Accepted("slept until vanilla woke the player")
    } else {
        requireNotNull(SLEEP_REFUSALS[report.outcome])
    }

private fun rejected(
    reason: String,
    tag: String,
): IntentOutcome.Rejected = IntentOutcome.Rejected(reason, tag)
