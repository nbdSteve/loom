package dev.loom.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Enforces the single-source-of-truth property on the JVM side.
 *
 * Two independent guarantees, both failing the build on drift:
 *
 * 1. Every shared fixture under `contract/fixtures/valid` validates against
 *    `contract/loom.schema.json`, and every fixture under
 *    `contract/fixtures/invalid` is rejected by it.
 * 2. Every valid fixture round-trips through the kotlinx-serialization types
 *    and re-serializes to the exact bytes of the canonical fixture. The Rust
 *    side pins the same fixtures the same way, so byte-for-byte agreement on the
 *    fixtures is transitively byte-for-byte agreement between the two runtimes.
 */
class ContractConformanceTest {
    private val contractDir: Path =
        Paths.get(System.getProperty("user.dir"), "..", "contract").toRealPath()

    private val schemaRoot: JsonObject =
        Json.parseToJsonElement(contractDir.resolve("loom.schema.json").readText()).jsonObject

    private val factory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /**
     * Build a self-contained schema rooted at the named `$defs` entry, carrying
     * the original `$defs` and `$schema` so internal `$ref`s still resolve. This
     * is the same scoping the Rust conformance test performs, so both sides
     * validate each fixture against the identical definition.
     */
    private fun schemaFor(def: String): JsonSchema {
        val defs = schemaRoot["\$defs"]!!.jsonObject
        val rooted =
            buildMap<String, kotlinx.serialization.json.JsonElement> {
                putAll(defs[def]!!.jsonObject)
                put("\$schema", schemaRoot["\$schema"]!!)
                put("\$defs", defs)
            }
        return factory.getSchema(JsonObject(rooted).toString(), InputFormat.JSON)
    }

    private fun fixtureFiles(subdir: String): List<Path> {
        val dir = contractDir.resolve("fixtures").resolve(subdir)
        val files =
            Files.list(dir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.toList().sorted()
            }
        assertTrue(files.isNotEmpty(), "no fixtures found in $dir")
        return files
    }

    private fun defFor(path: Path): String = if (path.name.startsWith("perception-")) "Perception" else "Decision"

    @Test
    fun validFixturesConformToSchema() {
        for (path in fixtureFiles("valid")) {
            val schema = schemaFor(defFor(path))
            val messages = schema.validate(path.readText(), InputFormat.JSON)
            assertTrue(
                messages.isEmpty(),
                "valid fixture ${path.name} failed ${defFor(path)} schema validation: $messages",
            )
        }
    }

    @Test
    fun invalidFixturesAreRejectedBySchema() {
        for (path in fixtureFiles("invalid")) {
            val schema = schemaFor(defFor(path))
            val messages = schema.validate(path.readText(), InputFormat.JSON)
            assertFalse(
                messages.isEmpty(),
                "invalid fixture ${path.name} was wrongly accepted by ${defFor(path)} schema",
            )
        }
    }

    @Test
    fun validFixturesRoundTripByteForByte() {
        for (path in fixtureFiles("valid")) {
            // Canonical bytes are stored without a trailing newline; compare exactly.
            val canonical = path.readText().trimEnd('\n')
            val reserialized =
                if (defFor(path) == "Perception") {
                    LoomJson.encodeToString(LoomJson.decodeFromString<Perception>(canonical))
                } else {
                    LoomJson.encodeToString(LoomJson.decodeFromString<Decision>(canonical))
                }
            assertEquals(
                canonical,
                reserialized,
                "round-trip of ${path.name} was not byte-identical",
            )
        }
    }
}
