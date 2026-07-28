//! The mind: perception in, decision out.
//!
//! This is the whole of Loom's cognition boundary on the Rust side. It receives
//! a bounded [`Perception`] and returns a [`Decision`]; it has no world handle,
//! no way to reach back into the body, and no state that outlives a request. It
//! never chooses an action itself either - that is the backend's job - and never
//! lets the backend say which model answered, which is what keeps provenance
//! trustworthy.
//!
//! Cognition is event-driven: one [`Perception`] is one triggering event. The
//! contract has no per-tick trigger, so there is no path by which a tick could
//! arrive here.

use loom_contract::{Decision, Perception, Provenance};

use crate::model::{BackendError, ModelBackend};
use crate::router::route;

/// Turns perceptions into decisions, routing each to a model by importance.
pub struct Mind<B: ModelBackend> {
    backend: B,
}

impl<B: ModelBackend> Mind<B> {
    /// Builds a mind that drafts through `backend`.
    pub fn new(backend: B) -> Self {
        Self { backend }
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
    pub fn decide(&self, perception: &Perception) -> Result<Decision, BackendError> {
        let routed = route(perception.importance);
        let draft = self.backend.draft(routed, perception)?;
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
        })
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::Mind;
    use crate::model::{BackendError, Draft, ModelBackend, ScriptedBackend};
    use crate::router::{is_frontier, ModelRoute};
    use loom_contract::{DecisionImportance, Intent, Perception, SelfState, Trigger};

    /// A backend that misreports what it is: it names a model in its reasoning
    /// and hands back an intent unrelated to the perception. Used to show the
    /// mind, not the backend, owns provenance.
    struct LyingBackend;

    impl ModelBackend for LyingBackend {
        fn draft(
            &self,
            _route: ModelRoute,
            _perception: &Perception,
        ) -> Result<Draft, BackendError> {
            Ok(Draft {
                intent: Intent::Idle,
                reasoning: "answered by definitely-not-the-routed-model".to_owned(),
            })
        }
    }

    /// A backend that always fails, standing in for an unreachable model.
    struct BrokenBackend;

    impl ModelBackend for BrokenBackend {
        fn draft(
            &self,
            _route: ModelRoute,
            _perception: &Perception,
        ) -> Result<Draft, BackendError> {
            Err(BackendError {
                message: "model unreachable".to_owned(),
            })
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
                position: [0.0, 64.0, 0.0],
                inventory_summary: vec![],
            },
            surroundings: vec![],
        }
    }

    #[test]
    fn a_decision_echoes_the_perception_it_answers() {
        let mind = Mind::new(ScriptedBackend);
        let seen = perception(DecisionImportance::Notable);
        let decided = mind.decide(&seen).expect("the stand-in backend decides");
        assert_eq!(decided.request_id, seen.request_id);
        assert_eq!(decided.agent_id, seen.agent_id);
    }

    #[test]
    fn every_decision_is_stamped_with_the_model_and_prompt_that_produced_it() {
        let mind = Mind::new(ScriptedBackend);
        for tier in [
            DecisionImportance::Routine,
            DecisionImportance::Notable,
            DecisionImportance::Pivotal,
        ] {
            let decided = mind
                .decide(&perception(tier))
                .expect("the stand-in backend decides");
            assert_eq!(decided.provenance.tier, tier);
            assert!(!decided.provenance.model.is_empty());
            assert!(!decided.provenance.prompt_version.is_empty());
        }
    }

    #[test]
    fn a_routine_decision_is_never_stamped_with_the_frontier_model() {
        let mind = Mind::new(ScriptedBackend);
        let decided = mind
            .decide(&perception(DecisionImportance::Routine))
            .expect("the stand-in backend decides");
        assert!(!is_frontier(&decided.provenance.model));
    }

    #[test]
    fn provenance_comes_from_the_route_not_from_the_backend() {
        let mind = Mind::new(LyingBackend);
        let decided = mind
            .decide(&perception(DecisionImportance::Routine))
            .expect("the lying backend still drafts");
        // Whatever the backend claimed, the stamp names the routed model.
        assert!(!is_frontier(&decided.provenance.model));
        assert_eq!(decided.provenance.tier, DecisionImportance::Routine);
        assert_eq!(decided.provenance.prompt_version, "routine-v1");
    }

    #[test]
    fn a_failing_backend_yields_an_error_rather_than_a_decision() {
        let mind = Mind::new(BrokenBackend);
        let failed = mind.decide(&perception(DecisionImportance::Notable));
        assert_eq!(
            failed.err().map(|e| e.message),
            Some("model unreachable".to_owned())
        );
    }
}
