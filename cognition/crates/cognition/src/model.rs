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

use loom_contract::{Intent, ObservationKind, Perception, Trigger};
use serde::Serialize;

use crate::config::{bounded, bounded_to, ConfigError, Env};
use crate::router::ModelRoute;

/// Version of the bounded machine-readable failure detail carried beside a
/// cognition refusal.
pub const BACKEND_FAILURE_SCHEMA_VERSION: &str = "loom-cognition-failure/1";

/// Which stage turned one model request into no decision.
///
/// These values are intentionally about Loom's mapping rather than provider
/// prose. The provider's message can quote a prompt and is never retained.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BackendFailureMapping {
    /// Loom could not encode its own provider request.
    RequestEncoding,
    /// Loom's configured request-byte ceiling refused the encoded request.
    RequestBound,
    /// No response arrived before the provider timeout.
    ProviderTimeout,
    /// A connection to the configured provider could not be established.
    ProviderConnection,
    /// A provider response body could not be read.
    ProviderResponseRead,
    /// Another transport failure ended the request.
    ProviderTransport,
    /// The provider rejected the credential.
    ProviderAuthentication,
    /// The provider did not recognize the routed model.
    ProviderModel,
    /// The provider rate-limited the request.
    ProviderRateLimit,
    /// The provider rejected the request shape or parameters.
    ProviderRequest,
    /// The provider returned a server error.
    ProviderServer,
    /// The provider returned another non-success HTTP status.
    ProviderHttp,
    /// The provider response crossed Loom's byte ceiling.
    ProviderResponseBound,
    /// A success response was not a Chat Completions response.
    ProviderResponseParse,
    /// Required provider usage was absent.
    ProviderUsageMissing,
    /// Provider usage counts contradicted each other.
    ProviderUsageInconsistent,
    /// Provider usage could not be made durable.
    ProviderUsageJournal,
    /// A Chat Completions response contained no choice.
    ProviderAnswerMissing,
    /// The model used the Chat Completions refusal field.
    ProviderSafetyRefusal,
    /// The model exhausted the configured completion-token ceiling.
    ProviderAnswerCutOff,
    /// The model returned no answer text.
    ProviderAnswerEmpty,
    /// The answer text did not decode as a Loom decision.
    ProviderDecisionParse,
}

/// The bounded structural shape observed at the provider boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BackendResponseShape {
    /// Loom rejected its own request before sending it.
    NotSent,
    /// The request ended without HTTP response headers.
    NoHttpResponse,
    /// HTTP headers arrived but the response body could not be read.
    UnreadableHttpBody,
    /// The HTTP response body crossed Loom's byte ceiling.
    OversizedHttpBody,
    /// A non-success response decoded as the provider's error envelope.
    ApiErrorEnvelope,
    /// The response was JSON, but not the expected provider object.
    OtherJson,
    /// The response was not complete valid JSON.
    MalformedJson,
    /// The response decoded as a Chat Completions object.
    ChatCompletion,
}

/// Safe machine-readable context for one backend failure.
///
/// This accompanies, but never replaces, [`BackendError::message`]. It contains
/// only bounded model/provider identifiers and structural classifications: no
/// prompt, answer text, credential, request id, provider request id, or URL.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendFailure {
    /// Version of this diagnostic object.
    pub schema_version: &'static str,
    /// Backend that produced the classification.
    pub backend: &'static str,
    /// Routed model for this one request.
    pub model: String,
    /// HTTP status, when response headers arrived.
    pub http_status: Option<u16>,
    /// Provider error type, when a safe error envelope supplied one.
    pub provider_type: Option<String>,
    /// Provider error code, when a safe error envelope supplied one.
    pub provider_code: Option<String>,
    /// Structural response shape observed by Loom.
    pub response_shape: BackendResponseShape,
    /// Loom stage that mapped the observation to a failed decision.
    pub mapping: BackendFailureMapping,
}

impl BackendFailure {
    /// Builds bounded diagnostic context for an `OpenAI` backend failure.
    #[must_use]
    pub fn openai(
        model: &str,
        http_status: Option<u16>,
        provider_type: Option<&str>,
        provider_code: Option<&str>,
        response_shape: BackendResponseShape,
        mapping: BackendFailureMapping,
    ) -> Self {
        Self {
            schema_version: BACKEND_FAILURE_SCHEMA_VERSION,
            backend: "openai",
            model: bounded_to(model, 100),
            http_status,
            provider_type: provider_type.map(bounded),
            provider_code: provider_code.map(bounded),
            response_shape,
            mapping,
        }
    }
}

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
    /// Optional bounded machine-readable failure context.
    failure: Option<Box<BackendFailure>>,
}

impl BackendError {
    /// A failure described by `message`.
    #[must_use]
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            failure: None,
        }
    }

    /// A failure with bounded context suitable for a private operator
    /// diagnostic.
    #[must_use]
    pub fn classified(message: impl Into<String>, failure: BackendFailure) -> Self {
        Self {
            message: message.into(),
            failure: Some(Box::new(failure)),
        }
    }

    /// The bounded classification, when the backend supplied one.
    #[must_use]
    pub fn failure(&self) -> Option<&BackendFailure> {
        self.failure.as_deref()
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

/// The vanilla food level at or below which the stand-in seeks a meal.
///
/// Well clear of exhaustion, because a goal review is the *only* moment this
/// backend can act on the need: the margin has to cover a whole review cadence
/// plus the stagger, or an agent would notice its reserve for the first time after
/// it had already run out. It is a rule of thumb and nothing more - a real backend
/// weighs resting against whatever else the agent is doing.
const LOW_FOOD_LEVEL: i64 = 7;

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
            // A threat sighting carries its own ref, so unlike the damage rule
            // above there is nothing to look up: the handle the body issued for
            // this one creature travels with the trigger, and it is the only handle
            // a `Flee` naming this threat may use.
            Trigger::ThreatSighted {
                threat, entity_ref, ..
            } => Draft {
                intent: Intent::Flee {
                    from: entity_ref.clone(),
                },
                reasoning: format!("{threat} hunts my kind and is in view; retreat from it"),
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
            // The one survival rule the stand-in has. Vanilla hunger is reported
            // directly, so a low food level outranks every other routine goal.
            //
            // Eating is the only thing that refills a reserve, so the stand-in
            // reaches for the inventory first and rests only when it is empty. A
            // rest buys nothing - it holds a position and restores nothing - so
            // that branch states what it is doing rather than claiming a recovery.
            //
            // Which stack it names is deliberately the first one the body
            // reported. The stand-in cannot tell food from a pickaxe: it has only
            // the body's own description, and matching words against it would be a
            // table of vanilla item semantics living in the wrong runtime. So it
            // names what it was told about first and lets the body refuse what is
            // not food, which is a refusal the body records. A demo therefore
            // provisions the food in the body's first occupied slot.
            Trigger::GoalReview if perception.self_state.food <= LOW_FOOD_LEVEL => {
                let food = perception.self_state.food;
                let saturation = perception.self_state.saturation;
                match perception.self_state.inventory_summary.first() {
                    Some(stack) => Draft {
                        intent: Intent::Eat {
                            slot_ref: stack.slot_ref.clone(),
                        },
                        reasoning: format!(
                            "food is {food} of 20 with saturation {saturation:.1}; eating the {what} may restore it",
                            what = stack.what
                        ),
                    },
                    None => Draft {
                        intent: Intent::Rest,
                        reasoning: format!(
                            "food is {food} of 20 with saturation {saturation:.1} and there is nothing to eat; holding position"
                        ),
                    },
                }
            }
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
/// carries `kind = entity`, so the first entity in the list is the nearest thing
/// that can be fled from. `None` says the scene holds no living target rather
/// than no actionable blocks or dropped items.
fn nearest_ref(perception: &Perception) -> Option<String> {
    perception
        .surroundings
        .iter()
        .find(|seen| seen.kind == ObservationKind::Entity)
        .map(|seen| seen.target_ref.clone())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        block_coord, BackendChoice, ModelBackend, ScriptedBackend, BACKEND_VAR, LOW_FOOD_LEVEL,
        WORLD_LIMIT_BLOCKS,
    };
    use crate::config::{ConfigError, Env};
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{
        Bearing, DayPhase, DecisionImportance, Intent, InventoryStack, Observation,
        ObservationKind, Perception, SelfState, Trigger, WorldState,
    };

    fn router() -> Router {
        Router::new(ModelCatalog::uniform("scripted-stand-in"))
    }

    /// A well-fed perception, so a test that is not about hunger never becomes one.
    fn perception(trigger: Trigger, importance: DecisionImportance) -> Perception {
        Perception {
            request_id: "req-1".to_owned(),
            agent_id: "agent-1".to_owned(),
            trigger,
            importance,
            captured_tick: 1,
            self_state: SelfState {
                health: 20.0,
                food: 20,
                saturation: 5.0,
                position: [10.5, 64.0, -8.25],
                inventory_summary: vec![],
                crafting_options: vec![],
                crafting_options_omitted: 0,
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
            kind: ObservationKind::Entity,
            what: what.to_owned(),
            distance: 3.0,
            direction: Bearing::North,
            vertical_offset: 0.0,
            visible: true,
            target_ref: handle.to_owned(),
            count: None,
        });
        seen
    }

    /// The same perception, from an agent whose food level is [food].
    fn hungry(seen: Perception, food: i64) -> Perception {
        Perception {
            self_state: SelfState {
                food,
                saturation: 0.0,
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
    fn a_goal_review_on_a_full_food_bar_does_not_rest() {
        let seen = perception(Trigger::GoalReview, DecisionImportance::Routine);
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(draft.intent, Intent::Idle);
    }

    #[test]
    fn a_goal_review_on_low_food_rests_instead() {
        // The survival rule, at the threshold and below it. An agent that only
        // rested strictly below the threshold would skip the review that lands
        // exactly on it, and reviews are the only chance it gets.
        for food in [LOW_FOOD_LEVEL, LOW_FOOD_LEVEL / 2, 0] {
            let seen = hungry(
                perception(Trigger::GoalReview, DecisionImportance::Routine),
                food,
            );
            let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
            assert_eq!(
                draft.intent,
                Intent::Rest,
                "an agent at food level {food} must rest, not idle"
            );
        }
    }

    #[test]
    fn the_resting_reasoning_states_vanilla_hunger() {
        let seen = hungry(
            perception(Trigger::GoalReview, DecisionImportance::Routine),
            6,
        );

        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);

        assert_eq!(
            draft.reasoning,
            "food is 6 of 20 with saturation 0.0 and there is nothing to eat; holding position"
        );
    }

    #[test]
    fn low_food_eats_what_it_is_carrying_rather_than_resting() {
        let mut seen = hungry(
            perception(Trigger::GoalReview, DecisionImportance::Routine),
            LOW_FOOD_LEVEL,
        );
        seen.self_state.inventory_summary = vec![
            InventoryStack {
                slot_ref: "i1".to_owned(),
                what: "bread".to_owned(),
                count: 3,
            },
            InventoryStack {
                slot_ref: "i2".to_owned(),
                what: "iron pickaxe".to_owned(),
                count: 1,
            },
        ];

        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);

        assert_eq!(
            draft.intent,
            Intent::Eat {
                slot_ref: "i1".to_owned()
            }
        );
        assert_eq!(
            draft.reasoning,
            "food is 7 of 20 with saturation 0.0; eating the bread may restore it"
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
    fn a_threat_sighting_flees_the_ref_the_trigger_carries() {
        // Unlike the damage rule, this one must not go looking: the trigger names
        // the exact creature the body issued a handle for, and the nearest thing in
        // sight may be a different one. Reading the surroundings here would answer a
        // sighting of the thing behind the agent by fleeing the thing in front of it.
        let seen = in_view(
            perception(
                Trigger::ThreatSighted {
                    threat: "zombie".to_owned(),
                    entity_ref: "e2".to_owned(),
                    distance: 4.0,
                    direction: Bearing::South,
                    visible: true,
                    reachable: true,
                },
                DecisionImportance::Pivotal,
            ),
            "pillager",
            "e1",
        );

        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);

        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e2".to_owned()
            }
        );
    }

    #[test]
    fn low_food_does_not_override_a_threat() {
        // Resting is a goal-review rule, not a reflex: a starving agent being
        // eaten still runs. Losing this would have a body sit down under attack.
        let seen = hungry(
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
            0,
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
