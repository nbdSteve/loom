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
 * The bounded self-state the body chose to reveal. Owned primitives only, so the
 * mind cannot reach back into world state through it.
 */
@Serializable
data class SelfState(
    /** Current health of the agent's body. */
    val health: Double,
    /** World position `[x, y, z]`. Continuous coordinates. */
    val position: List<Double>,
    /** Already-summarized inventory the body chose to reveal. */
    val inventorySummary: List<String>,
) {
    init {
        require(position.size == COORDINATE_DIMENSIONS) {
            "position must have exactly $COORDINATE_DIMENSIONS components [x, y, z], got ${position.size}"
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
