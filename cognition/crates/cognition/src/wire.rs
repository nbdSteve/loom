//! The wire frame: one JSON object per line, request and response.
//!
//! A request line is a contract [`Perception`]; a normal response line is a
//! contract [`Decision`]. Nothing else is added to those two - the contract
//! stays the currency crossing the mind/body boundary.
//!
//! One frame is *not* a contract type: a refusal. When a request cannot be
//! answered at all (unparseable perception, unreachable model), the response
//! carries `{"error":"<message>"}` and may carry a bounded machine-readable
//! `failure` beside it. That is a transport concern rather than a decision - a
//! mind that cannot answer has not decided anything, and inventing an `Intent`
//! to say so would put a fiction in the decision record. Answering immediately
//! also matters: a body that gets a refusal fails that one request now instead
//! of waiting out its watchdog.

use loom_contract::{Decision, Perception};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::model::BackendFailure;

/// The refusal frame as a receiver decodes it. Deliberately not a contract type:
/// see the module note.
#[derive(Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct WireError {
    /// Human-readable reason, safe to surface to the body and log.
    pub error: String,
    /// Optional bounded operator detail. The body gives this no authority.
    #[serde(default)]
    pub failure: Option<Value>,
}

/// The refusal frame as the cognition service encodes it.
#[derive(Serialize)]
struct EncodedWireError<'a> {
    error: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    failure: Option<&'a BackendFailure>,
}

/// Renders `decision` as the response line to write, without the newline.
///
/// # Errors
///
/// Returns the serde error if the decision cannot be serialized.
pub fn encode_decision(decision: &Decision) -> Result<String, serde_json::Error> {
    serde_json::to_string(decision)
}

/// Renders a refusal as the response line to write, without the newline.
///
/// Infallible by construction: the frame is one string field, so it cannot fail
/// to serialize, and falling back to a fixed literal keeps this off the
/// workspace's no-`expect` gate without hiding a real error.
#[must_use]
pub fn encode_error(message: &str) -> String {
    encode_error_with_failure(message, None)
}

/// Renders a refusal with bounded backend classification beside its
/// human-readable message.
#[must_use]
pub fn encode_error_with_failure(message: &str, failure: Option<&BackendFailure>) -> String {
    serde_json::to_string(&EncodedWireError {
        error: message,
        failure,
    })
    .unwrap_or_else(|_| {
        r#"{"error":"cognition failed, and the reason could not be encoded"}"#.to_owned()
    })
}

/// Parses a request line into a [`Perception`].
///
/// # Errors
///
/// Returns a human-readable message if the line is not a valid perception. The
/// caller answers with [`encode_error`] rather than dropping the request.
pub fn decode_perception(line: &str) -> Result<Perception, String> {
    serde_json::from_str(line).map_err(|e| format!("not a valid Perception: {e}"))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{decode_perception, encode_error, encode_error_with_failure, WireError};
    use crate::model::{
        BackendFailure, BackendFailureMapping, BackendResponseShape, BACKEND_FAILURE_SCHEMA_VERSION,
    };

    #[test]
    fn a_perception_line_round_trips_into_the_contract_type() {
        let line = r#"{"requestId":"req-goal-1","agentId":"agent-delta","trigger":{"event":"goalReview"},"importance":"ROUTINE","capturedTick":9000,"selfState":{"health":18.0,"food":20,"saturation":5.0,"position":[3.0,64.0,3.0],"inventorySummary":[{"ref":"i1","what":"wheat","count":12}],"craftingOptions":[],"craftingOptionsOmitted":0,"ownDrops":[],"world":{"dayPhase":"day","isDay":true,"ticksUntilNightfall":6000}},"surroundings":[],"surroundingsOmitted":0}"#;
        let seen = decode_perception(line).expect("fixture-shaped line decodes");
        assert_eq!(seen.request_id, "req-goal-1");
    }

    #[test]
    fn a_perception_with_an_unknown_trigger_is_refused_not_guessed() {
        let line = r#"{"requestId":"req-x","agentId":"agent-x","trigger":{"event":"tick"},"importance":"ROUTINE","capturedTick":1,"selfState":{"health":20.0,"food":20,"saturation":5.0,"position":[0.0,0.0,0.0],"inventorySummary":[],"craftingOptions":[],"craftingOptionsOmitted":0,"ownDrops":[],"world":{"dayPhase":"day","isDay":true,"ticksUntilNightfall":6000}},"surroundings":[],"surroundingsOmitted":0}"#;
        assert!(
            decode_perception(line).is_err(),
            "there is no per-tick trigger to decode"
        );
    }

    #[test]
    fn a_refusal_encodes_as_a_single_line_error_frame() {
        let encoded = encode_error("model unreachable");
        assert!(!encoded.contains('\n'), "a frame must stay on one line");
        let decoded: WireError = serde_json::from_str(&encoded).expect("the refusal frame decodes");
        assert_eq!(decoded.error, "model unreachable");
        assert!(decoded.failure.is_none());
    }

    #[test]
    fn a_classified_refusal_carries_only_bounded_machine_readable_context() {
        let failure = BackendFailure::openai(
            "gpt-test",
            Some(400),
            Some("invalid_request_error"),
            Some("invalid_json_schema"),
            BackendResponseShape::ApiErrorEnvelope,
            BackendFailureMapping::ProviderRequest,
        );
        let encoded = encode_error_with_failure("cognition failed safely", Some(&failure));
        let decoded: WireError = serde_json::from_str(&encoded).expect("the refusal frame decodes");
        let detail = decoded.failure.expect("classification is present");
        assert_eq!(detail["schemaVersion"], BACKEND_FAILURE_SCHEMA_VERSION);
        assert_eq!(detail["model"], "gpt-test");
        assert_eq!(detail["httpStatus"], 400);
        assert_eq!(detail["providerCode"], "invalid_json_schema");
        assert_eq!(detail["responseShape"], "apiErrorEnvelope");
        assert_eq!(detail["mapping"], "providerRequest");
    }
}
