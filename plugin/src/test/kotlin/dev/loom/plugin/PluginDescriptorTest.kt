/*
 * Pins the shipped descriptor's permission gating.
 *
 * `/loombody` drives live agents and, through `think`, spends a real model call.
 * `/loomwatch` changes nothing, but what it shows is operator-only diagnostics - model
 * names, prompt versions, and the mind's own reasoning. Bukkit treats a command block
 * with no `permission:` key as open to every sender - `Command.testPermissionSilent`
 * returns true immediately when the permission field is null - so each gate is one line
 * of `plugin.yml` and its absence is completely silent: the command keeps working for the
 * op who tests it, and no other test in this suite would fail. Hence this one.
 *
 * The two nodes are also asserted to be *different* nodes. Gating the read-only display
 * on `loom.body` would work perfectly and would quietly mean that anybody allowed to
 * watch a run is also allowed to disturb it.
 *
 * The descriptor is read through Bukkit's own `PluginDescriptionFile`, the same parser the
 * server uses at load time, and off the test classpath rather than out of the source tree,
 * so what is asserted is what a loaded plugin would see.
 */
package dev.loom.plugin

import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The single node gating every `/loombody` subcommand. */
private const val BODY_PERMISSION = "loom.body"

/** The node gating the read-only operator display. */
private const val OBSERVE_PERMISSION = "loom.observe"

/** Every command Loom ships, and the node that must gate it. */
private val GATES =
    mapOf(
        "loombody" to BODY_PERMISSION,
        "loomwatch" to OBSERVE_PERMISSION,
    )

class PluginDescriptorTest {
    private val descriptor: PluginDescriptionFile =
        checkNotNull(PluginDescriptorTest::class.java.getResourceAsStream("/plugin.yml")) {
            "plugin.yml must ship with the plugin"
        }.use { PluginDescriptionFile(it) }

    /** The declared block for [name], which the descriptor must have. */
    private fun block(name: String): Map<*, *> =
        descriptor.commands[name] ?: error("the descriptor must declare the $name command")

    @Test
    @DisplayName("every command declares a permission, so none is open to every sender")
    fun everyCommandIsGated() {
        for ((name, node) in GATES) {
            assertEquals(
                node,
                block(name)["permission"],
                "without this key Bukkit permits every sender for /$name",
            )
        }
    }

    @Test
    @DisplayName("every declared permission exists and defaults to op")
    fun everyPermissionDefaultsToOp() {
        for (node in GATES.values) {
            val permission = descriptor.permissions.firstOrNull { it.name == node }

            assertNotNull(permission, "$node must be declared so its default is Loom's call, not the server's")
            assertEquals(PermissionDefault.OP, permission?.default, node)
        }
    }

    @Test
    @DisplayName("a refused sender is told which permission they lack")
    fun refusalNamesThePermission() {
        for ((name, node) in GATES) {
            val message = block(name)["permission-message"] as? String ?: ""

            assertTrue(message.contains(node), "the refusal for /$name should name $node; was \"$message\"")
        }
    }

    @Test
    @DisplayName("watching a run is gated separately from driving one")
    fun theReadOnlyDisplayHasItsOwnNode() {
        // Otherwise the display would only be grantable to somebody who can also spawn,
        // move, and spend model calls - and "let them watch" would stop being safe to say.
        assertEquals(GATES.values.toSet().size, GATES.size, "each command needs a node of its own")
        assertTrue(
            descriptor.permissions.none { OBSERVE_PERMISSION in it.children.keys },
            "$OBSERVE_PERMISSION must not be implied by another node",
        )
    }
}
