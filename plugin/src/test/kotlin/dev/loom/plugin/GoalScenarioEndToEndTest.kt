package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.EventTag
import dev.loom.body.GoalEvidence
import dev.loom.contract.DecisionImportance
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
                put("z", 17.5)
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
    fun `a ranged attacker past a full crowd is reported, named, and fled from`() {
        // v32 plains rehearsal 15: epsilon was shot by a skeleton standing past the creatures it
        // could name. Fifty sheep fill the body's 48-entity list nearer than the skeleton 20
        // blocks off; the server's witness names the skeleton as the attacker.
        val sheep =
            (0 until CROWD).map { index ->
                buildJsonObject {
                    put("id", CROWD_FIRST_ID + index)
                    put("what", "sheep")
                    put("x", 4.5 + index % 10)
                    put("y", 64.0)
                    put("z", 3.5 + index / 10)
                }
            }
        val skeleton =
            buildJsonObject {
                put("id", SKELETON_ID)
                put("what", "skeleton")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 28.5)
            }
        val scene = Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), entities = sheep + skeleton, timeOfDay = NIGHT)
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            val attacker = (perception.trigger as? Trigger.Damaged)?.attackerRef
            (attacker?.let { Intent.Flee(it) } ?: Intent.Idle) to null
        }
        // Damage the witness cannot attribute is perceived as before: the crowd fills the list.
        world.onMain { world.cognition.consider(ALPHA, Trigger.Damaged(2, "skeleton")) }
        world.awaitDecided(1)
        val unnamed = world.mind.seen[0]
        assertTrue(unnamed.surroundings.none { it.what == "entity.minecraft.skeleton" }, "cut by the crowd")
        assertEquals(null, (unnamed.trigger as Trigger.Damaged).attackerRef)
        // Witnessed with its attacker, the skeleton is reported past the crowd, named, and fled from.
        world.damaged(ALPHA, 3.0, "skeleton", sceneEntityUuid(SKELETON_ID))
        world.awaitDecided(2)
        val named = world.mind.seen[1]
        val attacker = requireNotNull((named.trigger as Trigger.Damaged).attackerRef)
        val shown = named.surroundings.single { it.ref == attacker }
        assertEquals("entity.minecraft.skeleton", shown.what)
        assertTrue(named.surroundings.count { it.kind == dev.loom.contract.ObservationKind.ENTITY } > CREATURE_CUT)
        assertTrue(attacker in dev.loom.mind.SurvivalOffer.of(named).immediate, "the attacker is immediate danger")
        val retreat = world.awaitRecord { it.event == BodyEvent.RETREATING && it.requestId == named.requestId }
        assertEquals(EventTag.RETREAT_SAFE, retreat.outcome, "$retreat")
        val applied = world.records.single { it.event == BodyEvent.INTENT_APPLIED && it.requestId == named.requestId }
        assertEquals(EventTag.ACCEPTED, applied.outcome, "$applied")
    }

    @Test
    fun `damage taken while a flight runs is told as one perception once it settles`() {
        // r24: 43 of 68 damage perceptions were coalesced behind open answers and never told.
        val zombie =
            buildJsonObject {
                put("id", ZOMBIE_ID)
                put("what", "zombie")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 12.5)
            }
        val scene = Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), entities = listOf(zombie), timeOfDay = NIGHT)
        val world = world(scene)
        world.mind.answer = { perception, _ ->
            // Once clear of the zombie the told damage names nothing to flee from, and is idled.
            val zombie = perception.surroundings.firstOrNull { it.what == "entity.minecraft.zombie" }
            (zombie?.let { Intent.Flee(it.ref) } ?: Intent.Idle) to null
        }
        world.onMain {
            world.cognition.consider(ALPHA, Trigger.Damaged(2, "entity.minecraft.zombie"))
        }
        world.awaitDecided(1)
        world.onMain {
            world.cognition.consider(ALPHA, Trigger.Damaged(3, "entity.minecraft.zombie"))
            world.cognition.consider(ALPHA, Trigger.Damaged(1, "entity.minecraft.zombie"))
        }
        world.awaitRecord { it.event == BodyEvent.RETREATING }
        world.awaitDecided(2)
        val told = world.mind.seen.map { it.trigger }
        assertEquals(Trigger.Damaged(4, "entity.minecraft.zombie"), told[1], "the held damage, summed: $told")
        val coalesced =
            world.records.count {
                it.event == BodyEvent.COGNITION_UNAVAILABLE && it.outcome == EventTag.DANGER_COALESCED
            }
        assertEquals(2, coalesced)
    }

    @Test
    fun `a weak agent's review beside a hunter twelve blocks off is pivotal`() {
        // r24 eta: a review at health 4.5 with a skeleton 9.8 blocks off was routine and crafted.
        val zombie =
            buildJsonObject {
                put("id", ZOMBIE_ID)
                put("what", "zombie")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 20.5)
            }
        val scene =
            Scene(listOf(Stand(ALPHA, 8.5, 64.0, 8.5)), entities = listOf(zombie), timeOfDay = NIGHT, health = 6.0)
        val world = world(scene)
        world.mind.answer = { _, _ -> Intent.Idle to null }
        world.review(ALPHA)
        world.awaitDecided(1)
        assertEquals(DecisionImportance.PIVOTAL, world.mind.seen[0].importance)
        assertEquals(6.0, world.mind.seen[0].selfState.health)
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

    @Test
    fun `beside an immediate hunter a dig is withheld before the body is asked, and a flight is taken`() {
        // r24: seven reviews beside a near hunter dug, crafted or walked; delta's crafted beside a spider.
        val zombie =
            buildJsonObject {
                put("id", ZOMBIE_ID)
                put("what", "zombie")
                put("x", 8.5)
                put("y", 64.0)
                put("z", 12.5)
            }
        val scene =
            Scene(
                listOf(Stand(ALPHA, 8.5, 64.0, 8.5)),
                blocks = listOf(Cell(9, 64, 8, "dirt")),
                entities = listOf(zombie),
                timeOfDay = NIGHT,
            )
        val world = world(scene)
        world.mind.answer = { perception, index ->
            val hunter = perception.surroundings.first { it.what == "entity.minecraft.zombie" }
            if (index == 0) {
                Intent.Dig(perception.surroundings.first { it.what == "block.minecraft.dirt" }.ref) to null
            } else {
                Intent.Flee(hunter.ref) to null
            }
        }
        world.review(ALPHA)
        val refused = world.awaitRecord { it.event == BodyEvent.INTENT_APPLIED }
        assertEquals(GoalEvidence.SURVIVAL_REQUIRED, refused.outcome)
        assertTrue(world.digWrites().isEmpty(), "the body was never asked to dig")
        assertEquals(DecisionImportance.PIVOTAL, world.mind.seen[0].importance)
        world.review(ALPHA)
        world.awaitDecided(2)
        world.awaitApplied(2)
        val fled = world.records.filter { it.event == BodyEvent.INTENT_APPLIED }[1]
        assertEquals(EventTag.ACCEPTED, fled.outcome)
        val answered = world.records.filter { it.event == BodyEvent.DECIDED }[1].goal?.outcome
        assertTrue(answered !in GoalEvidence.REFUSING, "the flight is taken: $answered")
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

/** A crowd past the body's 48-entity list, and the first id it is spawned under. */
private const val CROWD = 50
private const val CROWD_FIRST_ID = 500

/** The skeleton that shoots from past the crowd. */
private const val SKELETON_ID = 900

/** The nearest creatures the mind offers a flight from (`MAX_CREATURE_REFS` in `offered.rs`). */
private const val CREATURE_CUT = 16
