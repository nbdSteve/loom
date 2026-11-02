//! The mind: perception in, decision out.
//!
//! This is the whole of Loom's cognition boundary on the Rust side. It receives
//! a bounded [`Perception`] and returns a [`Decision`]; it has no world handle,
//! no way to reach back into the body, and no state that outlives a request. It
//! never chooses an action itself either - that is the backend's job - and never
//! lets the backend say which model answered, which is what keeps provenance
//! trustworthy.
//!
//! The [`Router`] is held rather than reached for, because the model ids it names
//! are configuration: a mind wired to the `OpenAI` backend routes to the models that
//! backend was configured with, and a mind wired to the stand-in routes to the
//! stand-in's own name. Provenance therefore stays true whichever is running.
//!
//! Cognition is event-driven: one [`Perception`] is one triggering event. The
//! contract has no per-tick trigger, so there is no path by which a tick could
//! arrive here.

use loom_contract::{Decision, Perception, Provenance};

use crate::model::{BackendError, ModelBackend};
use crate::offered::OfferedRefs;
use crate::router::Router;

/// Turns perceptions into decisions, routing each to a model by importance.
pub struct Mind<B: ModelBackend> {
    router: Router,
    backend: B,
}

impl<B: ModelBackend> Mind<B> {
    /// Builds a mind that routes with `router` and drafts through `backend`.
    ///
    /// The two belong together: the router names the models the backend can
    /// actually answer with. Pairing a router with a backend that does not serve
    /// those models would stamp provenance nobody could reproduce.
    pub const fn new(router: Router, backend: B) -> Self {
        Self { router, backend }
    }

    /// The router this mind stamps provenance from.
    pub const fn router(&self) -> &Router {
        &self.router
    }

    /// Decides for `perception`.
    ///
    /// The request and agent ids are echoed from the perception rather than
    /// taken from the backend, so a decision can always be correlated with the
    /// perception it answers. Provenance is stamped here from the route that
    /// was actually used.
    ///
    /// # Errors
    ///
    /// Returns [`BackendError`] if the backend could not draft. One failed
    /// decision degrades one agent; the service keeps serving.
    pub async fn decide(&self, perception: &Perception) -> Result<Decision, BackendError> {
        let routed = self.router.route(perception.importance);
        let draft = self.backend.draft(&routed, perception).await?;
        // Held here rather than trusted to a backend: a handle of the wrong kind, or one
        // this perception never offered, is an answer the body could only refuse.
        if !OfferedRefs::of(perception).admits(&draft.intent) {
            return Err(BackendError::new(
                "the answer named a ref this perception did not offer for that action",
            ));
        }
        Ok(Decision {
            request_id: perception.request_id.clone(),
            agent_id: perception.agent_id.clone(),
            intent: draft.intent,
            reasoning: draft.reasoning,
            provenance: Provenance {
                tier: routed.tier(),
                model: routed.model().to_owned(),
                prompt_version: routed.prompt_version().to_owned(),
            },
            // Carried unchanged: the body's goal ledger resolves it against the exact
            // goal this perception presented, so the mind neither checks nor rewrites it.
            goal: draft.goal,
        })
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use std::future::Future;

    use super::Mind;
    use crate::model::{BackendError, Draft, ModelBackend, ScriptedBackend};
    use crate::router::{ModelCatalog, ModelRoute, Router};
    use loom_contract::{
        DayPhase, DecisionImportance, GoalChoice, GoalDisposition, GoalReason, GoalStepResult,
        Intent, Perception, SelfState, Trigger, WorldState,
    };

    const FAST: &str = "a-small-model";
    const DEEP: &str = "a-large-model";

    fn router() -> Router {
        Router::new(ModelCatalog::new(FAST, DEEP))
    }

    /// A backend that misreports what it is: it names a model in its reasoning
    /// and hands back an intent unrelated to the perception. Used to show the
    /// mind, not the backend, owns provenance.
    struct LyingBackend;

    impl ModelBackend for LyingBackend {
        fn draft(
            &self,
            _route: &ModelRoute,
            _perception: &Perception,
        ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
            std::future::ready(Ok(Draft {
                goal: None,
                intent: Intent::Idle,
                reasoning: format!("answered by {DEEP}, honestly"),
            }))
        }
    }

    /// A backend that always fails, standing in for an unreachable model.
    struct BrokenBackend;

    impl ModelBackend for BrokenBackend {
        fn draft(
            &self,
            _route: &ModelRoute,
            _perception: &Perception,
        ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
            std::future::ready(Err(BackendError::new("model unreachable")))
        }
    }

    fn perception(importance: DecisionImportance) -> Perception {
        Perception {
            request_id: "req-42".to_owned(),
            agent_id: "agent-7".to_owned(),
            trigger: Trigger::Spoken {
                from: "player-steve".to_owned(),
                text: "want to trade?".to_owned(),
            },
            importance,
            captured_tick: 1024,
            self_state: SelfState {
                health: 20.0,
                food: 20,
                saturation: 5.0,
                position: [0.0, 64.0, 0.0],
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

    #[tokio::test]
    async fn a_decision_echoes_the_perception_it_answers() {
        let mind = Mind::new(router(), ScriptedBackend);
        let seen = perception(DecisionImportance::Notable);
        let decided = mind
            .decide(&seen)
            .await
            .expect("the stand-in backend decides");
        assert_eq!(decided.request_id, seen.request_id);
        assert_eq!(decided.agent_id, seen.agent_id);
    }

    #[tokio::test]
    async fn every_decision_is_stamped_with_the_model_and_prompt_that_produced_it() {
        let mind = Mind::new(router(), ScriptedBackend);
        for tier in [
            DecisionImportance::Routine,
            DecisionImportance::Notable,
            DecisionImportance::Pivotal,
        ] {
            let decided = mind
                .decide(&perception(tier))
                .await
                .expect("the stand-in backend decides");
            assert_eq!(decided.provenance.tier, tier);
            assert!(!decided.provenance.model.is_empty());
            assert!(!decided.provenance.prompt_version.is_empty());
        }
    }

    #[tokio::test]
    async fn a_routine_decision_is_never_stamped_with_the_deep_model() {
        let mind = Mind::new(router(), ScriptedBackend);
        let decided = mind
            .decide(&perception(DecisionImportance::Routine))
            .await
            .expect("the stand-in backend decides");
        assert_eq!(decided.provenance.model, FAST);
    }

    #[tokio::test]
    async fn provenance_comes_from_the_route_not_from_the_backend() {
        let mind = Mind::new(router(), LyingBackend);
        let decided = mind
            .decide(&perception(DecisionImportance::Routine))
            .await
            .expect("the lying backend still drafts");
        // The backend claimed the deep model in its reasoning; the stamp names
        // the model the route actually chose, which is the only claim the
        // research record carries.
        assert!(decided.reasoning.contains(DEEP));
        assert_eq!(decided.provenance.model, FAST);
        assert_eq!(decided.provenance.tier, DecisionImportance::Routine);
        assert_eq!(decided.provenance.prompt_version, "routine-v38");
    }

    /// A backend that answers with one fixed intent, whatever it was offered.
    struct FixedBackend(Intent);

    impl ModelBackend for FixedBackend {
        fn draft(
            &self,
            _route: &ModelRoute,
            _perception: &Perception,
        ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
            std::future::ready(Ok(Draft {
                goal: None,
                intent: self.0.clone(),
                reasoning: "named whatever it liked".to_owned(),
            }))
        }
    }

    #[tokio::test]
    async fn a_ref_the_perception_did_not_offer_for_that_action_is_no_decision() {
        let mut seen = perception(DecisionImportance::Routine);
        seen.surroundings.push(loom_contract::Observation {
            kind: loom_contract::ObservationKind::Entity,
            what: "entity.minecraft.zombie".to_owned(),
            distance: 4.0,
            direction: loom_contract::Bearing::North,
            vertical_offset: 0.0,
            visible: true,
            target_ref: "e1".to_owned(),
            count: None,
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: Some(true),
            cover: None,
        });
        for wrong in [
            Intent::Dig {
                block: "e1".to_owned(),
            },
            Intent::Flee {
                from: "e9".to_owned(),
            },
        ] {
            let refused = Mind::new(router(), FixedBackend(wrong.clone()))
                .decide(&seen)
                .await
                .expect_err("a wrong-kind or unoffered ref is refused");
            assert!(refused.message.contains("did not offer"), "{wrong:?}");
        }
        let fled = Mind::new(
            router(),
            FixedBackend(Intent::Flee {
                from: "e1".to_owned(),
            }),
        )
        .decide(&seen)
        .await
        .expect("an offered creature may be fled from");
        assert_eq!(
            fled.intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
    }

    /// A backend that answers with one fixed goal choice.
    struct ChoosingBackend(GoalChoice);

    impl ModelBackend for ChoosingBackend {
        fn draft(
            &self,
            _route: &ModelRoute,
            _perception: &Perception,
        ) -> impl Future<Output = Result<Draft, BackendError>> + Send {
            std::future::ready(Ok(Draft {
                goal: Some(self.0.clone()),
                intent: Intent::Idle,
                reasoning: "the goal still holds".to_owned(),
            }))
        }
    }

    #[tokio::test]
    async fn the_backend_goal_choice_reaches_the_decision_unchanged_beside_true_provenance() {
        for disposition in [
            GoalDisposition::Adopt,
            GoalDisposition::Continue,
            GoalDisposition::Revise,
            GoalDisposition::Complete,
            GoalDisposition::Abandon,
        ] {
            let choice = GoalChoice {
                discover: None,
                disposition,
                success_item: None,
                success_count: None,
                step: (disposition != GoalDisposition::Complete
                    && disposition != GoalDisposition::Abandon)
                    .then(|| "stand by the nearest tree".to_owned()),
                step_result: (disposition != GoalDisposition::Adopt)
                    .then_some(GoalStepResult::Progressing),
                shelter: None,
                feeds: None,
                reason: (disposition == GoalDisposition::Abandon).then_some(GoalReason::Stale),
            };
            let mind = Mind::new(router(), ChoosingBackend(choice.clone()));
            let decided = mind
                .decide(&perception(DecisionImportance::Routine))
                .await
                .unwrap();
            assert_eq!(decided.goal, Some(choice));
            assert_eq!(decided.intent, Intent::Idle);
            assert_eq!(decided.provenance.model, FAST);
            assert_eq!(decided.provenance.prompt_version, "routine-v38");
        }
    }

    #[tokio::test]
    async fn a_failing_backend_yields_an_error_rather_than_a_decision() {
        let mind = Mind::new(router(), BrokenBackend);
        let failed = mind.decide(&perception(DecisionImportance::Notable)).await;
        assert_eq!(
            failed.err().map(|e| e.message),
            Some("model unreachable".to_owned())
        );
    }
}
