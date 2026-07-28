/*
 * The production `MainThread`: Bukkit's scheduler.
 *
 * It lives in the wiring package rather than in `dev.loom.mind` because the port
 * is engine-free by rule - ArchUnit fails the build if a server type reaches it -
 * and this is the one adapter that needs a server. It is deliberately trivial:
 * anything more than "hand the task to the scheduler" would be logic that a test
 * cannot reach without a running server.
 */
package dev.loom.plugin

import dev.loom.mind.MainThread
import org.bukkit.plugin.Plugin

/**
 * Schedules tasks onto the server main thread.
 *
 * `runTask` always defers to a later tick, even when called from the main thread,
 * which is the property the bridge depends on: a decision is applied on a tick of
 * its own and never re-entrantly inside whatever produced it.
 */
class BukkitMainThread(
    private val plugin: Plugin,
) : MainThread {
    override fun execute(task: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { task() })
    }
}
