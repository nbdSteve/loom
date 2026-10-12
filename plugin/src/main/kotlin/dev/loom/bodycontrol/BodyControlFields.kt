/*
 * What one field of the Loom body-control wire may hold.
 *
 * This file is the scalar half of the contract: every bound the schema states as a scalar
 * `$defs` entry, and the check that re-states it at decode. `BodyControl.kt` is the other
 * half - the enums, the payload objects and the three frame unions that put these fields
 * together - and the split follows the schema's own shape rather than a line count: the
 * schema too states its scalars once and then names them from the frames.
 *
 * Every one of these checks exists because nothing on this wire is schema-validated at
 * runtime. `minLength`, `maxLength`, `pattern`, `minimum` and `maximum` are all rules a
 * socket walks straight past, so each runtime states the same bound for itself and the
 * shared fixture corpus is what keeps the two statements equal.
 *
 * No refusal here may state what arrived, only what was expected. A measured length or an
 * out-of-range value is as much a fact about the peer's frame as the field itself, and a
 * refusal off this wire may carry nothing from it - `BodyControlCodec.kt` owns that rule.
 * A `field` name is this contract's own vocabulary, passed as a literal by a call site and
 * spelled in the schema, so it is not peer data and is safe to name.
 */

package dev.loom.bodycontrol

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The current protocol generation, mirroring the schema's `const`.
 *
 * A frame naming any other generation is refused rather than interpreted: a later
 * generation may spell the same field differently, and a value read under the wrong
 * generation is worse than a refusal.
 *
 * It moved from 3 to 4 when sense answers gained recipe offers and the body gained the
 * craft, attack, and sleep closed response sets. Adding request operation names alone
 * would not have moved it; widening a body-chosen answer does.
 */
const val BODY_CONTROL_PROTOCOL_VERSION: Int = 4

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
internal val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

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
internal fun requireBoundedControlId(
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
internal fun requireEnvelope(
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
internal const val MIN_REQUESTS_HANDLED = 1L

/**
 * Refuses a duration or a tally that could not have been measured, or read back.
 *
 * States the bound and not the value, for the reason [requireBoundedControlId] does.
 */
internal fun requireCount(
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
internal const val MAX_WORLD_COORDINATE = 30_000_000

/** How many numbers a position is, mirroring the schema's `minItems` and `maxItems`. */
internal const val WORLD_POSITION_AXES = 3

/** The widest a caller may ask a sense to look, in blocks, mirroring the schema. */
internal const val MAX_SENSE_RADIUS = 48

/** The most entities a sense answer may carry, mirroring the schema's `maxItems`. */
internal const val MAX_SENSE_ENTITIES = 24

/** The most nearby blocks a sense answer may carry. */
internal const val MAX_SENSE_BLOCKS = 24

/** The most dropped items a sense answer may carry. */
internal const val MAX_SENSE_ITEMS = 24

/** The most recipe offers a sense answer may carry. */
internal const val MAX_SENSE_RECIPES = 24

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
internal const val MIN_STACK_COUNT = 1

/** The most items one stack may hold, mirroring the schema's `maximum`. */
internal const val MAX_STACK_COUNT = 255

/** The most items one recipe application may return. */
internal const val MAX_RECIPE_RESULT_COUNT = 64

/** The game's own ceiling on a max-health attribute, mirroring the schema's `maximum`. */
internal const val MAX_HEALTH = 1024

/** Vanilla's own food and saturation ceiling, mirroring the schema's `maximum`. */
internal const val MAX_FOOD = 20

/** The last tick of a Minecraft day, mirroring the schema's `maximum`. */
internal const val MAX_TIME_OF_DAY = 23999

/**
 * The widest a caller may say counts as having arrived, in blocks.
 *
 * Half [MAX_RETREAT_DISTANCE], so a tolerance can never be so wide that every block a body
 * could reach satisfies it and a request answers `arrived` without the body having moved at
 * all.
 */
internal const val MAX_MOVE_TOLERANCE = 16

/**
 * The longest a caller may let one movement run, in milliseconds.
 *
 * Below the body process's own control-connection idle window, and the *relation* is what
 * matters rather than either number: a movement is answered on the connection that asked
 * for it and nothing flows on that connection while the body walks, so a deadline at or
 * past the idle window would let the socket's own timer close the channel before the answer
 * could be written. The controller would then see a dropped connection where this contract
 * promises exactly one answer per request. The window itself is the Node process's own
 * number, so each runtime states this one and a test pins the ordering.
 */
internal const val MAX_MOVE_DEADLINE_MS = 20_000

/**
 * The furthest from a named threat a flee may ask the body to get, in blocks.
 *
 * The same number the body's own movement reach is, because a retreat is a walk with the
 * goal inverted and nothing about running away may buy a body a wider reach than walking
 * does. The reach itself is not on this wire: it is measured against the body's live
 * position, which a codec never sees, so it lives where it is enforced and a test pins the
 * two numbers equal.
 */
internal const val MAX_RETREAT_DISTANCE = 32

/**
 * The highest entity index a flee may name.
 *
 * One below [MAX_SENSE_ENTITIES], so every index this codec admits could name an entity of
 * some legal sense answer. An index past the end of the answer that was in fact named is a
 * [MoveOutcome.THREAT_UNNAMED] outcome rather than a malformed field, which is the same
 * division of labour a slot index and [EatOutcome.SLOT_UNNAMED] already have.
 */
internal const val MAX_ENTITY_INDEX = MAX_SENSE_ENTITIES - 1

/** The highest request-local block ordinal a world interaction may name. */
internal const val MAX_BLOCK_INDEX = MAX_SENSE_BLOCKS - 1

/** The highest request-local dropped-item ordinal a collection may name. */
internal const val MAX_ITEM_INDEX = MAX_SENSE_ITEMS - 1

/** The highest request-local recipe ordinal a craft may name. */
internal const val MAX_RECIPE_INDEX = MAX_SENSE_RECIPES - 1

/** The most applications of one recipe a request may ask for. */
internal const val MAX_CRAFTS = 8

/** The most discrete attack packets one request may emit. */
internal const val MAX_ATTACKS = 4

/** The most result items one bounded craft may confirm. */
internal const val MAX_CRAFTED_ITEMS = MAX_CRAFTS * 64

/** The widest one fixed-position combat approach may travel. */
internal const val MAX_PURSUIT_DISTANCE = 8

/** The longest one native world interaction may remain active. */
internal const val MAX_ACTION_DEADLINE_MS = 20_000

/**
 * The widest distance a movement answer may report, in blocks.
 *
 * Derived from the full diagonal between two accepted world positions plus the final
 * bounded accumulated movement sample. A request may have accumulated the whole
 * 128-block allowance before its last sample moves between two accepted positions, and
 * that truthful terminal measurement still has to fit. One ceiling is shared by
 * `travelled`, `separation`, and `separationGained`, as it has been since generation 2.
 */
private const val MAX_FINAL_BOUNDED_TRAVEL = MAX_RETREAT_DISTANCE * 4
private val WORLD_POSITION_DIAMETER =
    sqrt(WORLD_POSITION_AXES.toDouble()) * (MAX_WORLD_COORDINATE * 2).toDouble()
internal val MAX_TRAVEL_DISTANCE =
    ceil(WORLD_POSITION_DIAMETER + MAX_FINAL_BOUNDED_TRAVEL).toInt()

/**
 * Refuses a whole number outside what its field may hold.
 *
 * States the bound and not the value, for the reason [requireBoundedControlId] does.
 */
internal fun requireWithin(
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
internal fun requireReal(
    field: String,
    value: Double,
    highest: Int,
) {
    require(value >= 0 && value <= highest) { "$field must be 0..$highest" }
}

/**
 * Refuses a real number outside a range that reaches below zero.
 *
 * Separate from [requireReal] rather than a widened version of it, because a field that may
 * be negative and a field that may not are different rules and only one field on this wire
 * takes the first: a `separationGained` is a *change*, so a flight the threat won reads as
 * the loss it was. Everything else measured here is a magnitude, and a magnitude admitting
 * a negative would let a body report having walked backwards.
 *
 * The infinity a runaway literal decodes to is refused by the same clause [requireReal]
 * relies on, since every comparison against a NaN or an infinity fails.
 */
internal fun requireSignedReal(
    field: String,
    value: Double,
    widest: Int,
) {
    require(value >= -widest && value <= widest) { "$field must be -$widest..$widest" }
}

/**
 * Refuses a name the game's own registry could not hold.
 *
 * Counted in code points for the reason [requireBoundedControlId] is: the schema's
 * `maxLength` counts them, and `String.length` would measure a non-BMP name double.
 */
internal fun requireResourceName(
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
internal fun requireReady(
    state: LifecycleState,
    what: String,
) {
    require(state == LifecycleState.READY) { "$what reports ${LifecycleState.READY}" }
}
