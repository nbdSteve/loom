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
 * That one carries what an agent knows and decides; this one carries whether a body
 * process is alive, which lifecycle state it is in, when to stop, the raw state the
 * game reports about its player, and one meal a controller named. The two
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
 * Several relations between two fields of one frame are enforced here rather than by
 * the schema - `stateMs` can never exceed `uptimeMs`, since a process starts in a state,
 * and a saturation is never larger than the food level it sits in front of. Draft
 * 2020-12 cannot compare two properties at all for some of them and deliberately
 * declines to for the rest, so both codecs enforce every one of them and the
 * `beyond-schema` fixtures pin each from both sides at once; BodyControlConformanceTest
 * owns that category.
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
internal const val CONTROL_ID_MAX_CHARS = 64

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

/** The shortest a block or item or entity name may be, mirroring the schema's `minLength`. */
private const val RESOURCE_NAME_MIN_CHARS = 1

/** The longest a block or item or entity name may be, mirroring the schema's `maxLength`. */
internal const val RESOURCE_NAME_MAX_CHARS = 40

/**
 * What the game's own data may call a block, an item or an entity, mirroring the
 * schema's `pattern`.
 *
 * A resource location: an optional namespace, then a path whose segments are separated
 * by `/`. Narrow for the reason [CONTROL_ID_PATTERN] is - these names are written into
 * records and shown to operators - and it deliberately admits `..` as a segment, because
 * a resource location is not a path and nothing on either side of this wire reads one as
 * a file name. The bound that matters for a name is that it is a name the registry could
 * hold, and this is it.
 */
private val RESOURCE_NAME_PATTERN = Regex("^([a-z0-9_.-]+:)?[a-z0-9_.-]+(/[a-z0-9_.-]+)*$")

/** How far from the origin a coordinate may be, mirroring the schema's `maximum`. */
private const val MAX_WORLD_COORDINATE = 30_000_000

/** How many numbers a position is, mirroring the schema's `minItems` and `maxItems`. */
private const val WORLD_POSITION_AXES = 3

/** The widest a caller may ask a sense to look, in blocks, mirroring the schema. */
private const val MAX_SENSE_RADIUS = 48

/** The most entities a sense answer may carry, mirroring the schema's `maxItems`. */
internal const val MAX_SENSE_ENTITIES = 24

/** The highest slot of a player's own inventory window, mirroring the schema. */
internal const val MAX_SLOT_INDEX = 45

/**
 * The most stacks a sense answer may carry.
 *
 * One per slot of the window, so a full inventory is reported in full: there is no
 * omission count beside the list, because a body that trimmed its own inventory would be
 * hiding the last food an agent has.
 */
internal const val MAX_SENSE_STACKS = MAX_SLOT_INDEX + 1

/** The fewest items a reported stack may hold: an empty slot is left out instead. */
private const val MIN_STACK_COUNT = 1

/** The most items one stack may hold, mirroring the schema's `maximum`. */
internal const val MAX_STACK_COUNT = 255

/** The game's own ceiling on a max-health attribute, mirroring the schema's `maximum`. */
private const val MAX_HEALTH = 1024

/** Vanilla's own food and saturation ceiling, mirroring the schema's `maximum`. */
internal const val MAX_FOOD = 20

/** The last tick of a Minecraft day, mirroring the schema's `maximum`. */
internal const val MAX_TIME_OF_DAY = 23999

/**
 * Refuses a whole number outside what its field may hold.
 *
 * States the bound and not the value, for the reason [requireBoundedControlId] does.
 */
private fun requireWithin(
    field: String,
    value: Int,
    range: IntRange,
) {
    require(value in range) { "$field must be ${range.first}..${range.last}" }
}

/**
 * Refuses a real number outside what its field may hold.
 *
 * Separate from [requireWithin] rather than generic over `Comparable`, because the two
 * are different rules: a whole number's bounds are the schema's `minimum` and `maximum`
 * on an `integer`, while a real one's range check is also what refuses the infinity a
 * runaway literal decodes to - every comparison against a NaN or an infinity fails, so a
 * value that is not a number this wire can hold is refused by the same clause.
 */
private fun requireReal(
    field: String,
    value: Double,
    highest: Int,
) {
    require(value >= 0 && value <= highest) { "$field must be 0..$highest" }
}

/**
 * Refuses a name the game's own registry could not hold.
 *
 * Counted in code points for the reason [requireBoundedControlId] is: the schema's
 * `maxLength` counts them, and `String.length` would measure a non-BMP name double.
 */
private fun requireResourceName(
    field: String,
    value: String,
) {
    val characters = value.codePointCount(0, value.length)
    require(characters in RESOURCE_NAME_MIN_CHARS..RESOURCE_NAME_MAX_CHARS) {
        "$field must be $RESOURCE_NAME_MIN_CHARS..$RESOURCE_NAME_MAX_CHARS characters"
    }
    require(RESOURCE_NAME_PATTERN.matches(value)) { "$field is not a name the game's own data would use" }
}

/**
 * Refuses an answer whose state is not the one its operation can only be answered in.
 *
 * A sense and an eat both read a live player, and the schema states the state as a
 * `const` for each. A frame reporting any other state is a process disagreeing with the
 * contract about what it just did, so it is refused rather than read - a body that had not
 * spawned has no vitals to report, and one the server has removed would be reporting the
 * world it last saw. Such a body answers [ErrorCode.NOT_READY] instead.
 *
 * The state is the one the request was **admitted under**, which is the same turn for a
 * sense and an earlier one for an eat: a meal is answered after two round trips to the
 * server, so the body can die or disconnect between being given the meal and being asked
 * what became of it. `ready` there is not a claim about the moment the frame was written but
 * about the moment the numbers beside it came from, and the vitals in an eat answer follow
 * the same rule - see [BodyResponse.EatReport].
 */
private fun requireReady(
    state: LifecycleState,
    what: String,
) {
    require(state == LifecycleState.READY) { "$what reports ${LifecycleState.READY}" }
}

/**
 * Refuses a position that is not three coordinates inside the world's own limits.
 *
 * The axis count is checked here rather than by a fixed-arity type, because the wire
 * carries an array and a frame naming four coordinates is a peer describing a world this
 * one does not have - which is a refusal and not a value to interpret.
 */
private fun requireWorldPosition(
    field: String,
    value: List<Double>,
) {
    require(value.size == WORLD_POSITION_AXES) { "$field must be $WORLD_POSITION_AXES coordinates" }
    require(value.all { it >= -MAX_WORLD_COORDINATE && it <= MAX_WORLD_COORDINATE }) {
        "$field must be within $MAX_WORLD_COORDINATE of the origin"
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

    /**
     * The body is not a spawned, live player, so the request has nothing to read.
     *
     * It is a refusal and not an [EatOutcome], because the two answer different
     * questions: an outcome says what the body did about a request it could carry out,
     * and this says the request could not be carried out at all. A body that has not
     * spawned has no position to read, and one the server has removed would be
     * reporting the world it last saw.
     */
    @SerialName("notReady")
    NOT_READY,
}

/**
 * What became of one eat request, as one value from a closed set.
 *
 * It is on the response rather than in [ErrorCode] because those are answers to
 * different questions: an [ErrorCode] is why a frame was not usable, while these are
 * what the body did about a request it understood perfectly. A refusal here is therefore
 * an answer, and it is a value rather than prose so that a run can count how many meals
 * were missed and why - the same reason the Villager body's own eat refusals are a tag on
 * the outcome rather than a sentence. Every refusal that is a fact about the request is
 * settled before anything is consumed, so a refused eat never costs an agent its food.
 *
 * [SLOT_UNNAMED] carries two readings, and a run counting them cannot tell which it has.
 * It is a handle the mind invented rather than named back - and it is also a handle the body
 * did issue and has already served a meal against, because one sense answer's offer of one
 * slot buys one meal: a body spends the handle on the meal, so an eat re-sent against it is
 * refused here rather than served a second time. Both are facts about the *handle* rather
 * than about the slot, which is the one thing the first of the body's checks knows about,
 * and that check stays first because an unresolvable handle names no slot to look in.
 *
 * [SLOT_EMPTY] and [SLOT_CHANGED] are kept apart because they are different facts about
 * the run: the slot the mind named holds nothing now, against it holds something that is
 * not what the mind chose. Reading them alike would leave a run unable to say whether an
 * agent's food had been eaten or moved. Neither is what a re-sent eat earns, for the reason
 * [SLOT_UNNAMED] gives: a spent handle is refused before any slot is read.
 *
 * [NOT_EDIBLE] and [RESERVE_FULL] are kept apart for the same reason, and it is the
 * sharper of the two pairs: an agent that had nothing edible and an agent that ate itself
 * to a standstill are opposite readings of one run, and vanilla itself refuses the second
 * - a full food bar cannot take an ordinary food, whatever the agent is carrying.
 *
 * [SLOT_INACCESSIBLE] is the residual, the only one of the seven that is not a fact about
 * the agent, and the only one that cannot promise the food is still there: the stack was
 * there and was edible and the meal still did not happen, because the body could not
 * bring it to hand or the game never confirmed it - and a use the game never confirmed
 * may still have been carried out. One of those in a run is something to go and look at
 * rather than something to count.
 */
@Serializable
enum class EatOutcome {
    @SerialName("eaten")
    EATEN,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("slotUnnamed")
    SLOT_UNNAMED,

    @SerialName("slotEmpty")
    SLOT_EMPTY,

    @SerialName("slotChanged")
    SLOT_CHANGED,

    @SerialName("notEdible")
    NOT_EDIBLE,

    @SerialName("reserveFull")
    RESERVE_FULL,

    @SerialName("slotInaccessible")
    SLOT_INACCESSIBLE,
}

/**
 * The three vitals the game keeps for a player, never combined into one.
 *
 * Health is damage to the body; food is how full the agent is; saturation is the hidden
 * reserve vanilla spends before food falls. A body can be beaten to death on a full
 * stomach and can starve without a scratch, so a wire reporting one figure for
 * "condition" could not say what ended an agent.
 *
 * Deliberately not the perception contract's `SelfState.energy`, which is the one
 * synthetic reserve the Villager body spends by living: these are vanilla's own numbers,
 * and reconciling the two is the coordinator's work at cutover rather than something this
 * wire pretends to have done.
 *
 * Saturation is never above the food level, because vanilla clamps it there when eating
 * and spends it before food falls: a report above it has read the two at different
 * instants. The schema cannot compare one property with another, so that relation is
 * stated here and in the Node codec, exactly as [BodyResponse.StatusReport]'s `stateMs`
 * relation is.
 */
@Serializable
data class SensedVitals(
    val health: Double,
    val food: Int,
    val saturation: Double,
) {
    init {
        requireReal("health", health, MAX_HEALTH)
        requireWithin("food", food, 0..MAX_FOOD)
        requireReal("saturation", saturation, MAX_FOOD)
        require(saturation <= food) { "saturation must be at or below food" }
    }
}

/**
 * The world clock, as the client library reports it and with nothing derived from it.
 *
 * The coordinator's own clock turns this into a phase and a countdown; a body that named
 * the phase would be deciding what part of a day an agent is in, which is a judgement and
 * belongs above this wire.
 *
 * [isDay] is reported rather than left to be derived, because it is a reading and not a
 * calculation: a dimension whose time is fixed has a time of day and no daylight, and an
 * agent needs the difference.
 */
@Serializable
data class SensedTime(
    val timeOfDay: Int,
    val day: Long,
    val isDay: Boolean,
) {
    init {
        requireWithin("timeOfDay", timeOfDay, 0..MAX_TIME_OF_DAY)
        requireCount("day", day)
    }
}

/**
 * The two blocks a walking body's own footing is made of.
 *
 * Two blocks and no more - this is not a map, and a body that reported the blocks around
 * it would be building a second world model on a wire whose frame bound could not hold
 * one. Air is reported as the block it is, since air is a block the registry names.
 */
@Serializable
data class SensedGround(
    val standingIn: String,
    val standingOn: String,
) {
    init {
        requireResourceName("standingIn", standingIn)
        requireResourceName("standingOn", standingOn)
    }
}

/**
 * One entity the client library was tracking within the request's radius.
 *
 * What the game calls it, where it is, and whether a straight line from the body's eyes
 * reaches it - and deliberately nothing else. There is no handle, because a handle that
 * outlived the request is exactly the reuse the perception contract's own entity
 * references refuse; the coordinator mints one per perception from this list as it
 * already does for its other body. There is no kind, no hostility and no threat either:
 * what an entity means is the coordinator's judgement, and a body that answered it would
 * be deciding.
 *
 * [visible] is a line and not a field of view: the body's facing is never consulted, so
 * everything inside the radius is equally known and false here means the body knows
 * something is there and cannot see it.
 */
@Serializable
data class SensedEntity(
    val what: String,
    val position: List<Double>,
    val visible: Boolean,
) {
    init {
        requireResourceName("what", what)
        requireWorldPosition("position", position)
    }
}

/**
 * One occupied slot of the player's own inventory window.
 *
 * Empty slots are left out, which is what makes the count on a reported stack at least
 * one, and every occupied slot is reported: there is no bound to omit against and so no
 * omission count beside the list.
 *
 * The item is named as the game's own data names it, which is the same vocabulary an
 * entity kind is reported in, and never as a display name: a renamed loaf is still bread,
 * and a display name is whatever an anvil or a datapack last called it.
 */
@Serializable
data class SensedStack(
    val slot: Int,
    val what: String,
    val count: Int,
) {
    init {
        requireWithin("slot", slot, 0..MAX_SLOT_INDEX)
        requireResourceName("what", what)
        requireWithin("count", count, MIN_STACK_COUNT..MAX_STACK_COUNT)
    }
}

/**
 * One request from a controller to a body process.
 *
 * Every variant carries the protocol generation, the correlation id its one answer
 * will echo, and the agent whose body is addressed, so a frame is self-describing
 * and a reply can never be matched to the wrong request. Three variants carry no
 * payload at all, and the two that do carry only the caller's own bounds and the one
 * slot it names - never a target, a threshold or a rule, because a body that was told
 * what to want would be deciding rather than acting.
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

    /**
     * Read what the game says about this player, once, within the caller's own bounds.
     *
     * The two bounds are the caller's because the caller is the one that has to fit the
     * answer inside a frame and inside a prompt: a body choosing its own radius would be
     * choosing how much of the world an agent gets to know. They are the *caller's*
     * bounds and not a policy either - nothing here says what to look for or what any of
     * it means.
     *
     * One request, one answer: there is no subscription and no stream, so a body reports
     * only when it is asked and never accumulates a view of the world between requests.
     */
    @Serializable
    @SerialName("sense")
    data class Sense(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val radius: Int,
        val limit: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireWithin("radius", radius, 1..MAX_SENSE_RADIUS)
            requireWithin("limit", limit, 1..MAX_SENSE_ENTITIES)
        }
    }

    /**
     * Eat the one stack a named sense answer reported in a named slot.
     *
     * [senseId] is the `requestId` of the [Sense] whose answer the caller is naming a slot
     * from, which is what makes a slot reference request-scoped: the body remembers only
     * its own most recent sense answers, so a slot named against a sense it has forgotten
     * is refused as [EatOutcome.SENSE_FORGOTTEN] rather than read against whatever the
     * inventory holds now. That is the same rule the perception contract's entity
     * references follow, and for the same reason - a handle that outlived its request
     * would let a decision act on a world that has moved on.
     *
     * The slot is the mind's own choice. There is no "eat something", no food preference
     * and no threshold: a body that picked the stack would be deciding what an agent
     * values, and a body with a threshold would be deciding when it is hungry.
     */
    @Serializable
    @SerialName("eat")
    data class Eat(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val slot: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("slot", slot, 0..MAX_SLOT_INDEX)
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
     * Still deliberately no position, health, hunger or inventory, now that a sense
     * answer carries all four: a status is what a controller asks to know whether the
     * process is there at all, so it must stay answerable by a body that has not
     * spawned - and it is asked far more often than a perception is taken. The bounded
     * answer to "what does the game say about this player" is [SenseReport], and it is
     * asked for by name.
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

    /**
     * What the game says about this player, bounded by what the request asked for.
     *
     * Every field is a reading and none is a judgement: the vitals are vanilla's own
     * numbers, an entity is named and placed and never classified, and the ground is the
     * two blocks the body is standing in and on. What any of it means - which entity is a
     * threat, whether the agent should eat, where it should go - is decided above this
     * wire, from this evidence.
     *
     * [entitiesOmitted] is what makes the entity list honest. A scene trimmed to the
     * caller's limit and reported as a bare list would read afterwards as a quiet scene,
     * which is the one reading a survival run must never be given, so the count of what
     * was dropped rides inside the answer rather than beside it.
     *
     * The inventory carries no such count, and that asymmetry is deliberate: every
     * occupied slot of the window is reported, so there is nothing to omit against. A
     * body that trimmed its own inventory could hide the last food an agent has.
     *
     * [ground] is absent rather than null when the body could not read the blocks it is
     * standing on - an unloaded chunk answers nothing, and a body that reported air there
     * would be stating a fact it never read.
     *
     * Two relations are stated here because the schema cannot state them: an inventory's
     * slots strictly ascend, so one slot cannot be reported twice and a reader can join
     * two answers by slot; and entities cannot have been omitted from an empty list, since
     * a limit is at least one and a cut that kept nothing kept less than it was allowed.
     */
    @Serializable
    @SerialName("sense")
    data class SenseReport(
        // The frame is flat, so there is no seam to split this along: kotlinx cannot
        // inline a nested holder's fields into one JSON object, and grouping these into
        // one would change the wire rather than the code. The four envelope fields are
        // the sealed interface's own, and the seven readings are what one sense answers.
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val position: List<Double>,
        val vitals: SensedVitals,
        val time: SensedTime,
        val ground: SensedGround? = null,
        val entities: List<SensedEntity>,
        val entitiesOmitted: Long,
        val inventory: List<SensedStack>,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a sense")
            requireWorldPosition("position", position)
            require(entities.size <= MAX_SENSE_ENTITIES) {
                "entities must be at most $MAX_SENSE_ENTITIES"
            }
            requireCount("entitiesOmitted", entitiesOmitted)
            require(entitiesOmitted == 0L || entities.isNotEmpty()) {
                "entitiesOmitted must be 0 when no entity is reported"
            }
            require(inventory.size <= MAX_SENSE_STACKS) {
                "inventory must be at most $MAX_SENSE_STACKS stacks"
            }
            require(inventory.zipWithNext().all { (earlier, later) -> earlier.slot < later.slot }) {
                "inventory slots must ascend, and no slot may be reported twice"
            }
        }
    }

    /**
     * What became of one eat request, and the vitals of the body that took it on.
     *
     * The vitals are on every answer, refusals included, because they are what the
     * request was about: an agent that was refused still needs to know how hungry it is,
     * and a refusal that reported nothing would cost it a round trip to find out.
     *
     * **[state] and [vitals] are both the moment the request was admitted rather than the
     * moment the frame was written**, and an eat is the one operation where those differ: it
     * is answered after two round trips to the server, so the body can die, disconnect or be
     * handed a food level outside vanilla's own ceiling in between. `ready` here is not a
     * claim about now; it is the state the body was in when the meal was taken on, which is
     * where the numbers beside it came from. A writer prefers a fresher vitals reading only
     * while the body is still in that state and the reading can still be stated, and carries
     * the admitted one otherwise, never a mix of the two - one rule for both fields, and
     * `body/src/eating.js`'s header is where it is owned.
     *
     * [what] and [remaining] are present exactly when the outcome is
     * [EatOutcome.EATEN] - what was eaten, and how much of it the body still holds
     * wherever bringing the stack to hand left it, which is not the slot the request
     * named. A refusal
     * naming food would be claiming to have read a stack it refused to touch, and a meal
     * naming none would leave a run unable to say what an agent ate. Draft 2020-12 could
     * express that as a conditional and deliberately does not, so the relation is stated
     * here and in the Node codec, as every other relation this contract states is.
     */
    @Serializable
    @SerialName("eat")
    data class EatReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: EatOutcome,
        val vitals: SensedVitals,
        val what: String? = null,
        val remaining: Int? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "an eat")
            val eaten = outcome == EatOutcome.EATEN
            require((what != null) == eaten) {
                "what is present exactly when the outcome is ${EatOutcome.EATEN}"
            }
            require((remaining != null) == eaten) {
                "remaining is present exactly when the outcome is ${EatOutcome.EATEN}"
            }
            what?.let { requireResourceName("what", it) }
            remaining?.let { requireWithin("remaining", it, 0..MAX_STACK_COUNT) }
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
