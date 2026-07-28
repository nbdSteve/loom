/*
 * Death is terminal for an agent identity.
 *
 * Loom is permadeath: when a body dies, the agent is gone for good. There is no
 * respawn, no reincarnation, and no second body. An agent id that has died names a
 * dead agent forever, and new agents come from reproduction, never from restarting
 * an old one.
 *
 * That rule lives here, in the domain, rather than inside the adapter, because it
 * belongs to Loom and not to any engine: every body flavor must honour it, and a
 * rule this load-bearing should be pinned by a test rather than by a comment.
 *
 * The type deliberately offers no way to un-record a death. Terminality is
 * structural: there is no method a future caller could reach for to revive an
 * agent, so a respawn path cannot be added by accident.
 */
package dev.loom.body

/**
 * The record of which agents have died.
 *
 * Death is recorded, never cleared: [isDead] answers for the whole life of the
 * process, which is what lets a body port refuse to embody a dead agent again.
 */
class Mortality {
    private val dead = mutableSetOf<AgentId>()

    /** Whether [agentId] names an agent that has died. Once true, always true. */
    fun isDead(agentId: AgentId): Boolean = dead.contains(agentId)

    /**
     * Records that [agentId] has died.
     *
     * Returns true the first time, and false if the death was already recorded, so a
     * caller can emit the terminal death signal exactly once.
     */
    fun recordDeath(agentId: AgentId): Boolean = dead.add(agentId)
}
