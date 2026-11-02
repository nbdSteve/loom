package dev.loom.witness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class InventoryHistoryTest {
    private val beta = UUID.fromString("00000000-0000-4000-8000-000000000002")

    private fun dyes(count: Int) = listOf(ObservedStack(9, "yellow_dye", count))

    @Test
    fun `a request sees the lag window before it and every tick of its round trip after it`() {
        val history = InventoryHistory()
        history.watch(beta)
        for (tick in 2_940L..2_946L) history.sample(tick) { dyes((tick - 2_939).toInt()) }
        val lagged = (2_943L..2_946L).toList()
        assertEquals(lagged, history.lagWindow(beta).map { it.tick })
        // r29 beta: a sense requested at 2945 and read at a craft's settled boundary inside 2946.
        assertEquals(listOf(2_945L, 2_946L), history.duringRoundTrip(beta, 2_945).map { it.tick })
        assertEquals(dyes(7), history.duringRoundTrip(beta, 2_945).last().inventory)
    }

    @Test
    fun `the history is bounded to the lag window and one round trip, and forgets a player who left`() {
        val history = InventoryHistory()
        history.watch(beta)
        for (tick in 0L until 500L) history.sample(tick) { dyes(1) }
        assertEquals(SENSE_ROUND_TRIP_TICKS, history.duringRoundTrip(beta, 0).size)
        assertEquals(499L, history.duringRoundTrip(beta, 0).last().tick)
        history.sample(500) { null }
        assertEquals(emptyList<InventoryAt>(), history.lagWindow(beta))
    }
}
