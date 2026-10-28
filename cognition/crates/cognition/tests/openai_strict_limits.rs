//! Every request schema a real perception produces, held to the provider's strict structured-output
//! rules before a run can meet them.
//!
//! r25 stopped a minute in: the provider refused one follow-up review's request as an invalid
//! request, and the rehearsals that precede a paid run use the scripted stand-in, which builds no
//! schema at all. So this sweeps the request schema of every perception the repository holds - each
//! contract fixture and every perception in a committed experiment's evidence - and of any local
//! evidence named in `LOOM_STRICT_CORPUS` (comma-separated `evidence.jsonl` paths), through the
//! exact builder a request uses, against each rule strict mode states.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{chat_request, decision_schema_for, GoalPhase};
use loom_cognition::prompt::render;
use loom_cognition::router::{ModelCatalog, Router};
use loom_contract::Perception;
use serde_json::Value;

/// The provider's strict-mode bounds on one schema.
const MAX_ENUM_VALUES: usize = 1_000;
const MAX_PROPERTIES: usize = 5_000;
const MAX_NESTING: usize = 10;
const MAX_STRING_CHARS: usize = 120_000;
const LARGE_ENUM: usize = 250;
const MAX_LARGE_ENUM_CHARS: usize = 15_000;

/// Keywords strict mode does not accept anywhere in a schema.
const REFUSED: [&str; 12] = [
    "oneOf",
    "allOf",
    "not",
    "if",
    "then",
    "else",
    "patternProperties",
    "minItems",
    "maxItems",
    "uniqueItems",
    "minProperties",
    "maxProperties",
];

#[derive(Default)]
struct Tally {
    enum_values: usize,
    properties: usize,
    string_chars: usize,
    depth: usize,
}

/// How much of each bound `schema` spends.
fn tally_of(schema: &Value) -> Tally {
    let mut tally = Tally::default();
    walk(
        schema,
        "#",
        0,
        &BTreeSet::new(),
        &mut tally,
        &mut Vec::new(),
    );
    tally
}

/// Every strict-mode rule `schema` breaks.
fn violations(schema: &Value) -> Vec<String> {
    let mut found = Vec::new();
    let mut tally = Tally::default();
    if schema.get("type") != Some(&Value::String("object".to_owned())) {
        found.push("the root is not an object".to_owned());
    }
    let defs: BTreeSet<String> = schema["$defs"]
        .as_object()
        .map(|defs| defs.keys().cloned().collect())
        .unwrap_or_default();
    walk(schema, "#", 0, &defs, &mut tally, &mut found);
    if tally.enum_values > MAX_ENUM_VALUES {
        found.push(format!("{} enum values", tally.enum_values));
    }
    if tally.properties > MAX_PROPERTIES {
        found.push(format!("{} properties", tally.properties));
    }
    if tally.string_chars > MAX_STRING_CHARS {
        found.push(format!(
            "{} characters of names and values",
            tally.string_chars
        ));
    }
    if tally.depth > MAX_NESTING {
        found.push(format!("{} levels of object nesting", tally.depth));
    }
    found
}

fn walk(
    node: &Value,
    at: &str,
    depth: usize,
    defs: &BTreeSet<String>,
    tally: &mut Tally,
    found: &mut Vec<String>,
) {
    match node {
        Value::Array(items) => {
            for (index, item) in items.iter().enumerate() {
                walk(item, &format!("{at}/{index}"), depth, defs, tally, found);
            }
        }
        Value::Object(map) => {
            for keyword in REFUSED {
                if map.contains_key(keyword) {
                    found.push(format!("{at} uses {keyword}"));
                }
            }
            let object = map.get("type") == Some(&Value::String("object".to_owned()))
                || map.contains_key("properties");
            let depth = if object { depth + 1 } else { depth };
            tally.depth = tally.depth.max(depth);
            if object {
                let properties = map.get("properties").and_then(Value::as_object);
                if map.get("additionalProperties") != Some(&Value::Bool(false)) {
                    found.push(format!("{at} does not close additionalProperties"));
                }
                let named: BTreeSet<&str> = properties
                    .map(|props| props.keys().map(String::as_str).collect())
                    .unwrap_or_default();
                let required: BTreeSet<&str> = map
                    .get("required")
                    .and_then(Value::as_array)
                    .map(|req| req.iter().filter_map(Value::as_str).collect())
                    .unwrap_or_default();
                if named != required {
                    found.push(format!("{at} does not require exactly its properties"));
                }
                tally.properties += named.len();
                tally.string_chars += named.iter().map(|name| name.len()).sum::<usize>();
            }
            for keyword in ["enum", "anyOf"] {
                if let Some(list) = map.get(keyword) {
                    match list.as_array() {
                        Some(list) if !list.is_empty() => {}
                        _ => found.push(format!("{at} has an empty {keyword}")),
                    }
                }
            }
            for keyword in ["enum", "anyOf", "required"] {
                if let Some(list) = map.get(keyword).and_then(Value::as_array) {
                    let distinct: BTreeSet<String> = list.iter().map(Value::to_string).collect();
                    if distinct.len() != list.len() {
                        found.push(format!("{at} repeats a value in its {keyword}"));
                    }
                }
            }
            if let (Some(least), Some(most)) = (
                map.get("minimum").and_then(Value::as_f64),
                map.get("maximum").and_then(Value::as_f64),
            ) {
                if least > most {
                    found.push(format!("{at} has an empty range {least}..{most}"));
                }
            }
            if let (Some(Value::String(kind)), Some(values)) =
                (map.get("type"), map.get("enum").and_then(Value::as_array))
            {
                let typed = |value: &Value| match kind.as_str() {
                    "string" => value.is_string(),
                    "integer" => value.is_i64() || value.is_u64(),
                    "number" => value.is_number(),
                    "boolean" => value.is_boolean(),
                    _ => true,
                };
                if !values.iter().all(typed) {
                    found.push(format!("{at} has an enum value that is not a {kind}"));
                }
            }
            if let Some(values) = map.get("enum").and_then(Value::as_array) {
                tally.enum_values += values.len();
                let chars: usize = values
                    .iter()
                    .map(|value| value.as_str().map_or(0, str::len))
                    .sum();
                tally.string_chars += chars;
                if values.len() > LARGE_ENUM && chars > MAX_LARGE_ENUM_CHARS {
                    found.push(format!("{at} is a large enum of {chars} characters"));
                }
            }
            if let Some(Value::String(constant)) = map.get("const") {
                tally.string_chars += constant.len();
            }
            if let Some(Value::String(target)) = map.get("$ref") {
                let name = target.strip_prefix("#/$defs/");
                if name.is_none_or(|name| !defs.contains(name)) {
                    found.push(format!("{at} refers to {target}, which is not defined"));
                }
            }
            for (key, value) in map {
                walk(value, &format!("{at}/{key}"), depth, defs, tally, found);
            }
        }
        _ => {}
    }
}

fn repository() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

/// Every perception the evidence file at `path` records that today's contract reads, as its mind
/// received it: the goal, last refusal and horizon a goal review is sent with are added after the
/// body records the perception, and the `decided` record states them as `shown`.
fn perceptions_in(path: &Path) -> Vec<(String, Perception)> {
    let text = fs::read_to_string(path).unwrap();
    let records: Vec<Value> = text
        .lines()
        .filter_map(|line| serde_json::from_str(line).ok())
        .collect();
    let shown: std::collections::HashMap<&str, &Value> = records
        .iter()
        .filter(|record| record["event"] == "decided" && record.get("shown").is_some())
        .filter_map(|record| Some((record["requestKey"].as_str()?, &record["shown"])))
        .collect();
    records
        .iter()
        .filter(|record| record["event"] == "perceived")
        .filter_map(|record| {
            let mut sent = record["perception"].clone();
            if let Some(shown) = record["requestKey"].as_str().and_then(|key| shown.get(key)) {
                for field in ["goal", "lastRefusal", "horizon"] {
                    if let Some(value) = shown.get(field) {
                        sent[field] = value.clone();
                    }
                }
            }
            let id = record["recordId"].as_str().unwrap_or("?").to_owned();
            serde_json::from_value(sent)
                .ok()
                .map(|perception| (id, perception))
        })
        .collect()
}

fn corpus() -> Vec<(String, Perception)> {
    let root = repository();
    let mut seen = Vec::new();
    for entry in fs::read_dir(root.join("contract/fixtures/valid")).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        if name.starts_with("perception-") {
            seen.push((
                name,
                serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap(),
            ));
        }
    }
    for entry in fs::read_dir(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")).unwrap()
    {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        seen.push((
            name,
            serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap(),
        ));
    }
    for entry in fs::read_dir(root.join("research/experiments")).unwrap() {
        let evidence = entry.unwrap().path().join("evidence.jsonl");
        if evidence.exists() {
            seen.extend(perceptions_in(&evidence));
        }
    }
    if let Ok(extra) = std::env::var("LOOM_STRICT_CORPUS") {
        for path in extra.split(',').filter(|path| !path.is_empty()) {
            seen.extend(perceptions_in(Path::new(path)));
        }
    }
    seen
}

#[test]
fn every_request_schema_a_perception_produces_is_one_strict_mode_accepts() {
    let corpus = corpus();
    assert!(corpus.len() > 1_000, "{} perceptions", corpus.len());
    let mut broken = Vec::new();
    let mut widest = (0, 0, 0, 0);
    for (id, perception) in &corpus {
        let schema = decision_schema_for(&OfferedRefs::of(perception), GoalPhase::of(perception));
        let mut found = violations(&schema);
        if !loom_cognition::openai::wire::request_offers_an_answer(perception) {
            found.push("the stand-ins would refuse it as offering no answer".to_owned());
        }
        let tally = tally_of(&schema);
        widest = (
            widest.0.max(tally.enum_values),
            widest.1.max(tally.properties),
            widest.2.max(tally.string_chars),
            widest.3.max(tally.depth),
        );
        if !found.is_empty() {
            broken.push(format!("{id}: {}", found.join("; ")));
        }
    }
    eprintln!(
        "widest: {} enum values, {} properties, {} characters, {} levels",
        widest.0, widest.1, widest.2, widest.3
    );
    assert!(
        broken.is_empty(),
        "{} of {} request schemas break strict mode:\n{}",
        broken.len(),
        corpus.len(),
        broken
            .iter()
            .take(20)
            .cloned()
            .collect::<Vec<_>>()
            .join("\n")
    );
}

/// The request bound every live research configuration pins (`cognition.maxRequestBytes`), which
/// the harness also prices as the input-token ceiling a spend bound is proved against.
const LIVE_MAX_REQUEST_BYTES: usize = 65_536;

/// The output bound those configurations pin (`cognition.maxOutputTokens`).
const LIVE_MAX_OUTPUT_TOKENS: u32 = 2_048;

#[test]
fn every_request_a_perception_produces_fits_the_live_request_bound() {
    // The routine-v36 rehearsal: an r39 review restated under the new end reasons came within
    // 1,816 bytes of the bound, which a refused request would have spent as a cognition failure.
    let router = Router::new(ModelCatalog::new("gpt-5.4-nano", "gpt-5.4"));
    let mut widest = (0, String::new());
    let mut over = Vec::new();
    for (id, perception) in &corpus() {
        let route = router.route(perception.importance);
        let schema = decision_schema_for(&OfferedRefs::of(perception), GoalPhase::of(perception));
        let prompt = render(&route, perception);
        let request = chat_request(route.model(), &prompt, LIVE_MAX_OUTPUT_TOKENS, schema);
        let bytes = serde_json::to_vec(&request).unwrap().len();
        if bytes > widest.0 {
            widest = (bytes, id.clone());
        }
        if bytes > LIVE_MAX_REQUEST_BYTES {
            over.push(format!("{id}: {bytes} bytes"));
        }
    }
    eprintln!(
        "widest request: {} bytes ({}), {} inside the bound",
        widest.0,
        widest.1,
        LIVE_MAX_REQUEST_BYTES - widest.0.min(LIVE_MAX_REQUEST_BYTES)
    );
    assert!(
        over.is_empty(),
        "requests past the live bound:\n{}",
        over.join("\n")
    );
}

#[test]
fn every_answer_the_scripted_stand_in_gives_is_one_its_own_request_offers() {
    // The r39 offline audit: the stand-in idled beside an abandon with the edge of sight offered,
    // which the strict schema never offers, so a provider-free rehearsal exercised answers no model
    // could give. Every corpus perception's stand-in answer is held to the schema it was asked under.
    let route =
        Router::new(ModelCatalog::uniform("x")).route(loom_contract::DecisionImportance::Routine);
    let mut refused = Vec::new();
    let mut answered = 0;
    for (id, perception) in &corpus() {
        if !loom_cognition::openai::wire::request_offers_an_answer(perception) {
            continue;
        }
        let schema = decision_schema_for(&OfferedRefs::of(perception), GoalPhase::of(perception));
        let validator = jsonschema::validator_for(&schema).unwrap();
        let draft = loom_cognition::model::ScriptedBackend::draft_now(&route, perception);
        answered += 1;
        let answer = loom_cognition::openai::instance::answer_of(&schema, &draft);
        if !validator.is_valid(&answer) {
            refused.push(format!("{id}: {answer}"));
        }
    }
    eprintln!(
        "{answered} stand-in answers, {} outside their schema",
        refused.len()
    );
    assert!(
        refused.is_empty(),
        "{} of {answered}:\n{}",
        refused.len(),
        refused
            .iter()
            .take(40)
            .cloned()
            .collect::<Vec<_>>()
            .join("\n")
    );
}
