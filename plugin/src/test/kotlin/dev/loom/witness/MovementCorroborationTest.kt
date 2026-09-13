/*
 * That a player body's account of one movement request is checked against the server's, and
 * that the check says which kind of thing was wrong.
 *
 * The cases below are all about *distinguishing* things a single boolean would collapse, the
 * way `CorroborationTest`'s are. A report that contradicts its own arithmetic and a report the
 * server puts somewhere else have failed for different reasons - a diverged client simulation
 * against a body that was moved - and a run has to count them apart. A body that stopped a
 * block short of the block it was sent to has not failed at all, and neither has one whose
 * route measured marginally shorter than the straight line across it. A position the server
 * cannot be asked about is neither agreement nor a fault. And a movement claimed by a player no
 * registration names was not compared at all, which is the fourth answer.
 *
 * Two properties are worth stating outright, because they are what the design turns on. The
 * journey checks need no server, so they run against an absent or a dead player as well as a
 * standing one; and a walk that reports it did not arrive is never measured against its
 * destination, since that report is itself the news.
 *
 * Every case drives the real `ClaimAudit` and builds its claims out of the real wire types, so
 * what holds is that a disagreement reaches the append-only record exactly once with a
 * countable tag, that agreement reaches it not at all, and that the reports all of this is
 * derived from are ones the codec will in fact carry.
 */
package dev.loom.witness

import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.body.toJsonLine
import dev.loom.bodycontrol.BODY_CONTROL_PROTOCOL_VERSION
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.LifecycleState
import dev.loom.bodycontrol.MoveOutcome
import dev.loom.bodycontrol.THREAT_REFUSALS
import dev.loom.contract.LoomJson
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The ground the in-world probe's body stood on, so every position here reads as one scene. */
private const val GROUND_LEVEL = 64.0

/** Where that body stood when the request was made. */
private val START = listOf(8.5, GROUND_LEVEL, 8.5)

/** Where its walk actually left it, which is 1.36 blocks from the block it was sent to. */
private val ARRIVAL = listOf(12.32, GROUND_LEVEL, 13.32)

/** How far it reported walking to get there: 0.13 under the straight line between the two. */
private const val WALKED = 6.022

/** The block the request named. */
private val NAMED = listOf(12.0, GROUND_LEVEL, 12.0)

/** The origin, for a case whose two ends have to be a whole number of blocks apart. */
private val HERE = listOf(0.0, GROUND_LEVEL, 0.0)

/** A destination far from the origin, so a two-fault detail is long enough to overrun the bound. */
private val FAR_AWAY = listOf(-1_234_567.5, GROUND_LEVEL, 2_345_678.5)

/** Thirty-two blocks from [FAR_AWAY], which is past both the arrival allowance and the slack. */
private val FAR_AWAY_MISSED = listOf(-1_234_599.5, GROUND_LEVEL, 2_345_678.5)

/** The agent behind the one managed identity every case uses. */
private const val AGENT = "agent-scout"

/** The tick the standalone audit in the replay case states. */
private const val RECORDED_TICK = 4_242L

/** The server standing that agrees with a claimed position exactly. */
private fun observed(position: List<Double>): ObservedPosition =
    ObservedPosition(x = position[0], y = position[1], z = position[2])

class MovementCorroborationTest {
    private val scout = managed(AGENT, "loomscout1")
    private val under = WitnessUnderTest(scout)

    private fun crossCheck(
        claim: MovementClaim,
        standing: ServerStanding = ServerStanding.Standing(observed(claim.position)),
    ): MovementCorroboration = under.claims.sawMovement(scout.uuid, scout.username, claim, standing)

    private fun disputes() = under.log.of(BodyEvent.WITNESS_DISPUTED)

    /** The `moveTo` frame a controller sends, correlated by `asked`. */
    private fun asking(
        asked: String,
        named: NamedDestination,
        agentId: String = AGENT,
    ): BodyRequest.MoveTo =
        BodyRequest.MoveTo(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = asked,
            agentId = agentId,
            destination = named.position,
            tolerance = named.tolerance,
            timeoutMs = 8_000,
            sprint = false,
        )

    /** The `moveTo` frame the body answers with, correlated by `answered`. */
    private fun answering(
        answered: String,
        outcome: MoveOutcome,
        from: List<Double>,
        position: List<Double>,
        travelled: Double,
    ): BodyResponse.MoveReport =
        BodyResponse.MoveReport(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = answered,
            agentId = AGENT,
            state = LifecycleState.READY,
            outcome = outcome,
            from = from,
            position = position,
            travelled = travelled,
            elapsedMs = 1_286,
        )

    /** One walk claim, built the way the runtime will build it: from the request and its report. */
    private fun walk(
        outcome: MoveOutcome = MoveOutcome.ARRIVED,
        from: List<Double> = START,
        position: List<Double> = ARRIVAL,
        travelled: Double = WALKED,
        named: NamedDestination = NamedDestination(NAMED, 0),
    ): MovementClaim =
        movementClaimOf(
            asking("m1", named),
            answering("m1", outcome, from, position, travelled),
        )

    /** One flight claim, which names no destination however the flight itself ended. */
    private fun flight(
        outcome: MoveOutcome = MoveOutcome.ARRIVED,
        position: List<Double> = ARRIVAL,
        travelled: Double = WALKED,
        separation: Double? = 9.6755,
        separationGained: Double? = 6.14,
    ): MovementClaim =
        movementClaimOf(
            BodyRequest.Flee(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "f1",
                agentId = AGENT,
                senseId = "sense-1",
                entity = 0,
                distance = 8,
                timeoutMs = 8_000,
                sprint = true,
            ),
            BodyResponse.FleeReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "f1",
                agentId = AGENT,
                state = LifecycleState.READY,
                outcome = outcome,
                from = START,
                position = position,
                travelled = travelled,
                elapsedMs = 1_286,
                separation = separation,
                separationGained = separationGained,
            ),
        )

    // The factory exists so that a walk's destination is read out of the request that named it,
    // and that is only true if the two frames are the same round trip. Paired wrongly they build
    // a claim whose destination belongs to one walk and whose arrival belongs to another, and the
    // cross-check then answers - either way round, disputing or agreeing - about a journey nothing
    // asked for. Refused at the factory, the first caller cannot get it wrong quietly.
    @Test
    fun aClaimMustPairOneRequestWithItsOwnReport() {
        val named = NamedDestination(NAMED, 0)
        val crossed =
            assertFailsWith<IllegalArgumentException> {
                movementClaimOf(
                    asking("m1", named),
                    answering("m2", MoveOutcome.ARRIVED, START, ARRIVAL, WALKED),
                )
            }
        assertContains(crossed.message ?: "", "its own report")

        val crossedAgent =
            assertFailsWith<IllegalArgumentException> {
                movementClaimOf(
                    asking("m1", named),
                    answering("m1", MoveOutcome.ARRIVED, START, ARRIVAL, WALKED).copy(
                        agentId = "agent-other",
                    ),
                )
            }
        assertContains(crossedAgent.message ?: "", "one agent")

        // And a pair the controller did correlate is unaffected, whatever it correlated them by,
        // which is what stops this reading as a ban on building a claim at all.
        val paired =
            movementClaimOf(
                asking("m7", named),
                answering("m7", MoveOutcome.ARRIVED, START, ARRIVAL, WALKED),
            )
        assertEquals(MovementCorroboration.Agreed, crossCheck(paired))
    }

    @Test
    fun aFlightClaimPairsTheRequestAndReportAgentsToo() {
        val request =
            BodyRequest.Flee(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "f1",
                agentId = AGENT,
                senseId = "sense-1",
                entity = 0,
                distance = 8,
                timeoutMs = 8_000,
                sprint = false,
            )
        val report =
            BodyResponse.FleeReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = "f1",
                agentId = "agent-other",
                state = LifecycleState.READY,
                outcome = MoveOutcome.THREAT_GONE,
                from = START,
                position = START,
                travelled = 0.0,
                elapsedMs = 0,
            )

        val refused =
            assertFailsWith<IllegalArgumentException> {
                movementClaimOf(request, report)
            }
        assertContains(refused.message ?: "", "one agent")
    }

    @Test
    fun aClaimMustNameTheAgentRegisteredToTheWitnessedPlayer() {
        val wrongAgent = walk().copy(agentId = "agent-other")
        val refused =
            assertFailsWith<IllegalArgumentException> {
                crossCheck(wrongAgent)
            }
        assertContains(refused.message ?: "", "registered")
        assertTrue(disputes().isEmpty(), "a mismatched claim must not write evidence under another agent")
    }

    // A record per cross-check that found nothing would bury the ones that did, in the one log
    // whose whole purpose is counting them.
    @Test
    fun anHonestWalkAgreesAndIsNotRecordedAtAll() {
        assertEquals(MovementCorroboration.Agreed, crossCheck(walk()))
        assertTrue(disputes().isEmpty(), "agreement must reach the record nowhere: ${disputes()}")
    }

    // The pathfinder measures to a block and the body is a point standing in it, so a body that
    // went exactly where it was sent is already a fraction of a block from the coordinates it was
    // given: with no allowance at all, this check disputed the in-world probe's own honest walk.
    @Test
    fun anArrivalMayMissItsBlockByTheAllowanceAndNoFurther() {
        val onTheEdge = walk(position = listOf(12.0, GROUND_LEVEL, 14.0), travelled = 6.6)
        assertEquals(MovementCorroboration.Agreed, crossCheck(onTheEdge))

        val beyond = walk(position = listOf(12.0, GROUND_LEVEL, 14.5), travelled = 7.0)
        val dispute = assertIs<MovementCorroboration.Disputed>(crossCheck(beyond))
        val fault = assertIs<JourneyFault.NotArrived>(dispute.journey.single())
        assertEquals(2.5, fault.apart, "the fault states how far off the claimed arrival was")
        assertEquals(EventTag.DISPUTED_JOURNEY, disputes().single().outcome)
    }

    // The bound is the request's own tolerance plus the allowance, never the allowance alone: a
    // body asked to get within four blocks of somewhere has not missed by stopping three away.
    @Test
    fun theRequestsOwnToleranceIsAddedToTheAllowance() {
        val fiveOff = listOf(12.0, GROUND_LEVEL, 17.0)
        val exact = walk(position = fiveOff, travelled = 9.5, named = NamedDestination(NAMED, 0))
        assertIs<MovementCorroboration.Disputed>(crossCheck(exact))

        val forgiving = walk(position = fiveOff, travelled = 9.5, named = NamedDestination(NAMED, 4))
        assertEquals(MovementCorroboration.Agreed, crossCheck(forgiving))
    }

    // A walk that was refused, timed out, stalled or cancelled is *reporting* that it did not
    // arrive, so measuring it against its destination would dispute every honest failure the wire
    // has. The pre-search refusal truthfully stayed at its start with zero travel.
    @Test
    fun anOutcomeOtherThanArrivalSaysNothingAboutTheDestination() {
        val nowhereNear = listOf(12.0, GROUND_LEVEL, 30.0)
        val unarrived = MoveOutcome.entries - MoveOutcome.ARRIVED - THREAT_REFUSALS
        assertTrue(unarrived.isNotEmpty(), "a walk must have more than one way to end")
        unarrived.forEach { outcome ->
            val preSearch = outcome == MoveOutcome.OUT_OF_RANGE
            val report =
                walk(
                    outcome = outcome,
                    position = if (preSearch) START else nowhereNear,
                    travelled = if (preSearch) 0.0 else 22.0,
                )
            assertEquals(MovementCorroboration.Agreed, crossCheck(report), "$outcome")
        }

        val claimedArrival = walk(position = nowhereNear, travelled = 22.0)
        assertIs<MovementCorroboration.Disputed>(crossCheck(claimedArrival))
    }

    // Half the value of the cross-check is reachable with no server at all, which is why the
    // journey checks are unconditional: a body that quit mid-route still reported its own
    // arithmetic, and a witness that only compared positions would have thrown that away.
    @Test
    fun aRouteShorterThanItsOwnDisplacementIsAFaultTheServerIsNotNeededFor() {
        val invented =
            walk(
                outcome = MoveOutcome.CANCELLED,
                from = HERE,
                position = listOf(0.0, GROUND_LEVEL, 10.0),
                travelled = 0.0,
            )
        val dispute = assertIs<MovementCorroboration.Disputed>(crossCheck(invented, ServerStanding.PlayerAbsent))
        val fault = assertIs<JourneyFault.ShortRoute>(dispute.journey.single())
        assertEquals(10.0, fault.displaced)
        assertEquals(0.0, fault.travelled)
        assertEquals(PositionUnread.PLAYER_ABSENT, dispute.positionUnread)
        assertEquals(EventTag.DISPUTED_JOURNEY, disputes().single().outcome)
    }

    // Travelled distance accumulates per physics tick while the two ends of it are single
    // readings, so a route can measure marginally under its own straight line with nothing
    // wrong; a check with no slack would report that as a body having lied.
    @Test
    fun aRouteMayFallShortOfItsDisplacementByTheSlackAndNoFurther() {
        val tenAway = listOf(0.0, GROUND_LEVEL, 10.0)
        val onTheEdge = walk(outcome = MoveOutcome.CANCELLED, from = HERE, position = tenAway, travelled = 9.0)
        assertEquals(MovementCorroboration.Agreed, crossCheck(onTheEdge))

        val beyond = walk(outcome = MoveOutcome.CANCELLED, from = HERE, position = tenAway, travelled = 8.9)
        val dispute = assertIs<MovementCorroboration.Disputed>(crossCheck(beyond))
        assertIs<JourneyFault.ShortRoute>(dispute.journey.single())
    }

    // A player Loom was never asked about is not Loom's to have an opinion about, and the claim
    // here is a wrong one: neither the answer nor the record may treat it as checked.
    @Test
    fun aStrangersMovementIsNotComparedAndIsNotAgreedWithEither() {
        val wrong =
            walk(
                outcome = MoveOutcome.CANCELLED,
                from = HERE,
                position = listOf(0.0, GROUND_LEVEL, 10.0),
                travelled = 0.0,
            )
        val found = under.claims.sawMovement(offlineUuid("passerby"), "passerby", wrong, ServerStanding.PlayerAbsent)
        assertEquals(MovementCorroboration.NotManaged, found)
        assertTrue(disputes().isEmpty(), "nothing may be recorded about a stranger: ${disputes()}")
    }

    // "The witness did not look" must never read back as "the witness agreed", which is the same
    // rule the crafting-window slots follow one level down.
    @Test
    fun aPositionTheServerCannotReadIsStatedRatherThanAgreedWith() {
        assertEquals(
            MovementCorroboration.PartlyChecked(PositionUnread.PLAYER_ABSENT),
            crossCheck(walk(), ServerStanding.PlayerAbsent),
        )
        assertEquals(
            MovementCorroboration.PartlyChecked(PositionUnread.PLAYER_DEAD),
            crossCheck(walk(), ServerStanding.PlayerDead),
        )
        assertTrue(disputes().isEmpty(), "an unchecked position is not a disagreement: ${disputes()}")
    }

    // A claim and the server's reading are separated by a socket round trip and a tick boundary,
    // over which a sprinting player moves several blocks; and a position fault is its own tag,
    // because it implicates the client's simulation rather than the report's own arithmetic.
    @Test
    fun aPositionMayDriftUpToTheToleranceAndNoFurtherAndIsItsOwnTag() {
        val stood = walk(outcome = MoveOutcome.CANCELLED, from = HERE, position = HERE, travelled = 0.0)
        val nearby = ObservedPosition(x = POSITION_DRIFT_TOLERANCE, y = GROUND_LEVEL, z = 0.0)
        assertEquals(MovementCorroboration.Agreed, crossCheck(stood, ServerStanding.Standing(nearby)))

        val further = ObservedPosition(x = 4.5, y = GROUND_LEVEL, z = 0.0)
        val dispute = assertIs<MovementCorroboration.Disputed>(crossCheck(stood, ServerStanding.Standing(further)))
        assertEquals(4.5, dispute.endedElsewhere?.apart)
        assertTrue(dispute.journey.isEmpty(), "the report agreed with itself: ${dispute.journey}")
        assertEquals(EventTag.DISPUTED_POSITION, disputes().single().outcome)
    }

    // One cross-check is one record, and the tag says it found both kinds: a run that read the
    // two apart from the prose could not count either.
    @Test
    fun bothKindsAtOnceAreOneRecordUnderOneTagThatSaysSo() {
        val invented =
            walk(
                outcome = MoveOutcome.CANCELLED,
                from = HERE,
                position = listOf(0.0, GROUND_LEVEL, 10.0),
                travelled = 0.0,
            )
        val stoodStill = ServerStanding.Standing(observed(HERE))
        val dispute = assertIs<MovementCorroboration.Disputed>(crossCheck(invented, stoodStill))
        assertIs<JourneyFault.ShortRoute>(dispute.journey.single())
        assertEquals(10.0, dispute.endedElsewhere?.apart)

        val record = disputes().single()
        assertEquals(EventTag.DISPUTED_MOVEMENT, record.outcome)
        assertContains(record.detail, "2 disagreed")
    }

    // The journal bounds a detail by cutting its tail, and a two-fault movement detail overruns
    // that bound easily: the note about the unread position is written ahead of the faults for
    // exactly that reason, since cut off the end it would read as a wholly checked disagreement.
    @Test
    fun theUnreadNoteStandsAheadOfTheFaultsSoTheBoundCannotCutIt() {
        val overrunning =
            walk(
                from = FAR_AWAY,
                position = FAR_AWAY_MISSED,
                travelled = 0.0,
                named = NamedDestination(FAR_AWAY, 0),
            )
        crossCheck(overrunning, ServerStanding.PlayerDead)

        val detail = disputes().single().detail
        assertTrue(detail.length <= MAX_WITNESS_DETAIL_CHARS, "a record's detail is bounded: ${detail.length}")
        assertTrue(detail.endsWith("..."), "this case must overrun the bound to prove anything: $detail")
        assertContains(detail, "2 disagreed")
        assertContains(detail, "the player is dead")
    }

    // A dispute with no fault would be a false accusation, and one that both disagreed about a
    // position and never read it would be two contradictory statements about the same fact.
    @Test
    fun aDisputeMustNameAFaultAndCannotBothDisagreeAndBeUnread() {
        assertFailsWith<IllegalArgumentException> { MovementCorroboration.Disputed() }
        assertFailsWith<IllegalArgumentException> {
            MovementCorroboration.Disputed(
                endedElsewhere = EndedElsewhere(claimed = HERE, observed = observed(NAMED), apart = 12.0),
                positionUnread = PositionUnread.PLAYER_DEAD,
            )
        }
    }

    // The destination is the request's and the arithmetic is the report's, so the claim has to
    // carry both: a cross-check handed only the report could not tell where the body was sent.
    @Test
    fun aWalksClaimCarriesTheDestinationTheRequestNamed() {
        val claim = walk(named = NamedDestination(NAMED, 3))
        assertEquals(AGENT, claim.agentId)
        assertEquals(NamedDestination(NAMED, 3), claim.destination)
        assertEquals(MoveOutcome.ARRIVED, claim.outcome)
        assertEquals(START, claim.from)
        assertEquals(ARRIVAL, claim.position)
        assertEquals(WALKED, claim.travelled)
    }

    // A flight is answered by the ground rather than by a coordinate anybody named, so there is
    // no destination to measure it against; and its separation is deliberately not cross-checked,
    // because the wire named the threat with an ordinal into a sense answer the server never saw.
    @Test
    fun aFlightsClaimNamesNoDestinationSoItsArrivalIsNotSecondGuessed() {
        val gained = flight(separation = 9.6755, separationGained = 6.14)
        val lost = flight(separation = 0.5, separationGained = -3.0)
        assertEquals(null, gained.destination)
        assertEquals(gained, lost, "separation is no part of what is cross-checked")
        assertEquals(MovementCorroboration.Agreed, crossCheck(gained))
    }

    // A flight that never resolved its threat went nowhere and says so, and the three outcomes
    // that report it carry no separation at all: none of that is a fault of the body's.
    @Test
    fun aThreatRefusalIsAFlightsAnswerAndCarriesNoJourneyFaultOfItsOwn() {
        THREAT_REFUSALS.forEach { outcome ->
            val refused =
                flight(
                    outcome = outcome,
                    position = START,
                    travelled = 0.0,
                    separation = null,
                    separationGained = null,
                )
            assertEquals(MovementCorroboration.Agreed, crossCheck(refused), "$outcome")
        }
    }

    // The record is the whole of the evidence, so it has to survive the round trip through the
    // file: an agreeing cross-check adds no line, and a disputed one replays as itself.
    @Test
    fun aDisagreementReachesTheAppendOnlyFileAsOneReplayableLineAndAgreementReachesItNotAtAll() {
        val directory = Files.createTempDirectory("loom-movement-witness")
        val file = directory.resolve("body-events.jsonl")
        try {
            val audit =
                ClaimAudit(
                    registryOf(scout),
                    WitnessJournal { record ->
                        Files.writeString(
                            file,
                            record.toJsonLine() + "\n",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                    },
                    currentTick = { RECORDED_TICK },
                )
            val honest = walk()
            audit.sawMovement(scout.uuid, scout.username, honest, ServerStanding.Standing(observed(honest.position)))
            val invented =
                walk(
                    outcome = MoveOutcome.CANCELLED,
                    from = HERE,
                    position = listOf(0.0, GROUND_LEVEL, 10.0),
                    travelled = 0.0,
                )
            audit.sawMovement(scout.uuid, scout.username, invented, ServerStanding.Standing(observed(HERE)))

            val lines = file.readLines()
            assertEquals(1, lines.size, "the agreeing claim must add no line: $lines")
            val written = LoomJson.decodeFromString<BodyEventRecord>(lines.single())
            assertEquals(BodyEvent.WITNESS_DISPUTED, written.event)
            assertEquals(EventTag.DISPUTED_MOVEMENT, written.outcome)
            assertEquals(AGENT, written.agentId)
            assertEquals(scout.uuid.toString(), written.witness?.uuid)
            assertEquals(scout.username, written.witness?.username)
            assertEquals(RECORDED_TICK, written.tick)
            assertContains(written.detail, "2 disagreed")
            assertContains(written.detail, "travelled 0.00 over a displacement of 10.00")
            assertEquals(lines.single(), written.toJsonLine(), "a record must replay as itself")
        } finally {
            file.deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }
}
