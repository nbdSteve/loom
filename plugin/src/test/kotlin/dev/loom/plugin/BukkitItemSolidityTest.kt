package dev.loom.plugin

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Item solidity read from the server's collision table, against r39's pressure plate. */
class BukkitItemSolidityTest {
    // As `readFrom` reads vanilla 1.21.1: a pressure plate gives its cell no collision.
    private val solidity =
        BukkitItemSolidity(
            mapOf(
                Material.DIRT to true,
                Material.OAK_LOG to true,
                Material.OAK_PRESSURE_PLATE to false,
                Material.OAK_BUTTON to false,
                Material.SHORT_GRASS to false,
            ),
        )

    @Test
    fun `an item is solid exactly when the block it places collides, whatever form its name takes`() {
        for (name in listOf("dirt", "minecraft:dirt", "DIRT", "oak_log")) assertEquals(true, solidity.solid(name), name)
        // r39 theta was offered its pressure plates as a shelter closer.
        for (name in listOf("oak_pressure_plate", "minecraft:oak_pressure_plate", "oak_button", "short_grass")) {
            assertEquals(false, solidity.solid(name), name)
        }
    }

    @Test
    fun `a name the server does not know as a block is unknown, which a shelter reads as no`() {
        assertNull(solidity.solid("stick"))
        assertNull(solidity.solid("not_an_item"))
    }
}
