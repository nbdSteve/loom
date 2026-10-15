/*
 * The vital half of [corroborate]: how a body's continuous readings are compared with the
 * server's across one sense round trip. Inventory, which does not drift, stays in
 * `Corroboration.kt`.
 */
package dev.loom.witness

import dev.loom.bodycontrol.SensedVitals

/*
 * A continuous vital is checked against the span of the server's readings from before the
 * sense was sent to after its answer arrived. Fast regeneration or one hit inside that
 * round trip moves health by more than any fixed tolerance, and the client reading is
 * some moment inside the window; a claim outside the whole span is still a dispute.
 */
internal fun vitalFaults(
    claimed: SensedVitals,
    before: ObservedVitals,
    after: ObservedVitals,
): List<VitalFault> =
    buildList {
        if (outsideSpan(claimed.health, before.health, after.health, VITAL_DRIFT_TOLERANCE)) {
            add(VitalFault(Vital.HEALTH, claimed.health, before.health))
        }
        if (outsideSpan(
                claimed.food.toDouble(),
                before.food.toDouble(),
                after.food.toDouble(),
                FOOD_DRIFT_TOLERANCE.toDouble(),
            )
        ) {
            add(VitalFault(Vital.FOOD, claimed.food.toDouble(), before.food.toDouble()))
        }
        if (saturationContradicts(claimed.saturation, before.saturation, after.saturation)) {
            add(VitalFault(Vital.SATURATION, claimed.saturation, before.saturation))
        }
    }

/*
 * Vanilla resends saturation only beside a health or food change or when it crosses zero, so a
 * client's saturation can stay above or below the server's for as long as it changes quietly.
 * What vanilla does keep in step is whether it is zero, so a claim is contradicted only when
 * its zero-ness disagrees with the server's throughout the round trip.
 */
private fun saturationContradicts(
    claimed: Double,
    before: Double,
    after: Double,
): Boolean {
    val serverEmpty = before == 0.0 && after == 0.0
    val serverFed = before > 0.0 && after > 0.0
    return (serverEmpty && claimed > 0.0) || (serverFed && claimed == 0.0)
}

private fun outsideSpan(
    claimed: Double,
    before: Double,
    after: Double,
    tolerance: Double,
): Boolean = claimed < minOf(before, after) - tolerance || claimed > maxOf(before, after) + tolerance
