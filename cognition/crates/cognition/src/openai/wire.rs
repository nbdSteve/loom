//! The bytes on the wire between Loom and `OpenAI`, and the schema that makes the
//! answer a decision rather than prose.
//!
//! Every field Loom sends is declared here as a type, so the request is a value
//! a test can inspect rather than a JSON literal built at the call site. Three
//! choices in it are load-bearing and not obvious:
//!
//! - **The answer's shape is enforced by the provider, not requested in prose.**
//!   [`decision_schema`] is sent as a strict `json_schema` response format, which
//!   is why [`crate::prompt`] never describes an output format: there would then
//!   be two statements of the same thing, and the prose one would drift. The
//!   schema mirrors the contract's [`Intent`] union - internally tagged on
//!   `action` - and an integration test validates it against the shared contract
//!   fixtures, so adding an `Intent` variant fails that test until the schema
//!   follows.
//! - **`max_completion_tokens`, not `max_tokens`.** The newer models reject the
//!   older field outright, and a request that is refused at the API is a decision
//!   an agent does not get.
//! - **No `temperature`.** Reasoning models accept only the default and reject
//!   the field being set at all. Loom's variation should come from the world an
//!   agent is in, not from a sampling knob nobody is tuning.
//!
//! What is *absent* is deliberate too: [`ApiErrorBody`] has no `message` field.
//! The provider's error message is the one part of an error response that can
//! quote back what was sent, so it is not deserialized at all rather than
//! deserialized and carefully not logged - there is then no code path that could
//! start logging it later.

use std::sync::LazyLock;

use loom_contract::Intent;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::config::bounded_to;
use crate::model::Draft;
use crate::prompt::Prompt;

/// Name the response schema is registered under in the request.
///
/// Reaches the provider's logs and error messages, so it says what it is.
pub const SCHEMA_NAME: &str = "loom_decision";

/// Longest reasoning Loom keeps from an answer, in characters.
///
/// The response read is bounded already, so this is not the defence against an
/// enormous body; it bounds what goes into the permanent decision record and
/// onto the wire back to the body. Generous enough for the few sentences a
/// decision deserves.
const MAX_REASONING_CHARS: usize = 2_000;

/// The chat completion request for one decision.
#[derive(Debug, Serialize)]
pub struct ChatRequest<'a> {
    /// The model id, taken from the resolved route.
    pub model: &'a str,
    /// The standing instructions, then this moment.
    pub messages: Vec<Message<'a>>,
    /// Bound on what one answer may cost.
    ///
    /// Named `max_completion_tokens` because that is the field the current models
    /// accept; `max_tokens` is refused.
    pub max_completion_tokens: u32,
    /// Forces the answer to be a decision-shaped JSON object.
    pub response_format: ResponseFormat,
}

/// One message in the request.
#[derive(Debug, Serialize)]
pub struct Message<'a> {
    /// `system` or `user`.
    pub role: &'static str,
    /// The message text.
    pub content: &'a str,
}

/// The strict-JSON-schema response format.
#[derive(Debug, Serialize)]
pub struct ResponseFormat {
    /// Always `json_schema`.
    #[serde(rename = "type")]
    pub kind: &'static str,
    /// The schema itself and its strictness.
    pub json_schema: SchemaSpec,
}

/// The schema an answer must satisfy.
#[derive(Debug, Serialize)]
pub struct SchemaSpec {
    /// [`SCHEMA_NAME`].
    pub name: &'static str,
    /// Always true. Without it the schema is a suggestion.
    pub strict: bool,
    /// [`decision_schema`].
    pub schema: &'static Value,
}

/// Builds the request for one prompt at one model.
#[must_use]
pub fn chat_request<'a>(
    model: &'a str,
    prompt: &'a Prompt,
    max_output_tokens: u32,
) -> ChatRequest<'a> {
    ChatRequest {
        model,
        messages: vec![
            Message {
                role: "system",
                content: &prompt.system,
            },
            Message {
                role: "user",
                content: &prompt.situation,
            },
        ],
        max_completion_tokens: max_output_tokens,
        response_format: ResponseFormat {
            kind: "json_schema",
            json_schema: SchemaSpec {
                name: SCHEMA_NAME,
                strict: true,
                schema: decision_schema(),
            },
        },
    }
}

/// The successful shape of a chat completion response.
///
/// Only the parts Loom uses are declared. Unknown fields are ignored, because
/// the provider adds fields over time and none of the ones Loom reads change
/// meaning when it does.
#[derive(Debug, Deserialize)]
pub struct ChatResponse {
    /// The answers. Loom asks for one and reads the first.
    ///
    /// Required, deliberately: a body with no `choices` key is not a completion
    /// at all - an HTML error page, or some other service answering - and that is
    /// a different failure from a completion that carries no answer. Defaulting
    /// it to empty would report a proxy's error page as a model that had nothing
    /// to say, and an operator would look in the wrong place.
    pub choices: Vec<Choice>,
}

/// One answer.
#[derive(Debug, Deserialize)]
pub struct Choice {
    /// Why generation stopped. `length` means the answer was cut off, which is
    /// the one value Loom has to act on rather than report.
    #[serde(default)]
    pub finish_reason: Option<String>,
    /// The answer itself.
    pub message: ChoiceMessage,
}

/// The message inside an answer.
#[derive(Debug, Deserialize)]
pub struct ChoiceMessage {
    /// The JSON text of the decision, absent when the model refused.
    #[serde(default)]
    pub content: Option<String>,
    /// Present when the model declined to answer.
    ///
    /// Its text is model output that can quote the perception back, so Loom
    /// records *that* a refusal happened and never the refusal's words.
    #[serde(default)]
    pub refusal: Option<String>,
}

/// An error response.
#[derive(Debug, Deserialize)]
pub struct ApiErrorEnvelope {
    /// The error.
    pub error: ApiErrorBody,
}

/// The part of a provider error Loom is willing to repeat.
///
/// `type` and `code` are short provider-defined identifiers - `rate_limit_error`,
/// `invalid_api_key` - which is exactly what an operator needs. `message` is
/// absent on purpose; see this module's note.
#[derive(Debug, Deserialize)]
pub struct ApiErrorBody {
    /// The error class.
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    /// The specific error code, where the provider gives one.
    #[serde(default)]
    pub code: Option<String>,
}

impl ApiErrorBody {
    /// The provider's own identifiers for this error, bounded, or `None` if it
    /// gave neither.
    #[must_use]
    pub fn identifiers(&self) -> Option<String> {
        let parts: Vec<String> = [self.kind.as_deref(), self.code.as_deref()]
            .into_iter()
            .flatten()
            .filter(|part| !part.is_empty())
            .map(crate::config::bounded)
            .collect();
        if parts.is_empty() {
            None
        } else {
            Some(parts.join("/"))
        }
    }
}

/// What Loom keeps from an answer: exactly the contract's half of a decision.
///
/// Unknown fields are ignored rather than refused. The provider's answer is raw
/// material, not the contract - the contract's own types are where strictness
/// belongs - and losing an otherwise-valid decision because a provider added a
/// key would cost an agent a decision for nothing.
#[derive(Debug, Deserialize)]
struct Answer {
    /// The intent, parsed through the contract's own type so a shape the body
    /// cannot apply is caught here rather than at the body.
    intent: Intent,
    /// The model's justification.
    reasoning: String,
}

/// Parses the model's answer text into a [`Draft`].
///
/// # Errors
///
/// A short reason, safe to log and safe to put in a refusal frame: it names the
/// kind of malformation and where in the text it was, never the text. A model
/// answer can contain anything, including the perception it was given, so the
/// content of a bad answer is not repeated. `serde_json`'s own `Display` is
/// deliberately not used, because it quotes the offending value.
pub fn draft_from_answer(content: &str) -> Result<Draft, String> {
    match serde_json::from_str::<Answer>(content) {
        Ok(answer) => Ok(Draft {
            intent: answer.intent,
            reasoning: bounded_to(&answer.reasoning, MAX_REASONING_CHARS),
        }),
        Err(e) => Err(format!(
            "the model's answer was not a decision ({detail})",
            detail = describe_json_error(&e)
        )),
    }
}

/// Describes a JSON failure without repeating the JSON.
///
/// A category and a position, which is enough to tell a truncated answer from a
/// well-formed answer of the wrong shape - the two failures with different fixes.
/// `serde_json`'s own `Display` is deliberately not used anywhere in this crate's
/// provider handling: it quotes the offending value, and the values here are
/// provider output that can contain the perception it was given.
#[must_use]
pub fn describe_json_error(e: &serde_json::Error) -> String {
    let category = match e.classify() {
        serde_json::error::Category::Io => "read error",
        serde_json::error::Category::Syntax => "not JSON",
        serde_json::error::Category::Data => "wrong shape",
        serde_json::error::Category::Eof => "ended early",
    };
    format!(
        "{category} at line {line} column {column}",
        line = e.line(),
        column = e.column()
    )
}

/// The schema every answer must satisfy.
///
/// Built once. Strict structured outputs constrain what a schema may say, and two
/// of those constraints shape this one:
///
/// - Every object must list all its properties as `required` and set
///   `additionalProperties: false`. That is why the union is an `anyOf` of four
///   closed objects rather than one object with optional fields.
/// - Array length cannot be expressed - `minItems` and `maxItems` are not
///   supported - so `moveTo`'s target says its length in words, and the contract
///   type `[i64; 3]` is what actually enforces it. An answer with the wrong
///   number of coordinates therefore fails in [`draft_from_answer`], as a bounded
///   malformed-answer error.
#[must_use]
pub fn decision_schema() -> &'static Value {
    static SCHEMA: LazyLock<Value> = LazyLock::new(build_decision_schema);
    &SCHEMA
}

/// One `Intent` variant, as a closed object tagged with its `action`.
fn intent_variant(action: &str, fields: &Value) -> Value {
    let mut properties = json!({ "action": { "type": "string", "enum": [action] } });
    let mut required = vec![json!("action")];
    if let (Some(properties), Some(fields)) = (properties.as_object_mut(), fields.as_object()) {
        for (name, schema) in fields {
            properties.insert(name.clone(), schema.clone());
            required.push(json!(name));
        }
    }
    json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    })
}

fn build_decision_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "intent": {
                "description": "The single action the body should take now.",
                "anyOf": [
                    intent_variant("say", &json!({
                        "text": {
                            "type": "string",
                            "description": "What to say out loud."
                        }
                    })),
                    intent_variant("moveTo", &json!({
                        "target": {
                            "type": "array",
                            "description": "Exactly three whole numbers: the x, y and z of the block to walk to.",
                            "items": { "type": "integer" }
                        }
                    })),
                    intent_variant("flee", &json!({
                        "from": {
                            "type": "string",
                            "description": "The name of what to move away from."
                        }
                    })),
                    intent_variant("rest", &json!({})),
                    intent_variant("idle", &json!({})),
                ],
            },
            "reasoning": {
                "type": "string",
                "description": "One or two sentences on why this action, recorded permanently.",
            },
        },
        "required": ["intent", "reasoning"],
        "additionalProperties": false,
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        chat_request, decision_schema, draft_from_answer, ApiErrorBody, ChatResponse,
        MAX_REASONING_CHARS, SCHEMA_NAME,
    };
    use crate::prompt::Prompt;
    use loom_contract::Intent;
    use serde_json::Value;

    fn prompt() -> Prompt {
        Prompt {
            system: "you are an agent".to_owned(),
            situation: "a zombie is nearby".to_owned(),
        }
    }

    #[test]
    fn a_request_names_the_routed_model_and_bounds_the_answer() {
        let prompt = prompt();
        let request = chat_request("some-model", &prompt, 128);
        let sent: Value = serde_json::to_value(&request).unwrap();
        assert_eq!(sent["model"], "some-model");
        assert_eq!(sent["max_completion_tokens"], 128);
        assert_eq!(sent["messages"][0]["role"], "system");
        assert_eq!(sent["messages"][0]["content"], "you are an agent");
        assert_eq!(sent["messages"][1]["role"], "user");
        assert_eq!(sent["messages"][1]["content"], "a zombie is nearby");
        assert_eq!(sent["response_format"]["type"], "json_schema");
        assert_eq!(sent["response_format"]["json_schema"]["name"], SCHEMA_NAME);
        assert_eq!(sent["response_format"]["json_schema"]["strict"], true);
    }

    #[test]
    fn a_request_sets_no_sampling_knobs_and_no_superseded_token_field() {
        let prompt = prompt();
        let sent: Value = serde_json::to_value(chat_request("m", &prompt, 8)).unwrap();
        let object = sent.as_object().unwrap();
        for absent in ["temperature", "top_p", "max_tokens"] {
            assert!(
                !object.contains_key(absent),
                "{absent} must not be sent: the current models refuse it"
            );
        }
    }

    #[test]
    fn every_intent_variant_is_a_closed_tagged_object() {
        let schema = decision_schema();
        let variants = schema["properties"]["intent"]["anyOf"].as_array().unwrap();
        assert_eq!(variants.len(), 5, "one per contract Intent variant");
        let mut actions = Vec::new();
        for variant in variants {
            assert_eq!(variant["additionalProperties"], false);
            let properties = variant["properties"].as_object().unwrap();
            let required = variant["required"].as_array().unwrap();
            assert_eq!(
                properties.len(),
                required.len(),
                "strict schemas require every property, {variant} did not"
            );
            actions.push(variant["properties"]["action"]["enum"][0].clone());
        }
        assert_eq!(
            actions,
            vec!["say", "moveTo", "flee", "rest", "idle"],
            "the tags must be the contract's own action names"
        );
    }

    #[test]
    fn the_schema_expresses_no_keyword_strict_mode_refuses() {
        // Array length cannot be expressed in a strict schema; the contract type
        // is what enforces it. A schema that quietly reintroduced these would be
        // rejected by the provider at request time, for every decision.
        let text = decision_schema().to_string();
        for refused in ["minItems", "maxItems", "oneOf", "uniqueItems", "pattern"] {
            assert!(
                !text.contains(refused),
                "{refused} is not usable in strict mode"
            );
        }
    }

    #[test]
    fn a_well_formed_answer_becomes_a_draft() {
        let draft =
            draft_from_answer(r#"{"intent":{"action":"flee","from":"zombie"},"reasoning":"hurt"}"#)
                .unwrap();
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "zombie".to_owned()
            }
        );
        assert_eq!(draft.reasoning, "hurt");
    }

    #[test]
    fn a_bad_answer_is_reported_without_repeating_it() {
        let secret_looking = r#"{"intent":{"action":"say","text":"sk-lookalike-value"}}"#;
        let reason = draft_from_answer(secret_looking).expect_err("reasoning is missing");
        assert!(
            !reason.contains("sk-lookalike"),
            "a malformed answer must not be echoed, got {reason}"
        );
        assert!(reason.contains("wrong shape"), "got {reason}");

        let not_json =
            draft_from_answer("I would rather not answer.").expect_err("prose is not a decision");
        assert!(not_json.contains("not JSON"), "got {not_json}");
    }

    #[test]
    fn a_target_of_the_wrong_length_is_refused_where_the_schema_could_not_say_so() {
        let reason =
            draft_from_answer(r#"{"intent":{"action":"moveTo","target":[1,2]},"reasoning":"go"}"#)
                .expect_err("a target is exactly three coordinates");
        assert!(reason.contains("wrong shape"), "got {reason}");
    }

    #[test]
    fn reasoning_is_bounded_before_it_enters_the_record() {
        let long = "x".repeat(MAX_REASONING_CHARS * 3);
        let answer = format!(r#"{{"intent":{{"action":"idle"}},"reasoning":"{long}"}}"#);
        let draft = draft_from_answer(&answer).unwrap();
        assert!(draft.reasoning.chars().count() < MAX_REASONING_CHARS + 10);
        assert!(draft.reasoning.ends_with("..."));
    }

    #[test]
    fn a_refusal_and_a_cut_off_answer_are_both_visible_in_the_response() {
        let body = r#"{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":null,"refusal":"I cannot help with that"}}]}"#;
        let response: ChatResponse = serde_json::from_str(body).unwrap();
        let choice = &response.choices[0];
        assert_eq!(choice.finish_reason.as_deref(), Some("length"));
        assert!(choice.message.content.is_none());
        assert!(choice.message.refusal.is_some());
    }

    #[test]
    fn a_provider_error_yields_its_identifiers_and_nothing_else() {
        let body = r#"{"error":{"message":"Incorrect API key provided: sk-abc...xyz","type":"invalid_request_error","code":"invalid_api_key","param":null}}"#;
        let envelope: super::ApiErrorEnvelope = serde_json::from_str(body).unwrap();
        let identifiers = envelope.error.identifiers().unwrap();
        assert_eq!(identifiers, "invalid_request_error/invalid_api_key");
        assert!(
            !format!("{:?}", envelope.error).contains("sk-abc"),
            "the provider's message is not deserialized, so it cannot be printed"
        );
    }

    #[test]
    fn an_error_with_no_identifiers_says_so_rather_than_inventing_one() {
        let empty = ApiErrorBody {
            kind: None,
            code: Some(String::new()),
        };
        assert!(empty.identifiers().is_none());
    }
}
