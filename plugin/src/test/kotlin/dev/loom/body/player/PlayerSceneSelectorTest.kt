package dev.loom.body.player

import dev.loom.contract.ObservationKind
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSceneSelectorTest {
    @Test
    fun `sixteen closer blocks keep one entity one item and fourteen blocks`() {
        val offers =
            blocks(16) +
                offer(ObservationKind.ENTITY, 0, 17.0) +
                offer(ObservationKind.DROPPED_ITEM, 0, 18.0)

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(16, selected.size)
        assertEquals(1, selected.count { it.kind == ObservationKind.ENTITY })
        assertEquals(1, selected.count { it.kind == ObservationKind.DROPPED_ITEM })
        assertEquals(14, selected.count { it.kind == ObservationKind.BLOCK })
        assertEquals(
            (0 until 14).toList(),
            selected.filter { it.kind == ObservationKind.BLOCK }.map(SceneOffer::sourceIndex),
        )
    }

    @Test
    fun `closer blocks cannot reduce a non-empty entity category to zero`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(16) + offer(ObservationKind.ENTITY, 0, 100.0),
            )

        assertTrue(selected.any { it.kind == ObservationKind.ENTITY })
    }

    @Test
    fun `closer blocks cannot reduce a non-empty dropped-item category to zero`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(16) + offer(ObservationKind.DROPPED_ITEM, 0, 100.0),
            )

        assertTrue(selected.any { it.kind == ObservationKind.DROPPED_ITEM })
    }

    @Test
    fun `entity and item crowds preserve an actionable block representative`() {
        val offers =
            (0 until 16).map { index ->
                offer(ObservationKind.ENTITY, index, index + 1.0)
            } +
                offer(ObservationKind.BLOCK, 0, 100.0) +
                offer(ObservationKind.DROPPED_ITEM, 0, 101.0)

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertTrue(selected.any { it.kind == ObservationKind.BLOCK })
    }

    @Test
    fun `empty categories reserve no capacity`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(16) + offer(ObservationKind.ENTITY, 0, 100.0),
            )

        assertEquals(15, selected.count { it.kind == ObservationKind.BLOCK })
        assertEquals(1, selected.count { it.kind == ObservationKind.ENTITY })
    }

    @Test
    fun `a blocks-only scene keeps the nearest sixteen blocks exactly`() {
        val offers =
            (0 until 20).map { index ->
                offer(ObservationKind.BLOCK, index, (20 - index).toDouble())
            }

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(
            (19 downTo 4).toList(),
            selected.map(SceneOffer::sourceIndex),
        )
    }

    @Test
    fun `a scene below the shared bound preserves every offer`() {
        val offers =
            listOf(
                offer(ObservationKind.DROPPED_ITEM, 0, 4.0),
                offer(ObservationKind.BLOCK, 0, 2.0),
                offer(ObservationKind.ENTITY, 0, 3.0),
            )

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(offers.toSet(), selected.toSet())
        assertEquals(
            listOf(ObservationKind.BLOCK, ObservationKind.ENTITY, ObservationKind.DROPPED_ITEM),
            selected.map(SceneOffer::kind),
        )
    }

    @Test
    fun `multiple entities and items keep their nearest representative then follow global distance`() {
        val offers =
            blocks(12) +
                listOf(
                    offer(ObservationKind.ENTITY, 0, 15.0),
                    offer(ObservationKind.ENTITY, 1, 13.0),
                    offer(ObservationKind.ENTITY, 2, 100.0),
                    offer(ObservationKind.DROPPED_ITEM, 0, 16.0),
                    offer(ObservationKind.DROPPED_ITEM, 1, 14.0),
                    offer(ObservationKind.DROPPED_ITEM, 2, 101.0),
                )

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(listOf(1, 0), sourceIndices(selected, ObservationKind.ENTITY))
        assertEquals(listOf(1, 0), sourceIndices(selected, ObservationKind.DROPPED_ITEM))
        assertEquals(12, selected.count { it.kind == ObservationKind.BLOCK })
    }

    @Test
    fun `equal distances use stable kind and source-index ordering across input orders`() {
        val offers =
            ObservationKind.entries.flatMap { kind ->
                (0 until 6).map { index -> offer(kind, index, 1.0) }
            }
        val expected =
            listOf(
                ObservationKind.ENTITY to (0 until 6).toList(),
                ObservationKind.BLOCK to (0 until 6).toList(),
                ObservationKind.DROPPED_ITEM to (0 until 4).toList(),
            )

        repeat(20) { seed ->
            val selected =
                PlayerSceneSelector.select(
                    ORIGIN,
                    offers.shuffled(Random(seed)),
                )
            assertEquals(
                expected,
                ObservationKind.entries.map { kind -> kind to sourceIndices(selected, kind) },
            )
        }
    }

    private fun blocks(count: Int): List<SceneOffer> =
        (0 until count).map { index ->
            offer(ObservationKind.BLOCK, index, index + 1.0)
        }

    private fun offer(
        kind: ObservationKind,
        sourceIndex: Int,
        distance: Double,
    ): SceneOffer =
        SceneOffer(
            kind = kind,
            sourceIndex = sourceIndex,
            what = kind.name,
            position = listOf(distance, 0.0, 0.0),
            visible = true,
        )

    private fun sourceIndices(
        selected: List<SceneOffer>,
        kind: ObservationKind,
    ): List<Int> =
        selected
            .filter { it.kind == kind }
            .map(SceneOffer::sourceIndex)

    private companion object {
        val ORIGIN: List<Double> = listOf(0.0, 0.0, 0.0)
    }
}
