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
use loom_cognition::openai::wire::decision_schema_for;
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
    let _ = (coupled(schema), goal);
    json!({ "intent": intent, "reasoning": "a test answer", "plan": { "update": "continue" } })
}

/// Whether `schema` admits `intent` beside no goal choice or an abandon for any reason, whichever
/// it offers.
fn accepts(schema: &Value, intent: &Value) -> bool {
    let validator = jsonschema::validator_for(schema).expect("the request schema compiles");
    std::iter::once(json!(null))
        .chain(
            ["completed", "impossible", "unsafe", "stale", "superseded"]
                .iter()
                .map(|reason| json!({ "disposition": "abandon", "stepResult": "done", "reason": reason })),
        )
        .any(|goal| validator.is_valid(&answer_in(schema, intent, &goal)))
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
    let schema = decision_schema_for(&OfferedRefs::of(&fixture("perception-threat-sighted")));
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
    let schema = decision_schema_for(&OfferedRefs::of(&fixture("perception-standing-on")));
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
    let schema = decision_schema_for(&OfferedRefs::of(&seen));
    assert_eq!(schema["$defs"]["keptBlockRef"]["enum"], json!(["b1"]));
    assert_eq!(schema["$defs"]["digBlockRef"]["enum"], json!(["b2", "b3"]));
    assert!(!accepts(
        &schema,
        &json!({ "action": "dig", "block": "b1" })
    ));
    assert!(accepts(&schema, &json!({ "action": "dig", "block": "b2" })));
    // Neither is a bed, so neither is a sleep (R46: sleeps in dirt, refused as no bed).
    for block in ["b1", "b2"] {
        assert!(!accepts(
            &schema,
            &json!({ "action": "sleep", "bed": block })
        ));
    }
}

#[test]
fn an_action_with_nothing_offered_to_name_is_not_offered() {
    let mut seen = fixture("perception-standing-on");
    seen.surroundings.clear();
    let schema = decision_schema_for(&OfferedRefs::of(&seen));
    assert_eq!(actions(&schema), ["say", "rest", "idle"]);
    // No ref namespace.
    assert!(schema["$defs"].as_object().unwrap().is_empty());

    let crafting = decision_schema_for(&OfferedRefs::of(&fixture("perception-crafting")));
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
fn a_craft_is_offered_no_more_applications_than_its_recipe_allows() {
    // r35 alpha: two applications of a recipe its planks made once.
    let mut seen = fixture("perception-crafting");
    let once = CraftingOption {
        recipe_ref: "r2".to_owned(),
        max_crafts: 1,
        ..seen.self_state.crafting_options[0].clone()
    };
    seen.self_state.crafting_options.push(once);
    let schema = decision_schema_for(&OfferedRefs::of(&seen));
    let craft = |recipe: &str, crafts: i64| json!({ "action": "craft", "recipe": recipe, "crafts": crafts });
    assert!(accepts(&schema, &craft("r1", 3)));
    assert!(!accepts(&schema, &craft("r1", 4)));
    assert!(accepts(&schema, &craft("r2", 1)));
    assert!(!accepts(&schema, &craft("r2", 2)));
}

#[test]
fn every_offered_ref_is_one_enum_once_whichever_actions_spend_it() {
    let offered = OfferedRefs::of(&fixture("perception-max-observations"));
    let schema = decision_schema_for(&offered);
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
        cover: None,
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
    let schema = decision_schema_for(&OfferedRefs::of(&seen));
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
    let schema = decision_schema_for(&OfferedRefs::of(&seen));
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
        // One stick a dig, so no one dig meets the two an objective asks.
        yields: Some(vec![loom_contract::BlockYield {
            what: "item.minecraft.stick".to_owned(),
            least: 1,
            most: 1,
            per_hundred: 100,
        }]),
        dig_safe: Some(true),
        route_refused: None,
        hunts: None,
        cover: None,
    });
    seen
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
        cover: None,
    };
    seen.surroundings = (0..MAX_PERCEPTION_OBSERVATIONS)
        .map(|index| Observation {
            target_ref: format!("b{index:03}"),
            yields: Some(
                (0..loom_contract::MAX_BLOCK_YIELDS)
                    .map(|item| loom_contract::BlockYield {
                        what: format!("item.minecraft.{:0>49}", (index + item) % 24),
                        least: 0,
                        // Eight a dig at most, so a goal of any of them asks past one dig.
                        most: 8,
                        per_hundred: 800,
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
    let schema = decision_schema_for(&offered);
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
            cover: None,
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
    let schema = decision_schema_for(&offered);
    let mut count = 0;
    let mut characters = 0;
    enum_values(&schema, &mut count, &mut characters);
    assert!(count <= 500, "{count} enum values");
    assert!(characters <= 7_500, "{characters} enum characters");
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
fn a_stalled_item_goal_with_nothing_grounded_may_become_a_discovery_goal() {
    let mut stalled = with_edge(fixture("perception-goal-carried"));
    if let Some(goal) = stalled.goal.as_mut() {
        if let Some(progress) = goal.progress.as_mut() {
            progress.steps_without_progress = loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS;
        }
    }
    let schema = decision_schema_for(&OfferedRefs::of(&stalled));
    let validator = jsonschema::validator_for(&schema).unwrap();
    assert!(validator.is_valid(&answer_in(
        &schema,
        &json!({ "action": "moveTo", "onto": "x1" }),
        &json!({ "disposition": "replace", "discover": true, "stepResult": "noProgress", "reason": "stale" })
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
        cover: None,
    });
    holding_bread(seen)
}

fn holding_bread(mut seen: Perception) -> Perception {
    seen.self_state.inventory_summary.push(InventoryStack {
        slot_ref: "i8".to_owned(),
        what: "item.minecraft.bread".to_owned(),
        count: 2,
        edible: None,
        solid: None,
        held: None,
    });
    seen
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
        solid: None,
        held: None,
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
    let schema = decision_schema_for(&offered);
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
        let schema = decision_schema_for(&OfferedRefs::of(&near));
        assert!(accepts(&schema, &flee), "{distance}");
        assert!(accepts(&schema, intent), "{distance}: {schema}");
    }
}
