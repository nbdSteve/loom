//! The refs one perception offers, by what each action can actually do with them.
//!
//! A ref is a request-scoped handle, and the body keeps one store per kind of thing it
//! names: creatures, blocks, dropped items, inventory stacks and recipes. An action can
//! spend a handle only from its own store, so a `dig` naming a creature or a `moveTo`
//! naming a stack is a decision the body must refuse. Prose alone does not prevent that,
//! so this is the set both the strict provider schema ([`crate::openai::wire`]) and
//! [`crate::mind::Mind::decide`] hold every answer to.
//!
//! The set is exactly what the prompt shows, cut by the same bounds as
//! [`crate::prompt`], because a handle the mind was never shown is not one it was offered.
//!
//! A handle of the right kind is still one the body must refuse when the action cannot reach
//! it, so each action is offered only what its own body bound admits, measured the way the body
//! measures it:
//! - a block is dug, placed against or slept in only when it is in sight and its centre is
//!   within the body's interaction reach of the eyes;
//! - a walk ends only at a block the server says a body can stand at, and never at one in the
//!   agent's own column - the block under its feet or a plant it stands in - which goes nowhere;
//! - a dropped item is collected without moving only within passive pickup reach, and with an
//!   approach only within movement reach;
//! - a creature is struck without moving only within attack reach, and with an approach only
//!   within the pursuit bound the JVM sends.
//!
//! r10 asked to collect a stick 18 blocks away without moving, and to walk onto a terracotta
//! whose top was covered; both were refused by the body, correctly, and neither should have been
//! offered. `tests/offered_reach_parity.rs` reads every bound below from the body and JVM source.

use loom_contract::{
    is_item_name, Bearing, BlockFace, DayPhase, DropFate, Intent, InventoryStack, Observation,
    ObservationKind, Perception, Trigger, AIR_ITEM, MAX_SUCCESS_COUNT,
};

use crate::prompt::{MAX_CRAFTING_LINES, MAX_INVENTORY_LINES, MAX_OBSERVATIONS};

/// `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`: eyes to a block's centre.
pub const INTERACTION_REACH_BLOCKS: f64 = 5.1;

/// Mineflayer's player eye height, from which interaction reach is measured.
pub const EYE_HEIGHT_BLOCKS: f64 = 1.62;

/// `PASSIVE_PICKUP_HORIZONTAL_BLOCKS` in `body/src/collecting.js`: vanilla's grown pickup box,
/// sideways, from the feet. The mind offers the circle inside that square.
pub const PASSIVE_PICKUP_HORIZONTAL_BLOCKS: f64 = 1.425;

/// `PASSIVE_PICKUP_BELOW_BLOCKS` in `body/src/collecting.js`: how far below the feet it reaches.
pub const PASSIVE_PICKUP_BELOW_BLOCKS: f64 = 0.75;

/// `PASSIVE_PICKUP_ABOVE_BLOCKS` in `body/src/collecting.js`: how far above the feet it reaches.
pub const PASSIVE_PICKUP_ABOVE_BLOCKS: f64 = 2.3;

/// Whether vanilla would pick `seen`, a dropped item, up from where the body stands: inside the
/// grown pickup box, not merely within a straight-line reach (the v25 rehearsal: a stick 1.9
/// away and one block below the feet was offered without moving, and never picked up).
fn within_pickup(seen: &Observation) -> bool {
    let horizontal = (seen.distance.powi(2) - seen.vertical_offset.powi(2))
        .max(0.0)
        .sqrt();
    horizontal < PASSIVE_PICKUP_HORIZONTAL_BLOCKS
        && seen.vertical_offset > -PASSIVE_PICKUP_BELOW_BLOCKS
        && seen.vertical_offset < PASSIVE_PICKUP_ABOVE_BLOCKS
}

/// `MOVE_REACH_BLOCKS` in `body/src/movement.js`: how far an approach may route.
pub const MOVE_REACH_BLOCKS: f64 = 32.0;

/// `ATTACK_REACH_BLOCKS` in `body/src/combat.js`.
pub const ATTACK_REACH_BLOCKS: f64 = 3.1;

/// `COMBAT_MAX_PURSUIT_DISTANCE` in `PlayerSurvivalPreparer.kt`.
pub const MAX_PURSUIT_BLOCKS: f64 = 7.5;

/// Most items a success condition may be offered: craftable first, then the nearest dropped and
/// dug ones. Each is its own closed goal shape per disposition that names a success item, with
/// the count bounds that item admits, so the bound keeps the request schema small.
pub const MAX_OBTAINABLE_ITEMS: usize = 16;

/// Most refs one goal item's steps may name, nearest kinds first, so a request offering every
/// obtainable item its own steps stays inside the provider's enum limits.
pub const MAX_STEP_REFS_PER_ITEM: usize = 4;

/// How near a hunter in sight must be to be offered for a flight at a goal review: the body's
/// perception radius, inside which a sighting is already news.
pub const DANGER_SIGHT_BLOCKS: f64 = 16.0;

/// The most creatures, nearest first as sight lists them, offered to flee or attack: each is up to
/// three enum values (flight, strike, pursuit), and 48 creatures in reach took a contract-legal
/// goal review's schema to 539 of the provider's 500.
pub const MAX_CREATURE_REFS: usize = 16;

/// The most hunters a goal review is offered a flight from, nearest first as sight lists them: a
/// flight from the nearest few is the answer, and every one more is an enum value the provider
/// counts against its 500 (48 hunters in view took a goal review's schema to 594).
pub const MAX_DANGER_REFS: usize = 4;

/// The health at or below which every near hunter is immediate danger and only a survival
/// response is offered; `LOW_HEALTH` in the plugin's `Importance.kt`.
pub const LOW_HEALTH: f64 = 10.0;

/// How near a hunter out of sight must be to be offered for a flight at a goal review: the
/// JVM's `THREAT_NEAR_BLOCKS`, inside which an unseen hunter is news.
pub const DANGER_UNSEEN_BLOCKS: f64 = 8.0;

/// How near a hunter in sight is immediate danger, when only a survival response is offered:
/// `PIVOTAL_THREAT_DISTANCE_BLOCKS` in the JVM.
pub const IMMEDIATE_DANGER_BLOCKS: f64 = 8.0;

/// How near an unseen hunter is immediate danger.
pub const IMMEDIATE_UNSEEN_BLOCKS: f64 = 4.0;

/// How far off a ranged hunter in sight can already hit: `RANGED_THREAT_BLOCKS` in the JVM.
pub const RANGED_THREAT_BLOCKS: f64 = 16.0;

/// The creatures that hunt the agent's kind from range: `RANGED_HUNTERS` in the JVM.
pub const RANGED_HUNTERS: [&str; 6] = [
    "entity.minecraft.skeleton",
    "entity.minecraft.stray",
    "entity.minecraft.bogged",
    "entity.minecraft.pillager",
    "entity.minecraft.witch",
    "entity.minecraft.blaze",
];

/// The health at or below which the body refuses to start an attack: `COMBAT_MIN_HEALTH` in the
/// JVM's `PlayerSurvivalPreparer.kt`.
pub const ATTACK_MIN_HEALTH: f64 = 4.5;

/// The health a hunter can take off while the mind decides - two bites of two - which an attack
/// must leave above [`ATTACK_MIN_HEALTH`] to be offered at all (r24: delta was perceived at 8.5,
/// bitten to 4.5 during its decision, and its attack was refused).
pub const ATTACK_HEALTH_MARGIN: f64 = 4.0;

/// The vanilla food level at or below which a goal review may eat before any goal step: the one
/// survival threshold Loom configures, `HUNGRY_FOOD_LEVEL` in the JVM's `Survival.kt`.
pub const HUNGRY_FOOD_LEVEL: i64 = 7;

/// The vanilla food level below which a player no longer heals: at or below [`LOW_HEALTH`] and
/// under it, a stack the server names food is a meal on any trigger, so a hurt agent can eat its
/// way back to healing before it starves (r33: every agent's food fell below 18 and stayed there,
/// and the agents spent hundreds of ticks at 0.8 to 4.8 health). `HEALING_FOOD_LEVEL` in the JVM.
pub const HEALING_FOOD_LEVEL: i64 = 18;

/// How long before nightfall a shelter goal is offered, in server ticks, and through the night that
/// follows: a pit of three digs and a placement takes a few follow-up reviews, and r36's first
/// hostile struck about 200 ticks after nightfall. `SHELTER_LEAD_TICKS` in the JVM's `Shelter.kt`.
pub const SHELTER_LEAD_TICKS: i64 = 3_000;

/// The most sites of natural cover a shelter step may walk onto, the most enclosed first.
pub const MAX_SHELTER_SITES: usize = 4;

/// How many more than it holds and its own drops of the item lying an item objective must ask: a
/// goal one step could meet is no objective. `MIN_OBJECTIVE_ADVANCE` in the JVM's `HeldObjectives.kt`.
pub const MIN_OBJECTIVE_ADVANCE: i64 = 2;

/// How far what a goal review holds and sees reaches toward its held item goal's total.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum GoalReach {
    /// No item goal is held, or neither sight nor its horizon reaches its total.
    #[default]
    Beyond,
    /// Only its horizon reaches it, past what sight gives.
    Horizon,
    /// What is held and sight could give reaches it.
    Sight,
}

/// Where a perception stands with shelter, read from the server's ground: how enclosed the agent
/// stands and the one-shot steps a shelter goal may take now (r36: all ten agents died at night in
/// the open). Mirrors the JVM's `CoverObservation`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ShelterOffer {
    /// How the agent stands, by its cover reading.
    pub standing: Standing,
    /// Whether night is here or within [`SHELTER_LEAD_TICKS`].
    pub night_near: bool,
    /// How many of the nine faces around the agent are closed.
    pub closed: i64,
    /// The dig underfoot that deepens the cover.
    pub descend: Option<String>,
    /// The placements that close an open face: the support block's ref and its side.
    pub closings: Vec<(String, BlockFace)>,
    /// The stacks the agent carries that place as a solid block, by ref.
    pub solid: Vec<String>,
    /// Of [`Self::solid`], the one in the main hand, which a placement must already be holding.
    pub solid_held: Vec<String>,
    /// Standable blocks out of the agent's own column more enclosed than where it stands.
    pub sites: Vec<String>,
    /// Items sight can give now that place as a solid block.
    pub block_items: Vec<String>,
    /// For a cover nothing held can close, the blocks and drops whose gathering is a shelter step:
    /// those of the cover's own that this perception offers to dig or collect.
    pub gathers: Vec<String>,
}

/// How an agent stands, by its cover reading.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Standing {
    /// The perception carried no cover reading.
    #[default]
    Unread,
    /// Not sealed in, with a short walk out.
    Open,
    /// Not sealed in, and no short walk leads out of where it stands: no flight can run.
    Trapped,
    /// Sealed in: nothing can walk, climb or swim in.
    Sealed,
}

impl ShelterOffer {
    /// Whether the agent stands sealed in.
    #[must_use]
    pub fn sealed(&self) -> bool {
        self.standing == Standing::Sealed
    }

    /// Whether no short walk leads out of where the agent stands and it is not sealed in.
    #[must_use]
    pub fn trapped(&self) -> bool {
        self.standing == Standing::Trapped
    }

    /// What `perception`'s cover reading offers.
    #[must_use]
    pub fn of(perception: &Perception) -> Self {
        let Some(cover) = &perception.self_state.cover else {
            return Self::default();
        };
        let closed = i64::try_from(loom_contract::MAX_COVER_FACES.saturating_sub(cover.open.len()))
            .unwrap_or(0);
        let world = &perception.self_state.world;
        let standing = match (cover.sealed, cover.walk_out) {
            (true, _) => Standing::Sealed,
            (false, false) => Standing::Trapped,
            (false, true) => Standing::Open,
        };
        Self {
            standing,
            night_near: matches!(world.day_phase, DayPhase::Night)
                || world.ticks_until_nightfall <= SHELTER_LEAD_TICKS,
            closed,
            descend: cover.descend.clone(),
            closings: cover
                .closings
                .iter()
                .map(|closing| (closing.block.clone(), closing.side))
                .collect(),
            solid: perception
                .self_state
                .inventory_summary
                .iter()
                .take(MAX_INVENTORY_LINES)
                .filter(|stack| stack.solid == Some(true))
                .map(|stack| stack.slot_ref.clone())
                .collect(),
            solid_held: perception
                .self_state
                .inventory_summary
                .iter()
                .take(MAX_INVENTORY_LINES)
                .filter(|stack| stack.solid == Some(true) && stack.held == Some(true))
                .map(|stack| stack.slot_ref.clone())
                .collect(),
            sites: sites_of(perception, closed),
            block_items: cover.block_items.clone(),
            gathers: cover.gathers.clone(),
        }
    }

    /// Whether any shelter step is offered now: a descent, a closing with a solid stack in hand or
    /// the equip that puts one there, a site, or the gathering of a closer.
    #[must_use]
    pub fn stepped(&self) -> bool {
        self.descend.is_some()
            || (!self.closings.is_empty() && !self.solid.is_empty())
            || !self.sites.is_empty()
            || !self.gathers.is_empty()
    }

    /// The solid stacks an equip may put in the main hand, so a closing can be placed: every one,
    /// while none is held and an open face can be closed.
    #[must_use]
    pub fn to_equip(&self) -> Vec<String> {
        if self.closings.is_empty() || !self.solid_held.is_empty() {
            Vec::new()
        } else {
            self.solid.clone()
        }
    }

    /// Whether a shelter goal may be set: night is near, the agent is not sealed, and a step exists.
    #[must_use]
    pub fn offered(&self) -> bool {
        self.standing != Standing::Unread && self.night_near && !self.sealed() && self.stepped()
    }

    /// Whether `intent` is a shelter step this perception offers.
    #[must_use]
    pub fn steps(&self, intent: &Intent) -> bool {
        match intent {
            Intent::Dig { block } => {
                self.descend.as_deref() == Some(block.as_str()) || self.gathers.contains(block)
            }
            Intent::Collect { item, .. } => self.gathers.contains(item),
            Intent::Place { block, item, face } => {
                self.closings
                    .iter()
                    .any(|(support, side)| support == block && side == face)
                    && self.solid_held.contains(item)
            }
            Intent::Equip { item, destination } => {
                *destination == loom_contract::EquipmentDestination::Hand
                    && self.to_equip().contains(item)
            }
            Intent::MoveTo { onto } => self.sites.contains(onto),
            _ => false,
        }
    }

    /// Whether `intent` closes the agent's own cover, or readies the block that does, where no
    /// flight can run: a survival response.
    #[must_use]
    pub fn shuts_out(&self, intent: &Intent) -> bool {
        self.trapped()
            && matches!(intent, Intent::Place { .. } | Intent::Equip { .. })
            && self.steps(intent)
    }
}

/// The standable blocks out of the agent's own column more enclosed than where it stands: the most
/// enclosed first, the nearest of those first as sight lists them, at most [`MAX_SHELTER_SITES`].
fn sites_of(perception: &Perception, closed: i64) -> Vec<String> {
    let mut sites: Vec<&Observation> = perception
        .surroundings
        .iter()
        .take(MAX_OBSERVATIONS)
        .filter(|seen| {
            seen.direction != Bearing::Here && seen.cover.is_some_and(|faces| faces > closed)
        })
        .collect();
    sites.sort_by_key(|seen| std::cmp::Reverse(seen.cover));
    sites
        .into_iter()
        .take(MAX_SHELTER_SITES)
        .map(|seen| seen.target_ref.clone())
        .collect()
}

/// The steps one goal item may be pursued by, by action: what in sight can give that item.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ItemSteps {
    /// The item these steps give.
    pub item: String,
    /// Blocks in dig reach that can drop it.
    pub dig: Vec<String>,
    /// Drops of it within pickup reach.
    pub pickup: Vec<String>,
    /// Drops of it within route reach.
    pub route: Vec<String>,
    /// Recipes that make it.
    pub recipes: Vec<String>,
    /// Standable blocks out of dig reach that can drop it: a walk onto one reaches a source a dig
    /// could not already reach (r18: all seven credited walks were onto bushes already in reach).
    pub walks: Vec<String>,
    /// Cells at the edge of sight in the direction of a recalled source of it, or of one in sight
    /// beyond dig reach: a walk toward where the body last saw one, or sees one it cannot dig
    /// (r20: no agent without a log in sight could set a log goal; r34 zeta).
    pub toward: Vec<String>,
}

impl ItemSteps {
    /// Whether nothing in sight gives the item.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.dig.is_empty()
            && self.pickup.is_empty()
            && self.route.is_empty()
            && self.recipes.is_empty()
            && self.walks.is_empty()
            && self.toward.is_empty()
    }

    /// Whether `intent` is one of these steps.
    #[must_use]
    pub fn admits(&self, intent: &Intent) -> bool {
        let held = |list: &[String], handle: &str| list.iter().any(|offered| offered == handle);
        match intent {
            Intent::Dig { block } => held(&self.dig, block),
            Intent::Collect { item, approach } => {
                held(if *approach { &self.route } else { &self.pickup }, item)
            }
            Intent::Craft { recipe, .. } => held(&self.recipes, recipe),
            Intent::MoveTo { onto } => held(&self.walks, onto) || held(&self.toward, onto),
            _ => false,
        }
    }
}

/// The handles one perception offers, each list in the order the prompt shows it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct OfferedRefs {
    /// Visible blocks within interaction reach: what `place` and `sleep` may name.
    pub blocks: Vec<String>,
    /// Those of them a dig leaves a way out of: what `dig` may name.
    pub dig_blocks: Vec<String>,
    /// The rest of them, which a dig would leave the agent trapped by: offered to `place` and
    /// `sleep` only.
    pub kept_blocks: Vec<String>,
    /// Standable blocks outside the agent's own column, and the cells at the edge of sight: what
    /// `moveTo` may name.
    pub walk_targets: Vec<String>,
    /// The cells at the edge of sight, of [`Self::walk_targets`].
    pub explore_targets: Vec<String>,
    /// Shown living things, cut to [`MAX_CREATURE_REFS`], and a sighted threat.
    pub creatures: Vec<String>,
    /// What `flee` may name: of [`Self::creatures`], those that hunt the agent's kind, a sighted
    /// threat, and the creature that just hurt the agent - never a peer or a passive animal (r27:
    /// delta fled a player with no hunter in sight, a flight the readiness gate rightly blocks).
    pub flee_targets: Vec<String>,
    /// Creatures that hunt the agent's kind and are near enough to flee from at a goal review:
    /// in sight within [`DANGER_SIGHT_BLOCKS`], or out of sight within [`DANGER_UNSEEN_BLOCKS`]
    /// (r20: kappa reviewed at health 7.7 beside a zombie and a skeleton, was offered only dirt
    /// digs, and died 28 ticks later).
    pub danger: Vec<String>,
    /// Creatures within attack reach: what an `attack` without approach may name.
    pub strike_targets: Vec<String>,
    /// Creatures within the pursuit bound: what an approaching `attack` may name.
    pub pursuit_targets: Vec<String>,
    /// Dropped items within passive pickup reach: what a `collect` without approach may name.
    pub pickup_items: Vec<String>,
    /// Dropped items within movement reach: what an approaching `collect` may name.
    pub route_items: Vec<String>,
    /// Shown inventory stacks: what `eat`, `equip` and an attack's weapon may name.
    pub stacks: Vec<String>,
    /// Of [`Self::stacks`], the one in the main hand: what `place` may name, since the body places
    /// only the stack already held (R44 theta body:00001886: a held-elsewhere stack, refused).
    pub held_stacks: Vec<String>,
    /// Of [`Self::blocks`], the beds: what `sleep` may name, since the body sleeps only in one
    /// (R46 epsilon and kappa body:00001597, body:00001600: a sleep in dirt, refused as no bed).
    pub beds: Vec<String>,
    /// Shown craftable recipes: what `craft` may name.
    pub recipes: Vec<String>,
    /// The most applications each shown recipe allows, by ref, within the contract's own bound:
    /// a craft may ask no more (r35 alpha asked two of a stick recipe its planks made once, and the
    /// body refused it as `craftQuantityExceeded`).
    pub recipe_crafts: Vec<(String, i64)>,
    /// Every item something in sight could give the agent more of - dropped in sight, craftable,
    /// or yielded by a visible block it may dig - which is what a success condition may name.
    pub obtainable: Vec<String>,
    /// How many more of each item what is in sight can be counted on to give: drops and crafts
    /// exactly, and a block its expected yield, the mean of the server's sampled rolls. The bound a
    /// success count may reach beyond what is held.
    pub capacity: Vec<(String, i64)>,
    /// How many more of each item what is in sight could give at best, every block at its most.
    pub best_case: Vec<(String, i64)>,
    /// The agent's own lying drops a collect can route to, by ref, with the item each holds.
    pub own_drops: Vec<(String, String)>,
    /// Whether the held goal must be collected for: an own drop of its item is in route reach, so
    /// dig is withheld and a step toward the goal is a collect (r16: in 11 of 21 such reviews the
    /// agent dug again or idled beside its own stick, and another agent took it).
    pub collect_required: bool,
    /// Whether the held goal reached the stall bound at this review: it may only be completed if
    /// met, or replaced by a new goal in the same answer.
    pub stalled: bool,
    /// For each item a success condition may name now, the fewest and most of it the count may
    /// ask: two beyond what is held and lying, and what is held plus [`Self::capacity`], never above
    /// [`MAX_SUCCESS_COUNT`]. A count is the total to hold, counting what is held already.
    pub count_bounds: Vec<(String, i64, i64)>,
    /// While a goal is held, the horizon recipe goals made from what the agent already holds that
    /// a replace may switch to before the stall bound, with their count bounds (r23: eta crafted
    /// eight planks from its logs with no goal held, because only a stalled goal could be replaced).
    pub switch_bounds: Vec<(String, i64, i64)>,
    /// The held goal's success item, while a goal review carries a goal that is not met and that
    /// what is in sight could still meet: dig and collect are then offered only for what can give
    /// that item, and a revise may change only its count (r15: digs of blocks that could never
    /// drop the goal's item were taken as its steps).
    pub pursued: Option<String>,
    /// How far what the goal review holds and sees reaches toward the held goal's total.
    pub goal_reach: GoalReach,
    /// The steps each item a goal review may set or pursue can be stepped toward by, in the order
    /// of [`Self::count_bounds`], the held goal's item first when it is pursued: a goal's step is
    /// one of its own item's (r18 epsilon adopted a stick goal and dug terracotta).
    pub item_steps: Vec<ItemSteps>,
    /// The held goal's success item at a goal review, whatever phase it is in.
    pub held_item: Option<String>,
    /// The held goal's success total, which a revise must change (r19: 28 of 31 revises set the
    /// total they were shown, and read as goal activity).
    pub held_total: Option<i64>,
    /// Whether more of the held goal's item serves a concrete chain this perception evidences: a
    /// grounded horizon recipe made from it, or a shelter it would close, as a solid item while night
    /// nears and the agent is not sealed in. Only then is a revise upward offered (R41: 34 of 40
    /// upward revises raised dirt totals with neither, 30 of them sealed in at night, and met goals
    /// kept growing instead of completing).
    pub held_serves_chain: bool,
    /// Where a goal review stands with a discovery goal.
    pub discovery: Discovery,
    /// The dig handles in reach that give the pursued goal's item, or an input on its path, at
    /// least once a dig on the server's sampled mean: a source worth staying for, so no walk to
    /// look further is offered beside one (r24 theta: 13% grass counted as a source on one side).
    pub productive: Vec<String>,
    /// The hunters that are immediate danger - in sight within [`IMMEDIATE_DANGER_BLOCKS`], a ranged
    /// one within [`RANGED_THREAT_BLOCKS`], unseen within [`IMMEDIATE_UNSEEN_BLOCKS`], or any near
    /// hunter when the agent was just hurt: only a survival response is offered while one stands.
    pub immediate: Vec<String>,
    /// The stacks a goal review may eat from before any goal step: every offered stack while its
    /// food is at or below [`HUNGRY_FOOD_LEVEL`], and none otherwise.
    pub meals: Vec<String>,
    /// Where the perception stands with shelter, from the server's ground.
    pub shelter: ShelterOffer,
    /// Where a goal review stands with a shelter goal or its resource subgoal.
    pub sheltering: Sheltering,
}

/// Where a goal review stands with a shelter goal (r36: all ten agents died at night in the open).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Sheltering {
    /// No shelter goal is held.
    #[default]
    Unheld,
    /// The held goal is a shelter goal.
    Held,
    /// The held goal is a resource subgoal that feeds a shelter goal.
    Feeding,
}

/// Where a goal review stands with a discovery goal (r23: 17 of 70 reviews could set no goal at
/// all, and looked further with nothing to measure it by).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Discovery {
    /// No discovery goal may be set: something grounds an item goal, or nowhere is offered to look.
    #[default]
    Unoffered,
    /// One may be set: cells at the edge of sight are offered and no horizon goal is grounded in
    /// what is seen, remembered or held.
    Offered,
    /// The held goal is one.
    Pursued,
}

impl OfferedRefs {
    /// Whether a goal review may set a discovery goal in place of the goal it holds, or none.
    #[must_use]
    pub fn discovery_offered(&self) -> bool {
        self.discovery == Discovery::Offered
    }

    /// Whether the held goal is a discovery goal.
    #[must_use]
    pub fn discovering(&self) -> bool {
        self.discovery == Discovery::Pursued
    }

    /// What a perception's danger and hunger make of its survival offer: just hurt, every near
    /// hunter is immediate; an attack the body would refuse by the time it arrives is not offered;
    /// and while hungry every stack is a meal.
    fn survival(&mut self, perception: &Perception) {
        let state = &perception.self_state;
        let offered = self;
        // Just hurt beside a hunter, or at low health, every near hunter is immediate (r27: theta
        // crafted planks and a crafting table at 2.2 health beside a spider and two zombies).
        if matches!(perception.trigger, Trigger::Damaged { .. }) || state.health <= LOW_HEALTH {
            offered.immediate.clone_from(&offered.danger);
        }
        // An attack the body would refuse by the time it arrives is not offered.
        if state.health <= ATTACK_MIN_HEALTH + ATTACK_HEALTH_MARGIN {
            offered.strike_targets.clear();
            offered.pursuit_targets.clear();
        }
        if perception.trigger.reviews_goal() && state.food <= HUNGRY_FOOD_LEVEL {
            offered.meals.clone_from(&offered.stacks);
        } else if state.health <= LOW_HEALTH && state.food < HEALING_FOOD_LEVEL {
            let food: Vec<&str> = state
                .inventory_summary
                .iter()
                .filter(|stack| stack.edible == Some(true))
                .map(|stack| stack.slot_ref.as_str())
                .collect();
            offered.meals = offered
                .stacks
                .iter()
                .filter(|stack| food.contains(&stack.as_str()))
                .cloned()
                .collect();
        }
        offered.sheltered();
    }

    /// Whether only a survival response may be offered: an immediate hunter stands near.
    #[must_use]
    pub fn in_immediate_danger(&self) -> bool {
        !self.immediate.is_empty()
    }

    /// Whether `intent` is a survival response this review may take with no goal choice: a flight
    /// from or an attack on a hunter near enough to matter, or a meal while hungry.
    #[must_use]
    pub fn survives(&self, intent: &Intent) -> bool {
        match intent {
            Intent::Flee { from } => !self.shelter.trapped() && self.danger.contains(from),
            Intent::Attack { target, .. } => self.danger.contains(target),
            Intent::Eat { slot_ref } => self.meals.contains(slot_ref),
            Intent::Place { .. } | Intent::Equip { .. } => self.shelter.shuts_out(intent),
            _ => false,
        }
    }

    /// What the agent's cover makes of its danger: sealed in, nothing can reach it and no flight
    /// can leave; where no short walk leads out no flight can run, closing the cover is the
    /// response, and with nothing to close it with nothing is due before the agent's own choice.
    /// Mirrors the JVM's `SurvivalOffer.of`.
    fn sheltered(&mut self) {
        if self.shelter.sealed() {
            self.danger.clear();
            self.immediate.clear();
            self.flee_targets.clear();
        } else if self.shelter.trapped() {
            self.flee_targets.clear();
            if self.shelter.closings.is_empty() || self.shelter.solid.is_empty() {
                self.immediate.clear();
            }
        }
    }

    /// The hunters near enough to flee from that an attack in `pursuit` mode or not can reach.
    #[must_use]
    pub fn danger_targets(&self, pursuit: bool) -> Vec<String> {
        let reach = if pursuit {
            &self.pursuit_targets
        } else {
            &self.strike_targets
        };
        self.danger
            .iter()
            .filter(|handle| reach.contains(handle))
            .cloned()
            .collect()
    }
}

/// Every item's whole capacity, uncut, so a held goal is judged by the rule the body uses: in
/// hundredths of an item, and at best. Craftable items lead, ahead of what the surroundings would
/// give. A held item alone is no source: a goal must be more than is held, and something in sight
/// must give the rest (r14 kappa raised a stick goal with no bush or drop in sight).
fn totals_of(
    perception: &Perception,
    sources: Vec<(&String, i64, i64)>,
) -> Vec<(String, i64, i64)> {
    let crafted = perception
        .self_state
        .crafting_options
        .iter()
        .take(MAX_CRAFTING_LINES)
        .map(|option| {
            let count = option.result_count.saturating_mul(option.max_crafts);
            (&option.what, count.saturating_mul(100), count)
        });
    let mut totals: Vec<(String, i64, i64)> = Vec::new();
    for (item, hundredths, best) in crafted.chain(sources) {
        // Only an item name can be held, so anything else in a description is no condition.
        if !is_item_name(item) || item == AIR_ITEM || best < 1 {
            continue;
        }
        if let Some((_, total, most)) = totals.iter_mut().find(|(name, ..)| name == item) {
            *total = total.saturating_add(hundredths);
            *most = most.saturating_add(best);
        } else {
            totals.push((item.clone(), hundredths, best));
        }
    }
    totals
}

/// The agent's own lying drops that `route_items` offers to collect, by ref, with their items.
fn own_drops_of(perception: &Perception, route_items: &[String]) -> Vec<(String, String)> {
    perception
        .self_state
        .own_drops
        .iter()
        .filter(|drop| drop.fate == DropFate::Lying)
        .filter_map(|drop| {
            let handle = drop.drop_ref.as_ref()?;
            route_items
                .contains(handle)
                .then(|| (handle.clone(), drop.what.clone()))
        })
        .collect()
}

/// The total the held goal a goal review carries asks of `item`, if its item is that one.
/// Whether the held goal's item is on `item`'s recipe path: a goal for `item` needs it first.
fn made_from_goal(perception: &Perception, item: &str) -> bool {
    perception
        .goal
        .as_ref()
        .and_then(|goal| goal.success_item.as_ref())
        .is_some_and(|held| path_of(perception, item).contains(held))
}

fn goal_total(perception: &Perception, item: &str) -> Option<i64> {
    let goal = perception.goal.as_ref()?;
    (goal.success_item.as_deref() == Some(item))
        .then_some(goal.success_count)
        .flatten()
}

/// The most of `item` one step `perception` offers could give: a collect of one stack in route
/// reach, a dig in reach at its most, or one craft of every application its recipe allows. An
/// objective asks more (the r39 offline audit: "hold 2 `oak_planks`" beside a held log, met by the
/// craft paired with it). `GoalObservation.oneStep` in the JVM.
#[must_use]
pub fn one_step(perception: &Perception, item: &str) -> i64 {
    let seen = perception.surroundings.iter().take(MAX_OBSERVATIONS);
    let collected = seen
        .clone()
        .filter(|seen| {
            seen.kind == ObservationKind::DroppedItem && seen.route_refused != Some(true)
        })
        .filter(|seen| seen.distance <= MOVE_REACH_BLOCKS && seen.what == item)
        .map(|seen| seen.count.unwrap_or(1));
    let dug = seen
        .filter(|seen| {
            seen.kind == ObservationKind::Block && seen.visible && seen.dig_safe == Some(true)
        })
        .filter(|seen| from_eyes(seen) <= INTERACTION_REACH_BLOCKS)
        .flat_map(|seen| seen.yields.iter().flatten())
        .filter(|given| given.what == item)
        .map(|given| given.most);
    let crafted = perception
        .self_state
        .crafting_options
        .iter()
        .take(MAX_CRAFTING_LINES)
        .filter(|option| option.what == item)
        .map(|option| option.result_count.saturating_mul(option.max_crafts));
    collected.chain(dug).chain(crafted).max().unwrap_or(0)
}

/// The fewest of `item` an objective may ask: [`MIN_OBJECTIVE_ADVANCE`] beyond what is held and the
/// agent's own drops of it lying, and more than what is held plus the most one offered step could
/// give. `GoalObservation.fewestObjective` in the JVM.
#[must_use]
pub fn fewest_objective(perception: &Perception, item: &str) -> i64 {
    held_of(perception, item).saturating_add(
        own_lying(perception, item)
            .saturating_add(MIN_OBJECTIVE_ADVANCE)
            .max(one_step(perception, item).saturating_add(1)),
    )
}

/// How many of `item` the agent's own drops have lying, wherever they lie.
#[must_use]
pub fn own_lying(perception: &Perception, item: &str) -> i64 {
    perception
        .self_state
        .own_drops
        .iter()
        .filter(|drop| drop.fate == DropFate::Lying && drop.what == item)
        .map(|drop| drop.count)
        .sum()
}

/// How many of `item` the agent's whole inventory holds, as the body counts a success condition.
#[must_use]
pub fn held_of(perception: &Perception, item: &str) -> i64 {
    perception
        .self_state
        .inventory_summary
        .iter()
        .filter(|stack| stack.what == item)
        .map(|stack| stack.count)
        .sum()
}

/// How many of `item` a hundred digs of the observed block `handle` drop on average, or 0.
fn mean_yield(perception: &Perception, handle: &str, item: &str) -> i64 {
    perception
        .surroundings
        .iter()
        .find(|seen| seen.target_ref == handle)
        .and_then(|seen| seen.yields.as_ref())
        .and_then(|yields| yields.iter().find(|given| given.what == item))
        .map_or(0, |given| given.per_hundred)
}

/// The sources recalled out of sight, each with what the body expects of it in hundredths and at
/// best, so a goal for one can be set and walked toward (r20).
fn recalled_sources(perception: &Perception) -> impl Iterator<Item = (&String, i64, i64)> {
    perception
        .recalled
        .iter()
        .take(loom_contract::MAX_RECALLED_SOURCES)
        .map(|source| {
            (
                &source.what,
                source.per_hundred,
                (source.per_hundred + 99) / 100,
            )
        })
}

/// The items `item`'s horizon recipe path is made from, nearest first: its `via`, then that one's.
fn path_of(perception: &Perception, item: &str) -> Vec<String> {
    let via_of = |name: &str| {
        perception
            .horizon
            .iter()
            .find(|goal| goal.what == name)
            .and_then(|goal| goal.via.clone())
    };
    let mut path = Vec::new();
    let mut next = via_of(item);
    while let Some(input) = next {
        if path.len() >= 2 || path.contains(&input) {
            break;
        }
        next = via_of(&input);
        path.push(input);
    }
    path
}

/// Whether a goal of `item` would crowd out looking further: cells at the edge of sight are
/// offered, and no horizon goal is grounded in `item` or made from it (r22: every goal was dirt
/// from underfoot, and no agent looked for wood). An item a craft offered now makes never crowds.
/// The JVM's `crowdsExploration`.
fn crowds_exploration(perception: &Perception, item: &str) -> bool {
    !perception.explore.is_empty()
        && !perception
            .horizon
            .iter()
            .any(|goal| goal.what == item || goal.via.as_deref() == Some(item))
        // A craft offered now is a recipe step, never sight handing an item over whole.
        && !perception
            .self_state
            .crafting_options
            .iter()
            .any(|option| option.what == item)
}

/// Appends `handle` unless the list already holds it.
fn offer(list: &mut Vec<String>, handle: &str) {
    if !list.iter().any(|held| held == handle) {
        list.push(handle.to_owned());
    }
}

/// The distance from the agent's eyes to the observed point, from its feet-relative reading.
/// Whether `seen` lies beyond the body's interaction reach of the eyes.
#[must_use]
pub fn beyond_dig_reach(seen: &Observation) -> bool {
    from_eyes(seen) > INTERACTION_REACH_BLOCKS
}

fn from_eyes(seen: &Observation) -> f64 {
    let horizontal = (seen.distance.powi(2) - seen.vertical_offset.powi(2)).max(0.0);
    (horizontal + (seen.vertical_offset - EYE_HEIGHT_BLOCKS).powi(2)).sqrt()
}

impl OfferedRefs {
    /// The handles `perception` offers.
    #[must_use]
    pub fn of(perception: &Perception) -> Self {
        let mut offered = Self::default();
        // Each source of an item with what it gives in hundredths of an item - a block the mean of
        // the server's sampled rolls, and a drop or a craft exactly - and at best (r19: leaves
        // counted at the middle of their range as about a stick each gave one in 33 digs).
        let mut sources: Vec<(&String, i64, i64)> = Vec::new();
        // What each dig and collect handle can give, for a held goal's restriction below.
        let mut gives: Vec<(&str, &String)> = Vec::new();
        for seen in perception.surroundings.iter().take(MAX_OBSERVATIONS) {
            let handle = seen.target_ref.as_str();
            match seen.kind {
                ObservationKind::Block => {
                    let dig_safe = seen.dig_safe == Some(true);
                    if seen.visible && from_eyes(seen) <= INTERACTION_REACH_BLOCKS {
                        offer(&mut offered.blocks, handle);
                        if seen.what.ends_with("_bed") {
                            offer(&mut offered.beds, handle);
                        }
                        if dig_safe {
                            offer(&mut offered.dig_blocks, handle);
                        } else {
                            offer(&mut offered.kept_blocks, handle);
                        }
                    }
                    if seen.visible && dig_safe {
                        for item in seen.yields.iter().flatten() {
                            sources.push((&item.what, item.per_hundred, item.most));
                            gives.push((handle, &item.what));
                        }
                    }
                    if seen.direction != Bearing::Here && seen.standable == Some(true) {
                        offer(&mut offered.walk_targets, handle);
                    }
                }
                ObservationKind::Entity => offered.sighted(seen),
                // A drop the body already found no route to is neither offered nor counted on
                // (r15: five collects were refused as noRoute while their items stood in capacity).
                ObservationKind::DroppedItem if seen.route_refused != Some(true) => {
                    if within_pickup(seen) {
                        offer(&mut offered.pickup_items, handle);
                    }
                    if seen.distance <= MOVE_REACH_BLOCKS {
                        offer(&mut offered.route_items, handle);
                        let count = seen.count.unwrap_or(1);
                        sources.push((&seen.what, count.saturating_mul(100), count));
                        gives.push((handle, &seen.what));
                    }
                }
                ObservationKind::DroppedItem => {}
            }
        }
        offered.named_by_trigger(perception);
        // Cells at the edge of sight are walks like any other, offered so an agent that sees nothing
        // worth doing can look further (r17: eta idled three reviews with a bush 5.4 blocks off).
        for target in perception
            .explore
            .iter()
            .take(loom_contract::MAX_EXPLORE_TARGETS)
        {
            offer(&mut offered.walk_targets, &target.target_ref);
            offer(&mut offered.explore_targets, &target.target_ref);
        }
        if perception.trigger.reviews_goal()
            && perception.horizon.is_empty()
            && !offered.explore_targets.is_empty()
        {
            offered.discovery = Discovery::Offered;
        }
        sources.extend(recalled_sources(perception));
        let state = &perception.self_state;
        offered.offer_stacks(&state.inventory_summary);
        for option in state.crafting_options.iter().take(MAX_CRAFTING_LINES) {
            offer(&mut offered.recipes, &option.recipe_ref);
            offered.recipe_crafts.push((
                option.recipe_ref.clone(),
                option
                    .max_crafts
                    .clamp(1, loom_contract::MAX_CRAFTS_PER_INTENT),
            ));
        }
        offered.shelter = offered.shelter_of(perception);
        offered.survival(perception);
        let totals = totals_of(perception, sources);
        // What sight can be counted on for is the expected yield, whole items only: a block's best
        // case is luck (r16: four goals sized to every bush's most, three of them failed).
        let calibrated: Vec<(String, i64)> = totals
            .iter()
            .map(|(item, hundredths, _)| (item.clone(), hundredths / 100))
            .collect();
        offered.own_drops = own_drops_of(perception, &offered.route_items);
        offered.pursue(perception, &calibrated, &gives);
        offered.best_case = totals
            .into_iter()
            .take(MAX_OBTAINABLE_ITEMS)
            .map(|(item, _, best)| (item, best))
            .collect();
        offered.capacity = calibrated
            .into_iter()
            .filter(|(_, count)| *count >= 1)
            .take(MAX_OBTAINABLE_ITEMS)
            .collect();
        offered.count_bounds = offered.count_bounds_for(perception);
        offered.switch_bounds = offered.switch_bounds_for(perception);
        offered.obtainable = offered
            .count_bounds
            .iter()
            .map(|(item, ..)| item.clone())
            .collect();
        offered.item_steps = offered.item_steps_for(perception, &gives);
        offered.descent();
        offered
    }

    /// Offers the shelter's descent to `dig`: the block underfoot, which no other goal digs.
    fn descent(&mut self) {
        if let Some(descend) = self.shelter.descend.clone() {
            self.kept_blocks.retain(|kept| *kept != descend);
            offer(&mut self.dig_blocks, &descend);
        }
    }

    /// The steps of each item a goal review may set or pursue: its own dig, collect, craft and
    /// walk refs, each at most [`MAX_STEP_REFS_PER_ITEM`] in all. While the agent's own drop of the
    /// held goal's item lies in reach, that item's steps are its collects alone.
    fn item_steps_for(&self, perception: &Perception, gives: &[(&str, &String)]) -> Vec<ItemSteps> {
        if !perception.trigger.reviews_goal() {
            return Vec::new();
        }
        let gives_item = |handle: &String, item: &str| {
            gives
                .iter()
                .any(|(given, what)| given == handle && *what == item)
        };
        let mut items: Vec<&String> = self.pursued.iter().collect();
        for (item, ..) in self.count_bounds.iter().chain(&self.switch_bounds) {
            if !items.contains(&item) {
                items.push(item);
            }
        }
        items
            .into_iter()
            .map(|item| {
                let mut budget = MAX_STEP_REFS_PER_ITEM;
                let mut take = |list: &[String], keep: &dyn Fn(&String) -> bool| {
                    let kept: Vec<String> = list
                        .iter()
                        .filter(|handle| keep(handle))
                        .take(budget)
                        .cloned()
                        .collect();
                    budget -= kept.len();
                    kept
                };
                let collecting = self.collect_required && self.pursued.as_ref() == Some(item);
                // A horizon goal made by a recipe is stepped toward through what it is made from.
                let path = path_of(perception, item);
                let horizon = perception.horizon.iter().any(|goal| &goal.what == item);
                let source = |handle: &String| {
                    gives_item(handle, item) || path.iter().any(|input| gives_item(handle, input))
                };
                let none = |_: &String| false;
                // A craft that makes the item outright comes first, then its drops, then the blocks
                // whose sampled rolls give most of it, so the budget is never spent on rare drops
                // while a recipe or a sure source waits (r19: a craftable stick recipe was crowded
                // out behind four leaves eight times).
                let recipe = |handle: &String| {
                    !collecting
                        && perception.self_state.crafting_options.iter().any(|option| {
                            &option.recipe_ref == handle
                                && (&option.what == item || path.contains(&option.what))
                        })
                };
                let recipes = take(&self.recipes, &recipe);
                let pickup = take(&self.pickup_items, &source);
                let route = take(&self.route_items, &source);
                let mut by_yield = self.dig_blocks.clone();
                by_yield
                    .sort_by_key(|handle| std::cmp::Reverse(mean_yield(perception, handle, item)));
                let dig = take(&by_yield, if collecting { &none } else { &source });
                let walk = |handle: &String| {
                    !collecting
                        && !self.blocks.contains(handle)
                        && !self.explore_targets.contains(handle)
                        && source(handle)
                };
                let walks = take(&self.walk_targets, &walk);
                // Toward where a source of it, or of what it is made from, was last seen, or is seen
                // beyond dig reach (r34: zeta's logs lay in view and out of reach, and only walks
                // past them were offered); a horizon goal with none remembered may look further
                // every way, as its total reaches past what sight gives.
                let remembered = self.source_bearings(perception, item, &path, &source);
                // Nor, when no cell is offered the way it was remembered, may it be left with no step:
                // it looks further every way, as the body accepts (v32 plains rehearsal 17: two met
                // goals' next logs lay where no cell was offered, and their review could not be answered).
                let offered_toward = perception.explore.iter().any(|cell| {
                    self.explore_targets.contains(&cell.target_ref)
                        && remembered.contains(&cell.direction)
                });
                let recalled_toward = |handle: &String| {
                    !collecting
                        && perception.explore.iter().any(|cell| {
                            &cell.target_ref == handle
                                && (remembered.contains(&cell.direction)
                                    || (horizon && !offered_toward))
                        })
                };
                // No walk to look further while a source can already be stepped to (r23: epsilon
                // walked away from two logs in dig reach).
                let reachable = !(dig.is_empty()
                    && pickup.is_empty()
                    && route.is_empty()
                    && recipes.is_empty());
                let toward = if reachable {
                    Vec::new()
                } else {
                    take(&self.explore_targets, &recalled_toward)
                };
                ItemSteps {
                    item: item.clone(),
                    dig,
                    pickup,
                    route,
                    recipes,
                    walks,
                    toward,
                }
            })
            .collect()
    }

    /// The bearings of the sources of `item`, or of an input on its `path`, recalled out of sight or
    /// in sight beyond dig reach: the directions a walk to the edge of sight steps toward it.
    fn source_bearings(
        &self,
        perception: &Perception,
        item: &String,
        path: &[String],
        source: &dyn Fn(&String) -> bool,
    ) -> Vec<Bearing> {
        perception
            .recalled
            .iter()
            .filter(|recalled| &recalled.what == item || path.contains(&recalled.what))
            .map(|recalled| recalled.direction)
            .chain(
                perception
                    .surroundings
                    .iter()
                    .take(MAX_OBSERVATIONS)
                    .filter(|seen| {
                        seen.kind == ObservationKind::Block
                            && !self.blocks.contains(&seen.target_ref)
                            && source(&seen.target_ref)
                    })
                    .map(|seen| seen.direction),
            )
            .collect()
    }

    /// The shelter the cover reading offers, its gathering steps narrowed to those this perception
    /// offers: a dig that leaves a way out, or a collect.
    fn shelter_of(&self, perception: &Perception) -> ShelterOffer {
        let mut shelter = ShelterOffer::of(perception);
        shelter.gathers.retain(|handle| {
            self.dig_blocks.contains(handle)
                || self.pickup_items.contains(handle)
                || self.route_items.contains(handle)
        });
        shelter
    }

    /// For each item [`Self::capacity`] or the perception's horizon names, the fewest and most a
    /// success count may ask now: two beyond what is held and lying, up to what is held plus
    /// capacity or the horizon's most, whichever is more. While cells at the edge of sight are
    /// offered, an item no horizon goal is grounded in or made from is not offered at all.
    fn count_bounds_for(&self, perception: &Perception) -> Vec<(String, i64, i64)> {
        let horizon_most = |item: &str| {
            perception
                .horizon
                .iter()
                .find(|goal| goal.what == item)
                .map_or(0, |goal| goal.most)
        };
        let mut items: Vec<(String, i64)> = self.capacity.clone();
        for goal in perception
            .horizon
            .iter()
            .take(loom_contract::MAX_HORIZON_GOALS)
        {
            if !items.iter().any(|(item, _)| *item == goal.what) {
                items.push((goal.what.clone(), 0));
            }
        }
        items
            .iter()
            .filter(|(item, _)| self.pursued.as_ref().is_none_or(|goal| goal == item))
            .filter(|(item, _)| self.pursued.is_some() || !crowds_exploration(perception, item))
            .filter_map(|(item, capacity)| {
                let held = held_of(perception, item);
                // A replace of a stalled goal may not set the same item again, at any total, which
                // would only relabel it (r17 alpha body:00000163; r18: three replaces at one more),
                // nor an item made from it, which still needs the source the goal never reached
                // (r34: zeta's stalled oak log goal became oak buttons and advanced nothing more).
                if self.stalled
                    && (goal_total(perception, item).is_some() || made_from_goal(perception, item))
                {
                    return None;
                }
                // An objective asks more than one step could give: two beyond what is held and the
                // agent's own drops of it lying (r39: alpha replaced "hold 9 dirt" by "hold 10 dirt"
                // at every step, and no held time ever counted), and more than the most one offered
                // step gives (the r39 offline audit: a planks goal one paired craft met).
                let fewest = fewest_objective(perception, item);
                let most = held
                    .saturating_add(*capacity)
                    .max(horizon_most(item))
                    .min(MAX_SUCCESS_COUNT);
                (fewest <= most).then(|| (item.clone(), fewest, most))
            })
            .take(MAX_OBTAINABLE_ITEMS)
            .collect()
    }

    /// The recipe goals of the horizon made from an item held now, while an unmet goal of another
    /// item is held: the switch a replace may make before the stall bound.
    fn switch_bounds_for(&self, perception: &Perception) -> Vec<(String, i64, i64)> {
        let Some(pursued) = &self.pursued else {
            return Vec::new();
        };
        perception
            .horizon
            .iter()
            .take(loom_contract::MAX_HORIZON_GOALS)
            .filter(|goal| {
                goal.grounding == loom_contract::HorizonGrounding::Recipe
                    && &goal.what != pursued
                    && !(self.stalled && made_from_goal(perception, &goal.what))
            })
            .filter(|goal| {
                path_of(perception, &goal.what)
                    .iter()
                    .any(|input| held_of(perception, input) > 0)
            })
            .filter_map(|goal| {
                let fewest = fewest_objective(perception, &goal.what);
                (fewest <= goal.most).then(|| (goal.what.clone(), fewest, goal.most))
            })
            .collect()
    }

    /// Judges the held goal a goal review carries, and while it is unmet and still obtainable
    /// narrows dig and collect to the handles that can give its item.
    fn pursue(
        &mut self,
        perception: &Perception,
        totals: &[(String, i64)],
        gives: &[(&str, &String)],
    ) {
        if !perception.trigger.reviews_goal() {
            return;
        }
        let Some(goal) = &perception.goal else {
            return;
        };
        let at_bound = goal.progress.as_ref().is_some_and(|seen| {
            seen.steps_without_progress >= loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS
        });
        let met = goal
            .progress
            .as_ref()
            .is_some_and(|seen| seen.condition_met);
        if goal.feeds.is_some() {
            self.sheltering = Sheltering::Feeding;
        }
        // A shelter goal names no item: it is stepped by what its cover reading offers, and stalls
        // like any other goal.
        if goal.shelter {
            self.sheltering = Sheltering::Held;
            self.stalled = at_bound && !met;
            return;
        }
        // A discovery goal names no item: it is stepped by a walk to the edge of sight until one
        // reveals a source, and stalls like any other goal.
        if goal.discovery {
            self.discovery = Discovery::Pursued;
            self.stalled = at_bound
                && !goal
                    .progress
                    .as_ref()
                    .is_some_and(|seen| seen.condition_met);
            return;
        }
        let (Some(item), Some(count)) = (&goal.success_item, goal.success_count) else {
            return;
        };
        self.held_item = Some(item.clone());
        self.held_total = Some(count);
        self.held_serves_chain = serves_chain(perception, &self.shelter, item, self.sheltering);
        let held = held_of(perception, item);
        let capacity = totals
            .iter()
            .find(|(name, _)| name == item)
            .map_or(0, |(_, total)| *total);
        let horizon_most = perception
            .horizon
            .iter()
            .find(|goal| &goal.what == item)
            .map_or(0, |goal| goal.most);
        self.goal_reach = if count <= held.saturating_add(capacity) {
            GoalReach::Sight
        } else if count <= horizon_most {
            GoalReach::Horizon
        } else {
            GoalReach::Beyond
        };
        if held >= count {
            return;
        }
        // At the bound the goal can only be replaced, by a goal of any item sight offers.
        if at_bound {
            self.stalled = true;
            return;
        }
        // Whether or not sight can meet all of it, a goal is stepped only toward its own item, and
        // its own lying drop is collected first (the path rehearsal: a goal one log short could only
        // walk away from its own log).
        let path = path_of(perception, item);
        let gives_goal = |handle: &String| {
            gives.iter().any(|(given, what)| {
                given == handle && (*what == item || path.iter().any(|input| input == *what))
            })
        };
        self.dig_blocks.retain(gives_goal);
        for seen in perception.surroundings.iter().take(MAX_OBSERVATIONS) {
            let yields_well = seen.yields.iter().flatten().any(|given| {
                given.per_hundred >= 100 && (given.what == *item || path.contains(&given.what))
            });
            if yields_well && self.dig_blocks.contains(&seen.target_ref) {
                offer(&mut self.productive, &seen.target_ref);
            }
        }
        self.pickup_items.retain(gives_goal);
        self.route_items.retain(gives_goal);
        self.pursued = Some(item.clone());
        if self.own_drops.iter().any(|(_, what)| what == item) {
            self.collect_required = true;
            self.dig_blocks.clear();
        }
    }

    /// Whether a held, unmet goal sight cannot meet may be continued by a walk to the edge of
    /// sight, to look for a source rather than abandon it (r18: five goals abandoned to look).
    #[must_use]
    pub fn explore_continue(&self) -> bool {
        (self.held_item.is_some() || self.discovering())
            && !self.goal_obtainable()
            && !self.stalled
            && !self.collect_required
            && self.productive.is_empty()
            && !self.explore_targets.is_empty()
    }

    /// Whether the held goal could still be met from what is in sight - its count no more than what
    /// is held plus what sight could give. A continue toward a source is offered only then, by the
    /// same rule the body holds an adopt and a revise to.
    #[must_use]
    pub fn goal_obtainable(&self) -> bool {
        self.goal_reach == GoalReach::Sight
    }

    /// Whether the held goal could still be met at all: from sight, or up to its horizon's most.
    /// Only a goal that cannot is offered its abandon as impossible (the r39 offline audit: every
    /// goal asking past sight was "impossible" from its adoption, and three were abandoned beside
    /// the logs and recipe that would have met them).
    #[must_use]
    pub fn goal_reachable(&self) -> bool {
        self.goal_reach != GoalReach::Beyond
    }

    /// Whether a goal review may answer with an idle or a rest: only when no cell at the edge of
    /// sight is offered to look further from (r18: nine scheduled reviews idled beside them).
    #[must_use]
    pub fn passive_allowed(&self) -> bool {
        self.explore_targets.is_empty()
    }

    /// How many more of `item` what is in sight could give at most.
    #[must_use]
    pub fn capacity_of(&self, item: &str) -> i64 {
        self.capacity
            .iter()
            .find(|(name, _)| name == item)
            .map_or(0, |(_, count)| *count)
    }

    /// The fewest and most of `item` a success count may ask now, if it may name it at all.
    #[must_use]
    pub fn count_bounds_of(&self, item: &str) -> Option<(i64, i64)> {
        self.count_bounds
            .iter()
            .chain(&self.switch_bounds)
            .find(|(name, ..)| name == item)
            .map(|(_, fewest, most)| (*fewest, *most))
    }

    /// Offers one creature in sight: to a goal review's flight when it hunts the agent's kind and is
    /// near ([`Self::danger`]), and to flight and attack while [`MAX_CREATURE_REFS`] allow - always,
    /// for a hunter offered to flee from.
    fn sighted(&mut self, seen: &Observation) {
        let near = if seen.visible {
            DANGER_SIGHT_BLOCKS
        } else {
            DANGER_UNSEEN_BLOCKS
        };
        let danger = seen.hunts == Some(true)
            && seen.distance <= near
            && self.danger.len() < MAX_DANGER_REFS;
        if danger {
            offer(&mut self.danger, &seen.target_ref);
            let ranged = seen.visible && RANGED_HUNTERS.contains(&seen.what.as_str());
            let reach = match (seen.visible, ranged) {
                (true, true) => RANGED_THREAT_BLOCKS,
                (true, false) => IMMEDIATE_DANGER_BLOCKS,
                (false, _) => IMMEDIATE_UNSEEN_BLOCKS,
            };
            if seen.distance <= reach {
                offer(&mut self.immediate, &seen.target_ref);
            }
        }
        if danger || self.creatures.len() < MAX_CREATURE_REFS {
            self.creature(&seen.target_ref, seen.distance, seen.hunts == Some(true));
        }
    }

    /// Offers the creatures `perception`'s trigger names: a sighted threat, whose handle travels
    /// with the trigger, and the creature that just hurt the agent, which is danger and is offered
    /// to flee and to attack within reach whatever lies nearer (v32 plains rehearsal 15: a skeleton
    /// shooting from past the sixteen nearest creatures could not be named).
    fn named_by_trigger(&mut self, perception: &Perception) {
        match &perception.trigger {
            Trigger::Damaged {
                attacker_ref: Some(attacker),
                ..
            } => {
                if let Some(seen) =
                    perception
                        .surroundings
                        .iter()
                        .take(MAX_OBSERVATIONS)
                        .find(|seen| {
                            seen.kind == ObservationKind::Entity && &seen.target_ref == attacker
                        })
                {
                    offer(&mut self.danger, attacker);
                    self.creature(attacker, seen.distance, true);
                }
            }
            Trigger::ThreatSighted {
                entity_ref,
                distance,
                ..
            } => self.creature(entity_ref, *distance, true),
            _ => {}
        }
    }

    /// Offers each shown stack, and the one in the main hand to `place` as well.
    fn offer_stacks(&mut self, stacks: &[InventoryStack]) {
        for stack in stacks.iter().take(MAX_INVENTORY_LINES) {
            offer(&mut self.stacks, &stack.slot_ref);
            if stack.held == Some(true) {
                offer(&mut self.held_stacks, &stack.slot_ref);
            }
        }
    }

    /// Offers one creature to each attack mode whose bound it is within, and to flight when it is
    /// one to `flee` from.
    fn creature(&mut self, handle: &str, distance: f64, flee: bool) {
        offer(&mut self.creatures, handle);
        if flee {
            offer(&mut self.flee_targets, handle);
        }
        if distance <= ATTACK_REACH_BLOCKS {
            offer(&mut self.strike_targets, handle);
        }
        if distance <= MAX_PURSUIT_BLOCKS {
            offer(&mut self.pursuit_targets, handle);
        }
    }

    /// The most applications the shown recipe `recipe` allows; none for one not shown.
    #[must_use]
    pub fn crafts_of(&self, recipe: &str) -> i64 {
        self.recipe_crafts
            .iter()
            .find(|(offered, _)| offered == recipe)
            .map_or(0, |(_, most)| *most)
    }

    /// Whether every ref `intent` names is offered to that action in that mode, and a craft asks
    /// no more applications than its recipe allows.
    #[must_use]
    pub fn admits(&self, intent: &Intent) -> bool {
        let held = |list: &[String], handle: &str| list.iter().any(|offered| offered == handle);
        match intent {
            Intent::MoveTo { onto } => held(&self.walk_targets, onto),
            Intent::Flee { from } => held(&self.flee_targets, from),
            Intent::Eat { slot_ref } => held(&self.stacks, slot_ref),
            Intent::Dig { block } => held(&self.dig_blocks, block),
            Intent::Collect { item, approach } => held(
                if *approach {
                    &self.route_items
                } else {
                    &self.pickup_items
                },
                item,
            ),
            Intent::Place { block, item, .. } => {
                held(&self.blocks, block) && held(&self.held_stacks, item)
            }
            Intent::Equip { item, .. } => held(&self.stacks, item),
            Intent::Craft { recipe, crafts } => {
                held(&self.recipes, recipe) && *crafts <= self.crafts_of(recipe)
            }
            Intent::Attack {
                target,
                weapon,
                approach,
                ..
            } => {
                let targets = if *approach {
                    &self.pursuit_targets
                } else {
                    &self.strike_targets
                };
                held(targets, target) && held(&self.stacks, weapon)
            }
            Intent::Sleep { bed } => held(&self.beds, bed),
            Intent::Say { .. } | Intent::Rest | Intent::Idle => true,
        }
    }
}

/// Whether more of `item` serves a concrete chain `perception` evidences: a grounded horizon goal
/// whose recipe is made from it, a shelter subgoal it feeds, or a shelter it would close, as an item
/// that places solid while night nears and the agent is not sealed in (`held_serves_chain`).
fn serves_chain(
    perception: &Perception,
    shelter: &ShelterOffer,
    item: &str,
    sheltering: Sheltering,
) -> bool {
    let crafted = perception
        .horizon
        .iter()
        .any(|goal| goal.what != item && goal.via.as_deref() == Some(item));
    let solid = shelter.block_items.iter().any(|named| named == item)
        || perception
            .self_state
            .inventory_summary
            .iter()
            .any(|stack| stack.what == item && stack.solid == Some(true));
    let closes =
        shelter.night_near && shelter.standing != Standing::Unread && !shelter.sealed() && solid;
    crafted || closes || sheltering == Sheltering::Feeding
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{OfferedRefs, ATTACK_REACH_BLOCKS, INTERACTION_REACH_BLOCKS, MAX_PURSUIT_BLOCKS};
    use crate::prompt::{MAX_CRAFTING_LINES, MAX_INVENTORY_LINES};
    use loom_contract::{
        Bearing, BlockFace, CraftingOption, EquipmentDestination, Intent, InventoryStack,
        Observation, ObservationKind, Perception,
    };

    fn fixture(name: &str) -> Perception {
        let path = format!(
            "{}/../../../contract/fixtures/valid/{name}.json",
            env!("CARGO_MANIFEST_DIR")
        );
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    /// A block yield of nought to two of `item`, as a dead bush's sticks are.
    fn yielding(item: &str) -> loom_contract::BlockYield {
        loom_contract::BlockYield {
            what: item.to_owned(),
            least: 0,
            most: 2,
            per_hundred: 100,
        }
    }

    fn handles(list: &[&str]) -> Vec<String> {
        list.iter().map(|&handle| handle.to_owned()).collect()
    }

    fn seen(kind: ObservationKind, handle: &str, distance: f64, vertical: f64) -> Observation {
        Observation {
            kind,
            what: "something".to_owned(),
            distance,
            direction: Bearing::North,
            vertical_offset: vertical,
            visible: true,
            target_ref: handle.to_owned(),
            count: (kind == ObservationKind::DroppedItem).then_some(1),
            standable: (kind == ObservationKind::Block).then_some(true),
            yields: (kind == ObservationKind::Block).then(Vec::new),
            dig_safe: (kind == ObservationKind::Block).then_some(true),
            route_refused: None,
            hunts: None,
            cover: None,
        }
    }

    /// The threat fixture, with `surroundings` and one stack and one recipe to spend.
    fn scene(surroundings: Vec<Observation>) -> Perception {
        let mut perception = fixture("perception-threat-sighted");
        perception.surroundings = surroundings;
        perception
            .self_state
            .inventory_summary
            .push(InventoryStack {
                slot_ref: "i1".to_owned(),
                what: "item.minecraft.stone_sword".to_owned(),
                count: 1,
                edible: None,
                solid: None,
                held: Some(true),
            });
        perception.self_state.crafting_options.push(CraftingOption {
            recipe_ref: "r1".to_owned(),
            what: "item.minecraft.stick".to_owned(),
            result_count: 4,
            max_crafts: 1,
            requires_table: false,
        });
        perception
    }

    #[test]
    fn each_kind_of_thing_is_offered_only_to_its_own_namespace() {
        let offered = OfferedRefs::of(&fixture("perception-threat-sighted"));
        assert_eq!(offered.creatures, handles(&["e1", "e2"]));
        // The oak log is nine blocks off: a walk target, but out of reach to dig.
        assert!(offered.blocks.is_empty());
        assert_eq!(offered.walk_targets, handles(&["b1"]));
        assert!(offered.route_items.is_empty() && offered.pickup_items.is_empty());
        assert!(offered.stacks.is_empty());
        assert!(offered.recipes.is_empty());

        let crafting = OfferedRefs::of(&fixture("perception-crafting"));
        assert_eq!(crafting.stacks, handles(&["i1"]));
        assert_eq!(crafting.recipes, handles(&["r1"]));
    }

    #[test]
    fn a_cell_at_the_edge_of_sight_is_a_walk_and_nothing_else() {
        let offered = OfferedRefs::of(&fixture("perception-explore"));
        assert_eq!(offered.walk_targets, handles(&["x1", "x2"]));
        assert_eq!(offered.explore_targets, handles(&["x1", "x2"]));
        assert!(offered.blocks.is_empty());
        assert!(offered.admits(&Intent::MoveTo {
            onto: "x1".to_owned()
        }));
        assert!(!offered.admits(&Intent::Dig {
            block: "x1".to_owned()
        }));
    }

    #[test]
    fn an_own_column_block_is_dug_or_placed_against_but_never_walked_to() {
        let offered = OfferedRefs::of(&fixture("perception-standing-on"));
        assert_eq!(offered.blocks, handles(&["b1", "b2", "b3"]));
        assert_eq!(offered.walk_targets, handles(&["b3"]));
        for here in ["b1", "b2"] {
            assert!(offered.admits(&Intent::Dig {
                block: here.to_owned()
            }));
            assert!(!offered.admits(&Intent::MoveTo {
                onto: here.to_owned()
            }));
        }
    }

    #[test]
    fn a_covered_block_is_no_walk_target_and_an_unseen_or_distant_one_is_not_dug() {
        let mut covered = seen(ObservationKind::Block, "covered", 2.06, -1.5);
        covered.standable = Some(false);
        let mut hidden = seen(ObservationKind::Block, "hidden", 2.0, 0.0);
        hidden.visible = false;
        // Five blocks off on the level is 5.25 from the eyes, past the 5.1 reach.
        let far = seen(ObservationKind::Block, "far", 5.0, 0.0);
        let near = seen(ObservationKind::Block, "near", 4.9, 0.5);
        let offered = OfferedRefs::of(&scene(vec![covered, hidden, far, near]));
        // r10's covered terracotta can still be dug, but a walk onto it ends nowhere.
        assert_eq!(offered.blocks, handles(&["covered", "near"]));
        assert_eq!(offered.walk_targets, handles(&["hidden", "far", "near"]));
        assert!(INTERACTION_REACH_BLOCKS < (5.0_f64.powi(2) + 1.62_f64.powi(2)).sqrt());
    }

    #[test]
    fn an_item_or_creature_is_offered_to_each_mode_whose_reach_it_is_within() {
        let offered = OfferedRefs::of(&scene(vec![
            seen(ObservationKind::DroppedItem, "close", 1.2, -0.5),
            // The v25 rehearsal's theta: 1.9 away but a block below the feet, out of pickup reach.
            seen(ObservationKind::DroppedItem, "below", 1.93, -1.0),
            // r10's stick, 18 blocks off: collected only by walking to it.
            seen(ObservationKind::DroppedItem, "stick", 18.17, -2.0),
            seen(ObservationKind::Entity, "beside", ATTACK_REACH_BLOCKS, 0.0),
            seen(ObservationKind::Entity, "chase", MAX_PURSUIT_BLOCKS, 0.0),
            seen(ObservationKind::Entity, "afar", 12.0, 0.0),
        ]));
        assert_eq!(offered.pickup_items, handles(&["close"]));
        assert_eq!(offered.route_items, handles(&["close", "below", "stick"]));
        let collect = |item: &str, approach| Intent::Collect {
            item: item.to_owned(),
            approach,
        };
        assert!(!offered.admits(&collect("stick", false)));
        assert!(offered.admits(&collect("stick", true)));
        assert!(offered.admits(&collect("close", false)));
        assert_eq!(offered.strike_targets, handles(&["beside"]));
        // The fixture's own sighted threat travels with the trigger, 6 blocks off.
        assert_eq!(offered.pursuit_targets, handles(&["beside", "chase", "e1"]));
        assert_eq!(
            offered.creatures,
            handles(&["beside", "chase", "afar", "e1"])
        );
        let attack = |target: &str, approach| Intent::Attack {
            target: target.to_owned(),
            weapon: "i1".to_owned(),
            attacks: 1,
            approach,
        };
        assert!(offered.admits(&attack("beside", false)));
        assert!(!offered.admits(&attack("chase", false)));
        assert!(offered.admits(&attack("chase", true)));
        assert!(!offered.admits(&attack("afar", true)));
        // A creature that hunts nothing is no flight target, however near (r27: delta's peer).
        assert!(!offered.admits(&Intent::Flee {
            from: "afar".to_owned()
        }));
        assert_eq!(
            offered.flee_targets,
            handles(&["e1"]),
            "the sighted threat alone"
        );
    }

    #[test]
    fn a_sighted_threat_is_a_creature_even_when_the_scene_was_cut_before_it() {
        let mut seen = fixture("perception-threat-sighted");
        seen.surroundings.clear();
        let offered = OfferedRefs::of(&seen);
        assert_eq!(offered.creatures.len(), 1);
        assert!(offered.admits(&Intent::Flee {
            from: offered.creatures[0].clone()
        }));
    }

    #[test]
    fn a_handle_of_the_wrong_kind_or_never_offered_is_refused_for_every_action() {
        let mut bed = seen(ObservationKind::Block, "bed1", 2.0, -0.5);
        bed.what = "block.minecraft.red_bed".to_owned();
        let offered = OfferedRefs::of(&scene(vec![
            seen(ObservationKind::Block, "b1", 2.0, -0.5),
            seen(ObservationKind::Entity, "e1", 2.0, 0.0),
            seen(ObservationKind::DroppedItem, "d1", 1.0, -0.5),
            bed,
        ]));
        let s = |handle: &str| handle.to_owned();
        let admitted = [
            Intent::MoveTo { onto: s("b1") },
            Intent::Flee { from: s("e1") },
            Intent::Eat { slot_ref: s("i1") },
            Intent::Dig { block: s("b1") },
            Intent::Collect {
                item: s("d1"),
                approach: false,
            },
            Intent::Place {
                block: s("b1"),
                item: s("i1"),
                face: BlockFace::Up,
            },
            Intent::Equip {
                item: s("i1"),
                destination: EquipmentDestination::Hand,
            },
            Intent::Craft {
                recipe: s("r1"),
                crafts: 1,
            },
            Intent::Attack {
                target: s("e1"),
                weapon: s("i1"),
                attacks: 1,
                approach: false,
            },
            Intent::Sleep { bed: s("bed1") },
            Intent::Say { text: s("hello") },
            Intent::Rest,
            Intent::Idle,
        ];
        for intent in &admitted {
            assert!(offered.admits(intent), "{intent:?}");
        }
        let refused = [
            Intent::MoveTo { onto: s("e1") },
            Intent::Flee { from: s("b1") },
            Intent::Eat { slot_ref: s("r1") },
            Intent::Dig { block: s("e1") },
            Intent::Collect {
                item: s("b1"),
                approach: true,
            },
            Intent::Place {
                block: s("b1"),
                item: s("e1"),
                face: BlockFace::Up,
            },
            Intent::Place {
                block: s("i1"),
                item: s("i1"),
                face: BlockFace::Up,
            },
            Intent::Equip {
                item: s("b1"),
                destination: EquipmentDestination::Hand,
            },
            Intent::Craft {
                recipe: s("i1"),
                crafts: 1,
            },
            Intent::Attack {
                target: s("b1"),
                weapon: s("i1"),
                attacks: 1,
                approach: false,
            },
            Intent::Attack {
                target: s("e1"),
                weapon: s("d1"),
                attacks: 1,
                approach: false,
            },
            Intent::Sleep { bed: s("e1") },
            // A block that is no bed (R46: a sleep in dirt).
            Intent::Sleep { bed: s("b1") },
            Intent::Dig { block: s("b9") },
        ];
        for intent in &refused {
            assert!(!offered.admits(intent), "{intent:?}");
        }
    }

    #[test]
    fn r19_a_stick_goal_is_offered_its_recipe_first_and_its_surest_digs_and_counted_at_the_mean() {
        // r19 alpha body:00000237: a craftable stick recipe was crowded out behind four leaves,
        // each counted as a stick a dig though 33 leaf digs dropped one.
        let leaf = |index: usize| {
            let mut leaf = seen(ObservationKind::Block, &format!("l{index}"), 1.0, 0.0);
            leaf.yields = Some(vec![loom_contract::BlockYield {
                per_hundred: 3,
                ..yielding("item.minecraft.stick")
            }]);
            leaf
        };
        let mut bush = seen(ObservationKind::Block, "b9", 1.0, 0.0);
        bush.yields = Some(vec![yielding("item.minecraft.stick")]);
        let surroundings = (0..5).map(leaf).chain(std::iter::once(bush)).collect();
        let mut review = scene(surroundings);
        review.trigger = loom_contract::Trigger::GoalReview;
        let offered = OfferedRefs::of(&review);
        let sticks = offered
            .item_steps
            .iter()
            .find(|steps| steps.item == "item.minecraft.stick")
            .unwrap();
        assert_eq!(sticks.recipes, handles(&["r1"]));
        assert_eq!(sticks.dig, handles(&["b9", "l0", "l1"]));
        // The recipe's four and the bush's one; five leaves at three hundredths give no stick.
        assert_eq!(offered.capacity_of("item.minecraft.stick"), 5);
    }

    #[test]
    fn a_stack_or_recipe_the_prompt_cut_is_not_offered() {
        let mut seen = fixture("perception-crafting");
        let stack = seen.self_state.inventory_summary[0].clone();
        let option = seen.self_state.crafting_options[0].clone();
        seen.self_state.inventory_summary = (0..=MAX_INVENTORY_LINES)
            .map(|index| InventoryStack {
                slot_ref: format!("i{index}"),
                ..stack.clone()
            })
            .collect();
        seen.self_state.crafting_options = (0..=MAX_CRAFTING_LINES)
            .map(|index| CraftingOption {
                recipe_ref: format!("r{index}"),
                ..option.clone()
            })
            .collect();
        let offered = OfferedRefs::of(&seen);
        assert_eq!(offered.stacks.len(), MAX_INVENTORY_LINES);
        assert_eq!(offered.recipes.len(), MAX_CRAFTING_LINES);
        assert!(!offered.admits(&Intent::Eat {
            slot_ref: format!("i{MAX_INVENTORY_LINES}")
        }));
    }

    #[test]
    fn a_block_whose_dig_would_trap_is_offered_to_place_and_sleep_but_never_to_dig() {
        // r13 eta, body:00000148: the only standable block was the floor eta stood on.
        let mut floor = seen(ObservationKind::Block, "b1", 1.0, -1.0);
        floor.dig_safe = Some(false);
        floor.yields = Some(vec![yielding("item.minecraft.terracotta")]);
        let wall = seen(ObservationKind::Block, "b2", 1.0, 0.0);
        let offered = OfferedRefs::of(&scene(vec![floor, wall]));
        assert_eq!(offered.blocks, handles(&["b1", "b2"]));
        assert_eq!(offered.dig_blocks, handles(&["b2"]));
        assert_eq!(offered.kept_blocks, handles(&["b1"]));
        assert!(!offered.admits(&Intent::Dig {
            block: "b1".to_owned()
        }));
        assert!(offered.admits(&Intent::Dig {
            block: "b2".to_owned()
        }));
        // Its yield is no success item either, since it may not be dug.
        assert!(!offered
            .obtainable
            .iter()
            .any(|item| item == "item.minecraft.terracotta"));
    }

    #[test]
    fn a_success_item_is_only_one_the_agent_can_get_now() {
        // r13 alpha, beta, epsilon and iota: terracotta, an oak log and a dead bush, none of
        // which anything in sight could give them.
        let mut bush = seen(ObservationKind::Block, "b1", 1.0, 0.0);
        bush.yields = Some(vec![yielding("item.minecraft.stick")]);
        let mut hidden = seen(ObservationKind::Block, "b2", 1.0, 0.0);
        hidden.visible = false;
        hidden.yields = Some(vec![yielding("item.minecraft.oak_log")]);
        let mut drop = seen(ObservationKind::DroppedItem, "d1", 3.0, 0.0);
        drop.what = "item.minecraft.wheat_seeds".to_owned();
        let mut named = seen(ObservationKind::DroppedItem, "d2", 3.0, 0.0);
        named.what = "block.minecraft.dead_bush".to_owned();
        let offered = OfferedRefs::of(&scene(vec![bush, hidden, drop, named]));
        // Craftable first, then what the surroundings would give; a held item alone, a hidden
        // block and a block name never, and nothing one collect could meet: the one seed lying
        // is no objective (r39: alpha and kappa held goals every step completed).
        assert_eq!(offered.obtainable, handles(&["item.minecraft.stick"]));
        // The recipe's four sticks and the bush's expected one; its two are only the best case
        // (r16: goals sized to every bush's most failed three times of four).
        assert_eq!(offered.capacity_of("item.minecraft.stick"), 5);
        assert!(offered
            .best_case
            .contains(&("item.minecraft.stick".to_owned(), 6)));
        assert_eq!(offered.capacity_of("item.minecraft.wheat_seeds"), 1);
        assert_eq!(offered.capacity_of("item.minecraft.stone_sword"), 0);
    }

    #[test]
    fn an_objective_asks_past_the_most_one_offered_step_gives() {
        // The r39 offline audit: "hold 2 oak_planks" beside a held log was met by its paired craft,
        // and a dig at a bush's most or one stack picked up meets a goal the same way.
        const STICK: &str = "item.minecraft.stick";
        let mut bush = seen(ObservationKind::Block, "b1", 2.0, 0.0);
        bush.yields = Some(vec![loom_contract::BlockYield {
            least: 0,
            most: 2,
            per_hundred: 100,
            ..yielding(STICK)
        }]);
        let far = Observation {
            target_ref: "b2".to_owned(),
            distance: 9.0,
            ..bush.clone()
        };
        let stack = Observation {
            what: STICK.to_owned(),
            count: Some(3),
            ..seen(ObservationKind::DroppedItem, "d1", 6.0, 0.0)
        };
        let mut perception = scene(vec![bush, far, stack]);
        // The scene's stick recipe makes four in its one application.
        assert_eq!(super::one_step(&perception, STICK), 4);
        perception.self_state.crafting_options.clear();
        // The stack's three beat the bush in reach's two; the bush out of reach is a walk first.
        assert_eq!(super::one_step(&perception, STICK), 3);
        assert_eq!(super::fewest_objective(&perception, STICK), 4);
        perception.surroundings.truncate(1);
        assert_eq!(super::fewest_objective(&perception, STICK), 3);
    }

    #[test]
    fn the_success_items_offered_are_bounded_however_much_the_blocks_yield() {
        let blocks = (0..48)
            .map(|index| {
                let mut block = seen(ObservationKind::Block, &format!("b{index}"), 1.0, 0.0);
                // Two blocks of each kind, each giving one of every item a dig, so each item is one
                // an objective can ask more than one dig of.
                let kind = index / 2;
                block.yields = Some(
                    (0..8)
                        .map(|item| loom_contract::BlockYield {
                            least: 1,
                            most: 1,
                            ..yielding(&format!("item.minecraft.thing_{kind}_{item}"))
                        })
                        .collect(),
                );
                block
            })
            .collect();
        let offered = OfferedRefs::of(&scene(blocks));
        // The scene's one stick recipe gives every stick sight could: a goal one craft meets is no
        // objective (the r39 offline audit), so it is cut from what is offered, the rest bounded.
        assert_eq!(offered.obtainable.len(), super::MAX_OBTAINABLE_ITEMS - 1);
        assert!(!offered
            .obtainable
            .contains(&"item.minecraft.stick".to_owned()));
        assert_eq!(offered.obtainable[0], "item.minecraft.thing_0_0");
    }
}
