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

use loom_cognition::offered::{OfferedRefs, Sheltering};
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

fn scene(name: &str) -> Perception {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("{name}.json"));
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

fn schema_of(perception: &Perception) -> Value {
    decision_schema_for(&OfferedRefs::of(perception), GoalPhase::of(perception))
}

fn accepts(schema: &Value, intent: &Value, goal: &Value) -> bool {
    let validator = jsonschema::validator_for(schema).expect("the request schema compiles");
    let answer = if schema["properties"].get("decision").is_some() {
        json!({ "decision": { "intent": intent, "goal": goal }, "reasoning": "a test answer" })
    } else {
        json!({ "intent": intent, "reasoning": "a test answer", "goal": goal })
    };
    validator.is_valid(&answer)
}

#[test]
fn at_dusk_on_open_grass_a_shelter_goal_is_adopted_only_by_the_descent_underfoot() {
    let dusk = scene("shelter-dusk-open-grass");
    let offered = OfferedRefs::of(&dusk);
    assert!(offered.shelter.offered());
    let schema = schema_of(&dusk);
    let adopt = json!({ "disposition": "adopt", "shelter": true });
    assert!(accepts(
        &schema,
        &json!({ "action": "dig", "block": "b1" }),
        &adopt
    ));
    // A dig beside the feet deepens nothing, and a walk away is no shelter step.
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b2" }),
        &adopt
    ));
    assert!(!accepts(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &adopt
    ));
    // A shelter goal names no item.
    let named = json!({ "disposition": "adopt", "shelter": true, "successItem": "item.minecraft.dirt", "successCount": 1 });
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b1" }),
        &named
    ));
}

#[test]
fn at_midday_no_shelter_goal_is_offered() {
    let midday = scene("shelter-midday-open-grass");
    assert!(!OfferedRefs::of(&midday).shelter.offered());
    let schema = schema_of(&midday);
    let adopt = json!({ "disposition": "adopt", "shelter": true });
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b1" }),
        &adopt
    ));
}

#[test]
fn a_held_shelter_goal_is_continued_by_the_closing_its_cover_names_with_the_block_in_hand() {
    let pit = scene("shelter-pit-roof-closable");
    let offered = OfferedRefs::of(&pit);
    assert_eq!(offered.sheltering, Sheltering::Held);
    // No short walk leads out of a pit three deep: no flight and no walk to look further.
    assert!(offered.flee_targets.is_empty());
    let schema = schema_of(&pit);
    let continued = json!({ "disposition": "continue", "stepResult": "progressing" });
    let roof = json!({ "action": "place", "block": "b2", "item": "s1", "face": "west" });
    assert!(accepts(&schema, &roof, &continued));
    // The wrong side of the support closes nothing.
    let wrong = json!({ "action": "place", "block": "b2", "item": "s1", "face": "up" });
    assert!(!accepts(&schema, &wrong, &continued));
    // A shelter goal has no item to revise.
    let revise = json!({ "disposition": "revise", "successItem": "item.minecraft.dirt", "successCount": 4, "stepResult": "progressing" });
    assert!(!accepts(&schema, &roof, &revise));
}

#[test]
fn a_solid_block_not_in_hand_is_equipped_first() {
    let pit = scene("shelter-pit-dirt-not-in-hand");
    let schema = schema_of(&pit);
    let continued = json!({ "disposition": "continue", "stepResult": "progressing" });
    let equip = json!({ "action": "equip", "item": "s2", "destination": "hand" });
    assert!(accepts(&schema, &equip, &continued));
    // A placement must already be holding the block, which here it does not.
    let roof = json!({ "action": "place", "block": "b2", "item": "s2", "face": "west" });
    assert!(!accepts(&schema, &roof, &continued));
    // Nor does equipping the log in hand help a shelter.
    let log = json!({ "action": "equip", "item": "s1", "destination": "hand" });
    assert!(!accepts(&schema, &log, &continued));
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

#[test]
fn sealed_in_a_shelter_is_completed_and_stayed_in_and_no_flight_is_offered() {
    let sealed = scene("shelter-sealed-met");
    let offered = OfferedRefs::of(&sealed);
    assert!(offered.shelter.sealed());
    assert!(offered.danger.is_empty() && offered.flee_targets.is_empty());
    let schema = schema_of(&sealed);
    let complete = json!({ "disposition": "complete", "stepResult": "done" });
    assert!(accepts(&schema, &json!({ "action": "rest" }), &complete));
    let continued = json!({ "disposition": "continue", "stepResult": "done" });
    assert!(!accepts(&schema, &json!({ "action": "rest" }), &continued));
}

#[test]
fn nothing_solid_to_close_natural_cover_with_is_gathered_as_a_step_of_the_shelter_itself() {
    let cover = scene("shelter-cover-gathers");
    let offered = OfferedRefs::of(&cover);
    assert_eq!(offered.shelter.gathers, vec!["b8".to_owned()]);
    let schema = schema_of(&cover);
    let continued = json!({ "disposition": "continue", "stepResult": "progressing" });
    assert!(accepts(
        &schema,
        &json!({ "action": "dig", "block": "b8" }),
        &continued
    ));
    // Stone gives a bare hand nothing, so it gathers nothing to close a face with.
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b7" }),
        &continued
    ));
    // Gathering is no goal of its own: a resource subgoal under the shelter is not offered (r39:
    // alpha's dirt subgoal changed the objective's identity twice and dug its own shelter's wall).
    let feeds = json!({ "disposition": "adopt", "successItem": "item.minecraft.dirt", "successCount": 2, "feeds": true });
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b8" }),
        &feeds
    ));
}

#[test]
fn a_met_subgoal_is_completed_and_the_shelter_it_fed_takes_the_closing() {
    let met = scene("shelter-subgoal-met");
    let offered = OfferedRefs::of(&met);
    assert_eq!(offered.sheltering, Sheltering::Feeding);
    let schema = schema_of(&met);
    let complete = json!({ "disposition": "complete", "stepResult": "done" });
    let close = json!({ "action": "place", "block": "b7", "item": "s1", "face": "up" });
    assert!(accepts(&schema, &close, &complete));
    // A subgoal is never replaced: that would drop the shelter it feeds.
    let replace = json!({ "disposition": "replace", "successItem": "item.minecraft.dirt", "successCount": 2, "stepResult": "done" });
    assert!(!accepts(&schema, &close, &replace));
}
