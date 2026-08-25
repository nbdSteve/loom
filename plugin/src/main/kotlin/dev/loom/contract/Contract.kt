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
 * A full energy reserve, the top of the [SelfState.energy] scale.
 *
 * The two ends of the scale are named here rather than left as bare literals
 * wherever energy is computed or asserted: the scale belongs to the contract, and
 * whoever fills the field and whoever reads it must agree on which end is which.
 */
const val ENERGY_FULL: Double = 1.0

/** An exhausted energy reserve, the bottom of the [SelfState.energy] scale. */
const val ENERGY_EXHAUSTED: Double = 0.0

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
 * precision the body never measured.
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
 * The bounded self-state the body chose to reveal. Owned primitives only, so the
 * mind cannot reach back into world state through it.
 */
@Serializable
data class SelfState(
    /** Current health of the agent's body. */
    val health: Double,
    /**
     * How much of the agent's energy reserve is left, as a fraction: `1.0` is a
     * full reserve and `0.0` is exhaustion, which is fatal.
     *
     * A fraction rather than a count of ticks, so the mind never has to know how
     * long a reserve lasts.
     */
    val energy: Double,
    /** World position `[x, y, z]`. Continuous coordinates. */
    val position: List<Double>,
    /** Already-summarized inventory the body chose to reveal. */
    val inventorySummary: List<String>,
    /** The bounded world clock the body reveals. */
    val world: WorldState,
) {
    init {
        require(position.size == COORDINATE_DIMENSIONS) {
            "position must have exactly $COORDINATE_DIMENSIONS components [x, y, z], got ${position.size}"
        }
        require(energy in ENERGY_EXHAUSTED..ENERGY_FULL) {
            "energy must be a fraction in $ENERGY_EXHAUSTED..$ENERGY_FULL, got $energy"
        }
    }
}

/**
 * One already-summarized observation the body chose to reveal. The perception
 * carries a list of these instead of a live world handle.
 */
@Serializable
data class Observation(
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
)

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
)

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

    /** Move toward a target block position `[x, y, z]`. */
    @Serializable
    @SerialName("moveTo")
    data class MoveTo(val target: List<Long>) : Intent {
        init {
            require(target.size == COORDINATE_DIMENSIONS) {
                "target must have exactly $COORDINATE_DIMENSIONS components [x, y, z], got ${target.size}"
            }
        }
    }

    /** Flee from a named source of threat. */
    @Serializable
    @SerialName("flee")
    data class Flee(val from: String) : Intent

    /**
     * Rest, refilling the agent's energy reserve.
     *
     * The only way to restore [SelfState.energy], and it costs the agent the cycle
     * it spends on it.
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
)
