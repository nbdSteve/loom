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
            .filter_map(|name| schema["$defs"][name]["anyOf"].as_array().cloned())
            .flatten()
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
    assert_eq!(defs, ["activeIntent", "passiveIntent"]);

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
            + offered.flee_targets.len()
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
        hunts: None,
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
                        per_hundred: 6400,
                    })
                    .collect(),
            ),
            dig_safe: Some(index != 0),
            route_refused: None,
            hunts: None,
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
            per_hundred: 100,
        }]),
        dig_safe: Some(true),
        route_refused: None,
        hunts: None,
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
    // continue was the one disposition the obtainability rule did not reach). A revise never
    // changes the item, so a bush in sight offers no revise of a log goal to sticks (r18 zeta
    // body:00000569 revised a scute goal into sticks under the same goal).
    let carried = fixture("perception-goal-carried");
    assert_eq!(dispositions(&of(&carried)), ["abandon"]);
    assert_eq!(dispositions(&of(&with_bush(carried.clone()))), ["abandon"]);
    let mut sticks = carried;
    let goal = sticks.goal.as_mut().unwrap();
    goal.success_item = Some("item.minecraft.stick".to_owned());
    goal.success_count = Some(1);
    assert_eq!(dispositions(&of(&sticks)), ["abandon"]);
    // An achievable goal is pursued, never abandoned (r17 theta body:00000333). A revise must
    // change the total, and one stick is all sight gives, so no revise is offered (r19: 28 of
    // 31 revises restated the total they were shown).
    assert_eq!(dispositions(&of(&with_bush(sticks.clone()))), ["continue"]);
    // Two sticks are one more than a bush's expected yield (r16 calibration): the bush is still
    // dug toward the goal, and the goal may be revised to what sight gives, or abandoned (the
    // path rehearsal: a goal one log short was refused the collect of its own lying log).
    sticks.goal.as_mut().unwrap().success_count = Some(2);
    assert_eq!(
        dispositions(&of(&with_bush(sticks))),
        ["continue", "revise", "abandon"]
    );
    // r11: delta, epsilon and zeta held the stick their goal named and went on. A met
    // condition is completed or revised; nothing else, not even no choice, is offered.
    let met = with_bush(fixture("perception-goal-condition-met"));
    // A replace on a met goal completes it and adopts the next in one answer (r17: every one of
    // eleven completes idled for a full cycle). While sight gives a next goal no plain complete
    // is offered at all (r19: two completes beside a filler equip, six leaving their agent
    // goal-less for a cadence). The bush gives sticks, not the held goal's logs, so no revise.
    assert_eq!(dispositions(&of(&met)), ["replace"]);
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
    // With nothing in sight to give a next goal, a plain complete is offered, beside any intent
    // while nowhere is offered to look.
    let bare = fixture("perception-goal-condition-met");
    assert_eq!(dispositions(&of(&bare)), ["complete"]);
    let completing = answer_in(
        &of(&bare),
        &json!({ "action": "idle" }),
        &json!({ "disposition": "complete", "stepResult": "done" }),
    );
    assert!(jsonschema::validator_for(&of(&bare))
        .unwrap()
        .is_valid(&completing));
    // Beside a cell at the edge of sight it travels with an action (r18: all eleven completes
    // idled, and six waited a whole cadence for the next goal).
    let mut looking = bare.clone();
    looking.explore.push(loom_contract::ExploreTarget {
        target_ref: "x1".to_owned(),
        distance: 12.0,
        direction: loom_contract::Bearing::East,
        vertical_offset: 0.0,
    });
    // and with nothing grounding a next item goal, it is replaced by a discovery goal stepped
    // by that walk rather than completed (r23).
    let beside = jsonschema::validator_for(&of(&looking)).unwrap();
    assert!(!beside.is_valid(&completing));
    assert!(!beside.is_valid(&answer_in(
        &of(&looking),
        &json!({ "action": "moveTo", "onto": "x1" }),
        &json!({ "disposition": "complete", "stepResult": "done" }),
    )));
    assert!(beside.is_valid(&answer_in(
        &of(&looking),
        &json!({ "action": "moveTo", "onto": "x1" }),
        &json!({ "disposition": "replace", "discover": true, "stepResult": "done" }),
    )));
    // A settled step reviews the goal just as a scheduled review does.
    let mut settled = fixture("perception-goal-carried");
    settled.trigger = loom_contract::Trigger::StepSettled;
    assert_eq!(GoalPhase::of(&settled), GoalPhase::Held);
    // Any other trigger is offered no goal choice at all: the ledger reads none outside a review
    // (r22: three event answers adopted goals that were dropped as goalNotReviewed).
    let sighted = fixture("perception-threat-sighted");
    assert_eq!(GoalPhase::of(&sighted), GoalPhase::Event);
    assert_eq!(dispositions(&of(&sighted)), ["none"]);
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
        edible: None,
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
        hunts: None,
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
        hunts: None,
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
    // r17 alpha body:00000163 replaced "hold 2 stick" with "hold 2 stick", and r18's three
    // replaces set the stalled stick goal again at one more: the same item is never offered.
    seen.surroundings.push(stick_drop("d7", 6.0));
    let sticks = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(dispositions(&sticks), ["abandon"]);
    assert!(count_bounds(&sticks, "replace", "item.minecraft.stick").is_empty());
    // A block in reach that drops dirt gives a goal for another item.
    let mut dirt = seen.surroundings[0].clone();
    dirt.what = "block.minecraft.dirt".to_owned();
    dirt.target_ref = "b8".to_owned();
    dirt.yields = Some(vec![loom_contract::BlockYield {
        what: "item.minecraft.dirt".to_owned(),
        least: 1,
        most: 1,
        per_hundred: 100,
    }]);
    seen.surroundings.push(dirt);
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    assert_eq!(dispositions(&schema), ["replace"]);
    assert_eq!(
        count_bounds(&schema, "replace", "item.minecraft.dirt"),
        [(1, 1)]
    );
    let validator = jsonschema::validator_for(&schema).unwrap();
    let replace = json!({ "disposition": "replace", "successItem": "item.minecraft.dirt",
        "successCount": 1, "stepResult": "noProgress" });
    let dig = json!({ "action": "dig", "block": "b8" });
    // Its step is one of its own item's: the bush cannot give dirt.
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b9" }),
        &replace
    )));
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

/// [`seen`] with a block in reach of `what` that drops `item` from `least` to `most`.
fn with_block(
    mut seen: Perception,
    handle: &str,
    what: &str,
    item: Option<(&str, i64, i64)>,
) -> Perception {
    seen.surroundings.push(Observation {
        kind: ObservationKind::Block,
        what: format!("block.minecraft.{what}"),
        distance: 1.2,
        direction: loom_contract::Bearing::South,
        vertical_offset: 0.0,
        visible: true,
        target_ref: handle.to_owned(),
        count: None,
        standable: Some(true),
        yields: Some(
            item.map(|(name, least, most)| loom_contract::BlockYield {
                what: name.to_owned(),
                least,
                most,
                per_hundred: (least + most) * 50,
            })
            .into_iter()
            .collect(),
        ),
        dig_safe: Some(true),
        route_refused: None,
        hunts: None,
    });
    seen
}

/// One cell at the edge of sight, `x1`, twelve blocks east.
fn with_edge(mut seen: Perception) -> Perception {
    seen.explore.push(loom_contract::ExploreTarget {
        target_ref: "x1".to_owned(),
        distance: 12.0,
        direction: loom_contract::Bearing::East,
        vertical_offset: 0.0,
    });
    seen
}

#[test]
fn an_adopted_goal_is_stepped_only_by_its_own_items_sources() {
    // r18 epsilon body:00000046: it adopted a stick goal standing in a dead bush and dug
    // terracotta, which gives nothing, refused as goalStepUnproductive at body:00000047.
    let seen = with_block(
        with_bush(fixture("perception-goal-review")),
        "bqr",
        "terracotta",
        None,
    );
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let adopt =
        json!({ "disposition": "adopt", "successItem": "item.minecraft.stick", "successCount": 1 });
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b9" }),
        &adopt
    )));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "bqr" }),
        &adopt
    )));
    // With no goal choice the terracotta may still be dug.
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "bqr" }),
        &json!(null)
    )));
    // A dirt block gives dirt, and dirt's own goal steps only by it.
    let both = with_block(seen, "b8", "dirt", Some(("item.minecraft.dirt", 1, 1)));
    let schema = decision_schema_for(&OfferedRefs::of(&both), GoalPhase::of(&both));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let dirt =
        json!({ "disposition": "adopt", "successItem": "item.minecraft.dirt", "successCount": 1 });
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b8" }),
        &dirt
    )));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b9" }),
        &dirt
    )));
}

#[test]
fn a_held_goal_sight_cannot_meet_continues_only_by_looking_further() {
    // r18 gamma body:00000069, delta 127 and 485: each abandoned its goal to walk to the edge of
    // sight. The walk now continues the goal, and nothing passive is offered beside it.
    let seen = with_edge(fixture("perception-goal-carried"));
    assert_eq!(
        dispositions(&decision_schema_for(
            &OfferedRefs::of(&seen),
            GoalPhase::of(&seen)
        )),
        ["continue", "abandon"]
    );
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let continuing = json!({ "disposition": "continue", "stepResult": "noProgress" });
    let abandon = json!({ "disposition": "abandon", "stepResult": "noProgress" });
    let walk = json!({ "action": "moveTo", "onto": "x1" });
    assert!(validator.is_valid(&answer_in(&schema, &walk, &continuing)));
    assert!(validator.is_valid(&answer_in(&schema, &walk, &abandon)));
    assert!(!validator.is_valid(&answer_in(&schema, &json!({ "action": "idle" }), &abandon)));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "idle" }),
        &continuing
    )));
    // With nowhere offered to look, only the abandon remains, idle beside it.
    let blind = fixture("perception-goal-carried");
    let schema = decision_schema_for(&OfferedRefs::of(&blind), GoalPhase::of(&blind));
    assert_eq!(dispositions(&schema), ["abandon"]);
    assert!(jsonschema::validator_for(&schema)
        .unwrap()
        .is_valid(&answer_in(&schema, &json!({ "action": "idle" }), &abandon)));
}

#[test]
fn an_unheld_review_beside_cells_to_look_from_is_offered_no_idle() {
    // r18 alpha body:00000467, delta 190, eta 051 and zeta 174 and 253 idled with cells offered.
    let seen = with_edge(fixture("perception-goal-review"));
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    // r23: with nothing grounding an item goal, the review sets a discovery goal, not none.
    assert_eq!(dispositions(&schema), ["adopt"]);
    let discover = json!({ "disposition": "adopt", "discover": true });
    let validator = jsonschema::validator_for(&schema).unwrap();
    for passive in ["idle", "rest"] {
        assert!(
            !validator.is_valid(&answer_in(
                &schema,
                &json!({ "action": passive }),
                &json!(null)
            )),
            "{passive}"
        );
    }
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &discover
    )));
    // It is stepped only by a walk to the edge of sight, and no review goes goalless.
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &json!(null)
    )));
    // A reply to speech is no goal review, and may still idle.
    let mut spoken = seen;
    spoken.trigger = loom_contract::Trigger::Spoken {
        from: "LoomBeta".to_owned(),
        text: "hello".to_owned(),
    };
    assert!(actions(&decision_schema_for(
        &OfferedRefs::of(&spoken),
        GoalPhase::of(&spoken)
    ))
    .contains(&"idle".to_owned()));
}

/// The widest goal review the contract admits: sixteen items each offered its own steps.
fn widest_goal_review() -> Perception {
    // Every observation a block yielding eight distinct items, every cell at the edge of sight,
    // full inventory and recipe lists, at a goal review with no goal held: sixteen items each
    // offered its own steps.
    let mut seen = fixture("perception-goal-review");
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
        hunts: None,
    };
    seen.surroundings = (0..MAX_PERCEPTION_OBSERVATIONS)
        .map(|index| Observation {
            target_ref: format!("b{index:03}"),
            yields: Some(
                (0..loom_contract::MAX_BLOCK_YIELDS)
                    .map(|item| loom_contract::BlockYield {
                        what: format!("item.minecraft.{:0>49}", (index + item) % 24),
                        least: 0,
                        most: 64,
                        per_hundred: 6400,
                    })
                    .collect(),
            ),
            ..block.clone()
        })
        .collect();
    seen.explore = (0..loom_contract::MAX_EXPLORE_TARGETS)
        .map(|index| loom_contract::ExploreTarget {
            target_ref: format!("x{index}"),
            distance: 12.0,
            direction: loom_contract::Bearing::North,
            vertical_offset: 0.0,
        })
        .collect();
    let crafting = fixture("perception-crafting");
    let option = crafting.self_state.crafting_options[0].clone();
    seen.self_state.crafting_options = (0..48)
        .map(|index| CraftingOption {
            recipe_ref: format!("r{index:02}"),
            what: format!("item.minecraft.{:0>49}", index % 24),
            ..option.clone()
        })
        .collect();
    // Eight horizon goals each made from another item, so sixteen items crowd out nothing.
    seen.horizon = (0..loom_contract::MAX_HORIZON_GOALS)
        .map(|index| loom_contract::HorizonGoal {
            what: format!("item.minecraft.{index:0>49}"),
            grounding: loom_contract::HorizonGrounding::Recipe,
            via: Some(format!("item.minecraft.{:0>49}", index + 8)),
            most: 64,
            frontier: false,
        })
        .collect();
    let stack = crafting.self_state.inventory_summary[0].clone();
    seen.self_state.inventory_summary = (0..46)
        .map(|index| InventoryStack {
            slot_ref: format!("i{index:02}"),
            ..stack.clone()
        })
        .collect();
    seen
}

#[test]
fn the_widest_goal_review_schema_stays_inside_the_providers_enum_limits() {
    let seen = widest_goal_review();
    let offered = OfferedRefs::of(&seen);
    assert_eq!(
        offered.item_steps.len(),
        loom_cognition::offered::MAX_OBTAINABLE_ITEMS
    );
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let mut count = 0;
    let mut characters = 0;
    enum_values(&schema, &mut count, &mut characters);
    assert!(count <= 500, "{count} enum values");
    assert!(characters <= 7_500, "{characters} enum characters");
}

#[test]
fn the_widest_goal_review_beside_hunters_and_recalled_sources_stays_inside_the_enum_limits() {
    let mut seen = widest_goal_review();
    // v29: the last 48 observations are hunters in sight, the nearest few offered to flee, beside
    // eight recalled sources and eight cells at the edge of sight.
    let total = seen.surroundings.len();
    for (index, observed) in seen.surroundings.iter_mut().enumerate().skip(total - 48) {
        *observed = Observation {
            kind: ObservationKind::Entity,
            what: "entity.minecraft.zombie".to_owned(),
            distance: 3.0,
            direction: loom_contract::Bearing::East,
            vertical_offset: 0.0,
            visible: true,
            target_ref: format!("e{index:03}"),
            count: None,
            standable: None,
            yields: None,
            dig_safe: None,
            route_refused: None,
            hunts: Some(true),
        };
    }
    seen.explore = (0..8)
        .map(|index| loom_contract::ExploreTarget {
            target_ref: format!("x{index}"),
            distance: 12.0,
            direction: [
                loom_contract::Bearing::North,
                loom_contract::Bearing::East,
                loom_contract::Bearing::South,
                loom_contract::Bearing::West,
            ][index % 4],
            vertical_offset: 0.0,
        })
        .collect();
    seen.recalled = (0..8)
        .map(|index| loom_contract::RecalledSource {
            what: format!("item.minecraft.recalled_{index:0>40}"),
            distance: 20.0,
            direction: loom_contract::Bearing::North,
            vertical_offset: 0.0,
            per_hundred: 6400,
        })
        .collect();
    let offered = OfferedRefs::of(&seen);
    assert_eq!(
        offered.danger.len(),
        loom_cognition::offered::MAX_DANGER_REFS
    );
    // Every hunter offered to flee from is a creature a flight may name.
    assert!(offered
        .danger
        .iter()
        .all(|hunter| offered.creatures.contains(hunter)));
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let mut count = 0;
    let mut characters = 0;
    enum_values(&schema, &mut count, &mut characters);
    assert!(count <= 500, "{count} enum values");
    assert!(characters <= 7_500, "{characters} enum characters");
}

#[test]
fn a_goal_sight_cannot_meet_still_collects_its_own_lying_drop_before_looking_further() {
    // The path rehearsal: epsilon held no log of a two-log goal with its own log lying in reach
    // and nothing else in sight; the collect was refused as unobtainable, three reviews running.
    let mut seen = with_edge(fixture("perception-goal-carried"));
    let mut log = stick_drop("d7", 2.5);
    log.what = "item.minecraft.oak_log".to_owned();
    seen.surroundings.push(log);
    seen.self_state.own_drops.push(loom_contract::OwnDrop {
        request_id: "req-dig".to_owned(),
        what: "item.minecraft.oak_log".to_owned(),
        count: 1,
        fate: loom_contract::DropFate::Lying,
        distance: Some(2.5),
        direction: Some(loom_contract::Bearing::North),
        vertical_offset: Some(0.0),
        drop_ref: Some("d7".to_owned()),
    });
    let offered = OfferedRefs::of(&seen);
    assert!(!offered.goal_obtainable);
    assert!(offered.collect_required);
    assert!(!offered.explore_continue());
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let continuing = json!({ "disposition": "continue", "stepResult": "progressing" });
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "collect", "item": "d7", "approach": true }),
        &continuing
    )));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &continuing
    )));
}

#[test]
fn a_goal_for_a_craftable_or_dropped_item_is_stepped_by_its_craft_or_its_collect() {
    // Three logs and a planks recipe at a goal review: a planks goal is stepped by the craft.
    let mut seen = fixture("perception-crafting");
    seen.surroundings.push(stick_drop("d1", 1.0));
    seen.surroundings.push(stick_drop("d2", 6.0));
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let planks = json!({ "disposition": "adopt", "successItem": "item.minecraft.oak_planks", "successCount": 4 });
    let sticks =
        json!({ "disposition": "adopt", "successItem": "item.minecraft.stick", "successCount": 1 });
    let craft = json!({ "action": "craft", "recipe": "r1", "crafts": 1 });
    let beside = json!({ "action": "collect", "item": "d1", "approach": false });
    let walking = json!({ "action": "collect", "item": "d2", "approach": true });
    assert!(validator.is_valid(&answer_in(&schema, &craft, &planks)));
    assert!(!validator.is_valid(&answer_in(&schema, &craft, &sticks)));
    assert!(validator.is_valid(&answer_in(&schema, &beside, &sticks)));
    assert!(validator.is_valid(&answer_in(&schema, &walking, &sticks)));
    assert!(!validator.is_valid(&answer_in(&schema, &walking, &planks)));
}

#[test]
fn a_goal_review_beside_a_hunter_may_flee_from_it_with_no_goal_choice() {
    // r20 kappa body:00000823: health 7.7, a zombie 3.3 off and a skeleton 2.1 off, and the
    // review offered only dirt digs; kappa dug and died 28 ticks later.
    let seen = fixture("perception-goal-review-hunted");
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.danger, vec!["e9".to_owned()]);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert_eq!(
        schema["$defs"]["dangerRef"]["enum"],
        serde_json::json!(["e9"])
    );
    let text = schema.to_string();
    assert!(text.contains("\"#/$defs/dangerRef\""), "{text}");
    // A creature that does not hunt the agent's kind is no danger.
    let mut calm = seen.clone();
    calm.surroundings[0].hunts = None;
    assert!(OfferedRefs::of(&calm).danger.is_empty());
    let calm_schema = decision_schema_for(&OfferedRefs::of(&calm), GoalPhase::of(&calm));
    assert!(calm_schema["$defs"].get("dangerRef").is_none());
}

#[test]
fn a_source_recalled_out_of_sight_can_be_a_goal_walked_toward_by_its_edge_of_sight_cell() {
    // r20: no agent without a log in sight could set a log goal, and the only walk to the edge
    // of sight came with an abandon.
    let mut seen = fixture("perception-goal-review");
    seen.explore = vec![
        loom_contract::ExploreTarget {
            target_ref: "x1".to_owned(),
            distance: 12.0,
            direction: loom_contract::Bearing::North,
            vertical_offset: 0.0,
        },
        loom_contract::ExploreTarget {
            target_ref: "x2".to_owned(),
            distance: 12.0,
            direction: loom_contract::Bearing::South,
            vertical_offset: 0.0,
        },
    ];
    seen.recalled = vec![loom_contract::RecalledSource {
        what: "item.minecraft.oak_log".to_owned(),
        distance: 18.0,
        direction: loom_contract::Bearing::North,
        vertical_offset: 1.0,
        per_hundred: 500,
    }];
    // The horizon the JVM grounds in the recalled tree: past its five logs onto the frontier.
    seen.horizon = vec![loom_contract::HorizonGoal {
        what: "item.minecraft.oak_log".to_owned(),
        grounding: loom_contract::HorizonGrounding::Recalled,
        via: None,
        most: 13,
        frontier: true,
    }];
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.capacity_of("item.minecraft.oak_log"), 5);
    let logs = offered
        .item_steps
        .iter()
        .find(|steps| steps.item == "item.minecraft.oak_log")
        .unwrap();
    assert_eq!(logs.toward, vec!["x1".to_owned()]);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert_eq!(dispositions(&schema), ["adopt", "none"]);
    let validator = jsonschema::validator_for(&schema).unwrap();
    let toward = |onto: &str| {
        answer_in(
            &schema,
            &json!({ "action": "moveTo", "onto": onto }),
            &json!({ "disposition": "adopt", "successItem": "item.minecraft.oak_log", "successCount": 3 }),
        )
    };
    assert!(validator.is_valid(&toward("x1")));
    assert!(
        !validator.is_valid(&toward("x2")),
        "a walk away from the source is no step toward it"
    );
}

#[test]
fn a_source_in_sight_beyond_dig_reach_is_walked_toward_by_its_edge_of_sight_cell_never_dug() {
    // r34 zeta: logs 6.6 to 8 blocks off and up a tree were in no sight a dig could reach, and the
    // only walks offered went past them.
    let mut seen = fixture("perception-goal-review");
    seen.explore = vec![
        loom_contract::ExploreTarget {
            target_ref: "x1".to_owned(),
            distance: 6.1,
            direction: loom_contract::Bearing::East,
            vertical_offset: 0.0,
        },
        loom_contract::ExploreTarget {
            target_ref: "x2".to_owned(),
            distance: 12.0,
            direction: loom_contract::Bearing::West,
            vertical_offset: 0.0,
        },
    ];
    seen.surroundings.push(Observation {
        kind: ObservationKind::Block,
        what: "block.minecraft.oak_log".to_owned(),
        distance: 8.1,
        direction: loom_contract::Bearing::East,
        vertical_offset: 3.5,
        visible: true,
        target_ref: "b40".to_owned(),
        count: None,
        standable: Some(false),
        yields: Some(vec![loom_contract::BlockYield {
            what: "item.minecraft.oak_log".to_owned(),
            least: 1,
            most: 1,
            per_hundred: 100,
        }]),
        dig_safe: Some(true),
        route_refused: None,
        hunts: None,
    });
    // The horizon the JVM grounds in the log sight shows.
    seen.horizon = vec![horizon_goal(
        "item.minecraft.oak_log",
        loom_contract::HorizonGrounding::Sight,
        None,
        9,
    )];
    let offered = OfferedRefs::of(&seen);
    assert!(
        !offered.dig_blocks.contains(&"b40".to_owned()),
        "a log out of reach is never dug"
    );
    assert_eq!(offered.capacity_of("item.minecraft.oak_log"), 1);
    let logs = offered
        .item_steps
        .iter()
        .find(|steps| steps.item == "item.minecraft.oak_log")
        .unwrap();
    assert!(logs.dig.is_empty());
    assert!(
        logs.walks.is_empty(),
        "a log up a tree has no room to stand on"
    );
    assert_eq!(logs.toward, vec!["x1".to_owned()]);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let adopt = json!({ "disposition": "adopt", "successItem": "item.minecraft.oak_log", "successCount": 1 });
    let step = |intent: Value| answer_in(&schema, &intent, &adopt);
    assert!(validator.is_valid(&step(json!({ "action": "moveTo", "onto": "x1" }))));
    assert!(!validator.is_valid(&step(json!({ "action": "moveTo", "onto": "x2" }))));
    assert!(!validator.is_valid(&step(json!({ "action": "dig", "block": "b40" }))));
}

#[test]
fn a_stalled_goal_is_not_replaced_by_an_item_made_from_it() {
    // r34 zeta: an oak log goal three steps without progress was replaced by an oak button goal,
    // which needed the same unreached log, and two more walks advanced nothing.
    let mut seen = with_edge(stick_goal(
        2,
        loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS,
    ));
    seen.goal.as_mut().unwrap().success_item = Some("item.minecraft.oak_log".to_owned());
    seen.recalled = vec![loom_contract::RecalledSource {
        what: "item.minecraft.oak_log".to_owned(),
        distance: 8.1,
        direction: loom_contract::Bearing::East,
        vertical_offset: 3.5,
        per_hundred: 100,
    }];
    seen.horizon = vec![
        horizon_goal(
            "item.minecraft.oak_planks",
            loom_contract::HorizonGrounding::Recipe,
            Some("item.minecraft.oak_log"),
            8,
        ),
        horizon_goal(
            "item.minecraft.oak_log",
            loom_contract::HorizonGrounding::Recalled,
            None,
            9,
        ),
        horizon_goal(
            "item.minecraft.oak_button",
            loom_contract::HorizonGrounding::Recipe,
            Some("item.minecraft.oak_planks"),
            8,
        ),
        horizon_goal(
            "item.minecraft.stick",
            loom_contract::HorizonGrounding::Sight,
            None,
            9,
        ),
    ];
    let offered = OfferedRefs::of(&seen);
    let replacing: Vec<&str> = offered
        .count_bounds
        .iter()
        .map(|(item, ..)| item.as_str())
        .collect();
    assert!(replacing.contains(&"item.minecraft.stick"), "{replacing:?}");
    for made in [
        "item.minecraft.oak_log",
        "item.minecraft.oak_planks",
        "item.minecraft.oak_button",
    ] {
        assert!(!replacing.contains(&made), "{made} needs the stalled log");
    }
}

fn horizon_goal(
    what: &str,
    grounding: loom_contract::HorizonGrounding,
    via: Option<&str>,
    most: i64,
) -> loom_contract::HorizonGoal {
    loom_contract::HorizonGoal {
        what: what.to_owned(),
        grounding,
        via: via.map(str::to_owned),
        most,
        frontier: grounding != loom_contract::HorizonGrounding::Recipe,
    }
}

#[test]
fn guaranteed_dirt_does_not_crowd_out_looking_further_unless_a_horizon_goal_is_made_from_it() {
    // r22: every goal was dirt from underfoot, and no agent looked for wood.
    const DIRT: &str = "item.minecraft.dirt";
    let ground = with_block(
        fixture("perception-goal-review"),
        "g1",
        "grass_block",
        Some((DIRT, 1, 1)),
    );
    // With nowhere further to look, the dirt is a goal as it always was.
    assert_eq!(
        count_bounds(
            &decision_schema_for(&OfferedRefs::of(&ground), GoalPhase::of(&ground)),
            "adopt",
            DIRT
        ),
        vec![(1, 1)]
    );
    // Beside a cell at the edge of sight, it is not: the review may only look further.
    let looking = with_edge(ground.clone());
    let schema = decision_schema_for(&OfferedRefs::of(&looking), GoalPhase::of(&looking));
    assert_eq!(dispositions(&schema), ["adopt"]);
    assert!(count_bounds(&schema, "adopt", DIRT).is_empty());
    // Unless a horizon goal is made from it: then it feeds a later typed goal.
    let mut feeding = looking.clone();
    feeding.horizon = vec![horizon_goal(
        "item.minecraft.coarse_dirt",
        loom_contract::HorizonGrounding::Recipe,
        Some(DIRT),
        8,
    )];
    let schema = decision_schema_for(&OfferedRefs::of(&feeding), GoalPhase::of(&feeding));
    assert_eq!(count_bounds(&schema, "adopt", DIRT), vec![(1, 1)]);
    assert_eq!(
        count_bounds(&schema, "adopt", "item.minecraft.coarse_dirt"),
        vec![(1, 8)]
    );
}

#[test]
fn a_horizon_goal_asks_past_what_sight_gives_and_is_stepped_now_by_a_dig_or_a_look_further() {
    const LOG: &str = "item.minecraft.oak_log";
    const PLANKS: &str = "item.minecraft.oak_planks";
    let mut seen = with_edge(with_block(
        fixture("perception-goal-review"),
        "b1",
        "oak_log",
        Some((LOG, 1, 1)),
    ));
    seen.horizon = vec![
        horizon_goal(
            PLANKS,
            loom_contract::HorizonGrounding::Recipe,
            Some(LOG),
            8,
        ),
        horizon_goal(LOG, loom_contract::HorizonGrounding::Sight, None, 9),
    ];
    let offered = OfferedRefs::of(&seen);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    // One log in sight, and eight more the frontier may hold: the cap, not the sky.
    assert_eq!(count_bounds(&schema, "adopt", LOG), vec![(1, 9)]);
    assert_eq!(count_bounds(&schema, "adopt", PLANKS), vec![(1, 8)]);
    let steps = |item: &str| {
        offered
            .item_steps
            .iter()
            .find(|steps| steps.item == item)
            .unwrap()
            .clone()
    };
    // The rolling step is executable now: the log in reach, and no walk to look further while it
    // is (r23: epsilon walked away from two logs in dig reach).
    assert_eq!(steps(LOG).dig, vec!["b1".to_owned()]);
    assert!(steps(LOG).toward.is_empty());
    // Planks are stepped toward through the log they are made from.
    assert_eq!(steps(PLANKS).dig, vec!["b1".to_owned()]);
    let validator = jsonschema::validator_for(&schema).unwrap();
    let dig = json!({ "action": "dig", "block": "b1" });
    let adopt = |item: &str, count: i64| json!({ "disposition": "adopt", "successItem": item, "successCount": count });
    assert!(validator.is_valid(&answer_in(&schema, &dig, &adopt(PLANKS, 8))));
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &adopt(LOG, 9)
    )));
    assert!(!validator.is_valid(&answer_in(&schema, &dig, &adopt(LOG, 10))));
}

#[test]
fn a_held_goal_may_switch_before_its_stall_bound_into_a_recipe_made_from_what_is_held() {
    // r23 eta body:00001542: it abandoned its goal and crafted eight planks from its logs with no
    // goal held, because only a stalled goal could be replaced.
    const PLANKS: &str = "item.minecraft.oak_planks";
    let mut seen = stick_goal(4, 0);
    let crafting = fixture("perception-crafting");
    seen.self_state.crafting_options = crafting.self_state.crafting_options.clone();
    seen.self_state.inventory_summary = crafting.self_state.inventory_summary.clone();
    let made = seen.self_state.crafting_options[0].what.clone();
    let input = seen.self_state.inventory_summary[0].what.clone();
    seen.horizon = vec![horizon_goal(
        &made,
        loom_contract::HorizonGrounding::Recipe,
        Some(&input),
        8,
    )];
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.switch_bounds.len(), 1, "{made} from held {input}");
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(dispositions(&schema).contains(&"replace".to_owned()));
    assert_eq!(count_bounds(&schema, "replace", &made).len(), 1);
    // A recipe made from nothing held is no switch.
    seen.horizon = vec![horizon_goal(
        PLANKS,
        loom_contract::HorizonGrounding::Recipe,
        Some("item.minecraft.birch_log"),
        8,
    )];
    assert!(OfferedRefs::of(&seen).switch_bounds.is_empty());
}

#[test]
fn a_discovery_goal_is_walked_to_the_edge_of_sight_and_replaced_by_what_it_reveals() {
    const LOG: &str = "item.minecraft.oak_log";
    // r23: 17 of 70 reviews could set no goal at all, and walked with nothing to measure it by.
    let held = fixture("perception-goal-discovery-held");
    let schema = decision_schema_for(&OfferedRefs::of(&held), GoalPhase::of(&held));
    let validator = jsonschema::validator_for(&schema).unwrap();
    let walk = json!({ "action": "moveTo", "onto": "x1" });
    let continuing = json!({ "disposition": "continue", "stepResult": "noProgress" });
    assert!(validator.is_valid(&answer_in(&schema, &walk, &continuing)));
    // Never abandoned before its stall bound while there is somewhere to look (r24: six of nine
    // were abandoned after one or two walks), never completed, revised, or set again over itself.
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &walk,
        &json!({ "disposition": "abandon", "stepResult": "noProgress" })
    )));
    for refused in [
        json!({ "disposition": "complete", "stepResult": "done" }),
        json!({ "disposition": "replace", "discover": true, "stepResult": "noProgress" }),
        json!(null),
    ] {
        assert!(
            !validator.is_valid(&answer_in(&schema, &walk, &refused)),
            "{refused}"
        );
    }
    // Once a walk reveals a log, the goal is met and is replaced by a goal for it alone.
    let mut met = with_block(held, "b1", "oak_log", Some((LOG, 1, 1)));
    met.horizon = vec![loom_contract::HorizonGoal {
        what: LOG.to_owned(),
        grounding: loom_contract::HorizonGrounding::Sight,
        via: None,
        most: 9,
        frontier: true,
    }];
    if let Some(goal) = met.goal.as_mut() {
        if let Some(progress) = goal.progress.as_mut() {
            progress.condition_met = true;
        }
    }
    let schema = decision_schema_for(&OfferedRefs::of(&met), GoalPhase::of(&met));
    assert_eq!(dispositions(&schema), ["replace"]);
    assert_eq!(count_bounds(&schema, "replace", LOG), vec![(1, 9)]);
    let validator = jsonschema::validator_for(&schema).unwrap();
    assert!(!validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b1" }),
        &json!({ "disposition": "complete", "stepResult": "done" })
    )));
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "dig", "block": "b1" }),
        &json!({ "disposition": "replace", "successItem": LOG, "successCount": 4, "stepResult": "done" })
    )));
}

#[test]
fn a_stalled_item_goal_with_nothing_grounded_may_become_a_discovery_goal() {
    let mut stalled = with_edge(fixture("perception-goal-carried"));
    if let Some(goal) = stalled.goal.as_mut() {
        if let Some(progress) = goal.progress.as_mut() {
            progress.steps_without_progress = loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS;
        }
    }
    let schema = decision_schema_for(&OfferedRefs::of(&stalled), GoalPhase::of(&stalled));
    let validator = jsonschema::validator_for(&schema).unwrap();
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &json!({ "disposition": "replace", "discover": true, "stepResult": "noProgress" })
    )));
}

/// [`seen`] with a zombie two blocks off that hunts the agent's kind, and a held stack.
fn beside_a_hunter(mut seen: Perception) -> Perception {
    seen.surroundings.push(Observation {
        kind: ObservationKind::Entity,
        what: "entity.minecraft.zombie".to_owned(),
        distance: 2.0,
        direction: loom_contract::Bearing::East,
        vertical_offset: 0.0,
        visible: true,
        target_ref: "e7".to_owned(),
        count: None,
        standable: None,
        yields: None,
        dig_safe: None,
        route_refused: None,
        hunts: Some(true),
    });
    holding_bread(seen)
}

fn holding_bread(mut seen: Perception) -> Perception {
    seen.self_state.inventory_summary.push(InventoryStack {
        slot_ref: "i8".to_owned(),
        what: "item.minecraft.bread".to_owned(),
        count: 2,
        edible: None,
    });
    seen
}

#[test]
fn a_mandatory_discovery_never_suppresses_a_survival_response() {
    // Every goal-review phase that couples its goal to its step still lets a hunter be fled or
    // fought, and a hungry agent eat, with no goal choice: the goal is deferred, not answered.
    let unheld = with_edge(fixture("perception-goal-review"));
    let held = fixture("perception-goal-discovery-held");
    let carried = with_edge(fixture("perception-goal-carried"));
    let flee = json!({ "action": "flee", "from": "e7" });
    let attack = json!({ "action": "attack", "target": "e7", "weapon": "i8", "attacks": 2, "approach": false });
    let eat = json!({ "action": "eat", "ref": "i8" });
    for (name, base) in [
        ("unheld", unheld),
        ("discovery", held),
        ("item goal", carried),
    ] {
        let hunted = beside_a_hunter(base.clone());
        let schema = decision_schema_for(&OfferedRefs::of(&hunted), GoalPhase::of(&hunted));
        let validator = jsonschema::validator_for(&schema).unwrap();
        for intent in [&flee, &attack] {
            assert!(
                validator.is_valid(&answer_in(&schema, intent, &json!(null))),
                "{name}: {intent}"
            );
        }
        // Fed, a meal is no survival response and cannot stand in for the goal.
        assert!(
            !validator.is_valid(&answer_in(&schema, &eat, &json!(null))),
            "{name}: a fed meal"
        );
        let mut starving = holding_bread(base);
        starving.self_state.food = loom_cognition::offered::HUNGRY_FOOD_LEVEL;
        let schema = decision_schema_for(&OfferedRefs::of(&starving), GoalPhase::of(&starving));
        let validator = jsonschema::validator_for(&schema).unwrap();
        assert!(
            validator.is_valid(&answer_in(&schema, &eat, &json!(null))),
            "{name}: a hungry meal"
        );
        // And no other action escapes the goal that way.
        assert!(
            !validator.is_valid(&answer_in(
                &schema,
                &json!({ "action": "moveTo", "onto": "x1" }),
                &json!(null)
            )),
            "{name}"
        );
    }
}

#[test]
fn immediate_danger_withholds_every_non_survival_action_and_a_weak_body_is_offered_no_attack() {
    // r24: seven reviews beside a near hunter dug, crafted or walked; delta's attack at 8.5 met the
    // 4.5 floor by the time it arrived.
    let flee = json!({ "action": "flee", "from": "e7" });
    let attack = json!({ "action": "attack", "target": "e7", "weapon": "i8", "attacks": 2, "approach": false });
    let dig = json!({ "action": "dig", "block": "b9" });
    let hunted = beside_a_hunter(with_bush(with_edge(fixture("perception-goal-review"))));
    for seen in [hunted.clone(), {
        let mut event = hunted.clone();
        event.trigger = loom_contract::Trigger::Damaged {
            amount: 2,
            source: "zombie".to_owned(),
            attacker_ref: None,
            health_before: None,
            health_after: None,
        };
        event
    }] {
        let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
        let validator = jsonschema::validator_for(&schema).unwrap();
        assert!(validator.is_valid(&answer_in(&schema, &flee, &json!(null))));
        assert!(validator.is_valid(&answer_in(&schema, &attack, &json!(null))));
        assert!(
            !validator.is_valid(&answer_in(&schema, &dig, &json!(null))),
            "{:?}",
            seen.trigger
        );
        assert!(!validator.is_valid(&answer_in(
            &schema,
            &json!({ "action": "idle" }),
            &json!(null)
        )));
    }
    let mut weak = hunted;
    weak.self_state.health =
        loom_cognition::offered::ATTACK_MIN_HEALTH + loom_cognition::offered::ATTACK_HEALTH_MARGIN;
    let schema = decision_schema_for(&OfferedRefs::of(&weak), GoalPhase::of(&weak));
    let validator = jsonschema::validator_for(&schema).unwrap();
    assert!(
        !validator.is_valid(&answer_in(&schema, &attack, &json!(null))),
        "the floor would refuse it"
    );
    assert!(validator.is_valid(&answer_in(&schema, &flee, &json!(null))));
}

#[test]
fn a_ranged_hunter_in_bow_range_is_immediate_where_a_melee_one_is_not() {
    let mut seen = holding_bread(with_edge(fixture("perception-goal-review")));
    let mut skeleton = beside_a_hunter(fixture("perception-goal-review"))
        .surroundings
        .pop()
        .unwrap();
    skeleton.what = "entity.minecraft.skeleton".to_owned();
    skeleton.distance = 12.0;
    seen.surroundings.push(skeleton.clone());
    assert!(OfferedRefs::of(&seen).in_immediate_danger());
    skeleton.what = "entity.minecraft.zombie".to_owned();
    *seen.surroundings.last_mut().unwrap() = skeleton;
    assert!(!OfferedRefs::of(&seen).in_immediate_danger());
}

/// The ranged-attacker fixture with its skeleton moved to `distance`, holding one stack to strike with.
fn attacked_from(distance: f64, named: bool) -> Perception {
    let mut seen = fixture("perception-damaged-ranged-attacker");
    if let Some(attacker) = seen
        .surroundings
        .iter_mut()
        .find(|seen| seen.target_ref == "e40")
    {
        attacker.distance = distance;
    }
    seen.surroundings
        .sort_by(|one, other| one.distance.total_cmp(&other.distance));
    if let loom_contract::Trigger::Damaged { attacker_ref, .. } = &mut seen.trigger {
        if !named {
            *attacker_ref = None;
        }
    }
    seen.self_state.inventory_summary = vec![loom_contract::InventoryStack {
        slot_ref: "i1".to_owned(),
        what: "item.minecraft.stick".to_owned(),
        count: 1,
        edible: None,
    }];
    seen
}

#[test]
fn the_creature_that_hurt_the_agent_is_offered_to_flee_and_strike_past_the_creature_cut() {
    // v32 plains rehearsal 15: epsilon was shot by a skeleton standing past the sixteen nearest
    // creatures, and no flight from it could be named.
    let flee = json!({ "action": "flee", "from": "e40" });
    let strike = json!({ "action": "attack", "target": "e40", "weapon": "i1", "attacks": 1, "approach": false });
    let pursue = json!({ "action": "attack", "target": "e40", "weapon": "i1", "attacks": 1, "approach": true });
    // Shooting from 20.5 blocks with seventeen creatures nearer: fled, and too far to strike.
    let ranged = attacked_from(20.5, true);
    let offered = OfferedRefs::of(&ranged);
    assert!(offered.creatures.len() > loom_cognition::offered::MAX_CREATURE_REFS);
    assert!(
        offered.danger.contains(&"e40".to_owned()),
        "{:?}",
        offered.danger
    );
    assert!(offered.in_immediate_danger());
    let schema = decision_schema_for(&offered, GoalPhase::of(&ranged));
    assert!(accepts(&schema, &flee), "{schema}");
    assert!(!accepts(&schema, &strike));
    // Without the body naming it, the same skeleton is past the cut and cannot be named at all.
    let unnamed = attacked_from(20.5, false);
    assert!(
        !OfferedRefs::of(&unnamed).admits(&loom_contract::Intent::Flee {
            from: "e40".to_owned()
        })
    );
    // Close enough to strike and to pursue, it is offered both, past the same seventeen.
    for (distance, intent) in [(3.0, &strike), (7.0, &pursue)] {
        let mut near = attacked_from(distance, true);
        for (index, other) in near
            .surroundings
            .iter_mut()
            .filter(|seen| seen.target_ref != "e40")
            .enumerate()
        {
            other.distance = 1.0 + 0.1 * f64::from(u32::try_from(index).unwrap());
        }
        near.surroundings
            .sort_by(|one, other| one.distance.total_cmp(&other.distance));
        let schema = decision_schema_for(&OfferedRefs::of(&near), GoalPhase::of(&near));
        assert!(accepts(&schema, &flee), "{distance}");
        assert!(accepts(&schema, intent), "{distance}: {schema}");
    }
}

/// A perception from `tests/fixtures`, as its mind received it.
fn local_fixture(name: &str) -> Perception {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("{name}.json"));
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn a_met_goal_whose_next_source_lies_where_no_cell_is_offered_still_has_an_answer() {
    // r25 stopped a minute in on the provider refusing a met goal's follow-up review: every item
    // it could replace the goal with was a remembered log lying where no edge-of-sight cell was
    // offered, so no item had a step and the answer schema offered nothing. The v32 plains
    // rehearsal held two such reviews, each answered by a walk to the edge of sight that the body
    // accepted as a replace.
    for (name, item) in [
        (
            "v32-met-discovery-next-log-unwalkable",
            "item.minecraft.oak_log",
        ),
        (
            "v32-met-table-next-logs-unwalkable",
            "item.minecraft.birch_log",
        ),
    ] {
        let seen = local_fixture(name);
        let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
        let pairings = schema["properties"]["decision"]["anyOf"]
            .as_array()
            .unwrap();
        assert!(!pairings.is_empty(), "{name}");
        let validator = jsonschema::validator_for(&schema).unwrap();
        let answer = answer_in(
            &schema,
            &json!({ "action": "moveTo", "onto": "x1" }),
            &json!({ "disposition": "replace", "stepResult": "progressing", "successItem": item, "successCount": 1 }),
        );
        assert!(validator.is_valid(&answer), "{name}: {schema}");
    }
}
