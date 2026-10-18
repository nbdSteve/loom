//! Drives the whole mind against a mock provider on loopback.
//!
//! **Nothing here reaches the real `OpenAI` service, and there is no credential
//! in this file.** The provider is a few dozen lines of HTTP/1.1 spoken over a
//! `tokio` listener, which is deliberate: a mocking crate would let these tests
//! pass against a mock that does not behave like a socket, and several of the
//! failures this file exists to pin - a provider that never answers, one that
//! sends more than Loom will read - are socket behavior rather than HTTP
//! behavior.
//!
//! Each test drives `Mind::decide`, not the backend alone, so what is proven is
//! the path an agent's perception really takes: route, render, post, classify,
//! stamp. Two properties are asserted throughout, because losing either is
//! invisible in an otherwise passing suite:
//!
//! - **One perception is one request.** A retry here would bill a second
//!   inference for one perception. Delivery is at-most-once by design, and the
//!   body's transport is the only layer allowed to retry.
//! - **No failure message carries the credential or the prompt.** Each failure
//!   test checks its own message, and the last test sweeps every failure path
//!   against both markers at once.
//!
//! The mock records what it was sent and asserts nothing itself. An assertion on
//! a spawned task would fail as a dropped connection at the client, which is a
//! confusing way to learn that a header was missing.

#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use loom_cognition::config::Env;
use loom_cognition::mind::Mind;
use loom_cognition::model::{BackendError, BackendFailureMapping, BackendResponseShape};
use loom_cognition::openai::settings::{
    API_KEY_VAR, BASE_URL_VAR, DEEP_MODEL_VAR, FAST_MODEL_VAR, MAX_OUTPUT_TOKENS_VAR,
    MAX_REQUEST_BYTES_VAR, TIMEOUT_MS_VAR, USAGE_LOG_VAR,
};
use loom_cognition::openai::{OpenAiBackend, OpenAiConfig, MAX_RESPONSE_BYTES};
use loom_cognition::router::Router;
use loom_contract::{
    DayPhase, Decision, DecisionImportance, GoalChoice, GoalDisposition, GoalStepResult, Intent,
    InventoryStack, Perception, SelfState, Trigger, WorldState,
};
use serde_json::{json, Value};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

/// Not a credential. These tests need a syntactically valid bearer token and
/// nothing more, so the value says what it is.
const NOT_A_KEY: &str = "test-key-not-a-secret";

/// Model ids the mock is configured with.
///
/// Distinct, so an assertion that a tier reached the right one is a real
/// assertion rather than one that would pass either way.
const FAST_MODEL: &str = "mock-fast-model";
const DEEP_MODEL: &str = "mock-deep-model";

/// Carried in the perception, so a message that leaked the prompt can be caught.
const PROMPT_MARKER: &str = "sixteen-emerald-marker";

/// How long a silent mock waits before giving up, well past any timeout a test
/// configures.
const SILENCE: Duration = Duration::from_secs(30);
static TEMPORARY_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

/// What the mock provider does with every connection it accepts.
enum Reply {
    /// Answer with this status, these extra headers, and this body.
    Http {
        status: u16,
        headers: &'static [(&'static str, &'static str)],
        body: String,
    },
    /// Read the request and never answer, so the caller's own timeout is the only
    /// thing that can end the request.
    Silence,
}

impl Reply {
    /// A successful chat completion whose answer text is `answer`.
    fn completion(answer: &Value) -> Self {
        Self::ok(&json!({
            "choices": [{
                "finish_reason": "stop",
                "message": { "role": "assistant", "content": answer.to_string() }
            }]
        }))
    }

    /// A successful completion with provider-reported billing counts.
    fn completion_with_usage(answer: &Value) -> Self {
        Self::ok(&json!({
            "choices": [{
                "finish_reason": "stop",
                "message": { "role": "assistant", "content": answer.to_string() }
            }],
            "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120,
                "prompt_tokens_details": { "cached_tokens": 25 }
            }
        }))
    }

    /// HTTP 200 carrying `body`.
    fn ok(body: &Value) -> Self {
        Self::Http {
            status: 200,
            headers: &[],
            body: body.to_string(),
        }
    }

    /// An error status carrying a provider-shaped error body.
    ///
    /// Its `message` holds both markers. That is the field Loom must never
    /// repeat, so every error the mock sends makes a leak of it obvious.
    fn refused(status: u16, kind: &str, code: &str) -> Self {
        Self::Http {
            status,
            headers: &[],
            body: json!({
                "error": {
                    "message":
                        format!("Incorrect API key provided: {NOT_A_KEY}, near {PROMPT_MARKER}"),
                    "type": kind,
                    "code": code,
                }
            })
            .to_string(),
        }
    }
}

/// One request the mock was sent.
#[derive(Clone)]
struct Recorded {
    /// The request line and headers, lowercased for matching.
    head: String,
    /// The request body, exactly as many bytes as it declared.
    body: String,
}

/// A provider on loopback that answers every request the same way and keeps what
/// it was sent.
struct MockProvider {
    base_url: String,
    seen: Arc<Mutex<Vec<Recorded>>>,
}

impl MockProvider {
    /// Starts a provider that answers `reply`, and returns once it is listening.
    ///
    /// Every connection gets the same reply, so a retry is answered rather than
    /// left hanging: it then shows up as a second recorded request instead of as
    /// some unrelated failure.
    async fn start(reply: Reply) -> Self {
        let listener = TcpListener::bind(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)))
            .await
            .expect("the mock provider binds an ephemeral loopback port");
        let port = listener
            .local_addr()
            .expect("the mock knows its own port")
            .port();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let recorded = Arc::clone(&seen);
        let shared = Arc::new(reply);
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let recorded = Arc::clone(&recorded);
                let reply = Arc::clone(&shared);
                tokio::spawn(async move { serve_one(stream, &reply, &recorded).await });
            }
        });
        Self {
            base_url: format!("http://127.0.0.1:{port}"),
            seen,
        }
    }

    /// The requests this provider was sent, in order.
    fn requests(&self) -> Vec<Recorded> {
        self.seen
            .lock()
            .expect("the record is not poisoned")
            .clone()
    }

    /// The one request this provider was sent.
    ///
    /// # Panics
    ///
    /// If it was sent any other number of requests. "Exactly one" is the
    /// property, so a second request has to fail the test that asserts on the
    /// first rather than be silently ignored.
    fn only_request(&self) -> Recorded {
        let requests = self.requests();
        assert_eq!(
            requests.len(),
            1,
            "one perception must reach the provider exactly once"
        );
        requests.into_iter().next().expect("just counted one")
    }
}

impl Recorded {
    /// The request body, parsed.
    fn json(&self) -> Value {
        serde_json::from_str(&self.body).expect("Loom sends JSON")
    }
}

/// Reads one HTTP request, records it, and answers it.
async fn serve_one(mut stream: TcpStream, reply: &Reply, seen: &Arc<Mutex<Vec<Recorded>>>) {
    let Some(request) = read_request(&mut stream).await else {
        return;
    };
    seen.lock()
        .expect("the record is not poisoned")
        .push(request);
    match reply {
        Reply::Http {
            status,
            headers,
            body,
        } => {
            let mut extra = String::new();
            for (name, value) in *headers {
                extra.push_str(name);
                extra.push_str(": ");
                extra.push_str(value);
                extra.push_str("\r\n");
            }
            let reason = reqwest::StatusCode::from_u16(*status)
                .ok()
                .and_then(|code| code.canonical_reason())
                .unwrap_or("Status");
            let head = format!(
                "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\n\
                 Content-Length: {len}\r\nConnection: close\r\n{extra}\r\n",
                len = body.len()
            );
            let _ = stream.write_all(head.as_bytes()).await;
            let _ = stream.write_all(body.as_bytes()).await;
            let _ = stream.flush().await;
        }
        Reply::Silence => tokio::time::sleep(SILENCE).await,
    }
}

/// Reads the headers and then exactly the declared body, or `None` if the peer
/// went away first.
///
/// Enough HTTP to be a provider and no more: Loom always sends a POST delimited
/// by `Content-Length`, so there is no chunked path to support here.
async fn read_request(stream: &mut TcpStream) -> Option<Recorded> {
    let mut raw: Vec<u8> = Vec::new();
    let head_end = loop {
        if let Some(at) = find(&raw, b"\r\n\r\n") {
            break at + 4;
        }
        let mut chunk = [0_u8; 1024];
        let read = stream.read(&mut chunk).await.ok()?;
        if read == 0 {
            return None;
        }
        raw.extend_from_slice(&chunk[..read]);
    };
    let head = String::from_utf8_lossy(&raw[..head_end]).to_lowercase();
    let declared: usize = head
        .lines()
        .find_map(|line| line.strip_prefix("content-length:"))
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0);
    let mut body = raw[head_end..].to_vec();
    while body.len() < declared {
        let mut chunk = [0_u8; 4096];
        let read = stream.read(&mut chunk).await.ok()?;
        if read == 0 {
            break;
        }
        body.extend_from_slice(&chunk[..read]);
    }
    Some(Recorded {
        head,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

/// First index of `needle` in `haystack`.
fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

/// A mind pointed at `base_url`, with `extra` settings applied over the defaults.
fn mind_for(base_url: &str, extra: &[(&str, &str)]) -> Mind<OpenAiBackend> {
    let mut pairs: Vec<(String, String)> = vec![
        (API_KEY_VAR.to_owned(), NOT_A_KEY.to_owned()),
        (BASE_URL_VAR.to_owned(), base_url.to_owned()),
        (FAST_MODEL_VAR.to_owned(), FAST_MODEL.to_owned()),
        (DEEP_MODEL_VAR.to_owned(), DEEP_MODEL.to_owned()),
        // Short, so a test that waits out a timeout does not cost the suite the
        // production default.
        (TIMEOUT_MS_VAR.to_owned(), "600".to_owned()),
    ];
    for (name, value) in extra {
        // Replaced rather than appended: the lookup below takes the first match,
        // so an appended override would be shadowed by the default it meant to
        // replace - and a test would then quietly assert against the default.
        match pairs.iter_mut().find(|(key, _)| key == name) {
            Some(existing) => (*value).clone_into(&mut existing.1),
            None => pairs.push(((*name).to_owned(), (*value).to_owned())),
        }
    }
    // Reading through a closure rather than the process environment is what lets
    // these tests run in parallel: no test mutates state another can see.
    let env = Env::new(move |name| {
        pairs
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.clone())
    });
    let config = OpenAiConfig::from_env(&env).expect("the mock configuration is valid");
    let router = Router::new(config.catalog().clone());
    let backend = OpenAiBackend::new(config).expect("an HTTP client builds");
    Mind::new(router, backend)
}

fn perception(importance: DecisionImportance) -> Perception {
    Perception {
        request_id: "req-mock-1".to_owned(),
        agent_id: "agent-mock".to_owned(),
        trigger: Trigger::Spoken {
            from: "player-steve".to_owned(),
            text: format!("trade me {PROMPT_MARKER}?"),
        },
        importance,
        captured_tick: 4096,
        self_state: SelfState {
            health: 12.5,
            food: 20,
            saturation: 5.0,
            position: [10.0, 64.0, -3.0],
            inventory_summary: vec![InventoryStack {
                slot_ref: "i1".to_owned(),
                what: "bread".to_owned(),
                count: 1,
            }],
            crafting_options: vec![],
            crafting_options_omitted: 0,
            own_drops: vec![],
            world: WorldState {
                day_phase: DayPhase::Day,
                is_day: true,
                ticks_until_nightfall: 6_000,
            },
        },
        surroundings: vec![],
        surroundings_omitted: 0,
        goal: None,
        last_refusal: None,
        explore: vec![],
        recalled: vec![],
        horizon: vec![],
    }
}

/// Decides once against a provider that answers `reply`.
async fn decide_with(
    reply: Reply,
    importance: DecisionImportance,
) -> (MockProvider, Result<Decision, BackendError>) {
    let provider = MockProvider::start(reply).await;
    let mind = mind_for(&provider.base_url, &[]);
    let decided = mind.decide(&perception(importance)).await;
    (provider, decided)
}

/// The answer a well-behaved model would give.
fn good_answer() -> Value {
    json!({
        "intent": { "action": "say", "text": "yes, one emerald" },
        "reasoning": "the player asked to trade and I have bread"
    })
}

fn temporary_usage_file() -> std::path::PathBuf {
    let sequence = TEMPORARY_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "loom-provider-usage-{}-{sequence}.jsonl",
        std::process::id()
    ))
}

#[tokio::test]
async fn a_decision_comes_back_from_the_provider_and_is_stamped_by_loom() {
    let (provider, decided) = decide_with(
        Reply::completion(&good_answer()),
        DecisionImportance::Notable,
    )
    .await;
    let decision = decided.expect("a well-formed completion is a decision");

    assert_eq!(decision.request_id, "req-mock-1");
    assert_eq!(decision.agent_id, "agent-mock");
    assert_eq!(
        decision.intent,
        Intent::Say {
            text: "yes, one emerald".to_owned()
        }
    );
    assert_eq!(decision.provenance.tier, DecisionImportance::Notable);
    assert_eq!(
        decision.provenance.model, FAST_MODEL,
        "provenance names the model the route chose, so the request must have gone there"
    );
    assert!(!decision.provenance.prompt_version.is_empty());

    let sent = provider.only_request().json();
    assert_eq!(sent["model"], FAST_MODEL);
    assert_eq!(sent["max_completion_tokens"], 2_048);
    assert_eq!(sent["response_format"]["json_schema"]["strict"], true);
    assert_eq!(
        sent["response_format"]["json_schema"]["schema"]["required"],
        json!(["intent", "reasoning", "goal"])
    );
    // The schema sent is this perception's: its one stack is the only stack ref, and
    // an action naming a namespace it offers nothing in is not offered at all.
    let schema = &sent["response_format"]["json_schema"]["schema"];
    assert_eq!(schema["$defs"]["stackRef"]["enum"], json!(["i1"]));
    assert!(schema["$defs"].get("blockRef").is_none(), "{schema}");
    let actions: Vec<&str> = schema["properties"]["intent"]["anyOf"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|variant| variant["properties"]["action"]["enum"][0].as_str())
        .collect();
    assert_eq!(actions, ["say", "eat", "equip", "rest", "idle"]);
    assert_eq!(
        decision.goal, None,
        "an answer without a goal choice makes none"
    );
    assert!(
        sent["messages"][1]["content"]
            .as_str()
            .is_some_and(|text| text.contains(PROMPT_MARKER)),
        "the perception itself must reach the provider, got {sent}"
    );
}

#[tokio::test]
async fn a_provider_goal_choice_reaches_the_decision_unchanged() {
    let answer = json!({
        "intent": { "action": "idle" },
        "reasoning": "the held goal is still worth it and nothing to step toward",
        "goal": {
            "disposition": "revise",
            "successItem": "item.minecraft.birch_log",
            "successCount": 8,
            "step": "walk to the birch on the hill",
            "stepResult": "noProgress"
        }
    });
    let (_provider, decided) =
        decide_with(Reply::completion(&answer), DecisionImportance::Routine).await;
    let decision = decided.expect("a goal choice is part of a decision");
    assert_eq!(
        decision.goal,
        Some(GoalChoice {
            disposition: GoalDisposition::Revise,
            success_item: Some("item.minecraft.birch_log".to_owned()),
            success_count: Some(8),
            step: Some("walk to the birch on the hill".to_owned()),
            step_result: Some(GoalStepResult::NoProgress),
        })
    );
    assert_eq!(decision.intent, Intent::Idle);
    assert_eq!(decision.provenance.model, FAST_MODEL);
    assert_eq!(decision.provenance.prompt_version, "routine-v30");
}

#[tokio::test]
async fn configured_usage_is_appended_without_provider_or_prompt_secrets() {
    let provider = MockProvider::start(Reply::completion_with_usage(&good_answer())).await;
    let file = temporary_usage_file();
    let configured = file.to_string_lossy().into_owned();
    let mind = mind_for(&provider.base_url, &[(USAGE_LOG_VAR, &configured)]);
    let decision = mind
        .decide(&perception(DecisionImportance::Notable))
        .await
        .expect("a decision");
    assert_eq!(decision.provenance.model, FAST_MODEL);
    drop(mind);

    let text = fs::read_to_string(&file).expect("usage was made durable");
    let record: Value = serde_json::from_str(text.trim()).expect("usage is JSON");
    assert_eq!(record["schemaVersion"], "loom-provider-usage/1");
    assert_eq!(record["agentId"], "agent-mock");
    assert_eq!(record["requestId"], "req-mock-1");
    assert_eq!(record["model"], FAST_MODEL);
    assert_eq!(record["promptTokens"], 100);
    assert_eq!(record["cachedPromptTokens"], 25);
    assert_eq!(record["completionTokens"], 20);
    assert_eq!(record["totalTokens"], 120);
    assert!(!text.contains(NOT_A_KEY));
    assert!(!text.contains(PROMPT_MARKER));
    assert!(!text.contains(&configured));
    fs::remove_file(file).expect("temporary usage file is removable");
}

#[tokio::test]
async fn configured_usage_refuses_a_success_response_that_omits_billing_counts() {
    let provider = MockProvider::start(Reply::completion(&good_answer())).await;
    let file = temporary_usage_file();
    let configured = file.to_string_lossy().into_owned();
    let mind = mind_for(&provider.base_url, &[(USAGE_LOG_VAR, &configured)]);
    let failed = mind
        .decide(&perception(DecisionImportance::Routine))
        .await
        .expect_err("configured accounting may not silently lose a provider call");
    assert!(failed.message.contains(USAGE_LOG_VAR));
    assert_eq!(provider.requests().len(), 1);
    drop(mind);
    assert_eq!(
        fs::read_to_string(&file).expect("the exclusive journal exists"),
        ""
    );
    fs::remove_file(file).expect("temporary usage file is removable");
}

#[tokio::test]
async fn the_serialized_request_bound_is_checked_before_any_provider_call() {
    let provider = MockProvider::start(Reply::completion(&good_answer())).await;
    let mind = mind_for(&provider.base_url, &[(MAX_REQUEST_BYTES_VAR, "1")]);
    let failed = mind
        .decide(&perception(DecisionImportance::Routine))
        .await
        .expect_err("the request cannot fit in one byte");
    assert!(failed.message.contains(MAX_REQUEST_BYTES_VAR));
    assert_eq!(
        provider.requests().len(),
        0,
        "a refused request must cost nothing"
    );
}

#[tokio::test]
async fn every_request_carries_the_credential_as_a_bearer_token() {
    let (provider, decided) = decide_with(
        Reply::completion(&good_answer()),
        DecisionImportance::Notable,
    )
    .await;
    decided.expect("a decision");
    let head = provider.only_request().head;
    assert!(
        head.contains("authorization: bearer "),
        "the credential must be sent as a bearer token, got {head}"
    );
    assert!(
        head.contains("content-type: application/json"),
        "got {head}"
    );
}

#[tokio::test]
async fn a_pivotal_perception_is_asked_of_the_deep_model() {
    let (provider, decided) = decide_with(
        Reply::completion(&good_answer()),
        DecisionImportance::Pivotal,
    )
    .await;
    let decision = decided.expect("a well-formed completion is a decision");
    assert_eq!(decision.provenance.model, DEEP_MODEL);
    assert_eq!(provider.only_request().json()["model"], DEEP_MODEL);
}

#[tokio::test]
async fn a_routine_perception_is_never_asked_of_the_deep_model() {
    let (provider, decided) = decide_with(
        Reply::completion(&good_answer()),
        DecisionImportance::Routine,
    )
    .await;
    assert_eq!(
        decided.expect("a decision").provenance.model,
        FAST_MODEL,
        "routine traffic reaching a frontier model is the failure routing exists to prevent"
    );
    assert_eq!(provider.only_request().json()["model"], FAST_MODEL);
}

#[tokio::test]
async fn a_rejected_credential_names_the_variable_to_check() {
    let (provider, decided) = decide_with(
        Reply::refused(401, "invalid_request_error", "invalid_api_key"),
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("a rejected credential is not a decision");
    assert!(failed.message.contains(API_KEY_VAR), "got {failed}");
    assert!(failed.message.contains("invalid_api_key"), "got {failed}");
    let classification = failed.failure().expect("OpenAI failures are classified");
    assert_eq!(
        classification.mapping,
        BackendFailureMapping::ProviderAuthentication
    );
    assert_eq!(
        classification.response_shape,
        BackendResponseShape::ApiErrorEnvelope
    );
    assert_eq!(classification.http_status, Some(401));
    assert_eq!(
        classification.provider_type.as_deref(),
        Some("invalid_request_error")
    );
    assert_eq!(
        classification.provider_code.as_deref(),
        Some("invalid_api_key")
    );
    assert_eq!(provider.requests().len(), 1);
}

#[tokio::test]
async fn a_structured_output_rejection_is_classified_without_its_message() {
    let (provider, decided) = decide_with(
        Reply::refused(400, "invalid_request_error", "invalid_json_schema"),
        DecisionImportance::Routine,
    )
    .await;
    let failed = decided.expect_err("a rejected request is not a decision");
    let classification = failed.failure().expect("OpenAI failures are classified");
    assert_eq!(classification.model, FAST_MODEL);
    assert_eq!(classification.http_status, Some(400));
    assert_eq!(
        classification.provider_type.as_deref(),
        Some("invalid_request_error")
    );
    assert_eq!(
        classification.provider_code.as_deref(),
        Some("invalid_json_schema")
    );
    assert_eq!(
        classification.response_shape,
        BackendResponseShape::ApiErrorEnvelope
    );
    assert_eq!(
        classification.mapping,
        BackendFailureMapping::ProviderRequest
    );
    assert!(!failed.message.contains(PROMPT_MARKER), "got {failed}");
    assert_eq!(provider.requests().len(), 1);
}

#[tokio::test]
async fn an_unknown_model_names_both_tier_settings() {
    let (_provider, decided) = decide_with(
        Reply::refused(404, "invalid_request_error", "model_not_found"),
        DecisionImportance::Pivotal,
    )
    .await;
    let failed = decided.expect_err("an unknown model is not a decision");
    assert!(failed.message.contains(DEEP_MODEL), "got {failed}");
    assert!(failed.message.contains(FAST_MODEL_VAR), "got {failed}");
    assert!(failed.message.contains(DEEP_MODEL_VAR), "got {failed}");
}

#[tokio::test]
async fn rate_limiting_is_reported_with_its_hint_and_the_perception_is_not_resent() {
    let provider = MockProvider::start(Reply::Http {
        status: 429,
        headers: &[("Retry-After", "20")],
        body: json!({ "error": { "type": "rate_limit_error" } }).to_string(),
    })
    .await;
    let mind = mind_for(&provider.base_url, &[]);
    let failed = mind
        .decide(&perception(DecisionImportance::Notable))
        .await
        .expect_err("a throttled request is not a decision");

    assert!(failed.message.contains("rate limiting"), "got {failed}");
    assert!(failed.message.contains("retry after 20"), "got {failed}");
    assert_eq!(
        provider.requests().len(),
        1,
        "a throttled perception must not be sent again: one perception is at most one inference"
    );
}

#[tokio::test]
async fn a_provider_outage_is_reported_as_the_provider_s_fault() {
    let (_provider, decided) = decide_with(
        Reply::refused(503, "server_error", "overloaded"),
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("a provider outage is not a decision");
    assert!(failed.message.contains("HTTP 503"), "got {failed}");
    assert!(failed.message.contains("overloaded"), "got {failed}");
}

#[tokio::test]
async fn a_provider_that_never_answers_fails_at_the_configured_timeout() {
    let provider = MockProvider::start(Reply::Silence).await;
    let mind = mind_for(&provider.base_url, &[(TIMEOUT_MS_VAR, "250")]);
    let failed = mind
        .decide(&perception(DecisionImportance::Notable))
        .await
        .expect_err("a silent provider is not a decision");
    assert!(failed.message.contains("250ms"), "got {failed}");
    assert!(failed.message.contains(TIMEOUT_MS_VAR), "got {failed}");
    assert_eq!(
        provider.requests().len(),
        1,
        "a timed-out perception must not be sent again"
    );
}

#[tokio::test]
async fn a_provider_nothing_is_listening_to_is_a_bounded_failure() {
    // A port that was bound and then released: nothing is listening, so the
    // connection is refused rather than left hanging.
    let closed = TcpListener::bind(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)))
        .await
        .expect("bind an ephemeral loopback port");
    let port = closed.local_addr().expect("port").port();
    drop(closed);

    let mind = mind_for(&format!("http://127.0.0.1:{port}"), &[]);
    let failed = mind
        .decide(&perception(DecisionImportance::Notable))
        .await
        .expect_err("an unreachable provider is not a decision");
    assert!(
        failed.message.contains("could not be reached")
            || failed.message.contains("did not answer"),
        "got {failed}"
    );
    assert!(!failed.message.contains(NOT_A_KEY), "got {failed}");
}

#[tokio::test]
async fn a_malformed_answer_is_reported_by_shape_and_position() {
    let (_provider, decided) = decide_with(
        Reply::completion(&json!({ "intent": { "action": "say", "text": "hello" } })),
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("an answer with no reasoning is not a decision");
    assert!(failed.message.contains("wrong shape"), "got {failed}");
}

#[tokio::test]
async fn a_body_that_is_not_a_completion_at_all_says_so() {
    let (_provider, decided) = decide_with(
        Reply::ok(&json!({ "unexpected": PROMPT_MARKER })),
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("an unrecognised body is not a decision");
    assert!(
        failed.message.contains("not a chat completion"),
        "got {failed}"
    );
    let classification = failed.failure().expect("OpenAI failures are classified");
    assert_eq!(
        classification.response_shape,
        BackendResponseShape::OtherJson
    );
    assert_eq!(
        classification.mapping,
        BackendFailureMapping::ProviderResponseParse
    );
}

#[tokio::test]
async fn a_refusal_is_recorded_as_a_refusal_and_never_quoted() {
    let provider = MockProvider::start(Reply::ok(&json!({
        "choices": [{
            "finish_reason": "stop",
            "message": {
                "role": "assistant",
                "content": Value::Null,
                "refusal": format!("I will not discuss {PROMPT_MARKER}")
            }
        }],
        "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120
        }
    })))
    .await;
    let file = temporary_usage_file();
    let configured = file.to_string_lossy().into_owned();
    let mind = mind_for(&provider.base_url, &[(USAGE_LOG_VAR, &configured)]);
    let failed = mind
        .decide(&perception(DecisionImportance::Notable))
        .await
        .expect_err("a refusal is not a decision");
    assert!(failed.message.contains("declined"), "got {failed}");
    let classification = failed.failure().expect("OpenAI failures are classified");
    assert_eq!(
        classification.response_shape,
        BackendResponseShape::ChatCompletion
    );
    assert_eq!(
        classification.mapping,
        BackendFailureMapping::ProviderSafetyRefusal
    );
    drop(mind);
    assert!(
        !fs::read_to_string(&file)
            .expect("usage was made durable")
            .is_empty(),
        "a standard safety refusal is classified only after usage is durable"
    );
    fs::remove_file(file).expect("temporary usage file is removable");
}

#[tokio::test]
async fn a_cut_off_answer_names_the_token_setting() {
    let (_provider, decided) = decide_with(
        Reply::ok(&json!({
            "choices": [{
                "finish_reason": "length",
                "message": { "role": "assistant", "content": "{\"intent\":" }
            }]
        })),
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("a truncated answer is not a decision");
    assert!(
        failed.message.contains(MAX_OUTPUT_TOKENS_VAR),
        "got {failed}"
    );
}

#[tokio::test]
async fn a_response_larger_than_the_bound_is_refused_rather_than_buffered() {
    let (_provider, decided) = decide_with(
        Reply::Http {
            status: 200,
            headers: &[],
            body: "x".repeat(MAX_RESPONSE_BYTES + 1),
        },
        DecisionImportance::Notable,
    )
    .await;
    let failed = decided.expect_err("an oversized body is not a decision");
    assert!(
        failed.message.contains("exceeded") || failed.message.contains("could not be read"),
        "got {failed}"
    );
    assert!(
        !failed.message.contains("xxx"),
        "the body must not be echoed, got {failed}"
    );
}

#[tokio::test]
async fn no_failure_on_any_provider_path_carries_the_credential_or_the_prompt() {
    // The sweep: every way a provider can fail Loom, checked against both markers
    // at once. A new failure path belongs here as well as in its own test, since
    // this is the assertion that would catch a leak added later.
    let paths: Vec<(&str, Reply)> = vec![
        ("rejected credential", Reply::refused(401, "e", "c")),
        ("unknown model", Reply::refused(404, "e", "c")),
        ("refused request", Reply::refused(400, "e", "c")),
        ("throttled", Reply::refused(429, "e", "c")),
        ("provider outage", Reply::refused(500, "e", "c")),
        ("unexpected status", Reply::refused(418, "e", "c")),
        (
            "answer that is not a decision",
            Reply::completion(&json!({ "reasoning": PROMPT_MARKER })),
        ),
        (
            "body that is not a completion",
            Reply::ok(&json!({ "oops": NOT_A_KEY })),
        ),
        (
            "empty answer",
            Reply::ok(&json!({
                "choices": [{ "message": { "role": "assistant", "content": "" } }]
            })),
        ),
        ("silence", Reply::Silence),
    ];

    for (what, reply) in paths {
        let provider = MockProvider::start(reply).await;
        let mind = mind_for(&provider.base_url, &[(TIMEOUT_MS_VAR, "250")]);
        let failed = mind
            .decide(&perception(DecisionImportance::Pivotal))
            .await
            .expect_err(what);
        assert!(
            !failed.message.contains(NOT_A_KEY),
            "the credential leaked through the {what} path: {failed}"
        );
        assert!(
            !failed.message.contains(PROMPT_MARKER),
            "the prompt leaked through the {what} path: {failed}"
        );
        assert!(
            !failed.message.contains('\n'),
            "a refusal frame is one line, and the {what} path broke that: {failed}"
        );
    }
}
