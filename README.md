# Loom

An embodied artificial-society prototype in Minecraft.

Loom gives software agents physical bodies in a shared Minecraft world and minds
driven by large language models, then studies how they survive, cooperate, and
build a small society together.

## Repository layout

This is a monorepo with two runtimes, shared contracts, and the research record:

- `plugin/` - the Kotlin Paper plugin: the physical world and the agent bodies.
- `cognition/` - the Rust cognition service: the agent minds.
- `contract/` - the shared schemas: the perception/decision contract exchanged
  between plugin and cognition, and the body-control contract the player body
  process is driven over.
- `body/` - the Node half of the body-control contract, and the Mineflayer player
  body process it drives. Runnable, and inert: nothing in Loom's runtime speaks to
  it yet. See `body/README.md`.
- `research/` - one directory per experiment, plus the versioned schema a manifest
  is validated against; see `research/README.md`, which makes everything there
  append-only, and `research/schema/README.md` for when a manifest's
  `schemaVersion` changes.

The body exposes only a bounded perception to the mind, and the mind never
reaches directly into world state. Cognition is event-driven, never per-tick, and
routes decisions to appropriately-sized models rather than always using a
frontier model. Every decision is logged, research-grade and append-only, with
its full perception, reasoning, action, and result.

## What runs today

The shared contract is in place: `contract/loom.schema.json` is its single source
of truth, with hand-written Rust and Kotlin bindings checked against it by
conformance tests and CI on both runtimes.
A second contract sits beside it, inert for now: `contract/body-control.schema.json`
carries only whether a body process is alive, which lifecycle state it is in, and
when to stop, with hand-written Node and Kotlin codecs held together by a shared
conformance corpus - the wire agreed on before either runtime speaks it.
The process on the far end of that wire is runnable too: `body/` starts one
persistent offline-mode Mineflayer player against a loopback Paper server and answers
its bounded control channel, holding no gameplay policy of its own.
It is also inert - the Paper plugin does not start it, does not connect to it, and the
Villager body is still the only body Loom's runtime drives. See `body/README.md`.

Bodies are in place on top of it. An agent can be given a body in a live world,
handed a movement intent expressed as a contract `Intent`, observed through a
bounded contract `Perception`, and removed again - driven in-game through
`/loombody`. The body flavor sits behind a single inbound port
(`dev.loom.body.BodyPort`) with one adapter behind it today, and the boundary is
enforced by architecture tests rather than convention, so a different flavor is a
new implementation of that port and one changed line of wiring.

Two world rules are already in force. Death is terminal: Loom is permadeath, so
an agent whose body dies in-world is gone for good and is never embodied again.
And living agents hold the world loaded around themselves, so a world with no
human players keeps ticking and a body's removal always means a real death.

The minds are connected now, and they are real models.
A body's perception travels to the Rust cognition service over newline-delimited JSON, the mind routes it by importance to one of two configured models, and the decision that comes back is applied to that body.
Agents drive themselves: each reviews its goals on its own on a cadence, with nobody asking it to, and `/loombody think` still triggers a round trip on demand.
Every decision carries the model that made it and the prompt version that shaped it, stamped from the route rather than claimed by the backend.
The scripted stand-in is still here and is still the default, so a fresh checkout runs with no credentials and the test suites stay hermetic; real inference is one setting away.

And the agents now have something to survive.
Each one carries an energy reserve that is spent by living, tick by tick, and reported to its mind as part of the ordinary perception; the only way to refill it is to eat one of the stacks it is carrying, and a rest holds a position and buys nothing back.
An agent that runs the reserve out dies of it, permanently, and exactly once.
The reserve is spent from server ticks rather than from wall-clock time, so a run replays identically and a paused server starves nobody.
Nothing has to be commanded for any of it: an agent meets its own hunger on the goal review it already does, and the stand-in mind reaches for the first stack a low agent reported carrying, resting it only when there is nothing to eat - so a single agent left entirely alone feeds itself while it has food, and starves once it has none.

Running from something is a decision an agent can now make.
Every living thing a perception reports carries a short handle, so a mind names one particular creature to run from rather than a place to walk to - and the body runs from that creature, rather than from whichever of two identical mobs it might otherwise have guessed at.
One such decision is a state the body then keeps: it carries the flight on across ticks, putting further ground between itself and that creature for as long as the threat is still there, and no further model call is needed for any of it.

And something hunting an agent now reaches that agent's mind at once, instead of waiting for its next scheduled review.
Every living agent looks around once a second, and a creature that hunts its kind in view earns a perception of its own carrying the handle to name that creature back by, so an agent can decide to run while running is still possible.
What that costs is bounded and every bound is recorded: a sighting is not repeated while nothing about it has changed, and each agent's night has a ceiling on the sightings worth a frontier model - so a run can always say which bound left an agent unasked, rather than letting that silence read as a choice the agent made.

A run is watchable while it happens.
`/loomwatch <agentId>` attaches one operator to one living agent and shows, as a boss bar and a few chat lines, its energy reserve and which way that reserve is going, its body's health once that body has been hurt, the bounded trigger that caused cognition, the model and prompt version that answered it, the reasoning behind the intent, and what the body did about it.
The two vitals are never aliased to one another: the reserve is what living spends and only eating refills - a rest holds a position and buys nothing back - health is damage to the body, and a body can be beaten to death on a full reserve, so a run can always say which of the two ended an agent.
Resting, moving, fleeing, a body that refused an intent, a mind that never answered, dimension travel, and permanent death are each a distinct word, so a survival run is legible without reading a JSON file.
The display is a reader of the append-only record and nothing else: it holds no body port, no cognition bridge, and no world handle, so attaching it adds no perception, no decision, no tick work, and no record, and the mind's operator-only vocabulary never travels back into what an agent is told.
See `DEMO.md` for the exact local procedure, from starting the cognition service through watching one autonomous survival cycle end to end.

See `cognition/README.md` for backend selection, the credential, and both model tiers, and `AGENTS.md` for build, test, and gate commands and for the sharp edges around the engine seam.
