//! Append-only, provider-reported billing evidence for bounded experiments.
//!
//! This is deliberately a sidecar rather than part of the perception/decision
//! contract. Token counts describe the provider call, not the agent's decision,
//! and neither the body nor the agent should gain authority from them. When no
//! destination is configured the shipped service behaves exactly as before.

use std::fmt;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::Mutex;

use loom_contract::Perception;
use serde::Serialize;

use crate::config::ConfigError;
use crate::model::BackendError;
use crate::openai::settings::USAGE_LOG_VAR;
use crate::openai::wire::Usage;
use crate::router::ModelRoute;

/// Version of the private sidecar the experiment harness imports and sanitizes.
pub const USAGE_SCHEMA_VERSION: &str = "loom-provider-usage/1";

/// One provider-reported usage observation.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct UsageRecord<'a> {
    schema_version: &'static str,
    agent_id: &'a str,
    request_id: &'a str,
    model: &'a str,
    prompt_tokens: u64,
    cached_prompt_tokens: u64,
    completion_tokens: u64,
    total_tokens: u64,
}

/// An optional synchronized writer shared by concurrent model requests.
pub struct UsageJournal {
    writer: Option<Mutex<File>>,
}

impl UsageJournal {
    /// Opens an exclusive new sidecar when `path` is configured.
    ///
    /// # Errors
    ///
    /// Returns a configuration failure without repeating the local path when the
    /// destination cannot be created. A stale file is refused rather than
    /// appended to, so two runs can never share billing evidence.
    pub fn open(path: Option<&Path>) -> Result<Self, ConfigError> {
        let writer = match path {
            None => None,
            Some(path) => {
                let file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(path)
                    .map_err(|_| ConfigError::Unusable {
                        what: "the OpenAI usage journal",
                        reason: format!("{USAGE_LOG_VAR} could not be created exclusively"),
                    })?;
                Some(Mutex::new(file))
            }
        };
        Ok(Self { writer })
    }

    /// Appends the billing counts for one provider response.
    ///
    /// # Errors
    ///
    /// Returns a safe backend failure if configured evidence is absent,
    /// inconsistent, or cannot be made durable. The provider has already
    /// answered at this point, so silently dropping the record would understate
    /// the run's spend.
    pub fn append(
        &self,
        route: &ModelRoute,
        perception: &Perception,
        usage: Option<Usage>,
    ) -> Result<(), BackendError> {
        let Some(writer) = &self.writer else {
            return Ok(());
        };
        let usage = usage.ok_or_else(|| {
            BackendError::new(format!(
                "the model provider omitted token usage required by {USAGE_LOG_VAR}"
            ))
        })?;
        let cached = usage.prompt_tokens_details.cached_tokens;
        if cached > usage.prompt_tokens
            || usage.prompt_tokens.saturating_add(usage.completion_tokens) != usage.total_tokens
        {
            return Err(BackendError::new(
                "the model provider returned inconsistent token usage",
            ));
        }
        let record = UsageRecord {
            schema_version: USAGE_SCHEMA_VERSION,
            agent_id: &perception.agent_id,
            request_id: &perception.request_id,
            model: route.model(),
            prompt_tokens: usage.prompt_tokens,
            cached_prompt_tokens: cached,
            completion_tokens: usage.completion_tokens,
            total_tokens: usage.total_tokens,
        };
        let mut line = serde_json::to_vec(&record)
            .map_err(|_| BackendError::new("the provider usage record could not be encoded"))?;
        line.push(b'\n');
        let mut locked = writer
            .lock()
            .map_err(|_| BackendError::new("the provider usage journal lock was poisoned"))?;
        locked
            .write_all(&line)
            .and_then(|()| locked.sync_data())
            .map_err(|_| BackendError::new("the provider usage journal could not be written"))
    }
}

impl fmt::Debug for UsageJournal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("UsageJournal")
            .field("configured", &self.writer.is_some())
            .finish()
    }
}
