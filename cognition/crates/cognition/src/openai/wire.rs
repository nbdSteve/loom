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

use loom_contract::{
    GoalChoice, GoalDisposition, Intent, Perception, Trigger, MAX_GOAL_STATEMENT_CHARS,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::config::bounded_to;
use crate::model::Draft;
use crate::offered::OfferedRefs;
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
    /// [`decision_schema_for`] the perception this request asks about.
    pub schema: Value,
}

/// Builds the request for one prompt at one model, answered in `schema`.
#[must_use]
pub fn chat_request<'a>(
    model: &'a str,
    prompt: &'a Prompt,
    max_output_tokens: u32,
    schema: Value,
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
                schema,
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
    /// Provider-reported billing counts, when requested by an experiment.
    #[serde(default)]
    pub usage: Option<Usage>,
}

/// Provider-reported token counts for one successful request.
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
pub struct Usage {
    /// Tokens in the rendered request.
    pub prompt_tokens: u64,
    /// Tokens in the generated answer, including hidden reasoning tokens.
    pub completion_tokens: u64,
    /// Provider total, checked against the two component counts before logging.
    pub total_tokens: u64,
    /// Optional detail needed to price discounted cached input.
    #[serde(default)]
    pub prompt_tokens_details: PromptTokenDetails,
}

/// The subset of prompt-token detail that affects cost.
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
pub struct PromptTokenDetails {
    /// Input tokens served from the provider's prompt cache.
    #[serde(default)]
    pub cached_tokens: u64,
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
    /// Provider error class, if present.
    #[must_use]
    pub fn kind(&self) -> Option<&str> {
        self.kind.as_deref().filter(|value| !value.is_empty())
    }

    /// Provider error code, if present.
    #[must_use]
    pub fn code(&self) -> Option<&str> {
        self.code.as_deref().filter(|value| !value.is_empty())
    }

    /// The provider's own identifiers for this error, bounded, or `None` if it
    /// gave neither.
    #[must_use]
    pub fn identifiers(&self) -> Option<String> {
        let parts: Vec<String> = [self.kind(), self.code()]
            .into_iter()
            .flatten()
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
    /// The optional goal choice. `null` and absence both mean no choice, so a
    /// held goal is left as it is; the strict schema always sends the key.
    #[serde(default)]
    goal: Option<GoalChoice>,
}

/// Which goal-choice fields one disposition requires; every other field is forbidden.
///
/// The working-goal shape: adopt and revise word the goal and its success condition;
/// adopt, continue and revise name the one step this decision takes toward it; and every
/// disposition that answers a carried goal judges that goal's previous step.
const fn goal_fields(disposition: GoalDisposition) -> GoalFields {
    match disposition {
        GoalDisposition::Adopt => GoalFields {
            worded: true,
            stepped: true,
            judged: false,
        },
        GoalDisposition::Continue => GoalFields {
            worded: false,
            stepped: true,
            judged: true,
        },
        GoalDisposition::Revise => GoalFields {
            worded: true,
            stepped: true,
            judged: true,
        },
        GoalDisposition::Complete | GoalDisposition::Abandon => GoalFields {
            worded: false,
            stepped: false,
            judged: true,
        },
    }
}

#[derive(Clone, Copy)]
struct GoalFields {
    worded: bool,
    stepped: bool,
    judged: bool,
}

/// Refuses a goal choice whose fields do not match its disposition.
///
/// The contract type admits every field on any disposition and leaves the body to refuse
/// what is missing, but an answer is held to the closed shapes the strict schema offers,
/// so a field the agent wrote but the ledger would silently discard never enters the
/// record as if it meant something, and a field the ledger needs is never missing.
fn checked_goal(goal: Option<GoalChoice>) -> Result<Option<GoalChoice>, String> {
    let Some(choice) = goal else {
        return Ok(None);
    };
    let fields = goal_fields(choice.disposition);
    let present = [
        ("statement", fields.worded, choice.statement.is_some()),
        (
            "success condition",
            fields.worded,
            choice.success_condition.is_some(),
        ),
        ("success item", fields.worded, choice.success_item.is_some()),
        (
            "success count",
            fields.worded,
            choice.success_count.is_some(),
        ),
        ("step", fields.stepped, choice.step.is_some()),
        ("step action", fields.stepped, choice.step_action.is_some()),
        ("step result", fields.judged, choice.step_result.is_some()),
    ];
    // A step's ref is nullable, for an action that names none, so only its presence off a
    // stepped disposition is a fault.
    if !fields.stepped && choice.step_ref.is_some() {
        return Err("the model's goal choice gave a step ref its disposition forbids".to_owned());
    }
    for (name, required, given) in present {
        if required && !given {
            return Err(format!(
                "the model's goal choice needs a {name} it did not give"
            ));
        }
        if !required && given {
            return Err(format!(
                "the model's goal choice gave a {name} its disposition forbids"
            ));
        }
    }
    Ok(Some(choice))
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
    let parsed = serde_json::from_str::<Value>(content)
        .map(bounded_goal_texts)
        .and_then(serde_json::from_value::<Answer>);
    match parsed {
        Ok(answer) => Ok(Draft {
            goal: checked_goal(answer.goal)?,
            intent: answer.intent,
            reasoning: bounded_to(&answer.reasoning, MAX_REASONING_CHARS),
        }),
        Err(e) => Err(format!(
            "the model's answer was not a decision ({detail})",
            detail = describe_json_error(&e)
        )),
    }
}

/// The goal's free-text fields, bounded to the contract's length before they are typed.
///
/// Strict mode cannot state a string length, so a model that words a goal, its success
/// condition or its step at length writes a valid answer the contract would refuse - which
/// lost the eighth live run in one answer. The text is cut, the way reasoning is, rather than
/// the whole decision. Nothing else is touched: a blank field, a wrong type and every other
/// shape fault still fail as a malformed answer.
fn bounded_goal_texts(mut answer: Value) -> Value {
    if let Some(goal) = answer.get_mut("goal").and_then(Value::as_object_mut) {
        for field in ["statement", "successCondition", "step"] {
            if let Some(Value::String(text)) = goal.get_mut(field) {
                if text.chars().count() > MAX_GOAL_STATEMENT_CHARS {
                    let kept: String = text.chars().take(MAX_GOAL_STATEMENT_CHARS - 3).collect();
                    *text = format!("{kept}...");
                }
            }
        }
    }
    answer
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

/// The unconstrained shape every answer has, whatever was offered.
///
/// Built once. This is the schema's reference shape: every variant present, and each
/// ref a plain string. It is what the fixture pin tests hold against the contract,
/// since a request never sends it - see [`decision_schema_for`].
///
/// Strict structured outputs constrain what a schema may say, and two of those
/// constraints shape this one:
///
/// - Every object must list all its properties as `required` and set
///   `additionalProperties: false`. That is why the union is an `anyOf` of closed
///   objects rather than one object with optional fields.
/// - String length cannot be expressed either, so a ref's bound is the contract
///   type's: an over-long or blank ref fails in [`draft_from_answer`], as a bounded
///   malformed-answer error.
#[must_use]
pub fn decision_schema() -> &'static Value {
    static SCHEMA: LazyLock<Value> = LazyLock::new(|| build_decision_schema(None, GoalPhase::Any));
    &SCHEMA
}

/// The schema one request sends: every ref an `enum` of the handles this perception
/// offers in that action's own namespace.
///
/// A variant whose refs have nothing to name is left out, because an action the body
/// can only refuse is not one to offer. `say`, `rest` and `idle` name nothing and are
/// always present, so the union is never empty.
#[must_use]
pub fn decision_schema_for(offered: &OfferedRefs, phase: GoalPhase) -> Value {
    build_decision_schema(Some(offered), phase)
}

/// One `Intent` variant, as a closed object tagged with its `action`, or `None` when a
/// ref it requires has nothing offered to name.
fn intent_variant(action: &str, fields: Vec<(&str, Option<Value>)>) -> Option<Value> {
    let mut properties = serde_json::Map::new();
    properties.insert(
        "action".to_owned(),
        json!({ "type": "string", "enum": [action] }),
    );
    let mut required = vec![json!("action")];
    for (name, schema) in fields {
        properties.insert(name.to_owned(), schema?);
        required.push(json!(name));
    }
    Some(json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    }))
}

/// One request namespace: the handles one action may name in one mode, defined once under
/// `$defs` so a block offered to three actions costs one enum and not three.
#[derive(Clone, Copy)]
enum Namespace {
    Block,
    DigBlock,
    WalkTarget,
    Creature,
    StrikeTarget,
    PursuitTarget,
    PickupItem,
    RouteItem,
    Stack,
    Recipe,
    SuccessItem,
}

impl Namespace {
    const ALL: [Self; 11] = [
        Self::Block,
        Self::DigBlock,
        Self::WalkTarget,
        Self::Creature,
        Self::StrikeTarget,
        Self::PursuitTarget,
        Self::PickupItem,
        Self::RouteItem,
        Self::Stack,
        Self::Recipe,
        Self::SuccessItem,
    ];

    const fn name(self) -> &'static str {
        match self {
            Self::Block => "keptBlockRef",
            Self::DigBlock => "digBlockRef",
            Self::WalkTarget => "walkTargetRef",
            Self::Creature => "creatureRef",
            Self::StrikeTarget => "strikeTargetRef",
            Self::PursuitTarget => "pursuitTargetRef",
            Self::PickupItem => "pickupItemRef",
            Self::RouteItem => "routeItemRef",
            Self::Stack => "stackRef",
            Self::Recipe => "recipeRef",
            Self::SuccessItem => "successItemName",
        }
    }

    const fn description(self) -> &'static str {
        match self {
            Self::Block => "A block ref offered in this message that is in sight and within reach, but that you may not dig: digging it would leave you in a hole with no way out.",
            Self::DigBlock => "A block ref offered in this message that is in sight, within reach, and safe to dig.",
            Self::WalkTarget => {
                "A block ref offered in this message that you can stand at, outside your own spot."
            }
            Self::Creature => "A living-thing ref offered in this message.",
            Self::StrikeTarget => {
                "A living-thing ref offered in this message within striking reach."
            }
            Self::PursuitTarget => {
                "A living-thing ref offered in this message within pursuit reach."
            }
            Self::PickupItem => "A dropped-item ref offered in this message within pickup reach.",
            Self::RouteItem => "A dropped-item ref offered in this message within walking reach.",
            Self::Stack => "An inventory-stack ref offered in this message.",
            Self::Recipe => "A recipe ref offered in this message.",
            Self::SuccessItem => "An item you can obtain now: one you hold, one dropped in sight, one a recipe offers, or one a block in sight drops when you dig it.",
        }
    }

    fn of(self, offered: &OfferedRefs) -> &[String] {
        match self {
            Self::Block => &offered.kept_blocks,
            Self::DigBlock => &offered.dig_blocks,
            Self::WalkTarget => &offered.walk_targets,
            Self::Creature => &offered.creatures,
            Self::StrikeTarget => &offered.strike_targets,
            Self::PursuitTarget => &offered.pursuit_targets,
            Self::PickupItem => &offered.pickup_items,
            Self::RouteItem => &offered.route_items,
            Self::Stack => &offered.stacks,
            Self::Recipe => &offered.recipes,
            Self::SuccessItem => &offered.obtainable,
        }
    }
}

/// One ref field: a plain string in the reference shape, a reference to the offered
/// namespace in a request, and `None` when a request offers that namespace nothing.
fn ref_field(
    description: &str,
    namespace: Namespace,
    offered: Option<&OfferedRefs>,
) -> Option<Value> {
    match offered {
        None => Some(json!({ "type": "string", "description": description })),
        Some(refs) if namespace.of(refs).is_empty() => None,
        Some(_) => Some(json!({
            "description": description,
            "anyOf": [{ "$ref": format!("#/$defs/{}", namespace.name()) }],
        })),
    }
}

/// One ref field any of several namespaces may fill, which is how one action names refs that
/// another action splits: `place` and `sleep` take every block in reach, partitioned by whether a
/// dig may name it, so that no block's handle is listed twice in a request's enums.
fn any_ref_field(
    description: &str,
    namespaces: &[Namespace],
    offered: Option<&OfferedRefs>,
) -> Option<Value> {
    let Some(refs) = offered else {
        return Some(json!({ "type": "string", "description": description }));
    };
    let any: Vec<Value> = namespaces
        .iter()
        .filter(|namespace| !namespace.of(refs).is_empty())
        .map(|namespace| json!({ "$ref": format!("#/$defs/{}", namespace.name()) }))
        .collect();
    (!any.is_empty()).then(|| json!({ "description": description, "anyOf": any }))
}

/// The `$defs` a request carries: one closed string enum per namespace it offers.
fn namespace_defs(offered: &OfferedRefs) -> Value {
    let mut defs = serde_json::Map::new();
    for namespace in Namespace::ALL {
        let handles = namespace.of(offered);
        if !handles.is_empty() {
            defs.insert(
                namespace.name().to_owned(),
                json!({
                    "type": "string",
                    "enum": handles,
                    "description": namespace.description(),
                }),
            );
        }
    }
    Value::Object(defs)
}

/// A field that is not a ref, present whatever was offered.
#[allow(clippy::unnecessary_wraps)]
fn plain(schema: Value) -> Option<Value> {
    Some(schema)
}

/// An `approach` field: either boolean in the reference shape, or the one value a request's
/// variant is for, since what an action may reach depends on whether it may move.
#[allow(clippy::unnecessary_wraps)]
fn approach_field(description: &str, only: Option<bool>) -> Option<Value> {
    Some(match only {
        None => json!({ "type": "boolean", "description": description }),
        Some(value) => json!({ "type": "boolean", "enum": [value], "description": description }),
    })
}

/// The approach modes a schema offers: one boolean field in the reference shape, or one closed
/// variant per mode in a request, each naming only what that mode can reach.
fn modes(offered: Option<&OfferedRefs>) -> Vec<Option<bool>> {
    if offered.is_some() {
        vec![Some(false), Some(true)]
    } else {
        vec![None]
    }
}

const COLLECT_APPROACH: &str = "Whether to permit one bounded walk toward that item. False permits no movement, and only an item already within pickup reach can then be collected.";
const ATTACK_APPROACH: &str = "Whether to permit one bounded approach to the target's sensed position. False permits no movement, and only a target already within striking reach can then be hit.";

fn collect_variants(offered: Option<&OfferedRefs>) -> Vec<Option<Value>> {
    modes(offered)
        .into_iter()
        .map(|mode| {
            let items = if mode == Some(true) {
                Namespace::RouteItem
            } else {
                Namespace::PickupItem
            };
            intent_variant(
                "collect",
                vec![
                    (
                        "item",
                        ref_field(
                            "The ref of the exact dropped-item entity to collect.",
                            items,
                            offered,
                        ),
                    ),
                    ("approach", approach_field(COLLECT_APPROACH, mode)),
                ],
            )
        })
        .collect()
}

fn attack_variants(offered: Option<&OfferedRefs>) -> Vec<Option<Value>> {
    modes(offered)
        .into_iter()
        .map(|mode| {
            let targets = if mode == Some(true) {
                Namespace::PursuitTarget
            } else {
                Namespace::StrikeTarget
            };
            intent_variant(
                "attack",
                vec![
                    (
                        "target",
                        ref_field(
                            "The ref of the exact offered living target.",
                            targets,
                            offered,
                        ),
                    ),
                    (
                        "weapon",
                        ref_field(
                            "The ref of the exact offered inventory stack to use as the weapon.",
                            Namespace::Stack,
                            offered,
                        ),
                    ),
                    (
                        "attacks",
                        plain(json!({
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 4,
                            "description": "The maximum number of bounded attack swings."
                        })),
                    ),
                    ("approach", approach_field(ATTACK_APPROACH, mode)),
                ],
            )
        })
        .collect()
}

/// One goal disposition, as a closed object carrying exactly the fields it requires.
/// One goal disposition, or `None` when it words a goal and a request offers nothing it could
/// name as a success item.
fn goal_variant(
    disposition: GoalDisposition,
    name: &str,
    description: &str,
    offered: Option<&OfferedRefs>,
) -> Option<Value> {
    let fields = goal_fields(disposition);
    let success_item = ref_field(
        "The item the goal is achieved by holding, named exactly as the item is listed. Your body measures it from your own inventory.",
        Namespace::SuccessItem,
        offered,
    );
    if fields.worded && success_item.is_none() {
        return None;
    }
    let mut properties = serde_json::Map::new();
    properties.insert(
        "disposition".to_owned(),
        json!({ "type": "string", "enum": [name], "description": description }),
    );
    let worded = format!("one short sentence of at most {MAX_GOAL_STATEMENT_CHARS} characters");
    if fields.worded {
        properties.insert(
            "statement".to_owned(),
            json!({ "type": "string", "description": format!("The goal in your own words, {worded}.") }),
        );
        properties.insert(
            "successCondition".to_owned(),
            json!({
                "type": "string",
                "description": format!(
                    "What you would observe in your own perception when the goal is achieved, {worded}."
                ),
            }),
        );
        if let Some(field) = success_item {
            properties.insert("successItem".to_owned(), field);
        }
        properties.insert(
            "successCount".to_owned(),
            json!({
                "type": "integer",
                "minimum": 1,
                "maximum": 64,
                "description": "How many of that item the goal is achieved by holding.",
            }),
        );
    }
    if fields.stepped {
        properties.insert(
            "step".to_owned(),
            json!({
                "type": "string",
                "description": format!(
                    "The one concrete next step this decision's action takes toward the goal, {worded}."
                ),
            }),
        );
        properties.insert(
            "stepAction".to_owned(),
            json!({
                "type": "string",
                "enum": INTENT_ACTIONS,
                "description": "The action this decision's intent takes, exactly. A step that names a different action from the intent is refused with it.",
            }),
        );
        properties.insert(
            "stepRef".to_owned(),
            json!({
                "type": ["string", "null"],
                "description": "The ref this decision's intent acts on, exactly - the walk's block, the dig's block, the collect's item, the attack's target, the placement's support block - or null for an action that names none. A step that names a different ref from the intent is refused with it.",
            }),
        );
    }
    if fields.judged {
        properties.insert(
            "stepResult".to_owned(),
            json!({
                "type": "string",
                "enum": ["done", "progressing", "noProgress"],
                "description": "Your verdict on the previous step, from the progress evidence the carried goal states: done finished it, progressing moved it forward, noProgress did not.",
            }),
        );
    }
    let required: Vec<Value> = properties.keys().map(|key| json!(key)).collect();
    Some(json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    }))
}

/// The optional goal choice: `null`, or exactly one closed disposition shape.
///
/// Strict mode requires the key, so "no choice" is an explicit `null` rather than
/// an absent field. The statement exists only on the two dispositions that word a
/// goal, which is what keeps a statement from riding on a disposition that would
/// discard it.
/// Every intent action, in the contract's order, for a typed step.
const INTENT_ACTIONS: [&str; 13] = [
    "say", "moveTo", "flee", "eat", "dig", "collect", "place", "equip", "craft", "attack", "sleep",
    "rest", "idle",
];

/// Where one perception stands with its goal, which decides the goal choices it can make.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalPhase {
    /// Not a goal review, or the reference shape: every choice is offered.
    Any,
    /// A goal review with no goal held: adopt one, or make no choice.
    Unheld,
    /// A goal review whose held goal's success condition is not met.
    Held,
    /// A goal review whose held goal's success condition is met: complete it or revise it.
    Met,
}

impl GoalPhase {
    /// The phase `perception` is in.
    #[must_use]
    pub fn of(perception: &Perception) -> Self {
        if !matches!(perception.trigger, Trigger::GoalReview) {
            return Self::Any;
        }
        match &perception.goal {
            None => Self::Unheld,
            Some(goal)
                if goal
                    .progress
                    .as_ref()
                    .is_some_and(|seen| seen.condition_met) =>
            {
                Self::Met
            }
            Some(_) => Self::Held,
        }
    }
}

/// The goal choice: `null`, or one closed disposition shape the body would accept now.
///
/// Strict mode requires the key, so "no choice" is an explicit `null` rather than
/// an absent field. The statement exists only on the two dispositions that word a
/// goal, which is what keeps a statement from riding on a disposition that would
/// discard it. At a review the offer narrows to what the ledger accepts: a met success
/// condition can only be completed or revised, and an unmet one is never completed.
fn goal_choice_schema(phase: GoalPhase, offered: Option<&OfferedRefs>) -> Value {
    let adopt = || {
        goal_variant(
            GoalDisposition::Adopt,
            "adopt",
            "Take up a new goal when you hold none.",
            offered,
        )
    };
    let continued = || {
        goal_variant(
            GoalDisposition::Continue,
            "continue",
            "Keep the held goal and take its next step.",
            offered,
        )
    };
    let revise = || {
        goal_variant(GoalDisposition::Revise, "revise", "Keep pursuing the held goal with new wording, success target and step; when its condition is met, a revise must name a target you do not yet hold.", offered)
    };
    let complete = || {
        goal_variant(
            GoalDisposition::Complete,
            "complete",
            "The held goal's success condition is met; drop it.",
            offered,
        )
    };
    let abandon = || {
        goal_variant(
            GoalDisposition::Abandon,
            "abandon",
            "The held goal is no longer worth pursuing; drop it.",
            offered,
        )
    };
    let none = || Some(json!({ "type": "null" }));
    let choices = match phase {
        GoalPhase::Any => vec![
            none(),
            adopt(),
            continued(),
            revise(),
            complete(),
            abandon(),
        ],
        GoalPhase::Unheld => vec![none(), adopt()],
        GoalPhase::Held => vec![none(), continued(), revise(), abandon()],
        GoalPhase::Met => vec![complete(), revise()],
    };
    let choices: Vec<Value> = choices.into_iter().flatten().collect();
    json!({
        "description": "Your choice about your one short-term goal, only at a scheduled goal review; null makes no choice and leaves any held goal as it is.",
        "anyOf": choices,
    })
}

fn build_decision_schema(offered: Option<&OfferedRefs>, phase: GoalPhase) -> Value {
    let blocks = |description: &str| {
        any_ref_field(
            description,
            &[Namespace::DigBlock, Namespace::Block],
            offered,
        )
    };
    let dig_blocks = |description: &str| ref_field(description, Namespace::DigBlock, offered);
    let walk_targets = |description: &str| ref_field(description, Namespace::WalkTarget, offered);
    let creatures = |description: &str| ref_field(description, Namespace::Creature, offered);
    let stacks = |description: &str| ref_field(description, Namespace::Stack, offered);
    let recipes = |description: &str| ref_field(description, Namespace::Recipe, offered);
    let mut variants: Vec<Option<Value>> = vec![
        intent_variant("say", vec![("text", plain(json!({
            "type": "string",
            "description": "One line of player chat heard by agents nearby: at most 128 characters and 256 UTF-8 bytes, words separated by single spaces, no leading or trailing space, no leading '/', and no control, formatting or bidirectional characters. Text outside these bounds is refused and never sent."
        })))]),
        intent_variant("moveTo", vec![("onto", walk_targets("The ref of the one nearby block to walk to: the body stands on top of a solid block, or in the own space of a block it can walk through such as a dead bush or grass. A block ref from this message outside your own spot, never coordinates or an invented one."))]),
        intent_variant("flee", vec![("from", creatures("The ref of the one living thing to move away from. A creature ref given in this message, never a name, a type, or an invented one."))]),
        intent_variant("eat", vec![("ref", stacks("The ref of the one stack in your own inventory to eat one item from. A stack ref given in this message, never an item name or an invented one."))]),
        intent_variant("dig", vec![("block", dig_blocks("The ref of the exact nearby block to dig once. A block ref from this message, never coordinates or an item name."))]),
    ];
    variants.extend(collect_variants(offered));
    variants.extend([
        intent_variant("place", vec![
            ("block", blocks("The ref of the exact sensed support block.")),
            ("item", stacks("The ref of the offered inventory stack already held in the main hand.")),
            ("face", plain(json!({
                "type": "string",
                "enum": ["down", "up", "north", "south", "west", "east"],
                "description": "The support-block face against which to place once."
            }))),
        ]),
        intent_variant("equip", vec![
            ("item", stacks("The ref of the exact offered inventory stack to equip.")),
            ("destination", plain(json!({
                "type": "string",
                "enum": ["hand", "offHand", "head", "torso", "legs", "feet"],
                "description": "The bounded player equipment destination."
            }))),
        ]),
        intent_variant("craft", vec![
            ("recipe", recipes("The ref of the exact offered recipe to apply.")),
            ("crafts", plain(json!({
                "type": "integer",
                "minimum": 1,
                "maximum": 8,
                "description": "How many bounded applications of that offered recipe to request."
            }))),
        ]),
    ]);
    variants.extend(attack_variants(offered));
    variants.extend([
        intent_variant(
            "sleep",
            vec![(
                "bed",
                blocks("The ref of the exact offered bed to use once."),
            )],
        ),
        intent_variant("rest", vec![]),
        intent_variant("idle", vec![]),
    ]);
    let variants: Vec<Value> = variants.into_iter().flatten().collect();
    let mut schema = json!({
        "type": "object",
        "properties": {
            "intent": {
                "description": "The single action the body should take now.",
                "anyOf": variants,
            },
            "reasoning": {
                "type": "string",
                "description": "One or two sentences on why this action, recorded permanently.",
            },
            "goal": goal_choice_schema(phase, offered),
        },
        "required": ["intent", "reasoning", "goal"],
        "additionalProperties": false,
    });
    if let (Some(refs), Some(root)) = (offered, schema.as_object_mut()) {
        root.insert("$defs".to_owned(), namespace_defs(refs));
    }
    schema
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        chat_request, decision_schema, decision_schema_for, draft_from_answer, ApiErrorBody,
        ChatResponse, GoalPhase, MAX_REASONING_CHARS, SCHEMA_NAME,
    };
    use crate::offered::OfferedRefs;
    use crate::openai::settings::DEFAULT_MAX_REQUEST_BYTES;
    use crate::prompt::{render, Prompt};
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{
        DecisionImportance, GoalChoice, GoalDisposition, GoalProgress, GoalStep, GoalStepResult,
        GoalSummary, Intent, IntentAction, InventoryChange, Perception, Trigger, MAX_GOAL_ID_CHARS,
        MAX_GOAL_STATEMENT_CHARS, MAX_INVENTORY_CHANGES, MAX_ITEM_NAME_CHARS, MAX_SUCCESS_COUNT,
        REF_MAX_CHARS,
    };
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
        let request = chat_request("some-model", &prompt, 128, decision_schema().clone());
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
    fn the_complete_observation_envelope_fits_the_provider_request_bound() {
        let seen: Perception = serde_json::from_str(include_str!(
            "../../../../../contract/fixtures/valid/perception-max-observations.json"
        ))
        .unwrap();
        let router = Router::new(ModelCatalog::new("fast", "deep"));
        let prompt = render(&router.route(seen.importance), &seen);
        let measured = serde_json::to_vec(&chat_request(
            "fast",
            &prompt,
            2_048,
            decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen)),
        ))
        .unwrap()
        .len();

        assert!(
            measured <= usize::try_from(DEFAULT_MAX_REQUEST_BYTES / 4).unwrap(),
            "the 144-observation provider request is {measured} bytes against the \
             {DEFAULT_MAX_REQUEST_BYTES}-byte request bound"
        );
    }

    #[test]
    fn the_complete_envelope_at_a_goal_review_with_the_widest_goal_fits_the_bound() {
        let mut seen: Perception = serde_json::from_str(include_str!(
            "../../../../../contract/fixtures/valid/perception-max-observations.json"
        ))
        .unwrap();
        // The goal line renders at a goal review only, so the widest goal is
        // measured on that trigger with every goal field at its contract limit.
        seen.trigger = Trigger::GoalReview;
        seen.importance = DecisionImportance::Routine;
        seen.goal = Some(GoalSummary {
            goal_id: "g".repeat(MAX_GOAL_ID_CHARS),
            statement: "s".repeat(MAX_GOAL_STATEMENT_CHARS),
            revision: i64::MAX,
            adopted_tick: i64::MAX,
            last_step: Some(GoalStep {
                request_id: "r".repeat(MAX_GOAL_ID_CHARS),
                action: "a".repeat(32),
                outcome: "o".repeat(MAX_GOAL_ID_CHARS),
            }),
            success_condition: Some("c".repeat(MAX_GOAL_STATEMENT_CHARS)),
            success_item: Some("i".repeat(MAX_ITEM_NAME_CHARS)),
            success_count: Some(MAX_SUCCESS_COUNT),
            step: Some("t".repeat(MAX_GOAL_STATEMENT_CHARS)),
            step_action: Some(IntentAction::MoveTo),
            step_ref: Some("f".repeat(REF_MAX_CHARS)),
            progress: Some(GoalProgress {
                condition_met: true,
                condition_held: i64::MAX,
                // Every inventory slot's item changed by the widest count.
                inventory_delta: (0..MAX_INVENTORY_CHANGES)
                    .map(|index| InventoryChange {
                        what: format!("{index:0>MAX_ITEM_NAME_CHARS$}"),
                        change: i64::MIN,
                    })
                    .collect(),
                condition_advanced: true,
                step_advanced: true,
                step_evidence: loom_contract::StepEvidence::None,
                steps_without_progress: i64::MAX,
            }),
        });
        let router = Router::new(ModelCatalog::new("fast", "deep"));
        let prompt = render(&router.route(seen.importance), &seen);
        let measured = serde_json::to_vec(&chat_request(
            "fast",
            &prompt,
            2_048,
            decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen)),
        ))
        .unwrap()
        .len();
        let bound = usize::try_from(DEFAULT_MAX_REQUEST_BYTES / 4).unwrap();

        assert!(prompt
            .situation
            .contains(&"s".repeat(MAX_GOAL_STATEMENT_CHARS)));
        assert!(
            measured <= bound,
            "the widest goal-review provider request is {measured} bytes against {bound}"
        );
    }

    #[test]
    fn a_maximum_measured_prompt_still_fits_when_every_byte_needs_json_escaping() {
        const MAX_MEASURED_PROMPT_BYTES: usize = 64 * 1024;
        let prompt = Prompt {
            system: "\\".repeat(MAX_MEASURED_PROMPT_BYTES / 2),
            situation: "\\".repeat(MAX_MEASURED_PROMPT_BYTES / 2),
        };
        let measured = serde_json::to_vec(&chat_request(
            "fast",
            &prompt,
            2_048,
            decision_schema().clone(),
        ))
        .unwrap()
        .len();
        let request_bound = usize::try_from(DEFAULT_MAX_REQUEST_BYTES).unwrap();

        assert!(
            measured <= request_bound * 3 / 4,
            "a {MAX_MEASURED_PROMPT_BYTES}-byte prompt with every byte escaped produces a \
             {measured}-byte provider request against the {request_bound}-byte bound"
        );
    }

    #[test]
    fn a_request_sets_no_sampling_knobs_and_no_superseded_token_field() {
        let prompt = prompt();
        let sent: Value =
            serde_json::to_value(chat_request("m", &prompt, 8, decision_schema().clone())).unwrap();
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
        assert_eq!(variants.len(), 13, "one per contract Intent variant");
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
            vec![
                "say", "moveTo", "flee", "eat", "dig", "collect", "place", "equip", "craft",
                "attack", "sleep", "rest", "idle",
            ],
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
            draft_from_answer(r#"{"intent":{"action":"flee","from":"e1"},"reasoning":"hurt"}"#)
                .unwrap();
        assert_eq!(
            draft.intent,
            Intent::Flee {
                from: "e1".to_owned()
            }
        );
        assert_eq!(draft.reasoning, "hurt");
    }

    fn goal_of(goal: &str) -> Result<Option<GoalChoice>, String> {
        draft_from_answer(&format!(
            r#"{{"intent":{{"action":"idle"}},"reasoning":"r","goal":{goal}}}"#
        ))
        .map(|draft| draft.goal)
    }

    #[test]
    fn every_disposition_parses_into_the_contract_choice_unchanged() {
        let choice =
            |disposition, statement: Option<&str>, step: Option<&str>, result| GoalChoice {
                disposition,
                statement: statement.map(str::to_owned),
                success_condition: statement.map(|_| "a roof is overhead".to_owned()),
                success_item: statement.map(|_| "item.minecraft.oak_log".to_owned()),
                success_count: statement.map(|_| 4),
                step: step.map(str::to_owned),
                step_action: step.map(|_| IntentAction::Dig),
                step_ref: step.map(|_| "b7".to_owned()),
                step_result: result,
            };
        let cases = [
            (
                r#"{"disposition":"adopt","statement":"find shelter","successCondition":"a roof is overhead","successItem":"item.minecraft.oak_log","successCount":4,"step":"walk to the cliff","stepAction":"dig","stepRef":"b7"}"#,
                choice(
                    GoalDisposition::Adopt,
                    Some("find shelter"),
                    Some("walk to the cliff"),
                    None,
                ),
            ),
            (
                r#"{"disposition":"continue","step":"dig into the cliff","stepAction":"dig","stepRef":"b7","stepResult":"done"}"#,
                choice(
                    GoalDisposition::Continue,
                    None,
                    Some("dig into the cliff"),
                    Some(GoalStepResult::Done),
                ),
            ),
            (
                r#"{"disposition":"revise","statement":"find shelter north","successCondition":"a roof is overhead","successItem":"item.minecraft.oak_log","successCount":4,"step":"walk north","stepAction":"dig","stepRef":"b7","stepResult":"noProgress"}"#,
                choice(
                    GoalDisposition::Revise,
                    Some("find shelter north"),
                    Some("walk north"),
                    Some(GoalStepResult::NoProgress),
                ),
            ),
            (
                r#"{"disposition":"complete","stepResult":"done"}"#,
                choice(
                    GoalDisposition::Complete,
                    None,
                    None,
                    Some(GoalStepResult::Done),
                ),
            ),
            (
                r#"{"disposition":"abandon","stepResult":"noProgress"}"#,
                choice(
                    GoalDisposition::Abandon,
                    None,
                    None,
                    Some(GoalStepResult::NoProgress),
                ),
            ),
        ];
        for (text, expected) in cases {
            assert_eq!(goal_of(text).unwrap(), Some(expected), "{text}");
        }
    }

    #[test]
    fn no_goal_choice_is_optional_and_keeps_idle_rational() {
        assert_eq!(goal_of("null").unwrap(), None);
        let absent =
            draft_from_answer(r#"{"intent":{"action":"idle"},"reasoning":"nothing to do"}"#)
                .unwrap();
        assert_eq!(absent.goal, None);
        assert_eq!(absent.intent, Intent::Idle);
    }

    #[test]
    fn each_disposition_requires_its_working_goal_fields_and_forbids_the_rest() {
        let target = r#""successItem":"item.minecraft.stick","successCount":1"#;
        let typed = r#""stepAction":"idle","stepRef":null"#;
        for (missing, field) in [
            (
                format!(
                    r#"{{"disposition":"adopt","successCondition":"c",{target},"step":"s",{typed}}}"#
                ),
                "statement",
            ),
            (
                format!(r#"{{"disposition":"adopt","statement":"g",{target},"step":"s",{typed}}}"#),
                "success condition",
            ),
            (
                format!(
                    r#"{{"disposition":"adopt","statement":"g","successCondition":"c","successCount":1,"step":"s",{typed}}}"#
                ),
                "success item",
            ),
            (
                format!(
                    r#"{{"disposition":"adopt","statement":"g","successCondition":"c","successItem":"item.minecraft.stick","step":"s",{typed}}}"#
                ),
                "success count",
            ),
            (
                format!(
                    r#"{{"disposition":"adopt","statement":"g","successCondition":"c",{target},{typed}}}"#
                ),
                "step",
            ),
            (
                format!(
                    r#"{{"disposition":"adopt","statement":"g","successCondition":"c",{target},"step":"s"}}"#
                ),
                "step action",
            ),
            (
                format!(r#"{{"disposition":"continue",{typed},"stepResult":"done"}}"#),
                "step",
            ),
            (
                format!(r#"{{"disposition":"continue","step":"s",{typed}}}"#),
                "step result",
            ),
            (
                format!(
                    r#"{{"disposition":"revise","statement":"g","successCondition":"c",{target},"step":"s",{typed}}}"#
                ),
                "step result",
            ),
            (r#"{"disposition":"complete"}"#.to_owned(), "step result"),
            (r#"{"disposition":"abandon"}"#.to_owned(), "step result"),
        ] {
            let reason = goal_of(&missing).expect_err(&missing);
            assert!(
                reason.contains(&format!("needs a {field}")),
                "{missing}: {reason}"
            );
        }
        for forbidden in [
            format!(
                r#"{{"disposition":"continue","step":"s",{typed},"stepResult":"done","statement":"sk-lookalike"}}"#
            ),
            format!(
                r#"{{"disposition":"continue","step":"s",{typed},"stepResult":"done","successItem":"item.minecraft.sk_lookalike"}}"#
            ),
            format!(
                r#"{{"disposition":"adopt","statement":"g","successCondition":"c",{target},"step":"s",{typed},"stepResult":"done"}}"#
            ),
            r#"{"disposition":"complete","stepResult":"done","step":"sk-lookalike"}"#.to_owned(),
            r#"{"disposition":"complete","stepResult":"done","stepRef":"b7"}"#.to_owned(),
        ] {
            let reason = goal_of(&forbidden).expect_err(&forbidden);
            assert!(reason.contains("forbids"), "{forbidden}: {reason}");
            assert!(!reason.contains("sk-lookalike"), "{reason}");
        }
    }

    #[test]
    fn a_malformed_goal_choice_is_a_bounded_malformed_answer() {
        // An over-long text is bounded rather than refused; see the test after this one.
        for bad in [
            r#"{"disposition":"adopt","statement":"go","priority":1}"#,
            r#"{"disposition":"pursue"}"#,
            r#"{"disposition":"adopt","statement":""}"#,
            r#"{"disposition":"adopt","statement":7}"#,
            r#""continue""#,
        ] {
            let reason = goal_of(bad).expect_err(bad);
            assert!(reason.contains("wrong shape"), "{bad}: {reason}");
        }
        let truncated =
            draft_from_answer(r#"{"intent":{"action":"idle"},"reasoning":"r","goal":{"disp"#)
                .expect_err("a cut-off goal is not a decision");
        assert!(truncated.contains("ended early"), "{truncated}");
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
    fn a_walk_names_a_block_ref_and_never_a_coordinate() {
        let reason = draft_from_answer(
            r#"{"intent":{"action":"moveTo","target":[1,2,3]},"reasoning":"go"}"#,
        )
        .expect_err("a walk names the block it ends on");
        assert!(reason.contains("wrong shape"), "got {reason}");
        let reason =
            draft_from_answer(r#"{"intent":{"action":"moveTo","onto":""},"reasoning":"go"}"#)
                .expect_err("a blank ref names nothing");
        assert!(reason.contains("wrong shape"), "got {reason}");
        let draft =
            draft_from_answer(r#"{"intent":{"action":"moveTo","onto":"b5"},"reasoning":"go"}"#)
                .unwrap();
        assert_eq!(
            draft.intent,
            Intent::MoveTo {
                onto: "b5".to_owned()
            }
        );
    }

    #[test]
    fn an_over_long_goal_text_is_bounded_rather_than_failing_the_decision() {
        // The strict provider schema cannot state a length, and the eighth live run lost a
        // whole run to one answer whose goal text was longer than the contract allows.
        let long = "w".repeat(MAX_GOAL_STATEMENT_CHARS * 2);
        let answer = format!(
            r#"{{"intent":{{"action":"idle"}},"reasoning":"r","goal":{{"disposition":"adopt","statement":"{long}","successCondition":"{long}","successItem":"item.minecraft.stick","successCount":1,"step":"{long}","stepAction":"idle","stepRef":null}}}}"#
        );
        let goal = draft_from_answer(&answer).unwrap().goal.unwrap();
        for text in [goal.statement, goal.success_condition, goal.step] {
            let text = text.unwrap();
            assert_eq!(text.chars().count(), MAX_GOAL_STATEMENT_CHARS);
            assert!(text.ends_with("..."));
        }
        // A blank goal text is still a malformed answer: there is nothing to keep.
        let blank = r#"{"intent":{"action":"idle"},"reasoning":"r","goal":{"disposition":"continue","step":"","stepAction":"idle","stepRef":null,"stepResult":"done"}}"#;
        assert!(draft_from_answer(blank).is_err());
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
    fn provider_usage_keeps_cached_input_separate() {
        let body = r#"{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":75}}}"#;
        let response: ChatResponse = serde_json::from_str(body).unwrap();
        let usage = response.usage.unwrap();
        assert_eq!(usage.prompt_tokens, 100);
        assert_eq!(usage.completion_tokens, 20);
        assert_eq!(usage.total_tokens, 120);
        assert_eq!(usage.prompt_tokens_details.cached_tokens, 75);
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
