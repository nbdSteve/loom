package dev.loom.body.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** What a body that found no way to a dropped item remembers, against r15's trench and r34 delta's stuck cell. */
class RouteRefusalsTest {
    private val log = UUID.fromString("bbba1205-277c-4297-bff8-a2c179a622a0")
    private var tick = 3_307L
    private val solid = mutableSetOf<List<Int>>()

    /** The ground under delta at y 65, and the rise its logs lay on at y 66 from x -6 east. */
    private val ground =
        BlockGround { cell -> cell !in solid && cell[1] > 65 && !(cell[1] == 66 && cell[0] >= -6) }
    private val refusals = RouteRefusals({ tick }, ground)

    // Where r34 delta stood, and where its first dug log lay, when its pickup stalled at tick 3307.
    private val stuck = listOf(-8.434705761507354, 66.0, -27.5)
    private val lying = listOf(-3.99072265625, 67.0, -26.64599609375)

    @Test
    fun `a stall holds from the same cell to the same cell over the same ground, and only there`() {
        refusals.stalled(log, stuck, lying)
        // The collect at tick 3622, from the same cell: not offered.
        tick = 3_378
        assertTrue(refusals.blocks(log, stuck, lying))
        assertFalse(log in refusals, "a stall is no route refusal anywhere")
        // Another cell is another approach.
        assertFalse(refusals.blocks(log, listOf(-7.5, 66.0, -27.5), lying))
        // The item moved to another cell.
        assertFalse(refusals.blocks(log, stuck, listOf(-2.5, 67.0, -26.5)))
        // A block dug or placed between them is other ground.
        solid += listOf(-6, 67, -27)
        assertFalse(refusals.blocks(log, stuck, lying))
        solid.clear()
        assertTrue(refusals.blocks(log, stuck, lying))
        // And it lasts no longer than an item lives.
        tick += ROUTE_REFUSAL_TICKS + 1
        assertFalse(refusals.blocks(log, stuck, lying))
    }

    @Test
    fun `a missing route holds wherever the body stands, for as long as the item lives`() {
        refusals.refused(log)
        assertTrue(log in refusals)
        assertTrue(refusals.blocks(log, stuck, lying))
        assertTrue(refusals.blocks(log, listOf(1.5, 68.0, -15.5), lying))
        tick += ROUTE_REFUSAL_TICKS + 1
        assertFalse(log in refusals)
    }
}
