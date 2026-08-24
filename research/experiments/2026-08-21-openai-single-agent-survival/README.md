# One agent, a real model, and one energy cycle

Run date: 2026-08-21.
Source commit: `05163ebff5a2009085c1fba209ef3864714a854a`.
Evidence: `body-events.jsonl` (162 records, immutable), `manifest.json` (every number below is derived from it, a setting the run was configured with, or marked `attested`).

## Question

**Does an agent driven by a real language model notice its own metabolic reserve running down, and rest before it dies of it?**

The shipped `ScriptedBackend` rests at a hard-coded threshold, so it can only demonstrate that the mechanism works.
The question here is about judgement rather than mechanism: given the reserve as an ordinary field on an ordinary perception, with no threshold stated anywhere in the prompt, does the model act on it in time.

A second question comes free with the first, because the run has to be clean to answer anything: does a 35-minute unattended run hold together at all.

## Protocol

One agent, `ada`, alone in a flat overworld arena for 2100 seconds, with a real OpenAI backend and nobody watching it after the first 7.3 seconds.

| | |
| --- | --- |
| Body | Paper 1.21.1 (protocol 767, build 133), flat `arena`, `difficulty peaceful`, no monsters, no animals, `allow-nether=false`, offline mode |
| Mind | `loom-cognition`, `LOOM_COGNITION_BACKEND=openai`, fast tier `gpt-5-mini`, deep tier `gpt-5`, 12 000 ms provider timeout |
| Drive | `AutonomousDriver` on the default 120-second goal-review cadence, swept every 5 seconds |
| Observers | none inside the perception bound at any of the 18 perceptions, each of which recorded `0 observation(s)`. One bootstrap client was connected for the run's first 7.3 seconds - inside the bound at the spawn, then 40 blocks out - and then disconnected |
| Portals | none, deliberately; dimension-transit health continuity is a separate open question |

The world is deliberately inert.
Nothing in a flat peaceful arena can hurt the body, so if the agent dies it dies of its own reserve, and the run answers the question it was asked rather than a mixture of that question and a fight.

The standing instructions were prompt version `routine-v1`, which is the shared `AGENT_BRIEF` plus `tier_brief(Routine)`, and they carry a rule about the reserve but no threshold.
They describe the reserve as a fraction that drains as the agent lives, name reaching empty as a permanent death, name resting as the only way to refill it, and instruct the agent "do not let it run low with no plan to rest".
No threshold, and no figure bearing on when to rest, appears anywhere in them: the only figures they state are the scale anchors, 1.0 for a full reserve and 0.0 for an empty one.
The tier half is the closing line, and it names `idle` as a usual answer at this tier: "This is a routine moment. Keep it simple and cheap; a small sensible action, or idle, is usually right."

### Preconditions

Four checks had to pass for the run to count, and all four are stated in `manifest.json` under `integrityChecks` with the reason each one matters.

1. **Zero observer contamination.**
   A player inside the 12-block perception radius is an observation, and an observation is an input.
   The bootstrap client exists only because `/loombody spawn` places the body where the sender stands and refuses the console, so it spawns the agent and teleports 40 blocks away in the same breath, with no wait in between.
   A never-reviewed agent is due at the next sweep and the driver sweeps every 5 seconds, so the window between the spawn and the first review runs from one tick to a full 5-second slice depending on sweep phase - 43 ticks in this run - which is not reliably long enough for a walk-away to clear the bound first.
   That client stayed connected for the run's first 7.3 seconds - long enough to confirm the body rendered from a client's point of view - and then disconnected, which is attested from the harness rather than derivable from the event log.
   The first perception is at tick 99, about 5 seconds in, so a client was connected during it and was 40 blocks from the body, outside the 12-block bound, rather than absent.
   Perception is 18 discrete captures rather than continuous coverage, so most of the run is unsampled - 17 gaps of about 2400 to 2500 ticks between captures, a 379-tick tail after the last one, and the 43 ticks between the spawn at tick 56 and the first capture at 99 - and the spawn interval is called out here only because it is the one unsampled interval in which the client is known to have been inside the 12-block bound: placing the body where the sender stands puts it there at tick 56 and until the teleport, which follows from the attested note and that placement rule rather than from the event log.
   What the evidence establishes is the bound at the moments it was sampled, not the absence: each of the 18 perceptions recorded `0 observation(s)`, and the earliest is tick 99, after the teleport, so no decision in this run was taken with anything inside the bound.
   Nor is that a vacuous check: `observationsAround` reports any `LivingEntity` within the bound and a player is one, so a client still inside 12 blocks would have appeared as an observation.
2. **Zero `worldHoldFailed`.**
   Loom holds chunks open around every living agent so that a chunk unload is never among the removals the port has to explain.
   A hold failure means a removal classified as a death may have been an unload, which would make any survival number meaningless.
3. **Zero dropped-record warnings.**
   The event log's write queue is bounded so a stalled disk cannot block the tick; a warning would mean the record is incomplete.
   This is the one precondition that is attested rather than derived, because the warning is raised in the server log, which is not committed, rather than in the event log it is about.
4. **Zero cognition failures.**
   A perception with no decision back is a cycle in which the agent was not actually being driven.

### Repeating it

The commit, the world settings, the backend, the cadence, and both artifact hashes are in `manifest.json`.
The one thing not reconstructable from this directory is the harness that started the four processes, which is deliberately not committed: it is scratch, and the protocol above is the part that matters.

## Timeline

| Tick | Minute | What happened |
| --- | --- | --- |
| 56 | 0.0 | World hold taken; `ada` spawned at `arena 6.50,-60.00,-3.50` with a full reserve and 20/20 health |
| 99 | 0.1 | First `perceived`: `goalReview`, `0 observation(s)` |
| 205 | 0.2 | First `decided`: `idle`, `gpt-5-mini` / `routine-v1` / `ROUTINE` |
| 205 - 22 307 | 0.2 - 18.6 | Ten consecutive `idle` decisions, each applied `accepted` |
| 24 599 | 20.5 | `perceived` at the run's lowest reserve, 31.83 percent |
| 24 716 | 20.6 | `decided`: **`rest`**, applied `accepted: resting`; the reserve refills to full |
| 27 227 - 42 014 | 22.7 - 35.0 | Seven further `idle` decisions on the refilled reserve |
| 42 278 | 35.2 | `despawned` at shutdown, reserve at 51.21 percent, health still 20/20 |

## Results

### The agent rested, on its own reasoning, with 32 percent of its reserve left

The reserve fell from full to **31.83 percent over 20.5 minutes**, at which point `gpt-5-mini` answered the goal review with `rest`.
The port accepted it and refilled the reserve completely, 117 ticks later.
Exactly one refill occurred in the run, at exactly the tick the intent was applied.

Nothing told the model a threshold.
It quoted its own reserve back on **all eleven reviews up to and including the rest** - "full", 0.9, 0.9, 0.8, 0.7, 0.7, 0.6, "only half", 0.5, 0.4, and then 0.3.
Nothing asked it for that figure, though the brief does tell it the reserve should shape every choice.
Of the ten reasonings before the rest, seven justify idling as conserving or not wasting energy, at ticks 2686, 10 124, 12 475, 14 894, 17 303, 19 784 and 22 307.
Two more, at ticks 205 and 7583, justify it as the safest and cheapest option without naming energy conservation at all.
The remaining one, at tick 5173, justifies it by avoiding unnecessary risk and waiting for a clearer situation, with no cost or conservation language of any kind, while still stating the level as 0.9.
On the eleventh it switched, and said why:

> rest: Energy is low at 0.3 and resting now safely refills my reserve; I see nothing threatening nearby so it's prudent to recover before doing more.

The scripted stand-in rests at a hard-coded 0.35.
The real model had no such constant available to it, and rested when its true reserve was 31.825 percent.
What it was shown was coarser than that figure: the prompt renders the reserve to one decimal, so the perception it rested on stated `energy reserve 0.3`.
The run therefore cannot resolve the model's threshold any more finely than the one decimal it was given.
Reviews are 120 to 125 seconds apart and each interval costs between 6.667 and 6.944 points of reserve, so the evidence brackets the switch only between idling at 38.49 percent and resting at 31.825 percent, and says nothing about where inside that interval the model's own line sits.
Comparing it with the scripted 0.35 is a comparison at one decimal for the same reason, since 0.35 renders as `0.3` too.
What is unaffected is that the model was given the reserve and no threshold for it: the two thresholds share no code path, so landing in the same region is a result about the model's judgement rather than about a constant it could see.

All 18 reasonings weigh the reserve in words, and a stated level for it - a number, or "full", or "half" - appears in 15 of them; the three that state no level are all after the rest, on a reserve between 0.94 and 0.66.
That is consistent with the reserve being quoted more readily as it falls, but with one run it is an observation rather than a finding.

### The reserve is tick-counted, and the numbers prove it

The implied full-reserve lifespan, computed from the full-precision readings alone, is **exactly 36 000 ticks on both sides of the rest**, against a configured `DEFAULT_ENERGY_LIFESPAN_TICKS` of 36 000.
In reserve ticks the agent went from 36 000 down to 11 457 across 24 543 server ticks before the rest, and from 35 999 down to 18 437 across 17 562 server ticks after it.
That is exactly one reserve tick spent per server tick, on both sides of a refill, with no residue to explain.
Exactness is what a reserve counted in ticks should do, and is what a wall-clock reserve or a fraction decremented every tick would not do.

The reporting gap varies while the slope does not.
The 104 `energyChanged` records give 103 gaps, one of which spans the rest refill, leaving 102 declining steps.
80 of those 102 are exactly 360 ticks for exactly 1.0 percent and six are 361 ticks for 1.003 percent, and the 16 wider steps are one of 483 ticks for 1.342 percent, five of about 600 ticks for 1.667 percent, and ten of about 700 ticks for 1.944 percent, which is the same rate.
The wide gaps are the vitals watch declining to restate a reading that a `perceived` record already carried.
There are 16 of them and each contains exactly one perception; the two perceptions with no wide gap around them are the first, at tick 99 before the first `energyChanged` at tick 460, and the rest perception at tick 24 599, where the refill step falls instead.

### Every decision came from the provider, and one nearly did not

All 18 decisions are stamped `gpt-5-mini` / `routine-v1` / `ROUTINE`.
There were zero `cognitionUnavailable` records, and the perception-to-decision round trip ran 62 ticks at best and 88 at the median.

The worst case was **228 ticks, 11.40 seconds, against a 12 000 ms provider timeout**.
That measurement spans the `perceived` record to the `decided` record, so it includes the bridge's queue time, both TCP legs, and main-thread scheduling at tick granularity, while the timeout bounds only the provider request.
The provider's own elapsed time was therefore strictly less than 11.40 seconds, and the 0.6 seconds left over is a lower bound on the real headroom rather than a measurement of it.
It is still the most actionable number in the run, because one slower answer would have cost that cycle its decision - nothing in the mind retries past the write - and a zero-failure result should not be read as a comfortable margin.

### Cadence held

Perceptions were spaced 120.0 to 125.0 seconds apart, mean 122.9, across 17 intervals.
That is the documented "cadence plus up to one 5-second slice" with no drift over 35 minutes.

### The two vitals stayed distinct

Health was 20/20 on all 106 readings, with zero `healthChanged` records, while the reserve moved 104 times.
The record therefore carried the two vitals as separate fields and did not alias one to the other during this run.

## Limitations

Stated as plainly as the results, because the run is narrow.

- **Death was not exercised.**
  The agent rested at 20.6 minutes, which pushed the earliest possible starvation past the end of the window.
  There is no `died` record, so permadeath, `Mortality`'s refusal to re-embody, and the rule that no vitals record may follow a death are all untested here.
- **Health was constant by construction.**
  A flat peaceful arena with no monsters and no fall damage has nothing that can hurt a body.
  The flat line confirms the record keeps the two vitals apart; it says nothing about damage, and the `healthChanged` path is untested.
- **No observer display was ever rendered.**
  No client was connected after the run's first 7.3 seconds and `/loomwatch` was never used, which is attested from the harness rather than derivable from the event log.
  The observation path only reads: it decorates the log and appends nothing of its own, so a watch leaves no trace there and the event set would be the same either way.
  Nothing here speaks to what a watcher would have been shown, including whether the display keeps the two vitals apart the way the record does: with no watcher attached, `ObserverView` and `WatchState` never produced a line and no boss bar or note ever reached an operator.
- **Movement was never chosen.**
  Every intent was `idle` or `rest`, so `WalkGait` and the walking half of the body were not exercised against a real model.
- **Perception was empty.**
  All 18 perceptions carried zero observations, so the model reasoned well about itself with nothing external to reason about.
  Its judgement under actual input is untested, and an empty world is only one of several candidate explanations for 17 of the 18 answers being `idle`.
  The others are in the prompt: `routine-v1` closes by telling the agent that at this tier "a small sensible action, or idle, is usually right", and the brief it opens with cautions that "Nothing is worth risking your life for casually", so the 17 `idle` decisions are consistent with either standing instruction as well as with an inert world, and this run cannot separate them.
  What none of them weakens is the rest: `idle` is the answer the tier brief names, and the eleventh review answered `rest`.
- **Only one tier was reached.**
  A goal review is `ROUTINE` by construction, so `gpt-5` was configured and never called.
  Two of the three prompt versions, `notable-v1` and `pivotal-v1`, and the deep model class the router keeps for `PIVOTAL` are untested end to end.
- **One run, one agent, one model.**
  There is no repetition, no variance estimate, and no second model to compare against.
  The model rested at a true reserve of 31.825 percent, shown to it as `0.3`, and that is one observation rather than a distribution.
- **The brief already told the agent to rest.**
  Prompt version `routine-v1` instructs the agent not to let the reserve run low with no plan to rest, so what this run isolates is the choice of moment rather than the decision to rest at all.
  A model that never rested would have been ignoring a standing instruction; this one chose when to act on it, with no number anywhere in the prompt to tell it when.
- **The resolution of the threshold is one decimal, not three.**
  The prompt renders the reserve to one decimal before the model ever sees it, so no run of this shape can locate a model's rest threshold more finely than that.
  This one brackets the switch between idling at 38.49 percent and resting at 31.825 percent, which is the spacing of two reviews rather than the width of a decision boundary.
- **Dimension transit was excluded** on purpose.
- **The reserve and the death record are in memory.**
  Neither survives a server restart, so nothing here speaks to a run spanning one.

## Conclusion

**The run is scientifically usable, for a narrow claim.**

All four integrity preconditions passed, 18 of 18 perceptions were answered by the real provider with zero failures, the record is internally consistent across 162 events, and the reserve's own arithmetic reproduces its configured constant twice over.
The claim it supports is that a real-model agent, given its reserve as an ordinary perception field and no stated threshold, tracks that reserve across reviews and rests before exhausting it.

It supports no claim about death, damage, movement, external perception, the deep tier, the observation path, or more than one agent.

Three warnings for anyone reading it:

- The 11.40-second worst-case round trip means the zero-failure result sits within 0.6 seconds of not being one, and that margin is measured perceive-to-decide rather than at the provider, so it bounds the real headroom from below.
- The rest happened at a true reserve of 31.825 percent, rendered to the model as `0.3`, and is a single observation at one decimal of resolution.
- Three `No key layers in MapLike[{}]` lines appear in every run of this harness.
  They are Paper's flat-generator codec finding no `generator-settings` during world creation, before the plugin is enabled, and falling back to the default preset.
  They are the only error lines in the run, and they appear identically under a different cognition backend.
  Those two claims and the count are attested from the server log rather than derivable from the event log: the server log is not committed, and these lines were raised during world creation, before the Loom plugin was enabled, so the event log says nothing about them.
  They do not affect these results, and they are recorded in `manifest.json` under `knownWarnings` so that a future reader does not have to rediscover them.

## Next experiment

**One agent has to survive a night outdoors in a world that is trying to kill it.**

Keep the single agent and keep the unattended protocol, and change five things:

1. **Rest stops being free.**
   `Intent.Rest` currently refills the entire reserve at no cost, which makes staying alive a matter of remembering to ask.
   Remove the free full refill.
2. **Food becomes the only restoration.**
   The reserve recovers only by acquiring and consuming food, so an agent that wants to live has to go and get something.
3. **Normal hostile stakes.**
   A real difficulty with monster spawning, so the world contains things that will attack the body.
4. **Shelter before dark.**
   The agent must be somewhere hostile mobs cannot reach it by nightfall.
5. **Success is surviving through dawn without operator help.**
   No `died` record between the first dusk tick and the following dawn tick, the reserve above zero at dawn, zero `worldHoldFailed`, and no operator input after the bootstrap client disconnects.
   Anything else is a failure, including a survival that needed a person.

This is the first experiment where the agent can lose, which is the point.
It is also the first where an `idle` answer is a decision with a consequence rather than the cheapest thing to say.

### What Minecraft already provides

None of the following needs any Loom work.

- **A day and a night.**
  The 24 000-tick day and its dark phase are the server's, and the dark phase is roughly seven minutes at twenty ticks a second.
  Nightfall needs no scheduling by Loom; it arrives.
- **Hostility aimed at exactly this body.**
  Loom's body extends Villager, and zombies specifically hunt villagers.
  Turning `difficulty` off peaceful and letting monsters spawn produces a threat that seeks the agent out, with no aggression logic written on either side.
- **Damage and death against the body.**
  `LoomBodyEntity` is a real entity with real health, and a death already flows through `BodyDeparture` to `Mortality` and into the record as a `died` event.
  The scoring mechanism for "did it survive" exists today.
- **Shelter as a real mechanic, not a scoring convention.**
  Hostile spawning is gated on light level and mobs are blocked by blocks, so an enclosed lit space is genuinely safe and an open field genuinely is not.
  Nothing has to simulate danger.
- **Food as world objects.**
  Crops, animals, and dropped items exist, and villagers have inventory slots.
- **The engine seams for watching all of it.**
  `EntityDamageEvent` and `EntityTransformEvent` are ordinary Bukkit events, and the second one already matters: a zombie attacking a villager can convert it rather than kill it, which Loom cancels and records as `converted` / `conversionRefused`.
  A hostile run will exercise that path, and a death count read naively would miss it.

### What Loom is still missing

These are gaps in the body, the perception, and the action seams, in the order they block the experiment.
They are named here so the run can be designed honestly, not proposed for implementation in this change.

1. **Food is not something an agent can act on at all.**
   `Intent` is `say`, `moveTo`, `flee`, `rest`, `idle`; there is no eat, no pick up, no use.
   `Metabolism` has exactly one restoration entry point and it refills the whole reserve, so there is no partial restore and no notion of an item conferring energy.
   Requirements 1 and 2 above are therefore a new intent, a body-side action to carry it out, and a second restoration shape on `Metabolism`, not a configuration change.
2. **The body cannot see food, or blocks, or anything that is not alive.**
   `observationsAround` collects `LivingEntity` within 12 blocks, up to 16 of them, and reports each as an entity type description plus a distance.
   A dropped carrot is an item entity, so it is invisible; a wheat crop, a door, and a wall are blocks, so they are invisible too.
   An agent asked to find food currently cannot perceive any.
3. **The body cannot tell that it is night, or that where it stands is dark.**
   The perception carries health, the reserve, a position, and nearby living things.
   There is no world time, no light level, and no sense of being enclosed, and `Trigger` has no nightfall variant.
   An agent cannot see dusk coming, and cannot tell a shelter from a field.
4. **The body cannot build.**
   With no place-block or break-block intent, shelter can only be walked to, never made.
   The first honest version of this experiment therefore pre-places a shelter and asks whether the agent goes to it in time, which narrows the question to navigation and timing - a smaller question, but a real one.
5. **`Trigger.Damaged` exists and nothing raises it.**
   The contract has the variant and `importanceOf` routes it `PIVOTAL`, which is how an attack would reach the deep tier.
   But the only producers of any trigger are `AutonomousDriver` and `/loombody`, and both send `GoalReview`: no damage listener exists.
   So under hostile stakes the mind first learns about being attacked at the next scheduled review.
   This run measured that spacing at 120 to 125 seconds against a night of about seven minutes, which is roughly three decisions per night, and a zombie kills a villager in far less than one interval.
   Without a damage trigger the agent is not reacting to the fight; it is being told about it afterwards, if it is alive.
6. **`Intent.Flee` is refused by the body.**
   The mind can already choose `flee`, and `LoomBodyEntity` answers `Unsupported`.
   The one intent that responds to a hostile encounter is a recorded refusal today, and `Intent.Say` is the same.
7. **`inventorySummary` is always empty.**
   The contract field exists and `BodyPerception` passes `emptyList()`, so an agent cannot know whether it is carrying the food it went to fetch.
8. **The agent is never told it is dying.**
   There is no death `Trigger`, so scoring has to come from the record.
   That is fine for this experiment and worth knowing.
9. **`Mortality` and `Metabolism` live in memory.**
   A run cannot span a server restart.

The smallest version of the experiment that the current seams can support is: pre-placed shelter, hostile night, no rest at all, and the question reduced to whether an agent that can only walk and idle reaches shelter before dark and is still alive at dawn.
The full version, with food as the restoration and a reaction to being hit, needs at minimum items 1, 2, 3, and 5 above.
