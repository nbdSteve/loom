/*
 * The way into and out of the Loom body-control protocol's Kotlin bindings.
 *
 * The types in `BodyControl.kt` refuse every bound the schema states in their own
 * `init` blocks, so one of those types existing is a frame that is legal. What an
 * `init` cannot refuse are facts discarded before a constructed value reaches it, or
 * costs already paid by the time one could be built:
 *
 *  - **A frame larger than this wire carries, and one that nests deeper than it reads.**
 *    Both are settled on the raw line before anything walks it, which is the only place
 *    a bound on the cost of reading can go. See [MAX_BODY_CONTROL_FRAME_BYTES] and
 *    [MAX_BODY_CONTROL_FRAME_DEPTH].
 *
 *  - **A member named twice.** JSON's data model has no room for a duplicate, so what a
 *    parser does with `{"agentId":"a","agentId":"b"}` is a property of the parser and
 *    not of the contract: one of the two values is believed, the other is dropped, and
 *    nothing says which. A controller and a body process reading the same bytes could
 *    therefore act on different agents, which is exactly the ambiguity a correlated
 *    lifecycle protocol exists to remove. [decodeBodyRequest] and its siblings scan the
 *    raw line for one before they parse it, the way `body/src/protocol.js` does. The
 *    scanner itself is `DuplicateMembers.kt`, which knows nothing of this protocol.
 *  - **An explicit null.** It survives the JSON parse, but `explicitNulls = false` -
 *    which lets an absent `requestId` stay absent on the way out - maps
 *    `"requestId":null` to an *absent* field during binding decode. A refusal naming
 *    no request and one naming null would therefore construct the same value, even
 *    though the schema refuses the second because no property here is nullable.
 *  - **The protocol generation.** Polymorphic dispatch reads `op`, and the null walk
 *    reads every generation-specific field, so the parsed top-level `protocol` is
 *    validated before either one can interpret a later generation.
 *
 * So nothing in Loom may call `decodeFromString` on a body-control type directly. The
 * three functions below are the way in, and they check the bytes before the parse and
 * the envelope and tree before the decode; the three beside them are the way out.
 *
 * **No failure out of this file may carry a byte of the frame.** That is the one
 * guarantee this protocol makes that the cognition wire does not, and it is what the
 * closed [ErrorCode] enum buys on the wire itself - so a decode that threw the parser's
 * own exception would give it all back on the way to a log. kotlinx-serialization's
 * messages quote the offset and the text at it, the unknown key it met, the enum value it
 * could not match and the discriminator it dispatched on, so every one of them is
 * translated here into one fixed sentence with no cause attached: a cause would carry the
 * message that was the reason for catching it. This file's own refusals - and the bounded
 * ones the `init` blocks state - are `IllegalArgumentException`s that pass through
 * untouched, which is why the catch names `SerializationException` and not its
 * `IllegalArgumentException` supertype.
 *
 * The Json configuration is stated here rather than taken from `dev.loom.contract`'s
 * canonical `LoomJson`, whose three settings it copies exactly. That is not
 * duplication for its own sake: `BodyControlBoundaryTest` pins that this package
 * depends on nothing else in Loom, so this wire can be reasoned about - and one day
 * lifted out - on its own. The two configurations have to agree, and the shared
 * fixtures round-tripping byte-for-byte on both sides of this wire are what say so.
 */
package dev.loom.bodycontrol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical Json configuration for the body-control wire.
 *
 * `encodeDefaults = false` is what keeps an omitted field omitted, `explicitNulls =
 * false` keeps an absent [BodyError.requestId] out of the frame rather than writing it
 * as a null the schema forbids, and `ignoreUnknownKeys = false` is what refuses the
 * extra data the contract does not admit. Identical to the perception/decision wire's
 * `LoomJson` by design and by fixture, deliberately not by import.
 */
private val BodyControlJson: Json =
    Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

/**
 * The longest frame this wire will read, in UTF-8 bytes.
 *
 * The same 32 KiB the Node codec's `MAX_FRAME_BYTES` states, and stated here for itself
 * rather than imported: `BodyControlBoundaryTest` pins that this package depends on nothing
 * else in Loom, and the other end of the wire is a different runtime, so each binding
 * states every shared number once. The shared conformance corpus is what keeps the two from
 * drifting.
 *
 * Which frame is largest is decided by the per-field bounds rather than by this number:
 * the widest sense answer they admit includes every entity, block, dropped item and
 * inventory slot, the longest name and id each may carry, and every real written as long
 * as a formatter writes one. It remains under 15 KiB on both runtimes.
 * `BodyControlConformanceTest` builds that answer from the live bounds and measures it
 * here rather than trusting this note or the much smaller fixture corpus.
 *
 * The two runtimes differ because a real's contribution to a frame is the length of its
 * decimal *text*, and the two formatters write their longest text at different magnitudes:
 * `Double.toString` writes plain decimals down to 1e-3 and an exponent below it, while
 * `Number.prototype.toString` writes plain decimals down to 1e-6, so a coordinate there is
 * one character wider than anything this side can produce. Each suite therefore derives its
 * own worst case rather than sharing a literal, and neither is the coordinate of largest
 * magnitude - which is *shorter* than an exponential form, the opposite of what the JVM
 * measurement used to assume.
 *
 * It is counted in **bytes and not in characters**, because that is what the wire carries
 * and what the Node side counts: a line of eight thousand astral characters is 32 KiB on
 * the socket, and measuring it as eight thousand would let a peer spend four times the
 * bound. The `String.length` clause in [exceedsFrameBound] is only what keeps the
 * measurement itself from having to allocate an unbounded array first.
 */
internal const val MAX_BODY_CONTROL_FRAME_BYTES = 32768

/**
 * The deepest a frame may nest before it is refused unparsed.
 *
 * Not a size bound but a stack bound, and the size bound above does not imply it:
 * 32 KiB buys more than sixteen thousand open brackets, while both
 * [Json.parseToJsonElement] and [carriesANull] descend per level, so the cost is the
 * reader's stack rather than its heap. With the guard removed, a line well inside the
 * byte bound raises a `StackOverflowError`; [carriesANull] spends several frames a
 * level rather than one: the `any` call, its iterator, the lambda and the recursion. A
 * `StackOverflowError` is an `Error` and not an exception, so [decoded]'s catch does not
 * cover it and no caller of this file does either - which is why the depth is settled
 * ahead of the parse, by a walk that does not itself recurse (`nestsDeeperThan`).
 *
 * Eight is far above anything this generation can legally hold. The deepest legal frame is
 * a sense answer at **four**: the top object, one sense list, an offered object, and
 * that object's `position` array. Raising any sense-list bound widens a list rather than
 * nesting it, so the headroom left here is spent by a *shape* this generation does not
 * have rather than by any list bound growing. The Node codec states the same
 * number for itself, where it is parity rather than safety: V8's own `JSON.parse` is
 * iterative and reads a hundred thousand levels without complaint, so only this runtime
 * is at risk, and both refuse the same frames anyway because a frame one end reads and
 * the other cannot is drift.
 */
internal const val MAX_BODY_CONTROL_FRAME_DEPTH = 8

/** Said when a frame names one of its members twice; carries nothing from the frame. */
private const val NAMED_TWICE = "a body-control frame names one member twice"

/** Said when a frame carries an explicit null; carries nothing from the frame. */
private const val EXPLICIT_NULL = "a body-control frame carries a null, which no field on this wire admits"

/** Said when a frame is over the byte bound; carries nothing from the frame. */
private const val TOO_LARGE = "a body-control frame is longer than this wire will carry"

/** Said when a frame nests past the depth bound; carries nothing from the frame. */
private const val TOO_DEEP = "a body-control frame nests deeper than this wire will read"

/**
 * Said when a frame is not one these types describe; carries nothing from the frame.
 *
 * Deliberately says no more than that. What kotlinx-serialization would have said instead
 * is which key it did not know, which enum value it could not match, or what text sat at
 * which offset - every one of which is a byte of the frame, on the one wire whose stated
 * guarantee is that none of them leaves.
 */
private const val NOT_THIS_CONTRACT = "a body-control frame is not one this contract describes"

/** Decodes one request frame, or throws. */
fun decodeBodyRequest(line: String): BodyRequest = decoded(line, BodyRequest.serializer())

/** Decodes one response frame, or throws. */
fun decodeBodyResponse(line: String): BodyResponse = decoded(line, BodyResponse.serializer())

/** Decodes one refusal frame, or throws. */
fun decodeBodyError(line: String): BodyError = decoded(line, BodyError.serializer())

/** Encodes one request frame. */
fun encodeBodyRequest(frame: BodyRequest): String = BodyControlJson.encodeToString(BodyRequest.serializer(), frame)

/** Encodes one response frame. */
fun encodeBodyResponse(frame: BodyResponse): String = BodyControlJson.encodeToString(BodyResponse.serializer(), frame)

/** Encodes one refusal frame. */
fun encodeBodyError(frame: BodyError): String = BodyControlJson.encodeToString(BodyError.serializer(), frame)

/**
 * One decode: every check this file owns, and a refusal that says nothing about the frame.
 *
 * The `try` spans the parse as well as the binding, because both halves of the decode are
 * kotlinx-serialization's and both report by quoting the frame: the parser names the text
 * at an offset, and the binding names an unknown key, an unmatched enum value or a
 * discriminator it could not dispatch on. Neither the caught exception nor its message is
 * attached as a cause; attaching one would carry back exactly what the catch is for.
 *
 * `SerializationException` is a subclass of `IllegalArgumentException`, so naming it rather
 * than its supertype is what lets the bounded refusals from [checkedFrame] and from the
 * types' own `init` blocks pass through with the sentences they chose.
 */
private fun <T> decoded(
    line: String,
    serializer: DeserializationStrategy<T>,
): T =
    try {
        BodyControlJson.decodeFromJsonElement(serializer, checkedFrame(line))
    } catch (_: SerializationException) {
        throw IllegalArgumentException(NOT_THIS_CONTRACT)
    }

/**
 * The frame's parse tree, once the raw and pre-binding checks are complete.
 *
 * Order is load-bearing throughout. The size and depth bounds come first because every
 * check after them walks the whole line at least once, and a bound applied after the work
 * it exists to bound is not a bound; depth in particular has to precede
 * [Json.parseToJsonElement], which is what it protects. Then the duplicate scan, which
 * reads the bytes, so it has to run before anything parses them. Then the protocol check,
 * which reads only the parsed envelope, so it runs before subtype dispatch or the null
 * walk. Then the null walk, which reads the tree, so it has to run before anything decodes
 * it.
 *
 * No refusal repeats a byte of the frame - a member name and a field value are both peer
 * data, and a refusal that quoted either would be the one place this protocol's stated
 * error-output guarantee could be lost.
 */
private fun checkedFrame(line: String): JsonElement {
    require(!exceedsFrameBound(line)) { TOO_LARGE }
    require(!nestsDeeperThan(line, MAX_BODY_CONTROL_FRAME_DEPTH)) { TOO_DEEP }
    require(!namesAMemberTwice(line)) { NAMED_TWICE }
    val frame = BodyControlJson.parseToJsonElement(line)
    if (frame is JsonObject) {
        requireProtocolGeneration(integralIntOrNull(frame["protocol"] as? JsonPrimitive))
    }
    require(!carriesANull(frame)) { EXPLICIT_NULL }
    return frame
}

/**
 * Whether the line is longer than [MAX_BODY_CONTROL_FRAME_BYTES] UTF-8 bytes.
 *
 * The first clause is the measurement's own bound rather than a shortcut with a different
 * meaning: UTF-8 never spends fewer bytes than the string spends UTF-16 units - one unit
 * costs one, two or three bytes, and a surrogate pair costs four - so a line over the bound
 * in units is over it in bytes, and refusing there is what keeps the encoding below from
 * allocating an array off an unbounded line to find out. Past that clause the line is at
 * most 8192 units, so the array it builds is bounded too.
 */
private fun exceedsFrameBound(line: String): Boolean =
    line.length > MAX_BODY_CONTROL_FRAME_BYTES ||
        line.toByteArray(Charsets.UTF_8).size > MAX_BODY_CONTROL_FRAME_BYTES

/**
 * Whether anything in the frame is an explicit null.
 *
 * No property in this contract is nullable, so a null anywhere is a frame the schema
 * refuses; the walk is written over the whole tree rather than over the one field that
 * has been seen to carry one, so a property added later inherits the refusal.
 */
private fun carriesANull(element: JsonElement): Boolean =
    when (element) {
        is JsonNull -> true
        is JsonObject -> element.values.any { carriesANull(it) }
        is JsonArray -> element.any { carriesANull(it) }
        else -> false
    }
