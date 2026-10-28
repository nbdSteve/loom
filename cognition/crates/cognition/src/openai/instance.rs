//! Instances of a request's strict decision schema, for the stand-ins that answer without a model.
//!
//! A provider-free rehearsal is worth what its answers are: the r39 offline audit found the
//! scripted stand-in idling beside an abandon with the edge of sight offered, an answer no strict
//! schema offers and so one no model could give. So the stand-ins hold every answer to the schema
//! the same perception would be asked under: [`admits`] reads the subset of JSON Schema the
//! decision schemas use - `anyOf`, a `$ref` into `$defs`, `enum`, `type`, `properties`,
//! `required`, a closed `additionalProperties`, `minimum` and `maximum` - and [`first_instance`]
//! writes the first answer a schema offers, for an answer that needs replacing. The provider
//! itself is the authority on strict mode; `tests/openai_strict_limits.rs` holds this reading to a
//! full validator over every corpus perception.

use serde_json::{Map, Value};

use crate::model::Draft;

/// How deep a schema is followed, past strict mode's own nesting bound, so a malformed one ends.
const MAX_DEPTH: usize = 32;

/// The answer `draft` is, worded as `schema` words one: a goal review's coupled `decision`
/// pairing its intent with its goal choice, any other answer's intent and goal beside its reasoning.
#[must_use]
pub fn answer_of(schema: &Value, draft: &Draft) -> Value {
    let intent = serde_json::to_value(&draft.intent).unwrap_or(Value::Null);
    let goal = serde_json::to_value(&draft.goal).unwrap_or(Value::Null);
    let mut answer = Map::new();
    if schema["properties"].get("decision").is_some() {
        let mut decision = Map::new();
        decision.insert("intent".to_owned(), intent);
        decision.insert("goal".to_owned(), goal);
        answer.insert("decision".to_owned(), Value::Object(decision));
    } else {
        answer.insert("intent".to_owned(), intent);
        answer.insert("goal".to_owned(), goal);
    }
    answer.insert(
        "reasoning".to_owned(),
        Value::String(draft.reasoning.clone()),
    );
    Value::Object(answer)
}

/// Whether `value` is an instance of `schema`, whose `$ref`s resolve into its own `$defs`.
#[must_use]
pub fn admits(schema: &Value, value: &Value) -> bool {
    admits_within(schema, schema, value, 0)
}

/// Whether `value` is an instance of `part`, a schema inside `root`.
#[must_use]
pub fn admits_part(root: &Value, part: &Value, value: &Value) -> bool {
    admits_within(root, part, value, 0)
}

/// The first instance `schema` offers: each union's first offered branch, each `enum`'s first
/// value, each bounded number's least, and an object's required fields alone. `None` where the
/// schema offers no instance at all.
#[must_use]
pub fn first_instance(schema: &Value) -> Option<Value> {
    instance_within(schema, schema, 0)
}

/// The first instance `part`, a schema inside `root`, offers.
#[must_use]
pub fn first_part_instance(root: &Value, part: &Value) -> Option<Value> {
    instance_within(root, part, 0)
}

/// The schema `part`, inside `root`, names by its `$ref`, or `part` itself; `null` for a reference
/// `root` does not define.
#[must_use]
pub fn resolved_part<'a>(root: &'a Value, part: &'a Value) -> &'a Value {
    resolved(root, part).unwrap_or(&Value::Null)
}

/// The schema `part` names by its `$ref`, or `part` itself.
fn resolved<'a>(root: &'a Value, part: &'a Value) -> Option<&'a Value> {
    match part.get("$ref").and_then(Value::as_str) {
        Some(reference) => {
            let name = reference.strip_prefix("#/$defs/")?;
            root.get("$defs")?.get(name)
        }
        None => Some(part),
    }
}

fn admits_within(root: &Value, part: &Value, value: &Value, depth: usize) -> bool {
    if depth > MAX_DEPTH {
        return false;
    }
    let Some(part) = resolved(root, part) else {
        return false;
    };
    if let Some(union) = part.get("anyOf").and_then(Value::as_array) {
        if !union
            .iter()
            .any(|branch| admits_within(root, branch, value, depth + 1))
        {
            return false;
        }
    }
    if let Some(values) = part.get("enum").and_then(Value::as_array) {
        if !values.contains(value) {
            return false;
        }
    }
    if let Some(kind) = part.get("type") {
        let named = |name: &Value| name.as_str().is_some_and(|name| of_type(name, value));
        let typed = match kind {
            Value::Array(kinds) => kinds.iter().any(named),
            single => named(single),
        };
        if !typed {
            return false;
        }
    }
    if let Some(number) = value.as_f64() {
        let below = part
            .get("minimum")
            .and_then(Value::as_f64)
            .is_some_and(|least| number < least);
        let above = part
            .get("maximum")
            .and_then(Value::as_f64)
            .is_some_and(|most| number > most);
        if below || above {
            return false;
        }
    }
    match value {
        Value::Object(fields) => object_admits(root, part, fields, depth),
        Value::Array(items) => part.get("items").is_none_or(|each| {
            items
                .iter()
                .all(|item| admits_within(root, each, item, depth + 1))
        }),
        _ => true,
    }
}

/// Whether `fields` meet `part`'s properties, required fields and closure.
fn object_admits(root: &Value, part: &Value, fields: &Map<String, Value>, depth: usize) -> bool {
    let properties = part.get("properties").and_then(Value::as_object);
    let required = part
        .get("required")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .all(|name| fields.contains_key(name));
    let closed = part.get("additionalProperties") == Some(&Value::Bool(false));
    required
        && fields.iter().all(|(name, field)| {
            match properties.and_then(|properties| properties.get(name)) {
                Some(schema) => admits_within(root, schema, field, depth + 1),
                None => !closed,
            }
        })
}

/// Whether `value` is of the JSON type `name`.
fn of_type(name: &str, value: &Value) -> bool {
    match name {
        "null" => value.is_null(),
        "boolean" => value.is_boolean(),
        "string" => value.is_string(),
        "integer" => value.is_i64() || value.is_u64(),
        "number" => value.is_number(),
        "object" => value.is_object(),
        "array" => value.is_array(),
        _ => false,
    }
}

fn instance_within(root: &Value, part: &Value, depth: usize) -> Option<Value> {
    if depth > MAX_DEPTH {
        return None;
    }
    let part = resolved(root, part)?;
    if let Some(union) = part.get("anyOf").and_then(Value::as_array) {
        return union
            .iter()
            .find_map(|branch| instance_within(root, branch, depth + 1));
    }
    if let Some(values) = part.get("enum").and_then(Value::as_array) {
        return values.first().cloned();
    }
    let kind = match part.get("type") {
        Some(Value::Array(kinds)) => kinds.first().and_then(Value::as_str),
        Some(single) => single.as_str(),
        None => None,
    };
    match kind? {
        "null" => Some(Value::Null),
        "boolean" => Some(Value::Bool(false)),
        "string" => Some(Value::String(String::new())),
        "integer" | "number" => Some(
            part.get("minimum")
                .cloned()
                .unwrap_or_else(|| Value::from(0)),
        ),
        "array" => Some(Value::Array(Vec::new())),
        "object" => {
            let properties = part.get("properties").and_then(Value::as_object);
            let mut fields = Map::new();
            for name in part
                .get("required")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                let field = properties.and_then(|properties| properties.get(name))?;
                fields.insert(name.to_owned(), instance_within(root, field, depth + 1)?);
            }
            Some(Value::Object(fields))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use serde_json::json;

    use super::{admits, first_instance};

    #[test]
    fn a_closed_union_of_tagged_objects_admits_only_its_own_shapes() {
        let schema = json!({
            "$defs": { "ref": { "type": "string", "enum": ["b1", "b2"] } },
            "type": "object",
            "properties": {
                "intent": { "anyOf": [
                    { "type": "object", "properties": { "action": { "type": "string", "enum": ["dig"] }, "block": { "$ref": "#/$defs/ref" } }, "required": ["action", "block"], "additionalProperties": false },
                    { "type": "object", "properties": { "action": { "type": "string", "enum": ["idle"] } }, "required": ["action"], "additionalProperties": false }
                ] },
                "count": { "type": "integer", "minimum": 2, "maximum": 4 }
            },
            "required": ["intent", "count"],
            "additionalProperties": false
        });
        assert!(admits(
            &schema,
            &json!({ "intent": { "action": "dig", "block": "b2" }, "count": 3 })
        ));
        assert!(!admits(
            &schema,
            &json!({ "intent": { "action": "dig", "block": "b3" }, "count": 3 })
        ));
        assert!(!admits(
            &schema,
            &json!({ "intent": { "action": "idle", "block": "b1" }, "count": 3 })
        ));
        assert!(!admits(
            &schema,
            &json!({ "intent": { "action": "idle" }, "count": 5 })
        ));
        assert!(!admits(&schema, &json!({ "intent": { "action": "idle" } })));
        let first = first_instance(&schema).unwrap();
        assert_eq!(
            first,
            json!({ "intent": { "action": "dig", "block": "b1" }, "count": 2 })
        );
        assert!(admits(&schema, &first));
    }
}
