//! Runs the cognition service.
//!
//! Listen address comes from `LOOM_COGNITION_ADDR` (default `127.0.0.1:8642`).
//! Binding to loopback by default is deliberate: the mind trusts whatever sends
//! it a perception, so it should not be reachable off the host without someone
//! saying so explicitly.
//!
//! This is the one place a model backend is chosen - the mirror of the plugin's
//! single body-flavor construction line. Swapping [`ScriptedBackend`] for real
//! inference is a change here and nowhere else.

use std::env;
use std::net::SocketAddr;
use std::process::ExitCode;

use loom_cognition::mind::Mind;
use loom_cognition::model::ScriptedBackend;
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

    // The single line that binds a model backend.
    let mind = Mind::new(ScriptedBackend);

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
