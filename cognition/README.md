# loom-cognition

The mind: it accepts a bounded `Perception` over the Loom contract and returns a routed, provenance-stamped `Decision`.

Run it, and the body reaches it over newline-delimited JSON on a TCP socket:

```sh
cargo run --bin loom-cognition
```

It prints what it will answer with before it starts answering:

```
loom-cognition backend=openai fast=gpt-5-mini deep=gpt-5 (https://api.openai.com/v1/chat/completions, 12000ms timeout, 2048 token answers, 262144 byte requests, usage evidence off)
loom-cognition listening on 127.0.0.1:8642
```

That line is the fastest way to tell a running service apart from another one, and it never carries the credential.

## Choosing a backend

`LOOM_COGNITION_BACKEND` selects which model backend answers, and it is the only switch that changes what a decision is worth.

| Value | What answers | Needs a credential |
| --- | --- | --- |
| `scripted` (default) | A deterministic stand-in. No network, no inference. | No |
| `openai` | The OpenAI API, at the two configured model tiers. | Yes |
| `scripted-paths` | The path rehearsal's exerciser (`src/exerciser.rs`): a deterministic role per agent, several of them wrong on purpose so the body, the ledger and the gate must refuse or class them. Stamped `scripted-path-exerciser`. Only `research/tools/path-rehearsal.js` selects it. | No |

The default is `scripted` on purpose, and it is the safe local and test fallback:
a fresh checkout runs with no credentials at all, the whole test suite is hermetic, and CI's cross-runtime round trip proves the wire without buying an inference.
Use it whenever what you are working on is the contract, the bridge, or the body, and use `openai` when what you are working on is decision quality.

A misspelled value stops the service at startup rather than falling back to `scripted`.
An operator who asked for a real model and got scripted decisions would have no way to tell from the log until someone read the provenance of a decision, so the failure is made loud instead.

## Settings

Every setting is read once, at startup. A mistake in any of them exits non-zero with a message naming the variable, because a mind with no reachable model has nothing to offer any agent and should not look healthy while offering it.

### The credential

| Variable | Default | Meaning |
| --- | --- | --- |
| `OPENAI_API_KEY` | none - required for `openai` | The API credential. |

Named as OpenAI's own tooling names it, so a host that already has a working `OPENAI_API_KEY` needs nothing else.
It is read from the process environment, it is never written to a file by Loom, and it is never logged:
it lives only as an HTTP header value marked sensitive, so no `Debug` output and no error message can carry it.
Set it in your shell or your service manager's environment, and keep it out of the repository.

A value with a trailing newline is trimmed, since `export OPENAI_API_KEY=$(cat secret)` is the usual way a credential looks present and is rejected by the provider.

### The model tiers

| Variable | Default | Meaning |
| --- | --- | --- |
| `LOOM_OPENAI_FAST_MODEL` | `gpt-5-mini` | Answers routine and notable decisions. |
| `LOOM_OPENAI_DEEP_MODEL` | `gpt-5` | Answers pivotal decisions. |

Loom classifies every perception into an importance tier on the body's side, and the mind routes that tier to one of these two models.
Nearly every decision an agent takes is routine or notable, which is why the fast model is the small one; the deep model exists for the decisions an agent may not survive, and Loom is permadeath, so that is the one tier where the expensive answer is the cheap one.

Both are settings rather than choices in the code.
Nothing in the routing rule knows which model it names, so moving a tier to a different model - or pointing both at the same one - takes no code change.

### The provider

| Variable | Default | Meaning |
| --- | --- | --- |
| `LOOM_OPENAI_BASE_URL` | `https://api.openai.com/v1` | API root. `/chat/completions` is appended. |
| `LOOM_OPENAI_TIMEOUT_MS` | `12000` | Longest one provider call may take. |
| `LOOM_OPENAI_MAX_OUTPUT_TOKENS` | `2048` | Most tokens one answer may cost. |
| `LOOM_OPENAI_MAX_REQUEST_BYTES` | `262144` | Largest serialized provider request. |
| `LOOM_OPENAI_USAGE_LOG` | unset | Exclusive append-only provider token-usage sidecar for the experiment harness. |

`LOOM_OPENAI_BASE_URL` accepts `https` anywhere and `http` only on loopback, so a compatible provider on the same host is reachable for development while a credential cannot be sent in the clear across a network.
User info in the URL (a `user:pass@` before the host) is refused under both schemes, because its only effect would be to make the host you read differ from the host the request reaches.

`LOOM_OPENAI_TIMEOUT_MS` is part of an ordering that spans both runtimes:

> this timeout (12s) < the body's `readTimeout` (14.5s) < the body's bridge `timeout` (30s)

The mind answers "the model provider at ... did not answer within 12000ms; raise `LOOM_OPENAI_TIMEOUT_MS`" - bounded, and naming the setting to raise - but only if the body is still waiting to hear it.
Raising this means raising both of the others, in `dev.loom.mind.CognitionSettings`.

`LOOM_OPENAI_MAX_OUTPUT_TOKENS` is not tight, deliberately: reasoning models spend hidden reasoning tokens out of the same budget, and running out arrives as a truncated answer rather than as a clear error.
If decisions start failing as cut off, this is the setting to raise.

`LOOM_OPENAI_MAX_REQUEST_BYTES` is checked over the complete JSON request before
the socket is opened, including the standing prompt, current perception, and
strict response schema. It is both a memory bound and the conservative input
token ceiling used by the research harness when it proves that the configured
call budget cannot cross the spend ceiling.

The canonical 48-entity, 48-block, 48-item fixture is 22,292 bytes on the cognition
socket, 816 of them each block's `standable` and 1,296 each block's `yields` and `digSafe`. With `routine-v29`, it renders to 26,737 bytes of system and situation text and a 34,699-byte
serialized provider request; the strict response schema's goal choice is most of the
growth since `routine-v12` (23,931 bytes), and v17's working-goal fields (success condition,
step, step verdict) are most of the 3,566 bytes added to v13's 26,500. v18's per-request
schema adds each offered namespace once as an enum and drops every action with nothing to
name, which on this fixture almost exactly offsets the 144 enum values; v19 splits collect and
attack into one closed variant per approach mode, each naming only what that mode can reach; v20's typed goal adds the success
item and count and the step's action and ref to the goal variants, and narrows the dispositions a review is offered; v22
adds the line of what digging would drop and an enum of the success items obtainable now, capped at 48; v23 states each yield's
random range, what sight could give beyond what is held, and the agent's own drops, and drops the stated success condition and
typed step from the answer; v24 drops the goal's statement from the answer, gives each success item, now capped at 16, its own
goal shape with the counts it admits, and words the goal's rules and any refusal; v25 pairs each goal
choice with the intents it may travel with, defining the intent variants once under `$defs`, and adds
the replace shapes, which is most of its 4,341 bytes; v26 drops the free-text step from the answer, lets a
replace complete a met goal and adopt the next, and words the step, replace and abandon rules; v27 offers each goal
item only its own steps, each under its own `$defs` enums of at most four refs, offers a held goal sight cannot meet a
continue by a walk to the edge of sight, and offers no idle or rest at a goal review beside such a cell, which on this
fixture is a net 707 bytes smaller; v28 states each yield's sampled mean, offers a goal's recipe before its digs, splits
a revise's range around the held total, and words the new refusals, 192 bytes more; the widest goal review the contract
admits, sixteen items each with its own steps, names 492 of the provider's 500 enum values; v29 names at most the 16 nearest creatures to flight and attack and offers a review a flight from its four nearest hunters, because 48 creatures in reach took a contract-legal review to 539, and the widest review beside 48 hunters, eight recalled sources and eight cells at the edge of sight names 454. The same envelope at
a goal review carrying the widest legal goal (160-character statement, success condition and
step, 64-character ids and item names, a 32-character step action, all 46 inventory changes, the widest refusal it can be
told of, and all eight cells at the edge of sight at the widest number a distance can render) renders a 45,606-byte request,
19,930 bytes inside the 65,536-byte quarter of the
256 KiB ceiling that `openai::wire` tests pin. At `routine-v12`, local `o200k_base`
tokenization measured 4,842 tokens for the system and situation text and 5,895 for the
serialized request JSON; those are reproducible local tokenizer counts, not a claim about provider-billed chat framing. On an Apple M3
Pro, Corretto 21 averaged 183 microseconds to decode and 57 microseconds to encode the
cognition line over 1,000 iterations. Twenty warmed JVM passes through deterministic
selection, translation, threat-candidate construction, and all 144 request-scoped refs
averaged 1.29 milliseconds per scene. The Rust release build averaged 50 microseconds to
decode the perception, 28 microseconds to encode it, and 80 microseconds to render the
prompt over 1,000 iterations. These are local processing baselines, not provider or
network latency; remeasure them when the observation envelope, prompt, or response
schema changes.

`LOOM_OPENAI_USAGE_LOG` is normally absent. The bounded research harness points
it at a new file inside its disposable run directory. Each successful provider
response must then carry token usage; the mind appends only agent id, request id,
routed model, prompt/completion totals, and cached prompt tokens, and syncs the
record before returning the decision. The path, provider ids, prompt, answer,
and credential are never written. A stale destination or a successful response
without consistent usage is a refusal, because silently losing billing evidence
would understate a paid run.

### The socket

| Variable | Default | Meaning |
| --- | --- | --- |
| `LOOM_COGNITION_ADDR` | `127.0.0.1:8642` | Where the service listens. |

Loopback by default: the mind trusts whatever sends it a perception, so reaching it off the host should take saying so.

## What a failure looks like

The mind either decides or refuses, and a refusal is one line naming the
setting an operator would change. The examples show the human-readable field;
an OpenAI refusal also carries a `loom-cognition-failure/1` object beside it
with the routed model, HTTP status, safe provider type/code, response shape,
and Loom mapping:

```
{"error":"cognition failed: the model provider rejected Loom's credential (HTTP 401, invalid_request_error/invalid_api_key); check OPENAI_API_KEY"}
{"error":"cognition failed: the model provider does not know the model 'gpt-5-mimi' (HTTP 404, model_not_found); check LOOM_OPENAI_FAST_MODEL and LOOM_OPENAI_DEEP_MODEL"}
{"error":"cognition failed: the model provider is rate limiting Loom (HTTP 429, rate_limit_error), retry after 20; this decision was not made"}
{"error":"cognition failed: the model's answer was cut off at 2048 tokens; raise LOOM_OPENAI_MAX_OUTPUT_TOKENS"}
```

Three properties of that frame are load-bearing rather than cosmetic:

- **No refusal carries the prompt or the credential.** The provider's own error `message` is the one field that can quote back what was sent, so it is not even read; only its short `type` and `code` are repeated. A malformed answer is reported as a category and a position, never as text.
- **The classification is evidence, not authority.** The body acts only on a
  contract `Decision`; it ignores the optional `failure` object. The experiment
  proxy validates the object against the route before retaining aggregate
  counts, and never retains the provider message or request id.
- **A refusal is not a decision.** `{"error":...}` is deliberately outside the contract: a mind that could not answer has not decided anything, and inventing an idle `Intent` for it would put a fiction in the permanent decision record.
- **Nothing is retried.** One perception is at most one inference. The body's transport retries only up to the point where its request is written, precisely so a single perception can never be billed twice, and the mind holds the other half of that bargain. A lost decision is cheaper than a duplicated one.

## Testing

The suite is hermetic and credential-free, and it must stay that way: no test reaches the live OpenAI service.

```sh
cargo fmt --all --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test --all-features
```

Three levels cover the provider path:

- `src/openai/` unit tests, over the classification of every status and every malformed answer.
- `tests/openai_provider.rs`, which drives the whole mind against a mock provider on loopback - success, both tiers, 401/404/429/503, silence, an unreachable port, refusal, a cut-off answer, an oversized body - and sweeps every failure path for leaked credentials and prompts.
- `tests/openai_request_schema.rs`, which holds the schema Loom asks a model to answer in against the shared contract fixtures, so a new `Intent` variant fails until the request schema lists it.

To exercise a real credential, run the service yourself and drive it from the body with `/loombody think`; the decision appears in the server log with its model and prompt version.
