//! Runs the cognition service.
//!
//! Listen address comes from `LOOM_COGNITION_ADDR` (default `127.0.0.1:8642`).
//! Binding to loopback by default is deliberate: the mind trusts whatever sends
//! it a perception, so it should not be reachable off the host without someone
//! saying so explicitly.
//!
//! This is the one place a model backend is chosen - the mirror of the plugin's
//! single body-flavor construction line. A new backend is a new [`ModelBackend`]
//! implementation and one new arm of the match below, and nothing else in the
//! crate needs to know it exists.
//!
//! The default is the scripted stand-in, so a fresh checkout runs with no
//! credentials and the hermetic tests and CI's cross-runtime round trip stay
//! credential-free. Real inference is `LOOM_COGNITION_BACKEND=openai`; the
//! settings it reads are documented in `cognition/README.md`.
//!
//! A configuration mistake exits non-zero at startup rather than degrading to
//! something that looks healthy. The startup lines below name the backend and the
//! models so a running service can be identified from its log - and they name the
//! endpoint but never the credential.

use std::env;
use std::net::SocketAddr;
use std::process::ExitCode;

use loom_cognition::config::process_env;
use loom_cognition::mind::Mind;
use loom_cognition::model::{BackendChoice, ModelBackend, ScriptedBackend, SCRIPTED_MODEL_ID};
use loom_cognition::openai::{OpenAiBackend, OpenAiConfig};
use loom_cognition::router::{ModelCatalog, ModelClass, Router};
use loom_cognition::server::serve;

/// Environment variable naming the address to listen on.
const ADDR_VAR: &str = "LOOM_COGNITION_ADDR";

/// Default listen address: loopback only.
const DEFAULT_ADDR: &str = "127.0.0.1:8642";

#[tokio::main]
async fn main() -> ExitCode {
    let raw = env::var(ADDR_VAR).unwrap_or_else(|_| DEFAULT_ADDR.to_owned());
    let Ok(addr) = raw.parse::<SocketAddr>() else {
        eprintln!("{ADDR_VAR}='{raw}' is not a valid socket address");
        return ExitCode::FAILURE;
    };

    let chosen = match BackendChoice::from_env(&process_env()) {
        Ok(chosen) => chosen,
        Err(e) => {
            eprintln!("loom-cognition is not configured: {e}");
            return ExitCode::FAILURE;
        }
    };

    // The single place a model backend is bound. Each arm builds the router from
    // the ids that backend can actually answer with, so provenance stays true.
    match chosen {
        BackendChoice::Scripted => {
            let router = Router::new(ModelCatalog::uniform(SCRIPTED_MODEL_ID));
            announce(chosen, &router, "no provider: decisions are scripted");
            run(addr, Mind::new(router, ScriptedBackend)).await
        }
        BackendChoice::OpenAi => {
            let config = match OpenAiConfig::from_process_env() {
                Ok(config) => config,
                Err(e) => {
                    eprintln!("loom-cognition is not configured: {e}");
                    return ExitCode::FAILURE;
                }
            };
            let router = Router::new(config.catalog().clone());
            let endpoint = format!(
                "{url}, {millis}ms timeout, {tokens} token answers, {bytes} byte requests, usage evidence {usage}",
                url = config.chat_completions_url(),
                millis = config.request_timeout().as_millis(),
                tokens = config.max_output_tokens(),
                bytes = config.max_request_bytes(),
                usage = if config.usage_log().is_some() { "on" } else { "off" }
            );
            let backend = match OpenAiBackend::new(config) {
                Ok(backend) => backend,
                Err(e) => {
                    eprintln!("loom-cognition could not start: {e}");
                    return ExitCode::FAILURE;
                }
            };
            announce(chosen, &router, &endpoint);
            run(addr, Mind::new(router, backend)).await
        }
    }
}

/// Says what this process will answer with, before it starts answering.
///
/// `detail` describes the provider and never the credential: everything on this
/// line is something an operator configured and would want confirmed back.
fn announce(chosen: BackendChoice, router: &Router, detail: &str) {
    println!(
        "loom-cognition backend={backend} fast={fast} deep={deep} ({detail})",
        backend = chosen.label(),
        fast = router.catalog().model(ModelClass::Fast),
        deep = router.catalog().model(ModelClass::Deep)
    );
}

/// Serves `mind` on `addr` until the process is stopped.
///
/// Generic over the backend rather than duplicated per arm: the service is
/// identical whichever one answered, which is the point of the seam.
async fn run<B: ModelBackend + 'static>(addr: SocketAddr, mind: Mind<B>) -> ExitCode {
    let served = serve(addr, mind, |bound| {
        println!("loom-cognition listening on {bound}");
    })
    .await;
    match served {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("loom-cognition could not serve {addr}: {e}");
            ExitCode::FAILURE
        }
    }
}
