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
    GoalChoice, GoalDisposition, Intent, Perception, MAX_GOAL_STATEMENT_CHARS, MAX_SUCCESS_COUNT,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::config::bounded_to;
use crate::model::Draft;
use crate::offered::{ItemSteps, OfferedRefs, Sheltering};
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
/// The working-goal shape: adopt, revise and replace set the goal's success condition, and every
/// disposition that answers a carried goal judges that goal's previous step. The step itself is
/// the decision's own intent, which the body words, so no answer states it.
const fn goal_fields(disposition: GoalDisposition) -> GoalFields {
    match disposition {
        GoalDisposition::Adopt => GoalFields {
            targeted: true,
            judged: false,
        },
        GoalDisposition::Revise | GoalDisposition::Replace => GoalFields {
            targeted: true,
            judged: true,
        },
        GoalDisposition::Continue | GoalDisposition::Complete | GoalDisposition::Abandon => {
            GoalFields {
                targeted: false,
                judged: true,
            }
        }
    }
}

#[derive(Clone, Copy)]
struct GoalFields {
    targeted: bool,
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
    // A replace or an abandon says why it ends the held goal, and no other disposition does.
    let ending = matches!(
        choice.disposition,
        GoalDisposition::Replace | GoalDisposition::Abandon
    );
    if ending && choice.reason.is_none() {
        return Err("the model's goal choice ended its goal without saying why".to_owned());
    }
    if !ending && choice.reason.is_some() {
        return Err("the model's goal choice gave a reason its disposition forbids".to_owned());
    }
    if choice.discover.is_some() || choice.shelter.is_some() {
        return checked_discovery(choice, fields);
    }
    if choice.feeds.is_some() && choice.disposition != GoalDisposition::Adopt {
        return Err(
            "the model's goal choice fed a shelter with a disposition other than adopt".to_owned(),
        );
    }
    let present = [
        (
            "success item",
            fields.targeted,
            choice.success_item.is_some(),
        ),
        (
            "success count",
            fields.targeted,
            choice.success_count.is_some(),
        ),
        ("step result", fields.judged, choice.step_result.is_some()),
    ];
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

/// Refuses a discovery or shelter choice that is not an adopt or replace, names a success item, or
/// judges the previous step where its disposition forbids it.
fn checked_discovery(choice: GoalChoice, fields: GoalFields) -> Result<Option<GoalChoice>, String> {
    if choice.discover.is_some() && choice.shelter.is_some() {
        return Err(
            "the model's goal choice set a discovery and a shelter goal at once".to_owned(),
        );
    }
    if !fields.targeted || choice.disposition == GoalDisposition::Revise || choice.feeds.is_some() {
        return Err(
            "the model's goal choice set a discovery goal its disposition cannot".to_owned(),
        );
    }
    if choice.success_item.is_some() || choice.success_count.is_some() {
        return Err("the model's goal choice gave a discovery goal a success item".to_owned());
    }
    if fields.judged != choice.step_result.is_some() {
        return Err("the model's goal choice judged a step its disposition does not".to_owned());
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
        .map(lifted_decision)
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

/// A goal review's coupled answer, with its `decision`'s intent and goal lifted beside the
/// reasoning, the shape every other answer already has.
fn lifted_decision(mut answer: Value) -> Value {
    if let Some(root) = answer.as_object_mut() {
        if let Some(Value::Object(decision)) = root.remove("decision") {
            for (key, value) in decision {
                root.entry(key).or_insert(value);
            }
        }
    }
    answer
}

/// The goal's free-text fields, bounded to the contract's length before they are typed.
///
/// Strict mode cannot state a string length, so a model that words a goal or its step at length writes a valid answer the contract would refuse - which
/// lost the eighth live run in one answer. The text is cut, the way reasoning is, rather than
/// the whole decision. Nothing else is touched: a blank field, a wrong type and every other
/// shape fault still fail as a malformed answer.
fn bounded_goal_texts(mut answer: Value) -> Value {
    if let Some(goal) = answer.get_mut("goal").and_then(Value::as_object_mut) {
        if let Some(Value::String(text)) = goal.get_mut("step") {
            if text.chars().count() > MAX_GOAL_STATEMENT_CHARS {
                let kept: String = text.chars().take(MAX_GOAL_STATEMENT_CHARS - 3).collect();
                *text = format!("{kept}...");
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

/// Whether the request schema `perception` produces offers the model any answer at all. A schema
/// with an empty answer union is one the provider refuses as an invalid request, so the stand-ins
/// a rehearsal runs ask this of every perception and fail the rehearsal rather than the paid run
/// (r25 stopped on a met goal's review whose schema offered nothing).
#[must_use]
pub fn request_offers_an_answer(perception: &Perception) -> bool {
    let schema = decision_schema_for(&OfferedRefs::of(perception), GoalPhase::of(perception));
    let answers = |field: &str| {
        schema["properties"][field]["anyOf"]
            .as_array()
            .is_some_and(|union| !union.is_empty())
    };
    answers("decision") || answers("intent")
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
#[derive(Clone, Copy, PartialEq, Eq)]
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
    HeldStack,
    Bed,
    Recipe,
}

impl Namespace {
    const ALL: [Self; 12] = [
        Self::Block,
        Self::DigBlock,
        Self::WalkTarget,
        Self::Creature,
        Self::StrikeTarget,
        Self::PursuitTarget,
        Self::PickupItem,
        Self::RouteItem,
        Self::Stack,
        Self::HeldStack,
        Self::Bed,
        Self::Recipe,
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
            Self::HeldStack => "heldStackRef",
            Self::Bed => "bedRef",
            Self::Recipe => "recipeRef",
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
            Self::HeldStack => "The ref of the inventory stack held in the main hand, offered in this message.",
            Self::Bed => "The ref of a bed in sight and within reach, offered in this message.",
            Self::Recipe => "A recipe ref offered in this message.",
        }
    }

    fn of(self, offered: &OfferedRefs) -> &[String] {
        match self {
            Self::Block => &offered.kept_blocks,
            Self::DigBlock => &offered.dig_blocks,
            Self::WalkTarget => &offered.walk_targets,
            Self::Creature => &offered.flee_targets,
            Self::StrikeTarget => &offered.strike_targets,
            Self::PursuitTarget => &offered.pursuit_targets,
            Self::PickupItem => &offered.pickup_items,
            Self::RouteItem => &offered.route_items,
            Self::Stack => &offered.stacks,
            Self::HeldStack => &offered.held_stacks,
            Self::Bed => &offered.beds,
            Self::Recipe => &offered.recipes,
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

/// The offered recipes grouped by how many applications each allows, fewest first.
fn recipe_groups<'a>(
    offered: &'a OfferedRefs,
    recipes: &'a [String],
) -> Vec<(i64, Vec<&'a String>)> {
    let mut groups: Vec<(i64, Vec<&String>)> = Vec::new();
    for recipe in recipes {
        let most = offered.crafts_of(recipe);
        match groups.iter_mut().find(|(group, _)| *group == most) {
            Some((_, members)) => members.push(recipe),
            None => groups.push((most, vec![recipe])),
        }
    }
    groups.sort_by_key(|(most, _)| *most);
    groups
}

/// The name of the `$defs` enum of the offered recipes that allow exactly `most` applications.
fn recipe_def(most: i64) -> String {
    format!("recipeOf{most}Ref")
}

/// The applications a craft may ask, from one to `most`.
fn crafts_field(most: i64) -> Option<Value> {
    plain(json!({
        "type": "integer",
        "minimum": 1,
        "maximum": most,
        "description": "How many bounded applications of that offered recipe to request; no more than it allows."
    }))
}

/// The `craft` variants a request offers: in the reference shape one with any recipe and up to the
/// contract's bound, and otherwise one per group of recipes that allow the same applications, so a
/// craft can never ask more than its recipe makes (r35 alpha's `craftQuantityExceeded`).
fn craft_variants(offered: Option<&OfferedRefs>) -> Vec<Option<Value>> {
    let Some(refs) = offered else {
        return vec![intent_variant(
            "craft",
            vec![
                (
                    "recipe",
                    ref_field(
                        "The ref of the exact offered recipe to apply.",
                        Namespace::Recipe,
                        None,
                    ),
                ),
                ("crafts", crafts_field(loom_contract::MAX_CRAFTS_PER_INTENT)),
            ],
        )];
    };
    recipe_groups(refs, &refs.recipes)
        .into_iter()
        .map(|(most, _)| {
            intent_variant(
                "craft",
                vec![
                    (
                        "recipe",
                        Some(json!({
                            "description": "The ref of the exact offered recipe to apply.",
                            "anyOf": [{ "$ref": format!("#/$defs/{}", recipe_def(most)) }],
                        })),
                    ),
                    ("crafts", crafts_field(most)),
                ],
            )
        })
        .collect()
}

/// The `$defs` a request carries: one closed string enum per namespace it offers, the recipes
/// split by the applications each allows.
fn namespace_defs(offered: &OfferedRefs) -> Value {
    let mut defs = serde_json::Map::new();
    for (most, members) in recipe_groups(offered, &offered.recipes) {
        defs.insert(
            recipe_def(most),
            json!({
                "type": "string",
                "enum": members,
                "description": Namespace::Recipe.description(),
            }),
        );
    }
    for namespace in Namespace::ALL {
        let handles = namespace.of(offered);
        if namespace != Namespace::Recipe && !handles.is_empty() {
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

/// A dig sequence's `blocks`: an array of `block`, bounded by the contract type since strict mode cannot.
fn dig_sequence(block: &Value) -> Value {
    json!({
        "type": "array",
        "items": block,
        "description": "One to five distinct block refs to dig in order, in one decision; the body stops at the first it cannot dig. More than five is refused."
    })
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

/// One goal disposition, as closed objects carrying exactly the fields it requires.
///
/// A disposition that sets a success condition is one shape per item a request may name, each
/// bounding its count to what that item admits now: one more than is held, and no more than is
/// held plus what is in sight could give. The reference shape has one, with the contract's own
/// bounds. A request that offers no success item offers no such disposition.
fn goal_variants(
    disposition: GoalDisposition,
    name: &str,
    description: &str,
    offered: Option<&OfferedRefs>,
) -> Vec<Value> {
    let fields = goal_fields(disposition);
    let targets: Vec<(Value, i64, i64)> = if !fields.targeted {
        vec![(Value::Null, 0, 0)]
    } else if let Some(refs) = offered {
        refs.count_bounds
            .iter()
            .map(|(item, fewest, most)| {
                (json!({ "type": "string", "enum": [item] }), *fewest, *most)
            })
            .collect()
    } else {
        vec![(json!({ "type": "string" }), 1, MAX_SUCCESS_COUNT)]
    };
    targets
        .into_iter()
        .map(|(item, fewest, most)| goal_variant(fields, name, description, item, fewest, most))
        .collect()
}

/// One closed goal shape; `item` is the success item's schema when `fields` words a goal.
fn goal_variant(
    fields: GoalFields,
    name: &str,
    description: &str,
    item: Value,
    fewest: i64,
    most: i64,
) -> Value {
    let mut properties = serde_json::Map::new();
    properties.insert(
        "disposition".to_owned(),
        json!({ "type": "string", "enum": [name], "description": description }),
    );
    if fields.targeted {
        let mut item = item;
        if let Some(schema) = item.as_object_mut() {
            schema.insert(
                "description".to_owned(),
                json!(
                    "The item to hold, named as listed; your body measures it from your inventory."
                ),
            );
        }
        properties.insert("successItem".to_owned(), item);
        properties.insert(
            "successCount".to_owned(),
            json!({
                "type": "integer",
                "minimum": fewest,
                "maximum": most,
                "description": "The total of it to hold, counting what you hold now.",
            }),
        );
    }
    if fields.judged {
        properties.insert(
            "stepResult".to_owned(),
            json!({
                "type": "string",
                "enum": ["done", "progressing", "noProgress"],
                "description": "Your verdict on the previous step, from its progress evidence: done finished it, progressing moved it forward, noProgress did not.",
            }),
        );
    }
    let required: Vec<Value> = properties.keys().map(|key| json!(key)).collect();
    json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    })
}

/// The reasons a replace or an abandon may give, each as the body judges it.
const COMPLETED: &str = "completed";
const IMPOSSIBLE: &str = "impossible";
const UNSAFE: &str = "unsafe";
const STALE: &str = "stale";
const SUPERSEDED: &str = "superseded";

/// What one reason means, as the shape that offers it states it.
fn reason_meaning(reason: &str) -> &'static str {
    match reason {
        COMPLETED => "completed, its condition is met",
        IMPOSSIBLE => "impossible, nothing you hold or could get now meets it",
        UNSAFE => "unsafe, night nears and a shelter takes its place",
        STALE => "stale, its last three steps advanced nothing",
        _ => "superseded, you choose a different objective, for the reason your reasoning gives",
    }
}

/// Why the held goal ends, worded from only the `reasons` a shape offers: each reason is stated
/// where it may be given and nowhere else, which also keeps the widest request inside its bound
/// (the full list on every replace cost the widest r39 review 2,691 bytes).
fn reason_description(reasons: &[&str]) -> String {
    let meanings: Vec<&str> = reasons
        .iter()
        .map(|reason| reason_meaning(reason))
        .collect();
    format!("Why the held goal ends: {}.", meanings.join("; "))
}

/// `variants`, each a replace or abandon shape, required to give one of `reasons`.
fn reasoned(variants: Vec<Value>, reasons: &[&str]) -> Vec<Value> {
    let description = reason_description(reasons);
    variants
        .into_iter()
        .map(|mut variant| {
            if let Some(properties) = variant["properties"].as_object_mut() {
                properties.insert(
                    "reason".to_owned(),
                    json!({ "type": "string", "enum": reasons, "description": description }),
                );
            }
            if let Some(required) = variant["required"].as_array_mut() {
                required.push(json!("reason"));
            }
            variant
        })
        .collect()
}

/// The discovery goal shape of an adopt or a replace: no success item, met when a walk to the edge
/// of sight reveals a useful resource no footing gives (r23: 17 of 70 reviews could set no goal).
fn discovery_variant(disposition: GoalDisposition, name: &str) -> Value {
    let fields = goal_fields(disposition);
    let mut properties = serde_json::Map::new();
    properties.insert(
        "disposition".to_owned(),
        json!({ "type": "string", "enum": [name], "description": DISCOVER }),
    );
    properties.insert(
        "discover".to_owned(),
        json!({
            "type": "boolean",
            "enum": [true],
            "description": "A discovery goal: find a useful resource beyond the edge of sight. Your body measures it as met when a walk reveals one; then replace it with a goal for that resource.",
        }),
    );
    if fields.judged {
        properties.insert(
            "stepResult".to_owned(),
            json!({
                "type": "string",
                "enum": ["done", "progressing", "noProgress"],
                "description": "Your verdict on the previous step, from its progress evidence: done finished it, progressing moved it forward, noProgress did not.",
            }),
        );
    }
    let required: Vec<Value> = properties.keys().map(|key| json!(key)).collect();
    json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    })
}

/// The shelter goal shape of an adopt or a replace: no success item, met when the server's ground
/// seals the space the agent stands in (r36: all ten agents died at night in the open).
fn shelter_variant(disposition: GoalDisposition, name: &str) -> Value {
    let fields = goal_fields(disposition);
    let mut properties = serde_json::Map::new();
    properties.insert(
        "disposition".to_owned(),
        json!({ "type": "string", "enum": [name], "description": SHELTER }),
    );
    properties.insert(
        "shelter".to_owned(),
        json!({
            "type": "boolean",
            "enum": [true],
            "description": "A shelter goal: seal the space you stand in against the night. Your body measures it from the ground around you, met once nothing can reach you; then complete it and stay.",
        }),
    );
    if fields.judged {
        properties.insert("stepResult".to_owned(), step_result_field());
    }
    let required: Vec<Value> = properties.keys().map(|key| json!(key)).collect();
    json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    })
}

/// The previous step's verdict field, which every disposition that answers a carried goal states.
fn step_result_field() -> Value {
    json!({
        "type": "string",
        "enum": ["done", "progressing", "noProgress"],
        "description": "Your verdict on the previous step, from its progress evidence: done finished it, progressing moved it forward, noProgress did not.",
    })
}

/// Where one perception stands with its goal, which decides the goal choices it can make.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalPhase {
    /// The reference shape: every choice is offered.
    Any,
    /// Not a goal review: the ledger reads no goal choice outside a review, so only `null` is
    /// offered (r22: three event answers adopted goals that were dropped as `goalNotReviewed`,
    /// theta's oak log the run's only log goal among them).
    Event,
    /// A goal review with no goal held: adopt one, or make no choice.
    Unheld,
    /// A goal review whose held goal's success condition is not met.
    Held,
    /// A goal review whose held goal's success condition is met: complete it or revise it.
    Met,
    /// A goal review whose held, unmet goal reached the stall bound: replace it, in one answer.
    Stalled,
}

impl GoalPhase {
    /// The phase `perception` is in.
    #[must_use]
    pub fn of(perception: &Perception) -> Self {
        if !perception.trigger.reviews_goal() {
            return Self::Event;
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
            Some(goal)
                if goal.progress.as_ref().is_some_and(|seen| {
                    seen.steps_without_progress >= loom_contract::MAX_GOAL_STEPS_WITHOUT_PROGRESS
                }) =>
            {
                Self::Stalled
            }
            Some(_) => Self::Held,
        }
    }

    /// Whether a goal review in this phase couples its goal choice to its intent.
    const fn coupled(self) -> bool {
        !matches!(self, Self::Any | Self::Event)
    }
}

/// The goal choices one phase offers, split by what each may travel with: for each goal item,
/// the choices that take one of that item's own steps as the goal's step; the choices that take
/// a walk to the edge of sight as it; and the rest, which travel with any intent.
struct GoalOffer {
    by_item: Vec<(String, Vec<Value>)>,
    exploring: Vec<Value>,
    /// The choices that take one of the cover reading's shelter steps as the goal's step.
    sheltering: Vec<Value>,
    any: Vec<Value>,
}

impl GoalOffer {
    /// Every choice, for a schema that couples none of them to an intent.
    fn all(self) -> Vec<Value> {
        let mut choices: Vec<Value> = self
            .by_item
            .into_iter()
            .flat_map(|(_, choices)| choices)
            .collect();
        choices.extend(self.exploring);
        choices.extend(self.sheltering);
        choices.extend(self.any);
        choices
    }
}

const ADOPT: &str = "Take up a new goal when you hold none.";
const CONTINUE: &str = "Keep the held goal and take its next step.";
const REVISE: &str = "Keep pursuing the held goal's item with a new total and step, under the same goal: more of the same item is always a revise, never a new goal; when its condition is met, a revise must name a total you do not yet hold.";
const DISCOVER: &str = "Set a discovery goal when nothing you see, remember or hold grounds an item goal: walk to the edge of sight until a useful resource comes into view.";
const REPLACE: &str = "End the held goal and take up a different one in this same answer, whose step is this answer's action, for the reason given.";
const SHELTER: &str = "Set a shelter goal as night nears: seal the space you stand in by the steps your cover offers - dig down, close an open face with a solid block you hold, or walk onto more enclosed ground.";

/// The goal choices one phase offers: `null`, or one closed disposition shape the body would
/// accept now.
///
/// Strict mode requires the key, so "no choice" is an explicit `null` rather than an absent
/// field, offered only while no goal is held. At a review the offer narrows to what the ledger
/// accepts: a met success condition can only be completed, replaced by the next goal or revised,
/// an unmet one is never completed, a goal sight could no longer meet is continued only by a walk
/// to look further, a revise never changes the item, and a goal at the stall bound may only be
/// replaced by a goal for another item in the same answer (r16: four answers past the bound
/// continued or revised; r18: three replaces relabelled the stalled goal at one more).
fn goal_offer(phase: GoalPhase, offered: Option<&OfferedRefs>) -> GoalOffer {
    let variants =
        |disposition, name, description| goal_variants(disposition, name, description, offered);
    let complete = || {
        variants(
            GoalDisposition::Complete,
            "complete",
            "The held goal's success condition is met; drop it.",
        )
    };
    let abandon = |reasons: &[&str]| {
        reasoned(
            variants(
                GoalDisposition::Abandon,
                "abandon",
                "End the held goal with nothing to take up in its place, for the reason given.",
            ),
            reasons,
        )
    };
    let continued = || variants(GoalDisposition::Continue, "continue", CONTINUE);
    let none = || vec![json!({ "type": "null" })];
    if phase == GoalPhase::Event {
        return GoalOffer {
            by_item: Vec::new(),
            exploring: Vec::new(),
            sheltering: Vec::new(),
            any: none(),
        };
    }
    let Some(refs) = offered.filter(|_| phase.coupled()) else {
        let every = [COMPLETED, IMPOSSIBLE, UNSAFE, STALE, SUPERSEDED];
        let by_item = vec![(
            String::new(),
            [
                variants(GoalDisposition::Adopt, "adopt", ADOPT),
                continued(),
                variants(GoalDisposition::Revise, "revise", REVISE),
                reasoned(
                    variants(GoalDisposition::Replace, "replace", REPLACE),
                    &every,
                ),
            ]
            .concat(),
        )];
        let discovery = [
            vec![discovery_variant(GoalDisposition::Adopt, "adopt")],
            reasoned(
                vec![discovery_variant(GoalDisposition::Replace, "replace")],
                &every,
            ),
        ]
        .concat();
        let shelter = [
            vec![shelter_variant(GoalDisposition::Adopt, "adopt")],
            reasoned(
                vec![shelter_variant(GoalDisposition::Replace, "replace")],
                &every,
            ),
        ]
        .concat();
        return GoalOffer {
            by_item,
            exploring: Vec::new(),
            sheltering: shelter,
            any: [
                none(),
                complete(),
                abandon(&[COMPLETED, IMPOSSIBLE, STALE]),
                discovery,
            ]
            .concat(),
        };
    };
    coupled_offer(phase, refs, continued, complete, abandon, none)
}

/// [`goal_offer`] for a goal review, whose choices narrow to what the ledger accepts.
/// The revise shapes of the held goal's item: the ranges either side of its held total, never
/// the total itself (r19: 28 of 31 revises set the total they were shown, and read as activity),
/// and the range above it only when more of the item serves a chain the perception evidences
/// (`held_serves_chain`; R41: met dirt goals revised upward at night instead of completing).
fn revise_choices(refs: &OfferedRefs, item: &str) -> Vec<Value> {
    let total = refs.held_total.unwrap_or(0);
    let Some((fewest, most)) = refs.count_bounds_of(item) else {
        return Vec::new();
    };
    let upward = if refs.held_serves_chain { most } else { total };
    [
        (fewest, most.min(total - 1)),
        (fewest.max(total + 1), upward),
    ]
    .into_iter()
    .filter(|(low, high)| low <= high)
    .map(|(low, high)| {
        goal_variant(
            goal_fields(GoalDisposition::Revise),
            "revise",
            REVISE,
            json!({ "type": "string", "enum": [item] }),
            low,
            high,
        )
    })
    .collect()
}

/// Whether any of `by_item`'s choices, or a walk to look further, can be paired with a step: an
/// item with no step is no answer at all, and the schema must always offer one (r25 stopped on the
/// provider refusing a met goal's review whose every item had no step).
fn pairable(refs: &OfferedRefs, by_item: &[(String, Vec<Value>)], exploring: &[Value]) -> bool {
    by_item.iter().any(|(item, choices)| {
        !choices.is_empty()
            && refs
                .item_steps
                .iter()
                .any(|steps| &steps.item == item && !steps.is_empty())
    }) || (!exploring.is_empty() && !refs.explore_targets.is_empty())
}

fn coupled_offer(
    phase: GoalPhase,
    refs: &OfferedRefs,
    continued: impl Fn() -> Vec<Value>,
    complete: impl Fn() -> Vec<Value>,
    abandon: impl Fn(&[&str]) -> Vec<Value>,
    none: impl Fn() -> Vec<Value>,
) -> GoalOffer {
    if let Some(offer) = shelter_offer(phase, refs, &continued, &complete, &abandon) {
        return offer;
    }
    match phase {
        // The reference shape and an event are never coupled, so never reach here.
        GoalPhase::Any | GoalPhase::Event => GoalOffer {
            by_item: Vec::new(),
            exploring: Vec::new(),
            sheltering: Vec::new(),
            any: Vec::new(),
        },
        // With no item goal grounded, a review sets a discovery goal and walks to look further,
        // rather than act with no goal at all (r23: 17 of 70 reviews held none).
        GoalPhase::Unheld if refs.discovery_offered() => GoalOffer {
            by_item: each_item_choices(refs, GoalDisposition::Adopt, "adopt", ADOPT),
            exploring: vec![discovery_variant(GoalDisposition::Adopt, "adopt")],
            sheltering: shelter_adopt(refs),
            any: Vec::new(),
        },
        GoalPhase::Unheld => GoalOffer {
            by_item: each_item_choices(refs, GoalDisposition::Adopt, "adopt", ADOPT),
            exploring: Vec::new(),
            sheltering: shelter_adopt(refs),
            any: none(),
        },
        GoalPhase::Held => held_offer(refs, &continued, &abandon),
        GoalPhase::Met => met_offer(refs, &complete, &abandon),
        // With nothing in sight to give a goal for another item, the stalled one can only go.
        // Nor can a stalled item goal with no item grounded look further but by a discovery goal.
        GoalPhase::Stalled => {
            let by_item = replace_other(refs, &[STALE]);
            let exploring = discovery_replace(refs, &[STALE]);
            let sheltering = shelter_replace(refs, &[STALE, UNSAFE]);
            let any = if !pairable(refs, &by_item, &exploring) && sheltering.is_empty() {
                abandon(&[STALE])
            } else {
                Vec::new()
            };
            GoalOffer {
                by_item,
                exploring,
                sheltering,
                any,
            }
        }
    }
}

/// Every offered item's replace shapes but the held goal's own, each stating one of `reasons`:
/// more of the held item is a revise, which keeps the goal (r39: alpha replaced "hold 9 dirt" by
/// "hold 10 dirt" at every step, and no held time counted).
fn replace_other(refs: &OfferedRefs, reasons: &[&str]) -> Vec<(String, Vec<Value>)> {
    each_item_choices(refs, GoalDisposition::Replace, "replace", REPLACE)
        .into_iter()
        .filter(|(item, _)| refs.held_item.as_ref() != Some(item))
        .map(|(item, choices)| (item, reasoned(choices, reasons)))
        .collect()
}

/// A shelter goal set in place of any other, stating one of `reasons`, while night nears and the
/// cover offers a step.
fn shelter_replace(refs: &OfferedRefs, reasons: &[&str]) -> Vec<Value> {
    if refs.shelter.offered() && refs.sheltering == Sheltering::Unheld {
        reasoned(
            vec![shelter_variant(GoalDisposition::Replace, "replace")],
            reasons,
        )
    } else {
        Vec::new()
    }
}

/// The choices of a review holding an unmet goal short of its stall bound. It must be answered: a
/// decision that acts while ignoring its goal is refused, so null is not offered (r15 kappa). It is
/// stepped toward its own item whether or not sight can meet all of it; one sight can meet is not
/// abandoned (r17 theta), and one it cannot may instead look further, or be abandoned as
/// impossible (r18: five goals abandoned to look). A switch to another objective is superseded,
/// and a shelter as night nears unsafe.
fn held_offer(
    refs: &OfferedRefs,
    continued: &impl Fn() -> Vec<Value>,
    abandon: &impl Fn(&[&str]) -> Vec<Value>,
) -> GoalOffer {
    let mut by_item = continue_first(
        revise_held_choices(refs),
        refs.held_item.as_ref(),
        continued(),
    );
    // A switch into a recipe made from what is already held, before the stall bound.
    for (item, ..) in &refs.switch_bounds {
        by_item.push((
            item.clone(),
            reasoned(
                shaped_choices(refs, GoalDisposition::Replace, "replace", REPLACE, item),
                &[SUPERSEDED],
            ),
        ));
    }
    // A discovery goal with somewhere to look is walked until its stall bound, never abandoned
    // first (r24: six of nine were abandoned after one or two walks).
    let looking = refs.discovering() && refs.explore_continue();
    let exploring = if refs.explore_continue() {
        continued()
    } else {
        Vec::new()
    };
    let sheltering = shelter_replace(refs, &[UNSAFE]);
    let short = (!refs.goal_reachable() && !looking)
        || (!pairable(refs, &by_item, &exploring) && sheltering.is_empty());
    GoalOffer {
        by_item,
        exploring,
        sheltering,
        any: if short {
            abandon(&[IMPOSSIBLE])
        } else {
            Vec::new()
        },
    }
}

/// The choices of a review whose goal is met: completed, or completed and the next adopted in one
/// answer by a replace, or revised to a total not yet held (r17 and r18: every completion idled).
/// A met discovery goal becomes a goal for what it revealed, and a met item goal with no next item
/// in sight a discovery goal; a plain complete only when neither can be set (r19: eight plain
/// completes, two beside a filler equip).
fn met_offer(
    refs: &OfferedRefs,
    complete: &impl Fn() -> Vec<Value>,
    abandon: &impl Fn(&[&str]) -> Vec<Value>,
) -> GoalOffer {
    let mut by_item = replace_other(refs, &[COMPLETED]);
    for (item, choices) in revise_held_choices(refs) {
        match by_item.iter_mut().find(|(name, _)| *name == item) {
            Some((_, existing)) => existing.extend(choices),
            None => by_item.push((item, choices)),
        }
    }
    let exploring = discovery_replace(refs, &[COMPLETED]);
    let sheltering = shelter_replace(refs, &[COMPLETED]);
    let unpaired = !pairable(refs, &by_item, &exploring) && sheltering.is_empty();
    let any = match (unpaired, refs.discovering()) {
        (false, _) => Vec::new(),
        (true, false) => complete(),
        (true, true) => abandon(&[COMPLETED]),
    };
    GoalOffer {
        by_item,
        exploring,
        sheltering,
        any,
    }
}

/// One item's targeted shapes of `disposition`, when a count for that item is offered.
fn shaped_choices(
    refs: &OfferedRefs,
    disposition: GoalDisposition,
    name: &str,
    description: &str,
    item: &str,
) -> Vec<Value> {
    refs.count_bounds_of(item)
        .map(|(fewest, most)| {
            goal_variant(
                goal_fields(disposition),
                name,
                description,
                json!({ "type": "string", "enum": [item] }),
                fewest,
                most,
            )
        })
        .into_iter()
        .collect()
}

/// Every offered item's targeted shapes of `disposition`, each beside its item.
fn each_item_choices(
    refs: &OfferedRefs,
    disposition: GoalDisposition,
    name: &str,
    description: &str,
) -> Vec<(String, Vec<Value>)> {
    refs.count_bounds
        .iter()
        .map(|(item, ..)| {
            (
                item.clone(),
                shaped_choices(refs, disposition, name, description, item),
            )
        })
        .filter(|(_, choices)| !choices.is_empty())
        .collect()
}

/// The revise shapes of the held goal's item, beside it.
fn revise_held_choices(refs: &OfferedRefs) -> Vec<(String, Vec<Value>)> {
    refs.held_item
        .iter()
        .map(|item| (item.clone(), revise_choices(refs, item)))
        .filter(|(_, choices)| !choices.is_empty())
        .collect()
}

/// The goal choices of a review holding a shelter goal, or a resource subgoal an older routine
/// adopted, or `None` for any other: a shelter goal is stepped by what its cover offers - gathering
/// a closer among them - completed once sealed, and let go only when no step can close it; a
/// subgoal is pursued like any goal of its item but never replaced, since a replace would drop the
/// shelter it feeds.
fn shelter_offer(
    phase: GoalPhase,
    refs: &OfferedRefs,
    continued: &impl Fn() -> Vec<Value>,
    complete: &impl Fn() -> Vec<Value>,
    abandon: &impl Fn(&[&str]) -> Vec<Value>,
) -> Option<GoalOffer> {
    let held = refs.held_item.clone();
    let offer = match (phase, refs.sheltering) {
        // A held shelter goal is stepped by what its cover reading offers, or let go as impossible
        // only when nothing can close it (r39: gamma abandoned three shelter goals while taking
        // their own offered step, the last of them its seal).
        (GoalPhase::Held, Sheltering::Held) => {
            let stepped = refs.shelter.stepped();
            GoalOffer {
                by_item: Vec::new(),
                exploring: Vec::new(),
                sheltering: if stepped { continued() } else { Vec::new() },
                any: if stepped {
                    Vec::new()
                } else {
                    abandon(&[IMPOSSIBLE])
                },
            }
        }
        // A sealed shelter is completed to be stayed in, or replaced by a goal sight gives.
        (GoalPhase::Met, Sheltering::Held) => GoalOffer {
            by_item: each_item_choices(refs, GoalDisposition::Replace, "replace", REPLACE)
                .into_iter()
                .map(|(item, choices)| (item, reasoned(choices, &[COMPLETED])))
                .collect(),
            exploring: Vec::new(),
            sheltering: Vec::new(),
            any: complete(),
        },
        // A resource subgoal is pursued like any goal of its item, never replaced, since a replace
        // would drop the shelter it feeds; let go, it hands the shelter back.
        (GoalPhase::Held, Sheltering::Feeding) => GoalOffer {
            by_item: continue_first(revise_held_choices(refs), held.as_ref(), continued()),
            exploring: Vec::new(),
            sheltering: Vec::new(),
            any: abandon(&[IMPOSSIBLE]),
        },
        // A met subgoal is completed, and the shelter it fed takes this answer's step.
        (GoalPhase::Met, Sheltering::Feeding) => GoalOffer {
            by_item: Vec::new(),
            exploring: Vec::new(),
            sheltering: if refs.shelter.stepped() {
                complete()
            } else {
                Vec::new()
            },
            any: if refs.shelter.stepped() {
                Vec::new()
            } else {
                complete()
            },
        },
        (GoalPhase::Stalled, Sheltering::Feeding) => GoalOffer {
            by_item: Vec::new(),
            exploring: Vec::new(),
            sheltering: Vec::new(),
            any: abandon(&[STALE]),
        },
        _ => return None,
    };
    Some(offer)
}

/// The adopt of a shelter goal, offered while night nears and the cover offers a step.
fn shelter_adopt(refs: &OfferedRefs) -> Vec<Value> {
    if refs.shelter.offered() {
        vec![shelter_variant(GoalDisposition::Adopt, "adopt")]
    } else {
        Vec::new()
    }
}

/// The replace into a discovery goal, offered when nothing grounds an item goal, for `reasons`.
fn discovery_replace(refs: &OfferedRefs, reasons: &[&str]) -> Vec<Value> {
    if refs.discovery_offered() {
        reasoned(
            vec![discovery_variant(GoalDisposition::Replace, "replace")],
            reasons,
        )
    } else {
        Vec::new()
    }
}

/// `by_item` with `continuing` offered first beside the held goal's own `item`'s steps.
fn continue_first(
    mut by_item: Vec<(String, Vec<Value>)>,
    item: Option<&String>,
    mut continuing: Vec<Value>,
) -> Vec<(String, Vec<Value>)> {
    let Some(item) = item else {
        return by_item;
    };
    match by_item.iter_mut().find(|(name, _)| name == item) {
        Some((_, choices)) => {
            continuing.append(choices);
            *choices = continuing;
        }
        None => by_item.insert(0, (item.clone(), continuing)),
    }
    by_item
}

/// The goal choice field over `choices`.
fn goal_field(choices: &[Value]) -> Value {
    json!({
        "description": "Your choice about your one short-term goal, only at a goal review; null makes no choice, and is offered only when you hold no goal.",
        "anyOf": choices,
    })
}

/// Whether an intent variant names `action`.
fn names_action(variant: &Value, actions: &[&str]) -> bool {
    variant["properties"]["action"]["enum"][0]
        .as_str()
        .is_some_and(|action| actions.contains(&action))
}

/// The intents that take no step, which a goal cannot be pursued by.
const PASSIVE_ACTIONS: [&str; 2] = ["rest", "idle"];

/// The `place` and `equip` variants: the two actions that name a held stack beside where it goes.
fn handling_variants(offered: Option<&OfferedRefs>) -> [Option<Value>; 2] {
    let blocks = |description: &str| {
        any_ref_field(
            description,
            &[Namespace::DigBlock, Namespace::Block],
            offered,
        )
    };
    let stacks = |description: &str| ref_field(description, Namespace::Stack, offered);
    [
        intent_variant(
            "place",
            vec![
                (
                    "block",
                    blocks("The ref of the exact sensed support block."),
                ),
                (
                    "item",
                    ref_field(
                        "The ref of the offered inventory stack already held in the main hand.",
                        Namespace::HeldStack,
                        offered,
                    ),
                ),
                (
                    "face",
                    plain(json!({
                        "type": "string",
                        "enum": ["down", "up", "north", "south", "west", "east"],
                        "description": "The support-block face against which to place once."
                    })),
                ),
            ],
        ),
        intent_variant(
            "equip",
            vec![
                (
                    "item",
                    stacks("The ref of the exact offered inventory stack to equip."),
                ),
                (
                    "destination",
                    plain(json!({
                        "type": "string",
                        "enum": ["hand", "offHand", "head", "torso", "legs", "feet"],
                        "description": "The bounded player equipment destination."
                    })),
                ),
            ],
        ),
    ]
}

fn build_decision_schema(offered: Option<&OfferedRefs>, phase: GoalPhase) -> Value {
    let dig_blocks = |description: &str| ref_field(description, Namespace::DigBlock, offered);
    let walk_targets = |description: &str| ref_field(description, Namespace::WalkTarget, offered);
    let creatures = |description: &str| ref_field(description, Namespace::Creature, offered);
    let stacks = |description: &str| ref_field(description, Namespace::Stack, offered);
    let mut variants: Vec<Option<Value>> = vec![
        intent_variant("say", vec![("text", plain(json!({
            "type": "string",
            "description": "One line of player chat heard by agents nearby: at most 128 characters and 256 UTF-8 bytes, words separated by single spaces, no leading or trailing space, no leading '/', and no control, formatting or bidirectional characters. Text outside these bounds is refused and never sent."
        })))]),
        intent_variant("moveTo", vec![("onto", walk_targets("The ref of the one nearby block to walk to: the body stands on top of a solid block, or in the own space of a block it can walk through such as a dead bush or grass. A block ref from this message outside your own spot, never coordinates or an invented one."))]),
        intent_variant("flee", vec![("from", creatures("The ref of the one living thing to move away from. A creature ref given in this message, never a name, a type, or an invented one."))]),
        intent_variant("eat", vec![("ref", stacks("The ref of the one stack in your own inventory to eat one item from. A stack ref given in this message, never an item name or an invented one."))]),
        intent_variant("dig", vec![("block", dig_blocks("The ref of the exact nearby block to dig once. A block ref from this message, never coordinates or an item name."))]),
        // A request offers a sequence only as a goal's own step, so the widest request stays inside the provider's enum limit.
        offered.map_or_else(|| Some(dig_sequence(&json!({ "type": "string" }))), |_| None).and_then(|blocks| intent_variant("digSequence", vec![("blocks", Some(blocks))])),
    ];
    variants.extend(collect_variants(offered));
    variants.extend(handling_variants(offered));
    variants.extend(craft_variants(offered));
    variants.extend(attack_variants(offered));
    variants.extend([
        intent_variant(
            "sleep",
            vec![(
                "bed",
                ref_field(
                    "The ref of the exact offered bed to use once.",
                    Namespace::Bed,
                    offered,
                ),
            )],
        ),
        intent_variant("rest", vec![]),
        intent_variant("idle", vec![]),
    ]);
    let variants: Vec<Value> = variants.into_iter().flatten().collect();
    let reasoning = json!({
        "type": "string",
        "description": "One or two sentences on why this action, recorded permanently.",
    });
    let offer = goal_offer(phase, offered);
    let mut defs = offered.map_or_else(|| json!({}), namespace_defs);
    let immediate = offered.filter(|refs| refs.in_immediate_danger() && phase != GoalPhase::Any);
    let mut schema = if let Some(refs) = immediate {
        survival_schema(refs, &reasoning, &mut defs)
    } else if let Some(refs) = offered.filter(|_| phase.coupled()) {
        coupled_schema(variants, &offer, refs, &reasoning, &mut defs)
    } else {
        let choices = offer.all();
        json!({
            "type": "object",
            "properties": {
                "intent": { "description": INTENT_DESCRIPTION, "anyOf": variants },
                "reasoning": reasoning,
                "goal": goal_field(&choices),
            },
            "required": ["intent", "reasoning", "goal"],
            "additionalProperties": false,
        })
    };
    if let (Some(root), Some(defs)) = (schema.as_object_mut(), defs.as_object()) {
        if offered.is_some() || !defs.is_empty() {
            root.insert("$defs".to_owned(), Value::Object(defs.clone()));
        }
    }
    schema
}

const INTENT_DESCRIPTION: &str = "The single action the body should take now.";

/// The answer while a hunter is immediate danger: a survival response alone, with no goal choice,
/// in a goal review and an event alike (r24: seven reviews beside a near hunter dug, crafted or
/// walked, delta's beside a spider 6.8 blocks off). The goal, if any, is deferred, not answered.
fn survival_schema(offered: &OfferedRefs, reasoning: &Value, defs: &mut Value) -> Value {
    let intents = defs
        .as_object_mut()
        .and_then(|table| survival_intents(offered, table))
        .unwrap_or(Value::Array(Vec::new()));
    json!({
        "type": "object",
        "properties": {
            "decision": {
                "description": "The survival response the body should take now; a hunter is upon you.",
                "anyOf": [{
                    "type": "object",
                    "properties": {
                        "intent": { "description": INTENT_DESCRIPTION, "anyOf": intents },
                        "goal": goal_field(&[json!({ "type": "null" })]),
                    },
                    "required": ["intent", "goal"],
                    "additionalProperties": false,
                }],
            },
            "reasoning": reasoning,
        },
        "required": ["decision", "reasoning"],
        "additionalProperties": false,
    })
}

/// A goal review's answer: one `decision` that pairs an intent with a goal choice it may travel
/// with. A choice that takes the intent as the goal's step is offered only beside one of its own
/// item's steps - a dig of a block in reach that can drop it, a collect of a drop of it, a craft of
/// it, or a walk onto a block out of dig reach that can drop it, and a collect alone while the
/// agent's own drop of the goal's item lies in reach - and a continue of a goal sight cannot meet
/// only beside a walk to the edge of sight. The rest travel with any action, and with an idle or a
/// rest only when no cell at the edge of sight is offered (r16: four adopts and revises beside
/// idle; r18: an adopted stick goal dug terracotta, and nine reviews idled beside offered cells).
fn coupled_schema(
    variants: Vec<Value>,
    offer: &GoalOffer,
    offered: &OfferedRefs,
    reasoning: &Value,
    defs: &mut Value,
) -> Value {
    let (passive, active): (Vec<Value>, Vec<Value>) = variants
        .into_iter()
        .partition(|variant| names_action(variant, &PASSIVE_ACTIONS));
    let reference = |name: &str| json!({ "$ref": format!("#/$defs/{name}") });
    let mut pairings = Vec::new();
    let pairing = |intent: Value, choices: &[Value]| {
        json!({
            "type": "object",
            "properties": {
                "intent": { "description": INTENT_DESCRIPTION, "anyOf": intent },
                "goal": goal_field(choices),
            },
            "required": ["intent", "goal"],
            "additionalProperties": false,
        })
    };
    let Some(table) = defs.as_object_mut() else {
        return json!({});
    };
    for (index, (item, choices)) in offer.by_item.iter().enumerate() {
        let Some(steps) = offered.item_steps.iter().find(|steps| &steps.item == item) else {
            continue;
        };
        let intents = item_step_variants(index, steps, offered, table);
        if intents.is_empty() || choices.is_empty() {
            continue;
        }
        let name = format!("stepsFor{index}");
        table.insert(name.clone(), json!({ "anyOf": intents }));
        pairings.push(pairing(json!([reference(&name)]), choices));
    }
    if !offer.exploring.is_empty() && !offered.explore_targets.is_empty() {
        table.insert(
            "exploreTargetRef".to_owned(),
            json!({
                "type": "string",
                "enum": offered.explore_targets,
                "description": "A cell at the edge of sight offered in this message, to look further from.",
            }),
        );
        let walk = intent_variant(
            "moveTo",
            vec![(
                "onto",
                Some(json!({
                    "description": "The ref of the cell at the edge of sight to walk to.",
                    "anyOf": [reference("exploreTargetRef")],
                })),
            )],
        );
        pairings.push(pairing(
            json!(walk.into_iter().collect::<Vec<_>>()),
            &offer.exploring,
        ));
    }
    if !offer.sheltering.is_empty() {
        let steps = shelter_step_variants(offered, table);
        if !steps.is_empty() {
            table.insert("shelterStep".to_owned(), json!({ "anyOf": steps }));
            pairings.push(pairing(
                json!([reference("shelterStep")]),
                &offer.sheltering,
            ));
        }
    }
    if let Some(survival) = survival_intents(offered, table) {
        pairings.push(pairing(survival, &[json!({ "type": "null" })]));
    }
    table.insert("activeIntent".to_owned(), json!({ "anyOf": active }));
    if offered.passive_allowed() {
        table.insert("passiveIntent".to_owned(), json!({ "anyOf": passive }));
    }
    if !offer.any.is_empty() {
        let mut intents = vec![reference("activeIntent")];
        if offered.passive_allowed() {
            intents.push(reference("passiveIntent"));
        }
        pairings.push(pairing(json!(intents), &offer.any));
    }
    json!({
        "type": "object",
        "properties": {
            "decision": {
                "description": "The action the body should take now, paired with your goal choice.",
                "anyOf": pairings,
            },
            "reasoning": reasoning,
        },
        "required": ["decision", "reasoning"],
        "additionalProperties": false,
    })
}

/// The survival responses a goal review may take before any goal step, with no goal choice: a
/// flight from or an attack on a hunter near enough to matter, and a meal at or below the hunger
/// threshold. Each steps aside from the goal, which stays held and is owed a review once the
/// response settles (r20: kappa reviewed at health 7.7 beside a zombie and a skeleton, was offered
/// only dirt digs, and died 28 ticks later). Their refs are defined once under `$defs`; none when
/// nothing is due.
fn survival_intents(
    offered: &OfferedRefs,
    table: &mut serde_json::Map<String, Value>,
) -> Option<Value> {
    const HUNTER: &str =
        "A creature in this message that hunts your kind and is near enough to flee from.";
    let mut intents = Vec::new();
    let mut enumerate = |name: &str, handles: Vec<String>, description: &str| {
        if handles.is_empty() {
            return None;
        }
        table.insert(
            name.to_owned(),
            json!({ "type": "string", "enum": handles, "description": description }),
        );
        Some(
            json!({ "description": description, "anyOf": [{ "$ref": format!("#/$defs/{name}") }] }),
        )
    };
    let flee_from = enumerate("dangerRef", offered.danger.clone(), HUNTER);
    let strike = enumerate(
        "dangerStrikeRef",
        offered.danger_targets(false),
        "A hunter near enough to strike where you stand.",
    );
    let pursue = enumerate(
        "dangerPursuitRef",
        offered.danger_targets(true),
        "A hunter near enough to strike after a bounded approach.",
    );
    let stack = || {
        (!offered.stacks.is_empty()).then(|| {
            json!({ "description": "The ref of an inventory stack offered in this message.", "anyOf": [{ "$ref": "#/$defs/stackRef" }] })
        })
    };
    if !offered.shelter.trapped() {
        intents.extend(intent_variant("flee", vec![("from", flee_from)]));
    }
    // Where no flight can run, closing the cover, or readying the block that does, shuts the
    // hunter out.
    if offered.shelter.trapped() && offered.in_immediate_danger() {
        intents.extend(closing_variants(offered, table, "survivalClosing"));
    }
    for (target, approach) in [(strike, false), (pursue, true)] {
        intents.extend(intent_variant(
            "attack",
            vec![
                ("target", target),
                ("weapon", stack()),
                (
                    "attacks",
                    plain(json!({ "type": "integer", "minimum": 1, "maximum": 4, "description": "The maximum number of bounded attack swings." })),
                ),
                ("approach", approach_field(ATTACK_APPROACH, Some(approach))),
            ],
        ));
    }
    if !offered.meals.is_empty() {
        intents.extend(intent_variant("eat", vec![("ref", stack())]));
    }
    (!intents.is_empty()).then(|| json!(intents))
}

/// A shelter goal's step intents, each ref list defined once under `$defs`: the descent underfoot,
/// each closing of an open face with a solid held stack, a walk onto more enclosed ground, and the
/// gathering of a closer while nothing held can close a face.
fn shelter_step_variants(
    offered: &OfferedRefs,
    table: &mut serde_json::Map<String, Value>,
) -> Vec<Value> {
    let shelter = &offered.shelter;
    let mut named = |name: &str, handles: Vec<String>, description: &str| -> Option<Value> {
        if handles.is_empty() {
            return None;
        }
        table.insert(
            name.to_owned(),
            json!({ "type": "string", "enum": handles, "description": description }),
        );
        Some(
            json!({ "description": description, "anyOf": [{ "$ref": format!("#/$defs/{name}") }] }),
        )
    };
    let dig = named(
        "shelterDigRef",
        shelter.descend.iter().cloned().collect(),
        "The block under your feet, whose dig drops you into more enclosed ground.",
    );
    let site = named(
        "shelterSiteRef",
        shelter.sites.clone(),
        "A block to stand on whose cell is more enclosed than where you stand: natural cover.",
    );
    // Gathering a closer while nothing held can close a face: a dig that yields a solid block, or a
    // collect of a solid drop, each a step of the shelter itself rather than a goal of its own.
    let gathered = |from: &[String]| -> Vec<String> {
        shelter
            .gathers
            .iter()
            .filter(|handle| from.contains(handle))
            .cloned()
            .collect()
    };
    let gather_dig = named(
        "shelterGatherDigRef",
        gathered(&offered.dig_blocks),
        "A block in reach whose dig gives a solid block to close your shelter with.",
    );
    let gather_pickup = named(
        "shelterGatherPickupRef",
        gathered(&offered.pickup_items),
        "A solid block lying within pickup reach, to close your shelter with.",
    );
    let gather_route = named(
        "shelterGatherRouteRef",
        gathered(&offered.route_items),
        "A solid block lying within walking reach, to close your shelter with.",
    );
    let mut variants: Vec<Value> = [
        dig.and_then(|block| intent_variant("dig", vec![("block", Some(block))])),
        site.and_then(|onto| intent_variant("moveTo", vec![("onto", Some(onto))])),
        gather_dig.and_then(|block| intent_variant("dig", vec![("block", Some(block))])),
        gather_pickup.and_then(|item| {
            intent_variant(
                "collect",
                vec![
                    ("item", Some(item)),
                    ("approach", approach_field(COLLECT_APPROACH, Some(false))),
                ],
            )
        }),
        gather_route.and_then(|item| {
            intent_variant(
                "collect",
                vec![
                    ("item", Some(item)),
                    ("approach", approach_field(COLLECT_APPROACH, Some(true))),
                ],
            )
        }),
    ]
    .into_iter()
    .flatten()
    .collect();
    variants.extend(closing_variants(offered, table, "shelterClosing"));
    variants
}

/// The placements that close an open face of the agent's cover with a solid held stack, one
/// variant per support side, their refs defined once under `$defs` with `prefix`.
fn closing_variants(
    offered: &OfferedRefs,
    table: &mut serde_json::Map<String, Value>,
    prefix: &str,
) -> Vec<Value> {
    let shelter = &offered.shelter;
    let ready = shelter.to_equip();
    if !ready.is_empty() {
        // Nothing solid in hand yet: the step is the equip that puts a solid stack there.
        let name = format!("{prefix}EquipRef");
        table.insert(
            name.clone(),
            json!({ "type": "string", "enum": ready, "description": "A stack you carry that places as a solid block, to put in your main hand." }),
        );
        return intent_variant(
            "equip",
            vec![
                ("item", Some(json!({ "description": "The solid stack to put in your main hand.", "anyOf": [{ "$ref": format!("#/$defs/{name}") }] }))),
                ("destination", plain(json!({ "type": "string", "enum": ["hand"], "description": "Your main hand." }))),
            ],
        )
        .into_iter()
        .collect();
    }
    if shelter.solid_held.is_empty() {
        return Vec::new();
    }
    let stack_name = format!("{prefix}StackRef");
    table.insert(
        stack_name.clone(),
        json!({ "type": "string", "enum": shelter.solid_held, "description": "The solid stack in your main hand." }),
    );
    let mut sides: Vec<loom_contract::BlockFace> = Vec::new();
    for (_, side) in &shelter.closings {
        if !sides.contains(side) {
            sides.push(*side);
        }
    }
    sides
        .into_iter()
        .filter_map(|side| {
            let face = serde_json::to_value(side).ok()?;
            let face_name = face.as_str()?.to_owned();
            let blocks: Vec<&String> = shelter
                .closings
                .iter()
                .filter(|(_, at)| *at == side)
                .map(|(block, _)| block)
                .collect();
            let name = format!("{prefix}{}Ref", capitalised(&face_name));
            table.insert(
                name.clone(),
                json!({ "type": "string", "enum": blocks, "description": "A support block whose side, placed against, closes an open face of your cover." }),
            );
            intent_variant(
                "place",
                vec![
                    ("block", Some(json!({ "description": "The support block to place against.", "anyOf": [{ "$ref": format!("#/$defs/{name}") }] }))),
                    ("item", Some(json!({ "description": "The solid stack, already held in the main hand, to place.", "anyOf": [{ "$ref": format!("#/$defs/{stack_name}") }] }))),
                    ("face", plain(json!({ "type": "string", "enum": [face_name], "description": "The side of the support block to place against." }))),
                ],
            )
        })
        .collect()
}

/// `text` with its first letter in upper case.
fn capitalised(text: &str) -> String {
    let mut chars = text.chars();
    chars.next().map_or_else(String::new, |first| {
        first.to_uppercase().chain(chars).collect()
    })
}

/// One goal item's own step intents, each ref list defined once under `$defs` as item `index`'s.
fn item_step_variants(
    index: usize,
    steps: &ItemSteps,
    offered: &OfferedRefs,
    table: &mut serde_json::Map<String, Value>,
) -> Vec<Value> {
    let mut named = |kind: &str, handles: &[String], description: &str| -> Option<Value> {
        if handles.is_empty() {
            return None;
        }
        let name = format!("item{index}{kind}Ref");
        table.insert(
            name.clone(),
            json!({ "type": "string", "enum": handles, "description": description }),
        );
        Some(
            json!({ "description": description, "anyOf": [{ "$ref": format!("#/$defs/{name}") }] }),
        )
    };
    let dig = named(
        "Dig",
        &steps.dig,
        "A block in reach that can drop this goal's item.",
    );
    let pickup = named(
        "Pickup",
        &steps.pickup,
        "A drop of this goal's item within pickup reach.",
    );
    let route = named(
        "Route",
        &steps.route,
        "A drop of this goal's item within walking reach.",
    );
    let recipes: Vec<(i64, Option<Value>)> = recipe_groups(offered, &steps.recipes)
        .into_iter()
        .map(|(most, members)| {
            let members: Vec<String> = members.into_iter().cloned().collect();
            (
                most,
                named(
                    &format!("RecipeOf{most}"),
                    &members,
                    "A recipe that makes this goal's item.",
                ),
            )
        })
        .collect();
    let walk = named(
        "Walk",
        &steps.walks,
        "A block out of dig reach that can drop this goal's item, to walk to.",
    );
    let toward = named(
        "Toward",
        &steps.toward,
        "A cell at the edge of sight toward where you last saw a source of this goal's item.",
    );
    [
        dig.clone()
            .map(|block| intent_variant("dig", vec![("block", Some(block))])),
        dig.map(|block| {
            intent_variant("digSequence", vec![("blocks", Some(dig_sequence(&block)))])
        }),
        pickup.map(|item| {
            intent_variant(
                "collect",
                vec![
                    ("item", Some(item)),
                    ("approach", approach_field(COLLECT_APPROACH, Some(false))),
                ],
            )
        }),
        route.map(|item| {
            intent_variant(
                "collect",
                vec![
                    ("item", Some(item)),
                    ("approach", approach_field(COLLECT_APPROACH, Some(true))),
                ],
            )
        }),
        walk.map(|onto| intent_variant("moveTo", vec![("onto", Some(onto))])),
        toward.map(|onto| intent_variant("moveTo", vec![("onto", Some(onto))])),
    ]
    .into_iter()
    .chain(recipes.into_iter().map(|(most, recipe)| {
        recipe.map(|recipe| {
            intent_variant(
                "craft",
                vec![("recipe", Some(recipe)), ("crafts", crafts_field(most))],
            )
        })
    }))
    .flatten()
    .flatten()
    .collect()
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
        DecisionImportance, GoalChoice, GoalDisposition, GoalProgress, GoalReason, GoalStep,
        GoalStepResult, GoalSummary, Intent, IntentAction, InventoryChange, Perception, Trigger,
        MAX_GOAL_ID_CHARS, MAX_GOAL_STATEMENT_CHARS, MAX_INVENTORY_CHANGES, MAX_ITEM_NAME_CHARS,
        MAX_SUCCESS_COUNT, REF_MAX_CHARS,
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
            discovery: false,
            goal_id: "g".repeat(MAX_GOAL_ID_CHARS),
            statement: "s".repeat(MAX_GOAL_STATEMENT_CHARS),
            revision: i64::MAX,
            adopted_tick: i64::MAX,
            last_step: Some(GoalStep {
                request_id: "r".repeat(MAX_GOAL_ID_CHARS),
                action: "a".repeat(32),
                outcome: "o".repeat(MAX_GOAL_ID_CHARS),
            }),
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
                cover: None,
            }),
            shelter: false,
            feeds: None,
        });
        // Every cell at the edge of sight it may be offered, each a walk target of its own, at
        // the widest place a number can render.
        seen.explore = (0..loom_contract::MAX_EXPLORE_TARGETS)
            .map(|index| loom_contract::ExploreTarget {
                target_ref: format!("{index:x>REF_MAX_CHARS$}"),
                distance: f64::MAX,
                direction: loom_contract::Bearing::North,
                vertical_offset: -f64::MAX,
            })
            .collect();
        // And the widest refusal a review can be told of.
        seen.last_refusal = Some(loom_contract::GoalRefusal {
            request_id: "q".repeat(MAX_GOAL_ID_CHARS),
            outcome: "o".repeat(MAX_GOAL_ID_CHARS),
            success_item: Some("i".repeat(MAX_ITEM_NAME_CHARS)),
            asked: Some(MAX_SUCCESS_COUNT),
            held: Some(i64::MAX),
            capacity: Some(i64::MAX),
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
        assert_eq!(variants.len(), 14, "one per contract Intent variant");
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
                "say",
                "moveTo",
                "flee",
                "eat",
                "dig",
                "digSequence",
                "collect",
                "place",
                "equip",
                "craft",
                "attack",
                "sleep",
                "rest",
                "idle",
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
        let choice = |disposition, targeted: bool, step: Option<&str>, result| GoalChoice {
            discover: None,
            disposition,
            success_item: targeted.then(|| "item.minecraft.oak_log".to_owned()),
            success_count: targeted.then_some(4),
            step: step.map(str::to_owned),
            step_result: result,
            shelter: None,
            feeds: None,
            reason: None,
        };
        let cases = [
            (
                r#"{"disposition":"adopt","successItem":"item.minecraft.oak_log","successCount":4,"step":"walk to the cliff"}"#,
                choice(
                    GoalDisposition::Adopt,
                    true,
                    Some("walk to the cliff"),
                    None,
                ),
            ),
            (
                r#"{"disposition":"continue","step":"dig into the cliff","stepResult":"done"}"#,
                choice(
                    GoalDisposition::Continue,
                    false,
                    Some("dig into the cliff"),
                    Some(GoalStepResult::Done),
                ),
            ),
            (
                r#"{"disposition":"revise","successItem":"item.minecraft.oak_log","successCount":4,"step":"walk north","stepResult":"noProgress"}"#,
                choice(
                    GoalDisposition::Revise,
                    true,
                    Some("walk north"),
                    Some(GoalStepResult::NoProgress),
                ),
            ),
            (
                r#"{"disposition":"complete","stepResult":"done"}"#,
                choice(
                    GoalDisposition::Complete,
                    false,
                    None,
                    Some(GoalStepResult::Done),
                ),
            ),
            (
                r#"{"disposition":"abandon","stepResult":"noProgress","reason":"stale"}"#,
                GoalChoice {
                    reason: Some(GoalReason::Stale),
                    ..choice(
                        GoalDisposition::Abandon,
                        false,
                        None,
                        Some(GoalStepResult::NoProgress),
                    )
                },
            ),
        ];
        for (text, expected) in cases {
            assert_eq!(goal_of(text).unwrap(), Some(expected), "{text}");
        }
    }

    #[test]
    fn a_coupled_answer_lifts_its_decision_beside_the_reasoning() {
        let draft = draft_from_answer(
            r#"{"decision":{"intent":{"action":"collect","item":"d7","approach":true},"goal":{"disposition":"replace","successItem":"item.minecraft.stick","successCount":2,"step":"collect my stick","stepResult":"noProgress","reason":"stale"}},"reasoning":"stalled; my stick lies here"}"#,
        )
        .unwrap();
        assert_eq!(
            draft.intent,
            Intent::Collect {
                item: "d7".to_owned(),
                approach: true
            }
        );
        let goal = draft.goal.unwrap();
        assert_eq!(goal.disposition, GoalDisposition::Replace);
        assert_eq!(goal.success_count, Some(2));
        // A replace sets a new goal and judges the old one's last step, so it needs both.
        let unjudged = draft_from_answer(
            r#"{"decision":{"intent":{"action":"idle"},"goal":{"disposition":"replace","successItem":"item.minecraft.stick","successCount":2,"step":"s","reason":"stale"}},"reasoning":"r"}"#,
        )
        .expect_err("a replace judges the previous step");
        assert!(unjudged.contains("needs a step result"), "{unjudged}");
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
        for (missing, field) in [
            (
                r#"{"disposition":"adopt","successCount":1,"step":"s"}"#.to_owned(),
                "success item",
            ),
            (
                r#"{"disposition":"adopt","successItem":"item.minecraft.stick","step":"s"}"#
                    .to_owned(),
                "success count",
            ),
            (
                r#"{"disposition":"continue","step":"s"}"#.to_owned(),
                "step result",
            ),
            (
                format!(r#"{{"disposition":"revise",{target},"step":"s"}}"#),
                "step result",
            ),
            (r#"{"disposition":"complete"}"#.to_owned(), "step result"),
            (
                r#"{"disposition":"abandon","reason":"stale"}"#.to_owned(),
                "step result",
            ),
        ] {
            let reason = goal_of(&missing).expect_err(&missing);
            assert!(
                reason.contains(&format!("needs a {field}")),
                "{missing}: {reason}"
            );
        }
        for forbidden in [
            r#"{"disposition":"continue","step":"s","stepResult":"done","successItem":"item.minecraft.sk_lookalike"}"#
                .to_owned(),
            format!(r#"{{"disposition":"adopt",{target},"step":"s","stepResult":"done"}}"#),
        ] {
            let reason = goal_of(&forbidden).expect_err(&forbidden);
            assert!(reason.contains("forbids"), "{forbidden}: {reason}");
            assert!(!reason.contains("sk-lookalike"), "{reason}");
        }
        // A stated success condition or a typed step is no part of a choice from routine-v23, and a
        // statement none from routine-v24: the body words and types all three itself (r14 delta
        // worded a log while naming a stick; r15 statements and counts disagreed). From
        // routine-v26 the step's wording is the body's too, from the intent (r17 epsilon worded
        // terracotta while its intent dug a bush), so a step an answer still gives is ignored.
        for stated in [
            format!(r#"{{"disposition":"adopt","successCondition":"c",{target},"step":"s"}}"#),
            format!(
                r#"{{"disposition":"adopt",{target},"step":"s","stepAction":"dig","stepRef":"b7"}}"#
            ),
            format!(r#"{{"disposition":"adopt","statement":"g",{target},"step":"s"}}"#),
        ] {
            let reason = goal_of(&stated).expect_err(&stated);
            assert!(reason.contains("wrong shape"), "{stated}: {reason}");
        }
    }

    #[test]
    fn a_reason_is_worded_only_where_it_may_be_given() {
        let completed = super::reason_description(&[super::COMPLETED]);
        assert_eq!(
            completed,
            "Why the held goal ends: completed, its condition is met."
        );
        let stalled = super::reason_description(&[super::STALE, super::UNSAFE]);
        assert!(stalled.contains("stale, ") && stalled.contains("unsafe, "));
        for absent in ["completed", "impossible", "superseded"] {
            assert!(!stalled.contains(absent), "{stalled}");
        }
    }

    #[test]
    fn a_replace_or_abandon_states_why_and_nothing_else_may() {
        for ending in [
            r#"{"disposition":"abandon","stepResult":"noProgress"}"#,
            r#"{"disposition":"replace","successItem":"item.minecraft.stick","successCount":2,"stepResult":"done"}"#,
        ] {
            let refused = goal_of(ending).expect_err(ending);
            assert!(
                refused.contains("without saying why"),
                "{ending}: {refused}"
            );
        }
        for unended in [
            r#"{"disposition":"continue","stepResult":"done","reason":"stale"}"#,
            r#"{"disposition":"complete","stepResult":"done","reason":"completed"}"#,
            r#"{"disposition":"revise","successItem":"item.minecraft.stick","successCount":4,"stepResult":"done","reason":"superseded"}"#,
            r#"{"disposition":"adopt","successItem":"item.minecraft.stick","successCount":2,"reason":"completed"}"#,
        ] {
            let refused = goal_of(unended).expect_err(unended);
            assert!(refused.contains("forbids"), "{unended}: {refused}");
        }
        let stated =
            goal_of(r#"{"disposition":"abandon","stepResult":"noProgress","reason":"impossible"}"#)
                .unwrap()
                .unwrap();
        assert_eq!(stated.reason, Some(GoalReason::Impossible));
        // The contract refuses a reason it does not define.
        assert!(
            goal_of(r#"{"disposition":"abandon","stepResult":"done","reason":"bored"}"#).is_err()
        );
    }

    #[test]
    fn a_discovery_choice_is_an_adopt_or_replace_with_no_success_item() {
        let adopt = goal_of(r#"{"disposition":"adopt","discover":true}"#)
            .unwrap()
            .unwrap();
        assert_eq!(adopt.discover, Some(true));
        assert_eq!(adopt.success_item, None);
        let replace = goal_of(
            r#"{"disposition":"replace","discover":true,"stepResult":"progressing","reason":"completed"}"#,
        )
        .unwrap()
        .unwrap();
        assert_eq!(replace.step_result, Some(GoalStepResult::Progressing));
        for bad in [
            r#"{"disposition":"continue","discover":true,"stepResult":"done"}"#,
            r#"{"disposition":"revise","discover":true,"stepResult":"done"}"#,
            r#"{"disposition":"adopt","discover":true,"successItem":"item.minecraft.dirt","successCount":2}"#,
            r#"{"disposition":"adopt","discover":true,"stepResult":"done"}"#,
            r#"{"disposition":"replace","discover":true,"reason":"completed"}"#,
        ] {
            assert!(goal_of(bad).is_err(), "{bad}");
        }
        // The contract refuses a discover that is anything but true.
        assert!(goal_of(r#"{"disposition":"adopt","discover":false}"#).is_err());
    }

    #[test]
    fn a_malformed_goal_choice_is_a_bounded_malformed_answer() {
        // An over-long text is bounded rather than refused; see the test after this one.
        for bad in [
            r#"{"disposition":"adopt","step":"go","priority":1}"#,
            r#"{"disposition":"pursue"}"#,
            r#"{"disposition":"continue","step":"","stepResult":"done"}"#,
            r#"{"disposition":"continue","step":7,"stepResult":"done"}"#,
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
            r#"{{"intent":{{"action":"idle"}},"reasoning":"r","goal":{{"disposition":"adopt","successItem":"item.minecraft.stick","successCount":1,"step":"{long}"}}}}"#
        );
        let goal = draft_from_answer(&answer).unwrap().goal.unwrap();
        let text = goal.step.unwrap();
        assert_eq!(text.chars().count(), MAX_GOAL_STATEMENT_CHARS);
        assert!(text.ends_with("..."));
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
