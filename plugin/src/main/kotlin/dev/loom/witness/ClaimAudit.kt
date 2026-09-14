/*
 * Cross-checking what a player body claimed about itself against the server's own reading.
 *
 * The witness's second job, and a separate object from [PlayerWitness] rather than a
 * method on it, for a reason that is about safety rather than about file size.
 *
 * [PlayerWitness] decides two irreversible things - whether a connection may join, and
 * that an agent has died for good. This decides nothing: it compares two accounts, and
 * records the disagreement. Keeping them apart in the *type* means a cross-check cannot
 * refuse a login or write a tombstone however it is later extended, so widening what is
 * cross-checked can never reach the permadeath rule. A method on the witness would have
 * had both sets of collaborators in scope and only a convention to keep them apart.
 *
 * The player adapter submits every bounded sense and movement report here. The audit
 * remains evidence-only: a disagreement is recorded, never promoted into a login
 * refusal or a death decision.
 */
package dev.loom.witness

import java.util.UUID

/**
 * The witness's cross-check of a managed player body's own account of itself.
 *
 * @param registry the managed set, read to resolve which agent a connection is. The same
 *   object [PlayerWitness] holds, because a claim is about one of the identities it admitted.
 * @param journal where a disagreement is recorded. Agreement is recorded nowhere.
 * @param currentTick the server tick a statement is made at.
 */
class ClaimAudit(
    private val registry: WitnessRegistry,
    private val journal: WitnessJournal,
    private val currentTick: () -> Long,
) {
    /**
     * Cross-checks what a player body claimed about itself against the server's own reading.
     *
     * The same asymmetry as the witness's first job: a Mineflayer body reads its vitals and
     * its inventory from a client, and a client reports what it was told, so the numbers a run
     * measures survival from are only worth what the server will vouch for. Answers what the
     * comparison found and journals a [Corroboration.Disputed] once; agreement is silent, as is
     * a [Corroboration.PartlyChecked] account - nothing there says the body was wrong, and the
     * caller holds the count. The comparison itself is [corroborate]'s - nothing about a
     * disagreement is decided here.
     *
     * Resolved by uuid alone, like every other statement after a login: an unregistered player
     * is not Loom's to have an opinion about, so nothing is compared and nothing is recorded
     * about a stranger's inventory. That answer is [Corroboration.NotManaged] and never
     * [Corroboration.Agreed] - a claim nothing looked at must not read back as a claim the
     * server vouched for. Main thread.
     */
    fun sawClaim(
        uuid: UUID,
        username: String,
        claim: BodyClaim,
        reading: ServerReading,
    ): Corroboration {
        val identity = registry.byUuid(uuid) ?: return Corroboration.NotManaged
        val found =
            corroborate(
                claimed = claim.vitals,
                claimedInventory = claim.inventory,
                observed = reading.vitals,
                observedInventory = reading.inventory,
            )
        if (found is Corroboration.Disputed) {
            journal.disputed(subjectOf(identity.agentId, uuid, username), found, currentTick())
        }
        return found
    }

    /**
     * Cross-checks what a player body reported about one movement request against the server.
     *
     * The other thing a body reports about itself, and every rule [sawClaim] follows applies
     * here unchanged: resolved by uuid alone, a stranger is [MovementCorroboration.NotManaged]
     * and is recorded nowhere, only a [MovementCorroboration.Disputed] is journalled, and the
     * comparison is [corroborateMovement]'s rather than this method's.
     *
     * [standing] is asked for even where the body's own arithmetic is what fails, because the
     * answer has to say whether the position was compared at all: reading a journey fault found
     * beside a silent server as a whole cross-check is the same mistake as reading an
     * uncheckable slot as an agreed one. Main thread.
     */
    fun sawMovement(
        uuid: UUID,
        username: String,
        claim: MovementClaim,
        standing: ServerStanding,
    ): MovementCorroboration {
        val identity = registry.byUuid(uuid) ?: return MovementCorroboration.NotManaged
        require(claim.agentId == identity.agentId.value) {
            "a movement claim must name the agent registered to the witnessed player"
        }
        val found = corroborateMovement(claim, standing)
        if (found is MovementCorroboration.Disputed) {
            journal.disputedMovement(subjectOf(identity.agentId, uuid, username), found, currentTick())
        }
        return found
    }
}
