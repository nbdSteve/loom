# The bounded tribe experiment harness

`research/bin/loom-tribe.js` creates one disposable, loopback-only Paper world,
runs a configured Loom fleet under explicit cognition and lifecycle budgets,
and publishes only a small sanitized artifact. It controls experimental
conditions, not agent goals. It never assigns roles, partners, hierarchy,
territory, conflict, cooperation, resource policy, or a story outcome.

The runnable configurations are the routine-v35 shelter-goal pilot,
`research/config/first-tribe-routine-v35-agency-pilot.json`, and its
provider-free twin, `research/config/first-tribe-routine-v35-agency-pilot-scripted.json`.
This primary behavior mode has no controlled crash and sends no `/loombody think`
command. Its only cognition sources are Loom's native `GoalReviewSchedule` and
native event drivers. It uses the v29 prompts, which show each agent its own
carried goal and let the model return an optional typed goal choice, and it
attests `categoryFairSelection: true` because the 48-per-kind selector it runs on
is category-fair. Since v14 a `moveTo` names an observed block by ref rather than
coordinates, because a perception never states coordinates and every coordinate a
v13 mind wrote for a refused walk named open air. v15 states where that walk ends:
on top of a solid block, or in the own cell of a block the server gives no
collision, such as a dead bush, because 24 of the v14 run's 25 walks named a dead
bush and were sent to the open air above it. v16 places a thing in the agent's own
block column - the block under its feet, a plant it stands in - by where it is
relative to the agent (bearing `here`) rather than by a compass point, because 16
of the v15 run's 18 walks went to the block the body already stood on, which the
prompt had called half a block to the north. v17 keeps both and makes the held goal a
working goal: adopt and revise state a success condition, every affirming review names
the one step its action takes, every answer to a carried goal judges the previous step
(`done`, `progressing`, `noProgress`), the JVM measures from the agent's own perceptions
whether that step changed its inventory or moved it, and a `continue` after three
affirmed steps in a row that changed neither is refused as `goalStalled` and ends the
goal. Metrics derive `goalContinuity.workingGoal` from that evidence. v16 never
published, so its configurations were replaced rather than kept historical. v18 keeps v17
and holds every action's refs to the handles that perception offered in the action's own
namespace by a per-request strict schema, never offers a block in the agent's own column
as a walk target, and carries the no-progress count through a `revise`, because r9 had five
wrong-kind refs, five walks that moved nothing, and goals reworded instead of advanced.
v19 keeps v18 and offers each action only what the body can reach with it: blocks in sight
within interaction reach to dig, place or sleep, walks only to blocks the server says a body can
stand at (a perception's block now states `standable`), and collect and attack refs within the
reach of the approach mode that names them, because r10's two refusals were a covered walk
target and a collect without moving 18 blocks off.
v20 keeps v19 and types the working goal: the success condition is an item and count the body
measures, the step is the intent's own action and ref, a step that disagrees with its intent or a
met condition left neither completed nor revised is refused before the body acts, and only
evidence that advances the condition or carries out the step resets the stall count, because the
independent r11 audit found the run's goal progress was movement and three met conditions unseen.
v21 keeps v20 and grounds each step in the perception: what the agent stands in is named as a dig
rather than a walk, the absence of any dropped item is stated, a refused decision is explained at
the next review, and a several-item goal is suggested where several are offered, because r12's
fourteen step mismatches were walks away from a bush the agent stood in and collects of drops that
did not exist.
v22 keeps v21 and holds a goal to evidence.
A success item must be one the agent can get now: held, dropped in sight, craftable, or dropped by a block in sight when dug with what it holds, which the server states per block.
A step is credited only on the evidence it produced: the exact named target in sight reached, an item gained, or a new drop in sight.
A dig that would leave the agent in a hole with no standable way out is never offered and is refused as `digWouldTrap`.
A threat flight halts routine work in flight and then flees.
The r13 audit found five agents chasing items they could not get and 22 zero-yield digs credited as progress.
v23 keeps v22 and grounds a goal in the agent's own evidence.
The body types the success condition from the item and count, and the step from the decision's own intent, so neither is the mind's claim.
A goal already met when it is set, one beyond what is held plus what sight could give, or one pursued by idling is refused.
A dig is credited for a drop only when the server attributed that drop to the dig's own request, and the digger is told of its drops wherever they went.
A block's yields state their random range, so an empty dig reads as luck.
A settled goal step owes one follow-up review soon after, at most one per scheduled review, which the harness counts under origin `nativeGoalFollowUp`.
Each configuration from v23 on states `followUpsPerReview: 1`, and preflight requires both call ceilings to hold the scheduled reviews and their follow-ups.
The r14 audit found three completions of goals met when they were set, a credit for another agent's drop, and goals that could advance at most once per two-minute review.
v24 keeps v23 and makes a held goal answerable only by evidence it can produce.
A collect approach walks to any standable cell whose pickup box covers the item, and an item the body found no route to is marked `routeRefused`, offered to nothing and counted in no goal's capacity.
The agent's own lying drops are reported and offered by ref even out of sight.
A goal refusal is told once, with its item, asked total, held count and capacity, at the next review.
A continue is held to the same obtainability rule as an adopt and a revise, and the request schema states each success item's count bounds.
A drop is the step's evidence but no progress until it is held, and a step is measured once.
An answer that acts while ignoring a held goal, a dig or collect that cannot give the goal's item, and an answer past the stall bound are refused with their intent.
The count is the total to hold, and the body words the goal from it.
The r15 audit found five `noRoute` collects of one stick, eight unobtainable refusals the mind never heard of, and a drop credit loop that kept the stall bound from firing.
v25 keeps v24 and offers only answers the body accepts.
A goal review's answer pairs its goal choice with the intents it may travel with, so adopt, continue, revise and replace never travel with idle or rest.
A goal at the stall bound may only be completed if met, or replaced by a new goal in the same answer.
While the agent's own drop of its goal's item lies in reach, the step is a collect and dig is withheld.
A walk is credited only onto a block that is itself a source of the goal's item.
What sight can be counted on to give is each block's expected yield, and its best case is stated apart.
Own drops are told until their fate is known, and every review's record states the goal and refusal the mind was shown.
The r16 audit found that the 9 remaining refusals were answers the schema still offered, and that the agent dug again or idled in 11 of 21 reviews with its own stick on offer.
v26 keeps v25 and holds a goal to positive bars set before the run.
Each perception offers up to eight cells at the edge of sight as walk targets, so an agent with nothing in sight can look further.
The body words the step from the decision's intent, so no free-text step can contradict it.
A replace of the same item at the same or a lower total keeps the stall count, a replace of a met goal completes it and adopts the next in one answer, and an abandon of a goal that what is held and seen can still meet is refused.
A step that leaves the agent's own drop of its goal's item lying owes one more follow-up review, so each configuration from v26 on states `followUpsPerReview: 2`.
A threat out of sight and more than 16 blocks off is not sighted.
The r17 audit found trivial goals, 27 of 50 scheduled reviews ending idle, nine flights from mobs out of sight 22 blocks off, and a replace that relabelled a stalled goal.
v27 keeps v26 and holds a goal to its own item.
A drop of the goal's item still lying in reach neither advances nor stalls the goal, a replace of the same item carries the stall count at any total, and a stalled goal may be replaced only by a goal for another item.
A held goal sight cannot meet is continued by a walk to the edge of sight, credited only when that walk brings a source into sight.
At a goal review beside such a cell no idle or rest is offered, so a completion travels with an action.
Each goal item is offered only its own steps, and a dig, collect, craft or walk that cannot give it, or a revise that changes it, is refused.
An unseen hunter is news only within eight blocks, and follow-ups are earned per scheduled review and spent over the run, so one is owed while an own drop of the goal's item lies and any earned one remains.
The live profile stands in a forest, where wood can be dug and crafted.
The r18 audit found every stall fired on a dig that had just dropped two sticks, all eleven completions idled, five goals abandoned to look further, and 21 of 22 goals for sticks.
v28 keeps v27 and holds a goal to substance.
A block's yield is the server's sampled mean, a goal's recipe is offered before its digs, and only a gain of the goal's item or of a direct input of a recipe for it is progress.
A revise must change the held total, a plain completion is refused beside a next goal's source, and an equip is no goal step.
A step that leaves an own drop of the goal's item lying, and a plain completion, owe a follow-up from a reserved allowance.
A dig whose drop would fall into a pocket out of reach is unsafe, and a craft the server shows fully applied is followed up even when the body refused it.
The live profile stands on plains a walk from the woods and starts in the afternoon, so wood must be looked for and night falls inside the run.
The r19 audit found ten of 34 goals were dirt one dig at a time, leaves counted as a stick a dig, 28 of 31 revises restating their total, no goal built on another, no walk and no danger.
v29 keeps v28 and makes danger and exploration answerable.
A perception that ends without an action gives its body sense back, and one refused at capacity is recorded.
A goal review may flee from a hunter near it and keep its goal, and a threat answered without a flight is asked about again.
A hunter in sight past the perception radius is no news, a farther threat waits while a nearer one is answered, and a body states a route to a threat only when it searched for one.
A dig underfoot needs a walk out of where the body lands, a review waits while the agent's own flight holds the body, and a call refused at the harness ceiling is typed.
The body remembers sources it saw recently, so a goal for an item out of sight can be set and walked toward.
Each scheduled review earns one follow-up, so each configuration from v29 on states `followUpsPerReview: 1`, and preflight requires the planned reviews to leave a third of the global call ceiling to events.
The harness tells the JVM each agent's share of the global ceiling and the reviews it plans, and the JVM keeps that share for the scheduled reviews still due: an event or follow-up that would eat into it ends as `callReserved`, a typed terminal like a coalesced one, so the night's danger can no longer stop a run before its last reviews.
The r20 audit found 17 hits on the three agents that died unperceived because coalesced perceptions held every body sense, a review beside two hostiles offered only dirt digs, 13 flights from self-dug shafts, and a plan that left the night's danger no calls.
`research/lib/experiment-mode.js` owns the pins. In the
scripted twin the stand-in digs the nearest block in reach that can drop something, walks onto a source of its goal's item out of reach or to the edge of sight when none is in reach, adopts one placeholder goal of holding one more of what it drops, continues it while it digs and once met replaces it with a goal sight still gives, or completes it when sight gives none; that exercises the goal round
trip, the follow-up review and the own-drop evidence, and is not evidence of agency.

Each primary-behavior configuration names a frozen profile, which
`research/lib/profiles.js` owns: `pilot-180s` (the r5 scene, three agents, 180
seconds or 4,000 ticks, two reviews each, 6/18/3 calls, $3.51),
`two-cycle-3` and `two-cycle-10` (the `s2-ring` scene on seed
`27183455263279`, the first three or all ten surveyed ring slots, 3,600 seconds or
48,000 ticks, up to 21 reviews each, 64/192/3 or 64/640/6 calls, $37.36 or
$124.52, and the measured log, evidence and publication bounds below). Preflight refuses any drift from the named profile. The `s2-ring`
positions are the survey's marker-confirmed slots, under its disclosed cover rule:
a collision-free, non-fluid cover block in the feet cell counts as passable, and
nothing is cleared or placed; the manifest states that rule. Their configurations
are `research/config/tribe-two-cycle-3*.json` and `tribe-two-cycle-10*.json`; the
live ones carry proposal ids and need their own explicit approval before any paid
run. `shakedown-10m-10` is `two-cycle-10` with only its window and envelope
changed: 600 seconds or 12,000 ticks, six reviews each, 24/150/6 calls, $29.19,
and 16 MiB log and publication bounds with 8,000 evidence records, about eight
times the 12,000-tick share of the ten-agent normal sample. The v13
configurations that ran `-r1` to `-r4` are `research/config/tribe-shakedown-10m-10*.json`
and are historical, as are the v14 `research/config/tribe-grounded-walk-10m-10*.json`
that ran `-r5` and the v15 `research/config/tribe-grounded-walk-v15-10m-10*.json` that
ran `-r6` and the v17 `research/config/tribe-working-goal-10m-10*.json` that ran `-r8` and
`-r9`, the v18 `research/config/tribe-offered-refs-10m-10*.json` that ran `-r10`, and the v19
`research/config/tribe-actionable-refs-10m-10*.json` that ran `-r11`, and the v20
`research/config/tribe-typed-goal-10m-10*.json` that ran `-r12`, and the v21
`research/config/tribe-grounded-step-10m-10*.json` that ran `-r13`, and the v22
`research/config/tribe-obtainable-goal-10m-10*.json` that ran `-r14`, and the v23
`research/config/tribe-own-evidence-10m-10*.json` that ran `-r15`, and the v24
`research/config/tribe-answered-goal-10m-10*.json` that ran `-r16`, and the v25
`research/config/tribe-collected-goal-10m-10*.json` that ran `-r17`, and the v26
`research/config/tribe-pursued-goal-10m-10*.json` that ran `-r18`, and the v27
`research/config/tribe-wooded-goal-10m-10*.json` that ran `-r19` in the `f1-forest-ring` scene
(seed `10001`, dry land among trees), and the v28 `research/config/tribe-explored-goal-10m-10*.json`
that ran `-r20`; `night-goal-10m-10` is the same profile under the v29 mode, in the
`e1-explore-ring` scene, with its plan cut to the five scheduled reviews a run can finish (the
sixth is due at tick 12,000 itself), one follow-up each, and its generation-12 coherence bars
pre-registered beside it; `discovery-goal-10m-10` is the same profile under the v31 and v32 modes, with
the generation-14 bars, which keep the strict substance bars and drop the three no run that
pursued anything could fail, configured by `research/config/tribe-night-goal-10m-10*.json` until r34.
The plains scene is seed `20011`, the first seed from 20001 whose ring around the origin is flat
dry land with most slots' nearest log beyond what a body senses: one tree stands 3.6 and 6.1
blocks from two slots and the woods lie 10.8 to 25.7 blocks from the other eight, so wood must be
looked for. Its ten slots were surveyed by `research/tools/survey-ring.js` from a fresh world,
and the survey reproduces them exactly. It is the one scene that does not start at dawn: its
`initialTimeOfDay` is 9000, so dusk falls about 3,500 ticks in and the hostile creatures night
spawns on open ground are part of the run; nothing is summoned and no goal or item is given.
The live one
carries the next fresh `2026-10-16-openai-10-agent-10m-r<n>` id. Each id is consumed
by its one attempt and its published artifact names the commit it ran from.
Outside the pilot profile the mechanical pilot verdict is `notApplicable`.

The other committed configurations are historical:
`first-tribe-scripted-proof.json` and `first-tribe-openai.json` (the
crash-containment proof, routine-v11 prompts) and the two
`first-tribe-routine-v12-agency-pilot*.json` files (the routine-v12
primary-behavior mode), and the `first-tribe-routine-v13-agency-pilot*.json` and
`tribe-shakedown-10m-10*.json` files (the routine-v13 goal-continuity mode), and the
`tribe-grounded-walk-10m-10*.json` and `tribe-grounded-walk-v15-10m-10*.json` files (the
routine-v14 and routine-v15 grounded-walk modes), and the `tribe-working-goal-10m-10*.json`
files (the routine-v17 working-goal mode), and the `tribe-offered-refs-10m-10*.json` files (the
routine-v18 offered-refs mode), and the `tribe-actionable-refs-10m-10*.json` files (the
routine-v19 actionable-refs mode), and the `tribe-typed-goal-10m-10*.json` files (the routine-v20
typed-goal mode), and the `tribe-grounded-step-10m-10*.json` files (the routine-v21 grounded-step
mode), and the `tribe-obtainable-goal-10m-10*.json` files (the routine-v22 obtainable-goal mode), and the
`tribe-own-evidence-10m-10*.json`, `tribe-answered-goal-10m-10*.json`, `tribe-collected-goal-10m-10*.json`
and `tribe-pursued-goal-10m-10*.json` files (the routine-v23 to routine-v26 modes), and the
`tribe-night-goal-10m-10*.json` and `tribe-balanced-envelope-10m-10*.json` files (the routine-v32 and routine-v33 configurations r34 and r35 ran). They pin prompts the current mind no longer serves, so
preflight refuses them before anything is built, started or read; the artifacts
committed under them still validate. `contract/fixtures/capability-support.json`
is the one classification of which configurations, modes, intents, triggers and
feature flags are runnable, supported, dormant or historical. Every feature flag
must be `true`, because no runtime route reads one and a `false` would be a false
manifest claim.

## Reproduce from a clean checkout

Prerequisites are Java 21, Node 22 or newer, npm, Rust/Cargo, and `lsof`.
The checkout must be clean so the manifest's source commit names all code that
produced the run. On macOS the harness discovers Java 21 with
`/usr/libexec/java_home`; elsewhere set `LOOM_JAVA_21` to the Java 21
executable.

```sh
npm --prefix research ci
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-routine-v35-agency-pilot-scripted.json
```

The default path verifies and caches the pinned Paper jar, installs and checks
the body package, builds the release cognition binary, runs the plugin checks
and shaded build, creates a temporary server, runs the proof, validates the
publication, and removes the temporary server. A previously verified local
Paper jar may be supplied with `--paper-jar`; its SHA-256 must still match the
configuration. `--skip-build` is for a repeated local rehearsal only and fails
if the expected build outputs do not exist.

The command refuses an existing artifact directory rather than overwriting
evidence. It builds and validates the artifact in a hidden `.publishing-*`
sibling and renames it into place only once it is valid, so a partial
directory never appears under the experiment id. Rehearse into a disposable parent directory, delete that parent after
inspection, then run once without `--artifact-root` to publish:

```sh
npm --prefix research run tribe -- run \
  --config research/config/first-tribe-routine-v35-agency-pilot-scripted.json \
  --artifact-root .scratch/routine-v35-agency-pilot-rehearsal \
  --skip-build
```

Validate any generation-3 artifact independently:

```sh
npm --prefix research run tribe -- validate \
  research/experiments/2026-10-12-scripted-tribe-crash-proof
```

## What is fixed

The configuration pins the experiment id, Paper and Minecraft versions, Paper
hash, world seed, border and loaded distances, canonical agent set, consecutive
control ports, difficulty and mob-spawning rule, model/provider ids, prompt
versions, provider endpoint and timeout, integer nano-USD token prices, feature
flags, and every resource ceiling. Primary behavior mode additionally pins the
three r5 positions, an exact initial world clock, empty inventories, the native
2,400-tick cadence and 100-tick slices, and the absence of injected roles,
resource targets, social goals, hierarchy, pairings, conflict, and scripted
objectives. The plugin derives each `loom_<hash>` username and offline UUID
from the sorted agent id set. The publisher independently derives the same
mapping and rejects a manifest that disagrees.

All listeners use the literal `127.0.0.1`, Paper runs in offline mode, and the
artifact records zero real player accounts. Never relax those properties for
this harness: offline Minecraft identity is safe here only because no listener
is network-reachable.

## Bounds and call accounting

The run configuration limits:

- total startup time, startup concurrency, and body startup attempts;
- experiment wall time and simulation tick span;
- forwarded model calls per agent and globally;
- globally outstanding cognition, with excess work refused rather than queued;
- provider output tokens and maximum external spend;
- the complete serialized provider request before a paid socket is opened;
- world size, border, view distance, and simulation distance;
- each captured process log, the append-only harness journal, evidence records,
  and the complete publication size;
- graceful shutdown and forced termination time.

The proxy counts a call only when it forwards one perception upstream. A
budget refusal is a separate normalized record. One accepted request is never
retried by the proxy or provider; the existing body transport retains only its
single pre-write retry, which cannot duplicate a billed request. Those three
limits are explicit in the manifest. Preflight prices the worst possible
configured call against the request/output bounds and refuses a spend ceiling
too small to cover every allowed call, so the ceiling does not depend on an
average token estimate. At run time the proxy also reserves that worst-case
price for every forwarded call and refuses, as the `spend` bound, a call whose
reservation would cross the ceiling. The plugin still treats every decision as untrusted
typed intent and validates request-scoped references against fresh body and
server state.

The crash-containment requirement owns part of both cognition ceilings. Before
the configured crash, the proxy keeps one call unavailable under each peer's
per-agent ceiling and keeps their combined reserve unavailable under the global
ceiling. Once the body disconnect is recorded, the reserve is released, but
each peer retains its own share until it has forwarded a post-crash call. This
prevents ordinary scheduled reviews or event-driven traffic before the
intervention—and one busy peer afterward—from consuming the calls the
containment check requires.

Cognition remains event-driven. The proof uses `/loombody think` to produce
bounded goal-review events; it does not create a per-tick call path. The normal
driver's threat and scheduled-review triggers remain unchanged.

Primary behavior mode does not use that proof intervention or reserve. Its
planned native schedule is six calls: two reviews for each of three agents. Its
preflight rejects a configuration unless those calls fit both the per-agent and
global ceilings. The historical routine-v12 pilot permitted four calls per agent,
twelve globally, and three outstanding. The routine-v13 pilot permits six per agent under the same twelve
global and three outstanding, because a live run saw daytime threat sightings
draw three flight calls from one agent before its second review; the global
ceiling, and so the worst-case spend, is unchanged. Preflight also refuses more cognition bridge workers than outstanding
slots, because the bridge would then send a fourth concurrent request that the
proxy can only refuse; with three workers that request waits in the bridge's
bounded queue instead. Every forwarded or refused cognition record carries either
`nativeGoalReviewSchedule` or `nativeEventDriver` as its origin. Publication
also requires the one run-level attestation that the harness issued zero think
commands and that native schedulers were the only cognition source.

## Measured two-cycle budgets

The two-cycle profiles' ceilings come from four provider-free rehearsals on the
`s2-ring` scene at 40 ticks per second, not from estimates. The structure runs
are peaceful, so every agent lives the whole 48,000 ticks; the normal-difficulty
samples are where calls, deaths and danger episodes are measured.

| Run | Ended | Calls | Records | Evidence | Body log | Paper RSS | Startup |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3 agents, peaceful | tick 48,061 | 60 | 266 | 0.61 MB | 0.53 MB | 1.43 GB | 14.2 s |
| 3 agents, normal | all died, tick 17,313 | 41 | 368 | 0.74 MB | 0.65 MB | 1.28 GB | 22.2 s |
| 10 agents, peaceful | tick 48,015 | 201 | 858 | 2.29 MB | 2.03 MB | 1.31 GB | 18.2 s |
| 10 agents, normal | all died, tick 16,734 | 125 | 1,343 | 2.85 MB | 2.54 MB | 1.41 GB | 16.8 s |

Graceful shutdown took 2.2 to 3.1 seconds. The normal runs coalesced 15 and 53
repeated danger perceptions; the ten-agent run peaked at four outstanding calls
against its six.

The per-agent ceiling is the part a run is most likely to meet. The busiest
agent forwarded 11 danger calls and 7 reviews in its one night, so an agent that
lives through two nights and all 21 reviews needs about 43; 56 is that with a
quarter of headroom. The global ceilings are about one and a half times (three
agents) and one and a quarter times (ten agents) the extrapolated mean of 34
and 32 calls per agent. The log, evidence-record and publication bounds are at
least four times the need extrapolated to 48,000 ticks with every agent alive.
Paper's 1 GiB heap held three agents; ten use 2 GiB. A real-clock sample ran at
19.9 ticks per second, so 48,000 ticks fit the 3,600-second wall bound. Any
refusal under these ceilings is a typed controlled stop, never a silent loss.
From routine-v23 each scheduled review may owe one follow-up review, so the ten-agent profile's
210 planned reviews became 420 and its global ceiling 440, with the spend ceiling at 440 calls at
the worst-case price. That is re-sized for the follow-ups and not re-measured; a run under it
should be treated as the next measurement.

## The balanced scene

From r34 the ten-minute shakedown runs the `balanced-envelope-10m-10` profile on `e2-balanced-ring`: `e1-explore-ring`'s seed, positions, border, difficulty, population, bounds, bars and plan, starting at time 6000 rather than 9000.
The r33 audit found the 9000 start left about two daylight reviews per agent, so its pursuit bars mostly measured night survival.
The profile pre-registers its exposure exactly, 7000 daylight ticks before dusk at 13000 and 5000 night ticks after it, and preflight refuses a configuration whose start and tick ceiling do not hold it (`exposureOf`).
The `e1-explore-ring` scene and the profiles on it are unchanged, so r19 to r33 validate as published.
r35 ran the balanced shakedown from `research/config/tribe-balanced-envelope-10m-10.json` under the routine-v33 mode, and r34 the same profile from `tribe-night-goal-10m-10.json`, whose name came from the profile before it; those files and their twins are kept unchanged under the names their artifacts state, and are historical because they pin routine-v33 and routine-v32.
From r36 the shakedown runs the `balanced-follow-up-10m-10` profile, configured by `research/config/tribe-balanced-follow-up-10m-10.json` and its provider-free twin under the routine-v34 mode: the balanced envelope's scene, bounds, exposure and bars, with two follow-ups a scheduled review in place of one, because with one the review a settled step owes was kept back for an owed one and 26 of r35's 60 day goal steps waited a whole cadence for their next review.
From r37 it runs the `shelter-goal-10m-10` profile, configured by `research/config/tribe-shelter-goal-10m-10.json` and its provider-free twin under the routine-v35 mode, and the r36 configurations are historical: the balanced follow-up profile's scene, bounds, exposure, plan and every bar unchanged, with one bar pre-registered beside them before any run, `shelterProgressAgentsBeforeNight` at 3, because all ten of r36's agents died at night in the open and none ever held a goal a hiding place could meet.
A profile may pin its own `followUpsPerReview` in place of its mode's, and the JVM spends up to that many on ordinary steps a review, past the one it keeps for an owed review.

## Tier fallback

Once the JVM's pivotal ceiling no longer fits a damage or threat-sighting perception, but its agent's own and the global ceiling still do, the JVM sends it at the ordinary tier and journals one `cognitionTierFallback` body record with outcome `tierFallback` (`CallReserve.Admission`).
The answer's provenance names the ordinary model and prompt it was routed to, the proxy reserves it at the ordinary tier's price, and preflight's worst case already counts it, because it is one of the global ceiling's ordinary calls.
No ceiling is raised, and a perception no ceiling fits is still a typed `callReserved` refusal (r33: alpha's damage was refused at the pivotal ceiling with 138 global calls unspent).

## Controlled stops

In primary behavior mode two outcomes end a run without making it an integrity failure.
A typed `perAgentCall`, `globalCall`, `globalOutstanding`, or `spend` refusal is a `cognitionBudgetExhausted` stop.
A provider failure that cognition classified as `providerTimeout`, `providerConnection`, `providerResponseRead`, `providerTransport`, `providerRateLimit`, or `providerServer` is a `providerUnavailable` stop.
An HTTP 401 or 403 still stops the run immediately as a failure, and every other cognition failure is still fatal.
A call already in flight when a provider stop closes admission, which the same outage then fails with a classification of that set, is part of the stop rather than a failure of its own; the proxy marks such a forwarded record `providerUnavailable: true`, a closed public fact beside the classification evidence does not keep (r31: five calls timed out within twelve seconds, and the four in flight ended the run untyped).
`research/lib/controlled-stop.js` owns these closed sets.

On a stop the proxy journals one `cognitionAdmissionClosed` record before it answers, and refuses every later request under the `admissionClosed` bound.
The harness then waits, inside an explicit bounded drain, until no forwarded call is outstanding and every perception has a terminal body record.
A drain that does not settle is the fatal `cognitionDrainTimedOut`.
Cleanup and usage import then run as for any completed run, and the publisher writes an artifact with stop reason `controlledStop`.

Its manifest, metrics, and README carry a `controlledStop` section: the stop kind, the exact refusal or provider classification, the calls forwarded and outstanding at the closure, planned versus completed native reviews, complete and refused chain counts, provider usage and spend, and behavioral verdict `no-go`.
Validation derives the owed reviews from the stop window: review k of each agent may be due from k cadences after the start, and must be due one population stagger plus one slice after that.
It refuses a stop with no matching refusal or classification, an untyped refusal, an impossible count, any refusal that is not the first before the closure, a missing earlier chain, provider usage that does not reconcile, a dirty shutdown, and a killed process.
Crash-containment evidence can never claim a controlled stop.

A normal duration or tick-ceiling ending in primary behavior mode closes admission the same way, with one `cognitionAdmissionEnded` record, and drains inside the same bound before shutdown, so a decision in flight at the deadline completes instead of becoming an incomplete chain.
Requests refused after that record are typed `admissionClosed` refusals and are not owed an answer.
In the same turn the harness sends `loombody end-exposure`, and the plugin ends the run's exposure in one main-thread step (`ExposureGate`): it stops every native cognition source and journals one `exposureEnded` record per agent on one tick, and from then on cancels every damage to a managed player and every creature taking one as its target, sends nothing new to the mind, applies nothing new, and owes no follow-up or pickup.
Work already admitted drains to typed terminals - a perception already captured ends as `cognitionUnavailable` and an answer already with the mind as `decisionDiscarded`, both `exposureEnded`, and a movement or interaction already running ends as its own report says - and the drain also waits for every agent's `exposureEnded` record.
r34's delta and gamma were shot dead by skeletons 78 and 124 ticks after admission closed, in the drain, and both deaths counted.
Validation then owes each agent the scheduled reviews the window up to the ending made due, so a slow host that ends before a second review is due still publishes; the behavioral gate still requires the full configured plan.
A due review the agent could not take, behind its own review or follow-up, its flight, or a danger the mind was deciding, is journalled once as `goalReviewDeferred` with that reason, and one still held when the window closed counts toward what the window owes (`heldAtEnd`), because it was owed rather than lost.
A held review delays only itself: the JVM counts the next from when this one was due, so holds never add up across a run (v32 plains rehearsal 14 ended with three agents owing reviews held behind flights).
A refused integrity check stops a run as `publicationRefused` (`artifactIntegrityFailed`), and its failed-run diagnostic states each agent's window counts (`cognition.nativeReviewWindows`); a diagnostic over its byte bound halves its timeline before it falls back.

## Danger episodes

A threat or damage perception the JVM coalesced into the agent's active danger episode ends its chain as `cognitionUnavailable` with outcome `dangerCoalesced`; the mind was deliberately not asked, so it is neither a failure nor a call. Metrics derive a `dangerEpisodes` section counting them by agent and trigger, present only when one occurred. `research/lib/danger.js` owns the rule.

## Witnessed deaths

In primary behavior mode a server-witnessed `died` record is an experiment outcome, not a failure.
That agent's chain ends there and its peers continue under the same deadline.
After its death only that agent's own `bodyDisconnected` (at most once), `decisionDiscarded`, and a `damageWitnessed` that asked no cognition (`damageDiedBeforeCognition` or `damageAgentDead`, such as the killing blow), and the typed settlement of a request decided before the death (its one `intentApplied` or `decisionDiscarded`, at most one `moved` and one `retreating` report for it, and after its `intentApplied` at most one `worldInteraction`, the server's witness of what that request did while the agent lived, since the JVM audits an interaction when its report arrives and a dead player can take no action) may follow; a settled request is listed in `witnessedDeaths` as `settledAfterDeath` and never counts as behavior, and a `worldInteraction` of any other request, or before its settlement, or anything new after the death is fatal; anything else for it, a second death, or a disconnect or discard of an agent that did not die, stays fatal.
Witness disputes, revivals, denials, quits, and an unsafe ledger stay fatal as well.
The publisher derives a `witnessedDeaths` section, present only when a death occurred, and owes a dead agent only the scheduled reviews its life window made due, by the same rule as a controlled stop.
Survivors still owe the full configured plan. When every agent has died there is nothing left to observe, so the run stops with `allAgentsDied` and publishes; validation requires every agent's death on record.
`research/lib/witnessed-death.js` owns the rule.

## Behavioral gate

Every primary behavior publication derives a `behaviorGate` section in `metrics.json`, copied into the manifest and summarized in the README.
`research/lib/behavior-gate.js` owns it.
It measures meaningful behavior per agent (a non-idle native goal-review action the body accepted and later evidence confirmed: its own server-confirmed interaction, a movement report, or a food change for an eat), typed goal continuity, accepted and confirmed action counts, per-kind omissions, night perceptions by world-clock day, calls by agent, peak outstanding, spend, and cleanup.
The mechanical pilot verdict is `pass` only when at least two agents show meaningful behavior, at least one carries a typed goal forward, every agent completed the configured reviews, no failure evidence exists, and no JVM cut occurred.
A controlled stop is always `no-go`.
Generation 2 of the gate, named in the stimulus attestation, also breaks server-witnessed native interactions down by operation into confirmed and disputed counts; evidence published before it names no generation and derives generation 1, so committed metrics stay re-derivable.
A mechanical `pass` still names the criteria only a reader can judge, so it is necessary for the pre-registered gate and not sufficient.

Generation 3 adds `behaviorGate.readiness`, owned by `research/lib/readiness.js`, and a rate is not its target.
A run is `ready` only when decision validity, typed terminal accounting and server truthfulness are each exactly 1 and every blocker count is zero.
Every non-passive decision ends in one class, joined by request key to the record that ended it: `confirmed`; a legitimate race (`targetRace` for a target that went or changed, `superseded` for one the agent's own later decision cancelled, `concurrent` for `controlNotReady`, `death`); or a blocker (`wrongKindRef`, `noOpTarget`, `inapplicable`, `unknownTimeout`, `incompleteMovement`, `falseSuccess`, `protocolError`, `staleRef`, `unconfirmed`, `untypedTerminal`, `unexplainedRefusal`).
`actions.nonSuccess` lists every action that did not succeed, races included, with the exact record that ended it.
A walk or flight's `moved` and `retreating` records carry its request id and a typed ending (`movementArrived`, `movementStalled`, `movementTimedOut` and so on) for this join, and an approaching collect or attack states its journey against its target in the interaction record.
A test reads the JVM's refusal tags and fails if any would classify as unexplained.
`readiness.goalCoherence` counts the affirmed steps the body measured as having changed the agent's inventory or place, the goals advanced by two or more such steps, and the goals completed after at least one.

Generation 4 (`loom-readiness-gate/2`) makes coherence part of the verdict, because r11 was mechanically `ready` and the independent audit found no evidence-driven multi-step pursuit.
`goalCoherence` is then read from the typed goal: steps that advanced the success condition, steps that only carried out the typed step, steps that advanced nothing, met conditions and how each was answered, step mismatches, unmet completions and stalls.
It is `coherent` only with no step mismatch, no unmet completion, no met condition left unanswered, and at least one goal whose condition advanced at two or more steps; `verdict` is `ready` only when the mechanical half (`mechanicalVerdict`) and coherence both pass.
A decision the goal ledger refused is the `goalIncoherent` blocker and an invalid decision, even when its intent was passive.
`test/readiness-coherence.test.js` holds r11's own evidence to that gate, where it is `notReady`.

Generation 5 (`loom-readiness-gate/3`) fails coherence directly on two things r13 showed generation 4 could pass by luck.
A credited step must state the evidence it produced (`stepEvidence` of `arrived`, `gained` or `dropped`), so a credited step with `none` or no evidence at all is counted in `unevidencedCredits`.
A goal refused as `goalSuccessUnobtainable` is counted in `unobtainableConditions`; it is also the `goalIncoherent` blocker and an invalid decision.
Coherence needs both counts at zero beside generation 4's rules, and a `digWouldTrap` refusal is `inapplicable`.
Generations 3 and 4 are frozen, so r10 to r13 re-derive exactly as published; the same test file holds r13's evidence to generation 5, where all 37 of its credited steps are unevidenced.

Generation 6 (`loom-readiness-gate/4`) also refuses a goal already met when it is set (`goalAlreadyMet`, counted in `alreadyMetConditions`) and one pursued by idling (`goalStepIdle`, counted in `idleSteps`); both are the `goalIncoherent` blocker and invalid decisions, and coherence needs both counts at zero.
From the same generation the behavior gate counts a review action as meaningful only when its decision adopted, continued or revised a goal, and counts the follow-up reviews' actions beside the scheduled ones.
Its spatial findings state what each measure counts: paired self-position samples taken within 20 ticks of each other, and perceptions that showed another player within eight blocks (`spatial.playerSightings`).
Generation 5 is frozen, so r14 re-derives exactly as published.

Generation 7 (`loom-readiness-gate/5`) also refuses an answer that acts while ignoring its held goal (`goalChoiceMissing`, counted in `unansweredGoals`), a dig or collect that cannot give the goal's item (`goalStepUnproductive`, counted in `unproductiveSteps`) and an answer past the stall bound (`goalStalled`); all three are the `goalIncoherent` blocker and invalid decisions, and coherence needs the first two counts at zero.
A `moveNoRoute`, `retreatNoRoute`, `collectNoRoute` or `attackNoRoute` refusal is the ground's answer and the legitimate `terrain` class rather than `inapplicable`, because the decision could not have known the route was missing; after a collect's the JVM marks the item so the agent is not offered it again.
A step a review measured is measured once, so a review that took no new step carries no `stepEvidence`, `stepAdvanced` or `stepsWithoutProgress` of its own.
Generation 6 is frozen, so r15 re-derives exactly as published.

Generation 8 (`loom-readiness-gate/6`) also refuses a continue or revise that does not collect the agent's own lying drop of its goal's item (`goalOwnDropIgnored`, counted in `ignoredOwnDrops`); it is the `goalIncoherent` blocker and an invalid decision, and coherence needs the count at zero.
A replaced goal (`goalReplaced`) counts its action as a step of the new goal for the behavior gate.
Generation 7 is frozen, so r16 re-derives exactly as published.

Generation 9 (`loom-readiness-gate/7`) adds positive bars, because r17 cleared generation 8 while the independent audit found trivial goals, idle reviews and needless flights.
The run's profile pre-registers its `coherenceBars`, and `goalCoherence.pursuit` states each bar beside what the run measured: the share of goal reviews that held or took up a goal and stepped toward it, the reviews that held no goal and idled beside offered cells at the edge of sight, the agents with a goal whose condition advanced at two or more steps at its original, never cut, target, and the completions at an original target that needed two or more beyond what was held when adopted.
Coherence needs every bar cleared, and no bars stated clears none.
A safe flight from another managed player is the `peerFlight` blocker, and one from a threat neither in view nor within 16 blocks that gained under four blocks of separation is the `needlessFlight` blocker; a `retreating` record states the separation and the separation gained.
An abandon of a goal that what is held and seen can still meet (`goalAbandonedAchievable`) is the `goalIncoherent` blocker and an invalid decision.
Generation 8 is frozen, so r17 re-derives exactly as published; `test/readiness-pursuit.test.js` holds r17's evidence to generation 9, where it is `notReady` with nine needless flights, one peer flight and its pursuit bars not cleared.

Generation 10 (`loom-readiness-gate/8`) answers the r18 audit, which found the generation-9 measures miscounted what they named.
A flight counts only when it answered damage, a threat in view, or one out of sight within eight blocks; the separation it then gained no longer rescues it.
A revise that changes the goal's item (`goalReviseItemChanged`) is the `goalIncoherent` blocker and an invalid decision.
The pursuit bars add the least agent's share of goal-active reviews, the fleet's and the least agent's share of time holding a goal, the kinds of item goals were set for, and accepted goal steps that crafted.
An idle or rest beside a cell at the edge of sight counts whatever its goal choice, an original-target completion needs two condition advances and two beyond what was held or lay as the agent's own drop when the goal was set, a cut sight forced (the old total above what the review held plus what sight could give, from `targetHeld` and `targetCapacity`) is no cut, and a revise to another item is one.
A complete-and-adopt answers a met condition.
Generation 9 is frozen, so r18 re-derives exactly as published; `test/readiness-wooded.test.js` holds r18's evidence to generation 10, where it is `notReady` with four needless flights and its pursuit bars not cleared.

Generation 11 (`loom-readiness-gate/9`) answers the r19 audit, which found generation 10's bars cleared by goals with no substance.
A revise that restates its shown total (`goalReviseUnchanged`) and a plain completion beside a next goal's source (`goalCompletedWithoutNext`) are `goalIncoherent` blockers, and a `collectNoRoute` to the agent's own lying drop no more than two blocks above its feet is the `ownDropUnreached` blocker rather than terrain.
The pursuit bars count goal activity without restated revises, the fleet's and the least agent's share of time holding a goal that advanced within the last 2,400 ticks, multi-step agents and completions only for goals sight did not guarantee one dig at a time or that a later goal built on, goals chained to an earlier met goal whose item their recipe uses that then advanced, craft steps of chained goals, the agents that walked to an offered cell at the edge of sight, and the perceptions that answered a threat sighted or damage taken.
`goalCoherence.schemaGuarantees` reports apart the counters the request schema holds at zero, because a zero there is a property of the schema and not behaviour.
Generation 10 is frozen, so r19 re-derives exactly as published; `test/readiness-substantive.test.js` holds r19's evidence to generation 11, where it is `notReady` with 28 restated revises, three own-drop route refusals, one substantive multi-step agent, no chained goal, no exploration walk and no danger met.

Generation 12 (`loom-readiness-gate/10`) answers the r20 audit, which found the generation-11 bars cleared on one-step goals, on chained crafts the adopting craft itself met, on perceptions counted as danger, and on held time after a death.
A goal counts toward the substance and chain bars only when it needed two or more beyond what was held and lay, was not re-adopted at or below a total the same agent had cut from, and - when chained - advanced at least twice; a craft steps a chain only after the decision that adopted it; "local" means sight guaranteed the whole count one dig at a time.
Held time ends at each agent's death, danger is the danger perceptions that reached a decision (`dangerEpisodes`, with `deaths` beside it), and a review that fled a hunter is goal activity.
A no-route flight by an agent that had dug a block below its feet within a step is the `selfTrapped` blocker rather than terrain, and the two review shares are reported as `schemaShaped`, because the schema offers no idle while a goal is held.
Generation 11 is frozen, so r20 re-derives exactly as published; the same test holds r20's evidence to generation 12, where it is `notReady` with 13 self-trapped flights, 28 one-step goals, 13 re-adopted goals, no chain, no substantive completion and three deaths.

Generation 16 (`loom-readiness-gate/16`) answers the r27 audit, which found three places where generation 15 read the run wrongly.
A flight's interval, from its decision to the record that ends it, is no longer progressing goal time.
A refusal for reach or sight (`OutOfReach`, `OutOfRange`, `NotVisible`) is the `ownMovementRace` race when a movement of the agent's own ended between the refused decision's perception and its refusal.
A no-route whose perception shows water in the body's own cells is the `waterTrapped` blocker rather than terrain, and a dig refused as `digFloodsBody` is `inapplicable`.
Generation 15 is frozen, so r27 re-derives exactly as published; `test/readiness-substantive.test.js` holds r27's evidence to generation 16, where iota's dig is a movement race, epsilon's four walks out of its flooded pit are `waterTrapped`, and delta's peer flight stays a blocker.

Generation 17 (`loom-readiness-gate/17`) answers the r33 audit.
A `goalDeferred` decision beside a met goal is counted as `metDeferred` and never as `metIgnored`, because a survival deferral allows no goal choice.
Each danger answer newer damage preempted (`dangerPreempted`) is a `superseded` action the gate counts, rather than a paid answer it cannot see.
A `controlNotReady` flight refused while another of the agent's own flights still ran is the `dangerOverlap` blocker rather than a `concurrent` race.
The report counts the JVM's `callReserved` refusals, `tierFallback` sends and preempted answers, and the README states them.
Generation 16 is frozen, so r33 re-derives exactly as published; `test/readiness-substantive.test.js` holds r33's evidence to generation 17.

Generation 18 (`loom-readiness-gate/18`) answers the r34 audit.
Work that drained after the run's exposure ended is typed `exposureEnded` and is never a cognition failure, and the report states the exposure's end tick, its source, and every death and damage after it (`exposure`); a death after the end makes the run mechanically `notReady`.
A flight an older danger answer started, refused or cancelled because a newer danger answer governs the body, is the superseded `retreatStale`, counted apart.
A collect or attack whose approach stalled or over-travelled is the body's `incompleteMovement`, as a walk's or flight's is, never the decision's `inapplicable` fault.
A collect's no-route is the ground's answer only when the server verified it - no cell a body stands in would pick the item up (`terrain.pickupCells` 0 on its record, `pickupCellsOf`), or no walk over the server's ground from where the body stood reaches one (`terrain.walkable` false, `walkReaches`) - and is otherwise the `unverifiedTerrain` blocker, or `ownDropUnreached` for the agent's own drop.
Generation 17 is frozen, so r34 re-derives exactly as published; `test/readiness-r34.test.js` holds r34's evidence to generation 18, where delta's four blockers are one stuck body's incomplete movements, delta's and gamma's deaths fall after the exposure, and the three no-routes to one lodged log are unverified until the ground is read.

Generation 19 (`loom-readiness-gate/19`) answers the r36 audit.
A shelter goal names no item and is counted apart from item goals, as a discovery goal is, so no item-chain measure moves for it; its `covered` step is credited evidence, and a shelter set where none was offered, a resource subgoal fed where none could feed a shelter, or a subgoal replaced from under its shelter is goal-incoherent.
The report adds `shelter`, measured from the agents' own goal evidence and the server's `shelterChanged` record of when each was sealed in: the pre-registered `shelterProgressAgentsBeforeNight` - agents whose own adopted shelter goal was credited a `covered` step before nightfall, the step accepted by the body and, for a dig or a placement, confirmed by the server's interaction witness - and, reported beside it, shelter goals, completions, the most sides each agent's shelter closed, the ticks agents spent sealed in from nightfall to the exposure's end, damage witnessed while sealed in, and the agents alive, and sheltered, at that end.
A profile that pre-registers the bar is `ready` only when it is met; nightfall is the first daylight perception's tick plus the ticks it stated to nightfall.
Generation 18 is frozen, so r36 re-derives exactly as published (`test/readiness-r36.test.js`), and `test/readiness-shelter.test.js` holds r36's evidence to generation 19, where no agent held a shelter goal.

## The path rehearsal

`research/tools/path-rehearsal.js` is a deterministic live rehearsal of the goal paths no research run has reached by itself, and it is not a research run.
It starts one real Paper server with the Loom plugin and ten real bodies on a flat world laid out by the console commands in `research/tools/path-rehearsal.json`, with the cognition service answering as the path exerciser (`LOOM_COGNITION_BACKEND=scripted-paths`).
Each agent walks one path, and the run's own evidence, normalized and judged by readiness gate generation 19, must show each one as the server witnessed it.
The paths are: a craft of two applications accepted and confirmed, and a goal completed and the next adopted in one answer, chained to the goal whose item its recipe uses; an own drop ignored and refused; that drop still told lying more than 4,800 ticks after the dig; a collect without moving that vanilla's own pickup ends; a walk to the edge of sight credited `revealed`; a walk to the cell beside a log that walk showed out of dig reach credited `arrived`; a revise to another item refused; a revise to the held total refused; a plain completion beside a source refused; a plain completion with nothing in sight accepted and followed by the review it owed; a block beside the support under an overhang reported unsafe to dig; a drop in a one-high nook beside a one-cell pit, just past vanilla's reach from where the agent stands, collected by a final step inside the pit cell; a goal review beside a creeper that cannot move fleeing it with no goal choice; and, once a console command brings dusk 1,500 ticks in (`timed`), a shelter goal set as night nears, dug down through the grass and two dirt layers and roofed with the dirt those digs gave, each step one its cover reading named and credited `covered` before nightfall, sealed in by the server's ground (`shelterChanged`) and completed there.
The creeper stands 9.1 blocks east of its agent along the border, past the 8 that make a hunter immediate danger, because the exerciser answers any sighting of an immediate hunter with a flight, as the body requires, and one that close would send the agent off before its review.
A flight from a peer is no longer rehearsed, because no request schema offers one: `flee` may name only a hunter, a sighted threat or the creature that hurt the agent (r27 delta), and the gate's `peerFlight` class stands for any that reached a body anyway.
No other decision may fail, no claim may be disputed, and no action may end in a class the paths do not explain.
Its window closes as a research run's does: the rehearsal sends `loombody end-exposure` and stops Paper only once every agent's `exposureEnded` record is written and every perception sent before it has its one typed terminal, within 60 seconds, so no decision is exempt for coming late and `untypedTerminal` must be zero.
Vanilla picks up an item inside the pickup box within half a second and despawns a lying one after 6,000 ticks, which is why a collect without moving uses a summoned item with a pickup delay, and why a peer's spoken line, timed by the review stagger, gives the dropping agent a perception between 4,800 and 6,000 ticks after its dig.
Its world keeps hostile mobs, so the summoned creeper stays, but spawns none: Bukkit's monster spawn limit is zero from the first tick, because superflat ground lies below y 40, where slimes spawn in any light.
Its world is built for the paths, its mind is scripted, and nothing is published: it writes one report under `.scratch/path-rehearsal/` and exits non-zero unless every path is covered.

```sh
node research/tools/path-rehearsal.js --skip-build
```

## Lifecycle and cleanup

Before it creates the disposable server or reads a credential, the harness refuses to start unless the run volume has at least 2 GiB free, or more when the configured log and artifact bounds need it.
No host state delays or refuses a run: not load, swap, memory pressure, battery, lid, display or power state, before it starts or while it waits.
A suspension the run itself measures is different, because the clients and timers stopped with the host: if the host is suspended mid-run (a closed laptop lid enters clamshell sleep, which no assertion prevents), the primary-behavior poll loop sees a wall-clock gap of more than ten seconds between its 250 ms polls and ends the run integrity-invalid as `hostSuspended`, whether or not anything else failed after it, with the usual cleanup and only the bounded private diagnostic retained, rather than as a body failure, and it is never published as a valid artifact: a rehearsal that slept for 422 seconds was cut when every Mineflayer client timed out on wake.
Each run directory is seeded from a verified Paper runtime cache under `.scratch/paper/runtime-*` (the Mojang server jar, libraries, patched server and remap cache, each file checked against the cache's own manifest), built once by a bare loopback Paper start, so startup no longer downloads and remaps on every run.
Paper, its world, the JVM, the bounded logs, and publication all write there, and a volume that fills mid-run loses the run.

The harness starts cognition, the bounded proxy, and Paper in that order. Paper
then starts the canonical fleet through Loom's existing bounded startup gate.
In primary behavior mode Paper starts with `LOOM_NATIVE_COGNITION=held`, so no
scheduled review, threat scan or damage witness runs until the harness has
applied the initial conditions and sent `loombody begin` after them. Without the
hold, the first agent's first review could arrive while later agents were still
starting, before the conditions existed; the `-r2` shakedown failed that way.
One agent has at most one scheduled review and one event with the mind at a time: a
further event is ended as `cognitionUnavailable` with outcome `cognitionCoalesced`
(never sent, not a failure, counted under `cognitionCoalescing`), and a review that falls
due while the agent's previous review is still unanswered is deferred to the first slice
it fits rather than claimed into a full per-agent budget. The `-r7` shakedown failed as
`agentBudgetExhausted` because neither held.
A danger answer that newer damage preempted while it was with the mind is a
`decisionDiscarded` with outcome `dangerPreempted`: superseded, not lost, so it stops no run,
needs no death and is counted as `cycles.preempted` rather than as a discarded cycle. The retained failure diagnostic keeps the
most recent cognition traffic (`cognition.timeline`: per forwarded call its trigger,
origin, outcome, outstanding count and latency; per body record its tick and outcome).
It also keeps the stop window (`cognition.stopWindow`): each discard by a living agent that is not a typed `dangerPreempted`, with that agent's last sixteen records as one-line steps, and each call still with the mind when the stop began, with the body's ending for it.
Requests in it are named by labels local to the diagnostic, and harness times are milliseconds from the first stop record.
Each build step before startup runs as its own process group under its own bound, and a step past its bound, a failed step, or a cancellation stops the whole group, so no test runner a build started outlives it (the v35 shelter rehearsal's body check, cut at its bound on a loaded host, left its runner loading the host).
A shared startup deadline covers the service, server, and all bodies. Any
partial startup enters the same cleanup path as cancellation, timeout, disk
failure, cognition failure, or Paper failure.

Paper and cognition are separate process groups. Before cleanup signals either, the proxy
admits nothing more and ends a call the stop cuts off as `runStopping` rather than
`upstreamUnavailable` (r28: alpha's damage call was cut off this way and read as a provider
failure). Cleanup sends Paper's ordinary
`stop` and terminates cognition immediately, waits for cooperative exit, then
terminates and finally kills only a process group that still survives inside
the one shared shutdown ceiling. Every group signal is recorded without a
process id. A successful primary behavior artifact is invalid if any managed
group received `SIGKILL`. Cleanup still closes the in-process proxy and probes
the Minecraft, cognition, and every body-control port if an earlier cleanup
step failed. Publication is possible only after every managed group and
listener is gone. `SIGINT` and `SIGTERM` enter this same path.

The proof resolves the one configured crash target from its body-control
listener, verifies that the process command is Loom's body executable, and
sends `SIGKILL`. No process id reaches evidence. Peer bodies remain under their
own supervisors, connections, ref stores, cognition budgets, and journals.

## Evidence and publication

The mutable server directory, worlds, raw process logs, raw provider failures,
credentials, binaries, Paper jar, and synchronous death ledger are destroyed or
kept only in ignored local caches. They are never research artifacts.

If a run fails after its disposable directory has been created, cleanup still removes that directory and all raw records.
Before removal, the harness writes one mode-0600 operator diagnostic under `.scratch/tribe-failures/<experiment-id>.json`.
That file is capped at 64 KiB and contains only allow-listed event counts, per-agent cycle counts, call-budget state, aggregate provider token/cost totals, crash-reserve state (crash-containment mode only), input completeness flags, a stable failure code, aggregate cognition-failure classifications, and up to 32 typed budget refusals.
Each classified tuple contains only the routed model, numeric HTTP status, allow-listed provider type/code, structural response shape, Loom mapping, and count.
A missing or route-inconsistent classification stays explicitly unclassified; the harness never guesses it from a zero-byte usage journal.
Each disputed world interaction, up to 32, keeps only its agent, tick, operation, the body's own outcome token, and whether vanilla recorded a matching effect at all; the r3 shakedown failed on one dispute that the earlier aggregate counts could not attribute.
Each typed refusal names the agent, the bound (`perAgentCall`, `globalCall`, `globalOutstanding`, `postCrashReserve`, or `spend`), its unit, the configured ceiling, the observed count, that the call was not forwarded, and the trigger, importance, and native origin.
The diagnostic never copies prompts, reasoning, credentials, provider/request/thread ids, process ids, local paths, raw provider messages, or raw evidence, and it is explicitly not a publishable research artifact.

If any stage of that diagnostic fails - reading inputs, summarizing, serializing, the secret scan, the byte bound, or the write - the harness writes a minimal fallback to `.scratch/tribe-failures/<experiment-id>.fallback.json` instead.
The fallback is capped at 8 KiB, names the stage that failed, and keeps only closed facts: the failure code, the cleanup probe result, proxy call and refusal counts, and provider calls, tokens, and spend aggregated by an independent reader of the usage journal.
A string field is kept only if it matches its closed shape and passes the secret scan; otherwise it is null, and an unusable experiment id never becomes a file name.
Both files are written to a temporary name, synced, and linked into place, so a partial file never appears under a diagnostic name and an existing diagnostic is never overwritten.

The committed directory contains:

```text
README.md
manifest.json
evidence.jsonl
metrics.json
```

`evidence.jsonl` preserves source order within the body log and within the
harness journal. Every body record carries a fleet-safe `requestKey` made from
agent id and local request id. New body records include the complete bounded
perception and typed decision, including bounded reasoning and routed
provenance. Action, movement, vitals, inventory, interaction, witness,
disconnect, death, and lifecycle evidence retain their existing typed or
countable fields.

Each primary behavior perception also carries a research-only
`perceptionAudit`. For entities, blocks, and dropped items separately it records
the raw body-list count, the body's reported omission count, the JVM-selected
count, and the JVM cut count. The same audit records the body's exact raw world
clock. These fields never enter the perception/decision contract or model
prompt. Each body list is capped at 48 and the model-visible scene is capped at
144, so a current-generation 48/48/48 answer passes through without a JVM cut.
Normalization and publication reject a missing category, negative count,
impossible omission, a raw or selected per-kind count above 48, JVM cut other
than raw minus selected, any current-generation JVM cut, or a selected total
above 144. The production selector still applies deterministic category
reservation and global ordering to malformed or future inputs outside that
envelope.

Normalization recursively removes credential-shaped text, local absolute
paths, provider request/thread ids, and process ids; it also truncates public
strings. A cognition failure is published only as its stable typed outcome, not
its raw provider or transport text. The validator scans every committed file
again and refuses any remaining match.

For a paid run, cognition writes a private append-only usage sidecar in the
disposable directory. Each successful provider response contributes only agent
id, request id, routed model, prompt/completion counts, and cached prompt
tokens. The harness validates those counts, prices them with the manifest's
pinned integer nano-USD rates, and imports the result as normalized
`providerUsage` evidence. The manifest reports both the worst-case spend ceiling
and actual provider-reported spend; no provider request id or thread id is
retained.

`metrics.json` is generated from normalized records and is never hand-edited.
Validation re-runs the derivation byte-for-value and checks its hash, counts,
timing, call ceilings, deterministic identities, artifact bounds, and README
sections. The README generator separates setup facts, observations,
interpretations, counterevidence/null results, and limitations. Absence of
speech, co-location, attack, or server-confirmed interaction remains explicit
counterevidence rather than being turned into a positive result. Co-location
without recorded movement is identified as compatible with shared spawn
placement. Every `witnessDisputed` record is counted into counterevidence and a
failed `serverEvidenceUncontested` integrity check instead of being buried among
lifecycle counts.

Absolute permadeath is unchanged. The witness is installed before bodies start,
the synchronous ledger remains the login authority, and the ledger is excluded
from publication because it is mutable authorisation state rather than research
evidence.

## Live-model approval boundary

Do not change `cognition.backend` to `openai` and run it merely because a key is
available. Before the first paid run, publish a `needs-decision` report naming
the exact models, agent count, simulated and wall duration, maximum calls,
expected maximum cost, credential source, artifact plan, and stop/rollback
conditions. The credential must reach cognition only as `OPENAI_API_KEY` and
must not be copied into a configuration, command line, log, or artifact.

On macOS, `--openai-keychain-service <service>` makes the harness invoke
`security find-generic-password -s <service> -w` exactly once, after builds,
preflight checks, and listener probes have completed and immediately before
cognition starts. Standard output is held only in memory, trimmed, and placed
only in the cognition child's environment. Lookup diagnostics are discarded.
An empty value or lookup failure stops before cognition starts. An HTTP 401 or
403 from the provider stops the whole run after that refusal, with no retry and
only a stable provider-safe error.

The routine-v28 pilot routes routine and notable decisions to
`gpt-5.4-nano` and only the existing pivotal tier to `gpt-5.4`. It runs three
agents for at most 180 wall-clock seconds or 4,000 server ticks, whichever
comes first, and permits at most six calls per agent, eighteen calls globally,
and three calls outstanding. Its 65,536-byte request and 2,048-output-token
bounds make the estimate-independent worst case $3.50208 at the pinned rates;
the configured spend ceiling is $3.51.

The paid two-cycle profiles use the same routing and bounds. Three agents at
64/192/3 calls make the worst case $37.35552 against a $37.36 ceiling; ten agents
at 64/640/6 make it $124.5184 against $124.52. The ten-minute shakedown at 24/150/6 makes it $29.184 against $29.19.
Each needs its own exact approval.

After explicit approval, replace the proposal placeholder with the approved
fresh experiment id and run the bounded live configuration once. The earlier
`-r2` and `-r3` containment shakedowns were each consumed by their one
authorized attempt and must never be retried. Changing an experiment id is not
itself authorization.
