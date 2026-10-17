//! The path exerciser: a deterministic backend that drives each agent of the path rehearsal
//! down one goal path no live run has reached on its own.
//!
//! It is selected by `LOOM_COGNITION_BACKEND=scripted-paths` and by nothing else, and it stamps
//! [`EXERCISER_MODEL_ID`], so no record it answers can be mistaken for a model's or for the
//! ordinary stand-in's. Several of its answers are wrong on purpose - an own drop ignored, a revise
//! to another item, a flight from a peer - because what the rehearsal proves is that the body, the
//! ledger and the gate each refuse or class them as they should against a real server.
//!
//! Each agent's role is fixed by its id's last segment, and every rule reads only the perception
//! in hand, so the exerciser keeps no state:
//! - `alpha` digs two logs, collects its own drops, then crafts planks in two applications and
//!   sticks, each completing the met goal and adopting the next in one answer;
//! - `beta` digs a log, walks away from its lying drop once (refused as an ignored own drop), and
//!   then leaves the drop lying, so a perception past retention still tells it;
//! - `gamma` flees the nearest other player in sight;
//! - `delta` collects, without moving, an item lying at its feet that vanilla delays picking up;
//! - `epsilon` digs the one log in reach, and once the other log it counted on is gone walks east
//!   to the edge of sight to look for another, then revises its log goal to planks once (refused)
//!   before finishing it;
//! - `zeta` speaks at every goal review, so an agent nearby perceives at a time no review of its
//!   own reaches.
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
const PLANKS: &str = "item.minecraft.oak_planks";
const STICK: &str = "item.minecraft.stick";
const PLAYER: &str = "entity.minecraft.player";
const IDLE_ROLE: &str = "nothing on this path to do";

/// How near another player must be for gamma's flight from it.
const PEER_FLIGHT_BLOCKS: f64 = 8.0;

/// The deterministic path exerciser.
#[derive(Clone, Copy, Debug, Default)]
pub struct PathExerciser;

impl ModelBackend for PathExerciser {
    fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
        std::future::ready(Ok(Self::draft_now(route, perception)))
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
            "gamma" => fleer(perception),
            "delta" => collector(perception),
            "epsilon" => explorer(perception),
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
        return look_further(
            perception,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal and look further",
        );
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
        return answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal",
        );
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

/// gamma: a flight from the nearest other player in sight.
fn fleer(perception: &Perception) -> Draft {
    perception
        .surroundings
        .iter()
        .find(|seen| {
            seen.kind == ObservationKind::Entity
                && seen.what == PLAYER
                && seen.distance <= PEER_FLIGHT_BLOCKS
        })
        .map_or_else(
            || answer(Intent::Idle, None, IDLE_ROLE),
            |peer| {
                answer(
                    Intent::Flee {
                        from: peer.target_ref.clone(),
                    },
                    None,
                    "flee a peer, which is no threat",
                )
            },
        )
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
        (_, Some(_)) if met(perception) => answer(
            Intent::Idle,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal",
        ),
        _ => answer(Intent::Idle, None, IDLE_ROLE),
    }
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
        return look_further(
            perception,
            Some(judged(GoalDisposition::Complete)),
            "complete the goal",
        );
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
    fn gamma_flees_the_nearest_peer_and_delta_collects_at_its_feet() {
        let mut gamma = review("gamma");
        let mut peer = dropped("e1", "entity.minecraft.player", 4.0, 0.0);
        peer.kind = ObservationKind::Entity;
        peer.count = None;
        gamma.surroundings = vec![peer];
        assert_eq!(
            draft(&gamma).intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
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
    fn zeta_speaks_and_any_other_agent_is_the_stand_in() {
        assert!(matches!(draft(&review("zeta")).intent, Intent::Say { .. }));
        let other = draft(&review("kappa"));
        assert!(!other.reasoning.starts_with("path exerciser"));
    }
}
