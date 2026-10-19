/*
 * Which flights each agent's body is still carrying out, by the request that started each.
 *
 * Keyed by request rather than by agent, so an older flight's completion releases only its own
 * hold and never a newer one still running (r27: iota's flight m3 completed while mc ran, the
 * one-entry hold was cleared, and a review claimed mid-flight had its dig refused out of reach
 * where mc had carried the body). Main thread only.
 */
package dev.loom.mind

import dev.loom.body.AgentId

class FlightHolds {
    private val held = mutableMapOf<AgentId, MutableSet<String>>()

    /** [agentId]'s body begins the flight [requestId] decided. */
    fun hold(
        agentId: AgentId,
        requestId: String,
    ) {
        held.getOrPut(agentId) { mutableSetOf() } += requestId
    }

    /** The flight [requestId] has settled; any other of [agentId]'s still holds. */
    fun release(
        agentId: AgentId,
        requestId: String,
    ) {
        val flights = held[agentId] ?: return
        flights -= requestId
        if (flights.isEmpty()) held -= agentId
    }

    /** Whether any flight of [agentId]'s is still being carried out. */
    fun holds(agentId: AgentId): Boolean = held[agentId].orEmpty().isNotEmpty()
}
