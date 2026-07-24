//! Rust serde bindings for the Loom perception/decision contract.
//!
//! The single source of truth for these types is `contract/loom.schema.json`
//! at the repository root. These hand-written types mirror that schema and are
//! kept honest by the tests in this crate, which validate the shared fixtures
//! against the schema and assert that every fixture round-trips through these
//! types byte-for-byte. If the schema and these types drift, the build fails.
//!
//! Wire tagging is internal: the `Trigger` and `Intent` unions carry a
//! discriminator property (`event` and `action` respectively) with the
//! variant's fields inlined alongside. This is matched byte-for-byte by the
//! JVM side's kotlinx-serialization configuration.

use serde::{Deserialize, Serialize};

/// Importance tier that drives model routing on the mind side and is stamped
/// onto every decision's provenance for the decision log.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum DecisionImportance {
    #[serde(rename = "ROUTINE")]
    Routine,
    #[serde(rename = "NOTABLE")]
    Notable,
    #[serde(rename = "PIVOTAL")]
    Pivotal,
}

/// The bounded self-state the body chose to reveal. Owned primitives only, so
/// the mind cannot reach back into world state through it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SelfState {
    /// Current health of the agent's body.
    pub health: f64,
    /// World position `[x, y, z]`. Continuous coordinates.
    pub position: [f64; 3],
    /// Already-summarized inventory the body chose to reveal.
    pub inventory_summary: Vec<String>,
}

/// One already-summarized observation the body chose to reveal. The perception
/// carries a list of these instead of a live world handle.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Observation {
    /// Human-readable summary of the observed thing.
    pub what: String,
    /// Distance from the agent to the observed thing, in blocks.
    pub distance: f64,
}

/// The event that triggered cognition. There is intentionally no per-tick
/// variant: cognition is event-driven, never per-tick.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "event")]
pub enum Trigger {
    /// Another actor spoke to or near the agent.
    #[serde(rename = "spoken")]
    Spoken { from: String, text: String },
    /// The agent's body took damage.
    #[serde(rename = "damaged")]
    Damaged { amount: i64, source: String },
    /// A notable block came into the agent's view.
    #[serde(rename = "blockSighted")]
    BlockSighted { block: String },
    /// A scheduled prompt for the agent to reconsider its goals.
    #[serde(rename = "goalReview")]
    GoalReview,
}

/// The bounded snapshot the body exposes to the mind. Carries no world handle:
/// this is the entire surface the mind sees.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Perception {
    /// Correlates this perception with the decision the mind returns.
    pub request_id: String,
    /// Stable identifier of the agent whose body produced this perception.
    pub agent_id: String,
    /// The event that triggered cognition.
    pub trigger: Trigger,
    /// Importance tier, used to route the decision to a model.
    pub importance: DecisionImportance,
    /// The server tick on which the body captured this snapshot.
    pub captured_tick: i64,
    /// The bounded self-state the body chose to reveal.
    pub self_state: SelfState,
    /// The summarized observations the body chose to reveal.
    pub surroundings: Vec<Observation>,
}

/// A named intent the mind returns. The body validates and applies it; the mind
/// never mutates world state directly.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "action")]
pub enum Intent {
    /// Speak a line of text.
    #[serde(rename = "say")]
    Say { text: String },
    /// Move toward a target block position `[x, y, z]`.
    #[serde(rename = "moveTo")]
    MoveTo { target: [i64; 3] },
    /// Flee from a named source of threat.
    #[serde(rename = "flee")]
    Flee { from: String },
    /// Do nothing this cycle.
    #[serde(rename = "idle")]
    Idle,
}

/// Reproducibility stamp attached to every decision and written to the
/// append-only decision log, so each decision's
/// perception -> reasoning -> action -> result chain can be replayed and audited.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Provenance {
    /// The importance tier the decision was routed at.
    pub tier: DecisionImportance,
    /// Identifier of the model that produced the decision.
    pub model: String,
    /// Version of the prompt template used, for reproducibility.
    pub prompt_version: String,
}

/// The structured decision the mind returns for a `Perception`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Decision {
    /// Echoes the `Perception::request_id` this decision answers.
    pub request_id: String,
    /// Echoes the `Perception::agent_id` this decision is for.
    pub agent_id: String,
    /// The named intent the body will validate and apply.
    pub intent: Intent,
    /// The mind's natural-language justification, logged for research.
    pub reasoning: String,
    /// Reproducibility stamp for the decision log.
    pub provenance: Provenance,
}
