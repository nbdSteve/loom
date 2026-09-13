/*
 * Cross-checking where a player body says a movement request left it against the server.
 *
 * The same asymmetry `Corroboration.kt` opens with, applied to the other thing a body
 * reports about itself. A `moveTo` or a `flee` answer is written by a Mineflayer client from
 * its own physics simulation, and a client simulates what it was told: a body whose
 * simulation has diverged, or which was pushed, teleported or killed on the way, answers
 * perfectly plausibly about a position the server never had it at. Nothing above the
 * body-control wire can tell the difference, so the server is asked.
 *
 * Four rules, and each is a choice rather than an obvious reading.
 *
 * **The claim and the observation are different types, for `Corroboration.kt`'s reason.** A
 * claim is what the wire may carry, so it arrives as the protocol's own list of three
 * bounded coordinates. The server's reading is not a claim but the truth, and it answers
 * [ObservedPosition] - unbounded, refusing nothing. A plugin can put a player outside the
 * world border, and a witness whose reading of that *throws* has stopped witnessing exactly
 * where it matters.
 *
 * **Two of the three faults need no server at all, and are still faults.** A route is never
 * shorter than the straight line between its own two ends, and a walk that reports `arrived`
 * has said something checkable about the destination it was given: both are the body's own
 * report contradicting itself, and both are worth stating for a body the server can no
 * longer be asked about. So they are checked whatever the server has to say, and only the
 * position comparison depends on there being a player to read.
 *
 * **A position the witness could not read is stated, never read as agreement.** A body that
 * has quit or died leaves nothing to compare, which is [ServerStanding]'s two absences and
 * [MovementCorroboration.PartlyChecked]'s whole content - the same rule the uncheckable
 * crafting slots follow one file over, and for the same reason: "the witness did not look"
 * must never read back as "the witness agreed".
 *
 * **Separation is deliberately not cross-checked**, and its absence here is a decision
 * rather than an omission. A flight's separation is measured against a creature named by a
 * sense answer's own ordinal, which resolves inside the body process alone: the wire carries
 * a `senseId` and an index, so the server was never told which entity a flight ran from and
 * cannot be asked whether the ground gained was real. What the server can settle is where
 * the body ended up, which every flight reports and which is checked here exactly as a
 * walk's is.
 */
package dev.loom.witness

import dev.loom.body.EventTag
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.MoveOutcome
import kotlin.math.sqrt

/**
 * How far a claimed position may sit from the server's reading of it before it is a fault.
 *
 * A claim is taken inside the body process, travels a socket, and is compared against a
 * reading taken later still, so an ordinary agreement is never exact: a sprinting player
 * covers about 5.6 blocks a second, and a falling one more. Four blocks covers that gap and a
 * body that stepped off a ledge as it arrived, and nothing wider than it is drift - a body
 * reporting a position a whole chunk from where the server has it is the case this exists for.
 */
const val POSITION_DRIFT_TOLERANCE: Double = 4.0

/**
 * How far a body claiming to have arrived may sit from the destination it was given.
 *
 * On top of the request's own `tolerance`, because that tolerance is stated in the
 * pathfinder's terms and a destination is a *block* while a body is a point: a body standing
 * in the destination block is already 0.71 blocks from the block's own coordinates
 * horizontally, and may be standing a block above them. Two blocks covers both with room for
 * the tick the arrival was reported on, and a body five blocks from where it says it arrived
 * is still caught.
 */
const val ARRIVAL_ALLOWANCE_BLOCKS: Double = 2.0

/**
 * How much less than its own displacement a route may report having walked.
 *
 * A route's length is accumulated a physics tick at a time while its two ends are single
 * readings, so the two measures never agree exactly; one block absorbs that and a shove or a
 * teleport of a block or so, while a route claiming to have walked half the distance between
 * its own ends is still caught.
 */
const val ROUTE_MEASURE_SLACK_BLOCKS: Double = 1.0

/**
 * The server's own reading of where a player is, in world coordinates.
 *
 * Unbounded on purpose, unlike the claim it is compared against: see this file's header.
 */
data class ObservedPosition(val x: Double, val y: Double, val z: Double)

/** Which of [ServerStanding]'s two absences left a position unchecked. */
enum class PositionUnread {
    PLAYER_ABSENT,
    PLAYER_DEAD,
    ;

    /** How a record says it. */
    val words: String get() = if (this == PLAYER_ABSENT) "no player connected" else "the player is dead"
}

/**
 * What the server has to say about where a managed player is standing.
 *
 * Three answers rather than a nullable position, because the two ways there is nothing to read
 * are two different facts about the run and a run has to count them apart: a body that quit
 * mid-route is a controller or a process that went away, and one that died is the thing the
 * permadeath rule is about. [unread] is a property of the reading rather than a comparison's
 * own `when`, so a fourth standing added later has to say whether it can be read.
 */
sealed interface ServerStanding {
    /** Which absence this reading is, or null if it carries a position. */
    val unread: PositionUnread?

    /** No player of that identity is connected, so there is no position to read. */
    data object PlayerAbsent : ServerStanding {
        override val unread: PositionUnread get() = PositionUnread.PLAYER_ABSENT
    }

    /**
     * The player is connected and dead, so its position means nothing.
     *
     * A dead player is where it died with a client on the death screen, which is not a reading
     * of where a movement request left it.
     */
    data object PlayerDead : ServerStanding {
        override val unread: PositionUnread get() = PositionUnread.PLAYER_DEAD
    }

    /** The player is alive and the server reads it at [position]. */
    data class Standing(val position: ObservedPosition) : ServerStanding {
        override val unread: PositionUnread? get() = null
    }
}

/**
 * A destination a request named, with the tolerance it was allowed to stop short by.
 *
 * The two halves are one object because they are only meaningful together: a tolerance with no
 * destination applies to nothing, and a destination with no tolerance would be compared
 * against a bound this file invented. A flight has neither - it names a creature, never a
 * place - which is why [MovementClaim.destination] is nullable rather than this carrying a
 * sentinel.
 */
data class NamedDestination(val position: List<Double>, val tolerance: Int)

/**
 * What a body reported about one movement request, as the wire carried it.
 *
 * Built by the two [movementClaimOf] factories rather than by hand, so a walk's destination is
 * read out of the request that named it, in one place, by name: assembled at a call site, one
 * request's destination could be carried in beside another's report and the cross-check would
 * answer about a journey nothing ever asked for. The `moveTo` factory refuses that pairing
 * outright, which is what makes the sentence above a rule rather than a hope.
 *
 * @param agentId the agent carried by both frames of this one round trip. The audit checks
 *   it against the server registry before it can write evidence under that registry identity.
 * @param outcome how the request ended. Every [MoveOutcome] is terminal, and
 *   [MoveOutcome.ARRIVED] is the one that says something the destination can settle.
 * @param from where the body says the request started.
 * @param position where the body says the request left it.
 * @param travelled how far the body says it walked along its route, which is never the
 *   straight line between [from] and [position] and never shorter than it.
 * @param destination the place a walk was sent to, or null for a flight.
 */
data class MovementClaim(
    val agentId: String,
    val outcome: MoveOutcome,
    val from: List<Double>,
    val position: List<Double>,
    val travelled: Double,
    val destination: NamedDestination?,
)

/**
 * The claim a `moveTo` request and its own report make together.
 *
 * The two frames must be the *same round trip*, and that is a `require` rather than a note:
 * both carry the `requestId` the controller correlated them by, so the pairing is checkable
 * here and nowhere else. Paired wrongly, the claim would carry one walk's destination beside
 * another's arrival, and the cross-check would then report a body that did not arrive - or
 * silently agree that it did - about a journey nothing ever asked for. The first caller is the
 * one that would get it wrong, since nothing in Loom submits a movement claim yet.
 */
fun movementClaimOf(
    request: BodyRequest.MoveTo,
    report: BodyResponse.MoveReport,
): MovementClaim {
    require(request.requestId == report.requestId) {
        "a movement claim must pair one request with its own report"
    }
    require(request.agentId == report.agentId) {
        "a movement claim must pair one agent's request with that agent's report"
    }
    return MovementClaim(
        agentId = report.agentId,
        outcome = report.outcome,
        from = report.from,
        position = report.position,
        travelled = report.travelled,
        destination = NamedDestination(request.destination, request.tolerance),
    )
}

/**
 * The claim a `flee` request and its own report make together.
 *
 * A flight names no destination the witness can compare, but its request still carries the
 * two identities that make the response one round trip: `requestId` and `agentId`. Taking both
 * frames here prevents two agents that reused one request id on separate control connections
 * from becoming one claim against the wrong player.
 */
fun movementClaimOf(
    request: BodyRequest.Flee,
    report: BodyResponse.FleeReport,
): MovementClaim {
    require(request.requestId == report.requestId) {
        "a movement claim must pair one request with its own report"
    }
    require(request.agentId == report.agentId) {
        "a movement claim must pair one agent's request with that agent's report"
    }
    return MovementClaim(
        agentId = report.agentId,
        outcome = report.outcome,
        from = report.from,
        position = report.position,
        travelled = report.travelled,
        destination = null,
    )
}

/** The server does not read the body where the body says it is. */
data class EndedElsewhere(
    val claimed: List<Double>,
    val observed: ObservedPosition,
    val apart: Double,
)

/** A movement report that contradicts itself, whatever the server has to say. */
sealed interface JourneyFault {
    /** An arrival reported too far from the destination the request named. */
    data class NotArrived(
        val destination: NamedDestination,
        val claimed: List<Double>,
        val apart: Double,
    ) : JourneyFault

    /** A route reported as shorter than the straight line between its own two ends. */
    data class ShortRoute(
        val travelled: Double,
        val displaced: Double,
    ) : JourneyFault
}

/**
 * What one movement cross-check found.
 *
 * Four answers, mirroring `Corroboration`: [Agreed] is only for a claim checked *whole*, so a
 * report whose journey held up while the server had no position to offer is [PartlyChecked]
 * however well the rest of it read.
 */
sealed interface MovementCorroboration {
    /** Everything comparable was compared and agreed. */
    data object Agreed : MovementCorroboration

    /** Not a managed identity, so Loom has no opinion and recorded nothing. */
    data object NotManaged : MovementCorroboration

    /** The report held up, and the server had no position to compare it against. */
    data class PartlyChecked(val why: PositionUnread) : MovementCorroboration

    /**
     * At least one fault, and whether the position was compared at all.
     *
     * [positionUnread] is carried for the reason `Corroboration.Disputed.outsideComparison`
     * is: a dispute that named only its faults would read as a whole cross-check, and a
     * journey fault found while the server was silent is weaker evidence than one found
     * beside a position the server confirmed.
     */
    data class Disputed(
        val endedElsewhere: EndedElsewhere? = null,
        val journey: List<JourneyFault> = emptyList(),
        val positionUnread: PositionUnread? = null,
    ) : MovementCorroboration {
        init {
            require(endedElsewhere != null || journey.isNotEmpty()) { "a dispute must name at least one fault" }
            require(endedElsewhere == null || positionUnread == null) {
                "a position that was not read cannot also disagree"
            }
        }
    }
}

/**
 * Which countable `DISPUTED_` tag a movement dispute is filed under.
 *
 * Total over the two kinds of fault by construction, since [MovementCorroboration.Disputed]
 * refuses to hold neither.
 */
fun tagOf(dispute: MovementCorroboration.Disputed): String =
    when {
        dispute.endedElsewhere != null && dispute.journey.isNotEmpty() -> EventTag.DISPUTED_MOVEMENT
        dispute.endedElsewhere != null -> EventTag.DISPUTED_POSITION
        else -> EventTag.DISPUTED_JOURNEY
    }

/**
 * Compares one movement report against the server's own standing of the body.
 *
 * The journey checks run unconditionally, because they need no server: see this file's header
 * for why a body the server can no longer be asked about is still worth checking against its
 * own arithmetic.
 */
fun corroborateMovement(
    claim: MovementClaim,
    standing: ServerStanding,
): MovementCorroboration {
    val journey = journeyFaults(claim)
    val elsewhere = (standing as? ServerStanding.Standing)?.let { endedElsewhere(claim, it.position) }
    val unread = standing.unread
    if (journey.isEmpty() && elsewhere == null) {
        return if (unread == null) MovementCorroboration.Agreed else MovementCorroboration.PartlyChecked(unread)
    }
    return MovementCorroboration.Disputed(endedElsewhere = elsewhere, journey = journey, positionUnread = unread)
}

private fun endedElsewhere(
    claim: MovementClaim,
    observed: ObservedPosition,
): EndedElsewhere? {
    val apart = apart(claim.position, listOf(observed.x, observed.y, observed.z))
    if (apart <= POSITION_DRIFT_TOLERANCE) {
        return null
    }
    return EndedElsewhere(claimed = claim.position, observed = observed, apart = apart)
}

/**
 * The two faults a report can carry against itself, in a fixed order so one moment always
 * reads the same way.
 */
private fun journeyFaults(claim: MovementClaim): List<JourneyFault> =
    buildList {
        val destination = claim.destination
        if (destination != null && claim.outcome == MoveOutcome.ARRIVED) {
            val apart = apart(claim.position, destination.position)
            if (apart > destination.tolerance + ARRIVAL_ALLOWANCE_BLOCKS) {
                add(JourneyFault.NotArrived(destination = destination, claimed = claim.position, apart = apart))
            }
        }
        val displaced = apart(claim.from, claim.position)
        if (displaced - claim.travelled > ROUTE_MEASURE_SLACK_BLOCKS) {
            add(JourneyFault.ShortRoute(travelled = claim.travelled, displaced = displaced))
        }
    }

/**
 * What a movement dispute record says.
 *
 * The fault count and the unread note stand ahead of the list for the reason
 * `Corroboration.detailOf` states: [WitnessJournal] bounds a detail by cutting the tail, and an
 * unread position cut off the end would turn "the witness did not look" back into "the witness
 * agreed".
 */
fun detailOf(dispute: MovementCorroboration.Disputed): String {
    val faults =
        buildList {
            dispute.endedElsewhere?.let { add(elsewhereWords(it)) }
            addAll(dispute.journey.map(::journeyWords))
        }
    val unread = dispute.positionUnread?.let { ", position unchecked: ${it.words}" } ?: ""
    return "${faults.size} disagreed$unread: ${faults.joinToString("; ")}"
}

private fun elsewhereWords(fault: EndedElsewhere): String {
    val observed = listOf(fault.observed.x, fault.observed.y, fault.observed.z)
    return "ended at ${fault.claimed.words}, server reads ${observed.words}, " +
        "${twoPlaces(fault.apart)} blocks apart"
}

private fun journeyWords(fault: JourneyFault): String =
    when (fault) {
        is JourneyFault.NotArrived ->
            "claimed arrival at ${fault.claimed.words} is ${twoPlaces(fault.apart)} blocks from " +
                "${fault.destination.position.words} with a tolerance of ${fault.destination.tolerance}"
        is JourneyFault.ShortRoute ->
            "travelled ${twoPlaces(fault.travelled)} over a displacement of ${twoPlaces(fault.displaced)}"
    }

/** Three coordinates as one field, each fixed to two places, the way [PositionUnread.words] reads. */
private val List<Double>.words: String get() = joinToString(",", transform = ::twoPlaces)

/**
 * Straight-line blocks between two positions, over all three axes.
 *
 * Three axes because a body a hundred blocks below where it claims to be has not arrived, and a
 * per-axis reading would call that agreement on two of them.
 */
private fun apart(
    one: List<Double>,
    other: List<Double>,
): Double =
    sqrt(
        one.indices.sumOf { axis ->
            val gap = one[axis] - other[axis]
            gap * gap
        },
    )
