# Mindcraft as Loom's agents

This directory runs [Mindcraft](https://github.com/mindcraft-bots/mindcraft) agents, as is, on a fresh plain Paper server, and keeps the record a Loom experiment needs: who did what, what each model decided, and what each call cost.

It does not use Loom's body, mind or plugin. Mindcraft brings its own agents: one complete skill per model call (`!collectBlocks`, `!craftRecipe`, ...), a persistent `!goal`, and reflex "modes" that run without the model.

## Versions

| Part | Pinned | Why |
|---|---|---|
| Minecraft | 1.21.6 | The version Mindcraft's README recommends, and one its patched mineflayer 4.33.0 is tested on. The newest Minecraft is 26.3 and Mindcraft claims up to 1.21.11, but on Paper 1.21.11 with mineflayer 4.39.0 (the only mineflayer that speaks 1.21.11) a body stopped under oak leaves was teleported back by the server on every tick and could not move, so its collects stalled until Mindcraft killed the agent (smoke run 20261104T211535Z). |
| Paper | 1.21.6 build 48, sha256 `35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214` | The newest stable build for 1.21.6 on the PaperMC fill API. `run.sh` downloads it once and checks the hash every run. |
| Mindcraft | commit `e1c1fc3ffa481506c2445fc639e16dba504b5920` (2026-10-26) | Mindcraft ships no lockfile, so the commit and `.cache/deps.txt` are the pin. |
| mineflayer | 4.33.0, with all six of Mindcraft's `patches/` applied | The version those patches were made against. A fresh install resolves 4.39.0, on which the mineflayer patch fails. |
| Node | 20 LTS, downloaded and checksum-verified into `.cache/node` | Mindcraft's native `gl` dependency does not compile on Node 24 or newer. |
| Java | 21 | What Paper 1.21.6 needs. |

## Set up (once, about 3 minutes)

```
research/mindcraft/setup.sh
```

It installs Mindcraft into `research/mindcraft/.cache/` (ignored by git).

## Run

```
research/mindcraft/run.sh <agents 1-16> <minutes> <ceiling-usd>
```

Example smoke test: `research/mindcraft/run.sh 1 2 1`.

**This makes paid OpenAI calls.** The key is read from the Keychain service `loom-openai`, only by the spend-cap proxy.

Each run is a new directory `research/mindcraft/runs/<UTC time>/` (ignored by git). It gets:

- A fresh world with a random seed, recorded in `seed.txt`.
- These `server.properties` values: `online-mode=false`, `server-ip=127.0.0.1`, `server-port=25565`, `hardcore=true`, `difficulty=normal`, `view-distance=10`, `simulation-distance=10`, and no world border beyond vanilla's.
- Vanilla hardcore makes a death permanent. It also plays at hard difficulty, whatever `difficulty` says.
- `nbdSteve` as an operator, by its offline-mode UUID.

Agents:

- One Mindcraft profile per agent, all on `gpt-5.4` (`reasoning_effort: low`) with `cooldown` 6000 ms.
- All agents start in one Mindcraft process, so they share one MindServer and can talk to each other.
- `LOG_ALL=true`, `base_profile: survival`, and an `init_message` that asks each agent to choose a long-term goal with `!goal` and to talk to the others when it helps.
- Set `LOOM_MINDCRAFT_MODEL=gpt-5.4-nano` for a cheaper run.

Every model call goes through `proxy.js`, a loopback spend-cap proxy:

- It forwards only `chat/completions` and `embeddings` to `api.openai.com`.
- It prices each answer from its `usage` at `prices.json`, and logs every call (time, agent, model, tokens, cost) to `proxy-calls.jsonl`.
- It refuses with HTTP 429 any call whose worst case could take spend past `<ceiling-usd>`. Each call reserves every request byte as an input token plus `max_completion_tokens` (set to 8192 when absent) of output, so the ceiling holds before the bill and not after.
- When refused, Mindcraft answers "My brain disconnected", and after three of those an agent stops its goal loop. That is how spend stops at the ceiling.

The run stops by itself after `<minutes>`, or on Ctrl-C. It stops Mindcraft, then the proxy, then Paper (`stop`), and then runs `capture.sh`.

While it runs:

- Join with Minecraft Java Edition **1.21.6**, Multiplayer, server address `localhost:25565`, user name `nbdSteve`. The server binds `127.0.0.1` only, so join from this machine.
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
| What did it cost? | `proxy-calls.jsonl`, `proxy-summary.json` |
| All of it, in time order | `events.jsonl` (`join.js`): `joined`, `left`, `said`, `died` from Paper; `decided` and `memSaving` from the model logs; `call` from the proxy |
| What ran | `run.json`, `settings.json`, `profiles/`, `server/server.properties`, `seed.txt`, `mindcraft-commit.txt`, `mindcraft-deps.txt`, `prices.json`, `loom-commit.txt`, `node-version.txt`, `files.sha256` |

`mindcraft-stdout.log` is kept too. Its lines from all agents are interleaved, and the `Agent executed` lines carry no agent name, so the per-agent files are the authority.

## Known limits

- An agent whose goal loop stopped (three answers with no command, which three provider errors in a row also cause) stays idle until somebody talks to it.
- Mindcraft has no tier router: one model per agent for every call.
- How Mindcraft behaves as a hardcore spectator after a death has not been watched yet.

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
