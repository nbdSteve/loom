//! Loom's mind: the cognition service the body's bridge talks to.
//!
//! The shape is four small pieces, each with one job:
//!
//! - [`router`] resolves a [`loom_contract::DecisionImportance`] to the model
//!   and prompt version that answer at that tier. This is where "never a
//!   frontier model for a routine decision" is enforced.
//! - [`model`] is the backend seam: given a route and a perception, produce an
//!   intent and its reasoning. [`model::ScriptedBackend`] stands in until real
//!   inference is wired in.
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

pub mod mind;
pub mod model;
pub mod router;
pub mod server;
pub mod wire;
