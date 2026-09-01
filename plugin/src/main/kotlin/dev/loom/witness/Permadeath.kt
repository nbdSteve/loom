/*
 * Permadeath for a body that outlives the server process.
 *
 * `Mortality` already holds Loom's rule that a death is terminal, and it is reused here rather
 * than restated: it is the type that offers no way to un-record a death, and having two
 * answers to "has this agent died" is how one of them ends up being the wrong one. What it
 * cannot do alone is survive a restart, which for a player body is the case that matters, so
 * this wraps it with the [DeathLedger] and makes three further guarantees.
 *
 * The set is replayed before it is readable. Construction reads the ledger, so a
 * [DurablePermadeath] cannot exist in a state where it has not yet caught up with the deaths of
 * previous runs. That ordering is the whole of the requirement that tombstones are replayed
 * before login admission begins, and expressing it as a constructor rather than as an
 * `open()` call to be remembered is what makes it impossible to get wrong.
 *
 * The replay keeps the *whole* identity a tombstone names, not only its agent id, and that is
 * what makes the ledger authoritative for admission rather than merely informative. An agent id
 * can only be compared against a registration, and a registration lives in the memory of one
 * process: the body driving a dead agent's account can reconnect before - or without - anything
 * re-registering it, at which point an agent-id-keyed answer has nothing to compare and the dead
 * walk in. So [deadIdentityFor] answers from the uuid and the username the tombstone itself
 * carries, with no registration present.
 *
 * A tombstone whose fields do not all read back is indexed by whichever of them does, because the
 * seal cannot stand in for it. The seal is consulted only for a connection a *registration* has
 * already matched, so after the restart it exists to survive it refuses nobody - which left a
 * hand-edited uuid enough to re-admit an agent whose death the file still named by username. The
 * two shapes of answer are what [DeadMatch] is for: a tombstone that read back whole hands back the
 * identity it named, and one that did not hands back the agent id alone, which is the one field
 * neither a refusal nor a death record can do without.
 *
 * A tombstone is durable before the death is reported as final. [recordDeath] does not return
 * until the write has landed or failed, and it says which.
 *
 * And it fails closed. Anything that leaves Loom unable to vouch for the durable state - an
 * unreadable ledger at startup, a tombstone that would not write - seals this object, and
 * [sealReason] then stays non-null for the rest of the process. It is one-way for the same
 * reason a death is: a seal that could be cleared is a seal something would clear.
 *
 * A seal states *every* fault it found rather than the first, because they are independent and each
 * is a different thing for an operator to go and look at: a tombstone that only partly read back and
 * a final line a crash tore off can be in one file at once, and naming one of them hid the other in
 * both the alert and the record that carry it.
 *
 * Sealing and enforcing are not alternatives, and reading them as alternatives is a mistake this
 * file has already made once. A ledger with a torn final line still *states* every death before
 * it, and the seal only refuses the identities a registration names - so discarding those deaths
 * left a dead agent's unregistered account admissible, which is the one outcome the ledger being
 * synchronous exists to rule out. Every tombstone a replay could read is therefore enforced,
 * whichever answer came back with it, and the seal covers the remainder on top.
 *
 * Every method takes the lock. `Mortality` holds a plain `HashSet`, and its two callers here
 * are on different threads by design - the login refusal runs during async pre-login on a
 * Netty thread, and a death is witnessed on the server main thread. Concurrent reads of a set
 * being written to is a data race whose worst outcome is precisely the one this file exists to
 * prevent, so the lock is not an optimisation to be removed later.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.Mortality
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What [DurablePermadeath.recordDeath] did about a death. */
sealed interface DeathRecording {
    /**
     * The first death of this agent, and the tombstone is durable.
     *
     * The only answer that means a `died` record should be written: exactly one authoritative
     * death per agent is an acceptance criterion, and this is where "exactly one" is decided.
     */
    data object Terminal : DeathRecording

    /**
     * This agent was already recorded dead. Nothing was written.
     *
     * Not an error. It is what a second death event for one identity looks like, which the
     * failsafe paths can reach - a respawn that slipped past the removal is also a report that
     * the agent died - and folding it into [Terminal] would put a second `died` record in the
     * log for one life.
     */
    data object AlreadyRecorded : DeathRecording

    /**
     * The agent is dead, and the tombstone did not reach the disk.
     *
     * The death holds for this process and would not survive a restart, so it seals the object:
     * from here every managed login is refused, because the alternative is a restart that
     * silently admits a dead agent. [reason] is bounded and quotes no file content.
     */
    data class Unpersisted(
        val reason: String,
    ) : DeathRecording
}

/**
 * What the durable state knows about a connection it recognises as a dead agent's.
 *
 * Two shapes rather than one, because a tombstone's fields fail independently and a refusal must
 * not claim more than the file actually said. Both carry [agentId], since a refusal is filed under
 * an agent and a tombstone that cannot name one cannot be enforced at all.
 */
sealed interface DeadMatch {
    /** The agent whose death the ledger states. */
    val agentId: AgentId

    /** A tombstone that read back whole, so the identity it named is known exactly. */
    data class Whole(
        val identity: ManagedIdentity,
    ) : DeadMatch {
        override val agentId: AgentId get() = identity.agentId
    }

    /**
     * A tombstone that named this agent and something Loom could not read back.
     *
     * There is deliberately no placeholder for the part that did not parse: a refusal recorded
     * from this may state only the agent it is filed under and what actually connected, because
     * inventing the rest would put a uuid in the research record that no file ever held.
     */
    data class AgentOnly(
        override val agentId: AgentId,
    ) : DeadMatch
}

/**
 * The durable, terminal record of which agents have died.
 *
 * @param ledger the durable state. Read once during construction.
 */
class DurablePermadeath(
    private val ledger: DeathLedger,
) {
    private val lock = ReentrantLock()
    private val mortality = Mortality()
    private val deadByUuid = mutableMapOf<UUID, DeadMatch>()

    /**
     * Dead usernames, keyed the way `usernameKeyOf` keys every username in this package.
     *
     * So this index and `WitnessRegistry.byUsername` agree by construction rather than by
     * coincidence: both normalise through the same function, which lowercases with `Locale.ROOT`
     * because the default locale lowercases a dotted capital I to a different code point and would
     * make a lookup depend on the operator's machine.
     */
    private val deadByUsername = mutableMapOf<String, DeadMatch>()

    /**
     * Every distinct reason Loom cannot vouch for the durable state, in the order they were found.
     *
     * Several rather than one string because a ledger can be corrupt in more than one way at once -
     * a tombstone that only partly read back *and* a final line a crash tore off - and each is a
     * different thing for an operator to go and look at. Keeping only the first named whichever was
     * found earliest and left the other invisible in both places the seal is ever read.
     *
     * A set rather than a list because a fault is a thing to go and look at rather than an
     * occurrence to count, and one that recurs is the same thing to look at: a full disk gives every
     * failed tombstone write the same reason, so four agents dying on it spent three of
     * [MAX_SEAL_REASONS]'s slots - and most of a record's bounded detail - restating one fault, which
     * is the crowding out the seal states every fault to avoid.
     */
    private val seals = linkedSetOf<String>()

    init {
        // Under the same lock as everything else, so the replayed state is published to the
        // threads that will read it by the same rule the rest of this class relies on.
        lock.withLock {
            val replay = ledger.replay()
            // Not a throw: a plugin that failed to enable would leave the server with no witness
            // at all, which is strictly worse than a witness that admits nobody and says loudly
            // why. Failing closed only works if the thing that fails is still there.
            //
            // Stated first because the ledger found it first, and because it is the gravest fault
            // of the set: a tombstone that only partly read back is still indexed by whichever half
            // did and still refuses that connection, while a line that would not read at all is a
            // death Loom cannot name. Added after the tombstones it was the one an elision dropped.
            if (replay is LedgerReplay.Unsafe) {
                seals += replay.reason
            }
            // Every death the ledger could be read for, whichever answer it came back with. A
            // partial read is not an empty one, and the tombstones before a torn line are the
            // deaths this object exists to keep enforcing.
            replay.tombstones.forEachIndexed(::remember)
        }
    }

    /**
     * Why Loom cannot vouch for the durable death state, or null when it can.
     *
     * Non-null means fail closed: no managed identity may be admitted, whatever
     * [isDead] says about it, because what [isDead] does not know is the problem.
     *
     * Every reason found, joined, and bounded only by [MAX_SEAL_REASONS] - because this is what the
     * *server log* reads, and a log line has no width to run out of. A reader with a narrower budget
     * asks [sealReasonWithin] for its own, rather than every reader inheriting the tightest one.
     */
    val sealReason: String? get() = lock.withLock { sealReasonOf(seals) }

    /**
     * The same answer as [sealReason], shortened until it fits [chars].
     *
     * For the one reader that has a character budget - the `loginLedgerUnsafe` record's detail, capped
     * at `MAX_WITNESS_DETAIL_CHARS`. Sharing one string with the alert made that cap everybody's, so
     * the server log named fewer faults than it could and the ones it dropped were then recoverable
     * from nowhere, since the record had dropped exactly the same ones.
     */
    fun sealReasonWithin(chars: Int): String? = lock.withLock { sealReasonOf(seals, chars) }

    /** Whether [agentId] names an agent that has died, in this run or in any previous one. */
    fun isDead(agentId: AgentId): Boolean = lock.withLock { mortality.isDead(agentId) }

    /**
     * What the ledger knows about a connection presenting [uuid] and [username], or null when
     * neither half of it is tombstoned.
     *
     * Answered from the durable state alone, so it holds with nothing registered - which is the
     * whole point of it, since the process driving a dead agent's account is started by something
     * Loom does not own and can reconnect before a registration exists. Either half is enough: a
     * tombstoned uuid connecting under a new username, and a tombstoned username connecting from a
     * new uuid, are both a dead agent's account trying to get back in.
     *
     * The uuid is asked first because it is the stronger claim, so a tombstone that read back whole
     * answers with the identity it named even when the username would have matched a partial one.
     */
    fun deadIdentityFor(
        uuid: UUID,
        username: String,
    ): DeadMatch? = lock.withLock { deadByUuid[uuid] ?: deadByUsername[usernameKeyOf(username)] }

    /**
     * The agent a tombstone names for [uuid], or null when no tombstone names that uuid.
     *
     * Keyed on the uuid alone because that is all the paths after a login have: the server's own
     * authoritative handle on a connected player, with no username fallback for the reason
     * `PlayerWitness`'s header gives. Answers an agent id rather than a [DeadMatch] because its
     * callers are recording what *connected*, so the only thing they need from the ledger is the
     * agent to file it under.
     */
    fun deadAgentFor(uuid: UUID): AgentId? = lock.withLock { deadByUuid[uuid]?.agentId }

    /**
     * Records that [identity] has died, durably.
     *
     * The in-memory claim is taken first and under the same lock as the write, so two death
     * events for one identity in flight at once produce one tombstone and one [Terminal].
     */
    fun recordDeath(
        identity: ManagedIdentity,
        tick: Long,
    ): DeathRecording =
        lock.withLock {
            if (!mortality.recordDeath(identity.agentId)) {
                return@withLock DeathRecording.AlreadyRecorded
            }
            // Indexed before the write is attempted, and whatever the write answers: the agent is
            // dead in this process either way, so its account must be refused from here on even
            // when the tombstone that would have outlived the process did not land.
            index(DeadMatch.Whole(identity), identity.uuid, identity.usernameKey)
            val tombstone =
                Tombstone(
                    agentId = identity.agentId.value,
                    uuid = identity.uuid.toString(),
                    username = identity.username,
                    tick = tick,
                )
            when (val write = ledger.record(tombstone)) {
                LedgerWrite.Persisted -> DeathRecording.Terminal
                is LedgerWrite.Failed -> {
                    // The agent is dead either way. What is lost is the guarantee that it stays
                    // dead across a restart, and the only safe response to that is to stop
                    // admitting anybody until a human has looked.
                    seals += write.reason
                    DeathRecording.Unpersisted(write.reason)
                }
            }
        }

    /**
     * Takes every part of one replayed tombstone that reads back, and seals for whatever does not.
     *
     * A tombstone Loom wrote always reads back, so one that does not means the file has been
     * corrupted or edited since it was written - and its fields fail independently, which is why
     * each is taken on its own rather than the line being kept or dropped whole. The agent id is
     * what a death is recorded under: discarding it made [isDead] answer false for an agent the
     * file names, so the login was refused as a ledger Loom could not read rather than as the death
     * it plainly is, and every statement after it treated that agent as alive. The uuid and the
     * username are what a *connection* is matched against, so each is indexed whenever it alone is
     * usable - a hand-edited uuid otherwise left the agent's own username, still sitting in the
     * file, matching nothing, and its unregistered reconnect was admitted.
     *
     * Recording and sealing are both required and neither replaces the other. The seal is reached
     * only for a connection a registration already matched, so it cannot stand in for an index; and
     * throwing is not an option, since it would leave the server with no witness at all.
     *
     * [ordinal] counts *tombstones*, not file lines, and the reason says which. The ledger's own
     * unsafe reason counts lines, and the two diverge as soon as a blank line is present - which is
     * tolerated - so a reason that did not name its own unit would read as an index into the file it
     * is not. Neither quotes the tombstone, for the reason [LedgerReplay.Unsafe] gives.
     */
    private fun remember(
        ordinal: Int,
        tombstone: Tombstone,
    ) {
        val agentId = agentIdIn(tombstone)
        agentId?.let(mortality::recordDeath)
        val identity = identityIn(tombstone)
        if (identity != null) {
            index(DeadMatch.Whole(identity), identity.uuid, identity.usernameKey)
            return
        }
        if (agentId != null) {
            index(DeadMatch.AgentOnly(agentId), uuidIn(tombstone), connectableUsernameKeyIn(tombstone))
        }
        // Worded for what is actually true of a line that only partly read back, because the same
        // call above may just have indexed the half that did: what is lost is the certainty that
        // Loom knows every identity the ledger meant to name, not the ability to match any of them.
        // Which is why the fault says only that, and leaves the consequence to the two readers of
        // [sealReason], each of which states it once - repeated per fault it was 73 characters of
        // the record's whole budget, spent again for every further fault the same file held.
        seals += "tombstone ${ordinal + 1} of the death ledger did not read back in full"
    }

    private fun index(
        match: DeadMatch,
        uuid: UUID?,
        usernameKey: String?,
    ) {
        uuid?.let { deadByUuid[it] = match }
        usernameKey?.let { deadByUsername[it] = match }
    }
}

/** The most faults a seal states before it stops naming them one by one. */
private const val MAX_SEAL_REASONS = 4

/** What separates one named fault from the next. */
private const val SEAL_SEPARATOR = "; "

/**
 * Every reason in [found] as one bounded line, or null when the durable state is sound.
 *
 * A ledger corrupted in several ways at once would otherwise put an unbounded list into a record's
 * detail and into the server log, so past what fits the rest are counted rather than named: an
 * operator who has four faults to look at does not need the fifth spelled out to know the file needs
 * rebuilding. Each reason already names its own unit, so joining them cannot make a tombstone
 * ordinal read as a file line.
 *
 * Two bounds, and each belongs to somebody. [MAX_SEAL_REASONS] caps how many faults are ever worth
 * naming and applies to every reader; [within] is one reader's own character budget, and the line is
 * shortened by whole faults until it fits. A reader with no such budget passes none and gets the
 * whole count-bounded line.
 *
 * Leaving a reader to do its own truncating was the failure this replaces: the faults overflowed the
 * `witnessDenied` record's detail, so the cut fell on the end of the line and took the "and N more"
 * with it, leaving an operator a truncated sentence and no sign that anything had been left out.
 *
 * At least one fault is always named, even one longer than the whole budget: a fault an operator can
 * only half read still says where to look, and saying nothing does not.
 *
 * [found] holds each fault once, so both bounds and the count of what they left unnamed are about
 * faults an operator has to go and look at rather than about how often one recurred.
 */
private fun sealReasonOf(
    found: Collection<String>,
    within: Int = Int.MAX_VALUE,
): String? {
    if (found.isEmpty()) {
        return null
    }
    val faults = found.toList()
    var named = minOf(faults.size, MAX_SEAL_REASONS)
    while (named > 1 && sealLine(faults, named).length > within) {
        named--
    }
    return sealLine(faults, named)
}

/** The first [named] of [faults] as one line, counting whatever it left out. */
private fun sealLine(
    faults: List<String>,
    named: Int,
): String {
    val stated = faults.take(named).joinToString(SEAL_SEPARATOR)
    val rest = faults.size - named
    return if (rest > 0) "$stated${SEAL_SEPARATOR}and $rest more" else stated
}

/**
 * The agent [tombstone] says died, or null when even that cannot be read.
 *
 * Taken on its own rather than as part of the identity below, because it is the one field whose
 * absence there is no working around: without it a death cannot be recorded and a refusal cannot
 * be filed, while a mangled uuid or username leaves the death itself perfectly legible.
 */
private fun agentIdIn(tombstone: Tombstone): AgentId? = runCatching { AgentId(tombstone.agentId) }.getOrNull()

/** The uuid [tombstone] names, or null when that field will not parse as one. */
private fun uuidIn(tombstone: Tombstone): UUID? = runCatching { UUID.fromString(tombstone.uuid) }.getOrNull()

/**
 * The index key for [tombstone]'s username, or null when no client could present that name.
 *
 * A name outside the game's own shape can never be what a connection offers, so indexing it would
 * add a key nothing can match rather than a refusal.
 */
private fun connectableUsernameKeyIn(tombstone: Tombstone): String? =
    tombstone.username.takeIf(::isConnectableUsername)?.let(::usernameKeyOf)

/**
 * The identity [tombstone] names, or null when it does not name one Loom can use.
 *
 * Every field is re-validated rather than trusted, because these three came off a file: a uuid
 * that will not parse, a username no client could present, and an agent id `AgentId` itself
 * refuses are all values no connection could ever match, and a null here is what turns each of
 * them into a seal instead of an exception thrown out of a plugin's enable.
 */
private fun identityIn(tombstone: Tombstone): ManagedIdentity? =
    runCatching {
        ManagedIdentity(
            agentId = AgentId(tombstone.agentId),
            username = tombstone.username,
            uuid = UUID.fromString(tombstone.uuid),
        )
    }.getOrNull()
