//! Loom's mind: the cognition service the body's bridge talks to.
//!
//! The shape is a small piece per job:
//!
//! - [`config`] reads the environment. Every outside-supplied string in the crate
//!   passes through it, and a misconfiguration stops the service rather than being
//!   guessed at.
//! - [`router`] resolves a [`loom_contract::DecisionImportance`] to the model
//!   class and prompt version that answer at that tier, and names the concrete
//!   model from a configured [`router::ModelCatalog`]. This is where "the
//!   expensive model is reachable only from a pivotal decision" is enforced, and
//!   it is why no provider model id appears in the domain.
//! - [`prompt`] renders a perception into words. Loom's own, versioned, and
//!   bounded, so a change of provider is not silently a change in what the agent
//!   was asked.
//! - [`model`] is the backend seam: given a route and a perception, produce an
//!   intent and its reasoning. [`model::ScriptedBackend`] is the deterministic
//!   stand-in that needs no credentials; [`openai`] is real inference.
//! - [`mind`] turns a perception into a decision, stamping the provenance from
//!   the route that was actually used - never from what the backend claims.
//! - [`server`] carries all of that over newline-delimited JSON on TCP.
//!
//! What the mind can see is exactly a [`loom_contract::Perception`]: owned,
//! bounded, summarized data. There is no world handle in it and no API here for
//! acquiring one, so the mind can only name an intent for the body to validate.
//!
//! Cognition is event-driven. Every request corresponds to one triggering event;
//! the contract has no per-tick trigger, so a tick cannot reach this crate.
//!
//! Which backend answers is one setting, [`model::BACKEND_VAR`], and the operator
//! documentation for it and for everything [`openai`] reads is `cognition/README.md`.

pub mod config;
pub mod mind;
pub mod model;
pub mod offered;
pub mod openai;
pub mod prompt;
pub mod router;
pub mod server;
pub mod wire;
