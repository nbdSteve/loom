/*
 * Deterministic category-fair selection under Loom's shared scene bound.
 */
package dev.loom.body.player

import dev.loom.body.PERCEPTION_MAX_OBSERVATIONS
import dev.loom.body.blocksApart
import dev.loom.contract.ObservationKind
import java.util.UUID

/**
 * One body-list offer before the shared JVM scene bound is applied.
 *
 * [sourceIndex] remains the body-control ordinal that a surviving request-scoped ref
 * resolves to. It is also the final tie-break inside one category, so equal-distance
 * offers from one sense answer always receive refs in the same order.
 */
internal data class SceneOffer(
    val kind: ObservationKind,
    val sourceIndex: Int,
    val what: String,
    val position: List<Double>,
    val visible: Boolean,
    val subject: UUID? = null,
    val count: Long? = null,
) {
    val key: Pair<ObservationKind, Int> = kind to sourceIndex
}

/**
 * Keeps one nearest representative of every non-empty category, then spends the
 * remaining capacity on the globally nearest unselected offers.
 *
 * The returned list is globally distance-ordered. Consequently, filtering it by kind
 * also keeps every category nearest-first. Kind and source ordinal explicitly break
 * distance ties rather than relying on input concatenation or sort implementation
 * details.
 */
internal object PlayerSceneSelector {
    private val representedKinds =
        listOf(
            ObservationKind.ENTITY,
            ObservationKind.BLOCK,
            ObservationKind.DROPPED_ITEM,
        )

    fun select(
        origin: List<Double>,
        offers: List<SceneOffer>,
    ): List<SceneOffer> {
        require(offers.map(SceneOffer::key).distinct().size == offers.size) {
            "scene offer keys must be unique"
        }
        val ordered = offers.sortedWith(orderFrom(origin))
        if (ordered.size <= PERCEPTION_MAX_OBSERVATIONS) return ordered

        val selected = mutableSetOf<Pair<ObservationKind, Int>>()
        representedKinds.forEach { kind ->
            ordered.firstOrNull { it.kind == kind }?.let { selected += it.key }
        }
        for (offer in ordered) {
            if (selected.size == PERCEPTION_MAX_OBSERVATIONS) break
            selected += offer.key
        }
        return ordered.filter { it.key in selected }
    }

    private fun orderFrom(origin: List<Double>): Comparator<SceneOffer> =
        compareBy<SceneOffer> { blocksApart(origin, it.position) }
            .thenBy { kindOrder(it.kind) }
            .thenBy(SceneOffer::sourceIndex)

    private fun kindOrder(kind: ObservationKind): Int =
        when (kind) {
            ObservationKind.ENTITY -> 0
            ObservationKind.BLOCK -> 1
            ObservationKind.DROPPED_ITEM -> 2
        }
}
