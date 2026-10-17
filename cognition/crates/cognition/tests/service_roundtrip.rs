//! Proves the service answers over a real socket, using the shared fixtures as
//! the requests.
//!
//! Driving it with `contract/fixtures/valid/perception-*.json` is what makes
//! this a contract test rather than a self-consistent one: the bytes on the wire
//! are the same bytes both runtimes' conformance tests pin, so a decision
//! produced here is one the JVM side can parse.

#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::{Path, PathBuf};

use loom_cognition::mind::Mind;
use loom_cognition::model::{
    ScriptedBackend, SCRIPTED_GOAL_STATEMENT, SCRIPTED_STEP, SCRIPTED_SUCCESS_CONDITION,
    SCRIPTED_SUCCESS_ITEM,
};
use loom_cognition::offered::OfferedRefs;
use loom_cognition::router::{ModelCatalog, Router};
use loom_cognition::server::serve;
use loom_contract::{
    Decision, DecisionImportance, GoalChoice, GoalDisposition, GoalStepResult, Perception, Trigger,
};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::oneshot;

/// Every `perception-*.json` under `contract/fixtures/valid`, sorted.
fn perception_fixtures() -> Vec<PathBuf> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contract/fixtures/valid")
        .canonicalize()
        .expect("shared fixtures directory resolves from the crate manifest dir");
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read {}: {e}", dir.display()))
        .map(|entry| entry.expect("dir entry").path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "json"))
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with("perception-"))
        })
        .collect();
    files.sort();
    assert!(!files.is_empty(), "no perception fixtures found");
    files
}

/// The id the fixture service answers the fast class with.
///
/// Distinct from [`DEEP_MODEL`] on purpose: the assertion below, that a routine
/// perception is not answered by the deep model, is a real check only where the
/// two classes name two different things.
const FAST_MODEL: &str = "fixture-fast-model";

/// The id the fixture service answers the deep class with.
const DEEP_MODEL: &str = "fixture-deep-model";

/// Starts the service on an ephemeral port and returns the address it bound.
///
/// The task is left running for the test's lifetime; the process exits with it.
async fn start_service() -> SocketAddr {
    let (ready_tx, ready_rx) = oneshot::channel();
    tokio::spawn(async move {
        let any_port = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0));
        let router = Router::new(ModelCatalog::new(FAST_MODEL, DEEP_MODEL));
        serve(any_port, Mind::new(router, ScriptedBackend), move |bound| {
            let _ = ready_tx.send(bound);
        })
        .await
        .expect("the service binds an ephemeral loopback port");
    });
    ready_rx
        .await
        .expect("the service reports its bound address")
}

#[tokio::test]
async fn every_shared_perception_fixture_gets_a_routed_decision_over_a_socket() {
    let addr = start_service().await;
    let stream = TcpStream::connect(addr)
        .await
        .expect("connect to the service");
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    // One connection, every fixture: the service must answer many requests in
    // order without reconnecting.
    for fixture in perception_fixtures() {
        let request = fs::read_to_string(&fixture).expect("read fixture");
        let sent: Perception = serde_json::from_str(&request).expect("fixture is a Perception");

        write_half
            .write_all(request.as_bytes())
            .await
            .expect("write request");
        write_half.write_all(b"\n").await.expect("write newline");
        write_half.flush().await.expect("flush request");

        let mut response = String::new();
        reader
            .read_line(&mut response)
            .await
            .expect("read response");
        let decision: Decision = serde_json::from_str(response.trim_end()).unwrap_or_else(|e| {
            panic!(
                "{} was not answered with a Decision: {e} in {response}",
                fixture.display()
            )
        });

        assert_eq!(
            decision.request_id, sent.request_id,
            "a decision must be correlatable with the perception it answers"
        );
        assert_eq!(decision.agent_id, sent.agent_id);
        assert!(
            !decision.reasoning.is_empty(),
            "every decision must carry reasoning for the research record"
        );
        assert_eq!(
            decision.provenance.tier, sent.importance,
            "the decision must be stamped with the tier it was routed at"
        );
        assert!(!decision.provenance.model.is_empty());
        assert!(!decision.provenance.prompt_version.is_empty());
        if sent.importance == DecisionImportance::Routine {
            assert_eq!(
                decision.provenance.model, FAST_MODEL,
                "a routine perception must never be answered by the deep model"
            );
        }
        // The stand-in adopts where nothing is held and continues a carried goal, and
        // that choice must survive the socket exactly as the mind made it.
        let intent = &decision.intent;
        let step = |disposition, step_result| GoalChoice {
            disposition,
            statement: None,
            success_condition: None,
            success_item: None,
            success_count: None,
            step: Some(SCRIPTED_STEP.to_owned()),
            step_action: Some(intent.action()),
            step_ref: intent.target_ref().map(str::to_owned),
            step_result,
        };
        // It adopts only an item it can get now, the stick first, and nothing without one.
        let obtainable = OfferedRefs::of(&sent).obtainable;
        let item = obtainable
            .iter()
            .find(|item| *item == SCRIPTED_SUCCESS_ITEM)
            .or_else(|| obtainable.first());
        let expected = (sent.trigger == Trigger::GoalReview)
            .then_some(())
            .and_then(|()| match &sent.goal {
                None => item.map(|item| GoalChoice {
                    statement: Some(SCRIPTED_GOAL_STATEMENT.to_owned()),
                    success_condition: Some(SCRIPTED_SUCCESS_CONDITION.to_owned()),
                    success_item: Some(item.clone()),
                    success_count: Some(1),
                    ..step(GoalDisposition::Adopt, None)
                }),
                Some(held) => {
                    let progress = held.progress.as_ref();
                    let result = if progress
                        .is_some_and(|seen| !seen.condition_advanced && !seen.step_advanced)
                    {
                        GoalStepResult::NoProgress
                    } else {
                        GoalStepResult::Progressing
                    };
                    Some(if progress.is_some_and(|seen| seen.condition_met) {
                        GoalChoice {
                            step: None,
                            step_action: None,
                            step_ref: None,
                            ..step(GoalDisposition::Complete, Some(result))
                        }
                    } else {
                        step(GoalDisposition::Continue, Some(result))
                    })
                }
            });
        assert_eq!(decision.goal, expected, "{}", fixture.display());
    }
}

#[tokio::test]
async fn a_malformed_request_is_refused_and_the_connection_keeps_serving() {
    let addr = start_service().await;
    let stream = TcpStream::connect(addr)
        .await
        .expect("connect to the service");
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    write_half
        .write_all(b"{\"requestId\":\"nope\"}\n")
        .await
        .expect("write a malformed request");
    write_half.flush().await.expect("flush");
    let mut refused = String::new();
    reader
        .read_line(&mut refused)
        .await
        .expect("read the refusal");
    assert!(
        refused.contains("not a valid Perception"),
        "an incomplete perception must be refused, got {refused}"
    );

    // The same connection must still answer a good request afterwards: one bad
    // request degrades one decision, not the channel.
    let good = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../contract/fixtures/valid/perception-spoken.json"),
    )
    .expect("read fixture");
    write_half
        .write_all(good.as_bytes())
        .await
        .expect("write request");
    write_half.write_all(b"\n").await.expect("write newline");
    write_half.flush().await.expect("flush");
    let mut response = String::new();
    reader
        .read_line(&mut response)
        .await
        .expect("read response");
    let decision: Decision =
        serde_json::from_str(response.trim_end()).expect("the next request is still answered");
    assert_eq!(decision.request_id, "req-spoken-1");
}
