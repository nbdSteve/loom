//! Holds the schema one request sends to exactly the refs its perception offers.
//!
//! [`decision_schema_for`] is the machine constraint that keeps a model from naming a
//! creature to `dig`, a stack to `moveTo`, the block under its own feet as a walk, or a
//! handle no message ever carried. It is checked here with a real validator rather than
//! by inspecting its text, against the same fixtures both runtimes round-trip.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::{
    CraftingOption, InventoryStack, Observation, ObservationKind, Perception,
    MAX_PERCEPTION_OBSERVATIONS,
};
use serde_json::{json, Value};

fn fixture(name: &str) -> Perception {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contract/fixtures/valid")
        .join(format!("{name}.json"));
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

/// Whether `schema` couples each intent to a goal choice, as a goal review's does.
fn coupled(schema: &Value) -> bool {
    schema["properties"].get("decision").is_some()
}

/// `intent` beside `goal`, in the shape `schema` asks for.
fn answer_in(schema: &Value, intent: &Value, goal: &Value) -> Value {
    if coupled(schema) {
        json!({ "decision": { "intent": intent, "goal": goal }, "reasoning": "a test answer" })
    } else {
        json!({ "intent": intent, "reasoning": "a test answer", "goal": goal })
    }
}

/// Whether `schema` admits `intent` beside no goal choice or an abandon, whichever it offers.
fn accepts(schema: &Value, intent: &Value) -> bool {
    let validator = jsonschema::validator_for(schema).expect("the request schema compiles");
    [
        json!(null),
        json!({ "disposition": "abandon", "stepResult": "done" }),
    ]
    .iter()
    .any(|goal| validator.is_valid(&answer_in(schema, intent, goal)))
}

/// Every intent variant `schema` offers.
fn intent_variants(schema: &Value) -> Vec<Value> {
    if coupled(schema) {
        ["activeIntent", "passiveIntent"]
            .iter()
            .flat_map(|name| schema["$defs"][name]["anyOf"].as_array().unwrap().clone())
            .collect()
    } else {
        schema["properties"]["intent"]["anyOf"]
            .as_array()
            .unwrap()
            .clone()
    }
}

fn actions(schema: &Value) -> Vec<String> {
    intent_variants(schema)
        .iter()
        .map(|variant| {
            variant["properties"]["action"]["enum"][0]
                .as_str()
                .unwrap()
                .to_owned()
        })
        .collect()
}

/// Every string enum value anywhere in `schema`, counted the way the provider counts them.
fn enum_values(schema: &Value, count: &mut usize, characters: &mut usize) {
    match schema {
        Value::Object(map) => {
            if let Some(Value::Array(values)) = map.get("enum") {
                *count += values.len();
                *characters += values
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::len)
                    .sum::<usize>();
            }
            for value in map.values() {
                enum_values(value, count, characters);
            }
        }
        Value::Array(values) => {
            for value in values {
                enum_values(value, count, characters);
            }
        }
        _ => {}
    }
}

#[test]
fn a_ref_of_the_wrong_namespace_is_refused_by_the_schema_itself() {
    let schema = decision_schema_for(
        &OfferedRefs::of(&fixture("perception-threat-sighted")),
        GoalPhase::of(&fixture("perception-threat-sighted")),
    );
    assert!(accepts(&schema, &json!({ "action": "flee", "from": "e1" })));
    assert!(accepts(
        &schema,
        &json!({ "action": "moveTo", "onto": "b1" })
    ));
    for wrong in [
        json!({ "action": "flee", "from": "b1" }),
        json!({ "action": "dig", "block": "e1" }),
        json!({ "action": "moveTo", "onto": "e2" }),
        json!({ "action": "sleep", "bed": "e1" }),
        json!({ "action": "dig", "block": "b2" }),
        // The oak log is nine blocks off: a walk target, but past reach to dig.
        json!({ "action": "dig", "block": "b1" }),
    ] {
        assert!(!accepts(&schema, &wrong), "{wrong}");
    }
}

#[test]
fn the_block_underfoot_is_offered_to_dig_and_never_to_a_walk() {
    let schema = decision_schema_for(
        &OfferedRefs::of(&fixture("perception-standing-on")),
        GoalPhase::of(&fixture("perception-standing-on")),
    );
    assert_eq!(schema["$defs"]["walkTargetRef"]["enum"], json!(["b3"]));
    for here in ["b1", "b2"] {
        assert!(accepts(&schema, &json!({ "action": "dig", "block": here })));
        assert!(!accepts(
            &schema,
            &json!({ "action": "moveTo", "onto": here })
        ));
    }
    assert!(accepts(
        &schema,
        &json!({ "action": "moveTo", "onto": "b3" })
    ));
}

#[test]
fn a_block_whose_dig_would_trap_the_agent_is_never_offered_to_dig() {
    // r13 eta, body:00000043 to body:00000270: four digs of its own floor left a 1x1 shaft
    // and three flights with no route out.
    let mut seen = fixture("perception-standing-on");
    seen.surroundings[0].dig_safe = Some(false);
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(schema["$defs"]["keptBlockRef"]["enum"], json!(["b1"]));
    assert_eq!(schema["$defs"]["digBlockRef"]["enum"], json!(["b2", "b3"]));
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b1" })
    ));
    assert!(accepts(&schema, &json!({ "action": "dig", "block": "b2" })));
    // The block stays a block for everything a dig is not.
    for block in ["b1", "b2"] {
        assert!(accepts(
            &schema,
            &json!({ "action": "sleep", "bed": block })
        ));
    }
}

#[test]
fn an_action_with_nothing_offered_to_name_is_not_offered() {
    let mut seen = fixture("perception-standing-on");
    seen.surroundings.clear();
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(actions(&schema), ["say", "rest", "idle"]);
    // Only the intent groups a goal review pairs its choices with; no ref namespace.
    let defs: Vec<&String> = schema["$defs"].as_object().unwrap().keys().collect();
    assert_eq!(defs, ["activeIntent", "passiveIntent", "steppingIntent"]);

    let crafting = decision_schema_for(
        &OfferedRefs::of(&fixture("perception-crafting")),
        GoalPhase::of(&fixture("perception-crafting")),
    );
    assert_eq!(
        actions(&crafting),
        ["say", "eat", "equip", "craft", "rest", "idle"]
    );
    assert!(accepts(
        &crafting,
        &json!({ "action": "craft", "recipe": "r1", "crafts": 1 })
    ));
    assert!(!accepts(
        &crafting,
        &json!({ "action": "craft", "recipe": "i1", "crafts": 1 })
    ));
}

#[test]
fn every_offered_ref_is_one_enum_once_whichever_actions_spend_it() {
    let offered = OfferedRefs::of(&fixture("perception-max-observations"));
    let schema = decision_schema_for(&offered, GoalPhase::Any);
    let mut count = 0;
    let mut characters = 0;
    enum_values(&schema["$defs"], &mut count, &mut characters);
    assert_eq!(
        count,
        offered.dig_blocks.len()
            + offered.kept_blocks.len()
            + offered.walk_targets.len()
            + offered.creatures.len()
            + offered.strike_targets.len()
            + offered.pursuit_targets.len()
            + offered.pickup_items.len()
            + offered.route_items.len()
    );
    for variant in intent_variants(&schema) {
        for (name, field) in variant["properties"].as_object().unwrap() {
            if ["action", "face", "destination", "approach"].contains(&name.as_str()) {
                continue;
            }
            assert!(
                field.get("enum").is_none(),
                "a ref enum must live in $defs, not inline: {variant}"
            );
        }
    }
}

#[test]
fn the_widest_request_schema_stays_inside_the_providers_enum_limits() {
    // The worst case the contract admits: every observation a block, so each one is
    // offered to dig, place, sleep and a walk, with full inventory and recipe lists.
    let mut seen = fixture("perception-crafting");
    let block = Observation {
        kind: ObservationKind::Block,
        what: "block.minecraft.dirt".to_owned(),
        distance: 3.0,
        direction: loom_contract::Bearing::North,
        vertical_offset: 0.0,
        visible: true,
        target_ref: String::new(),
        count: None,
        standable: Some(true),
        yields: None,
        dig_safe: Some(true),
        route_refused: None,
    };
    // Each block yields eight items of the contract's longest name, and one is unsafe to dig.
    seen.surroundings = (0..MAX_PERCEPTION_OBSERVATIONS)
        .map(|index| Observation {
            target_ref: format!("b{index:03}"),
            yields: Some(
                (0..loom_contract::MAX_BLOCK_YIELDS)
                    .map(|item| loom_contract::BlockYield {
                        what: format!("item.minecraft.{:0>49}", index * 8 + item),
                        least: 0,
                        most: 64,
                    })
                    .collect(),
            ),
            dig_safe: Some(index != 0),
            route_refused: None,
            ..block.clone()
        })
        .collect();
    let stack = seen.self_state.inventory_summary[0].clone();
    seen.self_state.inventory_summary = (0..46)
        .map(|index| InventoryStack {
            slot_ref: format!("i{index:02}"),
            ..stack.clone()
        })
        .collect();
    let option = seen.self_state.crafting_options[0].clone();
    seen.self_state.crafting_options = (0..48)
        .map(|index| CraftingOption {
            recipe_ref: format!("r{index:02}"),
            ..option.clone()
        })
        .collect();
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let mut count = 0;
    let mut characters = 0;
    enum_values(&schema, &mut count, &mut characters);
    // Strict structured outputs allow at most 500 enum values across a schema, and a
    // single enum of more than 250 values at most 7,500 characters.
    assert!(count <= 500, "{count} enum values");
    assert!(characters <= 7_500, "{characters} enum characters");
}

#[test]
fn a_collect_or_attack_names_only_what_its_own_mode_can_reach() {
    let mut seen = fixture("perception-max-observations");
    // r10: a stick 18 blocks off was asked for without moving, past the 2-block pickup reach.
    let stick = seen
        .surroundings
        .iter()
        .position(|observed| observed.kind == ObservationKind::DroppedItem)
        .unwrap();
    seen.surroundings[stick].distance = 18.17;
    let far = seen.surroundings[stick].target_ref.clone();
    let near = seen
        .surroundings
        .iter()
        .find(|observed| observed.kind == ObservationKind::DroppedItem && observed.distance <= 2.0);
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let collect = |item: &str, approach: bool| json!({ "action": "collect", "item": item, "approach": approach });
    assert!(!accepts(&schema, &collect(&far, false)));
    assert!(accepts(&schema, &collect(&far, true)));
    if let Some(near) = near {
        assert!(accepts(&schema, &collect(&near.target_ref, false)));
    }
    let collects = actions(&schema)
        .iter()
        .filter(|action| *action == "collect")
        .count();
    assert_eq!(collects, 2, "one closed variant per approach mode");
}

/// Every goal choice shape `schema` offers: a coupled schema's stepping pairing first.
fn goal_shapes(schema: &Value) -> Vec<Value> {
    if coupled(schema) {
        schema["properties"]["decision"]["anyOf"]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|pairing| {
                pairing["properties"]["goal"]["anyOf"]
                    .as_array()
                    .unwrap()
                    .clone()
            })
            .collect()
    } else {
        schema["properties"]["goal"]["anyOf"]
            .as_array()
            .unwrap()
            .clone()
    }
}

/// The dispositions, `null` as "none", a schema's goal choice offers, each once.
fn dispositions(schema: &Value) -> Vec<String> {
    let mut named: Vec<String> = Vec::new();
    for variant in goal_shapes(schema) {
        let name = variant["properties"]["disposition"]["enum"][0]
            .as_str()
            .unwrap_or("none")
            .to_owned();
        if !named.contains(&name) {
            named.push(name);
        }
    }
    named
}

/// [`seen`] with a dead bush in reach that drops nought to two sticks.
fn with_bush(mut seen: Perception) -> Perception {
    seen.surroundings.push(Observation {
        kind: ObservationKind::Block,
        what: "block.minecraft.dead_bush".to_owned(),
        distance: 1.0,
        direction: loom_contract::Bearing::North,
        vertical_offset: 0.0,
        visible: true,
        target_ref: "b9".to_owned(),
        count: None,
        standable: Some(true),
        yields: Some(vec![loom_contract::BlockYield {
            what: "item.minecraft.stick".to_owned(),
            least: 0,
            most: 2,
        }]),
        dig_safe: Some(true),
        route_refused: None,
    });
    seen
}

#[test]
fn a_review_is_offered_only_the_goal_choices_the_body_would_accept() {
    let of = |seen: &Perception| decision_schema_for(&OfferedRefs::of(seen), GoalPhase::of(seen));
    // r14 kappa: a held item alone is no source. With nothing in sight to give more of anything
    // there is no success item to name, so no goal can be worded.
    let review = fixture("perception-goal-review");
    assert_eq!(dispositions(&of(&review)), ["none"]);
    assert_eq!(dispositions(&of(&with_bush(review))), ["adopt", "none"]);
    // An unmet condition is never completed, and a held goal is always answered. Eight oak logs
    // with nothing in sight to give one cannot be met, so it is not offered a continue (r15: a
    // continue was the one disposition the obtainability rule did not reach), and with a bush in
    // sight it may be revised to sticks.
    let carried = fixture("perception-goal-carried");
    assert_eq!(dispositions(&of(&carried)), ["abandon"]);
    assert_eq!(
        dispositions(&of(&with_bush(carried.clone()))),
        ["revise", "abandon"]
    );
    let mut sticks = carried;
    let goal = sticks.goal.as_mut().unwrap();
    goal.success_item = Some("item.minecraft.stick".to_owned());
    goal.success_count = Some(1);
    assert_eq!(dispositions(&of(&sticks)), ["abandon"]);
    // An achievable goal is pursued, never abandoned (r17 theta body:00000333).
    assert_eq!(
        dispositions(&of(&with_bush(sticks.clone()))),
        ["continue", "revise"]
    );
    // Two sticks are one more than a bush's expected yield: not continued (r16 calibration).
    sticks.goal.as_mut().unwrap().success_count = Some(2);
    assert_eq!(dispositions(&of(&with_bush(sticks))), ["revise", "abandon"]);
    // r11: delta, epsilon and zeta held the stick their goal named and went on. A met
    // condition is completed or revised; nothing else, not even no choice, is offered.
    let met = with_bush(fixture("perception-goal-condition-met"));
    // A replace on a met goal completes it and adopts the next in one answer (r17: every one of
    // eleven completes idled for a full cycle).
    assert_eq!(dispositions(&of(&met)), ["revise", "replace", "complete"]);
    let schema = of(&met);
    let validator = jsonschema::validator_for(&schema).unwrap();
    let continuing = answer_in(
        &schema,
        &json!({ "action": "idle" }),
        &json!({ "disposition": "continue", "stepResult": "done" }),
    );
    assert!(!validator.is_valid(&continuing));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "idle" }),
        &json!(null)
    )));
    // A complete travels with any intent, idle included.
    let completing = answer_in(
        &schema,
        &json!({ "action": "idle" }),
        &json!({ "disposition": "complete", "stepResult": "done" }),
    );
    assert!(validator.is_valid(&completing));
    // A settled step reviews the goal just as a scheduled review does.
    let mut settled = fixture("perception-goal-carried");
    settled.trigger = loom_contract::Trigger::StepSettled;
    assert_eq!(GoalPhase::of(&settled), GoalPhase::Held);
    // Any other trigger keeps the reference shape's whole flat offer, less the worded choices
    // when, as here, nothing obtainable is in sight for a success item to name.
    assert_eq!(
        dispositions(&of(&fixture("perception-threat-sighted"))),
        ["continue", "none", "complete", "abandon"]
    );
}

/// The count bounds a request's goal shapes give `item` under `disposition`.
fn count_bounds(schema: &Value, disposition: &str, item: &str) -> Vec<(i64, i64)> {
    goal_shapes(schema)
        .iter()
        .filter(|variant| variant["properties"]["disposition"]["enum"][0] == disposition)
        .filter(|variant| variant["properties"]["successItem"]["enum"][0] == item)
        .map(|variant| {
            let count = &variant["properties"]["successCount"];
            (
                count["minimum"].as_i64().unwrap(),
                count["maximum"].as_i64().unwrap(),
            )
        })
        .collect()
}

#[test]
fn a_success_count_is_the_total_to_hold_bounded_by_what_is_held_and_what_sight_could_give() {
    let stick = "item.minecraft.stick";
    let mut seen = with_bush(fixture("perception-goal-review"));
    seen.self_state.inventory_summary.push(InventoryStack {
        slot_ref: "i9".to_owned(),
        what: stick.to_owned(),
        count: 3,
    });
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    // Three held and a bush expected to give one of its nought to two: a total of four (r15:
    // counts were read as more to gather; r16: goals sized to every bush's best case failed).
    assert_eq!(count_bounds(&schema, "adopt", stick), [(4, 4)]);
    let validator = jsonschema::validator_for(&schema).unwrap();
    let adopt = |intent: Value, count: i64| {
        answer_in(
            &schema,
            &intent,
            &json!({ "disposition": "adopt", "successItem": stick, "successCount": count }),
        )
    };
    let dig = json!({ "action": "dig", "block": "b9" });
    assert!(validator.is_valid(&adopt(dig.clone(), 4)));
    assert!(!validator.is_valid(&adopt(dig.clone(), 3)));
    assert!(!validator.is_valid(&adopt(dig, 5)));
    // An adopt takes the answer's action as its step, so it never travels with idle or rest
    // (r16: gamma and theta adopted beside idle).
    for passive in ["idle", "rest"] {
        assert!(
            !validator.is_valid(&adopt(json!({ "action": passive }), 4)),
            "{passive}"
        );
    }
}

#[test]
fn a_held_goal_offers_dig_and_collect_only_for_what_can_give_its_item() {
    let mut seen = with_bush(fixture("perception-goal-carried"));
    let goal = seen.goal.as_mut().unwrap();
    goal.success_item = Some("item.minecraft.stick".to_owned());
    goal.success_count = Some(2);
    // Terracotta beside the bush drops nothing: no dig of it can be the goal's step.
    seen.surroundings.push(Observation {
        target_ref: "b8".to_owned(),
        what: "block.minecraft.terracotta".to_owned(),
        yields: Some(vec![]),
        ..seen.surroundings[0].clone()
    });
    // A drop of something else is not the goal's item, and a stick the body found no route to
    // is offered to nothing and counted on by nothing.
    let bush = seen.surroundings[0].clone();
    let drop = |handle: &str, what: &str, refused: Option<bool>| Observation {
        kind: ObservationKind::DroppedItem,
        what: what.to_owned(),
        target_ref: handle.to_owned(),
        count: Some(1),
        standable: None,
        yields: None,
        dig_safe: None,
        route_refused: refused,
        ..bush.clone()
    };
    seen.surroundings
        .push(drop("d1", "item.minecraft.wheat_seeds", None));
    seen.surroundings
        .push(drop("d2", "item.minecraft.stick", None));
    seen.surroundings
        .push(drop("d3", "item.minecraft.stick", Some(true)));
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.pursued.as_deref(), Some("item.minecraft.stick"));
    assert_eq!(offered.dig_blocks, ["b9"]);
    assert_eq!(offered.route_items, ["d2"]);
    assert_eq!(offered.capacity_of("item.minecraft.stick"), 2);
    // Without the goal every handle in reach is offered again, but never the refused route.
    seen.goal = None;
    let free = OfferedRefs::of(&seen);
    assert_eq!(free.dig_blocks, ["b9", "b8"]);
    assert_eq!(free.route_items, ["d1", "d2"]);
}

/// A dropped stick `distance` blocks off, offered by `handle`.
fn stick_drop(handle: &str, distance: f64) -> Observation {
    Observation {
        kind: ObservationKind::DroppedItem,
        what: "item.minecraft.stick".to_owned(),
        distance,
        direction: loom_contract::Bearing::North,
        vertical_offset: 0.0,
        visible: true,
        target_ref: handle.to_owned(),
        count: Some(2),
        standable: None,
        yields: None,
        dig_safe: None,
        route_refused: None,
    }
}

/// [`fixture`]'s carried goal turned into a stick goal of `count` that advanced nothing for
/// `stalled` steps, beside a bush.
fn stick_goal(count: i64, stalled: i64) -> Perception {
    let mut seen = with_bush(fixture("perception-goal-carried"));
    let goal = seen.goal.as_mut().unwrap();
    goal.success_item = Some("item.minecraft.stick".to_owned());
    goal.success_count = Some(count);
    goal.progress.as_mut().unwrap().steps_without_progress = stalled;
    seen
}

#[test]
fn a_goal_at_the_stall_bound_is_offered_only_a_replace_beside_an_action() {
    // r16 alpha body:00000113 and kappa body:00000148: a continue or revise at the bound was
    // refused, the right collect with it, and a full review cycle was lost.
    let mut seen = stick_goal(1, loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS);
    // With the bush alone sight gives one stick, so a replace raising the total past the held
    // goal's one cannot be met: the stalled goal is offered only an abandon.
    assert_eq!(GoalPhase::of(&seen), GoalPhase::Stalled);
    let bare = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(dispositions(&bare), ["abandon"]);
    seen.surroundings.push(stick_drop("d7", 6.0));
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(dispositions(&schema), ["replace"]);
    // r17 alpha body:00000163 replaced "hold 2 stick" with "hold 2 stick" and cleared its stall
    // count; the same item may only be set again at a higher total.
    assert_eq!(
        count_bounds(&schema, "replace", "item.minecraft.stick"),
        [(2, 3)]
    );
    let validator = jsonschema::validator_for(&schema).unwrap();
    let replace = json!({ "disposition": "replace", "successItem": "item.minecraft.stick",
        "successCount": 2, "stepResult": "noProgress" });
    let dig = json!({ "action": "dig", "block": "b9" });
    assert!(validator.is_valid(&answer_in(&schema, &dig, &replace)));
    for passive in ["idle", "rest"] {
        assert!(!validator.is_valid(&answer_in(&schema, &json!({ "action": passive }), &replace)));
    }
    for kept in ["continue", "revise"] {
        let choice = json!({ "disposition": kept, "stepResult": "noProgress",
            "successItem": "item.minecraft.stick", "successCount": 1 });
        assert!(
            !validator.is_valid(&answer_in(&schema, &dig, &choice)),
            "{kept}"
        );
    }
    // A met condition is completed, whatever the count says.
    let mut met = seen;
    met.goal
        .as_mut()
        .unwrap()
        .progress
        .as_mut()
        .unwrap()
        .condition_met = true;
    assert_eq!(GoalPhase::of(&met), GoalPhase::Met);
}

#[test]
fn an_own_drop_of_the_goal_item_in_reach_is_collected_before_any_dig() {
    // r16: in 11 of 21 reviews with its own stick on offer the agent dug again or idled, and
    // delta collected the lost drops (body:00000241, body:00000298).
    let mut seen = stick_goal(1, 0);
    seen.surroundings.push(stick_drop("d7", 6.0));
    seen.self_state.own_drops.push(loom_contract::OwnDrop {
        request_id: "req-dig".to_owned(),
        what: "item.minecraft.stick".to_owned(),
        count: 2,
        fate: loom_contract::DropFate::Lying,
        distance: Some(6.0),
        direction: Some(loom_contract::Bearing::North),
        vertical_offset: Some(0.0),
        drop_ref: Some("d7".to_owned()),
    });
    let offered = OfferedRefs::of(&seen);
    assert!(offered.collect_required);
    assert!(offered.dig_blocks.is_empty());
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let continuing = json!({ "disposition": "continue", "stepResult": "progressing" });
    let collect = json!({ "action": "collect", "item": "d7", "approach": true });
    assert!(validator.is_valid(&answer_in(&schema, &collect, &continuing)));
    let dig = json!({ "action": "dig", "block": "b9" });
    assert!(!validator.is_valid(&answer_in(&schema, &dig, &continuing)));
    let walk = json!({ "action": "moveTo", "onto": "b9" });
    assert!(!validator.is_valid(&answer_in(&schema, &walk, &continuing)));
    // An achievable goal is not abandoned either (r17 theta body:00000333).
    let abandon = json!({ "disposition": "abandon", "stepResult": "noProgress" });
    assert!(!validator.is_valid(&answer_in(&schema, &walk, &abandon)));
}
