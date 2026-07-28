//! The model backend seam, and the deterministic stand-in behind it.
//!
//! A backend is handed a resolved [`ModelRoute`] and a bounded [`Perception`]
//! and returns the two things a model contributes to a decision: an [`Intent`]
//! and the reasoning for it. It does not build the [`Decision`] - provenance
//! stamping belongs to the mind, so a backend cannot misreport which model
//! answered.
//!
//! [`ScriptedBackend`] is the stand-in used until real inference is wired in.
//! It is deliberately not a fake in the test sense: it runs in the shipped
//! service, so the path under test is the real one. What it lacks is inference,
//! not structure - it maps a trigger to a sensible intent by rule and has no
//! spatial reasoning at all, which its own comments say plainly rather than
//! implying a competence it does not have.

use loom_contract::{Intent, Perception, Trigger};

use crate::router::ModelRoute;

/// The half of a decision a model produces: what to do, and why.
///
/// The mind wraps this into a [`loom_contract::Decision`] and stamps the
/// provenance itself.
#[derive(Clone, Debug, PartialEq)]
pub struct Draft {
    /// The intent the body will validate and apply.
    pub intent: Intent,
    /// The natural-language justification, carried into the decision for
    /// research.
    pub reasoning: String,
}

/// Why a backend could not produce a draft.
///
/// A backend failure must degrade one agent's decision, never the service, so
/// this is a returned value and never a panic.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackendError {
    /// Human-readable description, safe to log.
    pub message: String,
}

impl std::fmt::Display for BackendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for BackendError {}

/// Produces the model half of a decision.
///
/// Implementations must be usable from several connections at once, which is
/// why `Send + Sync` is part of the contract rather than left to the caller.
pub trait ModelBackend: Send + Sync {
    /// Drafts an intent and its reasoning for `perception`, at `route`.
    ///
    /// `route` is passed rather than the raw importance so a backend cannot
    /// pick its own model: the tier has already been resolved.
    ///
    /// # Errors
    ///
    /// Returns [`BackendError`] if no draft could be produced. The caller turns
    /// that into a failed request for one agent and keeps serving.
    fn draft(&self, route: ModelRoute, perception: &Perception) -> Result<Draft, BackendError>;
}

/// The furthest a world coordinate can be from the origin, in blocks.
///
/// Minecraft's world border sits here, so clamping to it makes the float ->
/// integer conversion below total without discarding any reachable position.
const WORLD_LIMIT_BLOCKS: f64 = 29_999_984.0;

/// How far the stand-in steps toward something it has sighted, in blocks.
const SIGHTED_STEP_BLOCKS: i64 = 2;

/// Converts a continuous world coordinate to the block coordinate an
/// [`Intent::MoveTo`] target carries.
///
/// Clamped and NaN-guarded first: an unbounded float-to-integer cast is exactly
/// the kind of silent nonsense the workspace's clippy gate denies, and the
/// clamp is what makes the narrowing well-defined rather than merely quiet.
#[allow(
    clippy::cast_possible_truncation,
    reason = "the clamp above bounds the value well inside i64, and floor() removes the fraction"
)]
fn block_coord(value: f64) -> i64 {
    if value.is_nan() {
        return 0;
    }
    value.clamp(-WORLD_LIMIT_BLOCKS, WORLD_LIMIT_BLOCKS).floor() as i64
}

/// The deterministic stand-in backend: a rule per trigger, no inference.
///
/// It exists so the routing structure and the contract round-trip can be real
/// before any model credentials are. Swapping in real inference is a different
/// [`ModelBackend`] and one changed construction line in `main`.
#[derive(Clone, Copy, Debug, Default)]
pub struct ScriptedBackend;

impl ModelBackend for ScriptedBackend {
    fn draft(&self, route: ModelRoute, perception: &Perception) -> Result<Draft, BackendError> {
        let draft = match &perception.trigger {
            Trigger::Spoken { from, text } => Draft {
                intent: Intent::Say {
                    text: format!("Hello, {from}."),
                },
                reasoning: format!(
                    "{from} said {text:?}; a greeting is the expected social response"
                ),
            },
            Trigger::Damaged { amount, source } => Draft {
                intent: Intent::Flee {
                    from: source.clone(),
                },
                reasoning: format!(
                    "took {amount} damage from {source} at health {health}; disengage",
                    health = perception.self_state.health
                ),
            },
            Trigger::BlockSighted { block } => {
                let [x, y, z] = perception.self_state.position;
                Draft {
                    intent: Intent::MoveTo {
                        // The stand-in has no spatial reasoning: the perception
                        // reports distance but not direction, so this steps a
                        // fixed offset rather than pretending to know a bearing.
                        // A real backend would be given, and would use, more.
                        target: [
                            block_coord(x) + SIGHTED_STEP_BLOCKS,
                            block_coord(y),
                            block_coord(z),
                        ],
                    },
                    reasoning: format!("{block} sighted nearby; close the distance to reach it"),
                }
            }
            Trigger::GoalReview => Draft {
                intent: Intent::Idle,
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; nothing outranks what is already underway",
                    tier = route.tier()
                ),
            },
        };
        Ok(draft)
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{block_coord, ModelBackend, ScriptedBackend, WORLD_LIMIT_BLOCKS};
    use crate::router::route;
    use loom_contract::{DecisionImportance, Intent, Perception, SelfState, Trigger};

    fn perception(trigger: Trigger, importance: DecisionImportance) -> Perception {
        Perception {
            request_id: "req-1".to_owned(),
            agent_id: "agent-1".to_owned(),
            trigger,
            importance,
            captured_tick: 1,
            self_state: SelfState {
                health: 20.0,
                position: [10.5, 64.0, -8.25],
                inventory_summary: vec![],
            },
            surroundings: vec![],
        }
    }

    #[test]
    fn a_threat_makes_the_body_flee_its_source() {
        let seen = perception(
            Trigger::Damaged {
                amount: 6,
                source: "zombie".to_owned(),
            },
            DecisionImportance::Pivotal,
        );
        let draft = ScriptedBackend
            .draft(route(seen.importance), &seen)
            .expect("the stand-in backend always drafts");
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "zombie".to_owned()
            }
        );
    }

    #[test]
    fn every_trigger_drafts_an_intent_and_a_reason() {
        let triggers = [
            Trigger::Spoken {
                from: "player-steve".to_owned(),
                text: "want to trade?".to_owned(),
            },
            Trigger::Damaged {
                amount: 1,
                source: "cactus".to_owned(),
            },
            Trigger::BlockSighted {
                block: "diamond_ore".to_owned(),
            },
            Trigger::GoalReview,
        ];
        for trigger in triggers {
            let seen = perception(trigger.clone(), DecisionImportance::Routine);
            let draft = ScriptedBackend
                .draft(route(seen.importance), &seen)
                .expect("the stand-in backend always drafts");
            assert!(
                !draft.reasoning.is_empty(),
                "every decision must carry reasoning, {trigger:?} did not"
            );
        }
    }

    #[test]
    fn a_sighting_steps_from_the_body_own_block_position() {
        let seen = perception(
            Trigger::BlockSighted {
                block: "diamond_ore".to_owned(),
            },
            DecisionImportance::Routine,
        );
        let draft = ScriptedBackend
            .draft(route(seen.importance), &seen)
            .expect("the stand-in backend always drafts");
        assert_eq!(
            draft.intent,
            Intent::MoveTo {
                target: [12, 64, -9]
            }
        );
    }

    #[test]
    fn block_coordinates_stay_finite_for_any_float() {
        assert_eq!(block_coord(f64::NAN), 0);
        // Stated as "clamps to the limit" rather than as a number, so the
        // assertion cannot drift from the constant it is about.
        assert_eq!(block_coord(f64::INFINITY), block_coord(WORLD_LIMIT_BLOCKS));
        assert_eq!(
            block_coord(f64::NEG_INFINITY),
            block_coord(-WORLD_LIMIT_BLOCKS)
        );
        assert!(block_coord(f64::INFINITY) > 0);
        assert!(block_coord(f64::NEG_INFINITY) < 0);
        assert_eq!(block_coord(-8.25), -9);
    }
}
