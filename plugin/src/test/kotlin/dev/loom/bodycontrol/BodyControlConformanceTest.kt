package dev.loom.bodycontrol

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.absoluteValue
import kotlin.math.ulp
import kotlin.streams.toList

/** How far from the origin a coordinate may be, mirroring the schema's `maximum`. */
private const val WORLD_BORDER = 30_000_000.0

/**
 * The magnitude at which `Double.toString` stops writing plain decimals.
 *
 * A formatter's own boundary rather than the contract's. Just inside it a coordinate is
 * written as `0.` plus two leading zeros plus a full seventeen-digit mantissa; below it the
 * same double is written with an exponent instead. The Node codec's own floor is three orders
 * of magnitude lower, which is why the two runtimes reach their widest text at different
 * values and why each derives its own rather than sharing a literal.
 */
private const val PLAIN_FORM_FLOOR = 1e-3

/** A magnitude whose exponential form carries a whole mantissa and three exponent digits. */
private const val DEEP_EXPONENT = 1e-300

/** The largest real strictly below [bound], which is longer to write down than [bound] is. */
private fun justInside(bound: Double): Double = bound - bound * 1.0.ulp / 2

/** The smallest real strictly above [bound], for the bounds a formatter has of its own. */
private fun justOutside(bound: Double): Double = bound + bound * 1.0.ulp / 2

/**
 * The legal coordinate whose decimal text is longest, which is *not* the largest one.
 *
 * A real's contribution to a frame is the length of its decimal text, and where a formatter
 * writes most is a property of the formatter rather than of the world border - so the answer
 * is taken over the formatter's own boundaries rather than named. Negative, because a minus
 * sign is a character too and a coordinate may carry one.
 *
 * This is what the worst-case frame below has to be measured with. Measured with an ordinary
 * position inside the world, which reads five characters shorter, it under-reported the widest
 * legal answer by 375 bytes across the twenty-five positions in it - on the one measurement
 * the list bounds are derived from.
 */
private val WIDEST_COORDINATE: Double =
    listOf(
        -justInside(WORLD_BORDER),
        -justOutside(PLAIN_FORM_FLOOR),
        -justOutside(DEEP_EXPONENT),
    ).maxBy { it.toString().length }

/** The same magnitude unsigned, since neither health nor saturation may be negative. */
private val WIDEST_VITAL: Double = WIDEST_COORDINATE.absoluteValue

/**
 * The JVM half of the body-control contract's conformance suite.
 *
 * Independent of the Node half by construction: it reads the same fixtures, but it
 * validates them against `contract/body-control.schema.json` with a real JSON Schema
 * validator and decodes them with hand-written kotlinx-serialization types that share
 * no code with `body/src/protocol.js`. Three descriptions of one wire, agreeing on
 * the same bytes, is the whole point - a generated binding would only prove the
 * generator ran.
 *
 * Five guarantees, each failing the build on drift:
 *
 * 1. Every fixture under `valid` validates against the schema definition its name
 *    names, and every fixture under `invalid` is rejected by it.
 * 2. Every valid fixture round-trips through these types and re-serializes to the
 *    exact bytes of the canonical fixture, so the JVM and Node encoders agree on key
 *    order as well as on shape.
 * 3. Every invalid fixture is refused by these types too, not only by the schema -
 *    which is the guarantee that matters, since nothing on this wire is
 *    schema-validated at runtime.
 * 4. Every fixture under `beyond-schema` is *accepted* by the schema and refused by
 *    these types, which is what makes that category a category rather than a place
 *    to file a case somebody could not get the schema to refuse.
 * 5. No fixture is skipped: a file this test cannot classify fails it.
 *
 * Everything here goes through the package's own decode and encode functions rather
 * than through a `Json` of its own, because two refusals are made before a binding can
 * reach its `init`: duplicate names are found in the raw line before parsing, and nulls
 * are found in the parse tree before kotlinx-serialization maps an optional null to an
 * absent field. A test that reached past those checks would prove the fixtures against
 * a decoder no running process uses.
 */
class BodyControlConformanceTest {
    private val fixtureRoot: Path =
        Paths.get(System.getProperty("user.dir"), "..", "contract").toRealPath()

    private val schemaRoot: JsonObject =
        Json.parseToJsonElement(fixtureRoot.resolve("body-control.schema.json").readText()).jsonObject

    private val factory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /**
     * A self-contained schema rooted at the named `$defs` entry, carrying the original
     * `$defs` and `$schema` so internal `$ref`s still resolve. The same scoping the
     * perception/decision conformance test performs, for the same reason: a fixture is
     * one frame, so it has to be validated against one definition rather than against
     * a document whose root describes nothing.
     */
    private fun schemaFor(def: String): JsonSchema {
        val defs = schemaRoot["\$defs"]!!.jsonObject
        val rooted =
            buildMap<String, JsonElement> {
                putAll(defs[def]!!.jsonObject)
                put("\$schema", schemaRoot["\$schema"]!!)
                put("\$defs", defs)
            }
        return factory.getSchema(JsonObject(rooted).toString(), InputFormat.JSON)
    }

    private fun fixtureFiles(subdir: String): List<Path> {
        val dir = fixtureRoot.resolve("fixtures").resolve("body-control").resolve(subdir)
        val files =
            Files.list(dir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.toList().sorted()
            }
        assertTrue(files.isNotEmpty(), "no body-control fixtures found in $dir")
        return files
    }

    /**
     * Which schema definition a fixture describes, from its name.
     *
     * A refusal is what the process writes instead of an answer, so `error-` files are
     * `BodyError` rather than a fourth response variant.
     */
    private fun defFor(path: Path): String? =
        when {
            path.name.startsWith("request-") -> "BodyRequest"
            path.name.startsWith("response-") -> "BodyResponse"
            path.name.startsWith("error-") -> "BodyError"
            else -> null
        }

    /** Canonical bytes are stored without a trailing newline; compare exactly. */
    private fun canonicalText(path: Path): String = path.readText().trimEnd('\n')

    private fun reserialize(
        def: String,
        text: String,
    ): String =
        when (def) {
            "BodyRequest" -> encodeBodyRequest(decodeBodyRequest(text))
            "BodyResponse" -> encodeBodyResponse(decodeBodyResponse(text))
            else -> encodeBodyError(decodeBodyError(text))
        }

    @Test
    fun everyFixtureIsClassified() {
        // A fixture whose name no decoder claims would be silently skipped by every
        // sweep below, which is worse than the case not existing: the corpus would
        // look like it covered something it never tested.
        for (path in FIXTURE_KINDS.flatMap { fixtureFiles(it) }) {
            assertTrue(
                defFor(path) != null,
                "${path.name} is named so that no schema definition claims it; " +
                    "prefix it request-, response- or error-",
            )
        }
    }

    @Test
    fun validFixturesConformToSchema() {
        for (path in fixtureFiles("valid")) {
            val def = defFor(path)!!
            val messages = schemaFor(def).validate(canonicalText(path), InputFormat.JSON)
            assertTrue(
                messages.isEmpty(),
                "valid fixture ${path.name} failed $def schema validation: $messages",
            )
        }
    }

    @Test
    fun invalidFixturesAreRejectedBySchema() {
        for (path in fixtureFiles("invalid")) {
            val def = defFor(path)!!
            val messages = schemaFor(def).validate(canonicalText(path), InputFormat.JSON)
            assertFalse(
                messages.isEmpty(),
                "invalid fixture ${path.name} was wrongly accepted by the $def schema",
            )
        }
    }

    @Test
    fun validFixturesRoundTripByteForByte() {
        for (path in fixtureFiles("valid")) {
            val canonical = canonicalText(path)
            assertEquals(
                canonical,
                reserialize(defFor(path)!!, canonical),
                "round-trip of ${path.name} was not byte-identical",
            )
        }
    }

    /**
     * Every invalid fixture is refused where it arrives, not only by the schema.
     *
     * This is the sweep that would actually have caught a bad frame in a running
     * system: `minLength`, `maxLength`, `pattern`, `minimum` and every `const` in the
     * schema are rules a socket walks straight past, so each one is restated as a type
     * rule and this asserts the restatement is complete.
     *
     * Both refusal shapes count. A field of the wrong type or an unknown key fails
     * kotlinx-serialization itself, and a well-typed value outside its bound fails an
     * `init` - so the assertion is that decoding throws, not which exception it throws.
     */
    @Test
    fun invalidFixturesAreRefusedAtDecode() {
        for (path in fixtureFiles("invalid")) {
            val def = defFor(path)!!
            val canonical = canonicalText(path)
            assertThrows<RuntimeException>("invalid fixture ${path.name} decoded into a $def") {
                reserialize(def, canonical)
            }
        }
    }

    /**
     * A frame the schema cannot refuse is still refused at decode.
     *
     * The `beyond-schema` category, and both halves of what makes it one. Each of these
     * frames satisfies every keyword the schema states and states a *relation* between two
     * of its own fields that is false - a saturation above the food level, inventory slots
     * that do not ascend, a refused meal naming food it did not eat. Draft 2020-12 cannot
     * compare two properties at all for some of them, and for the rest the schema
     * deliberately declines to, so that a reader meets one mechanism for every relation
     * rather than two: "the two codecs enforce it and the conformance suites assert it, as
     * they do for every other relation this schema states in prose".
     *
     * The schema half is asserted here rather than only the refusal, because that is what
     * keeps the category honest: a case the schema in fact refuses belongs under `invalid`,
     * where the sweeps above would prove more about it. Node's suite asserts the refusal
     * for its own codec and cannot assert this half - `body/` carries no validator.
     */
    @Test
    fun beyondSchemaFixturesAreAcceptedByTheSchemaAndRefusedAtDecode() {
        for (path in fixtureFiles("beyond-schema")) {
            val def = defFor(path)!!
            val canonical = canonicalText(path)
            assertSchemaAccepts(def, canonical, "${path.name} is filed as beyond the schema's reach")
            assertThrows<RuntimeException>("beyond-schema fixture ${path.name} decoded into a $def") {
                reserialize(def, canonical)
            }
        }
    }

    /**
     * A frame from another generation is refused before its fields are believed.
     *
     * The bound is the generation and nothing else, which is why this edits a fixture
     * that is otherwise valid rather than asserting on the invalid one alone: it has to
     * be the `protocol` field doing the refusing.
     */
    @Test
    fun aFrameFromAnotherGenerationIsRefused() {
        val canonical = fixtureText("valid", "request-handshake.json")
        assertEquals(BODY_CONTROL_PROTOCOL_VERSION, decodeBodyRequest(canonical).protocol)

        val current = "\"protocol\":$BODY_CONTROL_PROTOCOL_VERSION"
        val next = "\"protocol\":${BODY_CONTROL_PROTOCOL_VERSION + 1}"
        val nextGeneration = canonical.replace(current, next)
        assertFalse(canonical == nextGeneration, "the fixture no longer states the generation to edit")
        assertThrows<IllegalArgumentException>("a frame from the next generation decoded") {
            decodeBodyRequest(nextGeneration)
        }
        // And the generation this file superseded, which is the whole of what moving one buys: a
        // peer written against it finds out on its first frame rather than by misreading a field.
        val previous = canonical.replace(current, "\"protocol\":${BODY_CONTROL_PROTOCOL_VERSION - 1}")
        assertThrows<IllegalArgumentException>("a frame from a superseded generation decoded") {
            decodeBodyRequest(previous)
        }
    }

    @Test
    fun anotherGenerationIsRefusedBeforeAnyGenerationSpecificInterpretation() {
        val other = BODY_CONTROL_PROTOCOL_VERSION + 1
        val frames =
            listOf(
                """{"op":"future","protocol":$other,"requestId":"r","agentId":"a"}""",
                """{"op":"future","protocol":$other,"requestId":"r","agentId":"a","payload":null}""",
            )
        val decoders: Map<String, (String) -> Any> =
            mapOf(
                "request" to ::decodeBodyRequest,
                "response" to ::decodeBodyResponse,
                "error" to ::decodeBodyError,
            )
        for ((name, decode) in decoders) {
            for (frame in frames) {
                val refusal =
                    assertThrows<IllegalArgumentException>("$name interpreted another generation") {
                        decode(frame)
                    }
                assertEquals("protocol must be $BODY_CONTROL_PROTOCOL_VERSION", refusal.message)
            }
        }
    }

    /**
     * An id's bound is counted in code points, as the schema and the Node codec count it.
     *
     * `String.length` counts UTF-16 code units, so these five characters measure ten
     * there: measuring in units would refuse an id at half the documented length and
     * make this runtime disagree with the other about the one bound each states once.
     * No ASCII fixture can catch that, and the pattern refuses these characters anyway -
     * what is pinned is that the *count* is the one the schema means.
     */
    @Test
    fun anIdIsMeasuredInCodePointsAndNotInUtf16Units() {
        // U+1F642, written as its surrogate pair so the source stays plain ASCII.
        val astral = "\uD83D\uDE42".repeat(5)
        assertEquals(5, astral.codePointCount(0, astral.length))
        assertEquals(10, astral.length, "the point of the case is that the two counts differ")

        val longest = "r".repeat(MAX_ID_CHARS)
        val canonical = fixtureText("valid", "request-handshake.json")
        val atTheBound = canonical.replace("\"requestId\":\"req-1\"", "\"requestId\":\"$longest\"")
        assertFalse(canonical == atTheBound, "the fixture no longer states the requestId to edit")
        assertEquals(longest, decodeBodyRequest(atTheBound).requestId)

        val pastIt = canonical.replace("\"requestId\":\"req-1\"", "\"requestId\":\"${longest}x\"")
        assertThrows<IllegalArgumentException>("an id one character past the bound decoded") {
            decodeBodyRequest(pastIt)
        }
    }

    /**
     * A refusal's error can only be a code from the closed set, which keeps an
     * exception string, a path, a machine name or a token out of that field.
     *
     * The invalid sweep already proves the two free-text fixtures are refused; this
     * states the property they are fixtures *of*, so a future runtime reading these
     * types cannot mistake the field for a message.
     */
    @Test
    fun aRefusalCarriesNoFreeText() {
        val canonical = fixtureText("valid", "error-unknown-operation.json")
        assertEquals(ErrorCode.UNKNOWN_OPERATION, decodeBodyError(canonical).error)

        for (text in listOf("Error: connect ECONNREFUSED 127.0.0.1:25565", "/Users/someone/loom", "host.local")) {
            val leak = canonical.replace("\"error\":\"unknownOperation\"", "\"error\":\"$text\"")
            assertFalse(canonical == leak, "the fixture no longer states the code to edit")
            val refusal =
                assertThrows<IllegalArgumentException>("free text decoded into an ErrorCode") {
                    decodeBodyError(leak)
                }
            assertFalse(
                (refusal.message ?: "").contains(text),
                "the refusal repeated the free text the frame carried",
            )
        }
    }

    /**
     * Nothing kotlinx-serialization says about a frame reaches a caller of this package.
     *
     * The library reports a decode failure by quoting the frame - the text at an offset, the
     * unknown key it met, the enum value it could not match, the discriminator it could not
     * dispatch on - and this wire's whole stated guarantee is that no byte of a peer's frame
     * leaves through a refusal. So the marker below is planted in each of those positions in
     * turn, and none of them may come back out.
     *
     * The exception type is asserted as well as the message: a raw `SerializationException`
     * escaping is what used to carry all of it, and this test is the one that failed to catch
     * that, because it asked for exactly the exception whose message was the leak.
     */
    @Test
    fun noParserOrBindingFailureRepeatsTheFrame() {
        val marker = "s3cret-/Users/someone-host.local"
        val canonical = fixtureText("valid", "response-status.json")
        val leaks =
            mapOf(
                "an unparseable line" to """{"protocol":$BODY_CONTROL_PROTOCOL_VERSION,"agentId":"$marker",""",
                "an unknown key" to canonical.replace("{", """{"$marker":1,"""),
                "an unmatched enum value" to canonical.replace("\"state\":\"ready\"", "\"state\":\"$marker\""),
                "an unknown discriminator" to canonical.replace("\"op\":\"status\"", "\"op\":\"$marker\""),
                "a value of the wrong type" to canonical.replace("\"uptimeMs\":4210", "\"uptimeMs\":\"$marker\""),
            )
        for ((shape, line) in leaks) {
            assertFalse(line == canonical, "$shape no longer alters the fixture")
            val refusal =
                assertThrows<IllegalArgumentException>("$shape decoded") { decodeBodyResponse(line) }
            assertFalse(
                refusal is SerializationException,
                "$shape came back as the library's own exception, whose message is the frame",
            )
            assertFalse((refusal.message ?: "").contains(marker), "$shape repeated the frame")
            assertNull(refusal.cause, "$shape attached a cause, whose message is the frame")
        }
    }

    /**
     * This package's own refusals keep the bounded sentences they chose.
     *
     * The catch that translates the library's failures names `SerializationException` rather
     * than its `IllegalArgumentException` supertype for exactly this reason: widened by one
     * step it would swallow every `require` in `BodyControl.kt` too, and a wire whose every
     * refusal said the same thing would be closed but useless. Both refusals below are
     * bounded already - they state what was expected and never what arrived.
     */
    @Test
    fun aBoundedRefusalOfThisPackagesOwnKeepsItsMessage() {
        val handshake = fixtureText("valid", "response-handshake.json")
        val shortUsername = handshake.replace("\"loom_alpha\"", "\"ab\"")
        assertFalse(handshake == shortUsername, "the fixture no longer states the username to edit")
        assertEquals(
            "username is not a name Minecraft would accept",
            assertThrows<IllegalArgumentException> { decodeBodyResponse(shortUsername) }.message,
        )

        val status = fixtureText("valid", "response-status.json")
        val blankAgent = status.replace("\"agentId\":\"agent-alpha\"", "\"agentId\":\"\"")
        assertFalse(status == blankAgent, "the fixture no longer states the agentId to edit")
        assertEquals(
            "agentId must be 1..64 characters",
            assertThrows<IllegalArgumentException> { decodeBodyResponse(blankAgent) }.message,
        )
    }

    /**
     * A frame past the byte bound is refused before anything walks it.
     *
     * The bound was stated and exported and enforced by no decoder at all, on the theory
     * that the framing layer which would one day read the socket was the place for it - so
     * every public decode read an unbounded line, and a controller calling one itself would
     * have bypassed that layer anyway. Leading whitespace is the padding because JSON admits
     * it between tokens: the line is legal in every way except its size, which is what makes
     * the refusal about the size.
     *
     * The bound is counted in UTF-8 bytes rather than characters, so the astral case is the
     * one worth pinning: 4200 of those characters are 8400 UTF-16 units and 16 800 bytes, and
     * a runtime measuring either of the smaller numbers would carry twice or four times what
     * it promised.
     */
    @Test
    fun aFrameOverTheByteBoundIsRefusedUnread() {
        val canonical = fixtureText("valid", "request-handshake.json")
        val room = MAX_BODY_CONTROL_FRAME_BYTES - canonical.toByteArray(Charsets.UTF_8).size

        val atTheBound = " ".repeat(room) + canonical
        assertEquals(MAX_BODY_CONTROL_FRAME_BYTES, atTheBound.toByteArray(Charsets.UTF_8).size)
        assertEquals("agent-alpha", decodeBodyRequest(atTheBound).agentId, "a frame at the bound was refused")

        val pastIt = " ".repeat(room + 1) + canonical
        for ((name, decode) in decoders()) {
            assertEquals(
                "a body-control frame is longer than this wire will carry",
                assertThrows<IllegalArgumentException>("$name read a frame past the byte bound") {
                    decode(pastIt)
                }.message,
            )
        }

        // Inside the bound as UTF-16 units and past it as bytes, which is the whole difference:
        // nine thousand astral characters are eighteen thousand units and thirty-six thousand
        // bytes, so
        // this case is refused only by the second clause of the measurement.
        val astral = canonical.replace("\"agent-alpha\"", "\"${"🙂".repeat(9000)}\"")
        assertTrue(astral.length <= MAX_BODY_CONTROL_FRAME_BYTES, "the case must be inside the bound in units")
        assertTrue(
            astral.toByteArray(Charsets.UTF_8).size > MAX_BODY_CONTROL_FRAME_BYTES,
            "the case must be past the bound in bytes",
        )
        assertEquals(
            "a body-control frame is longer than this wire will carry",
            assertThrows<IllegalArgumentException> { decodeBodyRequest(astral) }.message,
        )
    }

    /**
     * A frame nesting past the depth bound is refused without the stack being spent on it.
     *
     * This is not the byte bound in another form, and the deep case is deliberately the very
     * deepest that bound admits rather than a comfortably round number. The margin between the
     * two bounds turns out to be none: with the depth guard removed this exact line raises a
     * `StackOverflowError` off the walk for explicit nulls, which spends several frames a level,
     * while a line of four thousand - also inside the byte bound - survives and is refused for
     * its unknown key instead. So a round number here would pass with the guard gone, and the
     * regression assertion is `assertThrows<IllegalArgumentException>` itself: a
     * `StackOverflowError` is an `Error` and not an exception, so nothing would catch it.
     */
    @Test
    fun aFrameNestingPastTheDepthBoundIsRefusedWithoutRecursing() {
        val canonical = fixtureText("valid", "request-handshake.json")
        val legal = MAX_BODY_CONTROL_FRAME_DEPTH
        val deepest = MAX_BODY_CONTROL_FRAME_BYTES / 2
        val deep = "[".repeat(deepest) + "]".repeat(deepest)
        assertTrue(deep.toByteArray(Charsets.UTF_8).size <= MAX_BODY_CONTROL_FRAME_BYTES, "the case must fit the bound")

        for ((name, decode) in decoders()) {
            assertEquals(
                "a body-control frame nests deeper than this wire will read",
                assertThrows<IllegalArgumentException>("$name descended a deeply nested frame") {
                    decode(deep)
                }.message,
            )
        }

        // The case that separates the bound from anything that would refuse a deep line
        // anyway: an otherwise entirely legal request whose one unknown key nests past the
        // bound. The object itself is one level, so `legal` arrays inside it is one too many.
        assertEquals(
            "a body-control frame nests deeper than this wire will read",
            assertThrows<IllegalArgumentException> { decodeBodyRequest(nesting(canonical, legal)) }.message,
        )

        // A bracket inside a string is a character and not a level, or the bound would be
        // reachable by naming an agent `[[[[[[[[[`.
        val bracketed = canonical.replace("\"agent-alpha\"", "\"${"[".repeat(64)}\"")
        assertThrows<IllegalArgumentException>("a bracketed id was read as nesting") { decodeBodyRequest(bracketed) }
            .also { assertFalse((it.message ?: "").contains("nests"), "a string's brackets were counted as depth") }

        // At the bound the depth check is not what refuses; the unknown key is.
        assertFalse(
            (
                assertThrows<IllegalArgumentException> {
                    decodeBodyRequest(nesting(canonical, legal - 1))
                }.message ?: ""
            ).contains("nests"),
            "a frame at the depth bound was refused for its depth",
        )
    }

    /** [canonical] with one extra key whose value nests [levels] deep, for the depth cases. */
    private fun nesting(
        canonical: String,
        levels: Int,
    ): String = canonical.replace("{", """{"x":${"[".repeat(levels)}1${"]".repeat(levels)},""")

    /** The three public decoders, so a property of the ingress is stated against all of them. */
    private fun decoders(): Map<String, (String) -> Any> =
        mapOf(
            "request" to ::decodeBodyRequest,
            "response" to ::decodeBodyResponse,
            "error" to ::decodeBodyError,
        )

    /**
     * A refusal omits a requestId it never received, rather than carrying a blank one.
     *
     * The presence of the field is what says "this is about that request"; a blank or
     * null one would let a controller match a refusal to a request that does not exist.
     */
    @Test
    fun aRefusalWithNoReadableRequestIdOmitsTheField() {
        val canonical = fixtureText("valid", "error-malformed-frame.json")
        assertFalse(canonical.contains("requestId"), "the fixture is meant to carry no requestId")

        val decoded = decodeBodyError(canonical)
        assertEquals(null, decoded.requestId)
        assertEquals(canonical, encodeBodyError(decoded), "an absent requestId came back as a field")
    }

    /**
     * A member named twice is refused on the bytes, before anything parses them.
     *
     * This cannot be a fixture: a duplicate never reaches the JSON Schema data model, so
     * `invalidFixturesAreRejectedBySchema` - which asserts the schema refuses every file
     * under `invalid` - would fail on one. It is therefore a codec test on both sides of
     * the wire by design, and it is the codec rather than the parser that is pinned: the
     * unaltered frame decodes, and the same frame with one *known* member repeated with a
     * second *legal* value does not.
     *
     * The refusal must also quote neither value. Which of the two a parser would have
     * believed is exactly what is ambiguous, and a member name and a field value are both
     * peer data, so repeating either is how this protocol's error-output guarantee would
     * be lost.
     */
    @Test
    fun aMemberNamedTwiceIsRefusedOnTheBytes() {
        val canonical = fixtureText("valid", "request-handshake.json")
        assertEquals("agent-alpha", decodeBodyRequest(canonical).agentId)

        val once = "\"agentId\":\"agent-alpha\""
        val namedTwice = canonical.replace(once, "$once,\"agentId\":\"a\"")
        assertFalse(canonical == namedTwice, "the fixture no longer states the member to repeat")
        val refusal =
            assertThrows<IllegalArgumentException>("a frame naming agentId twice decoded") {
                decodeBodyRequest(namedTwice)
            }
        val said = refusal.message ?: ""
        assertFalse(said.contains("agentId"), "the refusal repeated the member name")
        assertFalse(said.contains("agent-alpha"), "the refusal repeated a value from the frame")
    }

    /**
     * An explicit null is refused, rather than read as the absent field it decodes to.
     *
     * `explicitNulls = false` is what keeps an absent [BodyError.requestId] out of the
     * frame on the way out, and it is also why `"requestId":null` arrives on the way in as
     * an *absent* field - so a refusal naming no request and a refusal naming a null one
     * decoded to the same value, and the schema's refusal of the second was a rule the
     * socket walked past. The fixture is pinned against the schema by the sweeps above;
     * this states the property, and that the same frame without the null still decodes.
     */
    @Test
    fun anExplicitNullIsNotAnAbsentField() {
        val canonical = fixtureText("valid", "error-unknown-operation.json")
        assertEquals("req-4", decodeBodyError(canonical).requestId)

        val nulled = canonical.replace("\"requestId\":\"req-4\"", "\"requestId\":null")
        assertFalse(canonical == nulled, "the fixture no longer states the requestId to null")
        assertThrows<IllegalArgumentException>("an explicit null decoded as an absent field") {
            decodeBodyError(nulled)
        }
    }

    /**
     * A count is bounded above by the largest integer both runtimes can hold.
     *
     * The other end of this wire is a Node process, so a `Long` this side would encode
     * happily is a number the Node codec cannot read back: bounding at what the JVM can
     * hold would make a frame legal to the contract and to one runtime and unreadable to
     * the other. The bound is restated here as a literal rather than read from the
     * constant alone, because the whole point is that both bindings state the same number.
     */
    @Test
    fun aCountIsBoundedAtWhatBothRuntimesCanHold() {
        assertEquals(9_007_199_254_740_991L, MAX_CONTROL_COUNT)
        assertEquals(MAX_CONTROL_COUNT, statusReport(MAX_CONTROL_COUNT, MAX_CONTROL_COUNT, 1).uptimeMs)

        assertThrows<IllegalArgumentException>("a count past what Node can hold was accepted") {
            statusReport(MAX_CONTROL_COUNT + 1, 0, 1)
        }
        assertThrows<IllegalArgumentException>("a count past what Node can hold was accepted") {
            statusReport(MAX_CONTROL_COUNT + 1, MAX_CONTROL_COUNT + 1, MAX_CONTROL_COUNT + 1)
        }
    }

    /**
     * A status report's three numbers have to be measurable together, not only one at a
     * time.
     *
     * Two of the ways they can be nonsense are relations rather than ranges: a process
     * starts in a state, so `stateMs` can never exceed `uptimeMs`, and answering the
     * status request is itself handling one, so a report of zero has miscounted its own
     * answer. 2020-12 cannot compare two properties, so the first is a codec rule on both
     * sides and this is the JVM half of it; equality must stay legal, since a process in
     * its first state has been there for its whole life.
     */
    @Test
    fun aStatusReportsThreeNumbersMustBeMeasurableTogether() {
        assertEquals(4210, statusReport(4210, 4210, 1).stateMs, "stateMs == uptimeMs is a legal report")

        assertThrows<IllegalArgumentException>("a state older than the process was accepted") {
            statusReport(4210, 4211, 2)
        }
        assertThrows<IllegalArgumentException>("a report that had not counted its own answer was accepted") {
            statusReport(4210, 1980, 0)
        }
    }

    /**
     * A frame cannot be built carrying an id a frame may not carry.
     *
     * The refusal is the contract's own [BodyRequest] `init`, reached by construction as
     * well as at decode, so a runtime that builds a frame in memory and writes it out
     * cannot put on the wire what its own decoder would refuse.
     *
     * What the bound actually rules out includes slashes, backslashes, whitespace,
     * quotes and newlines - which keeps a local path, a credential path or an ordinary
     * stack trace out of a frame and out of a log. It is deliberately *not* "anything
     * that resembles a machine": a dotted, whitespace-free token is a legal id by design,
     * since that is the shape an ordinary agent name takes, and narrowing the pattern to
     * refuse one would refuse legitimate names.
     */
    @Test
    fun aFrameCannotBeBuiltWithAnIdAFrameMayNotCarry() {
        val illegal =
            listOf(
                "/tmp/secret",
                "/Users/alice/.aws/credentials\nTOKEN",
                "C:\\Users\\alice\\secrets.txt",
                "Error: connect ECONNREFUSED 127.0.0.1:25565",
                "has space",
                "",
            )
        for (id in illegal) {
            assertThrows<IllegalArgumentException>("a request was built with requestId <$id>") {
                BodyRequest.Status(BODY_CONTROL_PROTOCOL_VERSION, id, "agent-alpha")
            }
            assertThrows<IllegalArgumentException>("a request was built with agentId <$id>") {
                BodyRequest.Status(BODY_CONTROL_PROTOCOL_VERSION, "req-1", id)
            }
        }

        val dotted = "build-host-07.internal.example"
        assertEquals(
            dotted,
            BodyRequest.Status(BODY_CONTROL_PROTOCOL_VERSION, "req-1", dotted).agentId,
            "a dotted, whitespace-free token is a legal id by design",
        )
    }

    /**
     * A whole number is a whole number however the frame spells it.
     *
     * To the schema and to `JSON.parse` an integer is any number with a zero fractional
     * part, so `1.0` and `1e0` are two spellings of `1` and the Node binding reads both.
     * kotlinx reads the raw literal, so it took the exponent spellings and refused the
     * decimal-point ones - a frame legal to the contract and readable by one runtime and
     * not the other, which is the one thing three hand-written bindings of one wire exist
     * to prevent. Every spelling below is accepted by the schema, so this case is the JVM
     * half of an agreement the fixtures cannot state: a valid fixture must re-encode to
     * its own bytes, and a zero-fraction spelling of the generation re-encodes to the plain one.
     */
    @Test
    fun `a whole number is read the same however it is spelled`() {
        val generation = BODY_CONTROL_PROTOCOL_VERSION
        val spellings =
            mapOf(
                "$generation" to "the plain spelling both runtimes always read",
                "$generation.0" to "a zero fraction is an integer to the schema",
                "$generation.00" to "and stays one however many zeroes follow",
                "${generation}e0" to "an exponent form kotlinx already happened to read",
                "${generation}E0" to "whose exponent marker may be either case",
                "0.${generation}e1" to "a fraction the exponent makes whole again",
            )
        for ((literal, why) in spellings) {
            val line = handshakeFrameWithProtocol(literal)
            assertSchemaAccepts("BodyRequest", line, "protocol $literal: $why")
            assertEquals(BODY_CONTROL_PROTOCOL_VERSION, decodeBodyRequest(line).protocol, "protocol $literal: $why")
        }

        val statusLine = statusFrameWithCounts("1e3", "1.0", "2.0e0")
        assertSchemaAccepts("BodyResponse", statusLine, "three counts, three spellings")
        val counts = decodeBodyResponse(statusLine) as BodyResponse.StatusReport
        assertEquals(1000L, counts.uptimeMs, "an exponent spelling reaches the value it names")
        assertEquals(1L, counts.stateMs, "and so does a decimal-point one")
        assertEquals(2L, counts.requestsHandled, "and so does the two together")
    }

    /**
     * A number with a fraction left in it is refused, whatever it is spelled like.
     *
     * This is the half of the alignment that must not slip: reading `1.5` as `1` would
     * make the JVM *looser* than the schema rather than equal to it, and a `requestsHandled`
     * that had been rounded is a tally no run could trust. The refusal is by exact
     * integrality rather than by looking for a `.`, which is why `1.5e0` and `15e-1` are
     * classified opposite ways here.
     */
    @Test
    fun `a fraction is refused however it is spelled`() {
        val generation = BODY_CONTROL_PROTOCOL_VERSION
        val fractional =
            listOf("$generation.5", "$generation.5e0", "0.5", "$generation.0000001", "${generation * 10 + 5}e-1")
        for (literal in fractional) {
            val line = handshakeFrameWithProtocol(literal)
            assertSchemaRefuses("BodyRequest", line, "protocol $literal is not a whole number")
            assertThrows<IllegalArgumentException>("protocol $literal is not a whole number") {
                decodeBodyRequest(line)
            }
        }

        val fractionalCount = statusFrameWithCounts("10", "5.5", "1")
        assertSchemaRefuses("BodyResponse", fractionalCount, "a count with a fraction left in it")
        assertThrows<IllegalArgumentException>("a count with a fraction left in it is not a count") {
            decodeBodyResponse(fractionalCount)
        }

        val whole = decodeBodyResponse(statusFrameWithCounts("20e-1", "1", "1"))
        assertEquals(
            2L,
            (whole as BodyResponse.StatusReport).uptimeMs,
            "a negative exponent is refused for 15e-1 and accepted for 20e-1, since the test is the fraction",
        )
    }

    /**
     * A literal whose whole purpose is to be expensive is refused before it is built.
     *
     * `1e2000000000` is twelve characters, so no length bound can catch it: `BigDecimal`
     * stores it as an unscaled `1` and a scale of minus two billion, and materialises the
     * two billion digits only when something asks for the integer value. The guard is
     * therefore on the integer-digit count, taken from the scale, and a regression that
     * removed it would not fail slowly - it would exhaust the heap - so the assertion is
     * preemptively timed the way the cognition transport's drip test is.
     *
     * The negative exponents are here because this test held only the positive ones, and
     * that is precisely how the same cost shipped through the other end of the same
     * guard: `1e-100000000` is twelve characters whose scale is a hundred million, which
     * makes `precision - scale` hugely negative and so passes any ceiling. It spent
     * forty-seven seconds of one core dividing by `10^scale` before refusing a frame that
     * Node refuses on the bytes. `1e-999999999` is deliberately beside it: that one
     * `BigInteger` happens to refuse at once for its own reasons, which is why the band
     * just below it looked safe.
     */
    @Test
    fun `a number cannot ask for more than its literal spells`() {
        assertTimeoutPreemptively(NUMBER_PATIENCE) {
            val amplifying =
                listOf(
                    "1e2000000000",
                    "1e2147483647",
                    "-1e2000000000",
                    "1e999999999",
                    "1e-10000000",
                    "1e-100000000",
                    "-1e-100000000",
                    "1e-999999999",
                    "1.0000000000000000000e-999999999",
                )
            for (literal in amplifying) {
                assertThrows<IllegalArgumentException>("$literal asks for more digits than this wire will build") {
                    decodeBodyRequest(handshakeFrameWithProtocol(literal))
                }
            }
            assertThrows<IllegalArgumentException>("a count is guarded by the same reading") {
                decodeBodyResponse(statusFrameWithCounts("1e2000000000", "0", "1"))
            }
            assertThrows<IllegalArgumentException>("and by the same reading from the other end") {
                decodeBodyResponse(statusFrameWithCounts("1e-100000000", "0", "1"))
            }

            // Directly, because kotlinx would refuse these literals for its own reasons
            // too: the guard is what makes the refusal cheap rather than fatal, and only
            // the parser it guards can say so.
            assertNull(integralLongOrNull("1e2000000000"), "a two-billion-digit count is never built")
            assertNull(integralIntOrNull("1e2000000000"), "nor a two-billion-digit protocol")
            assertNull(integralLongOrNull("1e-100000000"), "nor a hundred-million-digit division")
            assertNull(integralIntOrNull("1e-100000000"), "on either field")
            assertNull(integralLongOrNull("1".repeat(64)), "nor a literal longer than this wire reads")

            // The floor refuses only what could not have been whole, so the spellings with
            // a fractional tail this wire accepts still read - including the one whose
            // exponent is itself negative, which is the case a cruder floor would take.
            assertEquals(1L, integralLongOrNull("1.0"), "while an ordinary integral spelling still reads")
            assertEquals(2L, integralLongOrNull("20e-1"), "and so does a negative exponent that is affordable")
            assertEquals(0L, integralLongOrNull("0e-2000000000"), "and a zero is answered before any of it")
        }
    }

    /**
     * A value past what the field holds is still refused, and so is a quoted number.
     *
     * Widening how a number may be *spelled* must not widen what it may *be*. The first
     * two cases reach the field's own bound by a spelling that hides it, so a reader
     * cannot tell the bound was met by looking for sixteen digits; the third is the
     * type rule this alignment could most easily have lost, since `"1"` is a string and
     * a string is not a number in any spelling.
     */
    @Test
    fun `a spelling cannot smuggle a value the field cannot hold`() {
        assertThrows<IllegalArgumentException>("one past the shared maximum is over it, spelled either way") {
            decodeBodyResponse(statusFrameWithCounts("9.007199254740992e15", "0", "1"))
        }

        assertThrows<IllegalArgumentException>("a whole number that is not this generation is still refused") {
            decodeBodyRequest(handshakeFrameWithProtocol("${BODY_CONTROL_PROTOCOL_VERSION + 1}.0"))
        }

        val generation = BODY_CONTROL_PROTOCOL_VERSION
        for (quoted in listOf("\"$generation\"", "\"$generation.0\"")) {
            assertThrows<IllegalArgumentException>("$quoted is a string, and a string is not a number") {
                decodeBodyRequest(handshakeFrameWithProtocol(quoted))
            }
        }

        assertEquals(
            MAX_CONTROL_COUNT,
            (decodeBodyResponse(statusFrameWithCounts("9.007199254740991e15", "0", "1")) as BodyResponse.StatusReport)
                .uptimeMs,
            "the bound itself is reachable by an exponent spelling",
        )
    }

    /**
     * Every spelling in the shared corpus reads here exactly as the schema reads it.
     *
     * The two lists above are this suite's own, and `body/test/protocol.test.js` has its
     * own - which is how two parity defects shipped inside the commits written to remove
     * them: neither list held a literal the other runtime disagreed about, so each proved
     * itself against itself. `contract/fixtures/body-control/numeric-spellings.json` is one
     * list of frames both suites sweep, over the same bytes, against the same stated
     * verdict and value. It is not a fixture, and deliberately not under `valid` or
     * `invalid`, for the reason the header of the accepting test above gives: a valid
     * fixture must re-encode to its own bytes, and a zero-fraction spelling of the
     * generation re-encodes to the plain one.
     *
     * The schema's verdict is measured here with the real validator rather than trusted
     * from the corpus, so a case whose `accepted` is wrong fails this test instead of
     * quietly teaching both runtimes the same mistake.
     */
    @Test
    fun `every shared numeric spelling is read the way the schema reads it`() {
        val corpus =
            Json.parseToJsonElement(
                fixtureRoot.resolve("fixtures").resolve("body-control").resolve("numeric-spellings.json").readText(),
            ).jsonObject
        val cases = corpus["cases"]!!.jsonArray
        assertEquals(SHARED_NUMERIC_CASES, cases.size, "the shared numeric corpus has gained or lost cases")
        for (element in cases) {
            val case = element.jsonObject
            val why = case["why"]!!.jsonPrimitive.content
            val line = case["line"]!!.jsonPrimitive.content
            val request = case["direction"]!!.jsonPrimitive.content == "request"
            val def = if (request) "BodyRequest" else "BodyResponse"
            val field = case["field"]!!.jsonPrimitive.content
            val accepted = case["accepted"]!!.jsonPrimitive.boolean
            // A validator reads the bytes through a parser that rounds, so a fine enough
            // fraction reaches it as a whole number and it accepts what the schema's own
            // reading refuses. The corpus states that where it happens, and the sweep
            // measures it rather than being weakened by it - the decoder still refuses.
            if (case["validatorAccepts"]?.jsonPrimitive?.boolean ?: accepted) {
                assertSchemaAccepts(def, line, why)
            } else {
                assertSchemaRefuses(def, line, why)
            }
            if (accepted) {
                assertEquals(case["value"]!!.jsonPrimitive.long, numberIn(line, field), why)
            } else {
                assertThrows<IllegalArgumentException>(why) {
                    if (request) decodeBodyRequest(line) else decodeBodyResponse(line)
                }
            }
        }
    }

    /** The value the named field of a corpus frame decodes to, through the real decoders. */
    private fun numberIn(
        line: String,
        field: String,
    ): Long =
        when (field) {
            "protocol" -> decodeBodyRequest(line).protocol.toLong()
            "uptimeMs" -> (decodeBodyResponse(line) as BodyResponse.StatusReport).uptimeMs
            "requestsHandled" -> (decodeBodyResponse(line) as BodyResponse.StatusReport).requestsHandled
            else -> throw AssertionError("the shared numeric corpus names a field this suite cannot read: $field")
        }

    /** Asserts the schema itself accepts the line, which is the other half of parity. */
    private fun assertSchemaAccepts(
        def: String,
        line: String,
        why: String,
    ) {
        val messages = schemaFor(def).validate(line, InputFormat.JSON)
        assertTrue(messages.isEmpty(), "the schema refused a line it should accept ($why): $messages")
    }

    /** Asserts the schema itself refuses the line, so the JVM is equal to it and not looser. */
    private fun assertSchemaRefuses(
        def: String,
        line: String,
        why: String,
    ) {
        val messages = schemaFor(def).validate(line, InputFormat.JSON)
        assertFalse(messages.isEmpty(), "the schema accepted a line it should refuse ($why)")
    }

    /** A handshake frame whose `protocol` carries the given literal verbatim. */
    private fun handshakeFrameWithProtocol(literal: String): String =
        """{"op":"handshake","protocol":$literal,"requestId":"req-1","agentId":"agent-alpha"}"""

    /** A status report frame whose three counts carry the given literals verbatim. */
    private fun statusFrameWithCounts(
        uptimeMs: String,
        stateMs: String,
        requestsHandled: String,
    ): String =
        """{"op":"status","protocol":$BODY_CONTROL_PROTOCOL_VERSION,"requestId":"req-2",""" +
            """"agentId":"agent-alpha","state":"ready",""" +
            """"uptimeMs":$uptimeMs,"stateMs":$stateMs,"requestsHandled":$requestsHandled}"""

    /** A [BodyResponse.StatusReport] that is otherwise canonical, for the count cases above. */
    private fun statusReport(
        uptimeMs: Long,
        stateMs: Long,
        requestsHandled: Long,
    ): BodyResponse.StatusReport =
        BodyResponse.StatusReport(
            protocol = BODY_CONTROL_PROTOCOL_VERSION,
            requestId = "req-2",
            agentId = "agent-alpha",
            state = LifecycleState.READY,
            uptimeMs = uptimeMs,
            stateMs = stateMs,
            requestsHandled = requestsHandled,
        )

    private fun fixtureText(
        subdir: String,
        name: String,
    ): String =
        canonicalText(
            fixtureRoot.resolve("fixtures").resolve("body-control").resolve(subdir).resolve(name),
        )

    private companion object {
        /**
         * Every category of the shared corpus.
         *
         * A frame is filed by what the *schema* says about it as much as by what the codecs
         * do: `valid` is accepted by both, `invalid` is refused by both, and `beyond-schema`
         * is admitted by the schema and refused by both codecs.
         */
        val FIXTURE_KINDS = listOf("valid", "invalid", "beyond-schema")

        /** The schema's `maxLength` for an id, restated so the case above can reach the bound. */
        const val MAX_ID_CHARS = 64

        /**
         * How many spellings `numeric-spellings.json` states.
         *
         * Pinned rather than merely nonzero, and pinned to the same number Node's suite
         * states: a sweep over whatever it finds passes just as happily over a corpus
         * somebody has deleted a case from, which is the one failure a shared list exists
         * to prevent. Adding a case means raising it here and in
         * `body/test/conformance.test.js`, which is the point - neither runtime can
         * quietly stop reading a spelling the other one still does.
         */
        const val SHARED_NUMERIC_CASES = 46

        /**
         * How long a refused numeric literal may take, generously.
         *
         * Preemptive, because a regression that dropped either side of the integer-digit
         * guard would not fail slowly - it would try to build a two-billion-digit number
         * and exhaust the heap, or spend forty-seven seconds dividing by a hundred million
         * digits, hanging the suite instead of failing it. The same reasoning as the
         * cognition transport's drip test.
         */
        val NUMBER_PATIENCE: Duration = Duration.ofSeconds(10)
    }
}

/**
 * The arithmetic proof that every answer admitted by the field bounds fits one frame.
 *
 * Separate from fixture conformance because it constructs the extremal answer rather
 * than reading the shared corpus.
 */
class BodyControlFrameBoundTest {
    @Test
    fun theLargestAnswerTheBoundsAdmitFitsTheFrameBound() {
        val name = "minecraft:" + "a".repeat(RESOURCE_NAME_MAX_CHARS - "minecraft:".length)
        val id = "a".repeat(CONTROL_ID_MAX_CHARS)
        val here = List(3) { WIDEST_COORDINATE }
        assertTrue(
            WIDEST_COORDINATE.toString().length > (-justInside(WORLD_BORDER)).toString().length,
            "the widest coordinate must not be the largest one",
        )
        val fullest =
            SensedVitals(
                health = WIDEST_VITAL,
                food = MAX_FOOD,
                saturation = WIDEST_VITAL,
            )
        val widest =
            BodyResponse.SenseReport(
                protocol = BODY_CONTROL_PROTOCOL_VERSION,
                requestId = id,
                agentId = id,
                state = LifecycleState.READY,
                position = here,
                vitals = fullest,
                time = SensedTime(timeOfDay = MAX_TIME_OF_DAY, day = MAX_CONTROL_COUNT, isDay = true),
                ground = SensedGround(standingIn = name, standingOn = name),
                entities =
                    List(MAX_SENSE_ENTITIES) {
                        SensedEntity(what = name, position = here, visible = true)
                    },
                entitiesOmitted = MAX_CONTROL_COUNT,
                blocks =
                    List(MAX_SENSE_BLOCKS) {
                        SensedBlock(what = name, position = listOf(it, it, it), visible = true)
                    },
                blocksOmitted = MAX_CONTROL_COUNT,
                items =
                    List(MAX_SENSE_ITEMS) {
                        SensedItem(
                            what = name,
                            count = MAX_STACK_COUNT,
                            position = here,
                            visible = true,
                        )
                    },
                itemsOmitted = MAX_CONTROL_COUNT,
                inventory =
                    List(MAX_SENSE_STACKS) {
                        SensedStack(slot = it, what = name, count = MAX_STACK_COUNT)
                    },
            )
        val measured = encodeBodyResponse(widest).toByteArray(Charsets.UTF_8).size
        assertTrue(
            measured <= MAX_BODY_CONTROL_FRAME_BYTES,
            "the largest sense answer the bounds admit is $measured bytes, over the " +
                "$MAX_BODY_CONTROL_FRAME_BYTES-byte frame bound",
        )
    }
}
