package dev.loom.replay

import dev.loom.contract.GoalSummary
import dev.loom.contract.HorizonGoal
import dev.loom.contract.LoomJson
import dev.loom.contract.Perception
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * One committed experiment's evidence, read exactly as it was published, so an audit replay
 * drives the real code with the very perception a cited record carries rather than a copy.
 */
class CommittedEvidence(
    experiment: String,
) {
    private val records: Map<String, JsonObject> =
        Files
            .readAllLines(Path.of("..", "research", "experiments", experiment, "evidence.jsonl"))
            .filter { it.isNotBlank() }
            .map { LoomJson.parseToJsonElement(it).jsonObject }
            .associateBy { requireNotNull(it["recordId"]).jsonPrimitive.content }

    /** The record [recordId] names. */
    fun record(recordId: String): JsonObject = requireNotNull(records[recordId]) { "no record $recordId" }

    /** Every record, in published order. */
    val all: List<JsonObject> get() = records.values.toList()

    /** The perception the `perceived` record [recordId] carries, as the contract type. */
    fun perception(recordId: String): Perception =
        LoomJson.decodeFromJsonElement(Perception.serializer(), requireNotNull(record(recordId)["perception"]))

    /** The horizon the `decided` record [recordId] says its mind was shown; empty when it states none. */
    fun shownHorizon(recordId: String): List<HorizonGoal> =
        (record(recordId)["shown"] as? JsonObject)?.get("horizon")?.let {
            LoomJson.decodeFromJsonElement(ListSerializer(HorizonGoal.serializer()), it)
        }.orEmpty()

    /** The goal the `decided` record [recordId] says its mind was shown, if any. */
    fun shownGoal(recordId: String): GoalSummary? =
        (record(recordId)["shown"] as? JsonObject)?.get("goal")?.let {
            LoomJson.decodeFromJsonElement(GoalSummary.serializer(), it)
        }

    companion object {
        /** r23, the run whose audit these replays answer. */
        val R23: CommittedEvidence by lazy { CommittedEvidence("2026-10-16-openai-10-agent-10m-r23") }
    }
}
