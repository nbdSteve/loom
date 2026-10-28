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

use loom_contract::{
    Bearing, EquipmentDestination, GoalChoice, GoalDisposition, GoalReason, GoalStepResult, Intent,
    Observation, ObservationKind, Perception, Trigger, MAX_GOAL_STEPS_WITHOUT_PROGRESS,
};
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
    /// The explicit goal choice, carried into the decision unchanged; `None`
    /// leaves any held goal as it is.
    pub goal: Option<GoalChoice>,
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
    /// The path rehearsal's deterministic exerciser. See [`crate::exerciser`].
    PathExerciser,
}

impl BackendChoice {
    /// What [`BACKEND_VAR`] means when it is not set.
    pub const DEFAULT: &'static str = "scripted";

    /// The values [`BackendChoice::from_env`] accepts, for error messages and
    /// for the operator documentation to quote.
    pub const ACCEPTED: &'static str = "'scripted', 'scripted-paths' or 'openai'";

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
            "scripted-paths" => Ok(Self::PathExerciser),
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
            Self::PathExerciser => "scripted-paths",
        }
    }
}

/// The vanilla food level at or below which the stand-in seeks a meal.
///
/// Well clear of exhaustion, because a goal review is the *only* moment this
/// backend can act on the need: the margin has to cover a whole review cadence
/// plus the stagger, or an agent would notice its reserve for the first time after
/// it had already run out. It is a rule of thumb and nothing more - a real backend
/// weighs resting against whatever else the agent is doing.
const LOW_FOOD_LEVEL: i64 = crate::offered::HUNGRY_FOOD_LEVEL;

/// The item the scripted stand-in prefers its placeholder goal to be achieved by holding.
pub const SCRIPTED_SUCCESS_ITEM: &str = "item.minecraft.stick";
/// The stand-in's step, worded as its own for the same reason.
pub const SCRIPTED_STEP: &str = "scripted stand-in step: act on the trigger in front of me";

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
        if let Some(sheltering) = Self::shelter_now(perception) {
            return sheltering;
        }
        let mut draft = Self::step_now(route, perception);
        draft.goal = Self::goal_now(perception, &draft.intent);
        draft
    }

    /// The stand-in's shelter as night nears: at a goal review with no hunter upon it, the one
    /// shelter goal - set in place of what it holds, for the reason that goal's evidence supports -
    /// stepped by what its cover names (the descent, the closing with a solid block in hand, the
    /// equip that readies one, the gathering of a closer, or a walk onto natural cover), completed
    /// once sealed and stayed in. `None` where no shelter is held or offered.
    fn shelter_now(perception: &Perception) -> Option<Draft> {
        let offered = crate::offered::OfferedRefs::of(perception);
        if !perception.trigger.reviews_goal() || !offered.immediate.is_empty() {
            return None;
        }
        let shelter = &offered.shelter;
        let held = perception.goal.as_ref();
        let holding = held.is_some_and(|goal| goal.shelter);
        let progress = held.and_then(|goal| goal.progress.as_ref());
        let met = progress.is_some_and(|seen| seen.condition_met);
        let step_result = match progress {
            Some(seen) if !seen.condition_advanced && !seen.step_advanced => {
                GoalStepResult::NoProgress
            }
            _ if met => GoalStepResult::Done,
            _ => GoalStepResult::Progressing,
        };
        let choice = |disposition, shelter: Option<bool>, reason| GoalChoice {
            discover: None,
            disposition,
            success_item: None,
            success_count: None,
            step: None,
            step_result: (disposition != GoalDisposition::Adopt).then_some(step_result),
            shelter,
            feeds: None,
            reason,
        };
        let answer = |intent, goal, why: &str| Draft {
            intent,
            reasoning: format!("scripted stand-in: {why}"),
            goal,
        };
        // Sealed in with nothing held, it stays: rest is a fine answer, and digging out is no step.
        if !holding && held.is_none() && shelter.sealed() && shelter.night_near {
            return Some(answer(
                Intent::Rest,
                None,
                "sealed in as night nears, so stay",
            ));
        }
        if holding && met {
            return Some(answer(
                Intent::Rest,
                Some(choice(GoalDisposition::Complete, None, None)),
                "sealed in: complete the shelter and stay in it",
            ));
        }
        if !holding && !shelter.offered() {
            return None;
        }
        let stalled = progress
            .is_some_and(|seen| seen.steps_without_progress >= MAX_GOAL_STEPS_WITHOUT_PROGRESS);
        // A shelter its last three steps advanced nothing is ended as stale, never continued into
        // the body's refusal at the stall bound.
        if holding && stalled {
            return Some(answer(
                Intent::Idle,
                Some(choice(
                    GoalDisposition::Abandon,
                    None,
                    Some(GoalReason::Stale),
                )),
                "my shelter advanced nothing for three steps",
            ));
        }
        let Some(intent) = Self::shelter_step(&offered).filter(|intent| offered.admits(intent))
        else {
            // A shelter no step of its cover can advance cannot be met now.
            return holding.then(|| {
                answer(
                    Intent::Idle,
                    Some(choice(
                        GoalDisposition::Abandon,
                        None,
                        Some(GoalReason::Impossible),
                    )),
                    "nothing my cover offers can close this shelter now",
                )
            });
        };
        let goal = match held {
            Some(_) if holding => choice(GoalDisposition::Continue, None, None),
            None => choice(GoalDisposition::Adopt, Some(true), None),
            // Night nears: the held goal ends for what its evidence states, and a shelter takes its place.
            Some(_) => choice(
                GoalDisposition::Replace,
                Some(true),
                Some(if met {
                    GoalReason::Completed
                } else if stalled {
                    GoalReason::Stale
                } else {
                    GoalReason::Unsafe
                }),
            ),
        };
        Some(answer(
            intent,
            Some(goal),
            "night nears: take the step my cover names toward a shelter",
        ))
    }

    /// The step the cover in `offered` names first: the descent underfoot, the closing with the
    /// solid block in hand, the equip that puts one there, the gathering of a closer, or a walk
    /// onto a more enclosed site.
    fn shelter_step(offered: &crate::offered::OfferedRefs) -> Option<Intent> {
        let shelter = &offered.shelter;
        let descend = shelter.descend.clone().map(|block| Intent::Dig { block });
        let placed = || {
            shelter
                .closings
                .first()
                .zip(shelter.solid_held.first())
                .map(|((block, face), item)| Intent::Place {
                    block: block.clone(),
                    item: item.clone(),
                    face: *face,
                })
        };
        let readied = || {
            shelter.to_equip().first().map(|item| Intent::Equip {
                item: item.clone(),
                destination: EquipmentDestination::Hand,
            })
        };
        let gathered = || {
            shelter.gathers.first().map(|handle| {
                if offered.dig_blocks.contains(handle) {
                    Intent::Dig {
                        block: handle.clone(),
                    }
                } else {
                    Intent::Collect {
                        item: handle.clone(),
                        approach: !offered.pickup_items.contains(handle),
                    }
                }
            })
        };
        let walked = || {
            shelter
                .sites
                .first()
                .map(|onto| Intent::MoveTo { onto: onto.clone() })
        };
        descend
            .or_else(placed)
            .or_else(readied)
            .or_else(gathered)
            .or_else(walked)
    }

    /// The scripted goal rule: adopt one placeholder goal of holding the fewest an objective asks
    /// of what the block it digs can drop, continue it while it digs, revise it for more of its own
    /// item once met or replace it as completed by another, and complete it when sight offers none.
    ///
    /// It exists so the goal round trip - choice, ledger, carried summary, next choice, and the
    /// follow-up review a settled step owes - is exercised deterministically without credentials.
    /// A fixed rule is not agency, which is why its wording says it is the stand-in's. It reads the
    /// body's progress evidence only as far as the ledger requires: a step that changed nothing is
    /// judged `noProgress`, a goal at the no-progress bound or with nothing left to dig is
    /// abandoned rather than continued into the body's refusal, and no goal is ever adopted beside
    /// an idle step or for a count nothing in sight could give.
    fn goal_now(perception: &Perception, intent: &Intent) -> Option<GoalChoice> {
        if !perception.trigger.reviews_goal()
            || crate::offered::OfferedRefs::of(perception).survives(intent)
        {
            return None;
        }
        let step = |disposition, step_result| GoalChoice {
            discover: None,
            disposition,
            success_item: None,
            success_count: None,
            step: Some(SCRIPTED_STEP.to_owned()),
            step_result,
            shelter: None,
            feeds: None,
            reason: None,
        };
        let digging = matches!(
            intent,
            Intent::Dig { .. } | Intent::Collect { .. } | Intent::Craft { .. }
        );
        let acting = digging || matches!(intent, Intent::MoveTo { .. });
        let offered = crate::offered::OfferedRefs::of(perception);
        let exploring =
            matches!(intent, Intent::MoveTo { onto } if offered.explore_targets.contains(onto));
        let Some(held) = &perception.goal else {
            // With nothing grounding an item goal, a walk to look further sets a discovery goal.
            if exploring && offered.discovery_offered() {
                return Some(GoalChoice {
                    discover: Some(true),
                    ..step(GoalDisposition::Adopt, None)
                });
            }
            // A craft's worth of what the recipe makes, and never fewer than the objective floor
            // the perception offers: a goal one dig could meet is no objective (r39: alpha and
            // kappa held "one more dirt" goals that every step completed).
            let (item, more) = match intent {
                Intent::Dig { block } => (yield_of(perception, block, None)?, 1),
                Intent::Craft { recipe, .. } => {
                    let option = perception
                        .self_state
                        .crafting_options
                        .iter()
                        .find(|option| &option.recipe_ref == recipe)?;
                    (option.what.clone(), option.result_count)
                }
                _ => return None,
            };
            let have = held_count(perception, &item);
            return offered
                .count_bounds_of(&item)
                .map(|(fewest, most)| GoalChoice {
                    success_item: Some(item),
                    success_count: Some(fewest.max((have + more).min(most))),
                    ..step(GoalDisposition::Adopt, None)
                });
        };
        let progress = held.progress.as_ref();
        let step_result = match progress {
            Some(seen) if !seen.condition_advanced && !seen.step_advanced => {
                GoalStepResult::NoProgress
            }
            _ => GoalStepResult::Progressing,
        };
        let ended = |disposition, reason| GoalChoice {
            step: None,
            reason,
            ..step(disposition, Some(step_result))
        };
        let stalled = progress
            .is_some_and(|seen| seen.steps_without_progress >= MAX_GOAL_STEPS_WITHOUT_PROGRESS);
        // An unmet goal ends only for what its evidence states: stale at the stall bound, else
        // impossible, which the ledger refuses while sight could still meet it.
        let abandoned = || {
            ended(
                GoalDisposition::Abandon,
                Some(if stalled {
                    GoalReason::Stale
                } else {
                    GoalReason::Impossible
                }),
            )
        };
        // A met condition is completed; a goal at the no-progress bound, with no step to take for
        // it, or that what is in sight could no longer meet, is abandoned rather than continued
        // into the body's refusal. One sight can still meet is walked toward instead, because the
        // body refuses its abandon (the v26 rehearsal's three `goalAbandonedAchievable`).
        let unobtainable = held.success_item.is_some() && !offered.goal_obtainable;
        // A goal sight cannot meet is continued by a walk to the edge of sight, as the body
        // accepts, and abandoned only once nowhere is offered to look or it stalls.
        Some(if progress.is_some_and(|seen| seen.condition_met) {
            Self::moved_on(perception, intent, held, &offered, step_result)
        } else if !acting || (unobtainable && !exploring) || stalled {
            abandoned()
        } else {
            step(GoalDisposition::Continue, Some(step_result))
        })
    }

    /// The stand-in's answer to the met goal `held`: completed by adopting the next goal in sight
    /// in the same answer - what `intent` gives when sight offers a count of it, else the first
    /// item it does - by a revise when that is more of the goal's own item, which keeps the
    /// objective's identity, and by a replace as completed when it is another.
    fn moved_on(
        perception: &Perception,
        intent: &Intent,
        held: &loom_contract::GoalSummary,
        offered: &crate::offered::OfferedRefs,
        step_result: GoalStepResult,
    ) -> GoalChoice {
        let judged = |disposition, reason| GoalChoice {
            discover: None,
            disposition,
            success_item: None,
            success_count: None,
            step: None,
            step_result: Some(step_result),
            shelter: None,
            feeds: None,
            reason,
        };
        let made = Self::item_of(perception, intent)
            .filter(|item| offered.count_bounds_of(item).is_some());
        match made.or_else(|| offered.count_bounds.first().map(|(item, ..)| item.clone())) {
            Some(item) => {
                let (fewest, _) = offered.count_bounds_of(&item).unwrap_or((1, 1));
                let more = held.success_item.as_ref() == Some(&item);
                GoalChoice {
                    success_item: Some(item),
                    success_count: Some(fewest),
                    ..judged(
                        if more {
                            GoalDisposition::Revise
                        } else {
                            GoalDisposition::Replace
                        },
                        (!more).then_some(GoalReason::Completed),
                    )
                }
            }
            // A met discovery goal is never completed bare: it is replaced or, with nothing to
            // replace it by, abandoned as completed.
            None if held.discovery => judged(GoalDisposition::Abandon, Some(GoalReason::Completed)),
            None => judged(GoalDisposition::Complete, None),
        }
    }

    /// What `intent` makes or digs for: a dig's yield, or a craft's result.
    fn item_of(perception: &Perception, intent: &Intent) -> Option<String> {
        match intent {
            Intent::Dig { block } => yield_of(perception, block, None),
            Intent::Craft { recipe, .. } => perception
                .self_state
                .crafting_options
                .iter()
                .find(|option| &option.recipe_ref == recipe)
                .map(|option| option.what.clone()),
            _ => None,
        }
    }

    /// The per-trigger action rule, before any goal choice is attached.
    fn step_now(route: &ModelRoute, perception: &Perception) -> Draft {
        // Danger where no walk leads out is answered by closing the cover, never by a flight.
        if matches!(
            perception.trigger,
            Trigger::Damaged { .. } | Trigger::ThreatSighted { .. }
        ) {
            if let Some(closing) = cornered(perception) {
                return closing;
            }
        }
        match &perception.trigger {
            // Never a reply: two stand-ins answering speech with speech would be a chat loop.
            Trigger::Spoken { from, text } => Draft {
                goal: None,
                intent: Intent::Idle,
                reasoning: format!(
                    "{from} said {text:?}; the scripted stand-in never speaks, so it does not reply"
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
            // The attacker the body named is fled first; it is always offered.
            Trigger::Damaged {
                amount,
                source,
                attacker_ref,
                ..
            } => match attacker_ref
                .clone()
                .filter(|handle| fleeable(perception, handle))
                .or_else(|| nearest_ref(perception, source))
            {
                Some(handle) => Draft {
                    goal: None,
                    intent: Intent::Flee { from: handle },
                    reasoning: format!(
                        "took {amount} damage from {source} at health {health:.1}; disengage",
                        health = perception.self_state.health
                    ),
                },
                None => Draft {
                    goal: None,
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
            } if fleeable(perception, entity_ref) => Draft {
                goal: None,
                intent: Intent::Flee {
                    from: entity_ref.clone(),
                },
                reasoning: format!("{threat} hunts my kind and is in view; retreat from it"),
            },
            // Sealed in, nothing can reach the agent and no flight is offered.
            Trigger::ThreatSighted { threat, .. } => Draft {
                goal: None,
                intent: Intent::Idle,
                reasoning: format!("{threat} is in view, but no flight from it is offered; hold still"),
            },
            // A walk names a block the body observed, so the stand-in walks onto the
            // nearest observation of the sighted kind. It has no spatial reasoning:
            // a sighting with no matching observation, or one the trigger names in
            // words the body did not report, is answered by doing nothing rather than
            // by a coordinate nothing in the perception grounds.
            Trigger::BlockSighted { block } => match nearest_block_ref(perception, block) {
                Some(handle) => Draft {
                    goal: None,
                    intent: Intent::MoveTo { onto: handle },
                    reasoning: format!("{block} sighted nearby; walk onto it to reach it"),
                },
                None => Draft {
                    goal: None,
                    intent: Intent::Idle,
                    reasoning: format!("{block} sighted, but no observed block of it can be named"),
                },
            },
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
            trigger if trigger.reviews_goal() && perception.self_state.food <= LOW_FOOD_LEVEL => {
                let food = perception.self_state.food;
                let saturation = perception.self_state.saturation;
                match perception.self_state.inventory_summary.first() {
                    Some(stack) => Draft {
                        goal: None,
                        intent: Intent::Eat {
                            slot_ref: stack.slot_ref.clone(),
                        },
                        reasoning: format!(
                            "food is {food} of 20 with saturation {saturation:.1}; eating the {what} may restore it",
                            what = stack.what
                        ),
                    },
                    None => Draft {
                        goal: None,
                        intent: Intent::Rest,
                        reasoning: format!(
                            "food is {food} of 20 with saturation {saturation:.1} and there is nothing to eat; holding position"
                        ),
                    },
                }
            }
            // The stand-in's one routine step: dig the nearest offered block that can drop
            // something, the held goal's item first, or do nothing when no such block is in reach.
            Trigger::GoalReview | Trigger::StepSettled => Self::review_step(route, perception),
        }
    }
}

impl ScriptedBackend {
    /// The stand-in's one routine step: collect its own drop of the held goal's item while one
    /// lies in reach, as the body requires; else dig the nearest offered block that can drop
    /// something, the held goal's item first; else do nothing.
    fn review_step(route: &ModelRoute, perception: &Perception) -> Draft {
        // A hunter upon the agent is answered first, by a flight from the nearest one, or where
        // no walk leads out, by closing its cover.
        if let Some(from) = crate::offered::OfferedRefs::of(perception)
            .immediate
            .first()
            .cloned()
        {
            if let Some(closing) = cornered(perception) {
                return closing;
            }
            return Draft {
                goal: None,
                intent: Intent::Flee { from },
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; something that hunts my kind is upon me, so flee first",
                    tier = route.tier()
                ),
            };
        }
        if let Some((item, approach)) = own_goal_drop(perception) {
            return Draft {
                goal: None,
                intent: Intent::Collect { item, approach },
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; my own drop of the goal's item lies in reach, so collect it",
                    tier = route.tier()
                ),
            };
        }
        let offered = crate::offered::OfferedRefs::of(perception);
        let met = perception
            .goal
            .as_ref()
            .and_then(|goal| goal.progress.as_ref())
            .is_some_and(|progress| progress.condition_met);
        // A met goal is stepped toward the first item sight offers, which replaces it, rather than
        // toward the met item (the v32 rehearsal's three met goals walked off from logs in reach).
        let next = met
            .then(|| offered.count_bounds.first().map(|(item, ..)| item.as_str()))
            .flatten();
        let wanted = next.or_else(|| {
            perception
                .goal
                .as_ref()
                .and_then(|goal| goal.success_item.as_deref())
        });
        // A held goal sight cannot meet looks further for a source, rather than dig for another;
        // so does a discovery goal not yet met, and a review that can set one.
        let discovering = offered.discovering() && !offered.stalled && !met;
        if (next.is_none()
            && wanted.is_some()
            && !offered.goal_obtainable
            && offered.held_item.is_some())
            || discovering
            || (perception.goal.is_none() && offered.discovery_offered())
        {
            return Self::look_further(route, perception);
        }
        if let Some((recipe, item)) = offered_craft(perception, wanted) {
            return Draft {
                goal: None,
                intent: Intent::Craft { recipe, crafts: 1 },
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; I can craft {item} from what I hold, so craft it",
                    tier = route.tier()
                ),
            };
        }
        match nearest_yielding_dig(perception, wanted) {
            Some((block, item)) => Draft {
                goal: None,
                intent: Intent::Dig { block },
                reasoning: format!(
                    "reviewed goals at the {tier:?} tier; a block in reach can drop {item}, so dig it",
                    tier = route.tier()
                ),
            },
            // With a goal's source in sight but out of reach it walks onto that source; with
            // nothing in reach to dig, it walks to the edge of sight to look further, rather than
            // idling where it stands.
            None => match nearest_source_walk(perception, wanted) {
                Some((onto, item)) => Draft {
                    goal: None,
                    intent: Intent::MoveTo { onto },
                    reasoning: format!(
                        "reviewed goals at the {tier:?} tier; a block in sight can drop {item}, so walk to it",
                        tier = route.tier()
                    ),
                },
                None => Self::look_further(route, perception),
            },
        }
    }

    /// Walk to the first cell at the edge of sight, or do nothing when none is offered.
    fn look_further(route: &ModelRoute, perception: &Perception) -> Draft {
        match perception.explore.first() {
                Some(target) => Draft {
                    goal: None,
                    intent: Intent::MoveTo {
                        onto: target.target_ref.clone(),
                    },
                    reasoning: format!(
                        "reviewed goals at the {tier:?} tier; nothing in sight drops anything, so look further",
                        tier = route.tier()
                    ),
                },
                None => Draft {
                    goal: None,
                    intent: Intent::Idle,
                    reasoning: format!(
                        "reviewed goals at the {tier:?} tier; no block in reach drops anything",
                        tier = route.tier()
                    ),
                },
        }
    }
}

/// The nearest block offered as a walk target that can drop the held goal's [`wanted`] item.
fn nearest_source_walk(perception: &Perception, wanted: Option<&str>) -> Option<(String, String)> {
    let wanted = wanted?;
    let offered = crate::offered::OfferedRefs::of(perception);
    perception
        .surroundings
        .iter()
        .filter(|seen| offered.walk_targets.contains(&seen.target_ref))
        .filter(|seen| {
            seen.yields.as_ref().is_some_and(|yields| {
                yields
                    .iter()
                    .any(|item| item.what == wanted && item.most > 0)
            })
        })
        .min_by(|a, b| a.distance.total_cmp(&b.distance))
        .map(|seen| (seen.target_ref.clone(), wanted.to_owned()))
}

/// The first offered recipe and what it makes: one for [`wanted`] while a goal names it, else
/// any, while no goal is held.
fn offered_craft(perception: &Perception, wanted: Option<&str>) -> Option<(String, String)> {
    let offered = crate::offered::OfferedRefs::of(perception);
    perception
        .self_state
        .crafting_options
        .iter()
        .filter(|option| offered.recipes.contains(&option.recipe_ref))
        .find(|option| wanted.is_none_or(|item| option.what == item))
        .map(|option| (option.recipe_ref.clone(), option.what.clone()))
}

/// The nearest block offered to dig that can drop something, and the item it is dug for:
/// [`wanted`] only while a goal names it, else the stick, else the first it can drop.
fn nearest_yielding_dig(perception: &Perception, wanted: Option<&str>) -> Option<(String, String)> {
    let offered = crate::offered::OfferedRefs::of(perception);
    let diggable = |seen: &&Observation| offered.dig_blocks.contains(&seen.target_ref);
    let candidates: Vec<&Observation> = perception.surroundings.iter().filter(diggable).collect();
    let pick = |want: Option<&str>| {
        candidates.iter().find_map(|seen| {
            yield_of(perception, &seen.target_ref, want)
                .filter(|item| want.is_none_or(|want| item == want))
                .map(|item| (seen.target_ref.clone(), item))
        })
    };
    match wanted {
        Some(want) => pick(Some(want)),
        None => pick(Some(SCRIPTED_SUCCESS_ITEM)).or_else(|| pick(None)),
    }
}

/// The ref of the agent's own drop of its held goal's item that the body requires it to collect,
/// and whether reaching it needs an approach, or `None` when no collect is required.
fn own_goal_drop(perception: &Perception) -> Option<(String, bool)> {
    let offered = crate::offered::OfferedRefs::of(perception);
    let wanted = offered.pursued.as_ref()?;
    if !offered.collect_required {
        return None;
    }
    let (handle, _) = offered.own_drops.iter().find(|(_, what)| what == wanted)?;
    Some((handle.clone(), !offered.pickup_items.contains(handle)))
}

/// What digging the observed block `block` is for: `want` when it can drop that, else the
/// stick, else the first item it can drop.
fn yield_of(perception: &Perception, block: &str, want: Option<&str>) -> Option<String> {
    let yields = perception
        .surroundings
        .iter()
        .find(|seen| seen.target_ref == block)?
        .yields
        .as_ref()?;
    let named = |name: &str| yields.iter().find(|item| item.what == name);
    want.and_then(named)
        .or_else(|| named(SCRIPTED_SUCCESS_ITEM))
        .or_else(|| yields.first())
        .map(|item| item.what.clone())
}

/// How many of `item` the agent holds now.
fn held_count(perception: &Perception, item: &str) -> i64 {
    perception
        .self_state
        .inventory_summary
        .iter()
        .filter(|stack| stack.what == item)
        .map(|stack| stack.count)
        .sum()
}

/// Why a stand-in refuses a perception whose request schema would offer the model no answer.
pub const NO_ANSWER_OFFERED: &str = "the request schema for this perception offers no answer";

impl ModelBackend for ScriptedBackend {
    fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
        // Ready rather than `async fn`: there is nothing to await, and a future
        // that is already complete says so to its caller. A perception whose request schema the
        // provider would refuse is refused here too, so a rehearsal meets it before a paid run.
        std::future::ready(
            if crate::openai::wire::request_offers_an_answer(perception) {
                Ok(Self::draft_now(route, perception))
            } else {
                Err(BackendError::new(NO_ANSWER_OFFERED))
            },
        )
    }
}

/// The ref of the nearest observed living thing, if the scene holds one.
///
/// The body already sorted `surroundings` nearest-first, and only a living thing
/// carries `kind = entity`, so the first entity in the list is the nearest thing
/// that can be fled from. `None` says the scene holds no living target rather
/// than no actionable blocks or dropped items.
/// The ref of the nearest observed block of kind `block`, if the scene holds one.
///
/// Matched on the last path segment of the body's own description, so `diamond_ore`
/// names `block.minecraft.diamond_ore` and nothing else. A block in the agent's own
/// column is skipped, because a walk to it goes nowhere and is not offered.
fn nearest_block_ref(perception: &Perception, block: &str) -> Option<String> {
    perception
        .surroundings
        .iter()
        .find(|seen| {
            seen.kind == ObservationKind::Block
                && seen.direction != Bearing::Here
                && seen.what.rsplit('.').next() == Some(block)
        })
        .map(|seen| seen.target_ref.clone())
}

/// Whether `perception` offers a flight from `handle`: never while the agent is sealed in.
fn fleeable(perception: &Perception, handle: &str) -> bool {
    crate::offered::OfferedRefs::of(perception)
        .flee_targets
        .iter()
        .any(|offered| offered == handle)
}

/// Where no short walk leads out of where the agent stands no flight can run, so danger is answered
/// by closing its cover - the placement its cover names with the solid stack in hand, or the equip
/// that puts one there - else by holding still. `None` while a flight can run (the v35 shelter
/// rehearsal: zeta, damaged in a pit, fled, and the mind refused the flight).
pub(crate) fn cornered(perception: &Perception) -> Option<Draft> {
    let offered = crate::offered::OfferedRefs::of(perception);
    let shelter = &offered.shelter;
    if !shelter.trapped() {
        return None;
    }
    let placed = shelter
        .closings
        .first()
        .zip(shelter.solid_held.first())
        .map(|((block, face), item)| Intent::Place {
            block: block.clone(),
            item: item.clone(),
            face: *face,
        });
    let readied = || {
        shelter.to_equip().first().map(|item| Intent::Equip {
            item: item.clone(),
            destination: EquipmentDestination::Hand,
        })
    };
    Some(
        match placed.or_else(readied).filter(|intent| offered.admits(intent)) {
            Some(intent) => Draft {
                goal: None,
                intent,
                reasoning: "no walk leads out of where I stand, so I close my cover against what hunts me".to_owned(),
            },
            None => Draft {
                goal: None,
                intent: Intent::Idle,
                reasoning: "no walk leads out of where I stand and nothing I hold closes my cover, so I hold still".to_owned(),
            },
        },
    )
}

/// The nearest creature to flee from after damage: one of the kind that dealt it, else any that
/// hunts the agent's kind, and never a peer or a creature that hunts nothing (the v29 rehearsal:
/// the stand-in fled the nearest thing with a ref, a peer, twice).
fn nearest_ref(perception: &Perception, source: &str) -> Option<String> {
    let kind = source.rsplit(['.', ':']).next().unwrap_or(source);
    // Only a creature the perception offers a flight may be named: a hunter past the offered
    // ones would be refused (v32 plains rehearsal 15: a skeleton shooting from beyond them).
    let offered = crate::offered::OfferedRefs::of(perception).flee_targets;
    let hunters = || {
        perception.surroundings.iter().filter(|seen| {
            seen.kind == ObservationKind::Entity
                && seen.hunts == Some(true)
                && offered.contains(&seen.target_ref)
        })
    };
    hunters()
        .find(|seen| seen.what.rsplit(['.', ':']).next() == Some(kind))
        .or_else(|| hunters().next())
        .map(|seen| seen.target_ref.clone())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        BackendChoice, ModelBackend, ScriptedBackend, BACKEND_VAR, LOW_FOOD_LEVEL, SCRIPTED_STEP,
    };
    use crate::config::{ConfigError, Env};
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{
        Bearing, DayPhase, DecisionImportance, GoalDisposition, GoalSummary, Intent,
        InventoryStack, Observation, ObservationKind, Perception, SelfState, Trigger, WorldState,
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
                own_drops: vec![],
                world: WorldState {
                    day_phase: DayPhase::Day,
                    is_day: true,
                    ticks_until_nightfall: 6_000,
                },
                cover: None,
            },
            surroundings: vec![],
            surroundings_omitted: 0,
            goal: None,
            last_refusal: None,
            explore: vec![],
            recalled: vec![],
            horizon: vec![],
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
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: (what.ends_with("zombie") || what.ends_with("skeleton")).then_some(true),
            cover: None,
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
                    attacker_ref: None,
                    health_before: None,
                    health_after: None,
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

    #[tokio::test]
    async fn damage_beside_only_a_peer_never_flees_the_peer() {
        // The v29 rehearsal: the stand-in fled the nearest thing with a ref, another player, twice.
        let seen = in_view(
            in_view(
                perception(
                    Trigger::Damaged {
                        amount: 3,
                        source: "minecraft:zombie".to_owned(),
                        attacker_ref: None,
                        health_before: None,
                        health_after: None,
                    },
                    DecisionImportance::Pivotal,
                ),
                "entity.minecraft.player",
                "e1",
            ),
            "entity.minecraft.zombie",
            "e2",
        );
        let draft = ScriptedBackend
            .draft(&router().route(seen.importance), &seen)
            .await
            .expect("the stand-in backend always drafts");
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e2".to_owned()
            }
        );
        let peer_only = in_view(
            perception(
                Trigger::Damaged {
                    amount: 3,
                    source: "fall".to_owned(),
                    attacker_ref: None,
                    health_before: None,
                    health_after: None,
                },
                DecisionImportance::Pivotal,
            ),
            "entity.minecraft.player",
            "e1",
        );
        let idle = ScriptedBackend
            .draft(&router().route(peer_only.importance), &peer_only)
            .await
            .expect("the stand-in backend always drafts");
        assert_eq!(idle.intent, Intent::Idle);
    }

    #[test]
    fn as_night_nears_the_stand_in_holds_one_shelter_goal_stepped_by_its_cover() {
        use loom_contract::{GoalDisposition, GoalReason};
        let route = router().route(DecisionImportance::Routine);
        // Open grass at dusk: the shelter is adopted by the descent underfoot.
        let dusk = shelter_scene("dusk-open-grass");
        let adopted = ScriptedBackend::draft_now(&route, &dusk);
        assert_eq!(
            adopted.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let choice = adopted.goal.unwrap();
        assert_eq!(choice.disposition, GoalDisposition::Adopt);
        assert_eq!(choice.shelter, Some(true));
        // Holding an unmet item goal, the shelter takes its place as unsafe to keep.
        let mut holding = dusk.clone();
        holding.goal = shelter_scene("cover-gathers")
            .goal
            .map(|goal| loom_contract::GoalSummary {
                shelter: false,
                success_item: Some("item.minecraft.oak_log".to_owned()),
                success_count: Some(4),
                ..goal
            });
        let replaced = ScriptedBackend::draft_now(&route, &holding).goal.unwrap();
        assert_eq!(replaced.disposition, GoalDisposition::Replace);
        assert_eq!(replaced.shelter, Some(true));
        assert_eq!(replaced.reason, Some(GoalReason::Unsafe));
        // With nothing solid held, gathering the closer its cover offers is the shelter's own step.
        let gathering = ScriptedBackend::draft_now(&route, &shelter_scene("cover-gathers"));
        assert_eq!(
            gathering.intent,
            Intent::Dig {
                block: "b8".to_owned()
            }
        );
        assert_eq!(
            gathering.goal.unwrap().disposition,
            GoalDisposition::Continue
        );
        // Sealed in, it is completed and stayed in.
        let sealed = ScriptedBackend::draft_now(&route, &shelter_scene("sealed-met"));
        assert_eq!(sealed.intent, Intent::Rest);
        assert_eq!(sealed.goal.unwrap().disposition, GoalDisposition::Complete);
        // A shelter at the stall bound is ended as stale.
        let mut stuck = shelter_scene("cover-gathers");
        if let Some(progress) = stuck.goal.as_mut().and_then(|goal| goal.progress.as_mut()) {
            progress.steps_without_progress = loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS;
            progress.condition_advanced = false;
            progress.step_advanced = false;
        }
        let stale = ScriptedBackend::draft_now(&route, &stuck).goal.unwrap();
        assert_eq!(stale.disposition, GoalDisposition::Abandon);
        assert_eq!(stale.reason, Some(GoalReason::Stale));
        // By day no shelter is offered, so the ordinary rule answers.
        let midday = ScriptedBackend::draft_now(&route, &shelter_scene("midday-open-grass"));
        assert_ne!(midday.goal.and_then(|goal| goal.shelter), Some(true));
    }

    fn shelter_scene(name: &str) -> Perception {
        let path = format!(
            "{}/tests/fixtures/shelter-{name}.json",
            env!("CARGO_MANIFEST_DIR")
        );
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn damage_where_no_walk_leads_out_closes_the_cover_rather_than_fleeing() {
        // The v35 shelter rehearsal: zeta, damaged in a pit with no walk out, fled, and the mind
        // refused the flight, which ended the run as a failed decision.
        let mut pit = shelter_scene("pit-damaged");
        let answer = |seen: &Perception| {
            let draft = ScriptedBackend::draft_now(&router().route(seen.importance), seen);
            assert!(
                crate::offered::OfferedRefs::of(seen).admits(&draft.intent),
                "the mind would refuse {:?}",
                draft.intent
            );
            draft.intent
        };
        assert_eq!(
            answer(&pit),
            Intent::Place {
                block: "b2".to_owned(),
                item: "s1".to_owned(),
                face: loom_contract::BlockFace::West
            }
        );
        // The dirt carried but not in hand is readied first.
        pit.self_state.inventory_summary[0].held = None;
        assert_eq!(
            answer(&pit),
            Intent::Equip {
                item: "s1".to_owned(),
                destination: loom_contract::EquipmentDestination::Hand
            }
        );
        // Nothing solid to close it with: it holds still.
        pit.self_state.inventory_summary.clear();
        assert_eq!(answer(&pit), Intent::Idle);
        // A sighting there, and a goal review beside the hunter, close the cover the same way.
        let mut sighted = shelter_scene("pit-damaged");
        sighted.trigger = Trigger::ThreatSighted {
            threat: "entity.minecraft.zombie".to_owned(),
            entity_ref: "e1".to_owned(),
            distance: 2.6,
            direction: Bearing::North,
            visible: true,
            reachable: None,
        };
        assert!(matches!(answer(&sighted), Intent::Place { .. }));
        let mut reviewed = shelter_scene("pit-damaged");
        reviewed.trigger = Trigger::GoalReview;
        assert!(matches!(answer(&reviewed), Intent::Place { .. }));
        // Sealed in, neither the attacker nor the creature sighted is fled.
        let mut sealed = shelter_scene("pit-damaged");
        let cover = sealed.self_state.cover.as_mut().unwrap();
        cover.sealed = true;
        cover.open.clear();
        cover.closings.clear();
        assert_eq!(answer(&sealed), Intent::Idle);
        sealed.trigger = sighted.trigger.clone();
        assert_eq!(answer(&sealed), Intent::Idle);
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
                attacker_ref: None,
                health_before: None,
                health_after: None,
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
    fn damage_from_a_hunter_past_the_offered_creatures_flees_one_that_was_offered() {
        // v32 plains rehearsal 15: epsilon was shot, the nearest skeleton stood past the creatures
        // the perception offers a flight, and the stand-in named it, which the mind refused and
        // the run stopped on.
        let creature = |index: usize, what: &str, hunts: bool| Observation {
            kind: ObservationKind::Entity,
            what: format!("entity.minecraft.{what}"),
            distance: 2.0 + f64::from(u32::try_from(index).unwrap()),
            direction: Bearing::East,
            vertical_offset: 0.0,
            visible: true,
            target_ref: format!("e{index}"),
            count: None,
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: Some(hunts),
            cover: None,
        };
        let mut seen = perception(
            Trigger::Damaged {
                amount: 3,
                source: "skeleton".to_owned(),
                attacker_ref: None,
                health_before: None,
                health_after: None,
            },
            DecisionImportance::Pivotal,
        );
        seen.surroundings = (0..20)
            .map(|index| creature(index, "sheep", false))
            .collect();
        seen.surroundings.insert(3, creature(90, "zombie", true));
        seen.surroundings.push(creature(91, "skeleton", true));
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        let offered = crate::offered::OfferedRefs::of(&seen);
        assert!(
            offered.admits(&draft.intent),
            "{:?} not offered",
            draft.intent
        );
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e90".to_owned()
            }
        );
        // And the skeleton the body names as the one that hurt it is fled, past the cut.
        seen.trigger = Trigger::Damaged {
            amount: 3,
            source: "skeleton".to_owned(),
            attacker_ref: Some("e91".to_owned()),
            health_before: None,
            health_after: None,
        };
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert!(crate::offered::OfferedRefs::of(&seen).admits(&draft.intent));
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e91".to_owned()
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
                attacker_ref: None,
                health_before: None,
                health_after: None,
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
    fn the_stand_in_adopts_at_its_first_review_and_continues_at_the_next() {
        let route = router().route(DecisionImportance::Routine);
        let mut first = perception(Trigger::GoalReview, DecisionImportance::Routine);
        // Nothing in reach drops anything: it idles, and adopts no goal beside an idle step (r14 eta).
        let idle = ScriptedBackend::draft_now(&route, &first);
        assert_eq!(idle.intent, Intent::Idle);
        assert_eq!(idle.goal, None);
        // A bush in reach that drops nought to two sticks: it digs it, for one more than it holds.
        first.surroundings = vec![loom_contract::Observation {
            kind: ObservationKind::Block,
            what: "block.minecraft.dead_bush".to_owned(),
            distance: 1.0,
            direction: Bearing::North,
            vertical_offset: 0.0,
            visible: true,
            target_ref: "b1".to_owned(),
            count: None,
            standable: Some(true),
            yields: Some(vec![loom_contract::BlockYield {
                what: super::SCRIPTED_SUCCESS_ITEM.to_owned(),
                least: 1,
                most: 3,
                per_hundred: 200,
            }]),
            dig_safe: Some(true),
            route_refused: None,
            hunts: None,
            cover: None,
        }];
        let digging = ScriptedBackend::draft_now(&route, &first);
        assert_eq!(
            digging.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let adopted = digging.goal.unwrap();
        assert_eq!(adopted.disposition, GoalDisposition::Adopt);
        assert_eq!(
            adopted.success_item.as_deref(),
            Some(super::SCRIPTED_SUCCESS_ITEM)
        );
        // Never fewer than the objective floor past what is held (r39: alpha's one-more goals).
        assert_eq!(adopted.success_count, Some(2));
        first.self_state.inventory_summary = vec![loom_contract::InventoryStack {
            slot_ref: "i1".to_owned(),
            what: super::SCRIPTED_SUCCESS_ITEM.to_owned(),
            count: 1,
            edible: None,
            solid: None,
            held: None,
        }];
        let more = ScriptedBackend::draft_now(&route, &first).goal.unwrap();
        assert_eq!(more.success_count, Some(3));

        let mut later = perception(Trigger::GoalReview, DecisionImportance::Routine);
        later.goal = Some(GoalSummary {
            discovery: false,
            goal_id: "req-1".to_owned(),
            statement: "hold 1 item.minecraft.stick".to_owned(),
            revision: 0,
            adopted_tick: 0,
            last_step: None,
            success_item: None,
            success_count: None,
            step: Some(SCRIPTED_STEP.to_owned()),
            step_action: None,
            step_ref: None,
            progress: None,
            shelter: false,
            feeds: None,
        });
        // With nothing left in reach to dig for it, the goal is abandoned, not idled on.
        let abandoned = ScriptedBackend::draft_now(&route, &later).goal.unwrap();
        assert_eq!(abandoned.disposition, GoalDisposition::Abandon);
        later.surroundings.clone_from(&first.surroundings);
        let continued = ScriptedBackend::draft_now(&route, &later).goal.unwrap();
        assert_eq!(continued.disposition, GoalDisposition::Continue);
        assert_eq!(continued.success_item, None);
        assert!(SCRIPTED_STEP.starts_with("scripted stand-in"));
    }

    #[test]
    fn a_held_goal_whose_source_is_in_sight_but_out_of_reach_walks_to_it_rather_than_abandoning() {
        // The v26 rehearsal: a bush in sight past dig reach, and the stand-in abandoned a goal
        // the body refused to let go as goalAbandonedAchievable.
        let route = router().route(DecisionImportance::Routine);
        let mut seen = perception(Trigger::GoalReview, DecisionImportance::Routine);
        seen.goal = Some(GoalSummary {
            discovery: false,
            goal_id: "req-1".to_owned(),
            statement: "hold 1 item.minecraft.stick".to_owned(),
            revision: 0,
            adopted_tick: 0,
            last_step: None,
            success_item: Some(super::SCRIPTED_SUCCESS_ITEM.to_owned()),
            success_count: Some(1),
            step: None,
            step_action: None,
            step_ref: None,
            progress: None,
            shelter: false,
            feeds: None,
        });
        let bush = |handle: &str, distance: f64| loom_contract::Observation {
            kind: ObservationKind::Block,
            what: "block.minecraft.dead_bush".to_owned(),
            distance,
            direction: Bearing::North,
            vertical_offset: 0.0,
            visible: true,
            target_ref: handle.to_owned(),
            count: None,
            standable: Some(true),
            yields: Some(vec![loom_contract::BlockYield {
                what: super::SCRIPTED_SUCCESS_ITEM.to_owned(),
                least: 0,
                most: 2,
                per_hundred: 100,
            }]),
            dig_safe: Some(true),
            route_refused: None,
            hunts: None,
            cover: None,
        };
        seen.surroundings = vec![bush("b9", 9.0), bush("b7", 7.0)];
        let walking = ScriptedBackend::draft_now(&route, &seen);
        assert_eq!(
            walking.intent,
            Intent::MoveTo {
                onto: "b7".to_owned()
            }
        );
        assert_eq!(walking.goal.unwrap().disposition, GoalDisposition::Continue);
        // With no source in sight it looks further, still pursuing the goal.
        seen.surroundings.clear();
        seen.explore = vec![loom_contract::ExploreTarget {
            target_ref: "x1".to_owned(),
            distance: 12.0,
            direction: Bearing::East,
            vertical_offset: 0.0,
        }];
        let looking = ScriptedBackend::draft_now(&route, &seen);
        assert_eq!(
            looking.intent,
            Intent::MoveTo {
                onto: "x1".to_owned()
            }
        );
    }

    #[test]
    fn the_stand_in_never_answers_speech_with_speech() {
        let spoken = Trigger::Spoken {
            from: "LoomAlpha".to_owned(),
            text: "hello there".to_owned(),
        };
        let route = router().route(DecisionImportance::Notable);
        let seen = perception(spoken, DecisionImportance::Notable);
        assert_eq!(
            ScriptedBackend::draft_now(&route, &seen).intent,
            Intent::Idle
        );
    }

    #[test]
    fn the_stand_in_makes_no_goal_choice_outside_a_goal_review() {
        let spoken = Trigger::Spoken {
            from: "p".to_owned(),
            text: "hi".to_owned(),
        };
        let route = router().route(DecisionImportance::Notable);
        let seen = perception(spoken, DecisionImportance::Notable);
        assert_eq!(ScriptedBackend::draft_now(&route, &seen).goal, None);
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
                edible: None,
                solid: None,
                held: None,
            },
            InventoryStack {
                slot_ref: "i2".to_owned(),
                what: "iron pickaxe".to_owned(),
                count: 1,
                edible: None,
                solid: None,
                held: None,
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
    fn a_met_discovery_goal_digs_the_log_it_revealed_and_replaces_itself_with_a_log_goal() {
        // The v31 plains rehearsal: seven met discoveries walked past the log they revealed.
        let path = format!(
            "{}/../../../contract/fixtures/valid/perception-goal-discovery-held.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let mut seen: Perception =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        seen.surroundings = vec![Observation {
            kind: ObservationKind::Block,
            what: "block.minecraft.oak_log".to_owned(),
            distance: 2.0,
            direction: Bearing::East,
            vertical_offset: 0.5,
            visible: true,
            target_ref: "b1".to_owned(),
            count: None,
            standable: Some(false),
            yields: Some(vec![loom_contract::BlockYield {
                what: "item.minecraft.oak_log".to_owned(),
                least: 1,
                most: 1,
                per_hundred: 100,
            }]),
            dig_safe: Some(true),
            route_refused: None,
            hunts: None,
            cover: None,
        }];
        seen.horizon = vec![loom_contract::HorizonGoal {
            what: "item.minecraft.oak_log".to_owned(),
            grounding: loom_contract::HorizonGrounding::Sight,
            via: None,
            most: 9,
            frontier: true,
        }];
        if let Some(progress) = seen.goal.as_mut().and_then(|goal| goal.progress.as_mut()) {
            progress.condition_met = true;
        }
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(
            draft.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let choice = draft.goal.unwrap();
        assert_eq!(choice.disposition, GoalDisposition::Replace);
        assert_eq!(
            choice.success_item.as_deref(),
            Some("item.minecraft.oak_log")
        );
    }

    #[test]
    fn a_met_item_goal_digs_the_log_in_reach_it_replaces_itself_with_not_a_walk_away() {
        // The v32 plains rehearsal: gamma's met dye goal, nothing more of it in sight, walked to
        // the edge of sight beside a birch log in reach and was refused as unproductive.
        let path = format!(
            "{}/../../../contract/fixtures/valid/perception-goal-discovery-held.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let mut seen: Perception =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        seen.surroundings = vec![Observation {
            kind: ObservationKind::Block,
            what: "block.minecraft.birch_log".to_owned(),
            distance: 2.3,
            direction: Bearing::East,
            vertical_offset: 0.5,
            visible: true,
            target_ref: "b1".to_owned(),
            count: None,
            standable: Some(false),
            yields: Some(vec![loom_contract::BlockYield {
                what: "item.minecraft.birch_log".to_owned(),
                least: 1,
                most: 1,
                per_hundred: 100,
            }]),
            dig_safe: Some(true),
            route_refused: None,
            hunts: None,
            cover: None,
        }];
        seen.horizon = vec![loom_contract::HorizonGoal {
            what: "item.minecraft.birch_log".to_owned(),
            grounding: loom_contract::HorizonGrounding::Sight,
            via: None,
            most: 11,
            frontier: true,
        }];
        seen.self_state.inventory_summary = vec![InventoryStack {
            slot_ref: "i1".to_owned(),
            what: "item.minecraft.light_gray_dye".to_owned(),
            count: 1,
            edible: None,
            solid: None,
            held: None,
        }];
        let goal = seen.goal.as_mut().unwrap();
        goal.discovery = false;
        goal.success_item = Some("item.minecraft.light_gray_dye".to_owned());
        goal.success_count = Some(1);
        if let Some(progress) = goal.progress.as_mut() {
            progress.condition_met = true;
        }
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(
            draft.intent,
            Intent::Dig {
                block: "b1".to_owned()
            }
        );
        let choice = draft.goal.unwrap();
        assert_eq!(choice.disposition, GoalDisposition::Replace);
        assert_eq!(
            choice.success_item.as_deref(),
            Some("item.minecraft.birch_log")
        );
    }

    #[test]
    fn a_hungry_meal_defers_a_held_goal_rather_than_abandoning_it() {
        let mut seen = hungry(
            perception(Trigger::GoalReview, DecisionImportance::Routine),
            LOW_FOOD_LEVEL,
        );
        seen.self_state.inventory_summary = vec![InventoryStack {
            slot_ref: "i1".to_owned(),
            what: "item.minecraft.bread".to_owned(),
            count: 3,
            edible: None,
            solid: None,
            held: None,
        }];
        let path = format!(
            "{}/../../../contract/fixtures/valid/perception-goal-discovery-held.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let held: Perception =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        seen.goal = held.goal;
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert!(matches!(draft.intent, Intent::Eat { .. }));
        assert_eq!(
            draft.goal, None,
            "the meal defers the goal, which stays held"
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
                attacker_ref: None,
                health_before: None,
                health_after: None,
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
                    reachable: Some(true),
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
                        attacker_ref: None,
                        health_before: None,
                        health_after: None,
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
    fn a_sighting_walks_onto_the_nearest_observed_block_of_its_kind() {
        let mut seen = perception(
            Trigger::BlockSighted {
                block: "diamond_ore".to_owned(),
            },
            DecisionImportance::Routine,
        );
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(draft.intent, Intent::Idle);
        for (what, handle, direction) in [
            ("block.minecraft.stone", "b1", Bearing::North),
            // Under the feet: a walk there goes nowhere, so the stand-in looks past it.
            ("block.minecraft.diamond_ore", "b0", Bearing::Here),
            ("block.minecraft.diamond_ore", "b2", Bearing::North),
            ("block.minecraft.diamond_ore", "b3", Bearing::North),
        ] {
            seen.surroundings.push(Observation {
                kind: ObservationKind::Block,
                what: what.to_owned(),
                distance: 2.0,
                direction,
                vertical_offset: -0.5,
                visible: true,
                target_ref: handle.to_owned(),
                count: None,
                standable: Some(true),
                yields: Some(vec![]),
                dig_safe: Some(true),
                route_refused: None,
                hunts: None,
                cover: None,
            });
        }
        let draft = ScriptedBackend::draft_now(&router().route(seen.importance), &seen);
        assert_eq!(
            draft.intent,
            Intent::MoveTo {
                onto: "b2".to_owned()
            }
        );
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
