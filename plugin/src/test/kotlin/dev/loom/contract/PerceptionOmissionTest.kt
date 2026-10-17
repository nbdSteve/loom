package dev.loom.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the body's count bound cut, as the wire carries it.
 *
 * `ContractConformanceTest` pins the field's *shape* through the shared fixtures, and
 * `PerceptionBoundTest` pins the arithmetic that counts a cut. Neither joins them: the
 * production join lives in `capturePerception`, which cannot be reached without a server.
 * So the two facts pinned here are the ones a survival run depends on and nothing else
 * asserts.
 *
 * 1. The number the bound counted is the number that leaves this runtime, under the name
 *    the mind reads.
 * 2. "Required and non-negative" holds in the *binding*, not only in the schema. The
 *    schema refuses both shapes already, but a body serializes through these types and a
 *    mind reading a perception whose count was absent would be back to reasoning about
 *    "everything I can see" over a list that was trimmed.
 */
class PerceptionOmissionTest {
    private val invalidFixtures =
        Paths.get(System.getProperty("user.dir"), "..", "contract", "fixtures", "invalid").toRealPath()

    private fun perceptionOf(
        shown: List<Double>,
        omitted: Int,
    ) = Perception(
        requestId = "req-omission-1",
        agentId = "agent-zeta",
        trigger = Trigger.GoalReview,
        importance = DecisionImportance.ROUTINE,
        capturedTick = 18_000,
        selfState =
            SelfState(
                health = 20.0,
                food = 10,
                saturation = 0.0,
                position = listOf(0.0, 64.0, 0.0),
                inventorySummary = emptyList(),
                craftingOptions = emptyList(),
                craftingOptionsOmitted = 0,
                ownDrops = emptyList(),
                world = WorldState(dayPhase = DayPhase.DUSK, isDay = false, ticksUntilNightfall = 900),
            ),
        surroundings =
            shown.map {
                Observation(
                    kind = ObservationKind.ENTITY,
                    what = "cow",
                    distance = it,
                    direction = Bearing.NORTH,
                    verticalOffset = 0.0,
                    visible = true,
                    ref = "e${shown.indexOf(it)}",
                )
            },
        surroundingsOmitted = omitted.toLong(),
    )

    @Test
    fun theCountTheBoundCutIsTheCountTheWireCarries() {
        // Two hundred things a body could have described, every one of them in range, so only
        // the count bound can hold the snapshot down.
        val eligible = (1..200).map { it * 0.5 }
        val shown = eligible.take(MAX_PERCEPTION_OBSERVATIONS)
        val omitted = eligible.size - shown.size

        val line = LoomJson.encodeToString(perceptionOf(shown, omitted))

        val wire = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            MAX_PERCEPTION_OBSERVATIONS,
            wire.getValue("surroundings").jsonArray.size,
            "the count bound keeps its nearest 144",
        )
        assertEquals(
            56L,
            wire.getValue("surroundingsOmitted").jsonPrimitive.long,
            "the wire must carry the 56 the bound dropped, or the mind is told a trimmed scene is whole",
        )
        // And it survives the round trip a mind's decode performs.
        assertEquals(56L, LoomJson.decodeFromString<Perception>(line).surroundingsOmitted)
    }

    @Test
    fun aPerceptionThatDoesNotSayWhatItCutIsRefused() {
        // The old behaviour, as a body that had not been updated would send it. Absence
        // must not be readable as "nothing was cut".
        val absent = invalidFixtures.resolve("perception-missing-omission.json").readText().trim()

        assertFailsWith<SerializationException> { LoomJson.decodeFromString<Perception>(absent) }
    }

    @Test
    fun aNegativeOmissionCountIsRefused() {
        // A count below zero is not a smaller cut; it is a nonsense value that would
        // subtract from a cut the reader made for itself.
        val negative = invalidFixtures.resolve("perception-negative-omission.json").readText().trim()

        assertFailsWith<IllegalArgumentException> { LoomJson.decodeFromString<Perception>(negative) }
    }
}
