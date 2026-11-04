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

/** The most blocks one [Intent.DigSequence] names. */
const val MAX_DIG_SEQUENCE_BLOCKS: Int = 5

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

/** One of the nine faces around the two cells a standing body fills. */
@Serializable
enum class CoverFace {
    @SerialName("feetNorth")
    FEET_NORTH,

    @SerialName("feetEast")
    FEET_EAST,

    @SerialName("feetSouth")
    FEET_SOUTH,

    @SerialName("feetWest")
    FEET_WEST,

    @SerialName("headNorth")
    HEAD_NORTH,

    @SerialName("headEast")
    HEAD_EAST,

    @SerialName("headSouth")
    HEAD_SOUTH,

    @SerialName("headWest")
    HEAD_WEST,

    @SerialName("roof")
    ROOF,
}

/** Most faces around a standing body: four beside the feet, four beside the head, and the roof. */
const val MAX_COVER_FACES: Int = 9

/** Most solid-block items a cover reading names as obtainable now. */
const val MAX_COVER_BLOCK_ITEMS: Int = 8

/** The most refs a cover may name to gather a closer from. */
const val MAX_COVER_GATHERS: Int = 8

/** One placement that would close an open face of the agent's cover. */
@Serializable
data class Closing(
    /** The open face the placement closes. */
    val face: CoverFace,
    /** The sensed support block, in sight and in reach, to place against. */
    val block: String,
    /** The side of that support block to place against. */
    val side: BlockFace,
) {
    init {
        requireBoundedRef(block)
    }
}

/** How enclosed the agent stands, read from the server's own ground. */
@Serializable
data class Cover(
    /** Whether the open space the body stands in is closed on every side. */
    val sealed: Boolean,
    /** Whether a short walk leads out of where the body stands. */
    val walkOut: Boolean,
    /** The faces around the body the server gives no collision, each at most once. */
    val open: List<CoverFace>,
    /** For open faces a held block could close from here, one placement each. */
    val closings: List<Closing>,
    /** The block under the feet whose dig deepens the cover, when there is one. */
    val descend: String? = null,
    /** Items sight can give now that place as a solid block. */
    val blockItems: List<String>,
    /** For a shelter nothing held can close, the blocks and drops whose gathering is its step. */
    val gathers: List<String> = emptyList(),
) {
    init {
        require(gathers.size <= MAX_COVER_GATHERS) { "a cover gathers from at most $MAX_COVER_GATHERS refs" }
        gathers.forEach(::requireBoundedRef)
        require(open.size <= MAX_COVER_FACES && open.distinct().size == open.size) {
            "a cover names each of its nine faces at most once"
        }
        val closed = closings.map { it.face }
        require(closed.size <= MAX_COVER_FACES && closed.distinct().size == closed.size && open.containsAll(closed)) {
            "a cover closes each open face at most once, and only open ones"
        }
        descend?.let(::requireBoundedRef)
        require(blockItems.size <= MAX_COVER_BLOCK_ITEMS) { "at most $MAX_COVER_BLOCK_ITEMS block items" }
        require(blockItems.all { isItemName(it) && it != AIR_ITEM }) { "a block item is an item name, never air" }
    }
}

/** A shelter goal's body-measured cover. */
@Serializable
data class CoverProgress(
    /** How many of the nine faces around the body are closed. */
    val closed: Long,
    /** How many more a held block could close from where the body stands. */
    val closable: Long,
    /** Whether the space the body stands in is sealed. */
    val sealed: Boolean,
) {
    init {
        require(closed in 0..MAX_COVER_FACES && closable in 0..MAX_COVER_FACES) {
            "cover counts are in 0..$MAX_COVER_FACES"
        }
    }
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
    /** Whether the server says the stack can be eaten; absent reads as no. */
    val edible: Boolean? = null,
    /** Whether the server says the stack places as a solid block; absent reads as no. */
    val solid: Boolean? = null,
    /** Whether the server says the stack is in the main hand; absent reads as no. */
    val held: Boolean? = null,
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
    /** What the server saw this agent's own recent digs drop, newest first, wherever it went. */
    val ownDrops: List<OwnDrop>,
    /** The bounded world clock the body reveals. */
    val world: WorldState,
    /** How enclosed the agent stands, from the server's ground; absent where there is none. */
    val cover: Cover? = null,
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
        require(ownDrops.size <= MAX_OWN_DROPS) { "at most $MAX_OWN_DROPS own drops" }
    }
}

/** Most of an agent's own recent dig drops one perception tells it about. */
const val MAX_OWN_DROPS: Int = 8

/** The most items one stack, or one block's drop of one item, can hold. */
const val MAX_STACK_COUNT: Long = 64

/** The item that is no item: what a loot roll that dropped nothing is reported as. */
const val AIR_ITEM: String = "item.minecraft.air"

/** What became of a stack an agent's own dig dropped. */
@Serializable
enum class DropFate {
    /** Still lying where the server can see it. */
    @SerialName("lying")
    LYING,

    /** Picked up by this agent. */
    @SerialName("collected")
    COLLECTED,

    /** Picked up by another player. */
    @SerialName("taken")
    TAKEN,

    /** Merged, despawned or destroyed. */
    @SerialName("gone")
    GONE,
}

/**
 * One stack the server dropped for one of this agent's digs, attributed to the perception
 * request whose dig dropped it. A lying drop is placed from the agent now, the way an
 * observation is; any other fate has no place.
 */
@Serializable
data class OwnDrop(
    /** The perception request id of the decision whose dig dropped this stack. */
    val requestId: String,
    /** The item, exactly as an inventory stack names it. */
    val what: String,
    /** How many items the server dropped in the stack. */
    val count: Long,
    /** What became of it. */
    val fate: DropFate,
    /** How far a lying drop is from the agent now. */
    val distance: Double? = null,
    /** The bearing to a lying drop. */
    val direction: Bearing? = null,
    /** How far above the agent a lying drop is. */
    val verticalOffset: Double? = null,
    /**
     * The dropped-item observation in this perception that is this drop, so a collect may name
     * it; present only for a lying drop the body offered, in sight or not.
     */
    val ref: String? = null,
) {
    init {
        requireBoundedText("own drop requestId", requestId, MAX_GOAL_ID_CHARS)
        requireItemName(what)
        require(count in 1..MAX_STACK_COUNT) { "a dropped stack holds 1 to $MAX_STACK_COUNT items" }
        val lying = fate == DropFate.LYING
        require((distance != null) == lying && (direction != null) == lying && (verticalOffset != null) == lying) {
            "distance, direction and verticalOffset are present exactly for a lying drop"
        }
        distance?.let { require(it >= 0.0) { "a drop's distance is never negative" } }
        ref?.let(::requireBoundedRef)
        require(ref == null || lying) { "only a lying drop carries a ref" }
    }
}

/**
 * One item digging a block with what the body holds can drop, with the range the server's
 * random loot rules give: [least] the fewest one dig has dropped, zero when a dig may drop
 * none, and [most] the most.
 */
@Serializable
data class BlockYield(
    /** The item, exactly as an inventory stack names it; never air. */
    val what: String,
    /** The fewest of it one dig has dropped. */
    val least: Long,
    /** The most of it one dig has dropped. */
    val most: Long,
    /**
     * How many of it a hundred digs drop on average in the server's sampled rolls: the expected
     * yield, between 100 times [least] and 100 times [most] (r19: a leaf counted as about one
     * stick dropped one in 33).
     */
    val perHundred: Long,
) {
    init {
        requireItemName(what)
        require(what != AIR_ITEM) { "a block never yields air" }
        require(most in 1..MAX_STACK_COUNT && least in 0..most) {
            "a yield's least is 0 to its most, and its most 1 to $MAX_STACK_COUNT"
        }
        require(perHundred in least * PER_HUNDRED..most * PER_HUNDRED) {
            "a yield's perHundred lies between 100 times its least and 100 times its most"
        }
    }

    private companion object {
        const val PER_HUNDRED = 100L
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
     * What digging this block with the held item can drop, each with the range the server's
     * random loot rules give.
     *
     * Present exactly for [ObservationKind.BLOCK]; empty when it drops nothing.
     */
    val yields: List<BlockYield>? = null,
    /**
     * Whether digging this block is safe: it leaves the body a way out, its drop where the body can
     * collect it, the body dry, and, through the shelter window, a sealed space it stands in sealed.
     *
     * Present exactly for [ObservationKind.BLOCK].
     */
    val digSafe: Boolean? = null,
    /**
     * Present, and true, only for a dropped item this agent's body has already found no way to
     * from where it stands - no route at all, or a route its approach stalled on from this same
     * cell over the same ground (r34 delta): it is not offered to collect and gives nothing a goal
     * may count on.
     */
    val routeRefused: Boolean? = null,
    /**
     * Present, and true, only for a creature that hunts the agent's kind, by the body's own
     * list of player hunters: what a goal review may flee from.
     */
    val hunts: Boolean? = null,
    /**
     * For a block a body can stand at, how many of the nine faces around the cell it would stand in
     * have collision: natural cover in sight. Present only for a standable block.
     */
    val cover: Long? = null,
) {
    init {
        requireBoundedRef(ref)
        require(cover == null || (standable == true && cover in 0..MAX_COVER_FACES)) {
            "cover is present only for a block a body can stand at, and counts 0..$MAX_COVER_FACES faces"
        }
        require(hunts != false && (hunts == null || kind == ObservationKind.ENTITY)) {
            "hunts is present, and true, only for a creature"
        }
        require(routeRefused != false && (routeRefused == null || kind == ObservationKind.DROPPED_ITEM)) {
            "routeRefused is present, and true, only for a dropped item"
        }
        require((count != null) == (kind == ObservationKind.DROPPED_ITEM)) {
            "count is present exactly for a dropped-item observation"
        }
        val block = kind == ObservationKind.BLOCK
        require((standable != null) == block && (yields != null) == block && (digSafe != null) == block) {
            "standable, yields and digSafe are present exactly for a block observation"
        }
        yields?.let { require(it.size <= MAX_BLOCK_YIELDS) { "at most $MAX_BLOCK_YIELDS yields" } }
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
    data class Damaged(
        val amount: Long,
        val source: String,
        /**
         * The creature that dealt the damage, a projectile's shooter first, by the ref this
         * perception's surroundings name it: always shown and always offered to flee and to
         * attack within reach, however many nearer creatures there are. Absent when no creature
         * dealt it or the body could not report the attacker.
         */
        val attackerRef: String? = null,
        /** The server's own health of the body when the hit landed, before it was applied. */
        val healthBefore: Double? = null,
        /**
         * The server's own health of the body once the hit was applied, which a damaged
         * perception's `selfState.health` is: the body's reading can still be the one before the
         * hit (r34: alpha took 14 from a creeper and was shown health 20 with 6 left).
         */
        val healthAfter: Double? = null,
    ) : Trigger {
        init {
            attackerRef?.let(::requireBoundedRef)
            listOfNotNull(healthBefore, healthAfter).forEach { health ->
                require(health.isFinite() && health >= 0.0) { "a health reading is finite and at or above zero" }
            }
        }
    }

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
         * sighting expensive: see `dev.loom.mind.importanceOf`. `null` when the body made
         * no such search, which is the player body's case: a constant `false` there read as
         * no route to flee (r20).
         */
        val reachable: Boolean? = null,
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

    /** The held goal's last step ended, so the agent reviews the goal again at once. */
    @Serializable
    @SerialName("stepSettled")
    data object StepSettled : Trigger
}

/** Whether this trigger is a review of the agent's goal, which alone carries the held goal. */
val Trigger.reviewsGoal: Boolean get() = this == Trigger.GoalReview || this == Trigger.StepSettled

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
    /**
     * Why the body refused the agent's previous goal decision before acting on it, told once,
     * at the next goal review or step-settled perception.
     */
    val lastRefusal: GoalRefusal? = null,
    /**
     * Cells beyond what the body can see where it can stand, each a `moveTo` target by its ref,
     * so an agent that sees nothing worth doing can walk to look further.
     */
    val explore: List<ExploreTarget> = emptyList(),
    /**
     * Sources of items the body saw recently and does not see now, nearest first: where the
     * nearest one of each item was. A goal for a recalled item is walked toward by the cells at
     * the edge of sight in its direction.
     */
    val recalled: List<RecalledSource> = emptyList(),
    /**
     * Goal items the agent may set a total for beyond what sight alone guarantees, each grounded
     * in a source seen now or remembered, or in a known recipe whose input is so grounded, with
     * the most a goal of it may ask.
     */
    val horizon: List<HorizonGoal> = emptyList(),
) {
    init {
        require(recalled.size <= MAX_RECALLED_SOURCES) { "at most $MAX_RECALLED_SOURCES recalled sources" }
        require(horizon.size <= MAX_HORIZON_GOALS) { "at most $MAX_HORIZON_GOALS horizon goals" }
        require(surroundings.size <= MAX_PERCEPTION_OBSERVATIONS) {
            "surroundings must state at most $MAX_PERCEPTION_OBSERVATIONS observations, " +
                "got ${surroundings.size}"
        }
        require(explore.size <= MAX_EXPLORE_TARGETS) { "at most $MAX_EXPLORE_TARGETS exploration targets" }
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

/** Most exploration targets one perception carries: one per compass bearing. */
const val MAX_EXPLORE_TARGETS: Int = 8

/** Most recalled sources one perception carries. */
const val MAX_RECALLED_SOURCES: Int = 8

/** The most a hundred digs of one block can drop of one item: 64 a dig. */
const val MAX_PER_HUNDRED: Long = 6400

/** Most horizon goal items one perception carries. */
const val MAX_HORIZON_GOALS: Int = 8

/** What grounds a horizon goal. */
@Serializable
enum class HorizonGrounding {
    /** A source in sight now. */
    @SerialName("sight")
    SIGHT,

    /** A source remembered out of sight. */
    @SerialName("recalled")
    RECALLED,

    /** A known recipe whose input is grounded by sight or memory. */
    @SerialName("recipe")
    RECIPE,
}

/** One goal item the agent may set a total for beyond what sight guarantees. */
@Serializable
data class HorizonGoal(
    /** The item, exactly as an inventory stack names it. */
    val what: String,
    /** What grounds the goal. */
    val grounding: HorizonGrounding,
    /** For a recipe-grounded goal, the grounded input its recipe is made from. */
    val via: String? = null,
    /** The largest total a goal of this item may ask, counting what is held. */
    val most: Long,
    /** Whether that total reaches past what the grounding gives, onto ground beyond the edge of sight. */
    val frontier: Boolean,
) {
    init {
        require(most in 1..MAX_SUCCESS_COUNT) { "a horizon goal asks 1 to $MAX_SUCCESS_COUNT" }
        require(what != AIR_ITEM && via != AIR_ITEM) { "a horizon goal is never air" }
        require((grounding == HorizonGrounding.RECIPE) == (via != null)) {
            "a horizon goal names its input exactly when a recipe grounds it"
        }
    }
}

/** Where the body last saw a source of an item it does not see now. */
@Serializable
data class RecalledSource(
    /** The item, exactly as an inventory stack names it. */
    val what: String,
    /** How far the source was from the agent, in blocks. */
    val distance: Double,
    /** The horizontal bearing to it. */
    val direction: Bearing,
    /** How far above the agent it was. */
    val verticalOffset: Double,
    /** How many of the item a hundred digs of it drop on average. */
    val perHundred: Long,
) {
    init {
        require(distance >= 0.0) { "a recalled source's distance is never negative" }
        require(perHundred in 1..MAX_PER_HUNDRED) { "a recalled source yields 1 to $MAX_PER_HUNDRED hundredths" }
        require(what != AIR_ITEM) { "a recalled source is never air" }
    }
}

/** One standable cell at the edge of sight, placed from the agent the way an observation is. */
@Serializable
data class ExploreTarget(
    /** The handle a `moveTo` names this cell by. */
    val ref: String,
    /** How far the cell is from the agent, in blocks. */
    val distance: Double,
    /** The horizontal bearing to the cell. */
    val direction: Bearing,
    /** How far above the agent the cell is. */
    val verticalOffset: Double,
) {
    init {
        requireBoundedRef(ref)
        require(distance >= 0.0) { "an exploration target's distance is never negative" }
    }
}

/**
 * One goal decision the body refused before acting: which request, the countable outcome tag it
 * was refused with, and - for a success condition it judged - the item, the total asked for, how
 * many were held and how many more what was in sight could give.
 */
@Serializable
data class GoalRefusal(
    /** The request id of the refused decision. */
    val requestId: String,
    /** The outcome tag the body refused it with. */
    val outcome: String,
    /** The success item the refusal judged. */
    val successItem: String? = null,
    /** The total the refused decision asked to hold. */
    val asked: Long? = null,
    /** How many of the success item were held. */
    val held: Long? = null,
    /** How many more of it what was in sight could give. */
    val capacity: Long? = null,
) {
    init {
        requireBoundedText("goal refusal requestId", requestId, MAX_GOAL_ID_CHARS)
        requireBoundedText("goal refusal outcome", outcome, MAX_GOAL_ID_CHARS)
        successItem?.let(::requireItemName)
        asked?.let { require(it in 1..MAX_SUCCESS_COUNT) { "a refused total is 1..$MAX_SUCCESS_COUNT" } }
        held?.let { require(it >= 0) { "a held count is never negative" } }
        capacity?.let { require(it >= 0) { "a capacity is never negative" } }
    }
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
    /** The goal, worded by the body from its typed success condition. */
    val statement: String,
    /** How many times the goal has been revised since it was adopted. */
    val revision: Long,
    /** The server tick on which the goal was adopted. */
    val adoptedTick: Long,
    /** The last step taken under the goal, once the body has answered it. */
    val lastStep: GoalStep? = null,
    /** The item the goal must hold: its success condition, with [successCount]. */
    val successItem: String? = null,
    /** How many of the success item the goal must hold. */
    val successCount: Long? = null,
    /** The step the goal's last affirming review chose to take. */
    val step: String? = null,
    /** The action that step's intent took. */
    val stepAction: IntentAction? = null,
    /** The target ref that step's intent named, absent for an action that names none. */
    val stepRef: String? = null,
    /**
     * Whether the goal is a discovery goal: no success item, met when a walk to the edge of sight
     * reveals a useful resource no footing gives.
     */
    val discovery: Boolean = false,
    /**
     * Whether the goal is a shelter goal: no success item, met when the server's ground seals the
     * space the agent stands in.
     */
    val shelter: Boolean = false,
    /** For a resource subgoal, the goal id of the shelter goal it feeds. */
    val feeds: String? = null,
    /** Body-measured evidence about the goal, once a later review can compare. */
    val progress: GoalProgress? = null,
) {
    init {
        requireBoundedText("goalId", goalId, MAX_GOAL_ID_CHARS)
        requireBoundedText("goal statement", statement, MAX_GOAL_STATEMENT_CHARS)
        requireTypedSuccess(successItem, successCount)
        step?.let { requireBoundedText("goal step", it, MAX_GOAL_STATEMENT_CHARS) }
        stepRef?.let(::requireBoundedRef)
        feeds?.let { requireBoundedText("goal feeds", it, MAX_GOAL_ID_CHARS) }
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
    /** For a shelter goal, its cover as this review measured it. */
    val cover: CoverProgress? = null,
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

    /** The inventory held more of the goal's item after the step. */
    @SerialName("gained")
    GAINED,

    /** The inventory held more of a direct input of a recipe for the goal's item after the step. */
    @SerialName("gainedInput")
    GAINED_INPUT,

    /** The server saw the step's own dig drop something for this agent, wherever it went. */
    @SerialName("dropped")
    DROPPED,

    /** A walk to the edge of sight arrived where a source of the goal's item was then in sight. */
    @SerialName("revealed")
    REVEALED,

    /** A shelter goal's step left the agent more enclosed than the goal had yet been. */
    @SerialName("covered")
    COVERED,

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
            is Intent.Dig, is Intent.DigSequence -> IntentAction.DIG
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
            is Intent.DigSequence -> blocks.first()
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

/** Why a replace or an abandon ends the held goal. */
@Serializable
enum class GoalReason {
    /** The success condition is met; a replace only. */
    @SerialName("completed")
    COMPLETED,

    /** What is held and could be given no longer reaches it, or no shelter step exists. */
    @SerialName("impossible")
    IMPOSSIBLE,

    /** A shelter goal replaces another as night nears and the agent stands exposed. */
    @SerialName("unsafe")
    UNSAFE,

    /** It advanced nothing for the stall bound's steps in a row. */
    @SerialName("stale")
    STALE,

    /** A replace by a different objective, chosen deliberately. */
    @SerialName("superseded")
    SUPERSEDED,
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

    /** Abandon the held goal and adopt a new one in the same answer, under this decision's id. */
    @SerialName("replace")
    REPLACE,

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
    /** The item the goal must hold, its success condition with the count, for adopt and revise. */
    val successItem: String? = null,
    /**
     * How many of the success item the goal must hold in total, counting what is already held,
     * for adopt and revise.
     */
    val successCount: Long? = null,
    /** The concrete next step this decision's intent takes, for adopt, continue and revise. */
    val step: String? = null,
    /** The verdict on the carried goal's previous step, for every disposition but adopt. */
    val stepResult: GoalStepResult? = null,
    /**
     * For an adopt or replace with no success item: a discovery goal, met when a walk to the edge
     * of sight reveals a useful resource no footing gives. Present only as `true`.
     */
    val discover: Boolean? = null,
    /**
     * For an adopt or replace with no success item: a shelter goal, met when the server's ground
     * seals the space the agent stands in. Present only as `true`.
     */
    val shelter: Boolean? = null,
    /** For an adopt while a shelter goal is held: a resource subgoal that feeds it. Present only as `true`. */
    val feeds: Boolean? = null,
    /** Why a replace or an abandon ends the held goal, which the body checks against its evidence. */
    val reason: GoalReason? = null,
) {
    init {
        requireTypedSuccess(successItem, successCount)
        step?.let { requireBoundedText("goal step", it, MAX_GOAL_STATEMENT_CHARS) }
        require(discover != false && shelter != false && feeds != false) {
            "discover, shelter and feeds are present only as true"
        }
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

    /** Dig up to five offered blocks in order, stopping at the first that is not dug. */
    @Serializable
    @SerialName("digSequence")
    data class DigSequence(val blocks: List<String>) : Intent {
        init {
            require(blocks.size in 1..MAX_DIG_SEQUENCE_BLOCKS) {
                "a dig sequence names 1..$MAX_DIG_SEQUENCE_BLOCKS blocks, got ${blocks.size}"
            }
            blocks.forEach(::requireBoundedRef)
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
