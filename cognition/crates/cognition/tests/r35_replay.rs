//! The r35 run's one blocker, replayed against today's request schema from the exact records
//! `research/experiments/2026-10-16-openai-10-agent-10m-r35/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::{Intent, Perception};
use serde_json::{json, Value};

static R35: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r35/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R35
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R35
        .iter()
        .find(|record| record["event"] == "perceived" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no perception {key}"));
    let mut perception = perceived["perception"].clone();
    for field in ["goal", "horizon"] {
        if let Some(value) = decided["shown"].get(field) {
            perception[field] = value.clone();
        }
    }
    (serde_json::from_value(perception).unwrap(), decided)
}

fn valid(schema: &Value, intent: &Value, goal: &Value) -> bool {
    let answer = json!({ "decision": { "intent": intent, "goal": goal }, "reasoning": "a replay" });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

#[test]
fn alpha_could_never_ask_two_crafts_of_a_recipe_its_planks_made_once() {
    // r35 alpha sense-hh (body:00000215 refused craftQuantityExceeded at tick 5107): two planks
    // made one application of sticks, and the schema let it ask two.
    let (seen, decided) = shown("tribe-alpha:loomaa88da42d7-sense-hh");
    let sticks = seen
        .self_state
        .crafting_options
        .iter()
        .find(|option| option.recipe_ref == "rct")
        .unwrap();
    assert_eq!(sticks.max_crafts, 1);
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.crafts_of("rct"), 1);
    assert_eq!(offered.crafts_of("rcr"), 2);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let goal = decided["decision"]
        .get("goal")
        .cloned()
        .unwrap_or(Value::Null);
    assert_eq!(decided["decision"]["intent"]["crafts"], 2);
    assert!(
        !valid(&schema, &decided["decision"]["intent"], &goal),
        "two applications of a recipe that allows one are no answer"
    );
    let once = json!({ "action": "craft", "recipe": "rct", "crafts": 1 });
    assert!(valid(&schema, &once, &goal), "one application still is");
    // And a backend that bypassed the schema is refused before the body sees it.
    assert!(!offered.admits(&Intent::Craft {
        recipe: "rct".to_owned(),
        crafts: 2
    }));
    assert!(offered.admits(&Intent::Craft {
        recipe: "rcr".to_owned(),
        crafts: 2
    }));
}
