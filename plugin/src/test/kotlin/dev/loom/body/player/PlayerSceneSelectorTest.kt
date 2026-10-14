package dev.loom.body.player

import dev.loom.body.BODY_SENSE_MAX_OFFERS_PER_KIND
import dev.loom.body.PERCEPTION_MAX_OBSERVATIONS
import dev.loom.contract.ObservationKind
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSceneSelectorTest {
    @Test
    fun `sixteen or forty-eight closer blocks remove neither entities nor items`() {
        for (blockCount in listOf(16, BODY_SENSE_MAX_OFFERS_PER_KIND)) {
            val offers =
                blocks(blockCount) +
                    offer(ObservationKind.ENTITY, 0, 100.0) +
                    offer(ObservationKind.DROPPED_ITEM, 0, 101.0)

            val selected = PlayerSceneSelector.select(ORIGIN, offers)

            assertEquals(offers.size, selected.size)
            assertEquals(blockCount, selected.count { it.kind == ObservationKind.BLOCK })
            assertEquals(1, selected.count { it.kind == ObservationKind.ENTITY })
            assertEquals(1, selected.count { it.kind == ObservationKind.DROPPED_ITEM })
        }
    }

    @Test
    fun `future oversized block input cannot reduce a non-empty entity category to zero`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(PERCEPTION_MAX_OBSERVATIONS) +
                    offer(ObservationKind.ENTITY, 0, 1_000.0),
            )

        assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND + 1, selected.size)
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            selected.count { it.kind == ObservationKind.BLOCK },
        )
        assertTrue(selected.any { it.kind == ObservationKind.ENTITY })
    }

    @Test
    fun `future oversized block input cannot reduce a non-empty item category to zero`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(PERCEPTION_MAX_OBSERVATIONS) +
                    offer(ObservationKind.DROPPED_ITEM, 0, 1_000.0),
            )

        assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND + 1, selected.size)
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            selected.count { it.kind == ObservationKind.BLOCK },
        )
        assertTrue(selected.any { it.kind == ObservationKind.DROPPED_ITEM })
    }

    @Test
    fun `future entity and item crowds preserve an actionable block representative`() {
        val offers =
            (0 until PERCEPTION_MAX_OBSERVATIONS).map { index ->
                offer(ObservationKind.ENTITY, index, index + 1.0)
            } +
                offer(ObservationKind.BLOCK, 0, 1_000.0) +
                offer(ObservationKind.DROPPED_ITEM, 0, 1_001.0)

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND + 2, selected.size)
        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            selected.count { it.kind == ObservationKind.ENTITY },
        )
        assertTrue(selected.any { it.kind == ObservationKind.BLOCK })
        assertTrue(selected.any { it.kind == ObservationKind.DROPPED_ITEM })
    }

    @Test
    fun `empty categories reserve no capacity`() {
        val selected =
            PlayerSceneSelector.select(
                ORIGIN,
                blocks(PERCEPTION_MAX_OBSERVATIONS) +
                    offer(ObservationKind.ENTITY, 0, 1_000.0),
            )

        assertEquals(
            BODY_SENSE_MAX_OFFERS_PER_KIND,
            selected.count { it.kind == ObservationKind.BLOCK },
        )
        assertEquals(1, selected.count { it.kind == ObservationKind.ENTITY })
    }

    @Test
    fun `a blocks-only malformed scene keeps the nearest forty-eight blocks exactly`() {
        val offers =
            (0 until 150).map { index ->
                offer(ObservationKind.BLOCK, index, (150 - index).toDouble())
            }

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(
            (149 downTo 102).toList(),
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
    fun `forty-eight offers of every kind preserve the complete one hundred forty-four offer envelope`() {
        val offers =
            ObservationKind.entries.flatMap { kind ->
                (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).map { index ->
                    offer(kind, index, index + kind.ordinal / 10.0)
                }
            }

        val selected = PlayerSceneSelector.select(ORIGIN, offers)

        assertEquals(PERCEPTION_MAX_OBSERVATIONS, selected.size)
        for (kind in ObservationKind.entries) {
            assertEquals(BODY_SENSE_MAX_OFFERS_PER_KIND, selected.count { it.kind == kind })
            assertEquals(
                (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).toList(),
                sourceIndices(selected, kind),
            )
        }
    }

    @Test
    fun `forty-nine per kind is deterministically bounded with stable tie ordering`() {
        val offers =
            ObservationKind.entries.flatMap { kind ->
                (0 until BODY_SENSE_MAX_OFFERS_PER_KIND + 1).map { index ->
                    offer(kind, index, 1.0)
                }
            }
        val expected =
            listOf(
                ObservationKind.ENTITY to (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).toList(),
                ObservationKind.BLOCK to (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).toList(),
                ObservationKind.DROPPED_ITEM to
                    (0 until BODY_SENSE_MAX_OFFERS_PER_KIND).toList(),
            )

        repeat(20) { seed ->
            val selected =
                PlayerSceneSelector.select(
                    ORIGIN,
                    offers.shuffled(Random(seed)),
                )
            assertEquals(PERCEPTION_MAX_OBSERVATIONS, selected.size)
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
