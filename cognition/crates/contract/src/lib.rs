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
/// precision the body never measured. [`Bearing::Here`] is the one value that is
/// not a direction: the thing lies in the agent's own block column, such as the
/// block it stands on, for which any compass point would be invented.
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
    #[serde(rename = "here")]
    Here,
}

/// The vanilla shape one request-scoped world observation describes.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObservationKind {
    #[serde(rename = "entity")]
    Entity,
    #[serde(rename = "block")]
    Block,
    #[serde(rename = "droppedItem")]
    DroppedItem,
}

/// One face of a sensed support block, chosen by a place intent.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum BlockFace {
    #[serde(rename = "down")]
    Down,
    #[serde(rename = "up")]
    Up,
    #[serde(rename = "north")]
    North,
    #[serde(rename = "south")]
    South,
    #[serde(rename = "west")]
    West,
    #[serde(rename = "east")]
    East,
}

/// The bounded player equipment destination chosen by an equip intent.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum EquipmentDestination {
    #[serde(rename = "hand")]
    Hand,
    #[serde(rename = "offHand")]
    OffHand,
    #[serde(rename = "head")]
    Head,
    #[serde(rename = "torso")]
    Torso,
    #[serde(rename = "legs")]
    Legs,
    #[serde(rename = "feet")]
    Feet,
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

/// The most exact recipe offers one perception may carry.
const MAX_CRAFTING_OPTIONS: usize = 24;

/// The most entity, block, and dropped-item observations one perception may combine.
pub const MAX_PERCEPTION_OBSERVATIONS: usize = 144;

/// The most applications one craft intent may request.
pub const MAX_CRAFTS_PER_INTENT: i64 = 8;

/// The most swings one attack intent may request.
pub const MAX_ATTACKS_PER_INTENT: i64 = 4;

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

/// Refuses a crafting-option list longer than the perception contract permits.
fn bounded_crafting_options<'de, D>(deserializer: D) -> Result<Vec<CraftingOption>, D::Error>
where
    D: Deserializer<'de>,
{
    let options = Vec::<CraftingOption>::deserialize(deserializer)?;
    if options.len() > MAX_CRAFTING_OPTIONS {
        return Err(de::Error::invalid_length(
            options.len(),
            &"at most 24 exact crafting options",
        ));
    }
    Ok(options)
}

/// Refuses a surroundings list longer than the shared perception contract permits.
fn bounded_surroundings<'de, D>(deserializer: D) -> Result<Vec<Observation>, D::Error>
where
    D: Deserializer<'de>,
{
    let observations = Vec::<Observation>::deserialize(deserializer)?;
    if observations.len() > MAX_PERCEPTION_OBSERVATIONS {
        return Err(de::Error::invalid_length(
            observations.len(),
            &"at most 144 world observations",
        ));
    }
    Ok(observations)
}

/// Refuses a recipe yield outside the bounded item count shown to the mind.
fn bounded_recipe_result_count<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let count = i64::deserialize(deserializer)?;
    if !(1..=64).contains(&count) {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(count),
            &"a recipe result count from 1 to 64",
        ));
    }
    Ok(count)
}

/// Refuses a recipe-application count outside the per-intent bound.
fn bounded_crafts<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let count = i64::deserialize(deserializer)?;
    if !(1..=MAX_CRAFTS_PER_INTENT).contains(&count) {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(count),
            &"a craft count from 1 to 8",
        ));
    }
    Ok(count)
}

/// Refuses an attack count outside the per-intent strike bound.
fn bounded_attacks<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let count = i64::deserialize(deserializer)?;
    if !(1..=MAX_ATTACKS_PER_INTENT).contains(&count) {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(count),
            &"an attack count from 1 to 4",
        ));
    }
    Ok(count)
}

/// Refuses a negative omission count.
fn nonnegative_omission<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let count = i64::deserialize(deserializer)?;
    if count < 0 {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(count),
            &"a non-negative omission count",
        ));
    }
    Ok(count)
}

/// The shortest a ref may be, mirroring the schema's `minLength`.
///
/// Stated here once for this whole runtime rather than at each field that carries
/// a ref, because two copies of a bound are two bounds that can disagree. The
/// schema gives `EntityRef` and `SlotRef` the same bound deliberately: they are the
/// same shape of handle and differ only in what they resolve to.
const REF_MIN_CHARS: usize = 1;

/// The longest a ref may be, mirroring the schema's `maxLength`.
pub const REF_MAX_CHARS: usize = 8;

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

/// One exact currently craftable recipe offered by this perception.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CraftingOption {
    /// Request-scoped handle retaining the exact recipe and workstation.
    #[serde(rename = "ref", deserialize_with = "bounded_ref")]
    pub recipe_ref: String,
    /// The result item, as the body describes it.
    pub what: String,
    /// How many result items one application produces.
    #[serde(deserialize_with = "bounded_recipe_result_count")]
    pub result_count: i64,
    /// How many applications current ingredients and Loom's bound permit.
    #[serde(deserialize_with = "bounded_crafts")]
    pub max_crafts: i64,
    /// Whether the retained recipe is tied to one reachable crafting table.
    pub requires_table: bool,
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
    /// Exact currently craftable recipes backed by this same perception.
    #[serde(deserialize_with = "bounded_crafting_options")]
    pub crafting_options: Vec<CraftingOption>,
    /// Craftable recipe offers omitted by the body-side list bound.
    #[serde(deserialize_with = "nonnegative_omission")]
    pub crafting_options_omitted: i64,
    /// The bounded world clock the body reveals.
    pub world: WorldState,
}

/// One already-summarized observation the body chose to reveal. The perception
/// carries a list of these instead of a live world handle.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    /// Whether this is a living entity, a block, or a dropped-item entity.
    pub kind: ObservationKind,
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
    /// The request-scoped handle by which a decision may name this exact target
    /// back to the body.
    #[serde(rename = "ref", deserialize_with = "bounded_ref")]
    pub target_ref: String,
    /// How many items a dropped-item entity represents. Present only for
    /// [`ObservationKind::DroppedItem`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub count: Option<i64>,
    /// Whether a body can stand at this block, as the server's ground says. Present
    /// exactly for [`ObservationKind::Block`]: a block whose face is in sight can still be
    /// covered, and a walk to a covered block has nowhere to end.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub standable: Option<bool>,
    /// What digging this block with the held item drops, as the server's loot rules answer it.
    /// Present exactly for [`ObservationKind::Block`]; empty when it drops nothing.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub yields: Option<Vec<String>>,
    /// Whether digging this block leaves the body a way out. Present exactly for
    /// [`ObservationKind::Block`]; false only for the support block of a body with no exit.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dig_safe: Option<bool>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ObservationWire {
    kind: ObservationKind,
    what: String,
    distance: f64,
    direction: Bearing,
    vertical_offset: f64,
    visible: bool,
    #[serde(rename = "ref", deserialize_with = "bounded_ref")]
    target_ref: String,
    #[serde(default)]
    count: Option<i64>,
    #[serde(default)]
    standable: Option<bool>,
    #[serde(default, deserialize_with = "bounded_optional_yields")]
    yields: Option<Vec<String>>,
    #[serde(default)]
    dig_safe: Option<bool>,
}

impl<'de> Deserialize<'de> for Observation {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let raw = ObservationWire::deserialize(deserializer)?;
        if raw.count.is_some() != (raw.kind == ObservationKind::DroppedItem) {
            return Err(de::Error::custom(
                "count is present exactly for a dropped-item observation",
            ));
        }
        let block = raw.kind == ObservationKind::Block;
        if raw.standable.is_some() != block
            || raw.yields.is_some() != block
            || raw.dig_safe.is_some() != block
        {
            return Err(de::Error::custom(
                "standable, yields and digSafe are present exactly for a block observation",
            ));
        }
        if raw.count.is_some_and(|count| count < 1) {
            return Err(de::Error::custom(
                "a dropped-item observation must hold at least one item",
            ));
        }
        Ok(Self {
            kind: raw.kind,
            what: raw.what,
            distance: raw.distance,
            direction: raw.direction,
            vertical_offset: raw.vertical_offset,
            visible: raw.visible,
            target_ref: raw.target_ref,
            count: raw.count,
            standable: raw.standable,
            yields: raw.yields,
            dig_safe: raw.dig_safe,
        })
    }
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
    #[serde(deserialize_with = "bounded_surroundings")]
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
    /// The agent's held short-term goal, carried into a goal review only.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub goal: Option<GoalSummary>,
}

/// Affirmed steps in a row that may change neither the agent's inventory nor its place
/// before a goal can no longer be continued or revised: past it the body refuses both, ends
/// the goal, and the agent must adopt anew. A revise carries the count, because rewording a
/// goal changes nothing in the world. The bound that keeps a held goal from becoming a
/// no-progress loop.
pub const MAX_GOAL_STEPS_WITHOUT_PROGRESS: i64 = 3;

/// Longest a goal statement may be, in characters, mirroring the schema.
pub const MAX_GOAL_STATEMENT_CHARS: usize = 160;

/// Longest a goal id or step request id may be, mirroring the schema.
pub const MAX_GOAL_ID_CHARS: usize = 64;

/// Longest a step's action name may be, mirroring the schema.
const MAX_GOAL_ACTION_CHARS: usize = 32;

fn refuse_unbounded_text<E>(text: &str, max: usize) -> Result<(), E>
where
    E: de::Error,
{
    let chars = text.chars().count();
    if !(1..=max).contains(&chars) {
        return Err(E::invalid_length(chars, &"a bounded non-empty goal field"));
    }
    Ok(())
}

fn bounded_goal_statement<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let text = String::deserialize(deserializer)?;
    refuse_unbounded_text(&text, MAX_GOAL_STATEMENT_CHARS)?;
    Ok(text)
}

fn bounded_optional_goal_statement<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let text = Option::<String>::deserialize(deserializer)?;
    if let Some(text) = &text {
        refuse_unbounded_text(text, MAX_GOAL_STATEMENT_CHARS)?;
    }
    Ok(text)
}

fn bounded_goal_id<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let text = String::deserialize(deserializer)?;
    refuse_unbounded_text(&text, MAX_GOAL_ID_CHARS)?;
    Ok(text)
}

fn bounded_goal_action<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let text = String::deserialize(deserializer)?;
    refuse_unbounded_text(&text, MAX_GOAL_ACTION_CHARS)?;
    Ok(text)
}

/// Longest item name a success condition or inventory change may carry, in characters.
pub const MAX_ITEM_NAME_CHARS: usize = 64;

/// Most of one item a success condition may ask the goal to hold.
pub const MAX_SUCCESS_COUNT: i64 = 64;

/// Most inventory changes one progress reading carries: one per player inventory slot.
pub const MAX_INVENTORY_CHANGES: usize = 46;

/// Most items one block observation says digging it drops.
pub const MAX_BLOCK_YIELDS: usize = 8;

/// Whether `name` is an item as an inventory stack names it: `item.<namespace>.<path>`, in
/// lower-case letters, digits, underscores and dots. A block's name (`block.minecraft.terracotta`)
/// is not one, and a success condition naming one could never be met.
#[must_use]
pub fn is_item_name(name: &str) -> bool {
    let Some(rest) = name.strip_prefix("item.") else {
        return false;
    };
    let Some((namespace, path)) = rest.split_once('.') else {
        return false;
    };
    let word = |text: &str, dots: bool| {
        !text.is_empty()
            && text.chars().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || (dots && c == '.')
            })
    };
    name.chars().count() <= MAX_ITEM_NAME_CHARS && word(namespace, false) && word(path, true)
}

fn refuse_non_item<E: de::Error>(name: &str) -> Result<(), E> {
    if is_item_name(name) {
        Ok(())
    } else {
        Err(E::custom("an item name is item.<namespace>.<path>"))
    }
}

fn bounded_optional_item_name<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let text = Option::<String>::deserialize(deserializer)?;
    if let Some(text) = &text {
        refuse_non_item(text)?;
    }
    Ok(text)
}

fn bounded_item_name<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let text = String::deserialize(deserializer)?;
    refuse_non_item(&text)?;
    Ok(text)
}

fn bounded_optional_yields<'de, D>(deserializer: D) -> Result<Option<Vec<String>>, D::Error>
where
    D: Deserializer<'de>,
{
    let yields = Option::<Vec<String>>::deserialize(deserializer)?;
    if let Some(yields) = &yields {
        if yields.len() > MAX_BLOCK_YIELDS {
            return Err(de::Error::invalid_length(yields.len(), &"at most 8 yields"));
        }
        for name in yields {
            refuse_non_item(name)?;
        }
    }
    Ok(yields)
}

fn bounded_optional_success_count<'de, D>(deserializer: D) -> Result<Option<i64>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Option::<i64>::deserialize(deserializer)?;
    if let Some(value) = value {
        if !(1..=MAX_SUCCESS_COUNT).contains(&value) {
            return Err(de::Error::invalid_value(
                de::Unexpected::Signed(value),
                &"a success count from 1 to 64",
            ));
        }
    }
    Ok(value)
}

fn bounded_optional_ref<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let handle = Option::<String>::deserialize(deserializer)?;
    if let Some(handle) = &handle {
        refuse_unbounded_ref(handle)?;
    }
    Ok(handle)
}

fn nonzero_change<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let value = i64::deserialize(deserializer)?;
    if value == 0 {
        return Err(de::Error::custom("an inventory change is never zero"));
    }
    Ok(value)
}

fn bounded_inventory_changes<'de, D>(deserializer: D) -> Result<Vec<InventoryChange>, D::Error>
where
    D: Deserializer<'de>,
{
    let changes = Vec::<InventoryChange>::deserialize(deserializer)?;
    if changes.len() > MAX_INVENTORY_CHANGES {
        return Err(de::Error::invalid_length(
            changes.len(),
            &"at most 46 inventory changes",
        ));
    }
    Ok(changes)
}

fn nonnegative_goal_count<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: Deserializer<'de>,
{
    let value = i64::deserialize(deserializer)?;
    if value < 0 {
        return Err(de::Error::invalid_value(
            de::Unexpected::Signed(value),
            &"a non-negative integer",
        ));
    }
    Ok(value)
}

/// The last step taken under a held goal and the body's outcome tag for it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalStep {
    /// The request id of the decision that took the step.
    #[serde(deserialize_with = "bounded_goal_id")]
    pub request_id: String,
    /// The `Intent` action that decision chose.
    #[serde(deserialize_with = "bounded_goal_action")]
    pub action: String,
    /// The body's countable outcome tag for that intent.
    #[serde(deserialize_with = "bounded_goal_id")]
    pub outcome: String,
}

/// The one short-term goal the agent's previous goal review left held.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalSummary {
    /// The request id of the decision that adopted the goal.
    #[serde(deserialize_with = "bounded_goal_id")]
    pub goal_id: String,
    /// The goal in the agent's own words.
    #[serde(deserialize_with = "bounded_goal_statement")]
    pub statement: String,
    /// How many times the goal has been revised since it was adopted.
    #[serde(deserialize_with = "nonnegative_goal_count")]
    pub revision: i64,
    /// The server tick on which the goal was adopted.
    #[serde(deserialize_with = "nonnegative_goal_count")]
    pub adopted_tick: i64,
    /// The last step taken under the goal, once the body has answered it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_step: Option<GoalStep>,
    /// The observable state that would mean the goal is achieved.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_goal_statement"
    )]
    pub success_condition: Option<String>,
    /// The item the goal must hold, which types the success condition.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_item_name"
    )]
    pub success_item: Option<String>,
    /// How many of the success item the goal must hold.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_success_count"
    )]
    pub success_count: Option<i64>,
    /// The step the goal's last affirming review chose to take.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_goal_statement"
    )]
    pub step: Option<String>,
    /// The action that step's intent took.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub step_action: Option<IntentAction>,
    /// The target ref that step's intent named, absent for an action that names none.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_ref"
    )]
    pub step_ref: Option<String>,
    /// Body-measured evidence about that step, once a later review can compare.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<GoalProgress>,
}

/// Body-measured evidence about the step the goal's last affirming review took.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalProgress {
    /// Whether the inventory now holds the success count of the success item.
    pub condition_met: bool,
    /// How many of the success item the inventory holds now.
    #[serde(deserialize_with = "nonnegative_goal_count")]
    pub condition_held: i64,
    /// Every item whose held count changed since the goal's last affirming review.
    #[serde(deserialize_with = "bounded_inventory_changes")]
    pub inventory_delta: Vec<InventoryChange>,
    /// Whether the held count of the success item rose since that review.
    pub condition_advanced: bool,
    /// Whether the typed step was carried out with evidence, which [`Self::step_evidence`] names.
    pub step_advanced: bool,
    /// What showed the step was carried out, or that nothing did.
    pub step_evidence: StepEvidence,
    /// How many affirmed steps in a row advanced neither.
    #[serde(deserialize_with = "nonnegative_goal_count")]
    pub steps_without_progress: i64,
}

/// What showed the goal's last step was carried out.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum StepEvidence {
    /// A walk reached its visible target, somewhere new to the goal.
    #[serde(rename = "arrived")]
    Arrived,
    /// The inventory held more of an item after the step.
    #[serde(rename = "gained")]
    Gained,
    /// A new dropped item lay in sight after the step.
    #[serde(rename = "dropped")]
    Dropped,
    /// Nothing showed it: a dig that dropped nothing, a walk that did not arrive.
    #[serde(rename = "none")]
    None,
}

/// How the held count of one item changed since the goal's last affirming review.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InventoryChange {
    /// The item, exactly as an inventory stack names it.
    #[serde(deserialize_with = "bounded_item_name")]
    pub what: String,
    /// Positive gained, negative lost; never zero.
    #[serde(deserialize_with = "nonzero_change")]
    pub change: i64,
}

/// The action an [`Intent`] takes, as its `action` tag.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum IntentAction {
    #[serde(rename = "say")]
    Say,
    #[serde(rename = "moveTo")]
    MoveTo,
    #[serde(rename = "flee")]
    Flee,
    #[serde(rename = "eat")]
    Eat,
    #[serde(rename = "dig")]
    Dig,
    #[serde(rename = "collect")]
    Collect,
    #[serde(rename = "place")]
    Place,
    #[serde(rename = "equip")]
    Equip,
    #[serde(rename = "craft")]
    Craft,
    #[serde(rename = "attack")]
    Attack,
    #[serde(rename = "sleep")]
    Sleep,
    #[serde(rename = "rest")]
    Rest,
    #[serde(rename = "idle")]
    Idle,
}

/// The mind's verdict on the step its previous goal review took.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalStepResult {
    #[serde(rename = "done")]
    Done,
    #[serde(rename = "progressing")]
    Progressing,
    #[serde(rename = "noProgress")]
    NoProgress,
}

/// What a decision does with the agent's short-term goal.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalDisposition {
    #[serde(rename = "adopt")]
    Adopt,
    #[serde(rename = "continue")]
    Continue,
    #[serde(rename = "revise")]
    Revise,
    #[serde(rename = "complete")]
    Complete,
    #[serde(rename = "abandon")]
    Abandon,
}

/// A decision's explicit choice about the agent's short-term goal.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalChoice {
    /// What the decision does with the goal.
    pub disposition: GoalDisposition,
    /// The goal's wording, for adopt and revise.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_goal_statement"
    )]
    pub statement: Option<String>,
    /// The observable state that would mean the goal is achieved, for adopt and revise.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_goal_statement"
    )]
    pub success_condition: Option<String>,
    /// The item the goal must hold, typing the success condition, for adopt and revise.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_item_name"
    )]
    pub success_item: Option<String>,
    /// How many of the success item the goal must hold, for adopt and revise.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_success_count"
    )]
    pub success_count: Option<i64>,
    /// The concrete next step this decision's intent takes, for adopt, continue and revise.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_goal_statement"
    )]
    pub step: Option<String>,
    /// The action the step takes, which must be the intent's own, for adopt, continue and revise.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub step_action: Option<IntentAction>,
    /// The target ref the step names, which must be the intent's own; absent for none.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "bounded_optional_ref"
    )]
    pub step_ref: Option<String>,
    /// The verdict on the carried goal's previous step, for every disposition but adopt.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub step_result: Option<GoalStepResult>,
}

/// A named intent the mind returns. The body validates and applies it; the mind
/// never mutates world state directly.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "action")]
pub enum Intent {
    /// Speak a line of text.
    #[serde(rename = "say")]
    Say { text: String },
    /// Walk to one nearby block, named by the `ref` it carried in the perception
    /// being answered.
    ///
    /// A ref rather than coordinates on purpose: a perception states where things
    /// are as a bearing, a distance and a vertical offset, never as coordinates, so
    /// a coordinate the mind wrote was a guess. The body resolves the ref against
    /// that one request only and walks to where a body stands on that exact block:
    /// the cell above a solid one, or the block's own cell where the server gives it
    /// no collision.
    #[serde(rename = "moveTo")]
    MoveTo {
        #[serde(deserialize_with = "bounded_ref")]
        onto: String,
    },
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
    /// Dig one nearby block named by a request-scoped observation ref.
    #[serde(rename = "dig")]
    Dig {
        #[serde(deserialize_with = "bounded_ref")]
        block: String,
    },
    /// Collect one nearby dropped item. `approach` explicitly permits one
    /// bounded path to its admitted position.
    #[serde(rename = "collect")]
    Collect {
        #[serde(deserialize_with = "bounded_ref")]
        item: String,
        approach: bool,
    },
    /// Place one offered stack that is already held against one face of one
    /// sensed support block.
    #[serde(rename = "place")]
    Place {
        #[serde(deserialize_with = "bounded_ref")]
        block: String,
        #[serde(deserialize_with = "bounded_ref")]
        item: String,
        face: BlockFace,
    },
    /// Equip one offered stack into one chosen equipment destination.
    #[serde(rename = "equip")]
    Equip {
        #[serde(deserialize_with = "bounded_ref")]
        item: String,
        destination: EquipmentDestination,
    },
    /// Apply one exact offered recipe a bounded number of times.
    #[serde(rename = "craft")]
    Craft {
        #[serde(deserialize_with = "bounded_ref")]
        recipe: String,
        #[serde(deserialize_with = "bounded_crafts")]
        crafts: i64,
    },
    /// Strike one exact target with one exact offered stack.
    #[serde(rename = "attack")]
    Attack {
        #[serde(deserialize_with = "bounded_ref")]
        target: String,
        #[serde(deserialize_with = "bounded_ref")]
        weapon: String,
        #[serde(deserialize_with = "bounded_attacks")]
        attacks: i64,
        approach: bool,
    },
    /// Sleep in one exact offered bed.
    #[serde(rename = "sleep")]
    Sleep {
        #[serde(deserialize_with = "bounded_ref")]
        bed: String,
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

impl Intent {
    /// The action this intent takes, as its `action` tag.
    #[must_use]
    pub const fn action(&self) -> IntentAction {
        match self {
            Self::Say { .. } => IntentAction::Say,
            Self::MoveTo { .. } => IntentAction::MoveTo,
            Self::Flee { .. } => IntentAction::Flee,
            Self::Eat { .. } => IntentAction::Eat,
            Self::Dig { .. } => IntentAction::Dig,
            Self::Collect { .. } => IntentAction::Collect,
            Self::Place { .. } => IntentAction::Place,
            Self::Equip { .. } => IntentAction::Equip,
            Self::Craft { .. } => IntentAction::Craft,
            Self::Attack { .. } => IntentAction::Attack,
            Self::Sleep { .. } => IntentAction::Sleep,
            Self::Rest => IntentAction::Rest,
            Self::Idle => IntentAction::Idle,
        }
    }

    /// The one target ref a goal step names for this intent: the thing it acts on, or
    /// `None` for an action that names nothing. A placement's target is its support block
    /// and an attack's is its creature; the stack each spends is the means, not the target.
    #[must_use]
    pub fn target_ref(&self) -> Option<&str> {
        match self {
            Self::MoveTo { onto } => Some(onto),
            Self::Flee { from } => Some(from),
            Self::Eat { slot_ref } => Some(slot_ref),
            Self::Dig { block } | Self::Place { block, .. } => Some(block),
            Self::Collect { item, .. } | Self::Equip { item, .. } => Some(item),
            Self::Craft { recipe, .. } => Some(recipe),
            Self::Attack { target, .. } => Some(target),
            Self::Sleep { bed } => Some(bed),
            Self::Say { .. } | Self::Rest | Self::Idle => None,
        }
    }
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
    /// The decision's explicit goal choice; absent leaves any held goal unchanged.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub goal: Option<GoalChoice>,
}
