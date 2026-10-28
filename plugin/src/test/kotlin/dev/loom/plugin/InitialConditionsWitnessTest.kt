package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.InitialConditionsEvidence
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

private inline fun <reified T> answering(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> T::class.java.simpleName
            else -> answer(method.name)
        }
    } as T

private fun playerAt(
    location: Location,
    contents: Array<ItemStack?>,
    cursor: ItemStack?,
): Player {
    val inventory: PlayerInventory = answering { if (it == "getContents") contents else null }
    return answering {
        when (it) {
            "getLocation" -> location
            "getInventory" -> inventory
            "getItemOnCursor" -> cursor
            else -> null
        }
    }
}

/** A stack of one [type], without the server a real stack's construction asks for. */
@Suppress("DEPRECATION")
private fun stackOf(type: Material): ItemStack =
    object : ItemStack() {
        override fun getType(): Material = type

        override fun getAmount(): Int = 1

        override fun isEmpty(): Boolean = type == Material.AIR
    }

/** The server's reading of every agent when a held run begins, against r38's first-perception check. */
class InitialConditionsWitnessTest {
    @Test
    fun `begin records every configured agent's server reading on one tick, sorted, and an absent player as absent`() {
        val records = mutableListOf<BodyEventRecord>()
        val pinned = InitialConditionsEvidence(position = listOf(3.5, 67.0, 11.5), inventoryStacks = 0)
        InitialConditionsWitness(
            eventLog = BodyEventLog { records += it },
            agents = { listOf(AgentId("tribe-zeta"), AgentId("tribe-gamma"), AgentId("tribe-alpha")) },
            readingOf = { agent -> if (agent.value == "tribe-zeta") null else pinned },
            currentTick = { 3L },
        ).witness()
        assertEquals(listOf("tribe-alpha", "tribe-gamma", "tribe-zeta"), records.map { it.agentId })
        assertTrue(records.all { it.event == BodyEvent.INITIAL_CONDITIONS_WITNESSED && it.tick == 3L })
        assertEquals(pinned, records[1].initial)
        assertEquals(EventTag.INITIAL_CONDITIONS_WITNESSED, records[1].outcome)
        assertNull(records[2].initial)
        assertEquals(EventTag.INITIAL_CONDITIONS_ABSENT, records[2].outcome)
    }

    @Test
    fun `the reading is the player's exact position and every stack it holds, the cursor's included`() {
        val at = Location(null, 3.5, 67.0, 11.5)
        val empty = playerAt(at, arrayOfNulls(41), null)
        assertEquals(InitialConditionsEvidence(listOf(3.5, 67.0, 11.5), 0), initialConditionsOf(empty))
        // A laid egg picked up off the ground, and a stack the cursor still holds.
        val contents = arrayOf(null, stackOf(Material.EGG), stackOf(Material.AIR), null)
        val holding = playerAt(at, contents, stackOf(Material.DIRT))
        assertEquals(2, initialConditionsOf(holding).inventoryStacks)
    }
}
