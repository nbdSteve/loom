//! Reading the runtime's configuration, once, at startup.
//!
//! Three rules shape this module, and each of them is a property a test pins
//! rather than a habit:
//!
//! - **Configuration is read at startup, and a mistake in it stops the service.**
//!   A mind with no reachable model has nothing to offer any agent, so it should
//!   refuse to start rather than answer every perception with a refusal and look
//!   healthy while doing it.
//! - **No error, and no log line, ever carries a secret.** Variables are looked
//!   up by name and reported by name; the one secret value lives behind a type
//!   whose `Debug` redacts it (see [`crate::openai::ApiKey`]).
//! - **Every string that comes from outside passes through [`bounded`] before it
//!   reaches an error message or a log line.** That covers configuration values
//!   and provider-supplied error codes alike: the wire between the mind and the
//!   body is newline-delimited, and an unbounded outside string in a refusal is
//!   both a framing risk and a way to make a log unreadable.
//!
//! Lookup is a closure rather than direct `std::env::var` so tests can exercise
//! every parse path against a map, in parallel, without mutating process-wide
//! state.

use std::fmt;
use std::time::Duration;

/// Longest an outside-supplied string may be when it reaches an error message.
///
/// Generous for a model id or a provider error code, short enough that no
/// refusal frame is dominated by something a peer chose.
const MAX_REPORTED_CHARS: usize = 80;

/// Marker appended when [`bounded`] had to cut a value short.
const TRUNCATION_MARKER: &str = "...";

/// Renders `text` safe to put in an error message or a log line.
///
/// See [`bounded_to`], at the bound errors and logs use.
#[must_use]
pub fn bounded(text: &str) -> String {
    bounded_to(text, MAX_REPORTED_CHARS)
}

/// Renders `text` safe to embed in something Loom composes, at a caller's bound.
///
/// The single funnel every string that came from outside passes through, whether
/// it is heading for a log line, a refusal frame, or a prompt. Two things happen
/// to it:
///
/// - Control characters are replaced rather than dropped. Newlines matter most:
///   the cognition wire and the event log are both newline-delimited, and a
///   prompt is line-structured, so a newline arriving inside a value is how one
///   line becomes two and a value starts to read as framing. Replacing rather
///   than dropping means a value that was mangled reads as mangled instead of
///   silently reflowing into something plausible.
/// - The result is cut to `max_chars` characters, counted in `char`s so a
///   multi-byte value cannot be split mid-character, with the cut left visible.
#[must_use]
pub fn bounded_to(text: &str, max_chars: usize) -> String {
    let cleaned: String = text
        .chars()
        .map(|c| if c.is_control() { '\u{fffd}' } else { c })
        .collect();
    if cleaned.chars().count() <= max_chars {
        return cleaned;
    }
    let kept: String = cleaned.chars().take(max_chars).collect();
    format!("{kept}{TRUNCATION_MARKER}")
}

/// Why the service could not be configured.
///
/// Every variant names the variable at fault, because the only useful thing an
/// operator can do with a configuration failure is go and fix the setting.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ConfigError {
    /// A required variable was not set at all.
    Missing {
        /// Name of the variable to set.
        var: &'static str,
    },
    /// A variable was set to nothing, or to whitespace.
    Blank {
        /// Name of the variable that was set but empty.
        var: &'static str,
    },
    /// A variable that must be a positive whole number was not one.
    NotAPositiveNumber {
        /// Name of the variable at fault.
        var: &'static str,
        /// What it was set to, bounded by [`bounded`].
        value: String,
    },
    /// A variable was set to something outside the set of accepted values.
    NotAChoice {
        /// Name of the variable at fault.
        var: &'static str,
        /// What it was set to, bounded by [`bounded`].
        value: String,
        /// The values that would have been accepted.
        accepted: &'static str,
    },
    /// A component could not be built from otherwise valid settings.
    Unusable {
        /// What could not be built.
        what: &'static str,
        /// Why, bounded by [`bounded`].
        reason: String,
    },
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Missing { var } => write!(f, "{var} is not set"),
            Self::Blank { var } => write!(f, "{var} is set but empty"),
            Self::NotAPositiveNumber { var, value } => {
                write!(f, "{var}='{value}' is not a positive whole number")
            }
            Self::NotAChoice {
                var,
                value,
                accepted,
            } => write!(f, "{var}='{value}' is not one of {accepted}"),
            Self::Unusable { what, reason } => write!(f, "{what} could not be built: {reason}"),
        }
    }
}

impl std::error::Error for ConfigError {}

/// The set of variables a configuration is read from.
///
/// Generic over the lookup so the shipped service reads the process environment
/// and a test reads a map. Values are trimmed on the way out: a trailing newline
/// from `export KEY=$(cat secret)` is the classic way to make a credential look
/// present and be rejected by the provider.
pub struct Env<F>
where
    F: Fn(&str) -> Option<String>,
{
    lookup: F,
}

impl<F> Env<F>
where
    F: Fn(&str) -> Option<String>,
{
    /// Builds an environment that resolves names through `lookup`.
    pub const fn new(lookup: F) -> Self {
        Self { lookup }
    }

    /// The trimmed value of `name`, or `None` if it is unset or all whitespace.
    fn present(&self, name: &str) -> Option<String> {
        let raw = (self.lookup)(name)?;
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_owned())
        }
    }

    /// Whether `name` is set to anything at all, blank included.
    fn set_at_all(&self, name: &str) -> bool {
        (self.lookup)(name).is_some()
    }

    /// The value of a variable that must be set.
    ///
    /// # Errors
    ///
    /// [`ConfigError::Missing`] if unset, [`ConfigError::Blank`] if set to
    /// nothing. The two are kept apart because they are different mistakes: one
    /// is a forgotten setting, the other a setting that resolved to nothing.
    pub fn required(&self, name: &'static str) -> Result<String, ConfigError> {
        match self.present(name) {
            Some(value) => Ok(value),
            None if self.set_at_all(name) => Err(ConfigError::Blank { var: name }),
            None => Err(ConfigError::Missing { var: name }),
        }
    }

    /// The value of a setting that may be absent but may not be blank.
    ///
    /// # Errors
    ///
    /// [`ConfigError::Blank`] if the variable is present with no value. Treating
    /// blank as absent would hide an operator mistake in the same way as
    /// [`Env::or_default`].
    pub fn optional(&self, name: &'static str) -> Result<Option<String>, ConfigError> {
        match self.present(name) {
            Some(value) => Ok(Some(value)),
            None if self.set_at_all(name) => Err(ConfigError::Blank { var: name }),
            None => Ok(None),
        }
    }

    /// The value of a variable that has a default.
    ///
    /// An *unset* variable takes the default; a variable set to nothing is an
    /// error rather than a silent fall back to the default, because someone
    /// clearly meant to configure it.
    ///
    /// # Errors
    ///
    /// [`ConfigError::Blank`] if the variable is set to nothing.
    pub fn or_default(
        &self,
        name: &'static str,
        default: &'static str,
    ) -> Result<String, ConfigError> {
        match self.present(name) {
            Some(value) => Ok(value),
            None if self.set_at_all(name) => Err(ConfigError::Blank { var: name }),
            None => Ok(default.to_owned()),
        }
    }

    /// A positive whole-number setting, or its default.
    ///
    /// # Errors
    ///
    /// [`ConfigError::Blank`] if set to nothing, or
    /// [`ConfigError::NotAPositiveNumber`] if it is not a number above zero.
    /// Zero is refused rather than taken as "no limit": every bound in the mind
    /// is a real bound.
    pub fn positive_u32(&self, name: &'static str, default: u32) -> Result<u32, ConfigError> {
        let Some(raw) = self.present(name) else {
            return if self.set_at_all(name) {
                Err(ConfigError::Blank { var: name })
            } else {
                Ok(default)
            };
        };
        match raw.parse::<u32>() {
            Ok(parsed) if parsed > 0 => Ok(parsed),
            Ok(_) | Err(_) => Err(ConfigError::NotAPositiveNumber {
                var: name,
                value: bounded(&raw),
            }),
        }
    }

    /// A duration expressed in whole milliseconds, or its default.
    ///
    /// # Errors
    ///
    /// As [`Env::positive_u32`].
    pub fn positive_millis(
        &self,
        name: &'static str,
        default_millis: u32,
    ) -> Result<Duration, ConfigError> {
        Ok(Duration::from_millis(u64::from(
            self.positive_u32(name, default_millis)?,
        )))
    }
}

/// An [`Env`] over the process environment.
#[must_use]
pub fn process_env() -> Env<impl Fn(&str) -> Option<String>> {
    Env::new(|name| std::env::var(name).ok())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::{bounded, bounded_to, ConfigError, Env, MAX_REPORTED_CHARS};
    use std::time::Duration;

    /// An environment over a fixed list of pairs, so a test never touches the
    /// process environment and cases can run in parallel.
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

    #[test]
    fn a_required_variable_distinguishes_unset_from_set_to_nothing() {
        let empty = env_of(&[("LOOM_X", "   ")]);
        assert_eq!(
            empty.required("LOOM_X"),
            Err(ConfigError::Blank { var: "LOOM_X" })
        );
        assert_eq!(
            empty.required("LOOM_Y"),
            Err(ConfigError::Missing { var: "LOOM_Y" })
        );
    }

    #[test]
    fn an_optional_variable_distinguishes_absent_from_blank() {
        assert_eq!(env_of(&[]).optional("LOOM_X").unwrap(), None);
        assert_eq!(
            env_of(&[("LOOM_X", " value ")]).optional("LOOM_X").unwrap(),
            Some("value".to_owned())
        );
        assert_eq!(
            env_of(&[("LOOM_X", " ")]).optional("LOOM_X").unwrap_err(),
            ConfigError::Blank { var: "LOOM_X" }
        );
    }

    #[test]
    fn a_value_is_trimmed_so_a_trailing_newline_does_not_travel_with_it() {
        let env = env_of(&[("LOOM_KEY", "  sk-not-a-real-key\n")]);
        assert_eq!(env.required("LOOM_KEY").unwrap(), "sk-not-a-real-key");
    }

    #[test]
    fn an_unset_variable_takes_its_default_but_an_empty_one_is_refused() {
        let env = env_of(&[("LOOM_SET_EMPTY", "")]);
        assert_eq!(
            env.or_default("LOOM_ABSENT", "fallback").unwrap(),
            "fallback"
        );
        assert_eq!(
            env.or_default("LOOM_SET_EMPTY", "fallback"),
            Err(ConfigError::Blank {
                var: "LOOM_SET_EMPTY"
            })
        );
    }

    #[test]
    fn a_number_setting_refuses_zero_and_nonsense_alike() {
        let env = env_of(&[
            ("LOOM_ZERO", "0"),
            ("LOOM_WORDS", "lots"),
            ("LOOM_NEGATIVE", "-5"),
            ("LOOM_GOOD", "1024"),
        ]);
        assert_eq!(env.positive_u32("LOOM_GOOD", 1).unwrap(), 1024);
        assert_eq!(env.positive_u32("LOOM_ABSENT", 7).unwrap(), 7);
        for name in ["LOOM_ZERO", "LOOM_WORDS", "LOOM_NEGATIVE"] {
            assert!(
                matches!(
                    env.positive_u32(name, 1),
                    Err(ConfigError::NotAPositiveNumber { .. })
                ),
                "{name} must be refused rather than quietly become a default"
            );
        }
    }

    #[test]
    fn a_millisecond_setting_becomes_that_duration() {
        let env = env_of(&[("LOOM_MS", "250")]);
        assert_eq!(
            env.positive_millis("LOOM_MS", 1).unwrap(),
            Duration::from_millis(250)
        );
        assert_eq!(
            env.positive_millis("LOOM_ABSENT", 8_500).unwrap(),
            Duration::from_millis(8_500)
        );
    }

    #[test]
    fn a_reported_value_never_carries_a_newline_into_a_frame() {
        let reported = bounded("rate_limit_exceeded\nand a second line");
        assert!(
            !reported.contains('\n'),
            "the cognition wire is newline-delimited, got {reported:?}"
        );
    }

    #[test]
    fn a_reported_value_is_cut_to_a_bound_without_splitting_a_character() {
        let long = "\u{e9}".repeat(MAX_REPORTED_CHARS * 3);
        let reported = bounded(&long);
        assert!(reported.chars().count() <= MAX_REPORTED_CHARS + "...".chars().count());
        assert!(reported.ends_with("..."), "truncation must be visible");
    }

    #[test]
    fn a_short_value_passes_through_unchanged() {
        assert_eq!(bounded("invalid_api_key"), "invalid_api_key");
    }

    #[test]
    fn a_callers_own_bound_is_honoured() {
        assert_eq!(bounded_to("abcdefgh", 3), "abc...");
        assert_eq!(bounded_to("abc", 3), "abc");
    }
}
