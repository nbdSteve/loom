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

use serde::de::{self, Deserializer};
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

/// A horizontal compass bearing from the agent to something it can see.
///
/// Four cardinal points and no finer: it is the engine's own nearest-axis answer
/// for the delta, and a bearing precise enough to look like a heading would be a
/// precision the body never measured.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Bearing {
    #[serde(rename = "north")]
    North,
    #[serde(rename = "east")]
    East,
    #[serde(rename = "south")]
    South,
    #[serde(rename = "west")]
    West,
}

/// Which part of the world's day it is.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum DayPhase {
    #[serde(rename = "dawn")]
    Dawn,
    #[serde(rename = "day")]
    Day,
    #[serde(rename = "dusk")]
    Dusk,
    #[serde(rename = "night")]
    Night,
}

/// The bounded world clock the body reveals.
///
/// Survival depends on it - what is safe at midday is not safe at night - and it
/// is a fact about the world rather than about the body, which is why it is named
/// separately from the self-state it rides in.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorldState {
    /// Which part of the day it is.
    pub day_phase: DayPhase,
    /// Whether it is daylight where the body stands, as the level itself answers
    /// it. Distinct from [`WorldState::day_phase`] because a dimension with a
    /// fixed time has a day time and no daylight.
    pub is_day: bool,
    /// Ticks from now until the next nightfall, wrapping the world's day. Always
    /// positive: during the night it counts to the following nightfall, and the
    /// phase is what says the night is already here.
    ///
    /// Enforced at decode by [`positive_nightfall_countdown`] rather than trusted,
    /// because a value below one is the one wrong answer that reads as a right one.
    #[serde(deserialize_with = "positive_nightfall_countdown")]
    pub ticks_until_nightfall: i64,
}

/// Refuses a nightfall countdown of less than one tick.
///
/// The wrap is a type rule on both sides of the contract, not a convention: the
/// schema says `minimum: 1` and the JVM binding refuses it in its own `init`, so
/// this side has to as well or "the contract refuses it" is only true of one
/// runtime. Zero is the value worth refusing rather than merely an odd one - it
/// is what a countdown that had run out would carry, so a night would arrive as a
/// nightfall permanently about to happen, and the mind cannot tell that from a
/// nightfall genuinely one tick away. A negative is the same mistake having gone
/// past.
///
/// Only decoding is narrowed. Serialization is left as the derive wrote it, so
/// every valid fixture still round-trips byte-for-byte.
fn positive_nightfall_countdown<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let ticks = i64::deserialize(deserializer)?;
    if ticks < 1 {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(ticks),
            &"a nightfall countdown of at least 1 tick",
        ));
    }
    Ok(ticks)
}

/// The bounded self-state the body chose to reveal. Owned primitives only, so
/// the mind cannot reach back into world state through it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SelfState {
    /// Current health of the agent's body.
    pub health: f64,
    /// How much of the agent's energy reserve is left, as a fraction: `1.0` is a
    /// full reserve and `0.0` is exhaustion, which is fatal. A fraction rather
    /// than a count of ticks, so the mind never has to know how long a reserve
    /// lasts.
    pub energy: f64,
    /// World position `[x, y, z]`. Continuous coordinates.
    pub position: [f64; 3],
    /// Already-summarized inventory the body chose to reveal.
    pub inventory_summary: Vec<String>,
    /// The bounded world clock the body reveals.
    pub world: WorldState,
}

/// One already-summarized observation the body chose to reveal. The perception
/// carries a list of these instead of a live world handle.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Observation {
    /// Human-readable summary of the observed thing.
    pub what: String,
    /// Distance from the agent to the observed thing, in blocks.
    pub distance: f64,
    /// The horizontal bearing from the agent to the observed thing.
    pub direction: Bearing,
    /// How far above the agent the observed thing is, in blocks; negative if it
    /// is below. Separate from the bearing because a bearing is horizontal, and a
    /// thing on a cliff is not reachable by walking toward it.
    pub vertical_offset: f64,
    /// Whether the body has an unobstructed line of sight to the observed thing.
    /// `false` means the body knows it is there and cannot see it, which is a
    /// different situation from not knowing at all.
    pub visible: bool,
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
#[serde(rename_all = "camelCase", deny_unknown_fields)]
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
    /// How many otherwise eligible observations the body's own count bound
    /// dropped before this perception was serialized: things in range and alive
    /// that would have been described, and are not in
    /// [`Perception::surroundings`].
    ///
    /// Required rather than optional, and never negative. A mind that was handed
    /// a trimmed scene with no way to know it had been trimmed would reason about
    /// "everything I can see" over a list that was not everything, so absence
    /// must not be able to read as completeness. Zero says the list is the whole
    /// of what the body had; out-of-range and ineligible things are not
    /// omissions, because they were never observations.
    pub surroundings_omitted: i64,
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
    /// Rest, refilling the agent's energy reserve.
    ///
    /// The only way to restore [`SelfState::energy`], and it costs the agent the
    /// cycle it spends on it.
    #[serde(rename = "rest")]
    Rest,
    /// Do nothing this cycle.
    #[serde(rename = "idle")]
    Idle,
}

/// Reproducibility stamp attached to every decision and written to the
/// append-only decision log, so each decision's
/// perception -> reasoning -> action -> result chain can be replayed and audited.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
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
#[serde(rename_all = "camelCase", deny_unknown_fields)]
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
