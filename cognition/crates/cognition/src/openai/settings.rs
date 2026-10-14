//! What the `OpenAI` backend needs to be told, and where it reads it from.
//!
//! Everything here is read once, at startup, and a mistake stops the service -
//! see [`crate::config`] for why. The settings themselves are listed for
//! operators in `cognition/README.md`; this module is the authority on their
//! names, their defaults, and what is refused.
//!
//! The credential gets its own type. [`ApiKey`] has a hand-written [`fmt::Debug`]
//! that prints a placeholder, so the ordinary ways a secret escapes - a `dbg!`, a
//! `{:?}` in an error, a derived `Debug` on a struct that happens to contain it -
//! cannot print this one. Reaching the actual bytes takes calling
//! [`ApiKey::header`], which exists to build one header and is used in exactly
//! one place.

use std::fmt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use reqwest::header::{HeaderValue, InvalidHeaderValue};

use crate::config::{bounded, ConfigError, Env};
use crate::router::ModelCatalog;

/// Environment variable carrying the `OpenAI` credential.
///
/// Named as `OpenAI`'s own tooling names it, so a host that already has a working
/// `OPENAI_API_KEY` needs nothing else.
pub const API_KEY_VAR: &str = "OPENAI_API_KEY";

/// Environment variable overriding the API root.
pub const BASE_URL_VAR: &str = "LOOM_OPENAI_BASE_URL";

/// Environment variable naming the model that answers routine and notable
/// decisions.
pub const FAST_MODEL_VAR: &str = "LOOM_OPENAI_FAST_MODEL";

/// Environment variable naming the model that answers pivotal decisions.
pub const DEEP_MODEL_VAR: &str = "LOOM_OPENAI_DEEP_MODEL";

/// Environment variable bounding how long one provider call may take.
pub const TIMEOUT_MS_VAR: &str = "LOOM_OPENAI_TIMEOUT_MS";

/// Environment variable bounding how many tokens one answer may cost.
pub const MAX_OUTPUT_TOKENS_VAR: &str = "LOOM_OPENAI_MAX_OUTPUT_TOKENS";

/// Environment variable bounding the serialized provider request.
pub const MAX_REQUEST_BYTES_VAR: &str = "LOOM_OPENAI_MAX_REQUEST_BYTES";

/// Optional append-only destination for provider-reported token usage.
pub const USAGE_LOG_VAR: &str = "LOOM_OPENAI_USAGE_LOG";

/// Default API root.
pub const DEFAULT_BASE_URL: &str = "https://api.openai.com/v1";

/// Default model for [`crate::router::ModelClass::Fast`].
///
/// A small model, because it answers nearly every decision an agent takes. It is
/// a default rather than a choice baked into the code: [`FAST_MODEL_VAR`]
/// overrides it, and the routing rule that sends routine traffic here does not
/// know or care which model it is.
pub const DEFAULT_FAST_MODEL: &str = "gpt-5-mini";

/// Default model for [`crate::router::ModelClass::Deep`].
///
/// The larger model, for the decisions an agent may not survive. Loom is
/// permadeath, so this is the one tier where the expensive answer is the cheap
/// one.
pub const DEFAULT_DEEP_MODEL: &str = "gpt-5";

/// Default bound on one provider call, in milliseconds.
///
/// Chosen against the JVM side rather than in isolation. The body's bridge gives
/// each request a deadline and derives a shorter socket read timeout from it, so
/// the ordering that has to hold is:
///
/// > this timeout < the body's `readTimeout` < the body's bridge `timeout`
///
/// With `dev.loom.mind.CognitionSettings`' defaults that middle number is 14.5s,
/// so 12s leaves the mind able to answer "the provider did not reply" - a
/// bounded, actionable failure - instead of having the body's socket give up
/// first and report the less specific "the mind did not reply". Raising this
/// means raising both of those.
pub const DEFAULT_TIMEOUT_MS: u32 = 12_000;

/// Default bound on the tokens one answer may cost.
///
/// A decision is small: one intent and a sentence of reasoning. The reason this
/// is not tighter is that reasoning models spend hidden reasoning tokens out of
/// the same budget, and running out shows up as a truncated answer rather than as
/// a clear error, so the bound is set well above the visible output a decision
/// needs. If answers start failing as cut off, this is the setting to raise.
pub const DEFAULT_MAX_OUTPUT_TOKENS: u32 = 2_048;

/// Default byte bound on one serialized provider request.
///
/// The 144-observation perception wire remains far below this measured ceiling.
/// Keeping a separate bound here covers the instructions and strict response schema
/// too, and refuses a future renderer that grows without making a provider call first.
pub const DEFAULT_MAX_REQUEST_BYTES: u32 = 256 * 1024;

/// The `OpenAI` credential.
///
/// Constructed from configuration and never displayed. There is deliberately no
/// `Display`, no `Serialize`, and no accessor returning the raw string.
#[derive(Clone)]
pub struct ApiKey {
    header: HeaderValue,
}

impl ApiKey {
    /// Wraps `key` as a bearer credential.
    ///
    /// # Errors
    ///
    /// [`InvalidHeaderValue`] if the key is not something an HTTP header can
    /// carry. The error carries no part of the key, which is why it is returned
    /// as the header crate's own type rather than re-wrapped with context.
    pub fn new(key: &str) -> Result<Self, InvalidHeaderValue> {
        let mut header = HeaderValue::from_str(&format!("Bearer {key}"))?;
        // Marks the value as one that must not be printed. `HeaderValue`'s own
        // `Debug` honours this, so a sensitive header cannot leak through a
        // `{:?}` of the client, a request, or this struct.
        header.set_sensitive(true);
        Ok(Self { header })
    }

    /// The `Authorization` header value.
    ///
    /// The only way to the credential, and it hands out something already marked
    /// sensitive rather than a `String` that could be formatted anywhere.
    #[must_use]
    pub fn header(&self) -> HeaderValue {
        self.header.clone()
    }
}

impl fmt::Debug for ApiKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ApiKey(<redacted>)")
    }
}

/// Everything the `OpenAI` backend was configured with.
#[derive(Clone)]
pub struct OpenAiConfig {
    api_key: ApiKey,
    chat_completions_url: String,
    catalog: ModelCatalog,
    request_timeout: Duration,
    max_output_tokens: u32,
    max_request_bytes: u32,
    usage_log: Option<PathBuf>,
}

impl fmt::Debug for OpenAiConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OpenAiConfig")
            .field("api_key", &self.api_key)
            .field("chat_completions_url", &self.chat_completions_url)
            .field("catalog", &self.catalog)
            .field("request_timeout", &self.request_timeout)
            .field("max_output_tokens", &self.max_output_tokens)
            .field("max_request_bytes", &self.max_request_bytes)
            .field("usage_log_configured", &self.usage_log.is_some())
            .finish()
    }
}

impl OpenAiConfig {
    /// Reads the configuration from the process environment.
    ///
    /// # Errors
    ///
    /// [`ConfigError`] naming the variable at fault. Nothing here retries or
    /// guesses: an operator asked for a real model, and being told exactly which
    /// setting is wrong is the only useful outcome.
    pub fn from_process_env() -> Result<Self, ConfigError> {
        Self::from_env(&crate::config::process_env())
    }

    /// Reads the configuration from `env`.
    ///
    /// # Errors
    ///
    /// As [`OpenAiConfig::from_process_env`].
    pub fn from_env<F>(env: &Env<F>) -> Result<Self, ConfigError>
    where
        F: Fn(&str) -> Option<String>,
    {
        let key = env.required(API_KEY_VAR)?;
        let api_key = ApiKey::new(&key).map_err(|_| ConfigError::Unusable {
            what: "the OpenAI credential",
            // Deliberately says nothing about the value. A credential with a
            // stray character in it is still a credential.
            reason: format!("{API_KEY_VAR} contains characters an HTTP header cannot carry"),
        })?;
        let base_url = env.or_default(BASE_URL_VAR, DEFAULT_BASE_URL)?;
        let base_url = checked_base_url(&base_url)?;
        Ok(Self {
            api_key,
            chat_completions_url: format!("{base_url}/chat/completions"),
            catalog: ModelCatalog::new(
                &env.or_default(FAST_MODEL_VAR, DEFAULT_FAST_MODEL)?,
                &env.or_default(DEEP_MODEL_VAR, DEFAULT_DEEP_MODEL)?,
            ),
            request_timeout: env.positive_millis(TIMEOUT_MS_VAR, DEFAULT_TIMEOUT_MS)?,
            max_output_tokens: env
                .positive_u32(MAX_OUTPUT_TOKENS_VAR, DEFAULT_MAX_OUTPUT_TOKENS)?,
            max_request_bytes: env
                .positive_u32(MAX_REQUEST_BYTES_VAR, DEFAULT_MAX_REQUEST_BYTES)?,
            usage_log: env.optional(USAGE_LOG_VAR)?.map(PathBuf::from),
        })
    }

    /// The credential.
    #[must_use]
    pub const fn api_key(&self) -> &ApiKey {
        &self.api_key
    }

    /// The endpoint one decision is asked of.
    #[must_use]
    pub fn chat_completions_url(&self) -> &str {
        &self.chat_completions_url
    }

    /// The configured model ids, by class.
    ///
    /// Handed to a [`crate::router::Router`] at startup, which is how a class
    /// becomes a concrete model without the domain naming one.
    #[must_use]
    pub const fn catalog(&self) -> &ModelCatalog {
        &self.catalog
    }

    /// How long one provider call may take.
    #[must_use]
    pub const fn request_timeout(&self) -> Duration {
        self.request_timeout
    }

    /// How many tokens one answer may cost.
    #[must_use]
    pub const fn max_output_tokens(&self) -> u32 {
        self.max_output_tokens
    }

    /// Largest serialized provider request that may be sent, in UTF-8 bytes.
    #[must_use]
    pub const fn max_request_bytes(&self) -> u32 {
        self.max_request_bytes
    }

    /// Append-only usage destination, when an experiment requested one.
    #[must_use]
    pub fn usage_log(&self) -> Option<&Path> {
        self.usage_log.as_deref()
    }
}

/// Normalises a configured API root and refuses one that would send the
/// credential in the clear.
///
/// Plain HTTP is allowed only to a loopback host, which is what makes the mock
/// provider in this crate's tests possible without also making "point Loom at a
/// plaintext proxy on the internet" a supported configuration. Every request
/// carries a bearer token, so an unencrypted hop to anywhere else would hand the
/// credential to whatever is in between.
///
/// User info in the authority (the `user:pass@` a URL may carry before the host)
/// is refused under both schemes.
/// Loom's base URL never needs credentials in the URL, and the only effect of
/// allowing them would be to make the host a reader sees differ from the host
/// reqwest connects to: `https://api.openai.com@evil.com/v1` ships the bearer
/// token to evil.com while looking correct in a config file, and
/// `http://127.0.0.1@evil.com/v1` slips past the loopback check the same way.
/// Refusing it outright is smaller and more durable than teaching the host check
/// to parse around it.
///
/// The check is written by hand rather than through a URL parser because it is
/// the whole of what this crate needs from a URL: a scheme, and - for the one
/// scheme that needs it - a host. A parser would be a dependency carried for a
/// three-line question.
fn checked_base_url(configured: &str) -> Result<String, ConfigError> {
    let trimmed = configured.trim_end_matches('/');
    let refuse = || ConfigError::NotAChoice {
        var: BASE_URL_VAR,
        value: bounded(trimmed),
        accepted: "an https:// URL, or an http:// URL on loopback, with no user info",
    };
    if let Some(rest) = trimmed.strip_prefix("https://") {
        return if rest.is_empty() || authority_has_userinfo(rest) {
            Err(refuse())
        } else {
            Ok(trimmed.to_owned())
        };
    }
    if let Some(rest) = trimmed.strip_prefix("http://") {
        return if authority_has_userinfo(rest) || !is_loopback_authority(rest) {
            Err(refuse())
        } else {
            Ok(trimmed.to_owned())
        };
    }
    Err(refuse())
}

/// Whether the authority at the start of `rest` carries user info.
///
/// The authority is everything before the first `/`; an `@` anywhere in it is the
/// `user:pass@` separator, which is refused rather than parsed - see
/// [`checked_base_url`].
fn authority_has_userinfo(rest: &str) -> bool {
    rest.split('/').next().unwrap_or(rest).contains('@')
}

/// Whether the authority at the start of `rest` names this host.
fn is_loopback_authority(rest: &str) -> bool {
    let authority = rest.split('/').next().unwrap_or(rest);
    let host = match authority.strip_prefix('[') {
        // Bracketed IPv6: everything up to the closing bracket is the host.
        Some(inside) => match inside.split_once(']') {
            Some((address, _port)) => address,
            None => return false,
        },
        None => authority.split(':').next().unwrap_or(authority),
    };
    matches!(host, "localhost" | "127.0.0.1" | "::1")
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        checked_base_url, ApiKey, OpenAiConfig, API_KEY_VAR, BASE_URL_VAR, DEEP_MODEL_VAR,
        DEFAULT_DEEP_MODEL, DEFAULT_FAST_MODEL, DEFAULT_MAX_OUTPUT_TOKENS,
        DEFAULT_MAX_REQUEST_BYTES, DEFAULT_TIMEOUT_MS, FAST_MODEL_VAR, MAX_OUTPUT_TOKENS_VAR,
        MAX_REQUEST_BYTES_VAR, TIMEOUT_MS_VAR, USAGE_LOG_VAR,
    };
    use crate::config::{ConfigError, Env};
    use crate::router::ModelClass;
    use std::time::Duration;

    fn env_of(
        pairs: &'static [(&'static str, &'static str)],
    ) -> Env<impl Fn(&str) -> Option<String>> {
        Env::new(move |name| {
            pairs
                .iter()
                .find(|(key, _)| *key == name)
                .map(|(_, value)| (*value).to_owned())
        })
    }

    /// Not a credential: 20 characters of nothing, so this file contains no
    /// secret and never needs one to test every path here.
    const NOT_A_KEY: &str = "test-key-not-a-secret";

    #[test]
    fn a_missing_credential_stops_the_service_and_names_the_variable() {
        assert_eq!(
            OpenAiConfig::from_env(&env_of(&[])).err(),
            Some(ConfigError::Missing { var: API_KEY_VAR })
        );
    }

    #[test]
    fn the_defaults_are_the_documented_ones() {
        let config = OpenAiConfig::from_env(&env_of(&[(API_KEY_VAR, NOT_A_KEY)])).unwrap();
        assert_eq!(
            config.chat_completions_url(),
            "https://api.openai.com/v1/chat/completions"
        );
        assert_eq!(config.catalog().model(ModelClass::Fast), DEFAULT_FAST_MODEL);
        assert_eq!(config.catalog().model(ModelClass::Deep), DEFAULT_DEEP_MODEL);
        assert_eq!(
            config.request_timeout(),
            Duration::from_millis(u64::from(DEFAULT_TIMEOUT_MS))
        );
        assert_eq!(config.max_output_tokens(), DEFAULT_MAX_OUTPUT_TOKENS);
        assert_eq!(config.max_request_bytes(), DEFAULT_MAX_REQUEST_BYTES);
        assert_eq!(config.usage_log(), None);
    }

    #[test]
    fn both_model_tiers_are_configurable_independently() {
        let config = OpenAiConfig::from_env(&env_of(&[
            (API_KEY_VAR, NOT_A_KEY),
            (FAST_MODEL_VAR, "some-small-model"),
            (DEEP_MODEL_VAR, "some-large-model"),
        ]))
        .unwrap();
        assert_eq!(config.catalog().model(ModelClass::Fast), "some-small-model");
        assert_eq!(config.catalog().model(ModelClass::Deep), "some-large-model");
    }

    #[test]
    fn the_bounds_are_configurable_and_refuse_nonsense() {
        let config = OpenAiConfig::from_env(&env_of(&[
            (API_KEY_VAR, NOT_A_KEY),
            (TIMEOUT_MS_VAR, "1500"),
            (MAX_OUTPUT_TOKENS_VAR, "64"),
            (MAX_REQUEST_BYTES_VAR, "65536"),
            (USAGE_LOG_VAR, "usage.jsonl"),
        ]))
        .unwrap();
        assert_eq!(config.request_timeout(), Duration::from_millis(1_500));
        assert_eq!(config.max_output_tokens(), 64);
        assert_eq!(config.max_request_bytes(), 65_536);
        assert_eq!(
            config.usage_log(),
            Some(std::path::Path::new("usage.jsonl"))
        );
        assert!(!format!("{config:?}").contains("usage.jsonl"));

        assert!(matches!(
            OpenAiConfig::from_env(&env_of(&[(API_KEY_VAR, NOT_A_KEY), (TIMEOUT_MS_VAR, "0")])),
            Err(ConfigError::NotAPositiveNumber { .. })
        ));
        assert!(matches!(
            OpenAiConfig::from_env(&env_of(&[
                (API_KEY_VAR, NOT_A_KEY),
                (MAX_OUTPUT_TOKENS_VAR, "none")
            ])),
            Err(ConfigError::NotAPositiveNumber { .. })
        ));
        assert!(matches!(
            OpenAiConfig::from_env(&env_of(&[
                (API_KEY_VAR, NOT_A_KEY),
                (MAX_REQUEST_BYTES_VAR, "0")
            ])),
            Err(ConfigError::NotAPositiveNumber { .. })
        ));
        assert_eq!(
            OpenAiConfig::from_env(&env_of(&[(API_KEY_VAR, NOT_A_KEY), (USAGE_LOG_VAR, " ")]))
                .unwrap_err(),
            ConfigError::Blank { var: USAGE_LOG_VAR }
        );
    }

    #[test]
    fn a_base_url_that_would_send_the_credential_in_the_clear_is_refused() {
        assert!(matches!(
            OpenAiConfig::from_env(&env_of(&[
                (API_KEY_VAR, NOT_A_KEY),
                (BASE_URL_VAR, "http://api.openai.example/v1")
            ])),
            Err(ConfigError::NotAChoice { .. })
        ));
    }

    #[test]
    fn plain_http_is_allowed_only_to_this_host() {
        for allowed in [
            "http://127.0.0.1:8080/v1",
            "http://localhost:1234",
            "http://[::1]:9/v1",
        ] {
            assert!(
                checked_base_url(allowed).is_ok(),
                "{allowed} is loopback and must be usable, so the tests can run a mock provider"
            );
        }
        for refused in [
            "http://example.com/v1",
            "http://127.0.0.1.example.com/v1",
            "http://[::1/v1",
            "ftp://example.com",
            "api.openai.com/v1",
            "https://",
            "http://127.0.0.1:80@evil.com/v1",
            "http://localhost@evil.com/v1",
            "http://[::1]@evil.com/v1",
            "https://api.openai.com@evil.com/v1",
        ] {
            assert!(
                checked_base_url(refused).is_err(),
                "{refused} must be refused rather than quietly used"
            );
        }
    }

    #[test]
    fn a_trailing_slash_does_not_produce_a_doubled_path() {
        let config = OpenAiConfig::from_env(&env_of(&[
            (API_KEY_VAR, NOT_A_KEY),
            (BASE_URL_VAR, "https://api.openai.example/v1/"),
        ]))
        .unwrap();
        assert_eq!(
            config.chat_completions_url(),
            "https://api.openai.example/v1/chat/completions"
        );
    }

    #[test]
    fn a_credential_is_never_printed_by_debug() {
        let key = ApiKey::new(NOT_A_KEY).unwrap();
        let printed = format!("{key:?} {:?}", key.header());
        assert!(
            !printed.contains(NOT_A_KEY),
            "a credential must not survive a Debug, got {printed}"
        );
        let config = OpenAiConfig::from_env(&env_of(&[(API_KEY_VAR, NOT_A_KEY)])).unwrap();
        assert!(
            !format!("{config:?}").contains(NOT_A_KEY),
            "a credential must not survive a Debug of the configuration either"
        );
    }

    #[test]
    fn a_credential_an_http_header_cannot_carry_is_refused_without_quoting_it() {
        let refused = OpenAiConfig::from_env(&env_of(&[(API_KEY_VAR, "key\u{7f}with-a-control")]));
        let ConfigError::Unusable { reason, .. } = refused.expect_err("must be refused") else {
            panic!("a header-unsafe credential is unusable, not a wrong choice");
        };
        assert!(!reason.contains("with-a-control"), "got {reason}");
    }
}
