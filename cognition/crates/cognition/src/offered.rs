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
    is_item_name, Bearing, DropFate, Intent, Observation, ObservationKind, Perception, Trigger,
    AIR_ITEM, MAX_SUCCESS_COUNT,
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
    /// Cells at the edge of sight in the direction of a recalled source of it: a walk toward
    /// where the body last saw one (r20: no agent without a log in sight could set a log goal).
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
    /// Shown living things, and a sighted threat: what `flee` may name.
    pub creatures: Vec<String>,
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
    /// Shown inventory stacks: what `eat`, `equip`, `place` and an attack's weapon may name.
    pub stacks: Vec<String>,
    /// Shown craftable recipes: what `craft` may name.
    pub recipes: Vec<String>,
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
    /// ask: one more than is held, and what is held plus [`Self::capacity`], never above
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
    /// Whether the held goal at a goal review could still be met from what is in sight - its
    /// count no more than what is held plus what sight could give. A continue toward a source is
    /// offered only then, by the same rule the body holds an adopt and a revise to.
    pub goal_obtainable: bool,
    /// The steps each item a goal review may set or pursue can be stepped toward by, in the order
    /// of [`Self::count_bounds`], the held goal's item first when it is pursued: a goal's step is
    /// one of its own item's (r18 epsilon adopted a stick goal and dug terracotta).
    pub item_steps: Vec<ItemSteps>,
    /// The held goal's success item at a goal review, whatever phase it is in.
    pub held_item: Option<String>,
    /// The held goal's success total, which a revise must change (r19: 28 of 31 revises set the
    /// total they were shown, and read as goal activity).
    pub held_total: Option<i64>,
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
        // Just hurt beside a hunter, every near hunter is immediate.
        if matches!(perception.trigger, Trigger::Damaged { .. }) {
            offered.immediate.clone_from(&offered.danger);
        }
        // An attack the body would refuse by the time it arrives is not offered.
        if state.health <= ATTACK_MIN_HEALTH + ATTACK_HEALTH_MARGIN {
            offered.strike_targets.clear();
            offered.pursuit_targets.clear();
        }
        if perception.trigger.reviews_goal() && state.food <= HUNGRY_FOOD_LEVEL {
            offered.meals.clone_from(&offered.stacks);
        }
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
            Intent::Flee { from } => self.danger.contains(from),
            Intent::Attack { target, .. } => self.danger.contains(target),
            Intent::Eat { slot_ref } => self.meals.contains(slot_ref),
            _ => false,
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
fn goal_total(perception: &Perception, item: &str) -> Option<i64> {
    let goal = perception.goal.as_ref()?;
    (goal.success_item.as_deref() == Some(item))
        .then_some(goal.success_count)
        .flatten()
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
        for stack in state.inventory_summary.iter().take(MAX_INVENTORY_LINES) {
            offer(&mut offered.stacks, &stack.slot_ref);
        }
        for option in state.crafting_options.iter().take(MAX_CRAFTING_LINES) {
            offer(&mut offered.recipes, &option.recipe_ref);
        }
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
        offered
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
                // Toward where a source of it, or of what it is made from, was last seen; a horizon
                // goal with none remembered may look further every way, as its total reaches past
                // what sight gives.
                let remembered: Vec<Bearing> = perception
                    .recalled
                    .iter()
                    .filter(|source| &source.what == item || path.contains(&source.what))
                    .map(|source| source.direction)
                    .collect();
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

    /// For each item [`Self::capacity`] or the perception's horizon names, the fewest and most a
    /// success count may ask now: one more than is held, up to what is held plus capacity or the
    /// horizon's most, whichever is more. While cells at the edge of sight are offered, an item no
    /// horizon goal is grounded in or made from is not offered at all.
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
                // would only relabel it (r17 alpha body:00000163; r18: three replaces at one more).
                if self.stalled && goal_total(perception, item).is_some() {
                    return None;
                }
                let fewest = held.saturating_add(1);
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
                goal.grounding == loom_contract::HorizonGrounding::Recipe && &goal.what != pursued
            })
            .filter(|goal| {
                path_of(perception, &goal.what)
                    .iter()
                    .any(|input| held_of(perception, input) > 0)
            })
            .filter_map(|goal| {
                let fewest = held_of(perception, &goal.what).saturating_add(1);
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
        let held = held_of(perception, item);
        let capacity = totals
            .iter()
            .find(|(name, _)| name == item)
            .map_or(0, |(_, total)| *total);
        self.goal_obtainable = count <= held.saturating_add(capacity);
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
            && !self.goal_obtainable
            && !self.stalled
            && !self.collect_required
            && self.productive.is_empty()
            && !self.explore_targets.is_empty()
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
            self.creature(&seen.target_ref, seen.distance);
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
                    self.creature(attacker, seen.distance);
                }
            }
            Trigger::ThreatSighted {
                entity_ref,
                distance,
                ..
            } => self.creature(entity_ref, *distance),
            _ => {}
        }
    }

    /// Offers one creature to flight, and to each attack mode whose bound it is within.
    fn creature(&mut self, handle: &str, distance: f64) {
        offer(&mut self.creatures, handle);
        if distance <= ATTACK_REACH_BLOCKS {
            offer(&mut self.strike_targets, handle);
        }
        if distance <= MAX_PURSUIT_BLOCKS {
            offer(&mut self.pursuit_targets, handle);
        }
    }

    /// Whether every ref `intent` names is offered to that action in that mode.
    #[must_use]
    pub fn admits(&self, intent: &Intent) -> bool {
        let held = |list: &[String], handle: &str| list.iter().any(|offered| offered == handle);
        match intent {
            Intent::MoveTo { onto } => held(&self.walk_targets, onto),
            Intent::Flee { from } => held(&self.creatures, from),
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
                held(&self.blocks, block) && held(&self.stacks, item)
            }
            Intent::Equip { item, .. } => held(&self.stacks, item),
            Intent::Craft { recipe, .. } => held(&self.recipes, recipe),
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
            Intent::Sleep { bed } => held(&self.blocks, bed),
            Intent::Say { .. } | Intent::Rest | Intent::Idle => true,
        }
    }
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
        assert!(offered.admits(&Intent::Flee {
            from: "afar".to_owned()
        }));
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
        let offered = OfferedRefs::of(&scene(vec![
            seen(ObservationKind::Block, "b1", 2.0, -0.5),
            seen(ObservationKind::Entity, "e1", 2.0, 0.0),
            seen(ObservationKind::DroppedItem, "d1", 1.0, -0.5),
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
            Intent::Sleep { bed: s("b1") },
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
        // block and a block name never.
        assert_eq!(
            offered.obtainable,
            handles(&["item.minecraft.stick", "item.minecraft.wheat_seeds"])
        );
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
    fn the_success_items_offered_are_bounded_however_much_the_blocks_yield() {
        let blocks = (0..48)
            .map(|index| {
                let mut block = seen(ObservationKind::Block, &format!("b{index}"), 1.0, 0.0);
                block.yields = Some(
                    (0..8)
                        .map(|item| yielding(&format!("item.minecraft.thing_{index}_{item}")))
                        .collect(),
                );
                block
            })
            .collect();
        let offered = OfferedRefs::of(&scene(blocks));
        assert_eq!(offered.obtainable.len(), super::MAX_OBTAINABLE_ITEMS);
        assert_eq!(offered.obtainable[0], "item.minecraft.stick");
        assert_eq!(offered.obtainable[1], "item.minecraft.thing_0_0");
    }
}
