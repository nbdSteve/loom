/*
 * Pins the shipped descriptor's permission gating.
 *
 * `/loombody` drives live agents and, through `think`, spends a real model call.
 * Bukkit treats a command block with no `permission:` key as open to every
 * sender - `Command.testPermissionSilent` returns true immediately when the
 * permission field is null - so the gate is one line of `plugin.yml` and its
 * absence is completely silent: the command keeps working for the op who tests
 * it, and no other test in this suite would fail. Hence this one.
 *
 * The descriptor is read through Bukkit's own `PluginDescriptionFile`, the same
 * parser the server uses at load time, and off the test classpath rather than
 * out of the source tree, so what is asserted is what a loaded plugin would see.
 */
package dev.loom.plugin

import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PluginDescriptorTest {
    private val descriptor: PluginDescriptionFile =
        checkNotNull(PluginDescriptorTest::class.java.getResourceAsStream("/plugin.yml")) {
            "plugin.yml must ship with the plugin"
        }.use { PluginDescriptionFile(it) }

    private val bodyCommand: Map<*, *> =
        descriptor.commands["loombody"] ?: error("the descriptor must declare the loombody command")

    @Test
    @DisplayName("the loombody command declares a permission, so it is not open to every sender")
    fun bodyCommandIsGated() {
        assertEquals(
            PERMISSION,
            bodyCommand["permission"],
            "without this key Bukkit permits every sender: any player could despawn agents and spend model calls",
        )
    }

    @Test
    @DisplayName("the declared permission exists and defaults to op")
    fun permissionDefaultsToOp() {
        val permission = descriptor.permissions.firstOrNull { it.name == PERMISSION }
        assertNotNull(permission, "$PERMISSION must be declared so its default is Loom's call, not the server's")
        assertEquals(PermissionDefault.OP, permission?.default)
    }

    @Test
    @DisplayName("a refused sender is told which permission they lack")
    fun refusalNamesThePermission() {
        val message = bodyCommand["permission-message"] as? String ?: ""
        assertTrue(message.contains(PERMISSION), "the refusal should name $PERMISSION; was \"$message\"")
    }

    private companion object {
        /** The single node gating every `/loombody` subcommand. */
        const val PERMISSION = "loom.body"
    }
}
