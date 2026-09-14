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

/// Vanilla's ceiling for both food and saturation.
pub const MAX_FOOD_LEVEL: i64 = 20;

/// Floating-point form of vanilla's saturation ceiling.
pub const MAX_SATURATION_LEVEL: f64 = 20.0;

/// Refuses a food level outside vanilla's inclusive range.
fn bounded_food_level<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let food = i64::deserialize(deserializer)?;
    if !(0..=MAX_FOOD_LEVEL).contains(&food) {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(food),
            &"a vanilla food level from 0 to 20",
        ));
    }
    Ok(food)
}

/// Refuses saturation outside vanilla's inclusive range.
fn bounded_saturation<'de, D>(deserializer: D) -> Result<f64, D::Error>
where
    D: Deserializer<'de>,
{
    let saturation = f64::deserialize(deserializer)?;
    if !(0.0..=MAX_SATURATION_LEVEL).contains(&saturation) {
        return Err(de::Error::invalid_value(
            de::Unexpected::Float(saturation),
            &"vanilla saturation from 0.0 to 20.0",
        ));
    }
    Ok(saturation)
}

/// The fewest items an occupied inventory slot can hold, mirroring the schema's `minimum`.
///
/// One, because an empty slot is left out of the summary rather than reported as nothing.
const STACK_MIN_ITEMS: i64 = 1;

/// Refuses an inventory stack holding fewer items than an occupied slot can.
///
/// The bound is a type rule on both sides of the contract for the same reason the nightfall
/// countdown's is: the schema's `minimum: 1` is a rule a socket walks straight past, and the
/// JVM binding refuses it in its own `init`, so this side has to as well. This runtime is the
/// only decoder of the field - a `Perception` is written by the body and read by the mind - so
/// the check that is missing here is the check that would have mattered.
///
/// Zero is the value worth refusing rather than merely an odd one. It reads to a model as food
/// the agent is carrying and does not have, on the one path whose whole purpose is deciding
/// whether to eat, and the body cannot have meant it: an empty slot is omitted from the summary
/// instead. A negative is the same mistake having gone past.
///
/// Only decoding is narrowed. Serialization is left as the derive wrote it, so every valid
/// fixture still round-trips byte-for-byte.
fn occupied_stack_count<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let count = i64::deserialize(deserializer)?;
    if count < STACK_MIN_ITEMS {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(count),
            &"an inventory stack of at least 1 item",
        ));
    }
    Ok(count)
}

/// The most occupied slots an inventory summary can state, mirroring the schema's `maxItems`.
///
/// Forty-six, the complete player inventory window.
const MAX_INVENTORY_STACKS: usize = 46;

/// Refuses an inventory summary longer than a body's own container.
///
/// The last of the bounds this file narrows, and it needs narrowing for exactly the reason the
/// others did: `maxItems` is a rule a socket walks straight past, so a summary of nine slots
/// decoded happily on both sides and was refused only by a schema neither runtime runs. This
/// runtime is the only decoder of the field, so the check that is missing here is the check that
/// would have mattered.
///
/// Only decoding is narrowed, as with [`occupied_stack_count`]: serialization is left as the
/// derive wrote it, so every valid fixture still round-trips byte-for-byte.
fn bounded_inventory_summary<'de, D>(deserializer: D) -> Result<Vec<InventoryStack>, D::Error>
where
    D: Deserializer<'de>,
{
    let stacks = Vec::<InventoryStack>::deserialize(deserializer)?;
    if stacks.len() > MAX_INVENTORY_STACKS {
        return Err(de::Error::invalid_length(
            stacks.len(),
            &"at most 46 occupied inventory slots",
        ));
    }
    Ok(stacks)
}

/// The shortest a ref may be, mirroring the schema's `minLength`.
///
/// Stated here once for this whole runtime rather than at each field that carries
/// a ref, because two copies of a bound are two bounds that can disagree. The
/// schema gives `EntityRef` and `SlotRef` the same bound deliberately: they are the
/// same shape of handle and differ only in what they resolve to.
const REF_MIN_CHARS: usize = 1;

/// The longest a ref may be, mirroring the schema's `maxLength`.
const REF_MAX_CHARS: usize = 8;

/// Refuses a ref that is blank or longer than a ref can be.
///
/// The bound is a type rule on both sides of the contract for the same reason the
/// nightfall countdown's is: nothing on either wire is schema-validated at
/// runtime, so `minLength`/`maxLength` alone is a rule a socket walks straight
/// past. Both ends are worth refusing rather than merely odd. A blank ref names
/// nothing the body can resolve, and it is what an absent handle becomes if
/// anything upstream ever fills the field instead of omitting it - so refusing it
/// keeps "a ref is present" and "a ref means something" the same statement. An
/// over-long one is a handle no request ever issued, which is a mind having
/// invented or rewritten one rather than named back what it was given.
///
/// Length is counted in characters, as the schema counts it.
fn refuse_unbounded_ref<E>(handle: &str) -> Result<(), E>
where
    E: de::Error,
{
    let chars = handle.chars().count();
    if !(REF_MIN_CHARS..=REF_MAX_CHARS).contains(&chars) {
        return Err(E::invalid_length(chars, &"a ref of 1 to 8 characters"));
    }
    Ok(())
}

/// Refuses an out-of-bounds ref on an observation, where the field may be absent.
///
/// Only decoding is narrowed. Serialization is left as the derive wrote it, so
/// every valid fixture still round-trips byte-for-byte.
fn bounded_optional_ref<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let handle = Option::<String>::deserialize(deserializer)?;
    if let Some(present) = handle.as_deref() {
        refuse_unbounded_ref(present)?;
    }
    Ok(handle)
}

/// Refuses an out-of-bounds ref on an intent, where the field is required.
///
/// Only decoding is narrowed, as with [`bounded_optional_ref`].
fn bounded_ref<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let handle = String::deserialize(deserializer)?;
    refuse_unbounded_ref(&handle)?;
    Ok(handle)
}

/// One occupied slot of the body's own inventory, as the body describes it.
///
/// The whole of what the mind is told about what it is carrying, and the only
/// place an [`Intent::Eat`] can get a handle from.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InventoryStack {
    /// The handle by which a decision may name this exact stack back to the body.
    ///
    /// Named `slot_ref` in Rust and `ref` on the wire because `ref` is a keyword
    /// here. Narrowed at decode by [`bounded_ref`], exactly as an entity ref is.
    #[serde(rename = "ref", deserialize_with = "bounded_ref")]
    pub slot_ref: String,
    /// What the stack holds, as the body describes it. A description for the mind
    /// to reason with, never the handle: two slots can hold the same item and be
    /// different stacks.
    pub what: String,
    /// How many items the stack holds. At least one, because an empty slot is not
    /// reported at all.
    ///
    /// Enforced at decode by [`occupied_stack_count`] rather than trusted, because the
    /// schema's `minimum` is not checked by anything on the wire and a count of zero would
    /// tell a mind it is carrying food it does not have.
    #[serde(deserialize_with = "occupied_stack_count")]
    pub count: i64,
}

/// The bounded self-state the body chose to reveal. Owned primitives only, so
/// the mind cannot reach back into world state through it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SelfState {
    /// Current health of the agent's body.
    pub health: f64,
    /// The player's vanilla food level.
    #[serde(deserialize_with = "bounded_food_level")]
    pub food: i64,
    /// Vanilla saturation, kept separate because it is spent before food falls.
    #[serde(deserialize_with = "bounded_saturation")]
    pub saturation: f64,
    /// World position `[x, y, z]`. Continuous coordinates.
    pub position: [f64; 3],
    /// The body's own inventory, one entry per occupied slot and nothing for an
    /// empty one, bounded by the player's forty-six-slot window.
    ///
    /// Enforced at decode by [`bounded_inventory_summary`] rather than trusted, because
    /// the schema's `maxItems` is not checked by anything on the wire.
    #[serde(deserialize_with = "bounded_inventory_summary")]
    pub inventory_summary: Vec<InventoryStack>,
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
    /// The handle by which a decision may name this particular thing back to the
    /// body, present only for a living thing.
    ///
    /// A block carries none, because a block is named by its position instead, so
    /// `None` is a fact about what the observation is rather than an omission. The
    /// field is named `entity_ref` in Rust and `ref` on the wire because `ref` is a
    /// keyword here; it is skipped entirely when absent, which is what keeps a
    /// block observation byte-identical on both sides of the contract. Its length
    /// is narrowed at decode by [`bounded_optional_ref`], because a ref the body
    /// could never have issued is worth refusing where it arrives.
    #[serde(
        rename = "ref",
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_ref"
    )]
    pub entity_ref: Option<String>,
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
    /// A living thing that hunts this agent's kind came into the body's view.
    ///
    /// Everything the body already verified about it travels with the trigger, so
    /// the mind never has to ask a second question to decide whether to run: which
    /// thing it is, the handle to name it back by, how far and in which direction
    /// it lies, whether the body can actually see it, and the engine's own verdict
    /// on whether it could be walked to.
    #[serde(rename = "threatSighted")]
    ThreatSighted {
        /// What the thing is, as the body describes it.
        ///
        /// A description for the mind to reason with, never the handle: naming a
        /// threat by what it is would leave the body guessing which of two
        /// identical creatures was meant.
        threat: String,
        /// The handle by which a decision may name this threat back to the body.
        ///
        /// Narrowed at decode by [`bounded_ref`], exactly as [`Intent::Flee`]'s is:
        /// an over-long handle is one no request ever issued. Named `entity_ref` in
        /// Rust and `ref` on the wire because `ref` is a keyword here.
        #[serde(rename = "ref", deserialize_with = "bounded_ref")]
        entity_ref: String,
        /// Distance from the agent to the threat, in blocks, measured as an
        /// observation's is.
        distance: f64,
        /// The horizontal bearing from the agent to the threat.
        direction: Bearing,
        /// Whether the body has an unobstructed line of sight to the threat.
        ///
        /// `false` is not safety: it means the body knows the threat is there and
        /// something stands between them.
        visible: bool,
        /// Whether the engine's own bounded ground search found a route reaching
        /// the threat.
        ///
        /// Evidence for the mind, and the result of a search the body already paid
        /// for - not an invitation to ask for another. Deliberately not part of
        /// what makes a sighting expensive; the body's own `importanceOf` decides
        /// that.
        reachable: bool,
    },
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
    /// Move away from one living thing, named by the `ref` it carried in the
    /// perception being answered.
    ///
    /// A ref rather than a name or a type on purpose: two identical creatures are
    /// different threats, and the body must retreat from the one the mind meant.
    /// The body resolves it against that one request only. Its length is narrowed
    /// at decode by [`bounded_ref`], so a blank or over-long handle is refused as
    /// a malformed answer rather than travelling to the body to be refused there.
    #[serde(rename = "flee")]
    Flee {
        #[serde(deserialize_with = "bounded_ref")]
        from: String,
    },
    /// Eat one item from one stack of the body's own inventory, named by the `ref`
    /// that stack carried in the perception being answered.
    ///
    /// A ref rather than an item name on purpose: two slots can hold the same item,
    /// and the body must consume the one the mind meant. The body resolves it
    /// against that one request only, re-reads the slot it names, and refuses what
    /// it finds there if it is no longer food. On acceptance Mineflayer asks
    /// vanilla to consume exactly one item; the next perception reports the
    /// resulting [`SelfState::food`] and [`SelfState::saturation`] separately.
    /// Named `slot_ref` in Rust and `ref` on the wire because `ref` is a keyword
    /// here.
    #[serde(rename = "eat")]
    Eat {
        #[serde(rename = "ref", deserialize_with = "bounded_ref")]
        slot_ref: String,
    },
    /// Stop, and hold this position on purpose.
    ///
    /// It restores nothing. Hunger continues under vanilla's own food and
    /// saturation rules while a resting agent waits.
    ///
    /// Its own variant rather than a synonym for [`Intent::Idle`] because
    /// deliberately waiting somewhere - sealed in for the night, say - is a choice a
    /// run must be able to count separately from having decided nothing.
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
