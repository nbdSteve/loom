//! The `OpenAI` backend: the first [`ModelBackend`] that actually thinks.
//!
//! Three files, each with one job. [`settings`] is what an operator configures
//! and the one place the credential lives. [`wire`] is the bytes, including the
//! strict schema that makes an answer decision-shaped. This file is the request:
//! render, post, classify what came back.
//!
//! Everything above the seam is unchanged by this module existing. Loom's own
//! judgement about what a decision is worth stays in [`crate::router`], the words
//! stay in [`crate::prompt`], and provenance is still stamped by
//! [`crate::mind::Mind`] from the route - so a decision cannot claim a model it
//! was not routed to even though a model is now really answering.
//!
//! **Every failure here is bounded, and none of them carries the prompt or the
//! credential.** That is not a habit but the module's main design constraint, and
//! it is what the failure-classification code below is for:
//!
//! - The credential exists only as an already-sensitive header value
//!   ([`settings::ApiKey`]), set once as a client default header, so no request
//!   code touches it and no `Debug` prints it.
//! - The provider's error `message` - the one field that can quote back what was
//!   sent - is not deserialized at all. Only its `type` and `code` are repeated,
//!   bounded.
//! - A malformed answer is reported as a category and a position, never as text,
//!   because a model answer may contain the perception it was given.
//! - A `reqwest` error's own `Display` is not used for a request that reached the
//!   provider. It is used only for the residual "something else went wrong" case,
//!   and then only its innermost cause, which is where DNS and TLS detail lives
//!   and where request data does not.
//!
//! **The response read is bounded** ([`MAX_RESPONSE_BYTES`]), and it is the outer
//! bound on everything an answer can cost: a provider that streamed forever would
//! otherwise grow the mind's memory without limit while one agent waited.
//!
//! **Nothing here retries.** Delivery to a model is at-most-once by design: the
//! body's `TcpCognitionTransport` retries only up to the point where its request
//! is written, precisely so that one perception can never be billed as two
//! inferences. A retry inside this module would reintroduce exactly that, one
//! layer lower and invisibly. A lost decision is cheaper than a duplicated one.

pub mod settings;
pub mod wire;

use std::time::Duration;

use loom_contract::Perception;
use reqwest::header::{HeaderMap, AUTHORIZATION, RETRY_AFTER};
use reqwest::{Response, StatusCode};

use crate::config::{bounded, ConfigError};
use crate::model::{BackendError, Draft, ModelBackend};
use crate::prompt::render;
use crate::router::ModelRoute;

pub use settings::{ApiKey, OpenAiConfig};

/// Most of a provider response Loom will read, in bytes.
///
/// A decision is a few hundred bytes; an error body is smaller. 256 KiB is the
/// same order as the service's own request-line bound, and the point of both is
/// the same: no single peer may decide how much memory the mind holds.
pub const MAX_RESPONSE_BYTES: usize = 256 * 1024;

/// Longest to wait for the connection itself.
///
/// Short and separate from the request timeout, so an unreachable provider is a
/// quick, clear failure rather than one that consumes the whole budget an agent
/// was waiting on. Capped by the request timeout, since a connect budget larger
/// than the whole budget would be meaningless.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

/// `finish_reason` meaning the answer was cut off at the token bound.
const CUT_OFF: &str = "length";

/// Drafts decisions by asking `OpenAI`.
#[derive(Debug)]
pub struct OpenAiBackend {
    http: reqwest::Client,
    config: OpenAiConfig,
}

impl OpenAiBackend {
    /// Builds the backend for `config`.
    ///
    /// The credential is installed once here, as a default header already marked
    /// sensitive. Request code below therefore never names it, and cannot leak it
    /// by forgetting to.
    ///
    /// # Errors
    ///
    /// [`ConfigError::Unusable`] if an HTTP client cannot be built - in practice a
    /// TLS backend that will not initialise, which is a host problem worth failing
    /// startup for rather than discovering on the first decision.
    pub fn new(config: OpenAiConfig) -> Result<Self, ConfigError> {
        let mut headers = HeaderMap::new();
        headers.insert(AUTHORIZATION, config.api_key().header());
        let http = reqwest::Client::builder()
            .timeout(config.request_timeout())
            .connect_timeout(CONNECT_TIMEOUT.min(config.request_timeout()))
            .default_headers(headers)
            .user_agent(concat!("loom-cognition/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| ConfigError::Unusable {
                what: "the OpenAI HTTP client",
                reason: root_cause(&e),
            })?;
        Ok(Self { http, config })
    }

    /// The configuration this backend was built with.
    #[must_use]
    pub const fn config(&self) -> &OpenAiConfig {
        &self.config
    }

    /// Asks the routed model for one decision.
    async fn ask(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> Result<Draft, BackendError> {
        let prompt = render(route, perception);
        let request = wire::chat_request(route.model(), &prompt, self.config.max_output_tokens());
        let response = self
            .http
            .post(self.config.chat_completions_url())
            .json(&request)
            .send()
            .await
            .map_err(|e| self.transport_failure(&e))?;
        let status = response.status();
        let retry_after = header_hint(&response, RETRY_AFTER.as_str());
        let body = self.read_bounded(response).await?;
        if !status.is_success() {
            return Err(status_failure(
                route.model(),
                status,
                &body,
                retry_after.as_deref(),
            ));
        }
        self.draft_from_body(&body)
    }

    /// Reads a response body, refusing one that will not fit.
    ///
    /// Accumulates chunk by chunk against the cap rather than reading the whole
    /// body and checking afterwards, which is what makes the bound real: a body
    /// that never ends is refused instead of being buffered first. A declared
    /// `Content-Length` is not trusted for this, because it is the peer's claim.
    async fn read_bounded(&self, mut response: Response) -> Result<Vec<u8>, BackendError> {
        let mut body: Vec<u8> = Vec::new();
        loop {
            let chunk = response
                .chunk()
                .await
                .map_err(|e| self.transport_failure(&e))?;
            let Some(chunk) = chunk else { return Ok(body) };
            if body.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
                return Err(BackendError::new(format!(
                    "the model provider's response exceeded {MAX_RESPONSE_BYTES} bytes"
                )));
            }
            body.extend_from_slice(&chunk);
        }
    }

    /// Turns a successful response body into a draft.
    fn draft_from_body(&self, body: &[u8]) -> Result<Draft, BackendError> {
        let parsed: wire::ChatResponse = serde_json::from_slice(body).map_err(|e| {
            BackendError::new(format!(
                "the model provider's answer was not a chat completion ({detail})",
                detail = wire::describe_json_error(&e)
            ))
        })?;
        let choice = parsed
            .choices
            .into_iter()
            .next()
            .ok_or_else(|| BackendError::new("the model provider returned no answer"))?;
        if choice.message.refusal.is_some() {
            // The refusal's own words are model output that can quote the
            // perception back, so the fact is recorded and the text is not.
            return Err(BackendError::new(
                "the model declined to answer this perception",
            ));
        }
        if choice.finish_reason.as_deref() == Some(CUT_OFF) {
            return Err(BackendError::new(format!(
                "the model's answer was cut off at {limit} tokens; raise {var}",
                limit = self.config.max_output_tokens(),
                var = settings::MAX_OUTPUT_TOKENS_VAR
            )));
        }
        let content = choice
            .message
            .content
            .filter(|text| !text.trim().is_empty())
            .ok_or_else(|| BackendError::new("the model answered with nothing"))?;
        wire::draft_from_answer(&content).map_err(BackendError::new)
    }

    /// Why a request never produced a response, in Loom's own words.
    fn transport_failure(&self, e: &reqwest::Error) -> BackendError {
        let url = self.config.chat_completions_url();
        if e.is_timeout() {
            return BackendError::new(format!(
                "the model provider at {url} did not answer within {millis}ms; raise {var}",
                millis = self.config.request_timeout().as_millis(),
                var = settings::TIMEOUT_MS_VAR
            ));
        }
        if e.is_connect() {
            return BackendError::new(format!(
                "the model provider at {url} could not be reached ({cause})",
                cause = root_cause(e)
            ));
        }
        if e.is_body() || e.is_decode() {
            return BackendError::new(format!(
                "the model provider at {url} sent a response that could not be read"
            ));
        }
        BackendError::new(format!(
            "the request to the model provider at {url} failed ({cause})",
            cause = root_cause(e)
        ))
    }
}

impl ModelBackend for OpenAiBackend {
    async fn draft(
        &self,
        route: &ModelRoute,
        perception: &Perception,
    ) -> Result<Draft, BackendError> {
        self.ask(route, perception).await
    }
}

/// Why a response Loom did receive is not a decision.
///
/// Each case names the setting an operator would change, because a provider
/// status on its own is not actionable and looking one up is work every operator
/// would otherwise repeat.
///
/// Free of the backend on purpose: what an HTTP status means does not depend on
/// how Loom was configured, only on what the provider said. That is what lets a
/// test pin every case without building a client.
fn status_failure(
    model: &str,
    status: StatusCode,
    body: &[u8],
    retry_after: Option<&str>,
) -> BackendError {
    let code = status.as_u16();
    let told = serde_json::from_slice::<wire::ApiErrorEnvelope>(body)
        .ok()
        .and_then(|envelope| envelope.error.identifiers())
        .map_or_else(String::new, |identifiers| format!(", {identifiers}"));
    let message = match code {
        401 | 403 => format!(
            "the model provider rejected Loom's credential (HTTP {code}{told}); check {var}",
            var = settings::API_KEY_VAR
        ),
        404 => format!(
            "the model provider does not know the model '{model}' (HTTP 404{told}); check {fast} and {deep}",
            model = bounded(model),
            fast = settings::FAST_MODEL_VAR,
            deep = settings::DEEP_MODEL_VAR
        ),
        429 => format!(
            "the model provider is rate limiting Loom (HTTP 429{told}){after}; this decision was not made",
            after = retry_after.map_or_else(String::new, |after| format!(", retry after {after}"))
        ),
        400 | 422 => format!(
            "the model provider refused Loom's request for '{model}' (HTTP {code}{told})",
            model = bounded(model)
        ),
        500..=599 => format!("the model provider failed (HTTP {code}{told})"),
        _ => format!("the model provider answered HTTP {code}{told}"),
    };
    BackendError::new(message)
}

/// A response header's value, bounded, if it is present and printable.
///
/// Header values are the provider's, so this goes through [`bounded`] like any
/// other outside string before it can reach a log line or a refusal frame.
fn header_hint(response: &Response, name: &str) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(bounded)
}

/// The innermost cause of `e`, bounded.
///
/// Where the useful detail in a failed HTTP request lives - "dns error", "invalid
/// peer certificate" - and where request data does not: the chain below `reqwest`
/// is transport, so it has never seen the prompt or the credential.
fn root_cause(e: &(impl std::error::Error + 'static)) -> String {
    let mut deepest: &dyn std::error::Error = e;
    while let Some(next) = deepest.source() {
        deepest = next;
    }
    bounded(&deepest.to_string())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{status_failure, OpenAiBackend, OpenAiConfig};
    use crate::config::Env;
    use crate::openai::settings::API_KEY_VAR;

    /// Not a credential. See the note in `settings`' tests.
    const NOT_A_KEY: &str = "test-key-not-a-secret";

    fn backend() -> OpenAiBackend {
        let env = Env::new(|name| (name == API_KEY_VAR).then(|| NOT_A_KEY.to_owned()));
        OpenAiBackend::new(OpenAiConfig::from_env(&env).unwrap()).unwrap()
    }

    #[test]
    fn a_backend_can_be_built_from_nothing_but_a_credential() {
        let backend = backend();
        assert_eq!(
            backend.config().chat_completions_url(),
            "https://api.openai.com/v1/chat/completions"
        );
    }

    #[test]
    fn the_credential_does_not_survive_a_debug_of_the_backend() {
        assert!(!format!("{:?}", backend()).contains(NOT_A_KEY));
    }

    #[test]
    fn a_no_choices_response_is_a_bounded_failure_rather_than_a_panic() {
        let failed = backend()
            .draft_from_body(br#"{"choices":[]}"#)
            .expect_err("no answer is not a decision");
        assert_eq!(failed.message, "the model provider returned no answer");
    }

    #[test]
    fn a_refusal_is_reported_as_a_refusal_and_never_quoted() {
        let body = br#"{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I will not discuss diamond_ore at 10.5 64.0"}}]}"#;
        let failed = backend()
            .draft_from_body(body)
            .expect_err("a refusal is not a decision");
        assert!(!failed.message.contains("diamond_ore"), "got {failed}");
        assert!(failed.message.contains("declined"), "got {failed}");
    }

    #[test]
    fn a_cut_off_answer_names_the_setting_that_would_fix_it() {
        let body =
            br#"{"choices":[{"finish_reason":"length","message":{"content":"{\"intent\":"}}]}"#;
        let failed = backend()
            .draft_from_body(body)
            .expect_err("a truncated answer is not a decision");
        assert!(
            failed.message.contains("LOOM_OPENAI_MAX_OUTPUT_TOKENS"),
            "got {failed}"
        );
    }

    #[test]
    fn a_well_formed_answer_becomes_a_draft() {
        let body = br#"{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"intent\":{\"action\":\"idle\"},\"reasoning\":\"nothing to do\"}"}}]}"#;
        let draft = backend().draft_from_body(body).unwrap();
        assert_eq!(draft.intent, loom_contract::Intent::Idle);
        assert_eq!(draft.reasoning, "nothing to do");
    }

    #[test]
    fn a_body_that_is_not_a_completion_is_reported_by_shape_not_by_content() {
        let failed = backend()
            .draft_from_body(br#"{"unexpected":"sk-lookalike"}"#)
            .expect_err("not a chat completion");
        assert!(!failed.message.contains("sk-lookalike"), "got {failed}");
        assert!(failed.message.contains("wrong shape"), "got {failed}");
    }

    #[test]
    fn each_provider_status_names_the_setting_an_operator_would_change() {
        use reqwest::StatusCode;
        let unauthorised = status_failure(
            "some-model",
            StatusCode::UNAUTHORIZED,
            br#"{"error":{"message":"Incorrect API key provided: sk-abc","type":"invalid_request_error","code":"invalid_api_key"}}"#,
            None,
        );
        assert!(
            unauthorised.message.contains(API_KEY_VAR),
            "got {unauthorised}"
        );
        assert!(unauthorised.message.contains("invalid_api_key"));
        assert!(
            !unauthorised.message.contains("sk-abc"),
            "the provider's message must not be repeated, got {unauthorised}"
        );

        let unknown_model = status_failure("no-such-model", StatusCode::NOT_FOUND, b"{}", None);
        assert!(unknown_model.message.contains("no-such-model"));
        assert!(unknown_model.message.contains("LOOM_OPENAI_FAST_MODEL"));

        let throttled = status_failure(
            "some-model",
            StatusCode::TOO_MANY_REQUESTS,
            br#"{"error":{"type":"rate_limit_error"}}"#,
            Some("20"),
        );
        assert!(
            throttled.message.contains("rate limiting"),
            "got {throttled}"
        );
        assert!(throttled.message.contains("rate_limit_error"));
        assert!(throttled.message.contains("retry after 20"));

        let broken = status_failure("some-model", StatusCode::BAD_GATEWAY, b"", None);
        assert!(broken.message.contains("HTTP 502"), "got {broken}");
    }

    #[test]
    fn a_status_failure_reports_even_when_the_error_body_is_unreadable() {
        let failed = status_failure(
            "some-model",
            reqwest::StatusCode::IM_A_TEAPOT,
            b"<html>not json at all</html>",
            None,
        );
        assert!(failed.message.contains("HTTP 418"), "got {failed}");
        assert!(!failed.message.contains("html"), "got {failed}");
    }
}
