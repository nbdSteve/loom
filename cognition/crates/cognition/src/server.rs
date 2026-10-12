//! The TCP service that carries the mind: newline-delimited JSON in, the same
//! out.
//!
//! One connection may carry many request lines, answered in order, so a body
//! keeps one socket per bridge rather than reconnecting per decision. Each line
//! is answered independently: a refusal for one request does not close the
//! connection or affect the next.
//!
//! Two properties are load-bearing rather than incidental:
//!
//! - **A bad request never kills the service.** Every failure path answers with
//!   a refusal frame and continues. The workspace's clippy gate forbids panic,
//!   unwrap, and expect in non-test code, so this is compiler-enforced.
//! - **Request lines are length-bounded.** `read_line` would let one peer grow
//!   the mind's memory without limit, so [`read_frame`] accumulates against a
//!   cap instead and refuses anything past it.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

use crate::mind::Mind;
use crate::model::ModelBackend;
use crate::wire::{decode_perception, encode_decision, encode_error, encode_error_with_failure};

/// Longest request line the service will accumulate, in bytes.
///
/// A bounded perception is small - one self-state and at most a couple of dozen
/// summarized observations - so 256 KiB is generous by a wide margin while still
/// bounding what one peer can make the mind hold.
const MAX_REQUEST_LINE_BYTES: usize = 256 * 1024;

/// Refusal messages for framing failures, which are not decisions and not
/// contract types. See the [`crate::wire`] module note.
const TOO_LONG: &str = "request line exceeded the maximum length";
const UNTERMINATED: &str = "connection closed mid-request line";
const NOT_UTF8: &str = "request line was not valid UTF-8";

/// How long to wait before accepting again after a resource-shortage error.
///
/// Short enough that a transient shortage costs one body a barely-noticeable
/// delay, long enough that the loop yields instead of spinning. See
/// [`is_resource_shortage`].
const ACCEPT_BACKOFF: Duration = Duration::from_millis(100);

/// One read from a connection.
#[derive(Debug, PartialEq, Eq)]
enum Frame {
    /// A complete request line, newline stripped.
    Line(String),
    /// The peer closed the connection between requests.
    Closed,
    /// Framing can no longer be trusted; carries the refusal to answer with
    /// before hanging up.
    Unusable(&'static str),
}

/// Serves `mind` on `addr` forever.
///
/// The bound address is reported through `on_ready` before the accept loop
/// starts, so a caller that asked for port 0 learns the port it actually got.
///
/// # Errors
///
/// Returns the I/O error if the listener cannot bind. A failed *accept* is not
/// fatal: that connection is dropped and the loop continues, because one peer
/// must never be able to end the service.
pub async fn serve<B, F>(addr: SocketAddr, mind: Mind<B>, on_ready: F) -> io::Result<()>
where
    B: ModelBackend + 'static,
    F: FnOnce(SocketAddr),
{
    let listener = TcpListener::bind(addr).await?;
    on_ready(listener.local_addr()?);
    let mind = Arc::new(mind);
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _peer)) => stream,
            Err(e) => {
                // A per-connection failure is skipped immediately, but a resource
                // shortage is not per-connection: the listener stays readable and
                // `accept` keeps failing at once, so retrying without yielding
                // would busy-wait at full CPU - starving the very connection tasks
                // whose completion would release the descriptors.
                if is_resource_shortage(&e) {
                    tokio::time::sleep(ACCEPT_BACKOFF).await;
                }
                continue;
            }
        };
        let mind = Arc::clone(&mind);
        tokio::spawn(async move {
            // A connection that errors mid-stream is simply closed: the mind is
            // stateless per request, so there is nothing to unwind.
            let _ = handle_connection(stream, &mind).await;
        });
    }
}

/// Whether an accept error means the host is out of a resource rather than that
/// one connection failed.
///
/// Decided by exclusion, because the errors that matter most here - `EMFILE` and
/// `ENFILE`, descriptor exhaustion - have no stable [`io::ErrorKind`] of their
/// own and arrive as `Uncategorized`, so a positive list would silently miss the
/// exact case the backoff exists for. Only the failures that are genuinely about
/// one connection are treated as skippable; anything else is assumed to persist
/// until something is released, and is worth a pause.
fn is_resource_shortage(e: &io::Error) -> bool {
    !matches!(
        e.kind(),
        io::ErrorKind::ConnectionAborted
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionRefused
            | io::ErrorKind::Interrupted
    )
}

/// Answers every request line on `stream` until the peer closes it.
///
/// # Errors
///
/// Returns the I/O error if the socket fails. A malformed *request* is not an
/// error here: it is answered with a refusal and the connection keeps serving.
async fn handle_connection<B: ModelBackend>(stream: TcpStream, mind: &Mind<B>) -> io::Result<()> {
    // Nagle off: these are small request/response frames where latency matters
    // more than packing, and a body is waiting on each one.
    stream.set_nodelay(true)?;
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);
    loop {
        match read_frame(&mut reader).await? {
            Frame::Closed => return Ok(()),
            Frame::Unusable(refusal) => {
                // Answer before hanging up so the body fails this request now
                // rather than waiting out its watchdog.
                write_line(&mut write_half, &encode_error(refusal)).await?;
                return Ok(());
            }
            Frame::Line(request) => {
                let response = answer(mind, request.trim_end()).await;
                write_line(&mut write_half, &response).await?;
            }
        }
    }
}

/// Reads one newline-terminated request line, bounded by
/// [`MAX_REQUEST_LINE_BYTES`].
///
/// Accumulates from the reader's own buffer rather than calling `read_line`,
/// which is what makes the bound real: the cap is checked as bytes arrive, so an
/// endless line is refused instead of being buffered first.
async fn read_frame<R: AsyncBufRead + Unpin>(reader: &mut R) -> io::Result<Frame> {
    let mut line: Vec<u8> = Vec::new();
    loop {
        let chunk: Vec<u8>;
        let terminated: bool;
        {
            let available = reader.fill_buf().await?;
            if available.is_empty() {
                return Ok(if line.is_empty() {
                    Frame::Closed
                } else {
                    Frame::Unusable(UNTERMINATED)
                });
            }
            let newline_at = available.iter().position(|byte| *byte == b'\n');
            terminated = newline_at.is_some();
            chunk = available[..newline_at.unwrap_or(available.len())].to_vec();
        }
        reader.consume(chunk.len() + usize::from(terminated));
        if line.len() + chunk.len() > MAX_REQUEST_LINE_BYTES {
            return Ok(Frame::Unusable(TOO_LONG));
        }
        line.extend_from_slice(&chunk);
        if terminated {
            return Ok(match String::from_utf8(line) {
                Ok(request) => Frame::Line(request),
                Err(_) => Frame::Unusable(NOT_UTF8),
            });
        }
    }
}

/// The response line for one request line, refusal included.
///
/// Split out from the socket handling so the whole request-to-response path is
/// testable without a network.
///
/// Requests on one connection are answered in order, so a real backend's latency
/// is serialized per connection. That is the body's to manage, not this side's:
/// the bridge owns concurrency and gives every request a deadline, and answering
/// out of order here would break the newline-delimited framing the contract is
/// carried in.
async fn answer<B: ModelBackend>(mind: &Mind<B>, request: &str) -> String {
    if request.is_empty() {
        return encode_error("empty request line");
    }
    let perception = match decode_perception(request) {
        Ok(perception) => perception,
        Err(reason) => return encode_error(&reason),
    };
    match mind.decide(&perception).await {
        Ok(decision) => match encode_decision(&decision) {
            Ok(line) => line,
            Err(e) => encode_error(&format!("decision could not be encoded: {e}")),
        },
        Err(e) => encode_error_with_failure(&format!("cognition failed: {e}"), e.failure()),
    }
}

async fn write_line<W: AsyncWriteExt + Unpin>(writer: &mut W, line: &str) -> io::Result<()> {
    writer.write_all(line.as_bytes()).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{
        answer, is_resource_shortage, read_frame, Frame, MAX_REQUEST_LINE_BYTES, TOO_LONG,
        UNTERMINATED,
    };
    use crate::mind::Mind;
    use crate::model::{
        BackendError, BackendFailure, BackendFailureMapping, BackendResponseShape, Draft,
        ModelBackend, ScriptedBackend, SCRIPTED_MODEL_ID,
    };
    use crate::router::{ModelCatalog, Router};
    use crate::wire::WireError;
    use loom_contract::{Decision, Intent, Perception};
    use std::{future, io};

    const SPOKEN_PERCEPTION: &str = r#"{"requestId":"req-spoken-1","agentId":"agent-alpha","trigger":{"event":"spoken","from":"player-steve","text":"want to trade?"},"importance":"NOTABLE","capturedTick":1024,"selfState":{"health":20.0,"food":20,"saturation":5.0,"position":[10.5,64.0,-8.25],"inventorySummary":[],"craftingOptions":[],"craftingOptionsOmitted":0,"world":{"dayPhase":"day","isDay":true,"ticksUntilNightfall":6000}},"surroundings":[],"surroundingsOmitted":0}"#;

    fn scripted_mind() -> Mind<ScriptedBackend> {
        Mind::new(
            Router::new(ModelCatalog::uniform(SCRIPTED_MODEL_ID)),
            ScriptedBackend,
        )
    }

    struct ClassifiedBrokenBackend;

    impl ModelBackend for ClassifiedBrokenBackend {
        fn draft(
            &self,
            route: &crate::router::ModelRoute,
            _perception: &Perception,
        ) -> impl future::Future<Output = Result<Draft, BackendError>> + Send {
            future::ready(Err(BackendError::classified(
                "provider request rejected",
                BackendFailure::openai(
                    route.model(),
                    Some(400),
                    Some("invalid_request_error"),
                    Some("invalid_json_schema"),
                    BackendResponseShape::ApiErrorEnvelope,
                    BackendFailureMapping::ProviderRequest,
                ),
            )))
        }
    }

    fn refusal(line: &str) -> WireError {
        serde_json::from_str(line).expect("a refusal decodes as an error frame")
    }

    #[tokio::test]
    async fn a_perception_line_is_answered_with_a_decision_line() {
        let response = answer(&scripted_mind(), SPOKEN_PERCEPTION).await;
        let decision: Decision = serde_json::from_str(&response).expect("the answer is a Decision");
        assert_eq!(decision.request_id, "req-spoken-1");
        assert_eq!(
            decision.intent,
            Intent::Say {
                text: "Hello, player-steve.".to_owned()
            }
        );
        // The stand-in names itself, so nothing in the research record claims a
        // model that never saw this perception.
        assert_eq!(decision.provenance.model, SCRIPTED_MODEL_ID);
    }

    #[tokio::test]
    async fn a_classified_backend_failure_reaches_the_refusal_frame() {
        let mind = Mind::new(
            Router::new(ModelCatalog::uniform("fast-model")),
            ClassifiedBrokenBackend,
        );
        let failed = refusal(&answer(&mind, SPOKEN_PERCEPTION).await);
        let classification = failed.failure.expect("classification reaches the wire");

        assert_eq!(failed.error, "cognition failed: provider request rejected");
        assert_eq!(classification["model"], "fast-model");
        assert_eq!(classification["httpStatus"], 400);
        assert_eq!(classification["providerCode"], "invalid_json_schema");
        assert_eq!(classification["responseShape"], "apiErrorEnvelope");
        assert_eq!(classification["mapping"], "providerRequest");
    }

    #[tokio::test]
    async fn a_malformed_request_is_refused_rather_than_answered_with_a_decision() {
        let response = answer(&scripted_mind(), "{not json").await;
        assert!(refusal(&response).error.contains("not a valid Perception"));
    }

    #[tokio::test]
    async fn an_empty_request_line_is_refused() {
        assert_eq!(
            refusal(&answer(&scripted_mind(), "").await).error,
            "empty request line"
        );
    }

    #[tokio::test]
    async fn consecutive_lines_are_read_as_separate_frames() {
        let mut reader = "first\nsecond\n".as_bytes();
        assert_eq!(
            read_frame(&mut reader).await.unwrap(),
            Frame::Line("first".to_owned())
        );
        assert_eq!(
            read_frame(&mut reader).await.unwrap(),
            Frame::Line("second".to_owned())
        );
        assert_eq!(read_frame(&mut reader).await.unwrap(), Frame::Closed);
    }

    #[tokio::test]
    async fn an_oversized_line_is_refused_rather_than_buffered() {
        let oversized = format!("{}\n", "x".repeat(MAX_REQUEST_LINE_BYTES + 1));
        let mut reader = oversized.as_bytes();
        assert_eq!(
            read_frame(&mut reader).await.unwrap(),
            Frame::Unusable(TOO_LONG)
        );
    }

    #[test]
    fn descriptor_exhaustion_earns_a_backoff_but_a_dropped_peer_does_not() {
        // The case the backoff exists for: EMFILE has no ErrorKind of its own and
        // arrives uncategorized, so it must still be recognized as persistent.
        let emfile = io::Error::from_raw_os_error(libc_emfile());
        assert!(
            is_resource_shortage(&emfile),
            "descriptor exhaustion persists until a connection closes, so accepting again at once would spin"
        );
        // A peer that went away before it was accepted is one connection's problem;
        // pausing the whole listener for it would penalize every other body.
        for kind in [
            io::ErrorKind::ConnectionAborted,
            io::ErrorKind::ConnectionReset,
            io::ErrorKind::Interrupted,
        ] {
            assert!(
                !is_resource_shortage(&io::Error::new(kind, "one peer")),
                "{kind:?} is per-connection and must not delay the accept loop"
            );
        }
    }

    /// `EMFILE` as this platform numbers it.
    const fn libc_emfile() -> i32 {
        // 24 on Linux and on the BSD/macOS family alike; asserted rather than
        // linked against libc so the test stays dependency-free.
        24
    }

    #[tokio::test]
    async fn a_line_the_peer_never_terminated_is_refused() {
        let mut reader = "half a request".as_bytes();
        assert_eq!(
            read_frame(&mut reader).await.unwrap(),
            Frame::Unusable(UNTERMINATED)
        );
    }
}
