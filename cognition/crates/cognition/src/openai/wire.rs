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

use loom_contract::{Intent, Perception, PlanUpdate, Trigger, MAX_PLAN_STEPS};
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
    /// What the answer does to the plan; absence is a continue.
    #[serde(default)]
    plan: Option<PlanUpdate>,
}

/// Most characters of a plan's objective or a change's reason.
const MAX_PLAN_LINE_CHARS: usize = 200;

/// Most characters of one subgoal or step.
const MAX_PLAN_ITEM_CHARS: usize = 160;

/// `items` cut to [`MAX_PLAN_STEPS`], each cut to its bound, blanks dropped.
fn bounded_items(items: Vec<String>) -> Vec<String> {
    items
        .into_iter()
        .map(|item| bounded_to(item.trim(), MAX_PLAN_ITEM_CHARS))
        .filter(|item| !item.is_empty())
        .take(MAX_PLAN_STEPS)
        .collect()
}

/// `update` with every text bounded; a change left with an empty list or line is a continue.
fn bounded_plan(update: PlanUpdate) -> PlanUpdate {
    let line = |text: &str| bounded_to(text.trim(), MAX_PLAN_LINE_CHARS);
    match update {
        PlanUpdate::Continue | PlanUpdate::StepDone => update,
        PlanUpdate::Replan { reason } => {
            let reason = line(&reason);
            if reason.is_empty() {
                PlanUpdate::Continue
            } else {
                PlanUpdate::Replan { reason }
            }
        }
        PlanUpdate::ReplacePlan {
            objective,
            subgoals,
            steps,
            reason,
        } => {
            let (objective, subgoals, steps, reason) = (
                line(&objective),
                bounded_items(subgoals),
                bounded_items(steps),
                line(&reason),
            );
            if objective.is_empty() || subgoals.is_empty() || steps.is_empty() {
                PlanUpdate::Continue
            } else {
                PlanUpdate::ReplacePlan {
                    objective,
                    subgoals,
                    steps,
                    reason,
                }
            }
        }
    }
}

/// What the planner answers: its reasoning and the whole plan.
#[derive(Debug, Deserialize)]
struct PlanningAnswer {
    reasoning: String,
    plan: PlannedTree,
}

/// The planner's plan, before it is bounded.
#[derive(Debug, Deserialize)]
struct PlannedTree {
    objective: String,
    subgoals: Vec<String>,
    steps: Vec<String>,
    reason: String,
}

/// Parses the planner's answer into a [`Draft`]: an idle intent beside the replacing plan.
///
/// # Errors
///
/// A short reason, as [`draft_from_answer`] gives one.
pub fn planning_draft_from_answer(content: &str) -> Result<Draft, String> {
    match serde_json::from_str::<PlanningAnswer>(content) {
        Ok(answer) => Ok(Draft {
            intent: Intent::Idle,
            reasoning: bounded_to(&answer.reasoning, MAX_REASONING_CHARS),
            goal: None,
            plan: Some(bounded_plan(PlanUpdate::ReplacePlan {
                objective: answer.plan.objective,
                subgoals: answer.plan.subgoals,
                steps: answer.plan.steps,
                reason: answer.plan.reason,
            })),
        }),
        Err(e) => Err(format!(
            "the planner's answer was not a plan ({detail})",
            detail = describe_json_error(&e)
        )),
    }
}

/// Whether `perception` asks the planner rather than the step model.
#[must_use]
pub fn is_plan_review(perception: &Perception) -> bool {
    matches!(perception.trigger, Trigger::PlanReview { .. })
}

/// The schema one request for `perception` sends: the planner's or the step model's.
#[must_use]
pub fn request_schema(perception: &Perception) -> Value {
    if is_plan_review(perception) {
        planning_schema().clone()
    } else {
        decision_schema_for(&OfferedRefs::of(perception))
    }
}

/// The answer text for `perception`'s request, parsed as its schema words one.
///
/// # Errors
///
/// A short reason, as [`draft_from_answer`] gives one.
pub fn request_draft_from_answer(perception: &Perception, content: &str) -> Result<Draft, String> {
    if is_plan_review(perception) {
        planning_draft_from_answer(content)
    } else {
        draft_from_answer(content)
    }
}

/// The planner's schema: its reasoning and the whole plan, the first subgoal the current one.
#[must_use]
pub fn planning_schema() -> &'static Value {
    static SCHEMA: LazyLock<Value> = LazyLock::new(|| {
        let list = |description: &str| json!({ "type": "array", "items": { "type": "string" }, "description": description });
        json!({
            "type": "object",
            "properties": {
                "reasoning": {
                    "type": "string",
                    "description": "One or two sentences on why this plan, recorded permanently.",
                },
                "plan": {
                    "type": "object",
                    "properties": {
                        "objective": { "type": "string", "description": "The objective in plain words." },
                        "subgoals": list("The remaining subgoals in order, the first being the current one; at most 8."),
                        "steps": list("The current subgoal's short ordered steps; at most 8."),
                        "reason": { "type": "string", "description": "What changed from the plan you were shown, or why it stands." },
                    },
                    "required": ["objective", "subgoals", "steps", "reason"],
                    "additionalProperties": false,
                },
            },
            "required": ["reasoning", "plan"],
            "additionalProperties": false,
        })
    });
    &SCHEMA
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
            goal: None,
            plan: answer.plan.map(bounded_plan),
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
    static SCHEMA: LazyLock<Value> = LazyLock::new(|| build_decision_schema(None));
    &SCHEMA
}

/// Whether the request schema `perception` produces offers the model any answer at all. A schema
/// with an empty answer union is one the provider refuses as an invalid request, so the stand-ins
/// a rehearsal runs ask this of every perception and fail the rehearsal rather than the paid run
/// (r25 stopped on a met goal's review whose schema offered nothing).
#[must_use]
pub fn request_offers_an_answer(perception: &Perception) -> bool {
    let schema = decision_schema_for(&OfferedRefs::of(perception));
    schema["properties"]["intent"]["anyOf"]
        .as_array()
        .is_some_and(|union| !union.is_empty())
}

/// The schema one request sends: every ref an `enum` of the handles this perception
/// offers in that action's own namespace.
///
/// A variant whose refs have nothing to name is left out, because an action the body
/// can only refuse is not one to offer. `say`, `rest` and `idle` name nothing and are
/// always present, so the union is never empty.
#[must_use]
pub fn decision_schema_for(offered: &OfferedRefs) -> Value {
    build_decision_schema(Some(offered))
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
    if !offered.explore_targets.is_empty() {
        defs.insert(
            "exploreTargetRef".to_owned(),
            json!({
                "type": "string",
                "enum": offered.explore_targets,
                "description": "A cell at the edge of sight offered in this message, whose bearing a long walk follows.",
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

const WALK_TOWARD: &str =
    "The ref of a cell at the edge of sight whose compass bearing to walk along.";

/// A long walk's `blocks`.
fn walk_blocks() -> Value {
    json!({
        "type": "integer",
        "minimum": 1,
        "description": "How many blocks to walk along that bearing, in one decision; the body walks in hops and stops on arrival, at a hop that makes no headway (the world border included), or when danger takes the body."
    })
}

/// A dig sequence's `blocks`: an array of `block`, bounded by the contract type since strict mode cannot.
fn dig_sequence(block: &Value) -> Value {
    json!({
        "type": "array",
        "items": block,
        "description": "One or more distinct block refs to dig in order, in one decision; the body stops at the first it cannot dig."
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

fn build_decision_schema(offered: Option<&OfferedRefs>) -> Value {
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
        // A request offers a long walk only beside its exploration walk, reusing the edge-of-sight refs.
        offered.map_or_else(|| Some(json!({ "type": "string", "description": WALK_TOWARD })), |refs| (!refs.explore_targets.is_empty()).then(|| json!({ "description": WALK_TOWARD, "anyOf": [{ "$ref": "#/$defs/exploreTargetRef" }] }))).and_then(|toward| intent_variant("walk", vec![("toward", Some(toward)), ("blocks", Some(walk_blocks()))])),
        intent_variant("flee", vec![("from", creatures("The ref of the one living thing to move away from. A creature ref given in this message, never a name, a type, or an invented one."))]),
        intent_variant("eat", vec![("ref", stacks("The ref of the one stack in your own inventory to eat one item from. A stack ref given in this message, never an item name or an invented one."))]),
        intent_variant("dig", vec![("block", dig_blocks("The ref of the exact nearby block to dig once. A block ref from this message, never coordinates or an item name."))]),
        // Its items name the dig namespace by `$ref`, so a sequence adds no enum values.
        dig_blocks("The ref of one nearby block to dig, from this message.").and_then(|block| intent_variant("digSequence", vec![("blocks", Some(dig_sequence(&block)))])),
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
    let mut defs = offered.map_or_else(|| json!({}), namespace_defs);
    let mut variants = variants;
    // The cover's closings name refs the cover reading offered, so they are offered too.
    if let (Some(refs), Some(table)) = (offered, defs.as_object_mut()) {
        variants.extend(closing_variants(refs, table, "shelterClosing"));
    }
    let mut schema = json!({
        "type": "object",
        "properties": {
            "intent": { "description": INTENT_DESCRIPTION, "anyOf": variants },
            "reasoning": reasoning,
            "plan": plan_field(),
        },
        "required": ["intent", "reasoning", "plan"],
        "additionalProperties": false,
    });
    if let (Some(root), Some(defs)) = (schema.as_object_mut(), defs.as_object()) {
        if offered.is_some() || !defs.is_empty() {
            root.insert("$defs".to_owned(), Value::Object(defs.clone()));
        }
    }
    schema
}

const INTENT_DESCRIPTION: &str = "The single action the body should take now.";

/// The step model's plan updates; exactly one per answer.
fn plan_field() -> Value {
    let shape = |update: &str, description: &str, reason: bool| {
        let mut properties = serde_json::Map::new();
        properties.insert(
            "update".to_owned(),
            json!({ "type": "string", "enum": [update], "description": description }),
        );
        let mut required = vec![Value::String("update".to_owned())];
        if reason {
            properties.insert(
                "reason".to_owned(),
                json!({ "type": "string", "description": "Why the current subgoal is blocked or impossible." }),
            );
            required.push(Value::String("reason".to_owned()));
        }
        json!({
            "type": "object",
            "properties": properties,
            "required": required,
            "additionalProperties": false,
        })
    };
    json!({
        "description": "What this answer does to your plan: exactly one update.",
        "anyOf": [
            shape("continue", "This action works on the current step.", false),
            shape("stepDone", "The current step is finished; this action starts the next.", false),
            shape("replan", "The current subgoal is blocked or impossible; ask your planner for a new plan.", true),
        ],
    })
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

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        chat_request, decision_schema, decision_schema_for, draft_from_answer, ApiErrorBody,
        ChatResponse, MAX_REASONING_CHARS, SCHEMA_NAME,
    };
    use crate::offered::OfferedRefs;
    use crate::openai::settings::DEFAULT_MAX_REQUEST_BYTES;
    use crate::prompt::{render, Prompt};
    use crate::router::{ModelCatalog, Router};
    use loom_contract::{Intent, Perception};
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
            decision_schema_for(&OfferedRefs::of(&seen)),
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
        assert_eq!(variants.len(), 15, "one per contract Intent variant");
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
                "walk",
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
