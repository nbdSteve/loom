//! Holds the schema Loom asks a model to answer in against the shared contract.
//!
//! The provider enforces [`decision_schema`] on every answer, so that schema is
//! the *only* thing standing between a model and an intent the body can apply.
//! It is hand-written, like both sides' binding types, and it is therefore the
//! one part of this crate that can silently fall behind the contract: a new
//! `Intent` variant would compile everywhere and simply never be reachable,
//! because the provider would refuse to emit an action the schema does not list.
//!
//! So it is pinned against the same fixtures both runtimes are pinned against.
//! Adding a contract case means adding a fixture in both fixture directories,
//! and this test then fails until the request schema lists the new action too.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use loom_cognition::openai::wire::decision_schema;
use serde_json::{json, Value};

/// Every valid `decision-*.json` fixture, as parsed JSON, sorted by path.
fn decision_fixtures() -> Vec<(PathBuf, Value)> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contract/fixtures/valid")
        .canonicalize()
        .expect("contract fixtures resolve from the crate manifest dir");
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read fixtures dir {}: {e}", dir.display()))
        .map(|entry| entry.expect("dir entry").path())
        .filter(|path| {
            path.extension().is_some_and(|ext| ext == "json")
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with("decision-"))
        })
        .collect();
    files.sort();
    assert!(
        !files.is_empty(),
        "no decision fixtures found in {}",
        dir.display()
    );
    files
        .into_iter()
        .map(|path| {
            let text = fs::read_to_string(&path).unwrap();
            let value = serde_json::from_str(&text)
                .unwrap_or_else(|e| panic!("{} is not JSON: {e}", path.display()));
            (path, value)
        })
        .collect()
}

/// An answer of the shape the provider is asked for, carrying `intent`.
fn answer_with(intent: &Value) -> Value {
    answer_with_goal(intent, &Value::Null)
}

fn answer_with_goal(intent: &Value, goal: &Value) -> Value {
    json!({ "intent": intent, "reasoning": "because the fixture says so", "goal": goal })
}

#[test]
fn every_intent_the_contract_can_express_is_one_the_provider_may_answer() {
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");

    for (path, fixture) in decision_fixtures() {
        let intent = &fixture["intent"];
        assert!(
            !intent.is_null(),
            "{} has no intent to check",
            path.display()
        );
        let answer = answer_with_goal(intent, fixture.get("goal").unwrap_or(&Value::Null));
        assert!(
            validator.is_valid(&answer),
            "the contract can express {intent}, but the request schema would not let a model \
             answer it ({fixture_path}): {errors:?}",
            fixture_path = path.display(),
            errors = validator
                .iter_errors(&answer)
                .map(|e| e.to_string())
                .collect::<Vec<_>>()
        );
    }
}

#[test]
fn the_schema_lists_exactly_the_actions_the_contract_uses() {
    // Equality both ways, deliberately. A missing action is an intent no model
    // can ever choose; an extra one is an action Loom invites and the body has no
    // way to apply.
    let in_schema: BTreeSet<String> = decision_schema()["properties"]["intent"]["anyOf"]
        .as_array()
        .expect("the intent union is an anyOf")
        .iter()
        .map(|variant| {
            variant["properties"]["action"]["enum"][0]
                .as_str()
                .expect("each variant is tagged with one action")
                .to_owned()
        })
        .collect();
    let in_fixtures: BTreeSet<String> = decision_fixtures()
        .iter()
        .map(|(path, fixture)| {
            fixture["intent"]["action"]
                .as_str()
                .unwrap_or_else(|| panic!("{} has no intent action", path.display()))
                .to_owned()
        })
        .collect();
    assert_eq!(
        in_schema, in_fixtures,
        "the request schema and the contract fixtures name different actions"
    );
}

#[test]
fn an_answer_the_body_could_not_apply_is_rejected_by_the_schema() {
    // Proves the validation above has teeth rather than accepting anything.
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");
    let refused = [
        json!({ "action": "detonate", "target": "the village" }),
        json!({ "action": "say" }),
        json!({ "action": "say", "text": "hi", "volume": 11 }),
        json!({ "action": "flee", "from": 7 }),
        json!("idle"),
    ];
    for intent in &refused {
        assert!(
            !validator.is_valid(&answer_with(intent)),
            "{intent} is not an intent the body can apply, so the schema must refuse it"
        );
    }
    assert!(
        !validator.is_valid(&json!({ "intent": { "action": "idle" } })),
        "reasoning is required: it is the permanent record of why an agent acted"
    );
}

#[test]
fn every_goal_disposition_the_contract_names_is_one_the_provider_may_answer() {
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");
    let idle = json!({ "action": "idle" });
    let accepted = [
        Value::Null,
        json!({
            "disposition": "adopt",
            "successItem": "item.minecraft.oak_log",
            "successCount": 4
        }),
        json!({
            "disposition": "continue",
            "stepResult": "done"
        }),
        json!({
            "disposition": "revise",
            "successItem": "item.minecraft.oak_log",
            "successCount": 4,
            "stepResult": "noProgress"
        }),
        json!({
            "disposition": "replace",
            "successItem": "item.minecraft.stick",
            "successCount": 2,
            "stepResult": "noProgress"
        }),
        json!({ "disposition": "complete", "stepResult": "done" }),
        json!({ "disposition": "abandon", "stepResult": "noProgress" }),
    ];
    for goal in &accepted {
        assert!(
            validator.is_valid(&answer_with_goal(&idle, goal)),
            "{goal} is a goal choice the contract can carry, so the schema must admit it"
        );
    }
    let in_schema: BTreeSet<String> = decision_schema()["properties"]["goal"]["anyOf"]
        .as_array()
        .expect("the goal choice is an anyOf")
        .iter()
        .filter_map(|variant| variant["properties"]["disposition"]["enum"][0].as_str())
        .map(str::to_owned)
        .collect();
    let in_contract: BTreeSet<String> =
        serde_json::from_str::<Value>(include_str!("../../../../contract/loom.schema.json"))
            .unwrap()["$defs"]["GoalDisposition"]["enum"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| value.as_str().unwrap().to_owned())
            .collect();
    assert_eq!(
        in_schema, in_contract,
        "the request schema must offer every disposition"
    );
}

#[test]
fn a_goal_choice_with_the_wrong_fields_is_rejected_by_the_schema() {
    let validator = jsonschema::validator_for(decision_schema()).expect("the schema compiles");
    let idle = json!({ "action": "idle" });
    let refused = [
        json!({ "disposition": "adopt" }),
        json!({ "disposition": "adopt", "statement": "g", "step": "s" }),
        json!({ "disposition": "revise" }),
        json!({ "disposition": "continue" }),
        json!({ "disposition": "continue", "step": "s" }),
        json!({ "disposition": "continue", "step": "s", "stepResult": "maybe" }),
        json!({ "disposition": "complete" }),
        json!({ "disposition": "continue", "statement": "keep going", "step": "s", "stepResult": "done" }),
        json!({ "disposition": "complete", "statement": "done" }),
        json!({ "disposition": "abandon", "statement": "give up" }),
        json!({ "disposition": "adopt", "statement": "go", "priority": 1 }),
        json!({ "disposition": "pursue" }),
        json!({ "disposition": "adopt", "statement": 7 }),
        // Untyped: a success condition with no item. And from routine-v23 a stated condition or
        // a typed step: the body types both from the item, the count and the intent.
        json!({ "disposition": "adopt", "statement": "g", "step": "s" }),
        json!({ "disposition": "adopt", "statement": "g", "successCondition": "c", "successItem": "item.minecraft.stick", "successCount": 1, "step": "s" }),
        json!({ "disposition": "continue", "step": "s", "stepAction": "dig", "stepRef": "b4", "stepResult": "done" }),
        json!("continue"),
    ];
    for goal in &refused {
        assert!(
            !validator.is_valid(&answer_with_goal(&idle, goal)),
            "{goal} is not one closed goal choice, so the schema must refuse it"
        );
    }
    assert!(
        !validator.is_valid(&json!({ "intent": idle, "reasoning": "r" })),
        "strict mode requires the goal key, so no choice is an explicit null"
    );
}
