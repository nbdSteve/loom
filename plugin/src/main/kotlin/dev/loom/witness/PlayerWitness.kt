/*
 * What Loom says and does about a managed player, and nothing about how it hears it.
 *
 * This is the whole of the witness's judgement. It holds the registry, the durable permadeath
 * state and the journal, and answers five questions with a verdict: may this login in, what does
 * this arrival mean, what does this departure mean, what does this death mean, and what does this
 * respawn mean. It touches no Bukkit type and no world, so every one of those answers is provable
 * at a desk - including the ones a real server reaches only by going wrong.
 *
 * Three rules about identity run through all five, and they are not the same rule on purpose.
 *
 * A login is resolved against the *durable* death state first, and that state is authoritative
 * whatever is registered. A registration lives in the memory of one process, and the process
 * driving a dead agent's account is started by something Loom does not own, so an admission
 * gated on the registry admits any tombstoned identity that is not registered at the moment it
 * reconnects - a dead agent walking in, with nothing recorded, after exactly the restart the
 * ledger was written synchronously to survive. So a connection whose uuid *or* username is
 * tombstoned is refused, and the refusal is filed under the agent id the tombstone names.
 *
 * A tombstoned identity is a managed identity by definition, so that does not widen what Loom
 * has an opinion about: a connection matching neither a registration nor a tombstone is still
 * [LoginVerdict.Unmanaged], still admitted, and still recorded nowhere at all.
 *
 * A login that is not tombstoned is then resolved by uuid *and* username, because that is the one
 * moment at which something can claim to be a managed body and not be one. A connection matching
 * half a registration is refused rather than resolved: see `claimOf`.
 *
 * Everything after the login is resolved by uuid *alone* - against the registry, and for the two
 * breach failsafes against the durable death state as well. A connected player's uuid is the
 * server's own authoritative handle on it, and taking it as the only key is what makes "an
 * unrelated player is never recorded" a property of the lookup rather than of a check somebody has
 * to remember to write. There is deliberately no fallback to the *username* here: a name that
 * matched with the wrong uuid was already refused at the door, and looking it up again after that
 * would file one player's evidence under another player's agent.
 *
 * The ledger half of that lookup is what makes `witnessRevived` reachable in the case it exists
 * for. A breach means the refusal in [PlayerWitness.admitLogin] did not hold, and after a restart
 * nothing is registered - so a failsafe gated on the registry alone said nothing at all about a
 * dead agent's account that something else had let in, in exactly the run the ledger is written
 * synchronously to survive. A uuid a tombstone names is a managed identity by the same argument
 * the login already makes, and a uuid with neither a registration nor a tombstone is still nobody's
 * business: see `breachSubject`.
 *
 * The order in [admitLogin] is load-bearing twice over. Death comes before everything, so a dead
 * identity is reported as having died rather than as an unknown or as a ledger Loom cannot read:
 * of the refusals that would all turn the same connection away, "this agent died" is the one an
 * operator needs to read. And the claim comes before the fail-closed seal, so nothing is recorded
 * about a player Loom does not manage - not even a refusal, because Loom has no business having
 * an opinion about a stranger's login, and a Loom file it cannot read must not become a
 * server-wide outage.
 *
 * A removal is a verdict rather than an act for the same reason: whether it happens by kick, on
 * this tick or the next, is the adapter's business, and keeping it out of here is what lets the
 * exactly-once death rule be tested without a server to be kicked from.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.EventTag
import dev.loom.body.WitnessedPlayer
import java.util.UUID

/** What the player is told when a dead agent's account is turned away or removed. */
private const val DEAD_NOTICE = "This agent has died. Loom is permadeath: it does not come back."

/** What the player is told when Loom cannot vouch for its own death records. */
private const val UNSAFE_NOTICE =
    "Loom cannot read its own death records, so it is refusing every agent login " +
        "until an operator has looked."

/** What the player is told when it claimed half of a managed identity. */
private const val MISMATCH_NOTICE =
    "That username belongs to a Loom agent registered to a different account."

/** How a death is described in the log when a respawn is the only evidence Loom has of it. */
private const val UNWITNESSED_DEATH =
    "died: Loom did not witness the death, only the respawn that followed it"

/** Whether a connection may join, and what to say if not. */
sealed interface LoginVerdict {
    /**
     * Nobody registered this connection. Loom has no opinion and has recorded nothing.
     *
     * A distinct answer from [Admitted] even though the adapter does the same thing with both,
     * because the two are what the acceptance criteria are stated in terms of: an unrelated
     * player must be neither recorded nor denied, and a bare "not denied" cannot show that.
     */
    data object Unmanaged : LoginVerdict

    /** A managed identity that Loom has no reason to refuse. Nothing was recorded. */
    data class Admitted(
        val identity: ManagedIdentity,
    ) : LoginVerdict

    /**
     * The login must be refused. Recorded as a `witnessDenied` before this was returned.
     *
     * @property agentId the agent the refusal was filed under, which is all a refusal is ever about
     *   an agent: what *connected* is in the record rather than here, and a tombstone that will not
     *   fully read back leaves no identity to name at all - only the agent whose death it states.
     * @property tag the `EventTag` `LOGIN_` value, so a caller can count refusals by reason.
     * @property notice what to tell the connection. Bounded, and quotes no file content.
     */
    data class Denied(
        val agentId: AgentId,
        val tag: String,
        val notice: String,
    ) : LoginVerdict
}

/**
 * As much of the server's account of a respawn as the witness needs.
 *
 * The server distinguishes more reasons than this, and deliberately so: naming its enum here would
 * put a Bukkit type in the engine-free judgement, and the witness only ever needs to know whether
 * the respawn was reported as following a death. Translating the server's reasons into these two is
 * the adapter's job.
 */
enum class RespawnCause {
    /** The server says the player died and this is the respawn that followed. */
    AFTER_DEATH,

    /**
     * The server respawned a player for a reason that is not a death.
     *
     * A live player leaving the End is the case that matters: vanilla sends it to its respawn
     * position rather than through an ordinary portal, so the respawn event fires with nobody having
     * died. Read as a death it cost a living agent its life, which is why this is a separate answer
     * rather than a default.
     */
    WITHOUT_DEATH,
}

/**
 * What the adapter must do to a player the witness has just made a statement about.
 *
 * One type for arrival, death and respawn, because the only act the witness ever asks for is a
 * removal, and how it is carried out - and on which tick - is entirely the adapter's business.
 */
sealed interface WitnessAction {
    /** Nothing to do. Either the player is not Loom's, or its presence is legitimate. */
    data object LeaveAlone : WitnessAction

    /**
     * Take the player out of the world, without letting it respawn.
     *
     * @property subject who is going out: the agent it is charged to, and the connection the server
     *   actually saw. Deliberately not a [ManagedIdentity] - the breach failsafes reach a dead
     *   agent's account with nothing registered, where all Loom holds is a tombstone that may name
     *   only an agent id, and inventing the rest would put a player in the record that never was.
     * @property notice what to tell it on the way out. Bounded.
     */
    data class Remove(
        val subject: WitnessSubject,
        val notice: String,
    ) : WitnessAction
}

/**
 * The server-authoritative witness for Loom's player bodies.
 *
 * @param registry the managed set. Exposed so the one caller that registers a body has a single
 *   object to reach for, and so this class has no opinion about when that happens.
 * @param permadeath the durable death state. Already replayed by the time it gets here, because
 *   its constructor is what replays it.
 * @param journal where statements are recorded.
 * @param currentTick the server tick. Read once per statement, so a death's tombstone and its
 *   `died` record agree. Read from a Netty thread during a login refusal, where it is
 *   unsynchronised and may lag by a tick - accepted, because nothing compares a refusal's tick.
 * @param alert raises a message where an operator will see it, for the two conditions that mean
 *   something is wrong rather than something happened: a sealed ledger and a breached permadeath.
 */
class PlayerWitness(
    val registry: WitnessRegistry,
    private val permadeath: DurablePermadeath,
    private val journal: WitnessJournal,
    private val currentTick: () -> Long,
    private val alert: (String) -> Unit,
) {
    init {
        // Said once at construction rather than only at the first refused login: a run whose
        // ledger is unreadable should say so before an operator wonders why nothing can connect.
        permadeath.sealReason?.let { alert("witness sealed, no agent may log in: $it") }
    }

    /**
     * Whether the connection identified by [uuid] and [username] may join.
     *
     * Safe to call from any thread, and called from a Netty thread in practice.
     */
    fun admitLogin(
        uuid: UUID,
        username: String,
    ): LoginVerdict {
        // The durable state first, and with no registration consulted: a tombstone is Loom's own
        // durable statement that this account is a dead agent's, and it has to outrank whatever
        // this process happens to have in memory.
        permadeath.deadIdentityFor(uuid, username)?.let { dead ->
            return denyDead(dead, uuid, username)
        }
        return when (val claim = claimOf(registry, uuid, username)) {
            IdentityClaim.Unmanaged -> LoginVerdict.Unmanaged
            is IdentityClaim.Mismatched ->
                deny(
                    // Filed under the agent whose identity was claimed, and naming the connection
                    // that claimed it, for the reason `denyDead` gives: `witness` is the join to a
                    // server log line and to a `playerdata` file, so it is what presented itself
                    // rather than the registration - which the detail states beside it, since which
                    // half of the registration matched is the whole of what this refusal is about.
                    WitnessSubject(claim.identity.agentId, WitnessedPlayer(uuid.toString(), username)),
                    EventTag.LOGIN_IDENTITY_MISMATCH,
                    MISMATCH_NOTICE,
                    "login as '$username' ($uuid) claimed ${claim.identity.username} " +
                        "(${claim.identity.uuid}) and was refused",
                )
            is IdentityClaim.Matched -> admitKnown(claim.identity, uuid, username)
        }
    }

    /**
     * Whether a connection Loom has resolved to [identity] may join.
     *
     * Separate from [admitLogin] because the two questions are different: that one asks *who* is
     * connecting, and this one asks whether *this* agent may.
     *
     * Death is asked again here, and it is not the check [admitLogin] already made: that one is
     * keyed on the identity a tombstone names, and this one on the agent behind it. An agent
     * re-registered under a different account after it died would pass the first and must not pass
     * this. The seal comes last for the reason the file header gives - of the refusals that would
     * all turn this connection away, "this agent died" is the informative one, and "Loom cannot
     * tell" would hide it.
     *
     * Both refusals name what [uuid] and [username] presented, like every other witness record, even
     * though `claimOf` matched them against this registration: the match is case-insensitive, so the
     * two readings can still differ by a letter's case, and the field is the join to a server log
     * line rather than a restatement of Loom's own answer.
     */
    private fun admitKnown(
        identity: ManagedIdentity,
        uuid: UUID,
        username: String,
    ): LoginVerdict {
        val connection = subjectOf(identity.agentId, uuid, username)
        return if (permadeath.isDead(identity.agentId)) {
            deny(connection, EventTag.LOGIN_DEAD_IDENTITY, DEAD_NOTICE, DEAD_NOTICE)
        } else {
            // The seal is read here rather than beside the death check, because the two reads take
            // the lock separately: a seal engaged in between by another agent's failed tombstone
            // would otherwise be missed by a login that had already found it null and admitted.
            permadeath.sealReasonWithin(MAX_WITNESS_DETAIL_CHARS)?.let { seal ->
                // The detail is the seal and nothing else. [UNSAFE_NOTICE] is what the *client* is
                // told, and repeating it here spent 114 of [MAX_WITNESS_DETAIL_CHARS] on a sentence
                // the record's own tag already carries - which cut the faults the record exists to
                // report, so a ledger corrupt two ways named neither of them in its own evidence.
                // The bound is stated here because it is this reader's, not the seal's: the operator
                // alert has no such budget and takes the whole of what the seal found.
                deny(connection, EventTag.LOGIN_LEDGER_UNSAFE, UNSAFE_NOTICE, seal)
            } ?: LoginVerdict.Admitted(identity)
        }
    }

    /**
     * Records that the player behind [uuid] and [username] is in [world], and says whether it
     * should be.
     *
     * A dead agent reaching this point means the refusal in [admitLogin] did not hold, which is
     * the permadeath invariant broken rather than an event, so it is recorded as a revival and
     * the player is removed. That failsafe is answered from the durable death state as well as
     * from the registry, because after a restart nothing is registered; [username] is what the
     * server saw, which is what such a revival has to name. Main thread.
     */
    fun sawJoin(
        uuid: UUID,
        username: String,
        world: String,
    ): WitnessAction {
        val tick = currentTick()
        val identity = registry.byUuid(uuid)
        if (identity != null && !permadeath.isDead(identity.agentId)) {
            journal.joined(subjectOf(identity.agentId, uuid, username), world, tick)
            return WitnessAction.LeaveAlone
        }
        return breachSubject(registry, permadeath, uuid, username)?.let { subject ->
            revive(
                subject,
                EventTag.REVIVED_AFTER_LOGIN,
                "${subject.player.username} joined in $world after a terminal death; " +
                    "the login refusal did not stop it",
                tick,
            )
        } ?: WitnessAction.LeaveAlone
    }

    /**
     * Records that the player behind [uuid] and [username] has left, when that is news.
     *
     * An ordinary disconnect is continuity: the agent is alive, its body's process can reconnect,
     * and no tombstone is written. A disconnect *after* a recorded death is not news at all - it
     * is the removal the death asked for, which Bukkit reports as a quit like any other - so
     * nothing is recorded for it. That is what keeps a terminal death the last thing in an
     * agent's chain apart from refused logins. Main thread.
     */
    fun sawQuit(
        uuid: UUID,
        username: String,
    ) {
        val identity = registry.byUuid(uuid) ?: return
        if (permadeath.isDead(identity.agentId)) {
            return
        }
        journal.quit(subjectOf(identity.agentId, uuid, username), currentTick())
    }

    /**
     * Records the death of the player behind [uuid] and [username], once, and asks for its removal.
     *
     * [cause] is the server's own account of what killed it, bounded on the way into the record.
     * Idempotent: a second death event for one identity records nothing further and still asks
     * for the removal, because a player that is somehow still in the world must not stay in it.
     * Main thread.
     */
    fun sawDeath(
        uuid: UUID,
        username: String,
        cause: String,
    ): WitnessAction {
        val identity = registry.byUuid(uuid) ?: return WitnessAction.LeaveAlone
        val connection = subjectOf(identity.agentId, uuid, username)
        terminate(identity, connection, cause, currentTick())
        return WitnessAction.Remove(connection, DEAD_NOTICE)
    }

    /**
     * Records that the player behind [uuid] and [username] respawned, when that is a breach rather
     * than an event.
     *
     * `PlayerRespawnEvent` cannot be cancelled, so this is a failsafe rather than a gate: whenever
     * it is a breach, the world already holds a live player for a dead agent by the time anything
     * here runs. [cause] is what decides which it is, and both answers are load-bearing.
     *
     * A respawn the server reports as following a death is always a breach for an agent a
     * registration names, so it is always witnessed. It records the death first, because such a
     * respawn is itself a report that the player died and the ordinary death path may be what
     * failed - and [terminate] is idempotent, so an already-recorded death stays exactly one.
     *
     * A respawn with no death behind it is a breach only for an agent that is *already* dead.
     * Something else on the server put a live player back in the world for an agent Loom has
     * tombstoned, and the run has to say so - no second death is recorded, because the death is
     * already in the log and there is exactly one per agent. For a live agent the same event is
     * ordinary continuity and nothing at all is recorded: a body walking home from the End arrives
     * here, and treating that as a death tombstoned a living agent and kicked it for good.
     *
     * A dead agent's account with nothing registered is a breach on either cause, answered from the
     * ledger by `breachSubject`, and writes no death of its own: the tombstone is already the death.
     *
     * Main thread.
     */
    fun sawRespawn(
        uuid: UUID,
        username: String,
        cause: RespawnCause,
    ): WitnessAction {
        val tick = currentTick()
        val identity = registry.byUuid(uuid)
        if (cause == RespawnCause.AFTER_DEATH && identity != null) {
            terminate(identity, subjectOf(identity.agentId, uuid, username), UNWITNESSED_DEATH, tick)
        }
        val subject =
            breachSubject(registry, permadeath, uuid, username) ?: return WitnessAction.LeaveAlone
        return revive(subject, EventTag.REVIVED_AFTER_RESPAWN, respawnBreach(cause, subject), tick)
    }

    /**
     * Records at most one death per agent, whichever path reports it.
     *
     * [identity] and [connection] are deliberately both taken, and they are not interchangeable. The
     * *tombstone* is written from the registration, because it is an authorisation input matched
     * against every later login and must name the identity Loom itself registered; the *record* is
     * written from [connection], because it is evidence and must name what the server actually saw.
     */
    private fun terminate(
        identity: ManagedIdentity,
        connection: WitnessSubject,
        cause: String,
        tick: Long,
    ) {
        when (val recording = permadeath.recordDeath(identity, tick)) {
            DeathRecording.Terminal -> journal.died(connection, cause, tick)
            // The one death this agent gets is already in the log. Writing a second would make
            // "how many agents died" and "how many death records are there" different numbers.
            DeathRecording.AlreadyRecorded -> Unit
            is DeathRecording.Unpersisted -> {
                // The agent is dead in this process and the log must say so. What is lost is
                // that it stays dead across a restart, which is why the ledger is now sealed.
                journal.died(connection, cause, tick)
                alert(
                    "${identity.agentId} died and its tombstone did not persist, so no agent " +
                        "may log in until this is fixed: ${recording.reason}",
                )
            }
        }
    }

    /**
     * Records a breach of permadeath and asks for the player to be taken back out.
     *
     * Takes a [WitnessSubject] because a breach is reachable with nothing registered - which is the
     * whole reason the ledger is durable - so what the world is holding may be known only as an
     * agent id and the connection that presented itself.
     */
    private fun revive(
        subject: WitnessSubject,
        tag: String,
        why: String,
        tick: Long,
    ): WitnessAction {
        journal.revived(subject, tag, why, tick)
        alert("permadeath was not enforced: $why")
        return WitnessAction.Remove(subject, DEAD_NOTICE)
    }

    /**
     * Refuses a connection the ledger says is a dead agent's, and records only what is true of it.
     *
     * A tombstone is matched on either half, so what connected need not be what the tombstone
     * names, and the record is filed under the tombstone's agent - which leaves the detail as the
     * only place the connection itself can be stated. Read without it, a refusal of somebody who
     * merely took a dead agent's username would read as that agent's own account trying to get back
     * in.
     *
     * The record's `witness` fields are the *connection's* on both answers, so that field means one
     * thing on every refusal: what actually connected. Filling it from the tombstone instead named a
     * player that never presented itself - a username nobody offered, or a dead agent's uuid for a
     * connection from another account entirely - which is the one thing those fields exist not to
     * do, since they are the join to a server log line and to a `playerdata` file.
     *
     * What the ledger could read decides the *detail*, not the subject. A tombstone that read back
     * whole is a real identity Loom itself wrote, so the detail names it beside the connection,
     * which is what shows an operator that only one half of it matched. One that did not read back
     * whole is only an agent id, and there is deliberately no third option of filling the gap in: a
     * reconstructed uuid would be a value no file ever held, put into the record as though one had.
     */
    private fun denyDead(
        dead: DeadMatch,
        uuid: UUID,
        username: String,
    ): LoginVerdict {
        val presented = "login as '$username' ($uuid) is a dead agent's account"
        val detail =
            when (dead) {
                is DeadMatch.Whole ->
                    "$presented (${dead.identity.username}, ${dead.identity.uuid}) and was refused"
                is DeadMatch.AgentOnly ->
                    "$presented and was refused; its tombstone named no identity Loom could read"
            }
        return deny(
            WitnessSubject(dead.agentId, WitnessedPlayer(uuid.toString(), username)),
            EventTag.LOGIN_DEAD_IDENTITY,
            DEAD_NOTICE,
            detail,
        )
    }

    /**
     * Records a refusal and reports it.
     *
     * Takes a [WitnessSubject] rather than an identity because that is what a refusal is about: the
     * agent it is filed under and the connection it turned away, which for a dead agent's account
     * need not be two halves of the same thing.
     */
    private fun deny(
        subject: WitnessSubject,
        tag: String,
        notice: String,
        detail: String,
    ): LoginVerdict {
        journal.denied(subject, tag, detail, currentTick())
        return LoginVerdict.Denied(subject.agentId, tag, notice)
    }
}

/**
 * Who a breach involving the connected player [uuid] and [username] is about, or null when there is
 * no breach: the agent is alive, or nothing Loom holds names this connection at all.
 *
 * A free function taking its two sources explicitly, like `claimOf` beside it, because this is the
 * identity rule for everything *after* a login and belongs with the other one rather than inside the
 * witness that applies it.
 *
 * [registry] is asked first, and answers with the agent its registration names: that is the ordinary
 * case and the one where Loom knows exactly whose body this is. Failing that, [permadeath] is asked
 * for a tombstone on the uuid alone, and the agent is the one that tombstone names.
 *
 * Either way the subject carries [uuid] and [username] rather than anything stored, because those
 * two fields are what a record is joined to a server log line by: a registration's username is not
 * what the connection presented, and after a login the lookup is by uuid alone, so an account renamed
 * against a stale registration would otherwise be recorded under a name nobody offered.
 *
 * That fallback is what makes a breach visible in the run the durable ledger exists for. Reaching a
 * failsafe with a dead agent means the login refusal did not hold, and after a restart nothing is
 * registered - so a registry-only lookup left that breach unrecorded, unalerted and unremoved.
 *
 * It widens nothing: a uuid a tombstone names is a managed identity by the same argument
 * `PlayerWitness.admitLogin` already makes for refusing it, and a uuid with neither a registration
 * nor a tombstone answers null, so nothing whatever is recorded about a stranger.
 */
private fun breachSubject(
    registry: WitnessRegistry,
    permadeath: DurablePermadeath,
    uuid: UUID,
    username: String,
): WitnessSubject? {
    registry.byUuid(uuid)?.let { identity ->
        return subjectOf(identity.agentId, uuid, username)
            .takeIf { permadeath.isDead(identity.agentId) }
    }
    return permadeath.deadAgentFor(uuid)?.let { agentId -> subjectOf(agentId, uuid, username) }
}

/** How a revival is described for a respawn of [cause] that turned out to be a breach. */
private fun respawnBreach(
    cause: RespawnCause,
    subject: WitnessSubject,
): String =
    when (cause) {
        RespawnCause.AFTER_DEATH ->
            "${subject.player.username} respawned after a terminal death; " +
                "the removal on death did not take effect"
        RespawnCause.WITHOUT_DEATH ->
            "${subject.player.username} was respawned without dying, after a terminal death; " +
                "something on the server put a dead agent back in the world"
    }
