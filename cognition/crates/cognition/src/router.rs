//! The tiered routing table: which model answers a decision, and at what
//! prompt version.
//!
//! Routing is by decision importance, never by anything else, and it is total:
//! [`route`] is a match over every [`DecisionImportance`], so there is no
//! unrouted importance and no default that could quietly send routine traffic
//! to an expensive model. The two rules that matter for Loom's cost and
//! research honesty are:
//!
//! - a routine decision never reaches the frontier model, and
//! - whatever answered is stamped on the decision's provenance, so "which
//!   decisions used which model" is answerable from the record alone.
//!
//! The first rule is structural rather than asserted: [`ModelRoute`]'s fields
//! are private and its only constructor is [`route`], so no other code can
//! pair a tier with a model of its choosing.

use loom_contract::DecisionImportance;

/// The small model. Answers the overwhelming majority of decisions.
const ROUTINE_MODEL: &str = "claude-haiku-4-5";

/// The mid model. Answers decisions that carry social or material weight.
const NOTABLE_MODEL: &str = "claude-sonnet-5";

/// The frontier model. Reachable only from [`DecisionImportance::Pivotal`].
const PIVOTAL_MODEL: &str = "claude-opus-5";

/// Prompt template versions, one per tier. Bumped whenever a tier's prompt
/// changes, so a decision's provenance identifies the exact prompt that
/// produced it.
const ROUTINE_PROMPT: &str = "routine-v1";
const NOTABLE_PROMPT: &str = "notable-v1";
const PIVOTAL_PROMPT: &str = "pivotal-v1";

/// A resolved route: the tier a decision was taken at, the model that answers
/// it, and the prompt version used.
///
/// Fields are private and the only constructor is [`route`], which makes
/// "routine never reaches the frontier model" a property of the type rather
/// than a convention callers have to remember.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ModelRoute {
    tier: DecisionImportance,
    model: &'static str,
    prompt_version: &'static str,
}

impl ModelRoute {
    /// The importance tier this route was resolved for.
    #[must_use]
    pub fn tier(&self) -> DecisionImportance {
        self.tier
    }

    /// Identifier of the model that answers at this tier.
    #[must_use]
    pub fn model(&self) -> &'static str {
        self.model
    }

    /// Version of the prompt template used at this tier.
    #[must_use]
    pub fn prompt_version(&self) -> &'static str {
        self.prompt_version
    }
}

/// Resolves the route for `importance`.
///
/// The only way to obtain a [`ModelRoute`]. Cognition is event-driven, so this
/// is called once per triggering event and never per tick.
#[must_use]
pub fn route(importance: DecisionImportance) -> ModelRoute {
    match importance {
        DecisionImportance::Routine => ModelRoute {
            tier: importance,
            model: ROUTINE_MODEL,
            prompt_version: ROUTINE_PROMPT,
        },
        DecisionImportance::Notable => ModelRoute {
            tier: importance,
            model: NOTABLE_MODEL,
            prompt_version: NOTABLE_PROMPT,
        },
        DecisionImportance::Pivotal => ModelRoute {
            tier: importance,
            model: PIVOTAL_MODEL,
            prompt_version: PIVOTAL_PROMPT,
        },
    }
}

/// Whether `model` is the frontier model, the most expensive tier.
///
/// Exposed so a caller - or a test - can ask the question without hardcoding
/// the model identifier a second time.
#[must_use]
pub fn is_frontier(model: &str) -> bool {
    model == PIVOTAL_MODEL
}

#[cfg(test)]
mod tests {
    use super::{is_frontier, route, ModelRoute};
    use loom_contract::DecisionImportance;

    const ALL_TIERS: [DecisionImportance; 3] = [
        DecisionImportance::Routine,
        DecisionImportance::Notable,
        DecisionImportance::Pivotal,
    ];

    #[test]
    fn routine_never_reaches_the_frontier_model() {
        let routed = route(DecisionImportance::Routine);
        assert!(
            !is_frontier(routed.model()),
            "routine decisions must never be answered by the frontier model, got {}",
            routed.model()
        );
    }

    #[test]
    fn only_pivotal_reaches_the_frontier_model() {
        for tier in ALL_TIERS {
            let routed = route(tier);
            assert_eq!(
                is_frontier(routed.model()),
                tier == DecisionImportance::Pivotal,
                "the frontier model must be reachable from Pivotal and no other tier, tier {tier:?}"
            );
        }
    }

    #[test]
    fn every_tier_routes_to_a_distinct_model_and_prompt() {
        let routes: Vec<ModelRoute> = ALL_TIERS.into_iter().map(route).collect();
        for (i, first) in routes.iter().enumerate() {
            for second in &routes[i + 1..] {
                assert_ne!(
                    first.model(),
                    second.model(),
                    "each tier must have its own model so routing is visible in provenance"
                );
                assert_ne!(
                    first.prompt_version(),
                    second.prompt_version(),
                    "each tier must have its own prompt version"
                );
            }
        }
    }

    #[test]
    fn the_route_carries_back_the_tier_it_was_asked_for() {
        for tier in ALL_TIERS {
            assert_eq!(route(tier).tier(), tier);
        }
    }
}
