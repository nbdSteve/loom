package dev.loom.mind

import dev.loom.body.GoalEvidence
import dev.loom.contract.GoalChoice
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * R42's 18 refused plain completions, replayed through the ledger's own coherence rule from the exact
 * records `research/experiments/2026-10-16-openai-10-agent-10m-r42/evidence.jsonl` carries: each a
 * met dirt goal the schema offered only a completion, since more dirt served no chain, and each
 * refused as `goalCompletedWithoutNext` for the revise the schema no longer offered.
 */
class R42CompletionReplayTest {
    private val records: List<JsonObject> =
        Files
            .readAllLines(Path.of("..", "research", "experiments", R42, "evidence.jsonl"))
            .filter { it.isNotBlank() }
            .map { LoomJson.parseToJsonElement(it).jsonObject }

    private fun field(
        record: JsonObject,
        name: String,
    ): String? = record[name]?.jsonPrimitive?.content

    @Test
    fun `a met goal the schema offered only a completion is completed`() {
        val refused =
            records.filter {
                field(it, "event") == "intentApplied" && field(it, "outcome") == GoalEvidence.COMPLETED_WITHOUT_NEXT
            }
        assertEquals(18, refused.size)
        for (record in refused) {
            val key = field(record, "requestKey")
            val decided = records.single { field(it, "event") == "decided" && field(it, "requestKey") == key }
            val perceived = records.single { field(it, "event") == "perceived" && field(it, "requestKey") == key }
            val shown = decided["shown"]?.jsonObject.orEmpty()
            val sent = requireNotNull(perceived["perception"]).jsonObject.toMutableMap()
            for (name in listOf("goal", "lastRefusal", "horizon")) shown[name]?.let { sent[name] = it }
            val perception = LoomJson.decodeFromJsonElement(Perception.serializer(), JsonObject(sent))
            val decision = requireNotNull(decided["decision"]).jsonObject
            val choice = LoomJson.decodeFromJsonElement(GoalChoice.serializer(), requireNotNull(decision["goal"]))
            val intent = LoomJson.decodeFromJsonElement(Intent.serializer(), requireNotNull(decision["intent"]))
            val observed = GoalObservation.of(perception)
            val item = requireNotNull(perception.goal?.successItem)
            assertTrue(perception.goal?.progress?.conditionMet == true, "$key is met")
            assertTrue(!observed.servesChain(item), "$key: more $item serves no chain")
            assertEquals(null, incoherenceOf(perception.goal, choice, intent, observed), "$key ${record["recordId"]}")
        }
    }

    @Test
    fun `a met goal whose item a grounded recipe is made from is still answered with its next`() {
        // The same alpha review (body:00000652), had its horizon grounded a recipe made from dirt.
        val key = "tribe-alpha:loomaa88da42d7-sense-l4"
        val decided = records.single { field(it, "event") == "decided" && field(it, "requestKey") == key }
        val perceived = records.single { field(it, "event") == "perceived" && field(it, "requestKey") == key }
        val sent = requireNotNull(perceived["perception"]).jsonObject.toMutableMap()
        decided["shown"]?.jsonObject?.get("goal")?.let { sent["goal"] = it }
        val perception = LoomJson.decodeFromJsonElement(Perception.serializer(), JsonObject(sent))
        val item = requireNotNull(perception.goal?.successItem)
        val grounded =
            perception.copy(
                horizon =
                    listOf(
                        dev.loom.contract.HorizonGoal(
                            what = "item.minecraft.coarse_dirt",
                            grounding = dev.loom.contract.HorizonGrounding.RECIPE,
                            via = item,
                            most = 8,
                            frontier = false,
                        ),
                    ),
            )
        val observed = GoalObservation.of(grounded)
        assertTrue(observed.servesChain(item))
        val decision = requireNotNull(decided["decision"]).jsonObject
        val choice = LoomJson.decodeFromJsonElement(GoalChoice.serializer(), requireNotNull(decision["goal"]))
        val intent = LoomJson.decodeFromJsonElement(Intent.serializer(), requireNotNull(decision["intent"]))
        assertEquals(GoalEvidence.COMPLETED_WITHOUT_NEXT, incoherenceOf(grounded.goal, choice, intent, observed))
    }

    private companion object {
        const val R42 = "2026-10-16-openai-10-agent-10m-r42"
    }
}
