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
 * Every bound the schema states is re-checked at decode, because nothing on this wire is
 * schema-validated at runtime - `minLength`, `maxLength`, `pattern`, `minimum` and
 * `maximum` are all rules a socket walks straight past. Those scalar bounds and their
 * checks live in `BodyControlFields.kt`, which is this file's other half: the schema too
 * states its scalars once and then names them from the frames, so this file holds the
 * enums, the payload objects and the three frame unions and nothing about what one field
 * may hold. Two schema rules need checks before a binding reaches an `init`: a duplicate
 * member name is
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
 * were missed and why. Every refusal that is a fact about the request is
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
 * Every way a movement can end, as one closed set shared by a walk and a flight.
 *
 * One arrival, six bounds and one cancellation, then three facts about a named threat that
 * only a flight can reach. One set rather than two is what lets a run count arrivals and
 * bounds across both operations without translating between two vocabularies, and the schema
 * deliberately does not narrow it per variant either: a second enum listing eight of these
 * would be a second thing to keep in step with this one.
 *
 * [NO_ROUTE] and [STALLED] are kept apart because they are opposite facts about the world:
 * the ground offered no way there at all, against a way that was found and then abandoned.
 * [SEARCH_OVER_BUDGET] is neither, and it is the one value here that is a fact about the body
 * process rather than about the world: the body's own search budget stopped a search that had
 * not finished, so nothing at all is known about the ground. Folding it into [NO_ROUTE] told a
 * controller the world was closed when the truth was that the body would not look any longer,
 * and for a flight that reads as nowhere to run.
 * [OVER_TRAVELLED] and [TIMED_OUT] are likewise the distance bound and the duration bound,
 * which are reached for unrelated reasons - a body walking in circles spends distance
 * without spending much time, and a body stuck against a wall spends time without spending
 * distance - so folding them together would leave a run unable to say which bound its
 * agents keep hitting. [OUT_OF_RANGE] is the one bound refused before any searching, since
 * a destination past the body's reach is a request this body may not be given rather than
 * ground it failed to cross.
 *
 * [CANCELLED] is an outcome rather than a refusal, because a halted movement did happen: the
 * body went somewhere before it was stopped, and that displacement is worth the same report
 * an arrival gets.
 *
 * The last three are the flight's own, and they are what makes the two answers different
 * shapes: a movement that never resolved a threat has no separation to report, so those
 * three carry none. They mirror the eat path's handle refusals exactly -
 * [SENSE_FORGOTTEN] is the window having closed, [THREAT_UNNAMED] is an index that answer
 * never offered, and [THREAT_GONE] is an entity the world no longer holds.
 */
@Serializable
enum class MoveOutcome {
    @SerialName("arrived")
    ARRIVED,

    @SerialName("outOfRange")
    OUT_OF_RANGE,

    @SerialName("noRoute")
    NO_ROUTE,

    @SerialName("searchOverBudget")
    SEARCH_OVER_BUDGET,

    @SerialName("stalled")
    STALLED,

    @SerialName("overTravelled")
    OVER_TRAVELLED,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("threatUnnamed")
    THREAT_UNNAMED,

    @SerialName("threatGone")
    THREAT_GONE,
}

@Serializable
enum class DigOutcome {
    @SerialName("dug")
    DUG,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("blockUnnamed")
    BLOCK_UNNAMED,

    @SerialName("blockGone")
    BLOCK_GONE,

    @SerialName("blockChanged")
    BLOCK_CHANGED,

    @SerialName("outOfReach")
    OUT_OF_REACH,

    @SerialName("notVisible")
    NOT_VISIBLE,

    @SerialName("notDiggable")
    NOT_DIGGABLE,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class CollectOutcome {
    @SerialName("collected")
    COLLECTED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("itemUnnamed")
    ITEM_UNNAMED,

    @SerialName("itemGone")
    ITEM_GONE,

    @SerialName("itemChanged")
    ITEM_CHANGED,

    @SerialName("outOfRange")
    OUT_OF_RANGE,

    @SerialName("noRoute")
    NO_ROUTE,

    @SerialName("searchOverBudget")
    SEARCH_OVER_BUDGET,

    @SerialName("stalled")
    STALLED,

    @SerialName("overTravelled")
    OVER_TRAVELLED,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class PlaceOutcome {
    @SerialName("placed")
    PLACED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("blockUnnamed")
    BLOCK_UNNAMED,

    @SerialName("slotUnnamed")
    SLOT_UNNAMED,

    @SerialName("blockGone")
    BLOCK_GONE,

    @SerialName("blockChanged")
    BLOCK_CHANGED,

    @SerialName("slotEmpty")
    SLOT_EMPTY,

    @SerialName("slotChanged")
    SLOT_CHANGED,

    @SerialName("itemNotHeld")
    ITEM_NOT_HELD,

    @SerialName("notPlaceable")
    NOT_PLACEABLE,

    @SerialName("destinationBlocked")
    DESTINATION_BLOCKED,

    @SerialName("outOfReach")
    OUT_OF_REACH,

    @SerialName("notVisible")
    NOT_VISIBLE,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class EquipOutcome {
    @SerialName("equipped")
    EQUIPPED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("slotUnnamed")
    SLOT_UNNAMED,

    @SerialName("slotEmpty")
    SLOT_EMPTY,

    @SerialName("slotChanged")
    SLOT_CHANGED,

    @SerialName("incompatible")
    INCOMPATIBLE,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class CraftOutcome {
    @SerialName("crafted")
    CRAFTED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("recipeUnnamed")
    RECIPE_UNNAMED,

    @SerialName("ingredientsChanged")
    INGREDIENTS_CHANGED,

    @SerialName("recipeUnavailable")
    RECIPE_UNAVAILABLE,

    @SerialName("workstationGone")
    WORKSTATION_GONE,

    @SerialName("workstationChanged")
    WORKSTATION_CHANGED,

    @SerialName("workstationOutOfReach")
    WORKSTATION_OUT_OF_REACH,

    @SerialName("workstationNotVisible")
    WORKSTATION_NOT_VISIBLE,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("bodyDied")
    BODY_DIED,

    @SerialName("disconnected")
    DISCONNECTED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class AttackOutcome {
    @SerialName("attacked")
    ATTACKED,

    @SerialName("targetDied")
    TARGET_DIED,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("targetUnnamed")
    TARGET_UNNAMED,

    @SerialName("targetGone")
    TARGET_GONE,

    @SerialName("targetChanged")
    TARGET_CHANGED,

    @SerialName("slotUnnamed")
    SLOT_UNNAMED,

    @SerialName("slotEmpty")
    SLOT_EMPTY,

    @SerialName("slotChanged")
    SLOT_CHANGED,

    @SerialName("healthBound")
    HEALTH_BOUND,

    @SerialName("outOfRange")
    OUT_OF_RANGE,

    @SerialName("noRoute")
    NO_ROUTE,

    @SerialName("searchOverBudget")
    SEARCH_OVER_BUDGET,

    @SerialName("stalled")
    STALLED,

    @SerialName("overTravelled")
    OVER_TRAVELLED,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("bodyDied")
    BODY_DIED,

    @SerialName("disconnected")
    DISCONNECTED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class SleepOutcome {
    @SerialName("woke")
    WOKE,

    @SerialName("senseForgotten")
    SENSE_FORGOTTEN,

    @SerialName("bedUnnamed")
    BED_UNNAMED,

    @SerialName("bedGone")
    BED_GONE,

    @SerialName("bedChanged")
    BED_CHANGED,

    @SerialName("notBed")
    NOT_BED,

    @SerialName("wrongDimension")
    WRONG_DIMENSION,

    @SerialName("outOfReach")
    OUT_OF_REACH,

    @SerialName("notVisible")
    NOT_VISIBLE,

    @SerialName("occupied")
    OCCUPIED,

    @SerialName("notNight")
    NOT_NIGHT,

    @SerialName("unsafe")
    UNSAFE,

    @SerialName("sleepRefused")
    SLEEP_REFUSED,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("bodyDied")
    BODY_DIED,

    @SerialName("disconnected")
    DISCONNECTED,

    @SerialName("libraryFailure")
    LIBRARY_FAILURE,
}

@Serializable
enum class BlockFace {
    @SerialName("down")
    DOWN,

    @SerialName("up")
    UP,

    @SerialName("north")
    NORTH,

    @SerialName("south")
    SOUTH,

    @SerialName("west")
    WEST,

    @SerialName("east")
    EAST,
}

@Serializable
enum class EquipmentDestination {
    @SerialName("hand")
    HAND,

    @SerialName("offHand")
    OFF_HAND,

    @SerialName("head")
    HEAD,

    @SerialName("torso")
    TORSO,

    @SerialName("legs")
    LEGS,

    @SerialName("feet")
    FEET,
}

/**
 * The three outcomes that say the body had no threat to measure against.
 *
 * They are the whole of what makes the two movement reports different shapes, and both
 * relations that read them are stated in a codec rather than in the schema because neither
 * is expressible there: a walk names no threat, so it may not answer one of these; and a
 * flight that ends on one carries no separation, because there was nothing to measure from.
 */
internal val THREAT_REFUSALS: Set<MoveOutcome> =
    setOf(
        MoveOutcome.SENSE_FORGOTTEN,
        MoveOutcome.THREAT_UNNAMED,
        MoveOutcome.THREAT_GONE,
    )

/**
 * The three vitals the game keeps for a player, never combined into one.
 *
 * Health is damage to the body; food is how full the agent is; saturation is the hidden
 * reserve vanilla spends before food falls. A body can be beaten to death on a full
 * stomach and can starve without a scratch, so a wire reporting one figure for
 * "condition" could not say what ended an agent.
 *
 * The player adapter passes food and saturation through separately.
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

/** One non-air block inside the body's bounded native interaction reach. */
@Serializable
data class SensedBlock(
    val what: String,
    val position: List<Int>,
    val visible: Boolean,
) {
    init {
        requireResourceName("what", what)
        requireBlockPosition("position", position)
    }
}

/** One dropped-item entity inside a sense request's radius. */
@Serializable
data class SensedItem(
    val what: String,
    val count: Int,
    val position: List<Double>,
    val visible: Boolean,
) {
    init {
        requireResourceName("what", what)
        requireWithin("count", count, MIN_STACK_COUNT..MAX_STACK_COUNT)
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

/** One bounded recipe result offered by this exact sense answer. */
@Serializable
data class SensedRecipe(
    val what: String,
    val resultCount: Int,
    val maxCrafts: Int,
    val requiresTable: Boolean,
) {
    init {
        requireResourceName("what", what)
        requireWithin("resultCount", resultCount, 1..MAX_RECIPE_RESULT_COUNT)
        requireWithin("maxCrafts", maxCrafts, 1..MAX_CRAFTS)
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
            requireWithin("limit", limit, 1..MAX_SENSE_OFFERS_PER_KIND)
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

    /**
     * Walk to one block the caller names, under the caller's own bounds.
     *
     * The destination is the controller having decided where the agent goes, which is the
     * whole point: what would make the body a decider is a target it chose itself, or a
     * standing instruction to keep going somewhere. There is neither. One request walks one
     * route once and is answered when that route ends.
     *
     * [tolerance] is the shape of the goal rather than a bound on effort - zero says the body
     * must stand on the named block itself, which is a perfectly reachable request - while
     * [timeoutMs] is a bound and so may not be zero: a caller allowing no time has not asked
     * the body to go anywhere.
     *
     * [sprint] is the caller accepting the hunger sprinting costs. It is on the request and
     * not a setting of the body's, because spending food to arrive sooner is a trade only
     * something that knows what the agent is doing can make.
     */
    @Serializable
    @SerialName("moveTo")
    data class MoveTo(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val destination: List<Double>,
        val tolerance: Int,
        val timeoutMs: Int,
        val sprint: Boolean,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireWorldPosition("destination", destination)
            requireWithin("tolerance", tolerance, 0..MAX_MOVE_TOLERANCE)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_MOVE_DEADLINE_MS)
        }
    }

    /**
     * Walk away from one entity a named sense answer reported, under the caller's own bounds.
     *
     * [senseId] and [entity] together are a request-scoped handle on one creature, exactly as
     * [Eat]'s `senseId` and `slot` are one on a stack: [entity] is the index into that
     * answer's own `entities` list, so a threat named against a sense the body has forgotten
     * is [MoveOutcome.SENSE_FORGOTTEN] rather than resolved against whatever is nearby now.
     * A flight names one creature and never a species, for the reason the perception
     * contract's `Intent.Flee` does - a body that picked the thing to run from would be
     * choosing what an agent fears.
     *
     * The handles differ in what they name, though, and that difference is why the outcomes
     * differ too: a slot is a position whose contents change, while an entity is an identity
     * that can leave the world - so this path has a [MoveOutcome.THREAT_GONE] where the eat
     * path has a `slotChanged`.
     *
     * [distance] is how far away the caller wants the body to get, not where to go: the
     * ground decides that. A body that chose the distance would be deciding how frightened
     * to be.
     */
    @Serializable
    @SerialName("flee")
    data class Flee(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val entity: Int,
        val distance: Int,
        val timeoutMs: Int,
        val sprint: Boolean,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("entity", entity, 0..MAX_ENTITY_INDEX)
            requireWithin("distance", distance, 1..MAX_RETREAT_DISTANCE)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_MOVE_DEADLINE_MS)
        }
    }

    /** Dig one block offered by one retained sense answer. */
    @Serializable
    @SerialName("dig")
    data class Dig(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val block: Int,
        val timeoutMs: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("block", block, 0..MAX_BLOCK_INDEX)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Collect one exact dropped item offered by one retained sense answer. */
    @Serializable
    @SerialName("collect")
    data class Collect(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val item: Int,
        val approach: Boolean,
        val timeoutMs: Int,
        val sprint: Boolean,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("item", item, 0..MAX_ITEM_INDEX)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Place one offered stack already held against one face of one offered block. */
    @Serializable
    @SerialName("place")
    data class Place(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val block: Int,
        val slot: Int,
        val face: BlockFace,
        val timeoutMs: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("block", block, 0..MAX_BLOCK_INDEX)
            requireWithin("slot", slot, 0..MAX_SLOT_INDEX)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Equip one offered stack into one chosen bounded equipment destination. */
    @Serializable
    @SerialName("equip")
    data class Equip(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val slot: Int,
        val destination: EquipmentDestination,
        val timeoutMs: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("slot", slot, 0..MAX_SLOT_INDEX)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Apply one explicitly offered recipe a bounded number of times. */
    @Serializable
    @SerialName("craft")
    data class Craft(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val recipe: Int,
        val crafts: Int,
        val timeoutMs: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("recipe", recipe, 0..MAX_RECIPE_INDEX)
            requireWithin("crafts", crafts, 1..MAX_CRAFTS)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Attack one retained living target with one retained stack and one strike budget. */
    @Serializable
    @SerialName("attack")
    data class Attack(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val entity: Int,
        val slot: Int,
        val attacks: Int,
        val approach: Boolean,
        val maxPursuitDistance: Double,
        val minHealth: Double,
        val timeoutMs: Int,
        val sprint: Boolean,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("entity", entity, 0..MAX_ENTITY_INDEX)
            requireWithin("slot", slot, 0..MAX_SLOT_INDEX)
            requireWithin("attacks", attacks, 1..MAX_ATTACKS)
            requireReal("maxPursuitDistance", maxPursuitDistance, MAX_PURSUIT_DISTANCE)
            requireReal("minHealth", minHealth, MAX_HEALTH)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /** Sleep in one exact bed offered by one retained sense answer. */
    @Serializable
    @SerialName("sleep")
    data class Sleep(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val senseId: String,
        val block: Int,
        val timeoutMs: Int,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireBoundedControlId("senseId", senseId)
            requireWithin("block", block, 0..MAX_BLOCK_INDEX)
            requireWithin("timeoutMs", timeoutMs, 1..MAX_ACTION_DEADLINE_MS)
        }
    }

    /**
     * Stop whatever movement is running, and leave the body standing still.
     *
     * It names no movement, because a body runs at most one at a time: a request naming which
     * to stop would imply there could be a choice. It carries no payload for the same reason
     * [Status] does not - there is nothing to bound about stopping.
     *
     * It is always accepted, even with nothing running, so a controller recovering from a
     * lost answer can make the body's state certain in one round trip rather than having to
     * work out first whether it needs to.
     */
    @Serializable
    @SerialName("halt")
    data class Halt(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
        }
    }

    /** Send one bounded line through this agent's own Mineflayer player chat; never a console broadcast. */
    @Serializable
    @SerialName("say")
    data class Say(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        val text: String,
    ) : BodyRequest {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireSayText(text)
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
     * Still deliberately no player or world reading, now that a sense answer carries it:
     * a status is what a controller asks to know whether the
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
        // the sealed interface's own, and the nine readings are what one sense answers.
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
        val blocks: List<SensedBlock>,
        val blocksOmitted: Long,
        val items: List<SensedItem>,
        val itemsOmitted: Long,
        val inventory: List<SensedStack>,
        val recipes: List<SensedRecipe>,
        val recipesOmitted: Long,
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
            require(blocks.size <= MAX_SENSE_BLOCKS) {
                "blocks must be at most $MAX_SENSE_BLOCKS"
            }
            requireCount("blocksOmitted", blocksOmitted)
            require(blocksOmitted == 0L || blocks.isNotEmpty()) {
                "blocksOmitted must be 0 when no block is reported"
            }
            require(items.size <= MAX_SENSE_ITEMS) {
                "items must be at most $MAX_SENSE_ITEMS"
            }
            requireCount("itemsOmitted", itemsOmitted)
            require(itemsOmitted == 0L || items.isNotEmpty()) {
                "itemsOmitted must be 0 when no dropped item is reported"
            }
            require(inventory.size <= MAX_SENSE_STACKS) {
                "inventory must be at most $MAX_SENSE_STACKS stacks"
            }
            require(inventory.zipWithNext().all { (earlier, later) -> earlier.slot < later.slot }) {
                "inventory slots must ascend, and no slot may be reported twice"
            }
            require(recipes.size <= MAX_SENSE_RECIPES) {
                "recipes must be at most $MAX_SENSE_RECIPES"
            }
            requireCount("recipesOmitted", recipesOmitted)
            require(recipesOmitted == 0L || recipes.isNotEmpty()) {
                "recipesOmitted must be 0 when no recipe is reported"
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

    /**
     * What became of one walk, and the journey it was.
     *
     * **[state] is the moment the request was admitted**, as an [EatReport]'s is and for a
     * sharper reason: a walk is answered when its route ends, which is seconds later, so the
     * body may have died or disconnected in between. `ready` here is the state the walk was
     * taken on in, which is where its numbers came from.
     *
     * Two positions and never one displacement, because a caller can subtract and a body that
     * subtracted would be a second measurement able to disagree with the readings it was
     * derived from. [travelled] is the one number nothing above this wire could derive: it is
     * the distance actually walked along the route rather than the straight line between the
     * ends, so a body that went round a wall reports the detour, and it is the number the
     * distance bound was applied to.
     *
     * [outcome] is the shared [MoveOutcome] set, of which a walk may never answer one of the
     * three [THREAT_REFUSALS]: a walk names no threat, so an answer claiming one would be
     * reporting on a handle the request never carried. The schema admits it, so the relation
     * is stated here and in the Node codec, as every other relation of this contract is.
     */
    @Serializable
    @SerialName("moveTo")
    data class MoveReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: MoveOutcome,
        val from: List<Double>,
        val position: List<Double>,
        @Serializable(with = CanonicalDistance::class)
        val travelled: Double,
        val elapsedMs: Long,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a walk")
            require(outcome !in THREAT_REFUSALS) { "a walk names no threat, so it cannot answer $outcome" }
            requireJourney(outcome, from, position, travelled, elapsedMs)
        }
    }

    /**
     * What became of one flight, the journey it was, and what it bought.
     *
     * The walk's own fields and then the two only a flight can measure, which is why they are
     * an extension rather than a second shape: the shared prefix is what lets a run count
     * arrivals, bounds and distances across walks and flights without translating between two
     * vocabularies.
     *
     * [separation] and [separationGained] are present exactly when the outcome is not one of
     * the three [THREAT_REFUSALS], because those three are the outcomes where the body never
     * had a threat to measure from. A refusal reporting a separation would be claiming to have
     * measured against a creature it never resolved, and a completed flight reporting none
     * would leave a run unable to say whether running away worked.
     *
     * [separationGained] is the one signed number on this wire, and that is the point of it: a
     * flight the threat won reads as the loss it was rather than as a distance covered. It is
     * stated beside [separation] rather than left to be derived, because deriving it needs the
     * separation the flight *started* at, which no other field carries.
     */
    @Serializable
    @SerialName("flee")
    data class FleeReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: MoveOutcome,
        val from: List<Double>,
        val position: List<Double>,
        @Serializable(with = CanonicalDistance::class)
        val travelled: Double,
        val elapsedMs: Long,
        val separation: Double? = null,
        val separationGained: Double? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a flight")
            requireJourney(outcome, from, position, travelled, elapsedMs)
            val measured = outcome !in THREAT_REFUSALS
            require((separation != null) == measured) {
                "separation is present exactly when a threat was resolved"
            }
            require((separationGained != null) == measured) {
                "separationGained is present exactly when a threat was resolved"
            }
            separation?.let { requireReal("separation", it, MAX_TRAVEL_DISTANCE) }
            separationGained?.let { requireSignedReal("separationGained", it, MAX_TRAVEL_DISTANCE) }
        }
    }

    /** What became of one request to dig one offered block. */
    @Serializable
    @SerialName("dig")
    data class DigReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: DigOutcome,
        val elapsedMs: Long,
        val attempted: Boolean,
        val tool: String? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a dig")
            requireCount("elapsedMs", elapsedMs)
            val handedToMineflayer =
                outcome in
                    setOf(
                        DigOutcome.DUG,
                        DigOutcome.TIMED_OUT,
                        DigOutcome.CANCELLED,
                        DigOutcome.LIBRARY_FAILURE,
                    )
            require(attempted == handedToMineflayer) {
                "attempted is true exactly when the dig was handed to Mineflayer"
            }
            require(tool == null || attempted) {
                "tool is absent when the dig was refused before Mineflayer was invoked"
            }
            tool?.let { requireResourceName("tool", it) }
        }
    }

    /** What became of collecting one exact dropped-item entity and the journey it took. */
    @Serializable
    @SerialName("collect")
    data class CollectReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: CollectOutcome,
        val from: List<Double>,
        val position: List<Double>,
        @Serializable(with = CanonicalDistance::class)
        val travelled: Double,
        val elapsedMs: Long,
        val what: String? = null,
        val count: Int? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a collection")
            requireWorldPosition("from", from)
            requireWorldPosition("position", position)
            requireReal("travelled", travelled, MAX_TRAVEL_DISTANCE)
            requireCount("elapsedMs", elapsedMs)
            require(outcome != CollectOutcome.OUT_OF_RANGE || travelled == 0.0) {
                "outOfRange is decided before movement, so travelled must be zero"
            }
            val collected = outcome == CollectOutcome.COLLECTED
            require((what != null) == collected) {
                "what is present exactly when the outcome is ${CollectOutcome.COLLECTED}"
            }
            require((count != null) == collected) {
                "count is present exactly when the outcome is ${CollectOutcome.COLLECTED}"
            }
            what?.let { requireResourceName("what", it) }
            count?.let { requireWithin("count", it, MIN_STACK_COUNT..MAX_STACK_COUNT) }
        }
    }

    /** What became of placing one held offered stack against one offered support block. */
    @Serializable
    @SerialName("place")
    data class PlaceReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: PlaceOutcome,
        val elapsedMs: Long,
        val what: String? = null,
        val position: List<Int>? = null,
        val remaining: Int? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a placement")
            requireCount("elapsedMs", elapsedMs)
            val placed = outcome == PlaceOutcome.PLACED
            require((what != null) == placed) {
                "what is present exactly when the outcome is ${PlaceOutcome.PLACED}"
            }
            require((position != null) == placed) {
                "position is present exactly when the outcome is ${PlaceOutcome.PLACED}"
            }
            require((remaining != null) == placed) {
                "remaining is present exactly when the outcome is ${PlaceOutcome.PLACED}"
            }
            what?.let { requireResourceName("what", it) }
            position?.let { requireBlockPosition("position", it) }
            remaining?.let { requireWithin("remaining", it, 0..MAX_STACK_COUNT) }
        }
    }

    /** What became of equipping one offered inventory stack. */
    @Serializable
    @SerialName("equip")
    data class EquipReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: EquipOutcome,
        val elapsedMs: Long,
        val what: String? = null,
        val count: Int? = null,
        val toSlot: Int? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "an equip")
            requireCount("elapsedMs", elapsedMs)
            val equipped = outcome == EquipOutcome.EQUIPPED
            require((what != null) == equipped) {
                "what is present exactly when the outcome is ${EquipOutcome.EQUIPPED}"
            }
            require((count != null) == equipped) {
                "count is present exactly when the outcome is ${EquipOutcome.EQUIPPED}"
            }
            require((toSlot != null) == equipped) {
                "toSlot is present exactly when the outcome is ${EquipOutcome.EQUIPPED}"
            }
            what?.let { requireResourceName("what", it) }
            count?.let { requireWithin("count", it, MIN_STACK_COUNT..MAX_STACK_COUNT) }
            toSlot?.let { requireWithin("toSlot", it, 0..MAX_SLOT_INDEX) }
        }
    }

    /** What became of one explicitly offered craft. */
    @Serializable
    @SerialName("craft")
    data class CraftReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: CraftOutcome,
        val elapsedMs: Long,
        val attempted: Boolean,
        val what: String? = null,
        val count: Int? = null,
        val crafts: Int? = null,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a craft")
            requireCount("elapsedMs", elapsedMs)
            require(outcome != CraftOutcome.CRAFTED || attempted) {
                "a completed craft was handed to Mineflayer"
            }
            require(
                outcome !in setOf(CraftOutcome.SENSE_FORGOTTEN, CraftOutcome.RECIPE_UNNAMED) ||
                    !attempted,
            ) {
                "a missing recipe authority was not handed to Mineflayer"
            }
            val crafted = outcome == CraftOutcome.CRAFTED
            require((what != null) == crafted) {
                "what is present exactly when the outcome is ${CraftOutcome.CRAFTED}"
            }
            require((count != null) == crafted) {
                "count is present exactly when the outcome is ${CraftOutcome.CRAFTED}"
            }
            require((crafts != null) == crafted) {
                "crafts is present exactly when the outcome is ${CraftOutcome.CRAFTED}"
            }
            what?.let { requireResourceName("what", it) }
            count?.let { requireWithin("count", it, 1..MAX_CRAFTED_ITEMS) }
            crafts?.let { requireWithin("crafts", it, 1..MAX_CRAFTS) }
        }
    }

    /** What became of one bounded combat request and its optional fixed approach. */
    @Serializable
    @SerialName("attack")
    data class AttackReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: AttackOutcome,
        val from: List<Double>,
        val position: List<Double>,
        @Serializable(with = CanonicalDistance::class)
        val travelled: Double,
        val elapsedMs: Long,
        val attacks: Int,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "an attack")
            requireWorldPosition("from", from)
            requireWorldPosition("position", position)
            requireReal("travelled", travelled, MAX_TRAVEL_DISTANCE)
            requireCount("elapsedMs", elapsedMs)
            requireWithin("attacks", attacks, 0..MAX_ATTACKS)
            require(outcome != AttackOutcome.ATTACKED || attacks > 0) {
                "attacked requires at least one attack packet"
            }
            require(outcome != AttackOutcome.TARGET_DIED || attacks > 0) {
                "targetDied requires at least one attack packet from this request"
            }
        }
    }

    /** What became of one exact offered bed, held through vanilla wake or cleanup. */
    @Serializable
    @SerialName("sleep")
    data class SleepReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: SleepOutcome,
        val elapsedMs: Long,
        val entered: Boolean,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a sleep")
            requireCount("elapsedMs", elapsedMs)
            require(outcome != SleepOutcome.WOKE || entered) {
                "woke requires an observed sleep entry"
            }
            val beforeEntry =
                outcome in
                    setOf(
                        SleepOutcome.SENSE_FORGOTTEN,
                        SleepOutcome.BED_UNNAMED,
                        SleepOutcome.BED_GONE,
                        SleepOutcome.BED_CHANGED,
                        SleepOutcome.NOT_BED,
                        SleepOutcome.WRONG_DIMENSION,
                        SleepOutcome.OUT_OF_REACH,
                        SleepOutcome.NOT_VISIBLE,
                        SleepOutcome.OCCUPIED,
                        SleepOutcome.NOT_NIGHT,
                        SleepOutcome.UNSAFE,
                        SleepOutcome.SLEEP_REFUSED,
                    )
            require(!beforeEntry || !entered) {
                "a pre-entry sleep refusal cannot claim an observed entry"
            }
        }
    }

    /**
     * Whether a halt found a movement to stop.
     *
     * [stopped] is a reading and not a verdict: the request is accepted either way, and false
     * says the body was already standing still. That is worth reporting because a controller
     * halting after a lost answer learns from it whether the answer it lost was the movement's
     * own.
     *
     * It carries no position, deliberately. The movement it stopped is answered in its own
     * [MoveReport] or [FleeReport] with [MoveOutcome.CANCELLED], and that answer states the
     * journey; stating it here as well would give a run two accounts of one journey, taken a
     * moment apart and able to disagree.
     */
    @Serializable
    @SerialName("halt")
    data class HaltReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val stopped: Boolean,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a halt")
        }
    }

    /** What became of one say; it echoes no text, since the server's chat event is the evidence. */
    @Serializable
    @SerialName("say")
    data class SayReport(
        override val protocol: Int,
        override val requestId: String,
        override val agentId: String,
        override val state: LifecycleState,
        val outcome: SayOutcome,
    ) : BodyResponse {
        init {
            requireEnvelope(protocol, requestId, agentId)
            requireReady(state, "a say")
        }
    }
}

/**
 * Refuses a journey whose two ends, distance or duration are not ones this wire can hold.
 *
 * Shared by the two movement answers rather than written twice, because the journey is the
 * same measurement whichever goal it was made toward, and a bound stated twice is a bound one
 * of them can drift from.
 */
private fun requireJourney(
    outcome: MoveOutcome,
    from: List<Double>,
    position: List<Double>,
    travelled: Double,
    elapsedMs: Long,
) {
    requireWorldPosition("from", from)
    requireWorldPosition("position", position)
    requireReal("travelled", travelled, MAX_TRAVEL_DISTANCE)
    require(outcome != MoveOutcome.OUT_OF_RANGE || travelled == 0.0) {
        "outOfRange is decided before movement, so travelled must be zero"
    }
    requireCount("elapsedMs", elapsedMs)
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
