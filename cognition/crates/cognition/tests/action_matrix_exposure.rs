//! The model-visible half of the offline action matrix.
//!
//! Every action the strict response schema lets a model answer with must be one the body
//! can carry out from an unchanged perception. An action the body deliberately reports as
//! unsupported must therefore not be answerable at all: a model that spends a pivotal
//! decision on it gets nothing, and the provider was told it was available.
//!
//! Which intents are dormant is read from the one shared classification,
//! `contract/fixtures/capability-support.json`, so no test keeps its own exemption list.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use loom_cognition::openai::wire::decision_schema;
use serde_json::{json, Value};

/// Intents the shared classification marks dormant: never model-visible.
fn body_unsupported() -> Vec<String> {
    let support: Value = serde_json::from_str(include_str!(
        "../../../../contract/fixtures/capability-support.json"
    ))
    .expect("the shared classification parses");
    support["intents"]
        .as_object()
        .expect("intents are classified")
        .iter()
        .filter(|(_, entry)| entry["status"] != "supported")
        .map(|(name, _)| name.clone())
        .collect()
}

fn answer_with(intent: &Value) -> Value {
    json!({ "intent": intent, "reasoning": "matrix", "goal": Value::Null })
}

#[test]
fn no_unsupported_action_is_model_visible_in_the_strict_schema() {
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");
    let exposed: Vec<String> = body_unsupported()
        .into_iter()
        .filter(|action| {
            validator.is_valid(&answer_with(&json!({ "action": action, "text": "hello" })))
        })
        .collect();
    assert!(
        exposed.is_empty(),
        "the strict schema lets a model answer with {exposed:?}, which the player body reports \
         Unsupported: remove it from the model-visible action set or implement it"
    );
}

#[test]
fn no_unsupported_action_is_named_in_the_schema_action_list() {
    let text = serde_json::to_string(decision_schema()).expect("serializes");
    for action in body_unsupported() {
        assert!(
            !text.contains(&format!("\"{action}\"")),
            "the strict schema names the unsupported action `{action}`"
        );
    }
}

#[test]
fn rest_and_idle_remain_distinct_answerable_actions() {
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");
    for action in ["rest", "idle"] {
        assert!(
            validator.is_valid(&answer_with(&json!({ "action": action }))),
            "`{action}` must stay answerable"
        );
    }
}
