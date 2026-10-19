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
 * mind. Until that decision has settled, and for [DANGER_EPISODE_COOLDOWN_TICKS] after a flight
 * settles, another threat or damage perception about the same threat for the same agent is
 * coalesced into it rather than sent; a decision lost or answered by anything but a flight ends
 * the episode at once. A different threat is new
 * information and is sent, unless it is further off, and outside pivotal range, than a threat
 * whose decision is still open, judged where each now stands: in r20, eta's flight from a zombie
 * 6.8 blocks off was preempted by one from a skeleton 23.1 off.
 * Damage is nearest of all. Damage, or a sighting back within pivotal range, after the episode's
 * answer has settled is always sent: the answer did not keep the threat off, which is news. In
 * the v29 rehearsal eta fled a spider safely, was bitten 58 ticks later inside the cooldown, and
 * died with that bite never asked about. Nothing here decides how to act; it decides only whether
 * a second question is worth a model call.
 *
 * Engine-free and main-thread only, like the rest of the cognition seam.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.PIVOTAL_THREAT_DISTANCE_BLOCKS
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
    private class Episode(
        /**
         * How far off the threat is, by its latest sighting, coalesced or not; `null` for damage,
         * which says nothing about where the attacker stands.
         */
        var distance: Double?,
    ) {
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
        subject: String? = null,
    ): Boolean {
        val key = keyOf(perception.trigger, subject) ?: return true
        val now = currentTick()
        val byThreat = episodes.getOrPut(agentId) { mutableMapOf() }
        byThreat.entries.removeIf { (_, episode) -> episode.expiredAt(now) }
        // Whatever survived the sweep is open or still cooling down.
        val distance = (perception.trigger as? Trigger.ThreatSighted)?.distance
        // The episode follows its threat: a repeat about it moves it to where it now stands.
        byThreat[key]?.let { episode -> if (distance != null) episode.distance = distance }
        val answered = byThreat[key]?.open?.isEmpty() == true && closedAgain(perception.trigger)
        val admitted = (key !in byThreat || answered) && !nearerOpen(byThreat, key, distance)
        if (admitted) {
            byThreat[key] = Episode(distance).also { it.open += perception.requestId }
        } else {
            // Damage taken while an answer runs is told once that answer settles (r24: 43 of 68
            // damage perceptions were coalesced, gamma's at health 2.7 and 0.7, and none was told).
            (perception.trigger as? Trigger.Damaged)?.let { damage ->
                // Held without its ref, which named a creature in the sense that minted it alone.
                pending.merge(agentId, damage.copy(attackerRef = null)) { earlier, later ->
                    Trigger.Damaged(earlier.amount + later.amount, later.source)
                }
                // The latest attacker is the one told of, so the flight can name it.
                if (subject != null) pendingAttacker[agentId] = subject else pendingAttacker.remove(agentId)
            }
        }
        return admitted
    }

    /**
     * The episode key of a danger [trigger]: the current creature when the server names it, so a
     * second zombie is news of its own (r24: damage from one zombie coalesced into another's
     * episode 0.4 blocks off), else its kind; `null` for a trigger that is no danger.
     */
    private fun keyOf(
        trigger: Trigger,
        subject: String?,
    ): String? = dangerKeyOf(trigger)?.let { kind -> subject?.let { "entity:$it" } ?: kind }

    /**
     * Whether a further threat at [distance] waits behind a nearer one being answered, judged
     * where each stands now; a threat inside pivotal range never waits, and damage holds back no
     * sighting (r23: 40 threats waited behind episodes opened up to 244 ticks earlier).
     */
    private fun nearerOpen(
        byThreat: Map<String, Episode>,
        key: String,
        distance: Double?,
    ): Boolean =
        distance != null && distance > PIVOTAL_THREAT_DISTANCE_BLOCKS &&
            byThreat.any { (other, episode) ->
                other != key && episode.open.isNotEmpty() && (episode.distance ?: Double.MAX_VALUE) < distance
            }

    /** The damage each agent took while one of its danger answers ran, owed once one settles. */
    private val pending = mutableMapOf<AgentId, Trigger.Damaged>()

    /** The server uuid of the creature that dealt each agent's latest held damage, when one did. */
    private val pendingAttacker = mutableMapOf<AgentId, String>()

    /**
     * Marks the decision for [requestId] settled. A flight starts its episode's cooldown; any
     * other answer ends the episode at once, so the next sighting of that threat is asked about
     * again (r20 kappa answered a skeleton 2.4 blocks off with a one-block walk, its next sighting
     * was coalesced into the cooldown, and it died beside that skeleton).
     */
    fun settled(
        agentId: AgentId,
        requestId: String,
        fled: Boolean = true,
    ): Trigger.Damaged? = settledDamage(agentId, requestId, fled)?.trigger

    /** As [settled], with the server uuid of the creature that dealt the damage it owes, if any. */
    fun settledDamage(
        agentId: AgentId,
        requestId: String,
        fled: Boolean = true,
    ): HeldDamage? {
        val now = currentTick()
        episodes[agentId]?.entries?.removeIf { (_, episode) ->
            val ended = episode.open.remove(requestId) && episode.open.isEmpty()
            if (ended) episode.settledTick = now
            ended && !fled
        }
        // Damage held behind an answer is owed once no answer of the agent's is still open.
        val open = episodes[agentId]?.values?.any { it.open.isNotEmpty() } == true
        if (open) return null
        val attacker = pendingAttacker.remove(agentId)
        return pending.remove(agentId)?.let { HeldDamage(it, attacker) }
    }

    /**
     * Withdraws the episode [requestId] alone opened, for a perception admitted here but then
     * not sent at all: a threat the mind was never asked about must not cool down as if it
     * had been answered.
     */
    fun withdraw(
        agentId: AgentId,
        requestId: String,
    ) {
        episodes[agentId]?.entries?.removeIf { (_, episode) ->
            episode.settledTick == null && episode.open == setOf(requestId)
        }
    }

    /** Drops every episode for an agent whose body has gone, so nothing outlives it. */
    fun forget(agentId: AgentId) {
        pending.remove(agentId)
        pendingAttacker.remove(agentId)
        episodes.remove(agentId)
    }

    /**
     * Whether a repeat after the episode's answer settled is news: damage always is, and so is a
     * sighting back inside [PIVOTAL_THREAT_DISTANCE_BLOCKS], because the flight did not keep the
     * threat off (r22: the last warnings of alpha at health 1.2, beta, epsilon and zeta were
     * coalesced into the cooldown of a flight the threat had already closed).
     */
    private fun closedAgain(trigger: Trigger): Boolean =
        trigger is Trigger.Damaged ||
            (trigger is Trigger.ThreatSighted && trigger.distance <= PIVOTAL_THREAT_DISTANCE_BLOCKS)

    private fun Episode.expiredAt(now: Long): Boolean {
        val settled = settledTick ?: return false
        return open.isEmpty() && now - settled > DANGER_EPISODE_COOLDOWN_TICKS
    }
}

/** Damage held behind a danger answer, and the server uuid of the creature that dealt it, if any. */
data class HeldDamage(
    val trigger: Trigger.Damaged,
    val attacker: String?,
)
