//! The shelter goal through the strict request schema: what a review is offered as night nears,
//! inside a pit it cannot walk out of, sealed in, while it gathers a closer, and while a resource
//! subgoal an older routine set feeds the shelter.
//!
//! r36's ten agents all died at night in the open, every death under hostile attack, and none was
//! ever offered anything a hiding place could meet. These pin, scene by scene, that a shelter goal
//! is offered only with a step its cover reading names, that each step is exactly one of those, and
//! that danger in a pit is answered by closing it rather than by a flight that cannot run.

#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::decision_schema_for;
use loom_contract::Perception;
use serde_json::{json, Value};

fn scene(name: &str) -> Perception {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("{name}.json"));
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

fn schema_of(perception: &Perception) -> Value {
    decision_schema_for(&OfferedRefs::of(perception))
}

fn accepts(schema: &Value, intent: &Value, goal: &Value) -> bool {
    let validator = jsonschema::validator_for(schema).expect("the request schema compiles");
    let answer = if schema["properties"].get("decision").is_some() {
        json!({ "intent": intent, "reasoning": "a test answer", "plan": { "update": "continue" }, "goal": goal })
    } else {
        json!({ "intent": intent, "reasoning": "a test answer", "plan": { "update": "continue" } })
    };
    validator.is_valid(&answer)
}

#[test]
fn danger_in_a_pit_is_answered_by_closing_it_never_by_a_flight_that_cannot_run() {
    let damaged = scene("shelter-pit-damaged");
    let offered = OfferedRefs::of(&damaged);
    assert!(offered.in_immediate_danger());
    let schema = schema_of(&damaged);
    let roof = json!({ "action": "place", "block": "b2", "item": "s1", "face": "west" });
    assert!(accepts(&schema, &roof, &Value::Null));
    assert!(!accepts(
        &schema,
        &json!({ "action": "flee", "from": "e1" }),
        &Value::Null
    ));
}
