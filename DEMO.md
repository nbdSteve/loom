# Watching one Loom agent live

This is the operator procedure for a single-agent Loom experiment.
It goes from a cold checkout to a boss bar that shows one agent's energy, the trigger that made it think, the model that answered, the intent it chose, and what its body did about it.

Everything on the screen comes from the append-only record Loom already writes.
The display is a reader of that record and never a participant in the run: `/loomwatch` cannot spawn, move, perceive, spend a model call, or touch the world, and attaching it adds no perception, no decision, no tick work, and no record of its own.
That property is held by types rather than by care, and `ObservatoryTest` proves it.

## What you need

- Corretto 21, for the plugin build.
- A Rust toolchain, for the mind.
- A Paper 1.21.1 server.
- A Minecraft 1.21.1 client, or a scripted client such as a mineflayer bot.

A client is not optional.
`/loombody spawn` places the body where the sender stands, so it refuses the console, and a body that moves server-side only looks identical in the log to one a client can actually render.
Verify from the client's point of view, not from the plugin's log.

## 1. Start the mind

```sh
cd cognition
cargo run --bin loom-cognition
```

It prints what it will answer with before it answers anything:

```
loom-cognition backend=scripted fast=scripted-stand-in deep=scripted-stand-in (no provider: decisions are scripted)
loom-cognition listening on 127.0.0.1:8642
```

The scripted stand-in is the default, so this needs no credential.
It answers a goal review with `idle` while the agent's reserve is above `RESTING_ENERGY` (0.35 of a full reserve), and with `rest` once the reserve has fallen to or below it.
So a scripted agent perceives, decides, and applies on cadence while standing still, and it keeps itself alive: it idles while its reserve is high, rests to refill it when it runs low, and idles again.
That is enough to demonstrate the whole cognition round trip, the whole display, and one full survival cycle.
It is not enough to demonstrate motion, so see [Watching an agent actually move](#watching-an-agent-actually-move) below.

For real inference, set `LOOM_COGNITION_BACKEND=openai` and `OPENAI_API_KEY`.
`cognition/README.md` documents every setting the service reads.

## 2. Build the plugin

```sh
cd plugin
JAVA_HOME=/path/to/corretto-21 ./gradlew shadowJar
```

The jar lands at `plugin/build/libs/loom-plugin-0.1.0.jar`.
The first build downloads and decompiles a Mojang-mapped server, which takes a few hundred megabytes and several minutes; later builds are fast.

## 3. Prepare the server

Put the jar in the server's `plugins/`, accept the EULA, and set these in `server.properties`:

| Setting | Value | Why |
| --- | --- | --- |
| `level-type` | `minecraft:flat` | A flat world makes a walking body easy to see. |
| `gamemode` | `creative` | The observer needs to fly and to run commands. |
| `difficulty` | `peaceful` | Nothing else kills the agent, so a death in the run is one you caused. |
| `spawn-monsters` | `false` | The same reason. |
| `allow-nether` | `true` | Needed only for the dimension-travel case. |
| `online-mode` | `false` | Needed only for a scripted client. |

Op the account you will watch from.
`/loombody` is gated on `loom.body` and `/loomwatch` on `loom.observe`, both defaulting to op, so an account that is not opped can do neither.
Losing that gate is silent, because the command keeps working for the op who tests it, so `PluginDescriptorTest` pins the shipped descriptor.

Start the server, and join with your client.

## 4. Attach the watch, then spawn

Attach the watch first.
A fresh watch knows nothing at all, because the record is the only thing it may read, so the `spawned` record is what gives it the first energy reading.

```
/loomwatch ada
```

```
watching ada. Lines appear as the record does, so attach before spawning to catch the first energy reading.
```

The boss bar appears, honest about what it does not know yet:

```
ada | energy not read yet | nothing seen yet
```

The bar itself is empty rather than full.
Guessing full would show a dying agent as healthy for as long as the guess lasted.

Now give the agent a body.
It appears where you are standing, so stand where you want to watch from.

```
/loombody spawn ada
```

```
[loom] has a body now
ada | energy 100/100 | standing by            (green, 100%)
```

The gauge is the agent's energy reserve, shown as a percentage, and a fresh agent starts full.
The body's health is a separate vital and stays off the line while it is full; it joins only once the body has been hurt.

## 5. Watch one autonomous survival cycle

Wait.
Nobody has to ask the agent to think: `AutonomousDriver` sweeps on its own timer and reviews each agent's goals on a cadence, and an agent that has never been reviewed is due at once.
Within about five seconds of the spawn, three lines arrive in order:

```
[loom] perceived the world (goal review)
[loom] decided [scripted-stand-in/routine-v1 routine]: idle: reviewed goals at the Routine tier; nothing outranks what is already underway
[loom] the body is doing it
ada | energy 99.8/100 declining | idle | goal review -> scripted-stand-in/routine-v1 routine   (green)
```

That is the whole round trip: the bounded perception that caused cognition, the model and prompt version that answered it, the reasoning behind the intent, and what the body did about the intent.
`/loombody think ada` triggers one on demand if you would rather not wait.

The reserve has already left 100 by then, and `declining` is on the line from here to the end of the agent's life.
That word is the standing condition of a living agent rather than a warning, which is why the bar is still green: the colour reads how much is left, not which way it is going.

The next scheduled review lands one cadence later.
The cadence is two minutes and the sweep period is a twenty-fourth of it, so observed spacing is two minutes plus up to five seconds.
In one run the second review landed 125 seconds after the first, which is the design and not drift.

### The reserve falling, and resting to refill it

The gauge is the agent's energy reserve, and it falls by itself.
The reserve is a survival stat, not the body's health: it is spent by living, one tick at a time, whether or not anything happens to the body, and the only thing that refills it is a `rest` decision from the mind.
A full reserve lasts `DEFAULT_ENERGY_LIFESPAN_TICKS`, which is 36000 ticks, half an hour at twenty ticks a second.
Nothing in chat marks the fall, because it is continuous: the gauge and its percentage carry it, and a record every tick would drown the log, so the log gets one about every one percent instead.

Left to the scripted stand-in, an agent keeps itself alive without being told to.
The stand-in idles while the reserve is above `RESTING_ENERGY` (0.35 of full) and rests once it reaches it, so roughly twenty minutes after the spawn - when the reserve has fallen to about a third - the next goal review comes back `rest` rather than `idle`:

```
[loom] perceived the world (goal review)
[loom] decided [scripted-stand-in/routine-v1 routine]: rest: the energy reserve is down to 32% of capacity; resting outranks every other goal
[loom] the body is doing it
ada | energy 32/100 declining | resting | goal review -> scripted-stand-in/routine-v1 routine   (yellow)
```

Those are one run's real numbers: the agent spawned on tick 815 and the review that decided to rest read the reserve at 31.99 of 100 on tick 25299, which is 20 minutes later.
The bar had gone yellow when the reserve passed half, five minutes before that, and nothing in chat marked the crossing - the colour is the whole signal, which is why it is spent on how much is left.

Resting refills the reserve completely, and it is one tick, not a gradual climb: the same tick that applied the rest recorded the reserve back at full.
So the yellow clears and the gauge is green again by the next refresh, with the trend word reading the refill rather than the drain:

```
ada | energy 100/100 recovering | resting | goal review -> scripted-stand-in/routine-v1 routine   (green)
```

`recovering` is the one time the trend word is news, because a living reserve is otherwise always falling.
The label stays `resting` until the next review, since that is still the last thing the mind chose.
From there the cycle repeats: idle while the reserve is high, rest when it runs low.
Waiting it out takes patience; shorten the reserve in `Metabolism` if you would rather watch a whole cycle quickly.

### The other vital: a body that is hurt rather than spent

The body's health changes for a different reason - being hurt, not living - and it moves in one hit rather than on a ramp.
The lines below are from a short separate run on a fresh agent, so the reserve in them is still near full; that is what makes the two vitals easy to tell apart.

```
/damage @e[type=villager,limit=1] 8
```

```
dex | energy 99.3/100 declining | health 12/20 | idle | goal review -> scripted-stand-in/rout...   (green)
```

Health joins the line only once the body is below full, and it leaves the gauge to the reserve.
The reserve here has barely moved - eight hearts of damage cost the agent nothing at all in energy - which is the point of showing both: a body can be beaten to death on a full reserve, and a full reserve is no evidence that an agent is well.

The wound reached the screen within one refresh of the command, and it must: health has its own step function and its own `healthChanged` record for that reason, so a hurt body is never waiting on the reserve's next step to be reported.

Four more hearts is where the colour turns, on the body rather than on the reserve:

```
/damage @e[type=villager,limit=1] 4
```

```
dex | energy 98.9/100 declining | health 8/20 | idle | goal review -> scripted-stand-in/routi...   (yellow)
```

The threshold is the same for either vital - half is yellow, a quarter is red - because either one can end the agent.

Both of those lines are cut with an ellipsis, and that is the 96-character boss bar rather than a lost field: with health on the line the model stamp is what runs out of room.
The stamp is last on purpose, and the `decided` chat line always carries it in full.

### Permadeath

A `/kill` is the quickest death to show:

```
/kill @e[type=villager,limit=1]
```

```
[loom] DIED. Permadeath: this agent will not return.
dex | energy 98.5/100 | health 0/20 | DEAD - will not return | goal review -> scripted-stand-...   (red)
```

That is the two-vitals reading that matters most: a nearly-full reserve and no health left says plainly what killed this agent, and a starvation reads the other way round.
The trend word is gone, because the display is final.

A starvation is the other death, and the one the reserve exists to produce.
Point the plugin at a mind that never rests, or shorten the reserve, and leave the agent alone: about half an hour after the spawn the reserve reaches zero and the agent dies of it, recorded as a single `died` record whose detail is `spent its energy reserve`.

The display freezes on the tombstone either way, which is correct: Loom is permadeath, and the state is never left.
The tombstone deliberately reports no reserve trend, because a dying agent's last reading is always a falling one and a live trend would sit on the tombstone saying something was still happening.

The refusal is worth showing too, since it is the rule made visible:

```
/loombody spawn ada
```

```
spawn rejected for ada: this agent has died, and Loom agents do not return
[loom] was refused a body
```

Not every refusal is bad news, and one of them means the opposite.
`/loombody spawn ada` for an agent that already has a body is refused *because* the body is standing there, so it is reported calmly and the display goes on saying what the body is doing:

```
spawn rejected for ada: a body already exists for this agent
[loom] already has a body, so a second one was refused
ada | energy 99.8/100 declining | standing by | ...   (green)
```

Which refusal it was is a field on the record (`outcome`), not a phrase in its detail, because the display never reads a record's prose.

### Stopping

```
/loomwatch off
```

The boss bar is taken down and the watch ends.
`/loomwatch` with no argument reports who is being watched, or says nobody is.

A watch belongs to the operator who started it, which matters as soon as two accounts hold `loom.observe`.
`off` ends your own watch and nobody else's: typed by anybody but the watcher it is refused with "somebody else is watching. Only the operator who started a watch can stop it.", and the watcher's bar stays where it is.
The no-argument report answers the same way, and deliberately does not name the agent somebody else is watching.
Taking a watch over is still one command - `/loomwatch <agentId>` - and that path does tell the displaced operator.

## Reading the display

The standing line names the agent, its reserve, what it is doing, and which mind answered.
Any field Loom does not know yet is left out rather than guessed, and the body's health joins only once it is below full.

```
ada | energy 62/100 declining | health 12/20 | moving | goal review -> gpt-5-mini/routine-v1 routine
 |          |                      |            |         |
 |          |                      |            |         the model, prompt version, and routing tier
 |          |                      |            the trigger that caused cognition
 |          |                      what the agent is visibly doing
 |          |                      (the body's health, shown only while it is below full)
 |          the energy reserve as a percentage, and which way it is going
 the agent
```

The activity word is the one an operator reads at a glance, and every value it can take is a fixed label in `AgentActivity`:

| Word | Meaning |
| --- | --- |
| `nothing seen yet` | The watch is attached and the record has said nothing about this agent yet. |
| `standing by` | It has a body and nothing is underway. |
| `thinking` | A perception has gone to the mind and no decision has come back. |
| `resting` | The mind chose to rest, which is the one way an agent refills its reserve. |
| `idle` | The mind chose to do nothing this cycle. Distinct from resting, which refills. |
| `moving` | Walking somewhere it chose. |
| `fleeing` | Running from something. |
| `speaking` | Speaking. |
| `body refused it` | The mind decided and the body would not do it. |
| `mind did not answer` | Cognition failed or timed out. Not the agent's doing, and not a decision. |
| `crossing dimensions` | Continuity, not an ending. |
| `no body` | Alive as an agent, with no body in the world. |
| `DEAD - will not return` | Gone for good. |

`standing by`, `resting`, and `idle` are deliberately three different words.
A body that has arrived where it was going, one whose mind chose to rest and refill its reserve, and one whose mind chose to do nothing all look identical in world, and which one you are seeing is most of what a survival run is about.
`resting` and `body refused it` are separated for the same reason.

Three colours carry how much attention the line deserves, on the bar and on the text at once:

| Colour | Meaning |
| --- | --- |
| Green | Calm. |
| Yellow | Worth attention: half or less of a vital is left, the body refused something, the mind did not answer, or the agent is between bodies. |
| Red | Alarm: a quarter or less of a vital is left, fleeing, or a death. |

The loudest of the activity, the reserve, and the body's health sets the colour, so an agent fleeing on a full reserve is red, an agent resting on a nearly-empty one is red as well, and a body beaten down to four hearts is red on an untouched reserve.

A world-hold failure is the one alarm that is *not* in that list, and it is worth knowing where it does appear: it arrives as a red note in chat ("Loom could not hold the world: a death shown here may be a chunk unload."), and the bar stays whatever the watched agent's own activity and vitals make it.
That is deliberate rather than an omission.
A hold failure is true of every agent at once, so coloured onto one agent's bar it would report a world-level condition as though it were that agent's own.
Watch the chat line for it, and check the event log for `worldHoldFailed` before trusting a run's permadeath numbers.

The colour reads how much is *left* and deliberately never which way it is going.
A reserve is spent by living, so `declining` is the standing condition of every living agent rather than news about one: an earlier build coloured the trend, and an in-world run went yellow four seconds after the spawn and stayed yellow for the rest of the agent's life.
The trend word stays on the line, where its absence is the signal - a reserve that is not falling has just been rested, or is frozen because the agent has no body spending it.
`/loombody despawn ada` is the quickest way to see the second case: the line keeps the last reading it took and drops the word, because nothing charges a put-away body's reserve.

```
ada | energy 99.4/100 | no body                                 (yellow)
```

Note lines are prefixed `[loom]` and are bounded.
A refresh delivers at most eight of them; a burst larger than that drops its oldest lines and says how many it dropped, because a display that silently skipped lines is worse than one that admits it.
A line identical to the one before it is not repeated, and is not counted as dropped either, since it is already on the screen.
That is what keeps a condition recorded every tick - a world hold that goes on being refused - from filling the eight lines with one sentence and pushing the `decided` and `died` lines it qualifies off the display.
Anything else arriving in between ends the run, so a condition that comes back after real news is stated again.

## The awkward cases

All five have been driven in world, and each has a unit test as well.

**Death.** Covered above. The display freezes on the tombstone.

**Dimension travel.** Walk the body into a lit nether portal with `/loombody move`, and the display reads travel rather than a death and a new agent:

```
[loom] crossed into another dimension
[loom] has a body now
cy | energy 100/100 | standing by | ...
```

The watch keeps following the same agent, because it is one continuous life.
Check the *count* of `travelled` records in the event log, not just their presence: one round trip is two, and a body bouncing back and forth is the failure this most easily hides.

**Cognition timeout, or no mind at all.** With nothing listening the failure arrives within a second; against a mind that accepts the connection and never replies, the bar reads `thinking` for about 29 seconds first:

```
eve | energy 100/100 | thinking | goal review                   (green)
```

The trigger is on the line for the whole of that wait and the model stamp is not, because no model has answered this cycle yet.
Then the failure lands:

```
[loom] its mind did not answer (failed)
eve | energy 100/100 | mind did not answer | goal review        (yellow)
```

The model stamp is absent here too, and it stays absent even for an agent whose earlier cycles were answered: a stamp belongs to the cycle it answered, and a held one would read "this trigger was answered by this model" about a cycle no model answered.

The reason word is a tag and never prose.
`failed` is a transport or provider failure, `timedOut` is the bridge watchdog, and `saturated` means every worker and queue slot was busy, so the perception was never sent - a word one agent on the shipped cadence will not produce.
The underlying exception text does reach the event log, and it deliberately does not reach the screen, because a provider failure can quote back the perception or the credential it was given.

**No observer.** With nobody watching, the run costs one comparison per refresh and produces no display work at all.

**The observer walking away.** A watcher who disconnects is dropped at the next refresh and their boss bar is taken down, since a bar can survive a reconnect and a stale one would be worse than none.
No `/loomwatch off` is needed.

## Watching an agent actually move

The scripted stand-in only ever idles or rests, so autonomy under it is real cognition and a visible survival cycle, with no motion.
If motion is what you are checking, point the plugin at a mind that replies `moveTo`: either the `openai` backend, or a stand-in of your own on `127.0.0.1:8642` that speaks the contract.
`/loombody move <agentId> <x> <y> <z>` drives the body directly with the mind out of the loop, which shows the `moving` label and its decay but proves nothing about cognition.

## What observation does not change

Nothing, with one exception you should know about.

The observatory holds no body port, no cognition bridge, and no world handle, so it could not disturb a run if it tried.
Its only input is the record stream, offered to it after the real log has taken each record.
`ObserverSink` is one-way and cannot be read from, so nothing an operator is shown can find its way back into what an agent is told.
The agent is never told that it is an AI, an NPC, an experiment subject, or a participant in a simulation, and the display's vocabulary of models, prompt versions, tiers, and permadeath is operator-only for exactly that reason.

The exception is you, standing there.
A body perceives the living entities around it, and a player is one, so a watcher inside the perception radius appears in the agent's perception as an observation.
In the runs above each perception recorded `1 observation(s)`, and that one was the watching player.
Fly out of range, or use a scripted client that stays away, if the run must be unobserved.
Walking one dimension transit with no client connected is worth doing anyway, because a body travelling unobserved is the case that would otherwise read as a death.

## Where the real record is

The display is a view. The record is `plugins/Loom/body-events.jsonl`, append-only, one JSON object per line, written off the main thread.
Derive metrics from it rather than adding counters.

```json
{"tick":7999,"agentId":"fin","event":"perceived","detail":"1 observation(s)","requestId":"356af7c7-...","trigger":"goalReview","vitals":{"level":100.0,"capacity":100.0}}
{"tick":8000,"agentId":"fin","event":"decided","detail":"idle: reviewed goals at the Routine tier; ...","requestId":"356af7c7-...","provenance":{"tier":"ROUTINE","model":"scripted-stand-in","promptVersion":"routine-v1"},"intent":"idle"}
{"tick":8000,"agentId":"fin","event":"intentApplied","detail":"idle -> accepted: standing still","requestId":"356af7c7-...","intent":"idle","outcome":"accepted"}
```

The `vitals` field is the energy reserve as a percentage, and it rides the records that took the reading anyway - a spawn, a perception, a change worth recording, a despawn, a death.
The body's health is a separate `health` field, and each vital has its own event, so a run can tell a starvation from a body beaten to death:

```json
{"tick":510,"agentId":"dex","event":"healthChanged","detail":"health 12 of 20","vitals":{"level":99.275,"capacity":100.0},"health":{"level":12.0,"capacity":20.0}}
{"tick":659,"agentId":"dex","event":"energyChanged","detail":"energy 98.9 of 100","vitals":{"level":98.86111111111111,"capacity":100.0},"health":{"level":8.0,"capacity":20.0}}
{"tick":790,"agentId":"dex","event":"died","detail":"died in-world; this agent will not return","vitals":{"level":98.49722222222222,"capacity":100.0},"health":{"level":0.0,"capacity":20.0}}
```

Each reading is `level` of `capacity`, and which vital it is of is the field it rides in: reading a health reading out of `health.energy` would alias the two vitals again one field further down.
Both readings are on every one of them.
Which vital moved is the event's name, and what the other stood at is what makes the record readable on its own - here, an agent beaten to death on a reserve that had barely been touched.
The `detail` is rounded for a reader; the exact number is in the fields, and anything counting should read those.
`died` is the last *vitals* record on an agent's chain, so a reading after one is a defect rather than a late reading: an earlier build wrote two, off an entity the engine had already removed.
It is not necessarily the last record of any kind, so do not take an agent's last line as the end of its life.
A decision that was already in flight when the agent died still lands afterwards, as `decided` plus `decisionDiscarded`, or as `cognitionUnavailable` if the mind never answered it.
Read a death from the `died` record itself.

The join is by `requestId` and not by tick, because several agents can think in one tick.

Two checks before you trust a run's numbers:

- Search the event log for `worldHoldFailed`. Its presence means some removals classified as deaths may have been chunk unloads.
- Search the server log for `body event log` warnings. They mean records were dropped before reaching the file.

## Current limits

These are true of the prototype today, not of the display.

- The energy reserve is the only survival drive. There is no hunger, stamina, or upkeep stat yet, and no needs framework - one drive, one knob, and a second is added when there is a second to add.
- The reserve and the recorded deaths live in memory only. A server restart forgets both a reserve and who has died, so making either outlive a restart waits on agent-identity persistence; the append-only log is meanwhile the durable record of who died.
- `say` and `flee` are not actuated yet. A mind that chooses either gets `Unsupported`, which the display shows as `the body cannot do that yet` and `body refused it`. That is the honest reading: the decision was real and the body could not carry it out.
- One watch at a time. A second `/loomwatch` from *somebody else* replaces the first and tells the displaced operator so. This is the one-agent demo path on purpose. Re-running it yourself is not a displacement: you keep your own bar, and switching agents keeps it too. `/loomwatch off` and the no-argument report are yours alone, and refuse anybody else rather than acting on their behalf.
- The boss bar title is 96 characters wide and a long line is cut with an ellipsis. The full model stamp is always in the `decided` chat line, which has more room.
