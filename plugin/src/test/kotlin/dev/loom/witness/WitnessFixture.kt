/*
 * The shared stand-ins for the witness suites.
 *
 * Two of them are deliberately fakes rather than mocks. A [RecordingLog] keeps the *thread* each
 * record arrived on as well as the record, because "records are written from the thread the server
 * called the witness on" is one of the properties under test and a plain list cannot show it. A
 * [FakeLedger] can be told to be unreadable or to refuse a write, which is how the fail-closed
 * paths are reached at a desk - a real filesystem cannot be made to fail on demand without either
 * root or a mount, and the paths that matter most here are exactly the ones a disk only reaches on
 * a bad day.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier

/** How long a tandem test waits for one of its threads before calling it a hang. */
private const val TANDEM_TIMEOUT_MILLIS = 10_000L

/**
 * The uuid an offline-mode server would give [username].
 *
 * The game's own derivation, so a fixture's identity is one a real login could actually present.
 * It matters that this is a hash of the name rather than a random value: it is the very inference
 * `ManagedIdentity` refuses to make, and using it here keeps the fixtures honest about the fact
 * that the two halves of an identity *can* be derived from one another and still must not be.
 */
internal fun offlineUuid(username: String): UUID =
    UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))

/** One managed identity, named the way a run would name it. */
internal fun managed(
    agentId: String,
    username: String,
): ManagedIdentity = ManagedIdentity(AgentId(agentId), username, offlineUuid(username))

/** A registry holding exactly [identities]. */
internal fun registryOf(vararg identities: ManagedIdentity): WitnessRegistry =
    WitnessRegistry().apply { identities.forEach(::register) }

/**
 * The tombstone Loom would write for [identity] at [tick].
 *
 * Built from a real [ManagedIdentity] rather than from loose strings, so a replay test is reading
 * back exactly what a death would have written and not a hand-made line that happens to parse.
 */
internal fun tombstoneFor(
    identity: ManagedIdentity,
    tick: Long,
): Tombstone = Tombstone(identity.agentId.value, identity.uuid.toString(), identity.username, tick)

/** One record, and the thread the witness was on when it wrote it. */
internal data class LoggedRecord(
    val record: BodyEventRecord,
    val thread: String,
)

/** A [BodyEventLog] that keeps everything it is given, safely for a concurrent test. */
internal class RecordingLog : BodyEventLog {
    private val entries = CopyOnWriteArrayList<LoggedRecord>()

    override fun append(record: BodyEventRecord) {
        entries += LoggedRecord(record, Thread.currentThread().name)
    }

    /** Every record, in the order it was appended. */
    val records: List<BodyEventRecord> get() = entries.map { it.record }

    /** The thread name each record was appended on, in the same order. */
    val threads: List<String> get() = entries.map { it.thread }

    /** Every record of [event]. */
    fun of(event: BodyEvent): List<BodyEventRecord> = records.filter { it.event == event }

    /** Every record filed under [agentId]. */
    fun forAgent(agentId: String): List<BodyEventRecord> = records.filter { it.agentId == agentId }
}

/**
 * A [DeathLedger] whose replay and write outcomes are chosen by the test.
 *
 * @param found what [replay] answers. Read once, by `DurablePermadeath`'s constructor.
 * @param writeFailure when non-null, every write fails with this reason and nothing is persisted.
 */
internal class FakeLedger(
    private val found: LedgerReplay = LedgerReplay.Readable(emptyList()),
    private val writeFailure: String? = null,
) : DeathLedger {
    /** The tombstones that actually landed. */
    val persisted = CopyOnWriteArrayList<Tombstone>()

    override fun replay(): LedgerReplay = found

    override fun record(tombstone: Tombstone): LedgerWrite =
        if (writeFailure != null) {
            LedgerWrite.Failed(writeFailure)
        } else {
            persisted += tombstone
            LedgerWrite.Persisted
        }
}

/**
 * Runs [work] on [threads] threads that all start at the same moment, and answers what each said.
 *
 * A barrier rather than a plain pool, because every concurrent property here is about two callers
 * being *inside* the witness at once - a server admitting a login on a Netty thread while the main
 * thread witnesses a death - and threads started one after another usually are not. Each thread is
 * named from [name], which is what lets a test assert which thread a record was written on.
 *
 * A failure on any thread is re-thrown here rather than swallowed, and a thread that never finishes
 * fails the test instead of hanging the suite: a deadlock is one of the outcomes under test, and a
 * suite that hung on regression would report nothing about why.
 */
internal fun <T> inTandem(
    threads: Int,
    name: String = "witness",
    work: (Int) -> T,
): List<T> {
    val ready = CyclicBarrier(threads)
    val answers = ConcurrentHashMap<Int, Result<T>>()
    val running =
        (0 until threads).map { index ->
            Thread({
                answers[index] =
                    runCatching {
                        ready.await()
                        work(index)
                    }
            }, "$name-$index")
        }
    running.forEach(Thread::start)
    running.forEach { thread ->
        thread.join(TANDEM_TIMEOUT_MILLIS)
        check(!thread.isAlive) { "${thread.name} did not finish within $TANDEM_TIMEOUT_MILLIS ms" }
    }
    return (0 until threads).map { answers.getValue(it).getOrThrow() }
}

/**
 * One witness and everything it wrote, over the identities it manages.
 *
 * Constructed rather than assembled per test because the alert callback has to exist before the
 * witness does - a sealed ledger is reported from the constructor, and a test that built the list
 * afterwards would miss the one alert that matters most.
 */
internal class WitnessUnderTest(
    vararg managed: ManagedIdentity,
    val ledger: DeathLedger = FakeLedger(),
) {
    val log = RecordingLog()
    val alerts = CopyOnWriteArrayList<String>()

    /** The server tick the next statement is made at. */
    var tick: Long = 100

    val permadeath = DurablePermadeath(ledger)

    val witness =
        PlayerWitness(
            registryOf(*managed),
            permadeath,
            WitnessJournal(log),
            currentTick = { tick },
            alert = alerts::add,
        )

    /** The tombstones that landed. Empty for a ledger whose bookkeeping is the file itself. */
    val persisted: List<Tombstone> get() = (ledger as? FakeLedger)?.persisted ?: emptyList()
}
