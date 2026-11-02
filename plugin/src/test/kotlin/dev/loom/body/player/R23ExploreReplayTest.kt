package dev.loom.body.player

import dev.loom.body.blocksApart
import dev.loom.contract.Bearing
import dev.loom.replay.CommittedEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * r23 delta at body:00001464 remembered an oak log 4.7 blocks east and was offered only the
 * twelve-block cell past it (x3, 11.7 off); body:00001473 walked there and back. Replayed with
 * delta's exact place and recalled reading over level ground, its east cell now stands beside the
 * log rather than past it.
 */
class R23ExploreReplayTest {
    @Test
    fun `delta's remembered log at body 1464 is walked to beside it, not past it`() {
        val seen = CommittedEvidence.R23.perception("body:00001464")
        val feet = seen.selfState.position
        val log = seen.recalled.single()
        assertEquals(Bearing.EAST, log.direction)
        val across = sqrt(log.distance * log.distance - log.verticalOffset * log.verticalOffset)
        val source = listOf(feet[0] + across, feet[1] + log.verticalOffset, feet[2])
        val floorY = floor(feet[1]).toInt()
        val level = BlockGround { cell -> cell[1] >= floorY }
        val before = exploreOf(level, feet).map { it.first }.single { it.ref == "x3" }
        assertTrue(before.distance > EXPLORE_REACH_BLOCKS - 1, "the cell r23 offered lay past the log")
        val east = exploreOf(level, feet, listOf(source)).map { it.second }[2]
        val centre = listOf(east[0] + 0.5, east[1].toDouble(), east[2] + 0.5)
        assertTrue(blocksApart(centre, feet) < log.distance, "nearer than the log itself: $east")
        assertTrue(kotlin.math.abs(centre[0] - source[0]) <= 2.5 && kotlin.math.abs(centre[2] - source[2]) <= 2.5)
    }
}
