/*
 * Request-scoped authority for nearby block and dropped-item observations.
 */
package dev.loom.body

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldTargetRefsTest {
    private val ada = AgentId("ada")
    private val bo = AgentId("bo")

    @Test
    fun `block and dropped-item namespaces are distinct and bounded`() {
        val blocks = WorldTargetRefs("b", maxRefsPerRequest = 2)
        val items = WorldTargetRefs("d", maxRefsPerRequest = 2)
        val block = blocks.mint(listOf(3, 7, 9))
        val item = items.mint(listOf(3)).single()

        assertEquals(2, block.size)
        assertTrue(block.all { it.handle.startsWith("b") && it.handle.length <= 8 })
        assertTrue(item.handle.startsWith("d"))
        assertNotEquals(block.first().handle, item.handle)
    }

    @Test
    fun `one perception remembers and settles every ref namespace together`() {
        val refs = RequestRefs()
        val creature = refs.creatures.mint(listOf(UUID.randomUUID())).single()
        val block = refs.blocks.mint(listOf(4)).single()
        val item = refs.droppedItems.mint(listOf(2)).single()
        val slot = refs.slots.mint(listOf(36)).single()
        refs.remember(
            agent = ada,
            requestId = "req-1",
            bindings =
                RequestRefBindings(
                    creatures = listOf(creature),
                    blocks = listOf(block),
                    droppedItems = listOf(item),
                    slots = listOf(slot),
                ),
            atTick = 100,
        )

        assertEquals(
            WorldTargetResolution.Bound(4),
            refs.blocks.resolve(ada, "req-1", block.handle, 100),
        )
        assertEquals(
            WorldTargetResolution.Bound(2),
            refs.droppedItems.resolve(ada, "req-1", item.handle, 100),
        )
        assertEquals(
            WorldTargetResolution.Unknown,
            refs.blocks.resolve(ada, "req-1", item.handle, 100),
        )

        refs.settle(ada, "req-1")

        assertEquals(
            WorldTargetResolution.Stale,
            refs.blocks.resolve(ada, "req-1", block.handle, 100),
        )
        assertEquals(
            WorldTargetResolution.Stale,
            refs.droppedItems.resolve(ada, "req-1", item.handle, 100),
        )
    }

    @Test
    fun `world refs do not cross requests agents expiry or eviction`() {
        val refs =
            WorldTargetRefs(
                prefix = "b",
                lifetimeTicks = 10,
                requestsInFlight = 2,
            )
        val first = refs.mint(listOf(0)).single()
        refs.remember(ada, "req-1", listOf(first), atTick = 20)
        val otherAgent = refs.mint(listOf(0)).single()
        refs.remember(bo, "req-1", listOf(otherAgent), atTick = 20)
        val otherRequest = refs.mint(listOf(0)).single()
        refs.remember(ada, "req-2", listOf(otherRequest), atTick = 20)

        assertEquals(
            WorldTargetResolution.Unknown,
            refs.resolve(bo, "req-1", first.handle, 20),
        )
        assertEquals(
            WorldTargetResolution.Unknown,
            refs.resolve(ada, "req-2", first.handle, 20),
        )
        assertEquals(
            WorldTargetResolution.Stale,
            refs.resolve(ada, "req-1", first.handle, 30),
        )

        val middle = refs.mint(listOf(1)).single()
        refs.remember(ada, "req-3", listOf(middle), atTick = 31)
        val newest = refs.mint(listOf(2)).single()
        refs.remember(ada, "req-4", listOf(newest), atTick = 31)

        assertEquals(
            WorldTargetResolution.Stale,
            refs.resolve(ada, "req-2", otherRequest.handle, 31),
        )
        assertEquals(
            WorldTargetResolution.Bound(1),
            refs.resolve(ada, "req-3", middle.handle, 31),
        )
        assertEquals(
            WorldTargetResolution.Bound(2),
            refs.resolve(ada, "req-4", newest.handle, 31),
        )
    }
}
