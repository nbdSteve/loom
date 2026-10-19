//! The path exerciser: a deterministic backend that drives each agent of the path rehearsal
//! down one goal path no live run has reached on its own.
//!
//! It is selected by `LOOM_COGNITION_BACKEND=scripted-paths` and by nothing else, and it stamps
//! [`EXERCISER_MODEL_ID`], so no record it answers can be mistaken for a model's or for the
//! ordinary stand-in's. Several of its answers are wrong on purpose - an own drop ignored, a revise
//! to another item - because what the rehearsal proves is that the body, the
//! ledger and the gate each refuse or class them as they should against a real server.
//!
//! Each agent's role is fixed by its id's last segment, and every rule reads only the perception
//! in hand, so the exerciser keeps no state:
//! - `alpha` digs two logs, collects its own drops, then crafts planks in two applications and
//!   sticks, each completing the met goal and adopting the next in one answer;
//! - `beta` digs a log atop a three-high pillar, whose one body pickup finds no route, and then
//!   leaves the drop lying, so a perception past retention still tells it;
//! - `delta` collects, without moving, an item lying at its feet that vanilla delays picking up,
//!   and `theta` does the same where nothing else in sight gives anything, so its met goal is
//!   completed plainly and owes a review;
//! - `epsilon` digs the one log in reach, and once the other log it counted on is gone walks east
//!   to the edge of sight to look for another, then revises its log goal to planks once (refused)
//!   before finishing it;
//! - `zeta` speaks at every goal review, so an agent nearby perceives at a time no review of its
//!   own reaches;
//! - `iota` stands in a pit beside a one-high nook its log lies in, just past vanilla's pickup
//!   reach from where it stands, and collects it with approach (r28 plains rehearsal alpha,
//!   tick 9765: refused `collectNoRoute`, because no cell covered the item from every point);
//! - `kappa` stands against the world border beside a creeper that cannot move and, at its goal
//!   review, flees from it with no goal choice, as a review beside a hunter now may (r20: kappa
//!   reviewed beside a zombie and a skeleton, was offered only dirt digs, and died), along the
//!   border rather than over it (v29 rehearsal theta stalled against it);
//! - `eta` digs logs for a two-log goal beside a pocket under an overhang that its perception
//!   must report unsafe to dig, revises the goal to the total it already has once (refused), and
//!   once the goal is met completes it plainly beside the log still in sight once (refused)
//!   before replacing it;
//! - `gamma` is refused a dirt goal while the frontier is offered (r22:
//!   the dirt underfoot crowded out looking for wood);
//! - `kappa`, once clear of its creeper with nothing in sight to ground an item goal, sets a
//!   discovery goal and walks toward a tree line the rehearsal lays out past what it senses, and
//!   once a walk reveals a log replaces the discovery with a log goal stepped by its dig (r23: 17
//!   of 70 reviews could set no goal at all).
//!
//! Any other agent is answered by [`ScriptedBackend`].

use std::future::Future;

use loom_contract::{
    Bearing, GoalChoice, GoalDisposition, GoalStepResult, Intent, ObservationKind, Perception,
    StepEvidence,
};

use crate::model::{BackendError, Draft, ModelBackend, ScriptedBackend};
use crate::offered::{held_of, OfferedRefs};
use crate::router::ModelRoute;

/// The model id the exerciser stamps on its decisions.
pub const EXERCISER_MODEL_ID: &str = "scripted-path-exerciser";

const LOG: &str = "item.minecraft.oak_log";
const DIRT: &str = "item.minecraft.dirt";
const PLANKS: &str = "item.minecraft.oak_planks";
const STICK: &str = "item.minecraft.stick";
const IDLE_ROLE: &str = "nothing on this path to do";

/// The deterministic path exerciser.
#[derive(Clone, Copy, Debug, Default)]
pub struct PathExerciser;

impl ModelBackend for PathExerciser {
    fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
        // As the scripted stand-in does: a request schema the provider would refuse fails here.
        std::future::ready(
            if crate::openai::wire::request_offers_an_answer(perception) {
                Ok(Self::draft_now(route, perception))
            } else {
                Err(BackendError::new(crate::model::NO_ANSWER_OFFERED))
            },
        )
    }
}

/// One answer, worded as the exerciser's.
fn answer(intent: Intent, goal: Option<GoalChoice>, why: &str) -> Draft {
    Draft {
        intent,
        reasoning: format!("path exerciser: {why}"),
        goal,
    }
}

/// A choice setting `count` of `item` under `disposition`, judging the previous step.
fn target(disposition: GoalDisposition, item: &str, count: i64) -> GoalChoice {
    GoalChoice {
        discover: None,
        disposition,
        success_item: Some(item.to_owned()),
        success_count: Some(count),
        step: None,
        step_result: (disposition != GoalDisposition::Adopt).then_some(GoalStepResult::Progressing),
    }
}

/// A choice under `disposition` that sets no target.
const fn judged(disposition: GoalDisposition) -> GoalChoice {
    GoalChoice {
        discover: None,
        disposition,
        success_item: None,
        success_count: None,
        step: None,
        step_result: Some(GoalStepResult::Progressing),
    }
}

impl PathExerciser {
    /// The exerciser's answer to `perception`, without the async wrapper.
    #[must_use]
    pub fn draft_now(route: &ModelRoute, perception: &Perception) -> Draft {
        if !perception.trigger.reviews_goal() {
            return answer(Intent::Idle, None, "only a goal review moves a path");
        }
        let role = perception.agent_id.rsplit('-').next().unwrap_or_default();
        match role {
            "alpha" => crafter(perception),
            "beta" => dropper(perception),
            "gamma" => crowder(perception),
            "delta" | "theta" => collector(perception),
            "epsilon" => explorer(perception),
            "eta" => restater(perception),
            "iota" => fetcher(perception),
            "kappa" => warden(perception),
            "zeta" => answer(
                Intent::Say {
                    text: "hello from zeta".to_owned(),
                },
                None,
                "speak, so an agent nearby perceives now",
            ),
            _ => ScriptedBackend::draft_now(route, perception),
        }
    }
}

/// The nearest offered dig of a block that can drop `item`.
fn dig_of(perception: &Perception, item: &str) -> Option<String> {
    let offered = OfferedRefs::of(perception);
    perception
        .surroundings
        .iter()
        .filter(|seen| offered.dig_blocks.contains(&seen.target_ref))
        .find(|seen| {
            seen.yields.as_ref().is_some_and(|yields| {
                yields
                    .iter()
                    .any(|given| given.what == item && given.most > 0)
            })
        })
        .map(|seen| seen.target_ref.clone())
}

/// The agent's own drop of `item` offered to collect, and whether collecting it needs a walk.
fn own_drop_of(perception: &Perception, item: &str) -> Option<(String, bool)> {
    let offered = OfferedRefs::of(perception);
    offered
        .own_drops
        .iter()
        .find(|(_, what)| what == item)
        .map(|(handle, _)| (handle.clone(), !offered.pickup_items.contains(handle)))
}

/// Any drop of `item` offered to collect, and whether collecting it needs a walk.
fn drop_of(perception: &Perception, item: &str) -> Option<(String, bool)> {
    let offered = OfferedRefs::of(perception);
    perception
        .surroundings
        .iter()
        .filter(|seen| seen.kind == ObservationKind::DroppedItem && seen.what == item)
        .find(|seen| offered.route_items.contains(&seen.target_ref))
        .map(|seen| {
            (
                seen.target_ref.clone(),
                !offered.pickup_items.contains(&seen.target_ref),
            )
        })
}

/// The offered recipe that makes `item`, how many one application makes, and how many
/// applications it offers.
fn recipe_of(perception: &Perception, item: &str) -> Option<(String, i64, i64)> {
    perception
        .self_state
        .crafting_options
        .iter()
        .find(|option| option.what == item)
        .map(|option| {
            (
                option.recipe_ref.clone(),
                option.result_count,
                option.max_crafts,
            )
        })
}

/// The cell at the edge of sight straight toward `direction` - the farthest of those bearing that
/// way, since a diagonal one is nearer - else the first one offered.
fn edge_toward(perception: &Perception, direction: Bearing) -> Option<String> {
    perception
        .explore
        .iter()
        .filter(|cell| cell.direction == direction)
        .max_by(|one, other| one.distance.total_cmp(&other.distance))
        .or_else(|| perception.explore.first())
        .map(|cell| cell.target_ref.clone())
}

/// A walk east to look further beside `goal`, or an idle when nowhere is offered.
fn look_further(perception: &Perception, goal: Option<GoalChoice>, why: &str) -> Draft {
    match edge_toward(perception, Bearing::East) {
        Some(onto) => answer(Intent::MoveTo { onto }, goal, why),
        None => answer(Intent::Idle, None, IDLE_ROLE),
    }
}

/// Whether the carried goal's success condition is met.
fn met(perception: &Perception) -> bool {
    perception
        .goal
        .as_ref()
        .and_then(|goal| goal.progress.as_ref())
        .is_some_and(|progress| progress.condition_met)
}

/// One pursuit step for a held goal of `item`: collect an own drop, craft it, dig it, or collect
/// any other drop of it in reach.
fn pursue(perception: &Perception, item: &str) -> Option<Draft> {
    let continuing = || Some(judged(GoalDisposition::Continue));
    if let Some((drop, approach)) = own_drop_of(perception, item) {
        return Some(answer(
            Intent::Collect {
                item: drop,
                approach,
            },
            continuing(),
            "collect my own drop of the goal's item",
        ));
    }
    if let Some((recipe, ..)) = recipe_of(perception, item) {
        return Some(answer(
            Intent::Craft { recipe, crafts: 1 },
            continuing(),
            "craft the goal's item",
        ));
    }
    if let Some(block) = dig_of(perception, item) {
        return Some(answer(
            Intent::Dig { block },
            continuing(),
            "dig a source of the goal's item",
        ));
    }
    drop_of(perception, item).map(|(drop, approach)| {
        answer(
            Intent::Collect {
                item: drop,
                approach,
            },
            continuing(),
            "collect a drop of the goal's item in reach",
        )
    })
}

/// A met goal ended: replaced by the next goal sight offers, stepped toward by its own step or a
/// walk to look further, or completed with a walk to look further when sight offers none.
fn finish(perception: &Perception) -> Draft {
    let offered = OfferedRefs::of(perception);
    let Some((item, fewest, _)) = offered.count_bounds.first().cloned() else {
        return look_further(
            perception,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal and look further",
        );
    };
    let next = Some(target(GoalDisposition::Replace, &item, fewest));
    match pursue(perception, &item) {
        Some(step) => answer(
            step.intent,
            next,
            "complete the met goal and adopt the next",
        ),
        None => look_further(
            perception,
            next,
            "complete the met goal, adopt the next and look further",
        ),
    }
}

/// alpha: two logs, then planks, then sticks, each met goal replaced by the next in one answer.
fn crafter(perception: &Perception) -> Draft {
    let Some(goal) = &perception.goal else {
        return dig_of(perception, LOG).map_or_else(
            || look_further(perception, None, "no log in sight; look further"),
            |block| {
                answer(
                    Intent::Dig { block },
                    Some(target(GoalDisposition::Adopt, LOG, 2)),
                    "dig a log for a two-log goal",
                )
            },
        );
    };
    let item = goal.success_item.as_deref().unwrap_or(LOG);
    if met(perception) {
        let next = if item == LOG { PLANKS } else { STICK };
        if let Some((recipe, makes, most)) = recipe_of(perception, next) {
            // Every application the recipe offers, at most two, so a craft of several
            // applications meets a real server (r19 theta body:00000310, epsilon 352).
            let crafts = most.clamp(1, 2);
            let count = held_of(perception, next) + makes * crafts;
            return answer(
                Intent::Craft { recipe, crafts },
                Some(target(GoalDisposition::Replace, next, count)),
                "complete the met goal and adopt the next by crafting it",
            );
        }
        return finish(perception);
    }
    pursue(perception, item).unwrap_or_else(|| {
        answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Abandon)),
            "nothing gives it",
        )
    })
}

/// beta: a log dug, one walk away from its lying drop (refused), and then the drop left lying.
fn dropper(perception: &Perception) -> Draft {
    if perception.goal.is_none() {
        return dig_of(perception, LOG).map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |block| {
                answer(
                    Intent::Dig { block },
                    Some(target(GoalDisposition::Adopt, LOG, 1)),
                    "dig a log",
                )
            },
        );
    }
    if met(perception) {
        return finish(perception);
    }
    let refused = perception
        .last_refusal
        .as_ref()
        .is_some_and(|refusal| refusal.outcome == "goalOwnDropIgnored");
    // The mind withholds every dig while the drop must be collected, so the drop is ignored by a
    // walk away from it, which the namespace admits and the ledger refuses; afterwards it is left
    // lying, so a perception past retention still tells it.
    if !refused
        && OfferedRefs::of(perception).collect_required
        && own_drop_of(perception, LOG).is_some()
    {
        if let Some(onto) = edge_toward(perception, Bearing::West) {
            return answer(
                Intent::MoveTo { onto },
                Some(judged(GoalDisposition::Continue)),
                "walk away from my own lying drop, which the body must refuse",
            );
        }
    }
    answer(Intent::Idle, None, "leave my own drop lying")
}

/// gamma: a dirt goal beside the frontier, refused once. A flight from a peer is no longer one it
/// may take, because a peer is never offered to `flee` from (r27: delta's peer flight).
fn crowder(perception: &Perception) -> Draft {
    // gamma asks for the dirt underfoot as a goal while there is ground
    // to look at, which the body must refuse, once (r22: the dirt crowded out looking for wood).
    let crowding = perception.trigger.reviews_goal()
        && perception.goal.is_none()
        && perception.last_refusal.is_none()
        && !perception.explore.is_empty();
    match dig_of(perception, DIRT).filter(|_| crowding) {
        Some(block) => answer(
            Intent::Dig { block },
            Some(target(GoalDisposition::Adopt, DIRT, 1)),
            "dig the dirt underfoot for a goal while there is ground to look at, which the body must refuse",
        ),
        None => answer(Intent::Idle, None, IDLE_ROLE),
    }
}

/// delta: an item at its feet collected without moving, which vanilla picks up once its delay ends.
fn collector(perception: &Perception) -> Draft {
    let offered = OfferedRefs::of(perception);
    let lying = perception.surroundings.iter().find(|seen| {
        seen.kind == ObservationKind::DroppedItem && offered.pickup_items.contains(&seen.target_ref)
    });
    match (lying, &perception.goal) {
        (Some(item), None) => answer(
            Intent::Collect {
                item: item.target_ref.clone(),
                approach: false,
            },
            Some(target(
                GoalDisposition::Adopt,
                &item.what,
                held_of(perception, &item.what) + item.count.unwrap_or(1),
            )),
            "collect the item at my feet without moving",
        ),
        // A met goal is completed where the agent stands.
        (_, Some(_)) if met(perception) => answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal and stay",
        ),
        _ => answer(Intent::Idle, None, IDLE_ROLE),
    }
}

/// kappa: a flight from the first hunter the review offers, with no goal choice.
fn warden(perception: &Perception) -> Draft {
    OfferedRefs::of(perception).danger.first().map_or_else(
        || discoverer(perception),
        |hunter| {
            answer(
                Intent::Flee {
                    from: hunter.clone(),
                },
                None,
                "flee the hunter beside me at my goal review",
            )
        },
    )
}

/// Where kappa's tree line runs, along z, from x [`TREE_LINE_WEST`] to [`TREE_LINE_EAST`]; the
/// rehearsal lays it out past anything kappa senses from the border.
const TREE_LINE_Z: f64 = -46.0;
const TREE_LINE_WEST: f64 = -60.0;
const TREE_LINE_EAST: f64 = -14.0;

/// The bearing from where `perception` stands toward the nearest point of kappa's tree line.
fn toward_tree_line(perception: &Perception) -> Bearing {
    let at = &perception.self_state.position;
    let (x, z) = (
        at.first().copied().unwrap_or(0.0),
        at.get(2).copied().unwrap_or(0.0),
    );
    let dx = x.clamp(TREE_LINE_WEST, TREE_LINE_EAST) - x;
    let dz = TREE_LINE_Z - z;
    match (dx.abs() > dz.abs(), dx > 0.0, dz > 0.0) {
        (true, true, _) => Bearing::East,
        (true, false, _) => Bearing::West,
        (false, _, true) => Bearing::South,
        (false, _, false) => Bearing::North,
    }
}

/// kappa, once clear of the creeper: with nothing grounding an item goal, a discovery goal walked
/// toward its tree line until a log comes into sight, then a log goal for it (r23: 17 of 70
/// reviews could set no goal at all).
fn discoverer(perception: &Perception) -> Draft {
    let offered = OfferedRefs::of(perception);
    let walk = || edge_toward(perception, toward_tree_line(perception));
    let discovering = perception.goal.as_ref().is_some_and(|goal| goal.discovery);
    if offered.stalled {
        return answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Abandon)),
            "the discovery advanced nothing for three walks; abandon it",
        );
    }
    if discovering && met(perception) {
        if let (Some(block), Some((fewest, _))) =
            (dig_of(perception, LOG), offered.count_bounds_of(LOG))
        {
            return answer(
                Intent::Dig { block },
                Some(target(GoalDisposition::Replace, LOG, fewest)),
                "the walk revealed a log: replace the discovery with a log goal and dig it",
            );
        }
    }
    if discovering {
        return walk().map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |onto| {
                answer(
                    Intent::MoveTo { onto },
                    Some(judged(GoalDisposition::Continue)),
                    "keep looking for wood",
                )
            },
        );
    }
    if perception.goal.is_none() && offered.discovery_offered() {
        if let Some(onto) = walk() {
            return answer(
                Intent::MoveTo { onto },
                Some(GoalChoice {
                    discover: Some(true),
                    step_result: None,
                    ..judged(GoalDisposition::Adopt)
                }),
                "nothing in sight grounds a goal: set out to discover one",
            );
        }
    }
    match &perception.goal {
        Some(_) if met(perception) => finish(perception),
        Some(_) => pursue(perception, LOG).unwrap_or_else(|| answer(Intent::Idle, None, IDLE_ROLE)),
        None => answer(Intent::Idle, None, IDLE_ROLE),
    }
}

/// iota: the first drop in route reach collected with approach, for a goal of holding it.
fn fetcher(perception: &Perception) -> Draft {
    if perception.goal.is_some() {
        return if met(perception) {
            finish(perception)
        } else {
            answer(Intent::Idle, None, IDLE_ROLE)
        };
    }
    let offered = OfferedRefs::of(perception);
    perception
        .surroundings
        .iter()
        .find(|seen| {
            seen.kind == ObservationKind::DroppedItem
                && offered.route_items.contains(&seen.target_ref)
        })
        .map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |item| {
                answer(
                    Intent::Collect {
                        item: item.target_ref.clone(),
                        approach: true,
                    },
                    Some(target(
                        GoalDisposition::Adopt,
                        &item.what,
                        held_of(perception, &item.what) + item.count.unwrap_or(1),
                    )),
                    "collect the drop in the nook beside my pit",
                )
            },
        )
}

/// epsilon: one log, a walk east that reveals another once the second it counted on is gone, one
/// refused revise to planks, then the log.
fn explorer(perception: &Perception) -> Draft {
    let Some(goal) = &perception.goal else {
        return dig_of(perception, LOG).map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |block| {
                answer(
                    Intent::Dig { block },
                    Some(target(GoalDisposition::Adopt, LOG, 2)),
                    "dig the log in reach for two",
                )
            },
        );
    };
    if met(perception) {
        return finish(perception);
    }
    let revealed = goal
        .progress
        .as_ref()
        .is_some_and(|progress| progress.step_evidence == StepEvidence::Revealed);
    if revealed && perception.last_refusal.is_none() {
        if let Some(block) = dig_of(perception, LOG) {
            return answer(
                Intent::Dig { block },
                Some(target(GoalDisposition::Revise, PLANKS, 1)),
                "revise the log goal to planks, which the body must refuse",
            );
        }
    }
    pursue(perception, LOG).unwrap_or_else(|| {
        look_further(
            perception,
            Some(judged(GoalDisposition::Continue)),
            "nothing in sight gives a log; look east",
        )
    })
}

/// Whether the review was told its last answer was refused as `outcome`.
fn refused_as(perception: &Perception, outcome: &str) -> bool {
    perception
        .last_refusal
        .as_ref()
        .is_some_and(|refusal| refusal.outcome == outcome)
}

/// eta: a two-log goal, one revise to the same total before its first log is held (refused), and
/// once met one plain completion beside the log still in sight (refused) before the replace it owed.
fn restater(perception: &Perception) -> Draft {
    let Some(goal) = &perception.goal else {
        return dig_of(perception, LOG).map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |block| {
                answer(
                    Intent::Dig { block },
                    Some(target(GoalDisposition::Adopt, LOG, 2)),
                    "dig a log for a two-log goal",
                )
            },
        );
    };
    let item = goal.success_item.as_deref().unwrap_or(LOG);
    if met(perception) {
        if refused_as(perception, "goalCompletedWithoutNext") {
            return finish(perception);
        }
        return answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Complete)),
            "complete the met goal beside dirt still in sight, which the body must refuse",
        );
    }
    let Some(step) = pursue(perception, item) else {
        return answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Abandon)),
            "nothing gives it",
        );
    };
    // Restated once while the goal is unmet, whatever its own pickup has already brought in.
    let unrevised = goal.revision == 0
        && perception.last_refusal.is_none()
        && goal
            .success_count
            .is_some_and(|total| held_of(perception, item) < total);
    match goal.success_count {
        Some(total) if unrevised => answer(
            step.intent,
            Some(target(GoalDisposition::Revise, item, total)),
            "revise the goal to the total it already has, which the body must refuse",
        ),
        _ => step,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{PathExerciser, LOG, PLANKS, STICK};
    use crate::offered::OfferedRefs;
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{
        Bearing, BlockYield, CraftingOption, DecisionImportance, DropFate, ExploreTarget,
        GoalDisposition, GoalProgress, GoalRefusal, GoalSummary, Intent, Observation,
        ObservationKind, OwnDrop, Perception, StepEvidence,
    };

    fn review(agent: &str) -> Perception {
        let path = format!(
            "{}/../../../contract/fixtures/valid/perception-goal-review.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let mut seen: Perception =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        seen.agent_id = format!("tribe-{agent}");
        seen.self_state.inventory_summary.clear();
        seen
    }

    fn log(handle: &str) -> Observation {
        Observation {
            kind: ObservationKind::Block,
            what: "block.minecraft.oak_log".to_owned(),
            distance: 2.2,
            direction: Bearing::East,
            vertical_offset: 0.5,
            visible: true,
            target_ref: handle.to_owned(),
            count: None,
            standable: Some(false),
            yields: Some(vec![BlockYield {
                what: LOG.to_owned(),
                least: 1,
                most: 1,
                per_hundred: 100,
            }]),
            dig_safe: Some(true),
            route_refused: None,
            hunts: None,
        }
    }

    fn dropped(handle: &str, what: &str, distance: f64, rise: f64) -> Observation {
        Observation {
            kind: ObservationKind::DroppedItem,
            what: what.to_owned(),
            distance,
            direction: Bearing::East,
            vertical_offset: rise,
            visible: true,
            target_ref: handle.to_owned(),
            count: Some(1),
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: None,
        }
    }

    fn edge(handle: &str, direction: Bearing, distance: f64) -> ExploreTarget {
        ExploreTarget {
            target_ref: handle.to_owned(),
            distance,
            direction,
            vertical_offset: 0.0,
        }
    }

    fn holding(seen: &mut Perception, item: &str, count: i64, target: i64, evidence: StepEvidence) {
        if count > 0 {
            seen.self_state
                .inventory_summary
                .push(loom_contract::InventoryStack {
                    slot_ref: "i1".to_owned(),
                    what: item.to_owned(),
                    count,
                });
        }
        seen.goal = Some(GoalSummary {
            discovery: false,
            goal_id: "g".to_owned(),
            statement: format!("hold {target} {item}"),
            revision: 0,
            adopted_tick: 0,
            last_step: None,
            success_item: Some(item.to_owned()),
            success_count: Some(target),
            step: None,
            step_action: None,
            step_ref: None,
            progress: Some(GoalProgress {
                condition_met: count >= target,
                condition_held: count,
                inventory_delta: vec![],
                condition_advanced: false,
                step_advanced: evidence != StepEvidence::None,
                step_evidence: evidence,
                steps_without_progress: 0,
            }),
        });
    }

    fn refusal(outcome: &str) -> GoalRefusal {
        GoalRefusal {
            request_id: "req-refused".to_owned(),
            outcome: outcome.to_owned(),
            success_item: Some(LOG.to_owned()),
            asked: Some(1),
            held: Some(0),
            capacity: Some(1),
        }
    }

    fn draft(seen: &Perception) -> crate::model::Draft {
        let route = Router::new(ModelCatalog::uniform("x")).route(DecisionImportance::Routine);
        let drafted = PathExerciser::draft_now(&route, seen);
        assert!(
            OfferedRefs::of(seen).admits(&drafted.intent),
            "the mind would refuse {:?}",
            drafted.intent
        );
        drafted
    }

    #[test]
    fn alpha_digs_two_logs_then_completes_into_planks_by_crafting() {
        let mut seen = review("alpha");
        seen.surroundings = vec![log("b1"), log("b2")];
        let first = draft(&seen);
        assert_eq!(
            first.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        assert_eq!(first.goal.unwrap().success_count, Some(2));
        holding(&mut seen, LOG, 2, 2, StepEvidence::Gained);
        seen.self_state.crafting_options.push(CraftingOption {
            recipe_ref: "r1".to_owned(),
            what: PLANKS.to_owned(),
            result_count: 4,
            max_crafts: 2,
            requires_table: false,
        });
        let next = draft(&seen);
        assert_eq!(
            next.intent,
            Intent::Craft {
                recipe: "r1".to_owned(),
                crafts: 2
            }
        );
        let replace = next.goal.unwrap();
        assert_eq!(replace.disposition, GoalDisposition::Replace);
        assert_eq!(replace.success_item.as_deref(), Some(PLANKS));
        assert_eq!(replace.success_count, Some(8));
    }

    #[test]
    fn beta_walks_away_from_its_lying_drop_once_then_leaves_it() {
        let mut seen = review("beta");
        seen.surroundings = vec![log("b1")];
        assert_eq!(
            draft(&seen).intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        holding(&mut seen, LOG, 0, 1, StepEvidence::Dropped);
        seen.surroundings = vec![dropped("d1", LOG, 2.0, 0.0)];
        seen.self_state.own_drops.push(OwnDrop {
            request_id: "req-dig".to_owned(),
            what: LOG.to_owned(),
            count: 1,
            fate: DropFate::Lying,
            distance: Some(2.0),
            direction: Some(Bearing::East),
            vertical_offset: Some(0.0),
            drop_ref: Some("d1".to_owned()),
        });
        seen.explore = vec![edge("x3", Bearing::West, 12.0)];
        let ignored = draft(&seen);
        assert_eq!(
            ignored.intent,
            Intent::MoveTo {
                onto: "x3".to_owned()
            }
        );
        assert_eq!(ignored.goal.unwrap().disposition, GoalDisposition::Continue);
        seen.last_refusal = Some(refusal("goalOwnDropIgnored"));
        assert_eq!(draft(&seen).intent, Intent::Idle);
    }

    #[test]
    fn gamma_never_flees_a_peer_and_delta_collects_at_its_feet() {
        // r27: a peer is never offered to flee from, so gamma beside one takes no flight.
        let mut gamma = review("gamma");
        let mut peer = dropped("e1", "entity.minecraft.player", 4.0, 0.0);
        peer.kind = ObservationKind::Entity;
        peer.count = None;
        gamma.surroundings = vec![peer];
        assert!(!matches!(draft(&gamma).intent, Intent::Flee { .. }));
        let mut delta = review("delta");
        delta.surroundings = vec![dropped("d1", STICK, 0.3, 0.0)];
        let collect = draft(&delta);
        assert_eq!(
            collect.intent,
            Intent::Collect {
                item: "d1".to_owned(),
                approach: false
            }
        );
        assert_eq!(collect.goal.unwrap().success_item.as_deref(), Some(STICK));
        // gamma asks for the dirt underfoot beside the frontier, once.
        let mut clear = review("gamma");
        clear.surroundings = vec![Observation {
            what: "block.minecraft.grass_block".to_owned(),
            yields: Some(vec![BlockYield {
                what: super::DIRT.to_owned(),
                least: 1,
                most: 1,
                per_hundred: 100,
            }]),
            ..log("g1")
        }];
        clear.explore = vec![edge("x1", Bearing::East, 12.0)];
        let crowding = draft(&clear);
        assert_eq!(
            crowding.goal.unwrap().success_item.as_deref(),
            Some(super::DIRT)
        );
        clear.last_refusal = Some(refusal("goalCrowdsExploration"));
        assert_eq!(draft(&clear).intent, Intent::Idle);
        // delta completes its met goal where it stands.
        let mut met = review("delta");
        holding(&mut met, STICK, 1, 1, StepEvidence::Gained);
        let stay = draft(&met);
        assert_eq!(stay.intent, Intent::Idle);
        assert_eq!(stay.goal.unwrap().disposition, GoalDisposition::Complete);
    }

    #[test]
    fn epsilon_asks_the_unreached_log_looks_east_then_revises_to_planks_once() {
        let mut seen = review("epsilon");
        seen.explore = vec![
            edge("x0", Bearing::North, 12.0),
            edge("x1", Bearing::East, 11.3),
            edge("x2", Bearing::East, 12.0),
        ];
        holding(&mut seen, LOG, 1, 2, StepEvidence::Gained);
        // A log lying in reach is collected.
        seen.surroundings = vec![dropped("d9", LOG, 3.6, 3.0)];
        assert_eq!(
            draft(&seen).intent,
            Intent::Collect {
                item: "d9".to_owned(),
                approach: true
            }
        );
        // Once it is gone, nothing in sight gives a log: walk straight east.
        seen.surroundings.clear();
        let looking = draft(&seen);
        assert_eq!(
            looking.intent,
            Intent::MoveTo {
                onto: "x2".to_owned()
            }
        );
        assert_eq!(looking.goal.unwrap().disposition, GoalDisposition::Continue);
        seen.surroundings = vec![log("b9")];
        seen.goal
            .as_mut()
            .unwrap()
            .progress
            .as_mut()
            .unwrap()
            .step_evidence = StepEvidence::Revealed;
        let revise = draft(&seen).goal.unwrap();
        assert_eq!(revise.disposition, GoalDisposition::Revise);
        assert_eq!(revise.success_item.as_deref(), Some(PLANKS));
        seen.last_refusal = Some(refusal("goalReviseItemChanged"));
        assert_eq!(
            draft(&seen).intent,
            Intent::Dig {
                block: "b9".to_owned()
            }
        );
    }

    #[test]
    fn eta_restates_its_log_total_once_and_completes_beside_a_log_once_before_replacing() {
        let grass = Observation {
            what: "block.minecraft.grass_block".to_owned(),
            yields: Some(vec![BlockYield {
                what: super::DIRT.to_owned(),
                least: 1,
                most: 1,
                per_hundred: 100,
            }]),
            standable: Some(true),
            ..log("g1")
        };
        let mut first = review("eta");
        first.surroundings = vec![grass, log("b1"), log("b2"), log("b3")];
        first.explore = vec![edge("x1", Bearing::East, 12.0)];
        // The horizon the JVM grounds in the logs in sight; the dirt underfoot grounds none.
        first.horizon = vec![loom_contract::HorizonGoal {
            what: LOG.to_owned(),
            grounding: loom_contract::HorizonGrounding::Sight,
            via: None,
            most: 11,
            frontier: true,
        }];
        let adopted = draft(&first);
        assert_eq!(
            adopted.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let adopt = adopted.goal.unwrap();
        assert_eq!(adopt.disposition, GoalDisposition::Adopt);
        assert_eq!(adopt.success_item.as_deref(), Some(LOG));
        assert_eq!(adopt.success_count, Some(2));
        let mut second = first.clone();
        second.last_refusal = None;
        holding(&mut second, LOG, 0, 2, StepEvidence::None);
        let restated = draft(&second).goal.unwrap();
        assert_eq!(restated.disposition, GoalDisposition::Revise);
        assert_eq!(restated.success_count, Some(2));
        second.last_refusal = Some(refusal("goalReviseUnchanged"));
        assert_eq!(
            draft(&second).goal.unwrap().disposition,
            GoalDisposition::Continue
        );
        let mut met = first.clone();
        met.last_refusal = None;
        holding(&mut met, LOG, 2, 2, StepEvidence::Gained);
        let plain = draft(&met);
        assert_eq!(plain.intent, Intent::Idle);
        assert_eq!(plain.goal.unwrap().disposition, GoalDisposition::Complete);
        met.last_refusal = Some(refusal("goalCompletedWithoutNext"));
        assert_eq!(
            draft(&met).goal.unwrap().disposition,
            GoalDisposition::Replace
        );
    }

    #[test]
    fn iota_collects_the_drop_in_its_nook_with_approach_and_then_waits() {
        let mut seen = review("iota");
        seen.surroundings = vec![dropped("d1", "item.minecraft.dirt", 1.506, 0.0)];
        let fetched = draft(&seen);
        assert_eq!(
            fetched.intent,
            Intent::Collect {
                item: "d1".to_owned(),
                approach: true
            }
        );
        let adopt = fetched.goal.unwrap();
        assert_eq!(adopt.disposition, GoalDisposition::Adopt);
        assert_eq!(adopt.success_count, Some(1));
        // Nothing to collect, or a goal it holds and has not met: it waits.
        assert_eq!(draft(&review("iota")).intent, Intent::Idle);
        let mut holding_goal = review("iota");
        holding(
            &mut holding_goal,
            "item.minecraft.dirt",
            0,
            1,
            StepEvidence::None,
        );
        assert_eq!(draft(&holding_goal).intent, Intent::Idle);
    }

    #[test]
    fn kappa_flees_the_hunter_its_review_offers_with_no_goal_choice() {
        let mut seen = review("kappa");
        assert_eq!(draft(&seen).intent, Intent::Idle);
        seen.surroundings = vec![Observation {
            kind: ObservationKind::Entity,
            what: "entity.minecraft.creeper".to_owned(),
            distance: 3.0,
            direction: Bearing::East,
            vertical_offset: 0.0,
            visible: true,
            target_ref: "e1".to_owned(),
            count: None,
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: Some(true),
        }];
        let fled = draft(&seen);
        assert_eq!(
            fled.intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
        assert!(fled.goal.is_none());
    }

    #[test]
    fn kappa_clear_of_the_creeper_discovers_its_tree_line_and_replaces_the_discovery_with_logs() {
        let mut seen = review("kappa");
        seen.self_state.position = [-30.5, -60.0, -60.5];
        seen.explore = vec![
            edge("x1", Bearing::North, 12.0),
            edge("x5", Bearing::South, 12.0),
        ];
        seen.horizon.clear();
        let set = draft(&seen);
        assert_eq!(
            set.intent,
            Intent::MoveTo {
                onto: "x5".to_owned()
            }
        );
        assert_eq!(set.goal.as_ref().and_then(|goal| goal.discover), Some(true));
        // Held and not yet met, it keeps walking toward the tree line.
        seen.goal = Some(GoalSummary {
            discovery: true,
            goal_id: "g".to_owned(),
            statement: "find a useful resource beyond the edge of sight".to_owned(),
            revision: 0,
            adopted_tick: 0,
            last_step: None,
            success_item: None,
            success_count: None,
            step: None,
            step_action: None,
            step_ref: None,
            progress: Some(GoalProgress {
                condition_met: false,
                condition_held: 0,
                inventory_delta: vec![],
                condition_advanced: false,
                step_advanced: false,
                step_evidence: StepEvidence::None,
                steps_without_progress: 1,
            }),
        });
        let walking = draft(&seen);
        assert_eq!(
            walking.intent,
            Intent::MoveTo {
                onto: "x5".to_owned()
            }
        );
        assert_eq!(
            walking.goal.map(|goal| goal.disposition),
            Some(GoalDisposition::Continue)
        );
        // The walk revealed a log: the discovery becomes a log goal, stepped by its dig.
        seen.surroundings = vec![log("b1")];
        seen.horizon = vec![loom_contract::HorizonGoal {
            what: LOG.to_owned(),
            grounding: loom_contract::HorizonGrounding::Sight,
            via: None,
            most: 9,
            frontier: true,
        }];
        if let Some(progress) = seen.goal.as_mut().and_then(|goal| goal.progress.as_mut()) {
            progress.condition_met = true;
            progress.step_evidence = StepEvidence::Revealed;
        }
        let became = draft(&seen);
        assert_eq!(
            became.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let choice = became.goal.unwrap();
        assert_eq!(choice.disposition, GoalDisposition::Replace);
        assert_eq!(choice.success_item.as_deref(), Some(LOG));
    }

    #[test]
    fn zeta_speaks_and_any_other_agent_is_the_stand_in() {
        assert!(matches!(draft(&review("zeta")).intent, Intent::Say { .. }));
        let other = draft(&review("lambda"));
        assert!(!other.reasoning.starts_with("path exerciser"));
    }
}
