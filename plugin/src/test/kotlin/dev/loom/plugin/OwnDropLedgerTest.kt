package dev.loom.plugin

import dev.loom.body.player.OwnDropReading
import dev.loom.body.player.yieldsOf
import dev.loom.contract.BlockYield
import dev.loom.contract.DropFate
import dev.loom.contract.MAX_OWN_DROPS
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * The r14 audit's drops, replayed through the ledger that attributes each dig's server drop to the
 * dig's own request: alpha's stick taken by gamma, beta's two taken by delta, kappa's own
 * collected, and a drop beside a dig that was not the dig's.
 */
class OwnDropLedgerTest {
    private var tick = 2_597L
    private val places = mutableMapOf<UUID, List<Double>>()
    private val ledger = OwnDropLedger({ tick }) { places[it] }

    private val alpha = uuid("alpha")
    private val beta = uuid("beta")
    private val gamma = uuid("gamma")
    private val delta = uuid("delta")

    @Test
    fun `a dig's drop is told to its digger by request whoever takes it`() {
        // alpha body:00000101: b3fl at 16,104,-2 dropped one stick that gamma collected.
        val stick = uuid("stick-1")
        ledger.dropped(alpha, stick, STICK, 1, listOf(16, 104, -2))
        places[stick] = listOf(17.1, 104.0, -1.5)
        val attributed = ledger.attribute(alpha, "alpha-sense-ab", listOf(16, 104, -2), sinceTick = 2_590)
        assertEquals(
            listOf(OwnDropReading("alpha-sense-ab", STICK, 1, DropFate.LYING, listOf(17.1, 104.0, -1.5), stick)),
            attributed,
        )
        ledger.pickedUp(stick, gamma)
        places.remove(stick)
        assertEquals(listOf(OwnDropReading("alpha-sense-ab", STICK, 1, DropFate.TAKEN, null)), ledger.of(alpha))
        // The taker is told nothing: it was not its dig.
        assertEquals(emptyList<OwnDropReading>(), ledger.of(gamma))
    }

    @Test
    fun `only the drops of the dug block in the dig's window are the dig's`() {
        // beta body:00000105: b4v5 at 10,100,5 dropped two sticks; a drop of another block and one
        // before the dig began are not its.
        val before = uuid("before")
        ledger.dropped(beta, before, STICK, 1, listOf(10, 100, 5))
        tick = 2_711
        val other = uuid("other")
        ledger.dropped(beta, other, STICK, 1, listOf(11, 100, 5))
        val sticks = uuid("sticks")
        ledger.dropped(beta, sticks, STICK, 2, listOf(10, 100, 5))
        places[sticks] = listOf(10.5, 100.0, 5.5)
        val attributed = ledger.attribute(beta, "beta-sense-b", listOf(10, 100, 5), sinceTick = 2_700)
        assertEquals(listOf(2L), attributed.map { it.count })
        ledger.pickedUp(sticks, delta)
        assertEquals(listOf(DropFate.TAKEN), ledger.of(beta).map { it.fate })
    }

    @Test
    fun `a drop collected by its digger, merged, or despawned keeps its own fate`() {
        val own = uuid("own")
        val merging = uuid("merging")
        val target = uuid("target")
        val rotting = uuid("rotting")
        val vanished = uuid("vanished")
        for (entity in listOf(own, merging, rotting, vanished)) {
            ledger.dropped(alpha, entity, STICK, 1, listOf(0, 64, 0))
            places[entity] = listOf(0.5, 64.0, 0.5)
        }
        places[target] = listOf(0.5, 64.0, 0.5)
        ledger.attribute(alpha, "r1", listOf(0, 64, 0), sinceTick = 0)
        places.remove(vanished)
        places.remove(merging)
        ledger.pickedUp(own, alpha)
        ledger.merged(merging, target)
        ledger.gone(rotting)
        // Newest first; a merged stack is followed into the one it joined, and an entity the server
        // no longer has with no event saying where it went is gone.
        assertEquals(
            listOf(DropFate.GONE, DropFate.GONE, DropFate.LYING, DropFate.COLLECTED),
            ledger.of(alpha).map { it.fate },
        )
        assertEquals(listOf(0.5, 64.0, 0.5), ledger.of(alpha)[2].position)
    }

    @Test
    fun `two own drops merged into one stack are both collected when it is picked up`() {
        // r19 theta body:00000496: its second log merged into the first, theta picked the stack up,
        // and the merged log read gone (perception body:00000509).
        val first = uuid("log-1")
        val second = uuid("log-2")
        places[first] = listOf(4.5, 78.0, 4.5)
        places[second] = listOf(4.5, 78.0, 4.5)
        ledger.dropped(alpha, first, LOG, 1, listOf(4, 80, 4))
        ledger.attribute(alpha, "r1", listOf(4, 80, 4), sinceTick = 0)
        ledger.dropped(alpha, second, LOG, 1, listOf(4, 79, 4))
        ledger.attribute(alpha, "r2", listOf(4, 79, 4), sinceTick = 0)
        ledger.merged(second, first)
        places.remove(second)
        ledger.pickedUp(first, alpha)
        places.remove(first)
        assertEquals(listOf(DropFate.COLLECTED, DropFate.COLLECTED), ledger.of(alpha).map { it.fate })
    }

    @Test
    fun `what is told is bounded in count and in server ticks`() {
        repeat(MAX_OWN_DROPS + 3) { index ->
            ledger.dropped(alpha, uuid("drop-$index"), STICK, 1, listOf(index, 64, 0))
            ledger.attribute(alpha, "r$index", listOf(index, 64, 0), sinceTick = 0)
        }
        val told = ledger.of(alpha)
        assertEquals(MAX_OWN_DROPS, told.size)
        assertEquals("r${MAX_OWN_DROPS + 2}", told.first().requestId)
        tick += OWN_DROP_RETENTION_TICKS + 1
        assertEquals(emptyList<OwnDropReading>(), ledger.of(alpha))
        // A drop never attributed expires unclaimed.
        ledger.dropped(beta, uuid("late"), STICK, 1, listOf(1, 1, 1))
        tick += OWN_DROP_RETENTION_TICKS + 1
        assertEquals(emptyList<OwnDropReading>(), ledger.attribute(beta, "x", listOf(1, 1, 1), sinceTick = 0))
    }

    @Test
    fun `sampled loot rolls give each item's range, and a roll that drops nothing is no item`() {
        // A dead bush drops nought to two sticks; r14 listed it as dropping air.
        val rolls = listOf(mapOf(STICK to 2L), emptyMap(), mapOf(STICK to 1L))
        assertEquals(listOf(BlockYield(STICK, 0, 2, 100)), yieldsOf(rolls))
        assertEquals(emptyList<BlockYield>(), yieldsOf(listOf(emptyMap(), emptyMap())))
        assertEquals(listOf(BlockYield(STICK, 1, 1, 100)), yieldsOf(listOf(mapOf(STICK to 1L), mapOf(STICK to 1L))))
        // r19: a leaf drops a stick about one dig in fifty, which the middle of its nought-to-two
        // range read as one a dig; the sampled mean states it as two in a hundred.
        val leaves = List(1000) { if (it % 50 == 0) mapOf(STICK to 1L) else emptyMap() }
        assertEquals(listOf(BlockYield(STICK, 0, 1, 2)), yieldsOf(leaves))
    }

    @Test
    fun `own drops admit only the bounded configured fleet`() {
        val server =
            Proxy.newProxyInstance(
                org.bukkit.Server::class.java.classLoader,
                arrayOf(org.bukkit.Server::class.java),
            ) { _, _, _ -> null } as org.bukkit.Server
        assertDoesNotThrow { BukkitOwnDrops(server, (0 until 16).map { uuid("p$it") }.toSet()) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { BukkitOwnDrops(server, (0 until 17).map { uuid("p$it") }.toSet()) }
    }

    @Test
    fun `a drop still lying is told for as long as the server has it, past retention`() {
        // r16 alpha: its dig at tick 220 dropped a stick that left its own-drop list at 5020 while
        // it lay until gamma took it at 5570, so alpha was never told.
        tick = 220
        val stick = uuid("stick-p")
        ledger.dropped(alpha, stick, STICK, 1, listOf(16, 104, -2))
        places[stick] = listOf(17.1, 104.0, -1.5)
        ledger.attribute(alpha, "alpha-sense-p", listOf(16, 104, -2), sinceTick = 200)
        tick = 5_065
        assertEquals(DropFate.LYING, ledger.of(alpha).single().fate)
        tick = 5_570
        ledger.pickedUp(stick, gamma)
        places.remove(stick)
        // Taken now, and told; retention runs from when its fate became known.
        assertEquals(DropFate.TAKEN, ledger.of(alpha).single().fate)
        tick = 5_570 + 4_800
        assertEquals(DropFate.TAKEN, ledger.of(alpha).single().fate)
        tick = 5_571 + 4_800
        assertEquals(emptyList<OwnDropReading>(), ledger.of(alpha))
        // One the server no longer has, with no event saying where it went, is gone, then forgotten.
        tick = 10_000
        val other = uuid("stick-q")
        ledger.dropped(alpha, other, STICK, 1, listOf(1, 64, 1))
        places[other] = listOf(1.5, 64.0, 1.5)
        ledger.attribute(alpha, "alpha-sense-q", listOf(1, 64, 1), sinceTick = 9_990)
        places.remove(other)
        tick = 10_500
        assertEquals(DropFate.GONE, ledger.of(alpha).single().fate)
        tick = 10_500 + 4_801
        assertEquals(emptyList<OwnDropReading>(), ledger.of(alpha))
    }

    @Test
    fun `a full own-drop list forgets a drop whose fate is known before one still lying`() {
        val lying = uuid("lying")
        ledger.dropped(alpha, lying, STICK, 1, listOf(0, 64, 0))
        places[lying] = listOf(0.5, 64.0, 0.5)
        ledger.attribute(alpha, "alpha-0", listOf(0, 64, 0), sinceTick = 0)
        for (index in 1..dev.loom.contract.MAX_OWN_DROPS) {
            val taken = uuid("taken-$index")
            ledger.dropped(alpha, taken, STICK, 1, listOf(index, 64, 0))
            ledger.pickedUp(taken, gamma)
            ledger.attribute(alpha, "alpha-$index", listOf(index, 64, 0), sinceTick = 0)
        }
        val told = ledger.of(alpha)
        assertEquals(dev.loom.contract.MAX_OWN_DROPS, told.size)
        assertEquals(1, told.count { it.fate == DropFate.LYING })
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val LOG = "item.minecraft.oak_log"

        fun uuid(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())
    }
}
