package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.GoalDisposition
import dev.loom.contract.Intent
import dev.loom.contract.ObservationKind
import dev.loom.contract.Trigger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The four goal and safety claims the r15 live run never exercised, each driven offline end to
 * end: the real JVM ledger, cognition, actuation and fleet over real control sockets to real Node
 * bodies on real Mineflayer, with a scripted mind standing in for the model.
 *
 * - a goal pursued by idling is refused before the body acts, and the next review is told why;
 * - digging the only floor of a shaft is refused before the body is asked;
 * - a threat flight halts a routine dig in flight and arrives clear of the threat;
 * - two agents digging one block end in the server's one break and one lost race, no dispute.
 *
 * The scene each body stands in and the server ground the JVM reads are one [Scene], so the two
 * sides cannot disagree about a block. Runs whenever `node` and `body/node_modules` exist.
 */
class GoalScenarioEndToEndTest {
    @TempDir
    lateinit var temporary: Path

    private val closers = ArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        closers.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `a goal pursued by idling is refused before the body acts and told at the next review`() {
        val bush = Cell(10, 64, 8, "dead_bush")
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = listOf(bush)))
        world.mind.answer = { _, index ->
            // Adopted beside an idle, which takes no step (r14 eta, untested in r15).
            val adopted = GoalChoice(GoalDisposition.ADOPT, STICK, 1, step = "wait for a stick")
            Intent.Idle to adopted.takeIf { index == 0 }
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(GoalEvidence.STEP_IDLE, refused.outcome)
        val decided = world.records.single { it.event == BodyEvent.DECIDED }
        assertEquals(GoalEvidence.STEP_IDLE, decided.goal?.outcome)

        world.review(ALPHA)
        world.awaitDecided(2)
        val told = world.mind.seen[1].lastRefusal
        assertEquals(world.mind.seen[0].requestId, told?.requestId)
        assertEquals(GoalEvidence.STEP_IDLE, told?.outcome)
        assertEquals(STICK, told?.successItem)
        assertEquals(0L, told?.held)
        assertEquals(1L, told?.capacity)
        // Told once.
        world.review(ALPHA)
        world.awaitDecided(3)
        assertNull(world.mind.seen[2].lastRefusal)
        assertTrue(world.digWrites().isEmpty(), "the body was never asked to act")
    }

    @Test
    fun `digging the only floor of a shaft is refused before the body is asked`() {
        // r13 eta dug its own support four times into a one-block shaft it could not flee from.
        val walls =
            listOf(64, 65).flatMap { y ->
                listOf(Cell(9, y, 8, "stone"), Cell(7, y, 8, "stone"), Cell(8, y, 9, "stone"), Cell(8, y, 7, "stone"))
            }
        val world = world(Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), blocks = walls))
        world.mind.answer = { perception, _ ->
            val support =
                perception.surroundings.single {
                    it.kind == ObservationKind.BLOCK && it.digSafe == false
                }
            Intent.Dig(support.ref) to null
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(EventTag.DIG_WOULD_TRAP, refused.outcome)
        val support = world.mind.seen.single().surroundings.single { it.digSafe == false }
        assertTrue(support.verticalOffset < 0, "the unsafe dig is the block underfoot: $support")
        assertTrue(world.digWrites().isEmpty(), "the body was never asked to dig")
    }

    @Test
    fun `a threat flight halts the routine dig in flight and arrives clear of the threat`() {
        // r13 eta's flight was refused behind a dig; r14 and r15 had no flight during routine work.
        val zombie =
            buildJsonObject {
                put("id", ZOMBIE_ID)
                put("what", "zombie")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 14.5)
            }
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(Cell(9, 64, 8, "stone")),
                entities = listOf(zombie),
                timeOfDay = NIGHT,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            when (perception.trigger) {
                is Trigger.Damaged -> {
                    val threat = perception.surroundings.first { it.what == "entity.minecraft.zombie" }
                    Intent.Flee(threat.ref) to null
                }
                else ->
                    Intent.Dig(
                        perception.surroundings.first {
                            it.what == "block.minecraft.stone" && it.verticalOffset > 0
                        }.ref,
                    ) to null
            }
        }
        world.review(ALPHA)
        // A bare-hand dig of stone holds the body for seconds; the flight cuts in while it is held.
        world.awaitWrite { it.status == DIG_STARTED }
        world.onMain { world.cognition.consider(ALPHA, Trigger.Damaged(2, "entity.minecraft.zombie")) }
        val retreat = world.awaitRecord { it.event == BodyEvent.RETREATING }
        assertEquals(EventTag.RETREAT_SAFE, retreat.outcome)
        val applied = world.records.filter { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(setOf(EventTag.DIG_CANCELLED, EventTag.ACCEPTED), applied.map { it.outcome }.toSet(), "$applied")
        assertTrue(world.digWrites().none { it.status == DIG_FINISHED }, "the stone was never broken")
    }

    @Test
    fun `two agents digging one block end in the server's one break and one lost race`() {
        // The v23 rehearsal's tick-3268 race, which r15 never met.
        val dirt = Cell(8, 64, 9, "dirt")
        val scene =
            Scene(
                listOf(Stand(KAPPA, 8.5, 64.0, 8.5), Stand(IOTA, 7.5, 64.0, 9.5)),
                blocks = listOf(dirt),
                serveBreaks = true,
            )
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            Intent.Dig(perception.surroundings.first { it.what == "block.minecraft.dirt" }.ref) to null
        }
        world.review(KAPPA)
        world.awaitWrite { it.agentId == KAPPA.value && it.status == DIG_STARTED }
        Thread.sleep(STAGGER_MS)
        world.review(IOTA)
        world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED && it.agentId == IOTA.value }
        world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED && it.agentId == KAPPA.value }
        val outcomes =
            world.records.filter { it.event == BodyEvent.INTENT_APPLIED }.associate { it.agentId to it.outcome }
        assertEquals(mapOf(KAPPA.value to EventTag.ACCEPTED, IOTA.value to EventTag.DIG_BLOCK_GONE), outcomes)
        assertEquals(listOf(KAPPA.value), world.breaks())
        assertTrue(world.records.none { it.event == BodyEvent.WITNESS_DISPUTED })
        assertTrue(
            world.records.filter { it.event == BodyEvent.WORLD_INTERACTION }.all {
                it.outcome == EventTag.SERVER_EFFECT_CONFIRMED
            },
        )
    }

    private fun world(scene: Scene): ScenarioWorld {
        val node = nodeOrNull()
        assumeTrue(node != null && Files.isDirectory(REPO.resolve("body/node_modules")), "node and body deps")
        return ScenarioWorld(requireNotNull(node), scene, temporary, closers)
    }

    private companion object {
        const val STICK = "item.minecraft.stick"
        const val ZOMBIE_ID = 41
        const val NIGHT = 14_000
        const val DIG_STARTED = 0
        const val DIG_FINISHED = 2
        const val STAGGER_MS = 300L
        val ALPHA = AgentId("agent-alpha")
        val KAPPA = AgentId("agent-kappa")
        val IOTA = AgentId("agent-iota")
    }
}
