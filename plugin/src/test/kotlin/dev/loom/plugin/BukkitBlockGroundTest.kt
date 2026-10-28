package dev.loom.plugin

import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The server is the witness of where a body may stand, and that includes its own world border:
 * the v29 plains rehearsal's 128-block border, which theta's flights and walks ran into.
 */
class BukkitBlockGroundTest {
    private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        } as T

    private fun server(): Server {
        val border =
            proxy<WorldBorder> { name, _ ->
                when (name) {
                    "getCenter" -> Location(null, 0.0, 0.0, 0.0)
                    "getSize" -> 128.0
                    else -> null
                }
            }
        // Air: vanilla's collision shape for it is empty, which is all the shared rule reads.
        val shape = proxy<VoxelShape> { name, _ -> if (name == "getBoundingBoxes") emptyList<BoundingBox>() else null }
        val data = proxy<BlockData> { name, _ -> if (name == "getCollisionShape") shape else null }
        val air =
            proxy<Block> { name, _ ->
                when (name) {
                    "getBlockData" -> data
                    "getLocation" -> Location(null, 0.0, 0.0, 0.0)
                    else -> null
                }
            }
        val world =
            proxy<World> { name, _ ->
                when (name) {
                    "getWorldBorder" -> border
                    "getBlockAt" -> air
                    else -> null
                }
            }
        return proxy { name, _ -> if (name == "getWorlds") listOf(world) else null }
    }

    @Test
    fun aPassableCellPastTheBorderIsNoCellABodyStandsIn() {
        val ground = BukkitBlockGround(server())
        assertTrue(ground.standsIn(listOf(27, 66, -64)))
        assertFalse(ground.standsIn(listOf(27, 66, -65)))
        assertFalse(ground.standsIn(listOf(64, 66, 0)))
        // Open air everywhere, so nothing inside has a floor and nothing past it is open.
        assertNull(ground.standCell(listOf(27, 65, -65)))
    }

    @Test
    fun noWorldIsNoGround() {
        val none = proxy<Server> { name, _ -> if (name == "getWorlds") emptyList<World>() else null }
        assertEquals(false, BukkitBlockGround(none).standsIn(listOf(0, 64, 0)))
    }
}
