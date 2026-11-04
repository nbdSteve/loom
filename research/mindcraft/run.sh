#!/usr/bin/env bash
# Run Mindcraft agents, as is, on a fresh plain Paper 1.21.6 world.
#
#   research/mindcraft/run.sh <agents> <minutes> <ceiling-usd>
#
# Every call goes through the spend-cap proxy (proxy.js), which refuses once
# <ceiling-usd> could be passed. The run stops by itself after <minutes>, or on
# Ctrl-C, and then capture.sh writes the record into the run directory.
#
# Optional environment:
#   LOOM_MINDCRAFT_UPSTREAM      loopback http mock instead of api.openai.com (no key is read)
#   LOOM_MINDCRAFT_MODEL         default gpt-5.4
#   LOOM_MINDCRAFT_PROXY_PORT    default 18565
#   LOOM_MINDCRAFT_MINDSERVER_PORT  default 8080
#   LOOM_MINDCRAFT_INIT_MESSAGE     the start message every agent gets; the default
#                                   names the others and asks each for a !goal
#   LOOM_MINDCRAFT_GRACE_SECONDS    how long an agent may be off the server before
#                                   watchdog.js restarts it, default 60
set -euo pipefail

usage() { echo "usage: $0 <agents 1-16> <minutes> <ceiling-usd>" >&2; exit 2; }
[ $# -eq 3 ] || usage
agents="$1" minutes="$2" ceiling="$3"
[[ "$agents" =~ ^[0-9]+$ ]] && [ "$agents" -ge 1 ] && [ "$agents" -le 16 ] || usage
[[ "$minutes" =~ ^[0-9]+$ ]] && [ "$minutes" -ge 1 ] || usage
[[ "$ceiling" =~ ^[0-9]+(\.[0-9]+)?$ ]] || usage

here="$(cd "$(dirname "$0")" && pwd)"
cache="$here/.cache"
mindcraft="$cache/mindcraft"
node_bin="$cache/node/bin/node"
model="${LOOM_MINDCRAFT_MODEL:-gpt-5.4}"
proxy_port="${LOOM_MINDCRAFT_PROXY_PORT:-18565}"
mindserver_port="${LOOM_MINDCRAFT_MINDSERVER_PORT:-8080}"
grace="${LOOM_MINDCRAFT_GRACE_SECONDS:-60}"
upstream="${LOOM_MINDCRAFT_UPSTREAM:-https://api.openai.com}"
# Paper 1.21.6 build 48, the newest stable build of the Minecraft version
# Mindcraft recommends, which its patched mineflayer 4.33.0 supports.
minecraft_version=1.21.6
paper_jar_name=paper-1.21.6-48.jar
paper_sha=35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214
paper_url=https://fill-data.papermc.io/v1/objects/$paper_sha/$paper_jar_name
operator=nbdSteve
names=(ada_ash bram_oak cora_fen dov_hale elin_moss finn_reed gwen_vale hugo_birch
  iris_dale jon_marsh kira_stone leo_brook mira_glen nils_ford orla_heath piet_wren)

[ -x "$node_bin" ] && [ -d "$mindcraft/node_modules" ] || { echo "run setup.sh first" >&2; exit 1; }
for port in 25565 "$proxy_port" "$mindserver_port"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "port $port is already in use" >&2; exit 1
  fi
done

java_bin="java"
if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 > /dev/null 2>&1; then
  java_bin="$(/usr/libexec/java_home -v 21)/bin/java"
fi

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
paper_jar="$cache/$paper_jar_name"
if [ ! -f "$paper_jar" ] || [ "$(sha256 "$paper_jar")" != "$paper_sha" ]; then
  curl -fsSL "$paper_url" -o "$paper_jar.part"
  [ "$(sha256 "$paper_jar.part")" = "$paper_sha" ] || { echo "downloaded Paper does not match $paper_sha" >&2; exit 1; }
  mv "$paper_jar.part" "$paper_jar"
fi

run="$here/runs/$(date -u +%Y%m%dT%H%M%SZ)"
server="$run/server"
mkdir -p "$server" "$run/profiles" "$run/bots"
date -u +%Y-%m-%dT%H:%M:%SZ > "$run/started-at.txt"
date +%Y-%m-%d > "$run/started-local-date.txt"

# A fresh world: a random seed, recorded, and no world border beyond vanilla's.
seed="$(od -An -N7 -tu8 /dev/urandom | tr -d ' ')"
echo "$seed" > "$run/seed.txt"
cp "$paper_jar" "$server/paper.jar"
echo "eula=true" > "$server/eula.txt"
cat > "$server/server.properties" <<EOF
online-mode=false
server-ip=127.0.0.1
server-port=25565
hardcore=true
difficulty=normal
level-seed=$seed
view-distance=10
simulation-distance=10
spawn-protection=0
max-players=$((agents + 8))
motd=Loom Mindcraft run
EOF
operator_uuid="$("$node_bin" -e '
const h = require("crypto").createHash("md5").update("OfflinePlayer:" + process.argv[1]).digest()
h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
const x = h.toString("hex")
console.log([x.slice(0, 8), x.slice(8, 12), x.slice(12, 16), x.slice(16, 20), x.slice(20)].join("-"))
' "$operator")"
cat > "$server/ops.json" <<EOF
[{"uuid":"$operator_uuid","name":"$operator","level":4,"bypassesPlayerLimit":true}]
EOF

# One profile per agent, every call routed through the proxy under its own name,
# so the proxy log attributes cost by agent. A url makes Mindcraft use
# chat.completions, which takes `reasoning_effort` rather than `reasoning`.
roster=()
profiles=()
for ((i = 0; i < agents; i++)); do
  name="${names[$i]}"
  if [ -e "$mindcraft/bots/$name" ] && [ ! -L "$mindcraft/bots/$name" ]; then
    echo "$mindcraft/bots/$name is a real directory from an earlier run; move it away first" >&2; exit 1
  fi
  roster+=("$name")
  url="http://127.0.0.1:$proxy_port/agent/$name/v1"
  params='{}'
  [ "$model" = "gpt-5.4" ] && params='{"reasoning_effort":"low"}'
  cat > "$run/profiles/$name.json" <<EOF
{
  "name": "$name",
  "model": { "api": "openai", "model": "$model", "url": "$url", "params": $params },
  "embedding": { "api": "openai", "model": "text-embedding-3-small", "url": "$url" },
  "cooldown": 6000
}
EOF
  profiles+=("\"$run/profiles/$name.json\"")
done
others="$(IFS=,; echo "${roster[*]}" | sed 's/,/, /g')"
init_message="${LOOM_MINDCRAFT_INIT_MESSAGE:-You live in this world with these other agents: $others. Choose a long-term goal for yourself and set it with !goal. Talk to the others with !startConversation when it helps.}"
settings="$("$node_bin" -e 'console.log(JSON.stringify({
  host: "127.0.0.1", minecraft_version: process.argv[2], auth: "offline",
  base_profile: "survival", auto_open_ui: false, load_memory: false,
  allow_insecure_coding: false, init_message: process.argv[1]
}))' "$init_message" "$minecraft_version")"
echo "$settings" > "$run/settings.json"
printf '%s\n' "$agents" > "$run/agents.txt"
printf '%s\n' "${roster[@]}" > "$run/roster.txt"
cat > "$run/run.json" <<EOF
{"agents":$agents,"minutes":$minutes,"ceilingUsd":$ceiling,"model":"$model","upstream":"$upstream","mindcraftCommit":"$(git -C "$mindcraft" rev-parse HEAD)","minecraftVersion":"$minecraft_version","paperJar":"$paper_jar_name","paperSha256":"$paper_sha","seed":"$seed"}
EOF

paper_pid="" proxy_pid="" mindcraft_pid="" watchdog_pid=""
cleaned=0
cleanup() {
  [ "$cleaned" -eq 1 ] && return
  cleaned=1
  echo "stopping run $run"
  # The watchdog goes first, so it cannot restart an agent that is being stopped.
  if [ -n "$watchdog_pid" ]; then kill -TERM "$watchdog_pid" 2> /dev/null || true; wait "$watchdog_pid" 2> /dev/null || true; fi
  if [ -n "$mindcraft_pid" ] && kill -0 "$mindcraft_pid" 2> /dev/null; then
    kill -INT "$mindcraft_pid" 2> /dev/null || true
    for _ in $(seq 1 15); do kill -0 "$mindcraft_pid" 2> /dev/null || break; sleep 1; done
    kill -TERM "$mindcraft_pid" 2> /dev/null || true
  fi
  pkill -INT -f "$mindcraft/src/process/init_agent.js.*-p $mindserver_port" 2> /dev/null || true
  sleep 2
  pkill -KILL -f "$mindcraft/src/process/init_agent.js.*-p $mindserver_port" 2> /dev/null || true
  if [ -n "$proxy_pid" ]; then kill -TERM "$proxy_pid" 2> /dev/null || true; wait "$proxy_pid" 2> /dev/null || true; fi
  if [ -n "$paper_pid" ] && kill -0 "$paper_pid" 2> /dev/null; then
    echo stop >&3 || true
    for _ in $(seq 1 60); do kill -0 "$paper_pid" 2> /dev/null || break; sleep 1; done
    kill -TERM "$paper_pid" 2> /dev/null || true
  fi
  for name in "${roster[@]}"; do rm -f "$mindcraft/bots/$name"; done
  date -u +%Y-%m-%dT%H:%M:%SZ > "$run/stopped-at.txt"
  "$here/capture.sh" "$run" || echo "capture failed; the raw run is still in $run" >&2
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# macOS sleeps an idle host mid-run (Loom lost r37 that way); hold an idle-sleep
# assertion for exactly this script's lifetime.
if command -v caffeinate > /dev/null 2>&1; then caffeinate -i -w $$ & fi

# Paper, with a console FIFO so the run can stop it with `stop` and an operator
# can type commands: echo "say hello" > $run/paper-console
mkfifo "$run/paper-console"
exec 3<> "$run/paper-console"
(cd "$server" && exec "$java_bin" -Xms2G -Xmx4G -jar paper.jar nogui < "$run/paper-console" > "$run/paper-stdout.log" 2>&1) &
paper_pid=$!
echo "paper starting (seed $seed)"
for _ in $(seq 1 300); do
  grep -q 'Done (' "$run/paper-stdout.log" 2> /dev/null && break
  kill -0 "$paper_pid" 2> /dev/null || { echo "paper exited; see $run/paper-stdout.log" >&2; exit 1; }
  sleep 1
done
grep -q 'Done (' "$run/paper-stdout.log" || { echo "paper did not start in 300 s" >&2; exit 1; }

"$node_bin" "$here/proxy.js" --port "$proxy_port" --ceiling-usd "$ceiling" --prices "$here/prices.json" \
  --upstream "$upstream" --log "$run/proxy-calls.jsonl" --summary "$run/proxy-summary.json" \
  > "$run/proxy-stdout.log" 2>&1 &
proxy_pid=$!
for _ in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$proxy_port/" || true)" = 404 ] && break
  kill -0 "$proxy_pid" 2> /dev/null || { cat "$run/proxy-stdout.log" >&2; exit 1; }
  sleep 1
done

# Mindcraft writes bots/<name>/ under its own checkout; point each at the run.
for name in "${roster[@]}"; do
  mkdir -p "$run/bots/$name"
  rm -f "$mindcraft/bots/$name"
  ln -s "$run/bots/$name" "$mindcraft/bots/$name"
done
profiles_json="[$(IFS=,; echo "${profiles[*]}")]"
start_mindcraft() {
  (cd "$mindcraft" && exec env OPENAI_API_KEY=sk-loom-proxy-holds-the-key LOG_ALL=true \
    MINECRAFT_PORT=25565 MINDSERVER_PORT="$mindserver_port" PROFILES="$profiles_json" SETTINGS_JSON="$1" \
    "$node_bin" main.js >> "$run/mindcraft-stdout.log" 2>&1) &
  mindcraft_pid=$!
}
start_mindcraft "$settings"
"$node_bin" "$here/watchdog.js" --run "$run" --mindcraft "$mindcraft" --mindserver-port "$mindserver_port" \
  --roster "$(IFS=,; echo "${roster[*]}")" --console "$run/paper-console" --observer "$operator" \
  --grace-seconds "$grace" > "$run/watchdog-stdout.log" 2>&1 &
watchdog_pid=$!
echo "mindcraft started: ${roster[*]} on $model, ceiling \$$ceiling, $minutes min"
echo "join with Minecraft Java $minecraft_version at localhost:25565 as $operator (operator, put in spectator on join); MindServer UI http://localhost:$mindserver_port"

deadline=$(($(date +%s) + minutes * 60))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if ! kill -0 "$mindcraft_pid" 2> /dev/null; then
    # Mindcraft itself went away: start it again, every agent from its memory.
    echo "mindcraft exited early; starting it again from saved memory" >&2
    date -u +%Y-%m-%dT%H:%M:%SZ >> "$run/mindcraft-relaunches.txt"
    start_mindcraft "$("$node_bin" -e 'const s = JSON.parse(process.argv[1]); s.load_memory = true; s.init_message = "Agent process restarted."; console.log(JSON.stringify(s))' "$settings")"
    sleep 10
  fi
  kill -0 "$paper_pid" 2> /dev/null || { echo "paper exited early" >&2; break; }
  sleep 5
done
echo "run time over"
