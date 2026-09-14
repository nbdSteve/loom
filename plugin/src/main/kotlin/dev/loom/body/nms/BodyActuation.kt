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
import dev.loom.body.BodyEventJournal
import dev.loom.body.EventTag

/**
 * Reads [body], belonging to [agentId], on one tick, recording what happened to it.
 *
 * Cheap by design, and cheaper than it looks: a renewed activation and two reads.
 * Nothing here searches for a path or moves anything. The body walks its route from
 * the engine's own tick, so this tick only *observes* the movement, which is why a
 * move record now says what the route is doing as well as where the body got to. See
 * `Navigation.kt` for why the search cost is bounded per request and not per tick.
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
        // Recorded on that agent's own chain, and tagged as the refusal it is: the same
        // event name also records a conversion that took the body, and nothing but the
        // tag distinguishes an agent that kept its body from one that lost it.
        journal.recordConversion(
            agentId,
            "refused becoming $nearlyBecame; body unchanged",
            EventTag.CONVERSION_REFUSED,
        )
    }
    val moved = body.observeDisplacement() ?: return
    val where = position(moved.x, moved.y, moved.z)
    // Where it got to, and what its route is doing there. The second half is the part a
    // straight-line model had no way to say: a body walking node three of nine, a body
    // the engine gave up on as stuck, and a body merely falling all displace alike.
    journal.record(
        agentId,
        BodyEvent.MOVED,
        "to $where yaw ${coordinate(body.yRot.toDouble())}, ${body.describeJourney()}",
    )
}
