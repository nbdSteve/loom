/*
 * The one correction a movement may state, the departure an arrival's check found, and the block
 * kinds a sense may be asked to look for beyond reach: generation 8 of the body-control wire.
 */
package dev.loom.bodycontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an arrival's departure check found: whether one move the route search offers out of the
 * cell the body came to rest in is one the client library's own executor can make from there.
 * `blocked` says the next request would stand still; it is a measurement, never a refusal (r34
 * delta stood under an oak's leaves for four requests in a row).
 */
@Serializable
enum class Departure {
    @SerialName("clear")
    CLEAR,

    @SerialName("blocked")
    BLOCKED,
}

/** The two corrections a movement may make, each the answer to its one [RecoveryCause]. */
@Serializable
enum class RecoveryKind {
    /** A straight step to the centre of the cell an arrival could not leave. */
    @SerialName("recentre")
    RECENTRE,

    /** A straight step into a level cell beside a start whose route was stuck at its first move. */
    @SerialName("sidestep")
    SIDESTEP,
}

/** Why a movement made its one correction. */
@Serializable
enum class RecoveryCause {
    @SerialName("departureBlocked")
    DEPARTURE_BLOCKED,

    @SerialName("firstMoveStuck")
    FIRST_MOVE_STUCK,
}

/** The cause each kind of correction answers, and the only one it may state. */
val RecoveryKind.cause: RecoveryCause
    get() =
        when (this) {
            RecoveryKind.RECENTRE -> RecoveryCause.DEPARTURE_BLOCKED
            RecoveryKind.SIDESTEP -> RecoveryCause.FIRST_MOVE_STUCK
        }

/**
 * The one correction one movement request made: at most one per request, never a teleport, from
 * where the step began to where the body came to rest.
 */
@Serializable
data class MovementRecovery(
    val kind: RecoveryKind,
    val cause: RecoveryCause,
    val from: List<Double>,
    val to: List<Double>,
) {
    init {
        require(cause == kind.cause) { "a $kind states only its own cause" }
        requireWorldPosition("from", from)
        requireWorldPosition("to", to)
    }
}

/**
 * Refuses a walk or flight's [departure] and [recovery] where they do not fit its [outcome]: a
 * departure is an arrival's check, and a re-centre is made for an arrival alone.
 */
internal fun requireCorrection(
    outcome: MoveOutcome,
    departure: Departure?,
    recovery: MovementRecovery?,
) {
    require(departure == null || outcome == MoveOutcome.ARRIVED) { "a departure is stated only on an arrival" }
    require(recovery?.kind != RecoveryKind.RECENTRE || outcome == MoveOutcome.ARRIVED) {
        "a re-centre is made only for an arrival"
    }
}

/** Refuses an approach's [recovery] that is a re-centre, which only an arrival check makes. */
internal fun requireApproachCorrection(recovery: MovementRecovery?) {
    require(recovery?.kind != RecoveryKind.RECENTRE) { "an approach makes no re-centre" }
}

/** Most block kinds one sense may ask to have looked for beyond interaction reach. */
const val MAX_SOUGHT_KINDS = 12

/** Refuses a `seek` that is empty, over [MAX_SOUGHT_KINDS], repeats a kind, or names no resource. */
internal fun requireSought(kinds: List<String>) {
    require(kinds.size in 1..MAX_SOUGHT_KINDS) { "seek names 1 to $MAX_SOUGHT_KINDS block kinds" }
    require(kinds.toSet().size == kinds.size) { "seek names each block kind once" }
    kinds.forEach { requireResourceName("seek", it) }
}
