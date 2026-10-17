/*
 * Kotlin kotlinx-serialization bindings for the Loom perception/decision contract.
 *
 * The single source of truth for these types is `contract/loom.schema.json` at
 * the repository root. These hand-written types mirror that schema and are kept
 * honest by ContractConformanceTest, which validates the shared fixtures against
 * the schema and asserts that every fixture round-trips through these types
 * byte-for-byte. If the schema and these types drift, the build fails.
 *
 * Wire tagging is internal: the Trigger and Intent unions carry a discriminator
 * property (`event` and `action` respectively) with the variant's fields inlined
 * alongside. This mirrors the Rust side's serde `#[serde(tag = ...)]`
 * configuration byte-for-byte.
 *
 * This module carries no dependency on any server API: it is plain data plus
 * serialization, the entire surface the mind and body exchange.
 */
@file:OptIn(ExperimentalSerializationApi::class)

package dev.loom.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Number of components in a world coordinate: `[x, y, z]`. */
private const val COORDINATE_DIMENSIONS = 3

/**
 * The shortest a ref may be, mirroring the schema's `minLength`.
 *
 * Stated here once for this whole runtime rather than at each field that carries a
 * ref, because two copies of a bound are two bounds that can disagree. The schema
 * gives `EntityRef` and `SlotRef` the same bound deliberately: they are the same
 * shape of handle and differ only in what they resolve to.
 */
private const val REF_MIN_CHARS = 1

/** The longest a ref may be, mirroring the schema's `maxLength`. */
private const val REF_MAX_CHARS = 8

/**
 * Refuses a ref that is blank or longer than a ref can be, letting an absent one
 * through.
 *
 * The bound is a type rule on both sides of the contract for the same reason
 * [WorldState.ticksUntilNightfall]'s is: nothing on either wire is
 * schema-validated at runtime, so `minLength`/`maxLength` alone is a rule a socket
 * walks straight past. Both ends are worth refusing rather than merely odd. A
 * blank ref names nothing the body can resolve, and it is what an absent handle
 * becomes if anything upstream ever fills the field instead of omitting it - so
 * refusing it keeps "a ref is present" and "a ref means something" the same
 * statement. An over-long one is a handle no request ever issued, which is a mind
 * having invented or rewritten one rather than named back what it was given.
 *
 * Length is counted in code points, as the schema counts it and as the Rust side's
 * `chars().count()` counts it. `String.length` is UTF-16 code units, so a handle of
 * non-BMP characters would measure double here: the two runtimes would then disagree
 * about the same bound, which is exactly what stating it once per runtime is meant to
 * rule out.
 */
private fun requireBoundedRef(handle: String?) {
    if (handle == null) return
    val characters = handle.codePointCount(0, handle.length)
    require(characters in REF_MIN_CHARS..REF_MAX_CHARS) {
        "ref must be $REF_MIN_CHARS..$REF_MAX_CHARS characters, got $characters"
    }
}

/** Vanilla's food and saturation ceiling. */
const val MAX_FOOD_LEVEL: Int = 20

/**
 * The most occupied slots a [SelfState.inventorySummary] may state, mirroring the
 * schema's `maxItems`.
 *
 * Forty-six, the complete player inventory window. Unlike the independently bounded
 * [Perception.surroundings], this describes a different container: a summary of
 * forty-seven slots is not a body having chosen to say more, it is a body that read
 * something which is not its own inventory.
 *
 * Narrowed at decode rather than left to the schema, because nothing on either wire
 * is schema-validated at runtime - `maxItems` alone is a rule a socket walks straight
 * past, exactly as `minimum` and `maxLength` are.
 */
const val MAX_INVENTORY_STACKS: Int = 46

/** The most exact recipe offers one perception may carry. */
const val MAX_CRAFTING_OPTIONS: Int = 24

/** The most entity, block, and dropped-item observations one perception may combine. */
const val MAX_PERCEPTION_OBSERVATIONS: Int = 144

/** The most applications one craft intent may request. */
const val MAX_CRAFTS_PER_INTENT: Long = 8

/** Vanilla's largest result stack from one recipe application. */
private const val MAX_RECIPE_RESULT_COUNT: Long = 64

/** The most swings one attack intent may request. */
const val MAX_ATTACKS_PER_INTENT: Long = 4

/**
 * Importance tier that drives model routing on the mind side and is stamped onto
 * every decision's provenance for the decision log.
 */
@Serializable
enum class DecisionImportance {
    @SerialName("ROUTINE")
    ROUTINE,

    @SerialName("NOTABLE")
    NOTABLE,

    @SerialName("PIVOTAL")
    PIVOTAL,
}

/**
 * A horizontal compass bearing from the agent to something it can see.
 *
 * Four cardinal points and no finer: it is the engine's own nearest-axis answer for
 * the delta, and a bearing precise enough to look like a heading would be a
 * precision the body never measured. [HERE] is the one value that is not a direction:
 * the thing lies in the agent's own block column, such as the block it stands on, for
 * which any compass point would be invented.
 */
@Serializable
enum class Bearing {
    @SerialName("north")
    NORTH,

    @SerialName("east")
    EAST,

    @SerialName("south")
    SOUTH,

    @SerialName("west")
    WEST,

    @SerialName("here")
    HERE,
}

/** The vanilla shape one request-scoped world observation describes. */
@Serializable
enum class ObservationKind {
    @SerialName("entity")
    ENTITY,

    @SerialName("block")
    BLOCK,

    @SerialName("droppedItem")
    DROPPED_ITEM,
}

/** One face of a sensed support block, chosen by a place intent. */
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

/** The bounded player equipment destination chosen by an equip intent. */
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

/** Which part of the world's day it is. */
@Serializable
enum class DayPhase {
    @SerialName("dawn")
    DAWN,

    @SerialName("day")
    DAY,

    @SerialName("dusk")
    DUSK,

    @SerialName("night")
    NIGHT,
}

/**
 * The bounded world clock the body reveals.
 *
 * Survival depends on it - what is safe at midday is not safe at night - and it is a
 * fact about the world rather than about the body, which is why it is named
 * separately from the self-state it rides in.
 */
@Serializable
data class WorldState(
    /** Which part of the day it is. */
    val dayPhase: DayPhase,
    /**
     * Whether it is daylight where the body stands, as the level itself answers it.
     *
     * Distinct from [dayPhase] because a dimension with a fixed time has a day time
     * and no daylight.
     */
    val isDay: Boolean,
    /**
     * Ticks from now until the next nightfall, wrapping the world's day.
     *
     * Always positive: during the night it counts to the following nightfall, and
     * [dayPhase] is what says the night is already here.
     */
    val ticksUntilNightfall: Long,
) {
    init {
        require(ticksUntilNightfall > 0) {
            "ticksUntilNightfall must be positive, got $ticksUntilNightfall"
        }
    }
}

/**
 * One occupied slot of the body's own inventory, as the body describes it.
 *
 * The whole of what the mind is told about what it is carrying, and the only place
 * an [Intent.Eat] can get a handle from.
 */
@Serializable
data class InventoryStack(
    /**
     * The handle by which a decision may name this exact stack back to the body.
     *
     * Narrowed at decode by [requireBoundedRef], exactly as an [Observation.ref] is.
     */
    val ref: String,
    /**
     * What the stack holds, as the body describes it.
     *
     * A description for the mind to reason with, never the handle: two slots can hold
     * the same item and be different stacks.
     */
    val what: String,
    /** How many items the stack holds. At least one, because an empty slot is not reported. */
    val count: Long,
) {
    init {
        requireBoundedRef(ref)
        require(count >= 1) { "an inventory stack must hold at least one item, got $count" }
    }
}

/** One exact currently craftable recipe offered by this perception. */
@Serializable
data class CraftingOption(
    /** Request-scoped handle retaining the exact recipe and any required workstation. */
    val ref: String,
    /** The result item, as the body describes it. */
    val what: String,
    /** How many result items one recipe application produces. */
    val resultCount: Long,
    /** How many applications current ingredients and Loom's output bound permit. */
    val maxCrafts: Long,
    /** Whether the retained recipe is tied to one exact reachable crafting table. */
    val requiresTable: Boolean,
) {
    init {
        requireBoundedRef(ref)
        require(resultCount in 1..MAX_RECIPE_RESULT_COUNT) {
            "resultCount must be in 1..$MAX_RECIPE_RESULT_COUNT, got $resultCount"
        }
        require(maxCrafts in 1..MAX_CRAFTS_PER_INTENT) {
            "maxCrafts must be in 1..$MAX_CRAFTS_PER_INTENT, got $maxCrafts"
        }
    }
}

/**
 * The bounded self-state the body chose to reveal. Owned primitives only, so the
 * mind cannot reach back into world state through it.
 */
@Serializable
data class SelfState(
    /** Current health of the agent's body. */
    val health: Double,
    /** The player's vanilla food level, separate from health and saturation. */
    val food: Int,
    /** Vanilla saturation, the reserve spent before [food] falls. */
    val saturation: Double,
    /** World position `[x, y, z]`. Continuous coordinates. */
    val position: List<Double>,
    /**
     * The body's own inventory, one entry per occupied slot and nothing for an empty
     * one, bounded by the [MAX_INVENTORY_STACKS] slots a body has.
     */
    val inventorySummary: List<InventoryStack>,
    /** Exact currently craftable recipes backed by this same request-scoped sense. */
    val craftingOptions: List<CraftingOption>,
    /** Craftable recipes omitted by the bounded option list. */
    val craftingOptionsOmitted: Long,
    /** The bounded world clock the body reveals. */
    val world: WorldState,
) {
    init {
        require(position.size == COORDINATE_DIMENSIONS) {
            "position must have exactly $COORDINATE_DIMENSIONS components [x, y, z], got ${position.size}"
        }
        require(food in 0..MAX_FOOD_LEVEL) {
            "food must be in 0..$MAX_FOOD_LEVEL, got $food"
        }
        require(saturation in 0.0..MAX_FOOD_LEVEL.toDouble()) {
            "saturation must be in 0..$MAX_FOOD_LEVEL, got $saturation"
        }
        require(inventorySummary.size <= MAX_INVENTORY_STACKS) {
            "inventorySummary must state at most $MAX_INVENTORY_STACKS occupied slots, " +
                "got ${inventorySummary.size}"
        }
        require(craftingOptions.size <= MAX_CRAFTING_OPTIONS) {
            "craftingOptions must state at most $MAX_CRAFTING_OPTIONS recipes, " +
                "got ${craftingOptions.size}"
        }
        require(craftingOptionsOmitted >= 0) {
            "craftingOptionsOmitted must not be negative, got $craftingOptionsOmitted"
        }
    }
}

/**
 * One already-summarized observation the body chose to reveal. The perception
 * carries a list of these instead of a live world handle.
 */
@Serializable
data class Observation(
    /** Whether this observation is a living entity, a block, or a dropped item. */
    val kind: ObservationKind,
    /** Human-readable summary of the observed thing. */
    val what: String,
    /** Distance from the agent to the observed thing, in blocks. */
    val distance: Double,
    /** The horizontal bearing from the agent to the observed thing. */
    val direction: Bearing,
    /**
     * How far above the agent the observed thing is, in blocks; negative if it is
     * below.
     *
     * Separate from the bearing because a bearing is horizontal, and a thing on a
     * cliff is not reachable by walking toward it.
     */
    val verticalOffset: Double,
    /**
     * Whether the body has an unobstructed line of sight to the observed thing.
     *
     * `false` means the body knows it is there and cannot see it - through a wall, or
     * round a corner - which is a different situation from not knowing at all.
     */
    val visible: Boolean,
    /**
     * The handle by which a decision may name this particular thing back to the body.
     *
     * Its length is narrowed at decode by [requireBoundedRef], because a ref the body
     * could never have issued is worth refusing where it arrives.
     */
    val ref: String,
    /**
     * How many items a dropped-item entity currently represents.
     *
     * Present exactly for [ObservationKind.DROPPED_ITEM]. Entities and blocks have no
     * count to report.
     */
    val count: Long? = null,
    /**
     * Whether a body can stand at this block, as the server's ground says.
     *
     * Present exactly for [ObservationKind.BLOCK]: a block whose face is in sight can still be
     * covered, and a walk to a covered block has nowhere to end.
     */
    val standable: Boolean? = null,
    /**
     * What digging this block with the held item drops, as the server's loot rules answer it.
     *
     * Present exactly for [ObservationKind.BLOCK]; empty when it drops nothing.
     */
    val yields: List<String>? = null,
    /**
     * Whether digging this block leaves the body a way out.
     *
     * Present exactly for [ObservationKind.BLOCK]; false only for the support block of a body
     * with no standable exit.
     */
    val digSafe: Boolean? = null,
) {
    init {
        requireBoundedRef(ref)
        require((count != null) == (kind == ObservationKind.DROPPED_ITEM)) {
            "count is present exactly for a dropped-item observation"
        }
        val block = kind == ObservationKind.BLOCK
        require((standable != null) == block && (yields != null) == block && (digSafe != null) == block) {
            "standable, yields and digSafe are present exactly for a block observation"
        }
        yields?.let {
            require(it.size <= MAX_BLOCK_YIELDS) { "at most $MAX_BLOCK_YIELDS yields" }
            it.forEach(::requireItemName)
        }
        count?.let { require(it >= 1) { "a dropped-item observation must hold at least one item" } }
    }
}

/**
 * The event that triggered cognition. There is intentionally no per-tick
 * variant: cognition is event-driven, never per-tick.
 */
@Serializable
@JsonClassDiscriminator("event")
sealed interface Trigger {
    /** Another actor spoke to or near the agent. */
    @Serializable
    @SerialName("spoken")
    data class Spoken(val from: String, val text: String) : Trigger

    /** The agent's body took damage. */
    @Serializable
    @SerialName("damaged")
    data class Damaged(val amount: Long, val source: String) : Trigger

    /**
     * A living thing that hunts this agent's kind came into the body's view.
     *
     * Everything the body already verified about it travels with the trigger, so the
     * mind never has to ask a second question to decide whether to run: which thing it
     * is, the handle to name it back by, how far and in which direction it lies, whether
     * the body can actually see it, and the engine's own verdict on whether it could be
     * walked to.
     */
    @Serializable
    @SerialName("threatSighted")
    data class ThreatSighted(
        /**
         * What the thing is, as the body describes it.
         *
         * A description for the mind to reason with, never the handle: naming a threat by
         * what it is would leave the body guessing which of two identical creatures was
         * meant.
         */
        val threat: String,
        /**
         * The handle by which a decision may name this threat back to the body.
         *
         * Narrowed at decode by [requireBoundedRef], exactly as an [Observation.ref] is:
         * an over-long handle is one no request ever issued.
         */
        val ref: String,
        /** Distance from the agent to the threat, in blocks, measured as an observation's is. */
        val distance: Double,
        /** The horizontal bearing from the agent to the threat. */
        val direction: Bearing,
        /**
         * Whether the body has an unobstructed line of sight to the threat.
         *
         * `false` is not safety: it means the body knows the threat is there and
         * something stands between them.
         */
        val visible: Boolean,
        /**
         * Whether the engine's own bounded ground search found a route reaching the threat.
         *
         * Evidence for the mind, and the result of a search the body already paid for -
         * not an invitation to ask for another. Deliberately not part of what makes a
         * sighting expensive: see `dev.loom.mind.importanceOf`.
         */
        val reachable: Boolean,
    ) : Trigger {
        init {
            requireBoundedRef(ref)
        }
    }

    /** A notable block came into the agent's view. */
    @Serializable
    @SerialName("blockSighted")
    data class BlockSighted(val block: String) : Trigger

    /** A scheduled prompt for the agent to reconsider its goals. */
    @Serializable
    @SerialName("goalReview")
    data object GoalReview : Trigger
}

/**
 * The bounded snapshot the body exposes to the mind. Carries no world handle:
 * this is the entire surface the mind sees.
 */
@Serializable
data class Perception(
    /** Correlates this perception with the decision the mind returns. */
    val requestId: String,
    /** Stable identifier of the agent whose body produced this perception. */
    val agentId: String,
    /** The event that triggered cognition. */
    val trigger: Trigger,
    /** Importance tier, used to route the decision to a model. */
    val importance: DecisionImportance,
    /** The server tick on which the body captured this snapshot. */
    val capturedTick: Long,
    /** The bounded self-state the body chose to reveal. */
    val selfState: SelfState,
    /** The summarized observations the body chose to reveal. */
    val surroundings: List<Observation>,
    /**
     * How many otherwise eligible observations the body's own count bound dropped
     * before this perception was serialized: things in range and alive that would
     * have been described, and are not in [surroundings].
     *
     * Required rather than optional, and never negative. A mind handed a trimmed
     * scene with no way to know it had been trimmed would reason about "everything
     * I can see" over a list that was not everything, so absence must not be able
     * to read as completeness. Zero says the list is the whole of what the body
     * had; out-of-range and ineligible things are not omissions, because they were
     * never observations.
     */
    val surroundingsOmitted: Long,
    /** The agent's held short-term goal, carried into a goal review only. */
    val goal: GoalSummary? = null,
) {
    init {
        require(surroundings.size <= MAX_PERCEPTION_OBSERVATIONS) {
            "surroundings must state at most $MAX_PERCEPTION_OBSERVATIONS observations, " +
                "got ${surroundings.size}"
        }
        require(surroundingsOmitted >= 0) {
            "surroundingsOmitted must not be negative, got $surroundingsOmitted"
        }
    }
}

/** Longest a goal statement may be, in code points, mirroring the schema. */
const val MAX_GOAL_STATEMENT_CHARS: Int = 160

/** Longest a goal id or step request id/outcome may be, mirroring the schema. */
const val MAX_GOAL_ID_CHARS: Int = 64

/** Longest a step's action name may be, mirroring the schema. */
private const val MAX_GOAL_ACTION_CHARS = 32

private fun requireBoundedText(
    field: String,
    text: String,
    max: Int,
) {
    val characters = text.codePointCount(0, text.length)
    require(characters in 1..max) { "$field must be 1..$max characters, got $characters" }
}

/** The last step taken under a held goal and the body's outcome tag for it. */
@Serializable
data class GoalStep(
    /** The request id of the decision that took the step. */
    val requestId: String,
    /** The [Intent] action that decision chose. */
    val action: String,
    /** The body's countable outcome tag for that intent. */
    val outcome: String,
) {
    init {
        requireBoundedText("goal step requestId", requestId, MAX_GOAL_ID_CHARS)
        requireBoundedText("goal step action", action, MAX_GOAL_ACTION_CHARS)
        requireBoundedText("goal step outcome", outcome, MAX_GOAL_ID_CHARS)
    }
}

/** The one short-term goal the agent's previous goal review left held. */
@Serializable
data class GoalSummary(
    /** The request id of the decision that adopted the goal. */
    val goalId: String,
    /** The goal in the agent's own words. */
    val statement: String,
    /** How many times the goal has been revised since it was adopted. */
    val revision: Long,
    /** The server tick on which the goal was adopted. */
    val adoptedTick: Long,
    /** The last step taken under the goal, once the body has answered it. */
    val lastStep: GoalStep? = null,
    /** The observable state that would mean the goal is achieved. */
    val successCondition: String? = null,
    /** The item the goal must hold, which types the success condition. */
    val successItem: String? = null,
    /** How many of the success item the goal must hold. */
    val successCount: Long? = null,
    /** The step the goal's last affirming review chose to take. */
    val step: String? = null,
    /** The action that step's intent took. */
    val stepAction: IntentAction? = null,
    /** The target ref that step's intent named, absent for an action that names none. */
    val stepRef: String? = null,
    /** Body-measured evidence about the goal, once a later review can compare. */
    val progress: GoalProgress? = null,
) {
    init {
        requireBoundedText("goalId", goalId, MAX_GOAL_ID_CHARS)
        requireBoundedText("goal statement", statement, MAX_GOAL_STATEMENT_CHARS)
        successCondition?.let { requireBoundedText("goal success condition", it, MAX_GOAL_STATEMENT_CHARS) }
        requireTypedSuccess(successItem, successCount)
        step?.let { requireBoundedText("goal step", it, MAX_GOAL_STATEMENT_CHARS) }
        stepRef?.let(::requireBoundedRef)
        require(revision >= 0) { "goal revision must not be negative, got $revision" }
        require(adoptedTick >= 0) { "goal adoptedTick must not be negative, got $adoptedTick" }
    }
}

/**
 * Affirmed steps in a row that may change neither the agent's inventory nor its place before
 * a goal can no longer be continued or revised; mirrors the Rust contract's constant.
 */
const val MAX_GOAL_STEPS_WITHOUT_PROGRESS: Long = 3

/** Longest item name a success condition or inventory change may carry, in characters. */
const val MAX_ITEM_NAME_CHARS: Int = 64

/** Most of one item a success condition may ask the goal to hold. */
const val MAX_SUCCESS_COUNT: Long = 64

/** Most inventory changes one progress reading carries: one per player inventory slot. */
const val MAX_INVENTORY_CHANGES: Int = 46

/** Body-measured evidence about the goal since its last affirming review. */
@Serializable
data class GoalProgress(
    /** Whether the inventory now holds the success count of the success item. */
    val conditionMet: Boolean,
    /** How many of the success item the inventory holds now. */
    val conditionHeld: Long,
    /** Every item whose held count changed since the goal's last affirming review. */
    val inventoryDelta: List<InventoryChange>,
    /** Whether the held count of the success item rose since that review. */
    val conditionAdvanced: Boolean,
    /** Whether the typed step was carried out with evidence, which [stepEvidence] names. */
    val stepAdvanced: Boolean,
    /** What showed the step was carried out, or that nothing did. */
    val stepEvidence: StepEvidence,
    /** How many affirmed steps in a row advanced neither. */
    val stepsWithoutProgress: Long,
) {
    init {
        require(conditionHeld >= 0) { "conditionHeld must not be negative, got $conditionHeld" }
        require(inventoryDelta.size <= MAX_INVENTORY_CHANGES) { "at most $MAX_INVENTORY_CHANGES inventory changes" }
        require(stepsWithoutProgress >= 0) { "stepsWithoutProgress must not be negative, got $stepsWithoutProgress" }
    }
}

/** Most items one block observation says digging it drops. */
const val MAX_BLOCK_YIELDS: Int = 8

/** What showed the goal's last step was carried out. */
@Serializable
enum class StepEvidence {
    /** A walk reached its visible target, somewhere new to the goal. */
    @SerialName("arrived")
    ARRIVED,

    /** The inventory held more of an item after the step. */
    @SerialName("gained")
    GAINED,

    /** A new dropped item lay in sight after the step. */
    @SerialName("dropped")
    DROPPED,

    /** Nothing showed it: a dig that dropped nothing, a walk that did not arrive. */
    @SerialName("none")
    NONE,
}

/**
 * Whether [name] is an item as an inventory stack names it, `item.<namespace>.<path>`; mirrors
 * the Rust contract's `is_item_name`. A block's name is not one.
 */
fun isItemName(name: String): Boolean = name.length <= MAX_ITEM_NAME_CHARS && ITEM_NAME.matches(name)

private val ITEM_NAME = Regex("item\\.[a-z0-9_]+\\.[a-z0-9_.]+")

private fun requireItemName(name: String) {
    require(isItemName(name)) { "an item name is item.<namespace>.<path>" }
}

/** How the held count of one item changed since the goal's last affirming review. */
@Serializable
data class InventoryChange(
    /** The item, exactly as an inventory stack names it. */
    val what: String,
    /** Positive gained, negative lost; never zero. */
    val change: Long,
) {
    init {
        requireItemName(what)
        require(change != 0L) { "an inventory change is never zero" }
    }
}

/** The action an [Intent] takes, as its `action` tag. */
@Serializable
enum class IntentAction {
    @SerialName("say")
    SAY,

    @SerialName("moveTo")
    MOVE_TO,

    @SerialName("flee")
    FLEE,

    @SerialName("eat")
    EAT,

    @SerialName("dig")
    DIG,

    @SerialName("collect")
    COLLECT,

    @SerialName("place")
    PLACE,

    @SerialName("equip")
    EQUIP,

    @SerialName("craft")
    CRAFT,

    @SerialName("attack")
    ATTACK,

    @SerialName("sleep")
    SLEEP,

    @SerialName("rest")
    REST,

    @SerialName("idle")
    IDLE,
}

/** The action this intent takes, as its `action` tag. */
val Intent.action: IntentAction
    get() =
        when (this) {
            is Intent.Say -> IntentAction.SAY
            is Intent.MoveTo -> IntentAction.MOVE_TO
            is Intent.Flee -> IntentAction.FLEE
            is Intent.Eat -> IntentAction.EAT
            is Intent.Dig -> IntentAction.DIG
            is Intent.Collect -> IntentAction.COLLECT
            is Intent.Place -> IntentAction.PLACE
            is Intent.Equip -> IntentAction.EQUIP
            is Intent.Craft -> IntentAction.CRAFT
            is Intent.Attack -> IntentAction.ATTACK
            is Intent.Sleep -> IntentAction.SLEEP
            Intent.Rest -> IntentAction.REST
            Intent.Idle -> IntentAction.IDLE
        }

/**
 * The one target ref a goal step names for this intent, or `null` for an action that names
 * nothing; mirrors the Rust contract's `Intent::target_ref`. A placement's target is its support
 * block and an attack's is its creature: the stack each spends is the means, not the target.
 */
val Intent.targetRef: String?
    get() =
        when (this) {
            is Intent.MoveTo -> onto
            is Intent.Flee -> from
            is Intent.Eat -> ref
            is Intent.Dig -> block
            is Intent.Collect -> item
            is Intent.Place -> block
            is Intent.Equip -> item
            is Intent.Craft -> recipe
            is Intent.Attack -> target
            is Intent.Sleep -> bed
            is Intent.Say, Intent.Rest, Intent.Idle -> null
        }

/** A typed success condition's two halves, each bounded where it is present. */
private fun requireTypedSuccess(
    item: String?,
    count: Long?,
) {
    item?.let(::requireItemName)
    count?.let { require(it in 1..MAX_SUCCESS_COUNT) { "goal success count must be 1..$MAX_SUCCESS_COUNT, got $it" } }
}

/** The mind's verdict on the step its previous goal review took. */
@Serializable
enum class GoalStepResult {
    @SerialName("done")
    DONE,

    @SerialName("progressing")
    PROGRESSING,

    @SerialName("noProgress")
    NO_PROGRESS,
}

/** What a decision does with the agent's short-term goal. */
@Serializable
enum class GoalDisposition {
    @SerialName("adopt")
    ADOPT,

    @SerialName("continue")
    CONTINUE,

    @SerialName("revise")
    REVISE,

    @SerialName("complete")
    COMPLETE,

    @SerialName("abandon")
    ABANDON,
}

/** A decision's explicit choice about the agent's short-term goal. */
@Serializable
data class GoalChoice(
    /** What the decision does with the goal. */
    val disposition: GoalDisposition,
    /** The goal's wording, for adopt and revise. */
    val statement: String? = null,
    /** The observable state that would mean the goal is achieved, for adopt and revise. */
    val successCondition: String? = null,
    /** The item the goal must hold, typing the success condition, for adopt and revise. */
    val successItem: String? = null,
    /** How many of the success item the goal must hold, for adopt and revise. */
    val successCount: Long? = null,
    /** The concrete next step this decision's intent takes, for adopt, continue and revise. */
    val step: String? = null,
    /** The action the step takes, which must be the intent's own. */
    val stepAction: IntentAction? = null,
    /** The target ref the step names, which must be the intent's own; absent for none. */
    val stepRef: String? = null,
    /** The verdict on the carried goal's previous step, for every disposition but adopt. */
    val stepResult: GoalStepResult? = null,
) {
    init {
        statement?.let { requireBoundedText("goal statement", it, MAX_GOAL_STATEMENT_CHARS) }
        successCondition?.let { requireBoundedText("goal success condition", it, MAX_GOAL_STATEMENT_CHARS) }
        requireTypedSuccess(successItem, successCount)
        step?.let { requireBoundedText("goal step", it, MAX_GOAL_STATEMENT_CHARS) }
        stepRef?.let(::requireBoundedRef)
    }
}

/**
 * A named intent the mind returns. The body validates and applies it; the mind
 * never mutates world state directly.
 */
@Serializable
@JsonClassDiscriminator("action")
sealed interface Intent {
    /** Speak a line of text. */
    @Serializable
    @SerialName("say")
    data class Say(val text: String) : Intent

    /**
     * Walk to one nearby block, named by the [Observation.ref] it carried in the perception
     * being answered.
     *
     * A ref rather than coordinates on purpose: a perception states where things are as a
     * bearing, a distance and a vertical offset, never as coordinates, so a coordinate the
     * mind wrote was a guess. The body resolves [onto] against that one request only and
     * walks to where a body stands on that exact block: the cell above a solid one, or the
     * block's own cell where the server gives it no collision.
     */
    @Serializable
    @SerialName("moveTo")
    data class MoveTo(val onto: String) : Intent {
        init {
            requireBoundedRef(onto)
        }
    }

    /**
     * Move away from one living thing, named by the [Observation.ref] it carried in
     * the perception being answered.
     *
     * A ref rather than a name or a type on purpose: two identical creatures are
     * different threats, and the body must retreat from the one the mind meant. The
     * body resolves [from] against that one request only, and refuses a ref it did not
     * issue for that request rather than guessing.
     *
     * [from]'s length is narrowed at decode by [requireBoundedRef], so a blank or
     * over-long handle is refused where it arrives rather than travelling to the body
     * to be refused there.
     */
    @Serializable
    @SerialName("flee")
    data class Flee(val from: String) : Intent {
        init {
            requireBoundedRef(from)
        }
    }

    /**
     * Eat one item from one stack of the body's own inventory, named by the
     * [InventoryStack.ref] it carried in the perception being answered.
     *
     * A ref rather than an item name on purpose: two slots can hold the same item, and
     * the body must consume the one the mind meant. The body resolves [ref] against
     * that one request only, re-reads the slot it names, and refuses what it finds
     * there if it is no longer food - so a stack that changed in between is refused
     * rather than substituted. On acceptance Mineflayer asks vanilla to consume exactly
     * one item; the next perception reports the resulting [SelfState.food] and
     * [SelfState.saturation] separately.
     */
    @Serializable
    @SerialName("eat")
    data class Eat(val ref: String) : Intent {
        init {
            requireBoundedRef(ref)
        }
    }

    /** Dig one nearby block named by a request-scoped observation ref. */
    @Serializable
    @SerialName("dig")
    data class Dig(val block: String) : Intent {
        init {
            requireBoundedRef(block)
        }
    }

    /**
     * Collect one nearby dropped item.
     *
     * [approach] is the mind's explicit permission for one bounded path to the
     * item's admitted position. False permits no movement.
     */
    @Serializable
    @SerialName("collect")
    data class Collect(
        val item: String,
        val approach: Boolean,
    ) : Intent {
        init {
            requireBoundedRef(item)
        }
    }

    /**
     * Place one offered stack that is already held against one face of one sensed block.
     */
    @Serializable
    @SerialName("place")
    data class Place(
        val block: String,
        val item: String,
        val face: BlockFace,
    ) : Intent {
        init {
            requireBoundedRef(block)
            requireBoundedRef(item)
        }
    }

    /** Equip one offered inventory stack into one chosen player equipment destination. */
    @Serializable
    @SerialName("equip")
    data class Equip(
        val item: String,
        val destination: EquipmentDestination,
    ) : Intent {
        init {
            requireBoundedRef(item)
        }
    }

    /**
     * Apply one exact offered recipe a bounded number of times.
     *
     * [recipe] is not a registry id: it is the request-scoped handle on one recipe,
     * ingredient snapshot, and any required reachable crafting table from
     * [SelfState.craftingOptions].
     */
    @Serializable
    @SerialName("craft")
    data class Craft(
        val recipe: String,
        val crafts: Long,
    ) : Intent {
        init {
            requireBoundedRef(recipe)
            require(crafts in 1..MAX_CRAFTS_PER_INTENT) {
                "crafts must be in 1..$MAX_CRAFTS_PER_INTENT, got $crafts"
            }
        }
    }

    /**
     * Strike one exact offered living target with one exact offered inventory stack.
     *
     * [approach] permits one bounded path to the target's admitted position. It never
     * permits target switching or a pursuit loop.
     */
    @Serializable
    @SerialName("attack")
    data class Attack(
        val target: String,
        val weapon: String,
        val attacks: Long,
        val approach: Boolean,
    ) : Intent {
        init {
            requireBoundedRef(target)
            requireBoundedRef(weapon)
            require(attacks in 1..MAX_ATTACKS_PER_INTENT) {
                "attacks must be in 1..$MAX_ATTACKS_PER_INTENT, got $attacks"
            }
        }
    }

    /** Sleep in one exact bed offered by the perception being answered. */
    @Serializable
    @SerialName("sleep")
    data class Sleep(val bed: String) : Intent {
        init {
            requireBoundedRef(bed)
        }
    }

    /**
     * Stop, and hold this position on purpose.
     *
     * It restores nothing. Hunger continues under vanilla's own food and saturation
     * rules while a resting agent waits.
     *
     * Its own intent rather than a synonym for [Idle] because deliberately waiting
     * somewhere - sealed in for the night, say - is a choice a run must be able to
     * count separately from having decided nothing.
     */
    @Serializable
    @SerialName("rest")
    data object Rest : Intent

    /** Do nothing this cycle. */
    @Serializable
    @SerialName("idle")
    data object Idle : Intent
}

/**
 * Reproducibility stamp attached to every decision and written to the
 * append-only decision log, so each decision's
 * perception -> reasoning -> action -> result chain can be replayed and audited.
 */
@Serializable
data class Provenance(
    /** The importance tier the decision was routed at. */
    val tier: DecisionImportance,
    /** Identifier of the model that produced the decision. */
    val model: String,
    /** Version of the prompt template used, for reproducibility. */
    val promptVersion: String,
)

/** The structured decision the mind returns for a [Perception]. */
@Serializable
data class Decision(
    /** Echoes the [Perception.requestId] this decision answers. */
    val requestId: String,
    /** Echoes the [Perception.agentId] this decision is for. */
    val agentId: String,
    /** The named intent the body will validate and apply. */
    val intent: Intent,
    /** The mind's natural-language justification, logged for research. */
    val reasoning: String,
    /** Reproducibility stamp for the decision log. */
    val provenance: Provenance,
    /** The decision's explicit goal choice; absent leaves any held goal unchanged. */
    val goal: GoalChoice? = null,
)
