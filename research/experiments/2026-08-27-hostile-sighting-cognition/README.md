# Does a hostile in view reach an agent's mind before its next goal review?

## Question

Loom's cognition is event-driven, and until this run the only event that reached a mind on its own was the scheduled goal review.
A goal review is due every `DEFAULT_GOAL_REVIEW_CADENCE`, which is 2400 ticks, or two minutes.
A zombie crosses eight blocks in far less than that, so an agent whose only prompt to think is the schedule can be killed with every perception it ever took still saying `goalReview`.
This run asks five things about the vigilance path that answers that, four about whether it works and one about what it costs.

Does a hostile coming into view raise a `threatSighted` trigger within one scan cadence rather than at the next review.
Is the sighting priced Pivotal while the threat is close and Notable once it is not.
Does the resulting `flee` name one creature by the request-scoped handle the body issued, and does the body resolve it.
Does an accepted flight then carry on from the body's own tick, without a further model call, while the threat is still there.
And does a repeat sighting of the same standing threat get suppressed with a record that says which bound refused it, rather than either firing a frontier call twenty times a second or going quiet.

The scene is deliberately the simplest one that can answer all five: one agent, one hostile, flat ground, nothing else in the world moving, and nobody watching.

## Protocol

The plugin was built from a clean worktree at `f1d03a3` on `fm/loom-threat-trigger` and its jar hash recorded.
`f1d03a3` is the product-code tree this run exercised.
The branch moved on before the run by exactly one commit, `d074099`, which changes nine files: three Markdown documents (`AGENTS.md`, `DEMO.md`, `README.md`), KDoc prose in three Kotlin sources, one JSON Schema `description` string with one indentation fix, and two test files.
No executable code differs and the prompt version strings are identical, so the plugin and the mind under test are `f1d03a3`'s.
`f1d03a3` is not an ancestor of the default branch, because the work landed there as the squash commit `70d0859`.
Check ancestry against pull request 21, whose head ref is `d074099` and outlives the `fm/loom-threat-trigger` branch that a squash merge normally prunes.
The cognition service was built with `cargo build --release` from the same tree immediately before the run, and checked to carry the prompt versions the tree declares (`routine-v7`, `notable-v7`, `pivotal-v7`) and none of the earlier ones.
Both artefact hashes are attested in `manifest.json`.
Paper 1.21.1 build 133 was downloaded and its sha256 verified against the value the build API publishes before it was run.
Everything the run needed outside the repository, including the server jar, the world, the client and the logs, was kept in a temporary directory and none of it is committed.

The server data directory was created for this run with no world and no `plugins/Loom` directory, so `body-events.jsonl` holds this run and nothing else.
The server was bound to loopback only, with `online-mode=false` so a temporary offline-mode client could join, `difficulty=normal` and `spawn-monsters=true` so hostile behaviour was real, and a flat world.
Four settings pinned the scene from the console before any client joined.
`doMobSpawning false`, so the only hostile in the world was the one deliberately placed.
`doDaylightCycle false` at time 18000, so the zombie could not burn away in the middle of the run.
`randomTickSpeed 0`, so nothing else in the world changed while the agent was in it.

The mind was the shipped `ScriptedBackend` with no provider configured, reached over the ordinary TCP bridge.
That is a real cognition service answering real perceptions on the real wire, and it is not a model: `cognition/crates/cognition/src/model.rs` answers a `ThreatSighted` trigger with `Intent::Flee` naming the trigger's own `entityRef`, which is exactly the composition under test and none of the judgement.
No credential existed anywhere in the run.

One temporary offline-mode client joined and issued `/loombody spawn agent-final`.
It was needed for one reason and no other: `/loombody spawn` places the body where the sender stands, and so refuses the console.
It was then teleported 200 blocks away and disconnected, and a console `list` reported zero players online before anything else happened.
Only after that was the experiment's one setup action performed: one ordinary AI-enabled zombie summoned from the console 6 blocks from the body, inside `PIVOTAL_THREAT_DISTANCE_BLOCKS`.
Tick 879 is the first `threatSighted` tick and is what the window starts from, running to the last record.
The summon itself is not in the event log, so it is bracketed to ticks 860 to 879 rather than stated as a point, and `manifest.json` attests that bracket and derives it two ways.
The zombie was never teleported, tagged `NoAI`, or modified in any way after that summon.

From the summon to the terminating `stop` the operator sent no client, console, watch or operator input of any kind, and no command blocks, scheduled commands or automated stimuli were used at any point.
No `/loomwatch` watch was ever attached, so no observer view was rendered at any point.

## Timeline

Ticks are the event log's own; wall-clock times are attested from the server log, which is not committed.

| UTC | Tick | What happened |
| --- | --- | --- |
| 23:18:35 | 0 | server ready; Loom's autonomy, vigilance and body timers running |
| before 23:18:51 | before ~330 | scene pinned from the console: `doMobSpawning false`, `doDaylightCycle false`, time 18000, `randomTickSpeed 0` |
| 23:18:51 | ~330 | one temporary offline-mode client joined |
| 23:18:57 | 440 | `/loombody spawn agent-final`; body at 100.50,-60.00,100.50 |
| 23:19:00 | ~500 | client teleported 200 blocks away; first scheduled goal review at tick 499 carries 0 observations, answered Routine `idle` at tick 500 |
| 23:19:02 | ~540 | client disconnected; a console `list` at 23:19:15 reported 0 of a max of 2 players online |
| 23:19:18 | 860 - 879 | one ordinary AI zombie summoned 6 blocks off, bracketed to these ticks; `threatSighted` perception at tick 879, which is the window start, within one scan cadence of the summon |
| 23:19:18 | 880 | Pivotal `flee` naming the zombie's ref, accepted, 6-node route away from it |
| 23:19:19 | 899 | first suppression, `threatDebounced`, on the next scan |
| 23:19:23 | 979 | next sighting reaches the mind, exactly `THREAT_DEBOUNCE_TICKS` after the first, and is priced Notable |
| 23:19:18 - 23:22:11 | 879 - 4321 | unattended: 62 sightings answered, 26 suppressed, 60 flights started, 19 continued from the body tick, 58 ended safe, 2 refused. The 2 refused are `flee` decisions the ground turned down, at ticks 2660 and 4080, rather than endings of started flights, so they do not account for the 2 started flights that carry no `retreatSafe`: those are the flight started at tick 880, replaced by a fresh `retreatStarted` at tick 980, and the flight started at tick 4240, still running where the log ends at tick 4321 |
| 23:21:57 | - | operator wrote `stop`, 159 s after the summon, having sent nothing in between |
| 23:22:11 | 4321 | server acted on the `stop`; Loom put the body away on disable, agent alive at 20 of 20 health |

## Results

All five questions are answered yes by the evidence, and one intended part of the run did not happen.
Every number below is derived from `body-events.jsonl` by `manifest.json`; the manifest is the authority and this is the reading of it.

**Nothing was watching.**
The experiment window holds 63 perceptions and none of them contains a player observation: 62 carry exactly one observation, which is the zombie, and the goal review at tick 2899 carries none because the body had outrun it.
No client was connected for any of them, and the whole server log holds exactly one join and one leave, both before the summon.
The one perception taken while a client was still connected, the goal review at tick 499, carries zero observations as well, so no perception in this run contains a player at all.

**A sighting reaches the mind immediately.**
The zombie was summoned somewhere in ticks 860 to 879 and the `threatSighted` perception is at tick 879, so the sighting followed the summon within one scan cadence, at most 20 ticks.
It was answered at tick 880, one tick later.
The scheduled review that would otherwise have carried it is 2400 ticks apart, and the run contains 62 `threatSighted` perceptions against exactly 2 `goalReview` ones.

**The pricing follows the distance.**
The first sighting, at 6 blocks, was priced Pivotal.
Fifty-nine later ones were priced Notable, because the agent had put more than eight blocks between itself and the zombie, which is the pricing doing its job rather than a tier being missed.
Two more were priced Pivotal, at ticks 2800 and 4240, when the zombie closed back inside eight blocks.
Three decisions carry `pivotal-v7`, 59 carry `notable-v7`, 2 carry `routine-v7`, and each stamp names the model the router chose rather than one a backend claimed.

**A flee names one creature and the body resolves it.**
Sixty-two of the 64 decisions were `flee`, each naming a request-scoped handle, and not one was refused as `retreatThreatForgotten` or `retreatThreatUnnamed`.
The first chain is the whole shape of it: request `1ae0d5fb`, a `threatSighted` perception at tick 879, a decision at tick 880 reading `flee from e2`, and an application in the same tick reading `flee from e2 -> accepted`, routed 6 nodes away from the threat.
The 62 flees named 62 distinct handles, because a ref window is per request, and the two refusals that a handle from the wrong request or one no request issued would produce do not occur once.

**An accepted flight carries on for free.**
Nineteen `retreatContinued` records were written and none of them shares a tick with a `decided` record.
Continuations are driven from the actuation branch of the body tick, so a flight kept going between decisions without costing a model call: 1646 `moved` records against 64 decisions.
Fifty-eight flights ended as `retreatSafe`, out of range of the threat.

**Suppression is evidence, not silence.**
Eighty-eight sightings occurred and 26 of them were refused a model call, each writing a `threatSuppressed` record naming the bound that refused it: 21 `threatDebounced` and 5 `threatUnchanged`.
The debounce is visible in the tick arithmetic and not only in the tag.
The first suppression is at tick 899, on the scan after the sighting, and the next sighting to reach the mind is at tick 979, exactly `THREAT_DEBOUNCE_TICKS` (100) after the first.

**Two flights were refused by the ground, and said so.**
Both `retreatFailed` records, at ticks 2660 and 4080, carry the same reason: nothing worth retreating to in 4 looks, none of them 3 blocks off and 2 blocks further from the threat.
That is `NoMeaningfulRetreat`, the honest refusal, and it was recorded as a failure rather than passed off as a flight.

**The integrity checks all pass.**
No `worldHoldFailed` record, so no removal in this run could have been a chunk unload and no search or perception read an unloaded chunk as empty ground or a quiet scene.
No `cognitionUnavailable` record, so every request was answered and no refusal above is a mind that simply failed to reply.
Exactly one `intentApplied` per `decided`, all 64 joined on `requestId`, and every decision answers a perception the log contains.
The server log carried no "body event log" warning, so no record was dropped and these counts are the whole run.

**The agent was never touched.**
Health is 20 of 20 at the first record, at the last, and at its minimum across the run, and no `healthChanged` record exists, so the zombie landed no blow at all.
The reserve fell 10.78 of 100 to ordinary metabolism.

## Limitations

**The mind was the scripted stand-in, so nothing here is about judgement.**
`ScriptedBackend` answers a `ThreatSighted` trigger with a flee unconditionally.
What is proven is the composition, meaning trigger, routing, ref resolution, actuation, suppression and continuation, and not that a model would choose to run.

**The eight-call nightly ceiling was not reached.**
This run spent 3 of the 8 `MAX_PIVOTAL_THREAT_CALLS_PER_NIGHT`, so no `threatOverNightlyCap` record exists and this run does not prove cap exhaustion in-world.
The exact ceiling and its per-agent independence are proved by the deterministic test suite instead.
It was not reachable in this scene: the agent outran the zombie past eight blocks and every sighting from beyond it was correctly priced Notable, which is the cheap tier and does not draw on the pivotal budget.
Reaching it needs a threat that stays inside eight blocks across at least eight debounce windows, which means a faster hostile or a cornered agent.

**Six of the ten retreat tags were never exercised.**
`retreatPendingGround`, `retreatPendingBudget`, `retreatThreatGone`, `retreatThreatForgotten`, `retreatThreatUnnamed` and `retreatSuperseded` do not occur, so nothing here proves a flight held for the ground or for a search token, a threat that died or despawned, a ref from the wrong request, or a flight superseded by another intent.

**One agent, so no contention was tested.**
Nothing here says what happens when several agents decide to walk in one tick and the path-search bucket refuses the fifth, and nothing tests that the nightly budget is per agent rather than shared, which is the whole reason it is per agent.

**No blow landed and the run was short.**
Nothing here measures survival under attack, and 3881 ticks against a 36000-tick reserve says nothing about starvation.

**Two readings are inferences rather than fields.**
The single observation in each window perception is the zombie by elimination: a perception record carries a count and not a list, and the count is one while no player is connected and nothing else in the world moves.
Final separation is likewise a bracket rather than a number, because no record states the threat's distance: the last sighting, at tick 4239, was priced Pivotal at tick 4240, so the zombie was in view and inside eight blocks 82 ticks before the end.

**Four facts are attested at second resolution.**
The wall-clock column, the client's departure tick, the unattended duration and the summon tick bracket are read from the server log's timestamps, which are not committed, so each carries about 20 ticks of uncertainty.
The summon is not in the event log at all, which is why it is the bracket 860 to 879 rather than a point, and why no claim here is that the sighting happened in the tick the zombie appeared.
None is load-bearing: the event log bounds the summon to the same window on its own ticks, because the vigilance sweep runs once per `THREAT_SCAN_CADENCE` and the sweep at tick 859 wrote nothing, so the immediacy result and the absence of a watcher both hold without the wall clock.

## Conclusion

A hostile in view now reaches an agent's mind within one scan cadence of appearing rather than up to two minutes later, and the flight that follows keeps running from the body's own tick without spending another decision.
The bounds that stop that being twenty frontier calls a second are visible in the record rather than inferred: 26 of 88 sightings were refused, each with the bound that refused it named.
The agent survived 172 unattended seconds with a zombie hunting it and was never hit, which is the first time that has been true of a Loom body.
The two things this run does not establish are the nightly ceiling and anything about a model's judgement, and both are stated above rather than left to a reader to notice.

## Next experiment

Corner the agent.
The pivotal budget is only reachable when a threat stays inside eight blocks across eight debounce windows, so the scene needs walls: a pen the agent cannot put distance in, which should turn Notable sightings back into Pivotal ones, exhaust the eight calls, and produce the `threatOverNightlyCap` record this run could not.
That same scene should also produce `retreatFailed` in quantity and, if the zombie reaches the body, the first `healthChanged` records and the first predation death, which is the number a survival run actually reads.
Run it with `LOOM_COGNITION_BACKEND=openai` so the decision to run is the model's, and with two agents in the same pen so the per-agent nightly budget is tested against the thing it was chosen over.
