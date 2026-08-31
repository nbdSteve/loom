/*
 * Kotlin kotlinx-serialization bindings for the Loom body-control protocol.
 *
 * The single source of truth for these types is `contract/body-control.schema.json`,
 * and the Node side carries its own hand-written codec in `body/src/protocol.js`.
 * Neither binding is generated from the schema; the shared conformance corpus under
 * `contract/fixtures/body-control` is what keeps the three descriptions of one wire
 * from drifting, and BodyControlConformanceTest fails the build when they do.
 *
 * This is deliberately NOT the perception/decision contract in `dev.loom.contract`.
 * That one carries what an agent knows and decides; this one carries only whether a
 * body process is alive, which lifecycle state it is in, and when to stop. The two
 * are siblings with separate binding types, and BodyControlBoundaryTest pins that this
 * package depends on nothing else in Loom at all - not the other contract, and not any
 * server API. `BodyControlCodec.kt` therefore states this wire's Json configuration
 * for itself rather than importing the canonical `dev.loom.contract.LoomJson` whose
 * settings it copies exactly, and the shared fixtures round-tripping on both sides are
 * what keep the two from drifting.
 *
 * Wire tagging is internal, as it is on the other contract: [BodyRequest] and
 * [BodyResponse] are tagged on `op` with the variant's fields inlined alongside, and
 * the Node encoder emits `op` first to match. [BodyError] is outside both unions and
 * carries no `op` at all, for the reason the cognition wire's refusal frame is not a
 * contract type: a frame the process could not decode has no operation to name.
 *
 * Every bound the schema states is re-checked here at decode, because nothing on
 * this wire is schema-validated at runtime - `minLength`, `maxLength`, `pattern`,
 * `minimum` and `maximum` are all rules a socket walks straight past. Two schema
 * rules need checks before a binding reaches an `init`: a duplicate member name is
 * lost when JSON is parsed, while an explicit null survives that parse but is erased
 * by kotlinx-serialization when `explicitNulls = false`. `BodyControlCodec.kt` checks
 * the raw line and the parse tree at those two points, which is why nothing may decode
 * one of these types except through the functions there.
 *
 * One semantic relation cannot be expressed by JSON Schema 2020-12: `stateMs` can
 * never exceed `uptimeMs`, since a process starts in a state. Both codecs enforce it,
 * and the conformance suites assert that they do.
 *
 * Numeric values need a separate decoder rule because the schema defines an integer
 * by value rather than spelling. To the schema and to `JSON.parse`, `1.0` and `1e0`
 * are both `1`; kotlinx reads the raw literal and would refuse the first.
 * `IntegralNumbers.kt` implements the schema's reading and is reached through the
 * file-level `UseSerializers` below, which applies it to every `Int` and `Long` on this
 * wire at once - so a numeric field added later inherits the contract's reading
 * without having to remember an annotation.
 */
@file:OptIn(ExperimentalSerializationApi::class)
@file:UseSerializers(IntegralInt::class, IntegralLong::class)

package dev.loom.bodycontrol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The one protocol generation, mirroring the schema's `const`.
 *
 * A frame naming any other generation is refused rather than interpreted: a later
 * generation may spell the same field differently, and a value read under the wrong
 * generation is worse than a refusal.
 */
const val BODY_CONTROL_PROTOCOL_VERSION: Int = 1

/** The shortest a requestId or agentId may be, mirroring the schema's `minLength`. */
private const val CONTROL_ID_MIN_CHARS = 1

/** The longest a requestId or agentId may be, mirroring the schema's `maxLength`. */
private const val CONTROL_ID_MAX_CHARS = 64

/**
 * What an id may contain, mirroring the schema's `pattern`.
 *
 * Deliberately narrow rather than merely bounded: an id is echoed into a frame and
 * into logs, so a newline would break the framing, and a path separator or whitespace
 * is how a local path or a free-text transport error would arrive in something that
 * gets written down.
 */
private val CONTROL_ID_PATTERN = Regex("^[A-Za-z0-9._:-]+$")

/** Minecraft's own account-name rule, which is what makes a login possible at all. */
private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

/**
 * Refuses a frame written in a generation this runtime does not speak.
 *
 * The check is stated once, applied before variant dispatch, and repeated from every
 * variant's `init` rather than being left to the schema, for the same reason the
 * length bounds below are: the frame arrives off a socket, where no schema runs.
 */
internal fun requireProtocolGeneration(protocol: Int?) {
    require(protocol == BODY_CONTROL_PROTOCOL_VERSION) {
        "protocol must be $BODY_CONTROL_PROTOCOL_VERSION"
    }
}

/**
 * Refuses an id that is blank, over-long, or carries something an id may not.
 *
 * Length is counted in code points, as the schema's `maxLength` counts it and as the
 * Node side's `[...value].length` counts it. `String.length` is UTF-16 code units,
 * so a non-BMP id would measure double here and this runtime would refuse a handle
 * the other accepts - the two disagreeing about the one bound each states once,
 * which is exactly what stating it once per runtime is meant to rule out.
 *
 * Neither refusal states what arrived, only what was expected. The measured length is
 * as much a fact about the peer's frame as the id itself, and a refusal off this wire
 * may carry nothing from it - see the note in `BodyControlCodec.kt`. The `field` name is
 * this contract's own vocabulary, passed as a literal by the call site below and stated
 * in the schema, so it is not peer data and is safe to name.
 */
private fun requireBoundedControlId(
    field: String,
    value: String,
) {
    val characters = value.codePointCount(0, value.length)
    require(characters in CONTROL_ID_MIN_CHARS..CONTROL_ID_MAX_CHARS) {
        "$field must be $CONTROL_ID_MIN_CHARS..$CONTROL_ID_MAX_CHARS characters"
    }
    require(CONTROL_ID_PATTERN.matches(value)) { "$field carries a character an id may not" }
}

/** Refuses the envelope every frame in both directions shares. */
private fun requireEnvelope(
    protocol: Int,
    requestId: String,
    agentId: String,
) {
    requireProtocolGeneration(protocol)
    requireBoundedControlId("requestId", requestId)
    requireBoundedControlId("agentId", agentId)
}

/**
 * The largest a duration or a tally on this wire may be, mirroring the schema's
 * `maximum`.
 *
 * It is JavaScript's largest exactly-representable integer rather than `Long.MAX_VALUE`,
 * because the other end of this wire is a Node process: a `Long` this side would encode
 * happily is a number the Node codec cannot read back, so bounding at what the JVM can
 * hold would make a frame legal to the contract and to one runtime and unreadable to
 * the other. The narrower runtime's bound is the only one both sides can hold, and each
 * binding states the same number for itself.
 */
const val MAX_CONTROL_COUNT: Long = 9_007_199_254_740_991L

/**
 * The fewest requests a status report may claim, mirroring the schema's `minimum`.
 *
 * Answering the status request is itself handling one, so a report of zero has
 * miscounted its own answer.
 */
private const val MIN_REQUESTS_HANDLED = 1L

/**
 * Refuses a duration or a tally that could not have been measured, or read back.
 *
 * States the bound and not the value, for the reason [requireBoundedControlId] does.
 */
private fun requireCount(
    field: String,
    value: Long,
) {
    require(value in 0..MAX_CONTROL_COUNT) {
        "$field must be 0..$MAX_CONTROL_COUNT"
    }
}

/**
 * Where a body process is in its one life.
 *
 * Seven states and no gameplay: this says whether there is a player on the server
 * and whether it is still usable, never what the player should do. [LOGGED_IN] and
 * [DEAD] are separate from [READY] for a reason found in world - a dead identity
 * reconnecting arrives on the death screen, so it reaches `loggedIn` and then `dead`
 * without ever reaching `ready`, and lifecycle handling that waited only for a spawn
 * would hang there.
 */
@Serializable
enum class LifecycleState {
    @SerialName("connecting")
    CONNECTING,

    @SerialName("loggedIn")
    LOGGED_IN,

    @SerialName("ready")
    READY,

    @SerialName("dead")
    DEAD,

    @SerialName("disconnected")
    DISCONNECTED,

    @SerialName("stopping")
    STOPPING,

    @SerialName("failed")
    FAILED,
}

/**
 * Why a request was refused, as one value from a closed set.
 *
 * It is an enum and not a message on purpose, and this is the one place the
 * body-control protocol is deliberately stricter than the cognition wire's free-text
 * refusal: an exception string, a file path, a host name or a token cannot reach a
 * controller or a log through a field that can only hold one of these words.
 */
@Serializable
enum class ErrorCode {
    @SerialName("unsupportedProtocol")
    UNSUPPORTED_PROTOCOL,

    @SerialName("malformedFrame")
    MALFORMED_FRAME,

    @SerialName("frameTooLarge")
    FRAME_TOO_LARGE,

    @SerialName("unknownOperation")
    UNKNOWN_OPERATION,

    @SerialName("invalidField")
    INVALID_FIELD,

    @SerialName("duplicateRequestId")
    DUPLICATE_REQUEST_ID,

    @SerialName("tooManyRequests")
    TOO_MANY_REQUESTS,

    @SerialName("handshakeRequired")
    HANDSHAKE_REQUIRED,

    @SerialName("wrongAgent")
    WRONG_AGENT,

    @SerialName("shuttingDown")
    SHUTTING_DOWN,

    @SerialName("timeout")
    TIMEOUT,
}

/**
 * One request from a controller to a body process.
 *
 * Every variant carries the protocol generation, the correlation id its one answer
 * will echo, and the agent whose body is addressed, so a frame is self-describing
 * and a reply can never be matched to the wrong request. No variant carries a
 * payload: everything this generation can ask for is asked by naming the operation.
 */
@Serializable
@JsonClassDiscriminator("op")
sealed interface BodyRequest {
    val protocol: Int
    val requestId: String
    val agentId: String

    /**
     * Agree the protocol generation and learn who the process is.
     *
     * It must be the first request on a connection, so a controller cannot read a
     * field this generation happens to share with the next.
     */
    @Serializable
    @SerialName("handshake")
    data class Handshake(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
        }
    }

    /**
     * Ask what state the body is in.
     *
     * This is also the protocol's correlated ping: an answer echoing the requestId is
     * itself the proof that the process is alive and reading its socket, so there is
     * no separate ping operation to keep in step with it.
     */
    @Serializable
    @SerialName("status")
    data class Status(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
        }
    }

    /** Stop the body process. Answered before anything is closed. */
    @Serializable
    @SerialName("shutdown")
    data class Shutdown(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
        }
    }
}

/**
 * The answer to one request the process understood.
 *
 * Tagged on the same `op` the request named and carrying that request's own ids
 * back, so a controller matches an answer by id rather than by arrival order.
 * Exactly one [BodyResponse] or one [BodyError] is written per request.
 */
@Serializable
@JsonClassDiscriminator("op")
sealed interface BodyResponse {
    val protocol: Int
    val requestId: String
    val agentId: String
    val state: LifecycleState

    /**
     * The protocol generation is agreed and the process names itself.
     *
     * The username is the identity an operator would see on the server's own player
     * list, which is what ties a control channel to a player without the process
     * reporting anything about the world.
     */
    @Serializable
    @SerialName("handshake")
    data class HandshakeAccepted(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val username: String,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            require(USERNAME_PATTERN.matches(username)) {
                "username is not a name Minecraft would accept"
            }
        }
    }

    /**
     * Where the body is in its life, and how long it has been there.
     *
     * Deliberately no position, health, hunger or inventory: those are perception,
     * and a control channel that reported them would be a second perception path
     * with no bound and no contract behind it.
     *
     * The three numbers are bounded together as well as one at a time, because two of
     * the ways they can be nonsense are relations rather than ranges: a process starts
     * in a state, so `stateMs` can never exceed `uptimeMs`, and answering this request
     * is itself handling one, so a report of zero has miscounted its own answer. The
     * schema states the second and cannot state the first, so both are stated here.
     */
    @Serializable
    @SerialName("status")
    data class StatusReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val uptimeMs: Long,
        val stateMs: Long,
        val requestsHandled: Long,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireCount("uptimeMs", uptimeMs)
            requireCount("stateMs", stateMs)
            requireCount("requestsHandled", requestsHandled)
            require(stateMs <= uptimeMs) { "stateMs must be at or below uptimeMs" }
            require(requestsHandled >= MIN_REQUESTS_HANDLED) {
                "requestsHandled must be at or above $MIN_REQUESTS_HANDLED"
            }
        }
    }

    /**
     * The shutdown was accepted and is under way.
     *
     * The state is always [LifecycleState.STOPPING] - the schema states it as a
     * `const`, so a frame reporting any other state is a process disagreeing with
     * the contract about what a shutdown is, and is refused here rather than read.
     */
    @Serializable
    @SerialName("shutdown")
    data class ShutdownAccepted(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            require(state == LifecycleState.STOPPING) {
                "a shutdown reports ${LifecycleState.STOPPING}"
            }
        }
    }
}

/**
 * A refusal.
 *
 * It is not a [BodyResponse] and carries no `op`, because a frame the process could
 * not decode has no operation to name. The agentId is always present, since the
 * process knows its own identity whatever arrived on the socket; the requestId is
 * present only when the frame carried a readable one, because echoing an id the
 * process never received would put a fiction on the wire and let a controller match
 * a refusal to a request that does not exist.
 */
@Serializable
data class BodyError(
    val protocol: Int,
    val requestId: String? = null,
    val agentId: String,
    val error: ErrorCode,
) {
    init {
        requireProtocolGeneration(protocol)
        requestId?.let { requireBoundedControlId("requestId", it) }
        requireBoundedControlId("agentId", agentId)
    }
}
