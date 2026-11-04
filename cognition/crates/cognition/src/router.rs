//! The tiered routing table: how much model a decision is worth, which model
//! that resolves to, and at what prompt version.
//!
//! Routing is by decision importance, never by anything else, and it happens in
//! two steps that are deliberately separate:
//!
//! 1. [`class_of`] maps a [`DecisionImportance`] to a [`ModelClass`] - *how much
//!    model* the decision is worth. This is Loom's own judgement and names no
//!    model and no provider, so it reads the same whoever is answering.
//! 2. A [`Router`], holding the [`ModelCatalog`] the runtime was configured
//!    with, resolves that class to the concrete model id that will answer.
//!
//! Keeping them apart is what lets model ids be configuration: the domain rule
//! ("a routine decision is not worth the deep model") is fixed and testable,
//! while the two ids it resolves through are an operator's to set. See
//! `cognition/README.md` for the settings.
//!
//! The two rules that matter for Loom's cost and research honesty are:
//!
//! - the deep model is reachable only from [`DecisionImportance::Pivotal`], and
//! - whatever answered is stamped on the decision's provenance, so "which
//!   decisions used which model" is answerable from the record alone.
//!
//! The first is structural rather than asserted: [`class_of`] is a total match
//! with no default branch, so a new importance tier will not compile until its
//! class is chosen. The second is too: [`ModelRoute`]'s fields are private and
//! its only constructor is [`Router::route`], so no other code - a backend least
//! of all - can pair a tier with a model of its choosing.

use std::sync::Arc;

use loom_contract::DecisionImportance;

use crate::prompt::{
    NOTABLE_PROMPT_VERSION, PIVOTAL_PROMPT_VERSION, PLANNING_PROMPT_VERSION, ROUTINE_PROMPT_VERSION,
};

/// How much model a decision is worth.
///
/// Two classes rather than one per tier: the expensive class exists for the
/// decisions that can end an agent, and everything else shares the cheap one.
/// The tier is still carried separately, so collapsing the classes does not cost
/// the record any detail - a `NOTABLE` decision is still stamped `NOTABLE` and
/// still gets its own prompt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ModelClass {
    /// The small, cheap, quick model. Answers the overwhelming majority of
    /// decisions.
    Fast,
    /// The large model. Reserved for decisions an agent may not survive getting
    /// wrong.
    Deep,
    /// The planner, which keeps the agent's plan.
    Planning,
}

impl ModelClass {
    /// The class's name, for logs and for configuration documentation.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Fast => "fast",
            Self::Deep => "deep",
            Self::Planning => "planning",
        }
    }
}

/// How much model each importance tier is worth.
///
/// Total over [`DecisionImportance`] with no default branch, on purpose: a tier
/// silently defaulting is how routine traffic ends up on an expensive model.
///
/// - **Routine** is the ordinary case and by far the most frequent, so it is
///   fast. Its whole point is being cheap enough to run often.
/// - **Notable** is fast as well. It matters - another party is waiting, and the
///   answer becomes part of a relationship - but it is not a decision the agent
///   can die of, and it is a decision someone is waiting on, so the cheaper and
///   quicker class is the right trade. It gets its own prompt instead.
/// - **Pivotal** is deep. It is the tier for the events that can end the agent,
///   and Loom is permadeath: there is no second body, so this decision is worth
///   the expensive model every time.
#[must_use]
pub const fn class_of(importance: DecisionImportance) -> ModelClass {
    match importance {
        DecisionImportance::Routine | DecisionImportance::Notable => ModelClass::Fast,
        DecisionImportance::Pivotal => ModelClass::Deep,
        DecisionImportance::Planning => ModelClass::Planning,
    }
}

/// The prompt template version used at `importance`.
///
/// One per tier, so a decision's provenance identifies the exact prompt that
/// produced it. The strings live in [`crate::prompt`], next to the text they
/// name, so a template edit and its version bump are the same diff.
#[must_use]
pub const fn prompt_version_of(importance: DecisionImportance) -> &'static str {
    match importance {
        DecisionImportance::Routine => ROUTINE_PROMPT_VERSION,
        DecisionImportance::Notable => NOTABLE_PROMPT_VERSION,
        DecisionImportance::Pivotal => PIVOTAL_PROMPT_VERSION,
        DecisionImportance::Planning => PLANNING_PROMPT_VERSION,
    }
}

/// The model id that answers at each [`ModelClass`].
///
/// A plain value: the ids come from configuration, and validating them - that
/// they are set, and not blank - belongs where they enter the process, in
/// [`crate::config`]. A catalog built from literals in code cannot be blank, so
/// making this constructor fallible would only push a check nobody needs onto
/// every caller.
///
/// The two ids may be the same. That is what the scripted stand-in does: it is
/// not a model at all, so it names itself at both classes rather than claiming
/// two models it does not have.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelCatalog {
    fast: Arc<str>,
    deep: Arc<str>,
    planning: Arc<str>,
}

impl ModelCatalog {
    /// A catalog naming `fast` at [`ModelClass::Fast`] and `deep` at
    /// [`ModelClass::Deep`].
    #[must_use]
    pub fn new(fast: &str, deep: &str) -> Self {
        Self {
            fast: Arc::from(fast),
            deep: Arc::from(deep),
            planning: Arc::from(deep),
        }
    }

    /// This catalog with `planning` at [`ModelClass::Planning`]; [`Self::new`] names the deep model there.
    #[must_use]
    pub fn with_planning(self, planning: &str) -> Self {
        Self {
            planning: Arc::from(planning),
            ..self
        }
    }

    /// A catalog naming one thing at both classes.
    #[must_use]
    pub fn uniform(model: &str) -> Self {
        Self::new(model, model)
    }

    /// The model id that answers at `class`.
    #[must_use]
    pub fn model(&self, class: ModelClass) -> &str {
        match class {
            ModelClass::Fast => &self.fast,
            ModelClass::Deep => &self.deep,
            ModelClass::Planning => &self.planning,
        }
    }
}

/// A resolved route: the tier a decision was taken at, how much model that is
/// worth, the model that answers, and the prompt version used.
///
/// Fields are private and the only constructor is [`Router::route`], which makes
/// "the deep model is reachable only from `Pivotal`" a property of the type
/// rather than a convention callers have to remember.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelRoute {
    tier: DecisionImportance,
    class: ModelClass,
    model: Arc<str>,
    prompt_version: &'static str,
}

impl ModelRoute {
    /// The importance tier this route was resolved for.
    #[must_use]
    pub const fn tier(&self) -> DecisionImportance {
        self.tier
    }

    /// How much model this tier is worth.
    #[must_use]
    pub const fn class(&self) -> ModelClass {
        self.class
    }

    /// Identifier of the model that answers at this route.
    #[must_use]
    pub fn model(&self) -> &str {
        &self.model
    }

    /// Version of the prompt template used at this tier.
    #[must_use]
    pub const fn prompt_version(&self) -> &'static str {
        self.prompt_version
    }
}

/// Resolves an importance to the route that answers it, through the catalog the
/// runtime was configured with.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Router {
    catalog: ModelCatalog,
}

impl Router {
    /// Builds a router that resolves classes through `catalog`.
    #[must_use]
    pub const fn new(catalog: ModelCatalog) -> Self {
        Self { catalog }
    }

    /// The catalog this router resolves through, for startup logging and for
    /// tests that need to know which id a class should have produced.
    #[must_use]
    pub const fn catalog(&self) -> &ModelCatalog {
        &self.catalog
    }

    /// Resolves the route for `importance`.
    ///
    /// The only way to obtain a [`ModelRoute`]. Cognition is event-driven, so
    /// this is called once per triggering event and never per tick.
    #[must_use]
    pub fn route(&self, importance: DecisionImportance) -> ModelRoute {
        let class = class_of(importance);
        ModelRoute {
            tier: importance,
            class,
            model: Arc::from(self.catalog.model(class)),
            prompt_version: prompt_version_of(importance),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{class_of, ModelCatalog, ModelClass, Router};
    use loom_contract::DecisionImportance;

    const ALL_TIERS: [DecisionImportance; 3] = [
        DecisionImportance::Routine,
        DecisionImportance::Notable,
        DecisionImportance::Pivotal,
    ];

    fn test_router() -> Router {
        Router::new(ModelCatalog::new("small-model", "large-model"))
    }

    #[test]
    fn a_routine_decision_is_never_worth_the_deep_class() {
        assert_eq!(class_of(DecisionImportance::Routine), ModelClass::Fast);
    }

    #[test]
    fn only_pivotal_reaches_the_deep_class() {
        for tier in ALL_TIERS {
            assert_eq!(
                class_of(tier) == ModelClass::Deep,
                tier == DecisionImportance::Pivotal,
                "the deep class must be reachable from Pivotal and no other tier, tier {tier:?}"
            );
        }
    }

    #[test]
    fn the_stamped_model_is_the_one_the_catalog_names_for_the_class() {
        let router = test_router();
        for tier in ALL_TIERS {
            let chosen = router.route(tier);
            assert_eq!(chosen.model(), router.catalog().model(class_of(tier)));
            assert_eq!(chosen.class(), class_of(tier));
        }
        assert_eq!(
            router.route(DecisionImportance::Pivotal).model(),
            "large-model"
        );
        assert_eq!(
            router.route(DecisionImportance::Routine).model(),
            "small-model"
        );
    }

    #[test]
    fn every_tier_keeps_its_own_prompt_version_even_where_the_model_is_shared() {
        let router = test_router();
        let all_routes: Vec<_> = ALL_TIERS.into_iter().map(|t| router.route(t)).collect();
        for (i, first) in all_routes.iter().enumerate() {
            for second in &all_routes[i + 1..] {
                assert_ne!(
                    first.prompt_version(),
                    second.prompt_version(),
                    "each tier must have its own prompt version so provenance identifies the prompt"
                );
            }
        }
    }

    #[test]
    fn a_catalog_may_name_one_thing_at_both_classes() {
        let router = Router::new(ModelCatalog::uniform("scripted-stand-in"));
        for tier in ALL_TIERS {
            assert_eq!(router.route(tier).model(), "scripted-stand-in");
        }
    }

    #[test]
    fn the_route_carries_back_the_tier_it_was_asked_for() {
        let router = test_router();
        for tier in ALL_TIERS {
            assert_eq!(router.route(tier).tier(), tier);
        }
    }

    #[test]
    fn a_class_has_a_name_configuration_can_be_written_against() {
        assert_eq!(ModelClass::Fast.label(), "fast");
        assert_eq!(ModelClass::Deep.label(), "deep");
    }
}
