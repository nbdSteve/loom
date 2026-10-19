/*
 * The facts one inventory dispute compared, as the record states them.
 *
 * A dispute stops a primary-behavior run, and the run's raw evidence is then removed; what is left
 * is the bounded failure diagnostic. So the record carries what the comparison saw - the claim, the
 * server's inventory at the request, and how it differed at the answer and at each earlier tick of
 * the lag window - in a compressed form that names only slots, items and counts. r26's dispute left
 * nothing but its tag, and its cause could only be inferred.
 */
package dev.loom.witness

import dev.loom.body.DisputeEvidence
import dev.loom.body.EarlierInventory
import dev.loom.bodycontrol.SensedStack

/** [claim] against [reading] and the [around] readings, or `null` when their times were not taken. */
internal fun disputeEvidenceOf(
    claim: BodyClaim,
    reading: ServerReading,
    around: Around,
): DisputeEvidence? {
    val timing = around.timing ?: return null
    val atRequest = reading.inventory
    return DisputeEvidence(
        requestTick = timing.requestTick,
        answerTick = timing.answerTick,
        roundTripMillis = timing.roundTripMillis,
        claimed = claim.inventory.sortedBy(SensedStack::slot).map { stackOf(it.slot, it.what, it.count) },
        atRequest = atRequest.sortedBy(ObservedStack::slot).map { stackOf(it.slot, it.what, it.count) },
        atAnswer = around.after?.let { changedFrom(atRequest, it.inventory) }.orEmpty(),
        earlier = around.earlier.map { EarlierInventory(it.tick, changedFrom(atRequest, it.inventory)) },
    )
}

private fun stackOf(
    slot: Int,
    what: String,
    count: Int,
): String = "$slot $what $count"

/** The slots of [later] that differ from [base], each as its stack or `"slot -"` when emptied. */
private fun changedFrom(
    base: List<ObservedStack>,
    later: List<ObservedStack>,
): List<String> {
    val before = base.associateBy(ObservedStack::slot)
    val after = later.associateBy(ObservedStack::slot)
    return (before.keys + after.keys).sorted().mapNotNull { slot ->
        val now = after[slot]
        when {
            now == before[slot] -> null
            now == null -> "$slot -"
            else -> stackOf(slot, now.what, now.count)
        }
    }
}
