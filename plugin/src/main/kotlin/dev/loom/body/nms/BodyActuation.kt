/*
 * What one live body does on one server tick, and what that puts in the record.
 *
 * Split out from the port so the tick path reads as the two separate questions it
 * actually asks: who is still Loom's to drive - which is the survival drive, the
 * departure classification, and permadeath - and then, for whoever is left, this. Only
 * the second question touches an entity, and it needs nothing but the body and the log.
 *
 * Actuation only. Cognition is event-driven and is never reached from a tick; the
 * contract has no per-tick `Trigger`, and the boundary test forbids this package from
 * depending on `dev.loom.mind` at all.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent

/**
 * Advances [body], belonging to [agentId], by one tick, recording what it did.
 *
 * Cheap by design: a renewed activation and at most one walk step, never a
 * pathfinding search. See `WalkGait` for why that bound is load-bearing.
 */
internal fun actuate(
    agentId: AgentId,
    body: LoomBodyEntity,
    journal: BodyEventJournal,
) {
    // Without this a body goes inactive after ten seconds and stops reaching the vanilla
    // hook that carries out portal travel. See readyForTheEngineTick.
    val nearlyBecame = body.readyForTheEngineTick()
    if (nearlyBecame != null) {
        // The agent is alive and still in the body Loom made for it, which is the whole
        // point: being struck by lightning happens to an agent rather than ending one.
        // Recorded on that agent's own chain.
        journal.record(agentId, BodyEvent.CONVERTED, "refused becoming $nearlyBecame; body unchanged")
    }
    val step = body.advanceMovement() ?: return
    val where = position(body.x, body.y, body.z)
    journal.record(agentId, BodyEvent.MOVED, "to $where yaw ${coordinate(step.yaw.toDouble())}")
}
