package dev.loom.observe

import dev.loom.body.AgentVitals
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Provenance

/** The agent every test in this package watches. */
const val WATCHED: String = "agent-ada"

/** A stamp of the shape the mind puts on a decision. */
val STAMP: Provenance =
    Provenance(
        tier = DecisionImportance.ROUTINE,
        model = "test-model",
        promptVersion = "test-v1",
    )

/** The operator every test in this package watches from. */
const val OPERATOR: String = "operator-1"

/**
 * An observer that keeps everything it was shown, for a test to read back.
 *
 * A fake rather than a mock because the interesting assertions are about *what* an
 * operator ends up looking at - which lines, in which order, carrying which text - and
 * that is a value, not an interaction.
 *
 * [observer] defaults to the one operator, so building two of these is by default what
 * the watch command actually does: two surfaces onto the same person.
 */
class RecordingSink(
    override val observer: String = OPERATOR,
    override var isPresent: Boolean = true,
) : ObserverSink {
    val overlays = mutableListOf<Overlay>()
    val notes = mutableListOf<Note>()
    var releases = 0
        private set

    /** Every line an operator has been shown, however it reached them. */
    val everything: List<String> get() = overlays.map { it.text } + notes.map { it.text }

    /** The standing line as it is right now, or `null` if none has been pushed. */
    val standing: Overlay? get() = overlays.lastOrNull()

    override fun show(overlay: Overlay) {
        overlays.add(overlay)
    }

    override fun note(note: Note) {
        notes.add(note)
    }

    override fun release() {
        releases++
    }
}

/**
 * A record about [WATCHED] carrying nothing but [event].
 *
 * The countable fields are added with `copy` at the call sites that care about them,
 * which keeps each test's record showing only the fields that test is about.
 */
fun record(
    event: BodyEvent,
    detail: String = "",
    tick: Long = 0L,
): BodyEventRecord =
    BodyEventRecord(
        tick = tick,
        agentId = WATCHED,
        event = event,
        detail = detail,
    )

/**
 * A vital reading, against a capacity of twenty.
 *
 * Either vital, since the view reads both as a share of their own capacity and never as
 * a scale it knows: in world the reserve arrives on a hundred and health on twenty.
 */
fun energy(left: Double): AgentVitals = AgentVitals(energy = left, capacity = 20.0)

/**
 * An energy-reserve reading on the percentage scale the port really records it on.
 *
 * For the tests whose whole point is a reserve, where a reading out of twenty would not
 * look like the number an operator sees.
 */
fun reserve(left: Double): AgentVitals = AgentVitals(energy = left, capacity = 100.0)
