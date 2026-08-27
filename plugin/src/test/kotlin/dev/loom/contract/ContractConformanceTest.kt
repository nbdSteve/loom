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
import org.junit.jupiter.api.assertThrows
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

    /**
     * A ref outside its length is refused where it arrives, not only by the schema.
     *
     * Nothing on either wire is schema-validated at runtime, so `minLength` and
     * `maxLength` alone are rules a socket walks straight past. A blank ref names
     * nothing the body can resolve, and an over-long one is a handle no request ever
     * issued - a mind having invented one rather than named back what it was given.
     */
    @Test
    fun aRefOutsideItsLengthIsRefusedAtDecode() {
        val blank = fixtureText("invalid", "decision-flee-blank-ref.json")
        assertThrows<IllegalArgumentException>("a blank flee ref decoded into a Decision") {
            LoomJson.decodeFromString<Decision>(blank)
        }

        val overLong = fixtureText("invalid", "perception-observation-ref-too-long.json")
        assertThrows<IllegalArgumentException>("a nine-character observation ref decoded into a Perception") {
            LoomJson.decodeFromString<Perception>(overLong)
        }

        // The bound is the length and nothing else: a ref of exactly the maximum is an
        // ordinary one the body issues, and must still decode.
        val longest = overLong.replace("\"ref\":\"entity-42\"", "\"ref\":\"entity-4\"")
        assertFalse(overLong == longest, "the fixture no longer states the over-long ref to edit")
        assertEquals(
            "entity-4",
            LoomJson.decodeFromString<Perception>(longest).surroundings[0].ref,
        )
    }

    /**
     * The bound counts code points, as the schema and the Rust decoder do.
     *
     * `String.length` counts UTF-16 code units, so these five characters measure ten there
     * and the JVM would refuse a ref both the schema and Rust accept - the two runtimes
     * disagreeing about the one bound each states once, which no ASCII fixture can catch.
     */
    @Test
    fun aRefIsMeasuredInCharactersAndNotInUtf16Units() {
        // U+1F642, written as its surrogate pair so the source stays plain ASCII.
        val astral = "\uD83D\uDE42".repeat(5)
        assertEquals(5, astral.codePointCount(0, astral.length))
        assertEquals(10, astral.length, "the point of the case is that the two counts differ")

        val fixture = fixtureText("invalid", "perception-observation-ref-too-long.json")
        val astralRef = fixture.replace("\"ref\":\"entity-42\"", "\"ref\":\"$astral\"")
        assertFalse(fixture == astralRef, "the fixture no longer states the over-long ref to edit")

        assertEquals(
            astral,
            LoomJson.decodeFromString<Perception>(astralRef).surroundings[0].ref,
        )
    }

    private fun fixtureText(
        subdir: String,
        name: String,
    ): String = contractDir.resolve("fixtures").resolve(subdir).resolve(name).readText().trimEnd('\n')
}
