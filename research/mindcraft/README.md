# Mindcraft as Loom's agents

This directory runs [Mindcraft](https://github.com/mindcraft-bots/mindcraft) agents, as is, on a fresh plain Paper server, and keeps the record a Loom experiment needs: who did what, what each model decided, and what each call cost.

It does not use Loom's body, mind or plugin. Mindcraft brings its own agents: one complete skill per model call (`!collectBlocks`, `!craftRecipe`, ...), a persistent `!goal`, and reflex "modes" that run without the model.

## Versions

| Part | Pinned | Why |
|---|---|---|
| Minecraft | 1.21.6 | The version Mindcraft's README recommends, and one its patched mineflayer 4.33.0 is tested on. The newest Minecraft is 26.3 and Mindcraft claims up to 1.21.11, but on Paper 1.21.11 with mineflayer 4.39.0 (the only mineflayer that speaks 1.21.11) a body stopped under oak leaves was teleported back by the server on every tick and could not move, so its collects stalled until Mindcraft killed the agent (smoke run 20261104T211535Z). |
| Paper | 1.21.6 build 48, sha256 `35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214` | The newest stable build for 1.21.6 on the PaperMC fill API. `run.sh` downloads it once and checks the hash every run. |
| Mindcraft | commit `e1c1fc3ffa481506c2445fc639e16dba504b5920` (2026-10-26), plus `patches/` | Mindcraft ships no lockfile, so the commit and `.cache/deps.txt` are the pin. `setup.sh` applies Loom's fixes in `patches/` after the dependency install; each run records the local source diff in `mindcraft-local.diff`. See "Loom's patches" below. |
| mineflayer | 4.33.0, with all six of Mindcraft's `patches/` applied | The version those patches were made against. A fresh install resolves 4.39.0, on which the mineflayer patch fails. |
| Node | 20 LTS, downloaded and checksum-verified into `.cache/node` | Mindcraft's native `gl` dependency does not compile on Node 24 or newer. |
| Java | 21 | What Paper 1.21.6 needs. |

## Loom's patches

`setup.sh` applies each file in `patches/` to the Mindcraft checkout, after Mindcraft's own patches. Paths are relative to the checkout, so one patch can fix Mindcraft's source or a dependency in `node_modules`.

- **`01-mindserver-null-socket.patch`.** Mindcraft's MindServer crashed its whole process when one agent messaged another that was restarting (`mindserver.js:206`, `TypeError` on `null.emit`). That disconnected every agent (society run `20261104T223720Z`). It now drops that one message.
- **`02-mineflayer-entity-velocity.patch`.**
  - The fault: with Mindcraft's unpinned dependencies, minecraft-data resolves to 3.119.0. That version describes 1.21.6's `entity_velocity` and `spawn_entity` velocity as one `velocity: {x, y, z}` field, but mineflayer 4.33.0 reads `velocityX/Y/Z`, which are undefined.
  - The effect: any knockback gave the bot a NaN velocity, physics then made its position NaN, and Paper kicked it with "Invalid move player packet received". That was 49 kicks in the society run's 20 minutes, and 16 in a 6-minute offline night test.
  - The fix: the patch reads either shape, as later mineflayer releases do.
  - Proof: a bot hit by a summoned zombie was kicked on the first hit without the patch. With it, the bot took five hits and was never kicked.

- **`03-group-messages.patch`.** Group conversation.
  - **The command:** Mindcraft lets an agent talk to one partner at a time. This patch adds `!broadcast("...")`, which sends one message to every other agent in the game through the MindServer.
  - **Receiving:** each agent receives a broadcast as a system message ("GROUP MESSAGE from ... to every agent"), even while it is in a one-to-one conversation, and can answer the group with `!broadcast`.
  - **Rate limit:** one broadcast per agent every 20 s, so the group cannot loop.
  - **The record:** a broadcast is also said in public chat as `(To everyone) ...`, so Paper's log records it, and `join.js` writes it as a `groupSaid` event.
  - **Tested offline on port 25599 with the mock:** `ada_ash` broadcast once. `bram_oak` and `cora_fen` both received it and answered the group; `cora_fen` was mid one-to-one conversation with bram at the time. Paper logged all three, and the rate limit refused one repeat.

## Code writing

`run.sh` sets Mindcraft's `allow_insecure_coding: true`, so agents can use `!newAction`: they write JavaScript that Mindcraft runs to build. This is the captain's decision for this laptop.

The code is written by the model and runs inside the Mindcraft process on this host. Mindcraft runs it in an SES compartment that exposes only its skills library, and it has no time limit (`code_timeout_mins: -1`).

Run it only on a machine where that is acceptable. The society run before this change refused about 20 `!newAction` attempts, and nothing was built.

## Set up (once, about 3 minutes)

```
research/mindcraft/setup.sh
```

It installs Mindcraft into `research/mindcraft/.cache/` (ignored by git).

## Run

```
research/mindcraft/run.sh <agents 1-32> <minutes> <ceiling-usd>
```

Example smoke test: `research/mindcraft/run.sh 1 2 1`.

**This makes paid OpenAI calls.** The key is read from the Keychain service `loom-openai`, only by the spend-cap proxy.

Each run is a new directory `research/mindcraft/runs/<UTC time>/` (ignored by git). It gets:

- A fresh world with a random seed, recorded in `seed.txt`.
- These `server.properties` values: `online-mode=false`, `server-ip=127.0.0.1`, `server-port=25565`, `hardcore=true`, `difficulty=normal`, `view-distance=10`, `simulation-distance=10`, and no world border beyond vanilla's.
- Vanilla hardcore makes a death permanent. It also plays at hard difficulty, whatever `difficulty` says.
- `nbdSteve` as an operator, by its offline-mode UUID.

Agents:

- One Mindcraft profile per agent, all on `gpt-5.4` (`reasoning_effort: low` unless set) with `cooldown` 6000 ms unless `LOOM_MINDCRAFT_COOLDOWN_MS` is set (Mindcraft's own default is 3000).
- All agents start in one Mindcraft process, so they share one MindServer and can talk to each other.
- `LOG_ALL=true`, `base_profile: survival`, and an `init_message` that asks each agent to choose a long-term goal with `!goal` and to talk to the others when it helps.
- Set `LOOM_MINDCRAFT_MODEL=gpt-5.4-nano` for a cheaper run.
- Set `LOOM_MINDCRAFT_REASONING_EFFORT` (`none`, `minimal`, `low`, `medium`, `high` or `xhigh`, default `low`) for any gpt-5 model. It is recorded in `run.json`.
- Set `LOOM_MINDCRAFT_INIT_MESSAGE` to give every agent one shared start message instead.
- Port 25565 is for real runs only, because observers join it. An offline test with a mock upstream must set another port, such as `LOOM_MINDCRAFT_SERVER_PORT=25599`. `run.sh` refuses to start a mock run on 25565.

Example society run with no assigned roles:

```
LOOM_MINDCRAFT_INIT_MESSAGE="You and the others have just arrived in this world. Together, survive and build a thriving community. Talk with the others to coordinate." research/mindcraft/run.sh 10 20 15
```

Every model call goes through `proxy.js`, a loopback spend-cap proxy:

- It forwards only `chat/completions` and `embeddings` to `api.openai.com`.
- It prices each answer from its `usage` at `prices.json`, and logs every call (time, agent, model, tokens, cost) to `proxy-calls.jsonl`.
- It refuses with HTTP 429 any call whose worst case could take spend past `<ceiling-usd>`. Each call reserves every request byte as an input token plus `max_completion_tokens` (set to 8192 when absent) of output, so the ceiling holds before the bill and not after.
- When refused, Mindcraft answers "My brain disconnected", and after three of those an agent stops its goal loop. That is how spend stops at the ceiling.

`watchdog.js` keeps every agent in the world. Mindcraft restarts an agent only when its process exits, and never if it exits within 10 s of its last restart. On 2026-11-05 an agent's process froze inside `collectBlocks` (run `20261104T213950Z`), was timed out by the server at 07:46:21, and never came back.

The watchdog follows Paper's log instead. An agent off the server for longer than `LOOM_MINDCRAFT_GRACE_SECONDS` (default 60; at least 180 before its first join) is killed if it has a process. If Mindcraft does not start it again within 8 s, the MindServer is asked to. Mindcraft restarts it from its saved memory, and if that memory shows no running goal loop the agent is told to resume its goal. If Mindcraft's main process exits, `run.sh` starts it again with `load_memory`.

All of these paths were tested with the mock, each back in the world with its goal running:

- A frozen agent (`SIGSTOP`).
- Two kills within 10 s.
- A killed main process.

Two more rules:

- **A death is final.** The world is hardcore, so a dead agent would otherwise come back as a spectator and keep acting. On its death message in Paper's log, the watchdog stops the agent for good: it sends SIGINT, which Mindcraft does not restart. It lists the agent in `dead-agents.txt`, which `run.sh` leaves out if it relaunches Mindcraft, and it stops the agent again if it ever rejoins.
- **An idle agent is told to set its goal again.** This applies to an agent in the world whose goal loop has stopped (not merely paused for a conversation) and that has made no model call for 90 s. The goal loop stops after three answers with no command, or three provider errors. A call still in flight counts as activity: the proxy keeps `proxy-inflight.json` by agent, and the watchdog never nudges an agent waiting on an answer. In society run r2 (high effort), nudges at 90 s made Mindcraft discard answers still being generated 44 times, most of them code-writing calls.

Both rules were tested offline: `kill ada_ash` left its process gone and never rejoined, and an idle agent was told after 91 s and made its next call.

The watchdog also puts `nbdSteve` in spectator mode on every join, as an operator.

The run stops by itself after `<minutes>`, or on Ctrl-C. It stops Mindcraft, then the proxy, then Paper (`stop`), and then runs `capture.sh`.

While it runs:

- Join with Minecraft Java Edition **1.21.6**, Multiplayer, server address `localhost:25565`, user name `nbdSteve`. You arrive in spectator mode and stay an operator. The server binds `127.0.0.1` only, so join from this machine.
- The MindServer UI is at `http://localhost:8080`.
- Type server console commands with `echo "time set day" > research/mindcraft/runs/<run>/paper-console`.

Offline test, with no key and no paid call: start a loopback OpenAI mock and set `LOOM_MINDCRAFT_UPSTREAM=http://127.0.0.1:<port>`. Any non-OpenAI upstream must be loopback http, and it receives no credential.

## Capture

`capture.sh <run-directory>` runs at the end of every run. You can run it again at any time.

| Question | File in the run directory |
|---|---|
| What did each model decide? | `bots/<agent>/logs/conversation_<time>.txt`: the full prompt and response of every call |
| What did it remember, and which goal was active? | `bots/<agent>/memory.json`, `bots/<agent>/histories/*.json` |
| Who did what, as the server saw it? | `server/logs/latest.log`: joins, quits, deaths, and every agent chat line. Agents announce each command in chat, so this is a server-timestamped action record. |
| What did it cost? | `proxy-calls.jsonl`, `proxy-summary.json` (`proxy-inflight.json` is the live count of calls waiting on an answer) |
| When was an agent out of the world, and how did it come back? | `watchdog.jsonl` (`joined`, `left`, `missing`, `killed`, `restartedByMindcraft`, `startRequested`, `goalResumedFromMemory`, `toldToResumeGoal`, `goalLoopRestarted`, `died`, `stoppedForGood`, `observerSpectator`), `mindcraft-relaunches.txt`, `dead-agents.txt` |
| All of it, in time order | `events.jsonl` (`join.js`): `joined`, `left`, `said`, `groupSaid`, `died` from Paper; `decided` and `memSaving` from the model logs; `call` from the proxy; `watchdog:*` from the watchdog |
| What ran | `run.json`, `settings.json`, `profiles/`, `server/server.properties`, `seed.txt`, `mindcraft-commit.txt`, `mindcraft-deps.txt`, `prices.json`, `loom-commit.txt`, `node-version.txt`, `files.sha256` |

`mindcraft-stdout.log` is kept too. Its lines from all agents are interleaved, and the `Agent executed` lines carry no agent name, so the per-agent files are the authority.

## Known limits

- What froze the agent's event loop in `collectBlocks("acacia_log", 8)` is not known. It came right after the server's "ada_ash moved wrongly!" correction. A blocked process cannot be asked for its stack, so the watchdog only recovers from a freeze. It cannot explain one.
- Mindcraft has no tier router: one model per agent for every call.

## License

Mindcraft is MIT licensed:

```
MIT License

Copyright (c) 2024 Kolby Nottingham

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

This directory does not copy Mindcraft's code. `setup.sh` fetches it from GitHub.
