//! The model backend seam, and the deterministic stand-in behind it.
//!
//! A backend is handed a resolved [`ModelRoute`] and a bounded [`Perception`]
//! and returns the two things a model contributes to a decision: an [`Intent`]
//! and the reasoning for it. It does not build the [`loom_contract::Decision`] -
//! provenance stamping belongs to the mind, so a backend cannot misreport which
//! model answered.
//!
//! [`ModelBackend::draft`] is async because the interesting implementations talk
//! to something over a network. It is an `impl Future` rather than a boxed one:
//! the mind is generic over its backend, never `dyn`, so nothing needs erasing
//! and no allocation per decision is needed. `+ Send` is part of the signature
//! because the service spawns a task per connection.
//!
//! [`ScriptedBackend`] is the deterministic stand-in. It is deliberately not a
//! fake in the test sense: it runs in the shipped service - it is what
//! `LOOM_COGNITION_BACKEND=scripted` selects - so the path under test is the real
//! one. What it lacks is inference, not structure. It maps a trigger to a
//! sensible intent by rule and has no spatial reasoning at all, which its own
//! comments say plainly rather than implying a competence it does not have, and
//! it names itself [`SCRIPTED_MODEL_ID`] in provenance rather than borrowing the
//! name of a model that never saw the perception.

use std::future::Future;

use loom_contract::{Intent, Perception, Trigger};

use crate::config::{bounded, ConfigError, Env};
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
///
/// The message reaches the body in a refusal frame and the server log, so it is
/// held to the same rule as any other outside-facing string: it names what went
/// wrong and never carries a credential or the prompt. Building one from provider
/// output means passing that output through [`crate::config::bounded`] first.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackendError {
    /// Human-readable description, safe to log.
    pub message: String,
}

impl BackendError {
    /// A failure described by `message`.
    #[must_use]
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
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
    /// pick its own model: the tier has already been resolved, and the model id
    /// to ask for is on the route.
    ///
    /// # Errors
    ///
    /// Returns [`BackendError`] if no draft could be produced. The caller turns
    /// that into a failed request for one agent and keeps serving.
    fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> impl Future<Output = Result<Draft, BackendError>> + Send;
}

/// Environment variable naming which backend answers.
pub const BACKEND_VAR: &str = "LOOM_COGNITION_BACKEND";

/// The model id [`ScriptedBackend`] stamps on its decisions.
///
/// Not the name of any model, on purpose. Provenance is the research record's
/// claim about what produced a decision, so the stand-in has to be identifiable
/// as the stand-in - a scripted decision labelled with a real model's name would
/// be a false entry in that record, and nothing downstream could tell.
pub const SCRIPTED_MODEL_ID: &str = "scripted-stand-in";

/// Which backend the runtime answers with.
///
/// The default is [`BackendChoice::Scripted`], so a checkout with no credentials
/// configured runs. An unrecognised value is a startup failure rather than a
/// quiet fall back to the default: an operator who asked for a real model and
/// misspelled it should be told, not served scripted decisions that look real in
/// the log until someone reads the provenance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BackendChoice {
    /// The deterministic stand-in. No credentials, no network, no inference.
    Scripted,
    /// The `OpenAI` API. See [`crate::openai`].
    OpenAi,
}

impl BackendChoice {
    /// What [`BACKEND_VAR`] means when it is not set.
    pub const DEFAULT: &'static str = "scripted";

    /// The values [`BackendChoice::from_env`] accepts, for error messages and
    /// for the operator documentation to quote.
    pub const ACCEPTED: &'static str = "'scripted' or 'openai'";

    /// Reads the choice from `env`.
    ///
    /// # Errors
    ///
    /// [`ConfigError::NotAChoice`] if [`BACKEND_VAR`] names something else, or
    /// [`ConfigError::Blank`] if it is set to nothing.
    pub fn from_env<F>(env: &Env<F>) -> Result<Self, ConfigError>
    where
        F: Fn(&str) -> Option<String>,
    {
        let raw = env.or_default(BACKEND_VAR, Self::DEFAULT)?;
        match raw.to_ascii_lowercase().as_str() {
            "scripted" => Ok(Self::Scripted),
            "openai" => Ok(Self::OpenAi),
            _ => Err(ConfigError::NotAChoice {
                var: BACKEND_VAR,
                value: bounded(&raw),
                accepted: Self::ACCEPTED,
            }),
        }
    }

    /// The choice's name, as configuration spells it.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Scripted => "scripted",
            Self::OpenAi => "openai",
        }
    }
}

/// The furthest a world coordinate can be from the origin, in blocks.
///
/// Minecraft's world border sits here, so clamping to it makes the float ->
/// integer conversion below total without discarding any reachable position.
const WORLD_LIMIT_BLOCKS: f64 = 29_999_984.0;

/// How far the stand-in steps toward something it has sighted, in blocks.
const SIGHTED_STEP_BLOCKS: i64 = 2;

/// The fraction of reserve at or below which the stand-in rests on a goal review.
///
/// Well clear of exhaustion, because a goal review is the *only* moment this
/// backend can act on the need: the margin has to cover a whole review cadence
/// plus the stagger, or an agent would notice its reserve for the first time after
/// it had already run out. It is a rule of thumb and nothing more - a real backend
/// weighs resting against whatever else the agent is doing.
const RESTING_ENERGY: f64 = 0.35;

/// Turns a reserve fraction into the percentage a reader of the reasoning sees.
///
/// The reasoning is the one field carried verbatim to an operator's screen, so a
/// reserve stated in it is read by eye rather than counted. A reserve is derived
/// from a tick count and most of its values do not divide: an in-world run printed
/// `the energy reserve is down to 0.3198888888888889`, which is thirteen digits of
/// noise about a number the display beside it renders as `32/100`.
const PERCENT: f64 = 100.0;

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
pub(crate) fn block_coord(value: f64) -> i64 {
    if value.is_nan() {
        return 0;
    }
    value.clamp(-WORLD_LIMIT_BLOCKS, WORLD_LIMIT_BLOCKS).floor() as i64
}

/// The deterministic stand-in backend: a rule per trigger, no inference.
///
/// It exists so the routing structure and the contract round-trip can be real
/// without credentials, which is what keeps the hermetic test suite and CI's
/// cross-runtime round trip honest. Selecting real inference instead is
/// `LOOM_COGNITION_BACKEND=openai`.
#[derive(Clone, Copy, Debug, Default)]
pub struct ScriptedBackend;

impl ScriptedBackend {
    /// The scripted rule, without the async wrapper.
    ///
    /// Kept synchronous and separate so the rules can be read and tested as what
    /// they are - a table from trigger to intent - with no runtime involved.
    #[must_use]
    pub fn draft_now(route: &ModelRoute, perception: &Perception) -> Draft {
        match &perception.trigger {
            Trigger::Spoken { from, text } => Draft {
                intent: Intent::Say {
                    text: format!("Hello, {from}."),
                },
                reasoning: format!(
                    "{from} said {text:?}; a greeting is the expected social response"
                ),
            },
            // The health is rounded for the same reason the reserve below is: this
            // reasoning is the one field carried verbatim to an operator's screen,
            // and a health that is an `f32` in world widens to an `f64` that does
            // not divide - four hearts off a full villager prints as
            // `19.899999618530273`. To a tenth, which is a half-heart, and the exact
            // value stays in the perception where anything counting it will look.
            // The damage `amount` is an integer and needs no such help.
            //
            // `Flee` names a ref rather than a name, and a damage trigger carries
            // only the name of what hurt the agent - so the stand-in reads the ref
            // off the nearest observation that has one. Falling back to the damage
            // `source` would put a creature's name where the body expects a handle
            // it issued, which the body refuses; naming nothing when nothing in
            // sight is alive is the honest answer.
            Trigger::Damaged { amount, source } => match nearest_ref(perception) {
                Some(handle) => Draft {
                    intent: Intent::Flee { from: handle },
                    reasoning: format!(
                        "took {amount} damage from {source} at health {health:.1}; disengage",
                        health = perception.self_state.health
                    ),
                },
                None => Draft {
                    intent: Intent::Idle,
                    reasoning: format!(
                        "took {amount} damage from {source} at health {health:.1}, and nothing in sight can be named to flee from",
                        health = perception.self_state.health
                    ),
                },
            },
            Trigger::BlockSighted { block } => {
                let [x, y, z] = perception.self_state.position;
                Draft {
                    intent: Intent::MoveTo {
                        // The stand-in has no spatial reasoning. The perception
                        // now carries a bearing for every observation, but the
                        // trigger names a block rather than an observation, so
                        // there is nothing here to read one off; this steps a
                        // fixed offset rather than inventing a direction.
                        // A real backend is given, and uses, the whole payload.
                        target: [
                            block_coord(x) + SIGHTED_STEP_BLOCKS,
                            block_coord(y),
                            block_coord(z),
                        ],
                    },
                    reasoning: format!("{block} sighted nearby; close the distance to reach it"),
                }
            }
            // The one survival rule the stand-in has. An agent whose reserve runs
            // out dies for good, so a low reserve outranks every other goal; the
            // cost of resting is only the cycle it spends.
            Trigger::GoalReview if perception.self_state.energy <= RESTING_ENERGY => Draft {
                intent: Intent::Rest,
                reasoning: format!(
                    "the energy reserve is down to {share:.0}% of capacity; resting outranks every other goal",
                    share = perception.self_state.energy * PERCENT
                ),
            },
            Trigger::GoalReview => Draft {
                intent: Intent::Idle,
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; nothing outranks what is already underway",
                    tier = route.tier()
                ),
            },
        }
    }
}

impl ModelBackend for ScriptedBackend {
    fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
        // Ready rather than `async fn`: there is nothing to await, and a future
        // that is already complete says so to its caller.
        std::future::ready(Ok(Self::draft_now(route, perception)))
    }
}

/// The ref of the nearest observed living thing, if the scene holds one.
///
/// The body already sorted `surroundings` nearest-first, and only a living thing
/// carries a ref, so the first ref in the list is the nearest thing that can be
/// named. `None` says the scene holds nothing nameable rather than nothing at all.
fn nearest_ref(perception: &Perception) -> Option<String> {
    perception
        .surroundings
        .iter()
        .find_map(|seen| seen.entity_ref.clone())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        block_coord, BackendChoice, ModelBackend, ScriptedBackend, BACKEND_VAR, RESTING_ENERGY,
        WORLD_LIMIT_BLOCKS,
    };
    use crate::config::{ConfigError, Env};
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{
        Bearing, DayPhase, DecisionImportance, Intent, Observation, Perception, SelfState, Trigger,
        WorldState,
    };

    fn router() -> Router {
        Router::new(ModelCatalog::uniform("scripted-stand-in"))
    }

    /// A perception from an agent with a full reserve, so a test that is not about
    /// energy is never accidentally about it.
    fn perception(trigger: Trigger, importance: DecisionImportance) -> Perception {
        Perception {
            request_id: "req-1".to_owned(),
            agent_id: "agent-1".to_owned(),
            trigger,
            importance,
            captured_tick: 1,
            self_state: SelfState {
                health: 20.0,
                energy: 1.0,
                position: [10.5, 64.0, -8.25],
                inventory_summary: vec![],
                world: WorldState {
                    day_phase: DayPhase::Day,
                    is_day: true,
                    ticks_until_nightfall: 6_000,
                },
            },
            surroundings: vec![],
            surroundings_omitted: 0,
        }
    }

    fn env_of(
        pairs: &'static [(&'static str, &'static str)],
    ) -> Env<impl Fn(&str) -> Option<String>> {
        Env::new(move |name| {
            pairs
                .iter()
                .find(|(key, _)| *key == name)
                .map(|(_, value)| (*value).to_owned())
        })
    }

    /// The same perception, with one living thing in view carrying `handle`.
    ///
    /// A flee names a ref rather than a species, so a scene with nothing
    /// ref-carrying in it is a scene the stand-in has nothing to flee from. That
    /// makes the ref part of the setup of any test about damage rather than
    /// something such a test can leave out.
    fn in_view(mut seen: Perception, what: &str, handle: &str) -> Perception {
        seen.surroundings.push(Observation {
            what: what.to_owned(),
            distance: 3.0,
            direction: Bearing::North,
            vertical_offset: 0.0,
            visible: true,
            entity_ref: Some(handle.to_owned()),
        });
        seen
    }

    /// The same perception, from an agent whose reserve is down to `energy`.
    fn drained(seen: Perception, energy: f64) -> Perception {
        Perception {
            self_state: SelfState {
                energy,
                ..seen.self_state
            },
            ..seen
        }
    }

    #[tokio::test]
    async fn a_threat_makes_the_body_flee_the_nearest_thing_it_can_name() {
        // The damage trigger names the species that hurt the agent; `Flee` names a
        // ref the body issued. Handing the species name back would be a handle the
        // body never minted, so the stand-in reads the ref off the scene instead.
        let seen = in_view(
            perception(
                Trigger::Damaged {
                    amount: 6,
                    source: "zombie".to_owned(),
                },
                DecisionImportance::Pivotal,
            ),
            "zombie",
            "e1",
        );
        let draft = ScriptedBackend
            .draft(&router().route(seen.importance), &seen)
            .await
            .expect("the stand-in backend always drafts");
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
    }

    #[test]
    fn damage_with_nothing_nameable_in_sight_idles_rather_than_inventing_a_ref() {
        // The failure this rules out is the tempting one: falling back to the damage
        // `source` would put a species name where the body expects a handle it
        // issued, and the body would refuse it - so the agent would have spent a
        // pivotal decision on an action that could never be carried out.
        let seen = perception(
            Trigger::Damaged {
                amount: 6,
                source: "zombie".to_owned(),
            },
            DecisionImportance::Pivotal,
        );
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(draft.intent, Intent::Idle);
        assert!(
            draft.reasoning.contains("nothing in sight can be named"),
            "the reasoning must say why it did not run, got {reasoning:?}",
            reasoning = draft.reasoning
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
        let router = router();
        for trigger in triggers {
            let seen = perception(trigger.clone(), DecisionImportance::Routine);
            let draft = ScriptedBackend::draft_now(&router.route(seen.importance), &seen);
            assert!(
                !draft.reasoning.is_empty(),
                "every decision must carry reasoning, {trigger:?} did not"
            );
        }
    }

    #[test]
    fn a_goal_review_on_a_full_reserve_does_not_rest() {
        let seen = perception(Trigger::GoalReview, DecisionImportance::Routine);
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(draft.intent, Intent::Idle);
    }

    #[test]
    fn a_goal_review_on_a_low_reserve_rests_instead() {
        // The survival rule, at the threshold and below it. An agent that only
        // rested strictly below the threshold would skip the review that lands
        // exactly on it, and reviews are the only chance it gets.
        for energy in [RESTING_ENERGY, RESTING_ENERGY / 2.0, 0.0] {
            let seen = drained(
                perception(Trigger::GoalReview, DecisionImportance::Routine),
                energy,
            );
            let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
            assert_eq!(
                draft.intent,
                Intent::Rest,
                "an agent at {energy} of its reserve must rest, not idle"
            );
        }
    }

    #[test]
    fn the_resting_reasoning_states_the_reserve_as_a_reader_reads_it() {
        // The reasoning is the one field an operator is shown verbatim, and a
        // reserve derived from a tick count rarely divides: this exact reading
        // printed as `0.3198888888888889` on a boss bar in world, next to a
        // display of its own that said `32/100`.
        let seen = drained(
            perception(Trigger::GoalReview, DecisionImportance::Routine),
            0.319_888_888_888_888_9,
        );

        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);

        assert_eq!(
            draft.reasoning,
            "the energy reserve is down to 32% of capacity; resting outranks every other goal"
        );
    }

    #[test]
    fn the_damage_reasoning_states_the_health_as_a_reader_reads_it() {
        // The other number that reaches an operator's screen verbatim. A body's
        // health is an `f32` in world, so a reading that is not exactly
        // representable widens to thirteen digits of noise: four hearts off a full
        // villager is `19.899999618530273` as an `f64`.
        let mut seen = perception(
            Trigger::Damaged {
                amount: 4,
                source: "zombie".to_owned(),
            },
            DecisionImportance::Pivotal,
        );
        seen.self_state.health = f64::from(19.9_f32);
        let seen = in_view(seen, "zombie", "e1");

        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);

        assert_eq!(
            draft.reasoning,
            "took 4 damage from zombie at health 19.9; disengage"
        );
    }

    #[test]
    fn a_low_reserve_does_not_override_a_threat() {
        // Resting is a goal-review rule, not a reflex: an exhausted agent being
        // eaten still runs. Losing this would have a body sit down under attack.
        let seen = drained(
            in_view(
                perception(
                    Trigger::Damaged {
                        amount: 6,
                        source: "zombie".to_owned(),
                    },
                    DecisionImportance::Pivotal,
                ),
                "zombie",
                "e1",
            ),
            0.0,
        );
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
    }

    #[test]
    fn a_sighting_steps_from_the_body_own_block_position() {
        let seen = perception(
            Trigger::BlockSighted {
                block: "diamond_ore".to_owned(),
            },
            DecisionImportance::Routine,
        );
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
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

    #[test]
    fn an_unconfigured_runtime_answers_with_the_stand_in() {
        let env = env_of(&[]);
        assert_eq!(
            BackendChoice::from_env(&env).unwrap(),
            BackendChoice::Scripted
        );
    }

    #[test]
    fn a_backend_can_be_named_in_any_case() {
        let named = |value| BackendChoice::from_env(&env_of(value)).unwrap();
        assert_eq!(named(&[(BACKEND_VAR, "scripted")]), BackendChoice::Scripted);
        assert_eq!(named(&[(BACKEND_VAR, "SCRIPTED")]), BackendChoice::Scripted);
        assert_eq!(named(&[(BACKEND_VAR, "openai")]), BackendChoice::OpenAi);
        assert_eq!(named(&[(BACKEND_VAR, "OpenAI")]), BackendChoice::OpenAi);
    }

    #[test]
    fn a_misspelled_backend_stops_the_service_rather_than_falling_back() {
        let env = env_of(&[(BACKEND_VAR, "opeanai")]);
        assert_eq!(
            BackendChoice::from_env(&env),
            Err(ConfigError::NotAChoice {
                var: BACKEND_VAR,
                value: "opeanai".to_owned(),
                accepted: BackendChoice::ACCEPTED,
            })
        );
    }

    #[test]
    fn a_backend_choice_names_itself_as_configuration_spells_it() {
        assert_eq!(BackendChoice::Scripted.label(), BackendChoice::DEFAULT);
        assert_eq!(BackendChoice::OpenAi.label(), "openai");
    }
}
