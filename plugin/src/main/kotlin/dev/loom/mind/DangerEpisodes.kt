/*
 * Bounded danger episodes: one model call per agent per threat while the agent is already
 * answering that threat.
 *
 * A threat sighting and the damage it deals are separate native events, and the threat
 * debounce and the damage episode each bound their own kind. Neither knows the other, and
 * neither knows that the agent is already in the middle of a decision or a flight about the
 * same creature. A provider-free two-cycle rehearsal measured the result: 59 of 84 calls in
 * one night were threat or damage cognition, and most arrived while the agent's previous
 * decision about the same kind of creature was still being decided or carried out, where the
 * body could only refuse a second flight as not ready.
 *
 * So an episode opens when a threat or damage perception about one threat is sent to the
 * mind. Until that decision has settled - answered and applied, or lost - and for
 * [DANGER_EPISODE_COOLDOWN_TICKS] after, another threat or damage perception about the same
 * threat for the same agent is coalesced into it rather than sent. A different threat is new
 * information and is always sent. Nothing here decides how to act; it decides only whether
 * a second identical question is worth a model call.
 *
 * Engine-free and main-thread only, like the rest of the cognition seam.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.contract.Perception
import dev.loom.contract.Trigger

/**
 * How long after an episode's decision settles a repeat about the same threat stays
 * coalesced, in server ticks.
 *
 * Measured, not chosen: in the normal-difficulty rehearsal 11 of 17 repeat triggers about the
 * same threat arrived within 111 ticks of the previous decision settling, and the rest only
 * after 150 or more, when a new engagement had begun. One hundred ticks is also the threat
 * debounce, so a creature standing in view re-asks on the cadence the debounce already set.
 */
const val DANGER_EPISODE_COOLDOWN_TICKS: Long = 100

/** The threat a danger perception is about, or null for a perception that is not one. */
fun dangerKeyOf(trigger: Trigger): String? =
    when (trigger) {
        is Trigger.ThreatSighted -> trigger.threat.removePrefix("entity.minecraft.")
        is Trigger.Damaged -> trigger.source.removePrefix("entity.minecraft.").removePrefix("minecraft:")
        else -> null
    }

class DangerEpisodes(
    private val currentTick: () -> Long,
) {
    private class Episode {
        val open = mutableSetOf<String>()
        var settledTick: Long? = null
    }

    private val episodes = mutableMapOf<AgentId, MutableMap<String, Episode>>()

    /**
     * Whether [perception] should be sent to the mind. A non-danger perception always is; a
     * danger perception opens or joins its threat's episode when it is sent, and is refused
     * while that episode is active or cooling down.
     */
    fun admit(
        agentId: AgentId,
        perception: Perception,
    ): Boolean {
        val key = dangerKeyOf(perception.trigger) ?: return true
        val now = currentTick()
        val byThreat = episodes.getOrPut(agentId) { mutableMapOf() }
        byThreat.entries.removeIf { (_, episode) -> episode.expiredAt(now) }
        // Whatever survived the sweep is open or still cooling down.
        val admitted = key !in byThreat
        if (admitted) byThreat[key] = Episode().also { it.open += perception.requestId }
        return admitted
    }

    /** Marks the decision for [requestId] settled, which starts its episode's cooldown. */
    fun settled(
        agentId: AgentId,
        requestId: String,
    ) {
        val now = currentTick()
        episodes[agentId]?.values?.forEach { episode ->
            if (episode.open.remove(requestId) && episode.open.isEmpty()) episode.settledTick = now
        }
    }

    /** Drops every episode for an agent whose body has gone, so nothing outlives it. */
    fun forget(agentId: AgentId) {
        episodes.remove(agentId)
    }

    private fun Episode.expiredAt(now: Long): Boolean {
        val settled = settledTick ?: return false
        return open.isEmpty() && now - settled > DANGER_EPISODE_COOLDOWN_TICKS
    }
}
