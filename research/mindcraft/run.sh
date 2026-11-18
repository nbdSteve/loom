#!/usr/bin/env bash
set -euo pipefail

usage() { echo "usage: $0 <agents 1-32> <minutes> <ceiling-usd>" >&2; exit 2; }
[ $# -eq 3 ] || usage
agents="$1" minutes="$2" ceiling="$3"
[[ "$agents" =~ ^[0-9]+$ ]] && [ "$agents" -ge 1 ] && [ "$agents" -le 32 ] || usage
[[ "$minutes" =~ ^[0-9]+$ ]] && [ "$minutes" -ge 1 ] || usage
[[ "$ceiling" =~ ^[0-9]+(\.[0-9]+)?$ ]] || usage

here="$(cd "$(dirname "$0")" && pwd)"
cache="$here/.cache"
mindcraft="$cache/mindcraft"
node_bin="$cache/node/bin/node"
model="${LOOM_MINDCRAFT_MODEL:-gpt-5.4}"
reasoning_effort="${LOOM_MINDCRAFT_REASONING_EFFORT:-low}"
min_ms_between_agent_model_calls="${LOOM_MINDCRAFT_COOLDOWN_MS:-6000}"
[[ "$min_ms_between_agent_model_calls" =~ ^[0-9]+$ ]] || { echo "LOOM_MINDCRAFT_COOLDOWN_MS must be a whole number of milliseconds" >&2; exit 2; }
[[ "$reasoning_effort" =~ ^(none|minimal|low|medium|high|xhigh)$ ]] || { echo "unknown reasoning effort $reasoning_effort" >&2; exit 2; }
proxy_port="${LOOM_MINDCRAFT_PROXY_PORT:-18565}"
mindserver_port="${LOOM_MINDCRAFT_MINDSERVER_PORT:-8080}"
seconds_off_server_before_watchdog_restarts_agent="${LOOM_MINDCRAFT_GRACE_SECONDS:-60}"
real_run_server_port=25565
offline_test_server_port=25599
server_port="${LOOM_MINDCRAFT_SERVER_PORT:-$real_run_server_port}"
openai_upstream=https://api.openai.com
upstream="${LOOM_MINDCRAFT_UPSTREAM:-$openai_upstream}"
if [ "$server_port" = "$real_run_server_port" ] && [ "$upstream" != "$openai_upstream" ]; then
  echo "an offline test may not use port $real_run_server_port, which observers join for real runs; set LOOM_MINDCRAFT_SERVER_PORT=$offline_test_server_port" >&2
  exit 2
fi
minecraft_version=1.21.6
paper_build=48
paper_jar_name=paper-$minecraft_version-$paper_build.jar
paper_sha=35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214
paper_url=https://fill-data.papermc.io/v1/objects/$paper_sha/$paper_jar_name
operator=nbdSteve
names=(ada_ash bram_oak cora_fen dov_hale elin_moss finn_reed gwen_vale hugo_birch
  iris_dale jon_marsh kira_stone leo_brook mira_glen nils_ford orla_heath piet_wren
  ansel_ivy beth_rowan cal_thorn dara_pine emil_heath faye_cedar gil_slate hana_fern
  ivo_lark juno_wells kai_ridge lena_frost milo_crane nora_hollow oren_maple pia_willow)

[ -x "$node_bin" ] && [ -d "$mindcraft/node_modules" ] || { echo "run setup.sh first" >&2; exit 1; }
for port in "$server_port" "$proxy_port" "$mindserver_port"; do
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

seed="$(od -An -N7 -tu8 /dev/urandom | tr -d ' ')"
echo "$seed" > "$run/seed.txt"
cp "$paper_jar" "$server/paper.jar"
echo "eula=true" > "$server/eula.txt"
cat > "$server/server.properties" <<EOF
online-mode=false
server-ip=127.0.0.1
server-port=$server_port
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

takes_reasoning_effort() { [[ "$1" == gpt-5* ]]; }
roster=()
profiles=()
for ((i = 0; i < agents; i++)); do
  name="${names[$i]}"
  if [ -e "$mindcraft/bots/$name" ] && [ ! -L "$mindcraft/bots/$name" ]; then
    echo "$mindcraft/bots/$name is a real directory from an earlier run; move it away first" >&2; exit 1
  fi
  roster+=("$name")
  proxy_url_attributing_calls_to_agent="http://127.0.0.1:$proxy_port/agent/$name/v1"
  chat_completions_params='{}'
  takes_reasoning_effort "$model" && chat_completions_params="{\"reasoning_effort\":\"$reasoning_effort\"}"
  cat > "$run/profiles/$name.json" <<EOF
{
  "name": "$name",
  "model": { "api": "openai", "model": "$model", "url": "$proxy_url_attributing_calls_to_agent", "params": $chat_completions_params },
  "embedding": { "api": "openai", "model": "text-embedding-3-small", "url": "$proxy_url_attributing_calls_to_agent" },
  "cooldown": $min_ms_between_agent_model_calls
}
EOF
  profiles+=("\"$run/profiles/$name.json\"")
done
others="$(IFS=,; echo "${roster[*]}" | sed 's/,/, /g')"
init_message="${LOOM_MINDCRAFT_INIT_MESSAGE:-You live in this world with these other agents: $others. Choose a long-term goal for yourself and set it with !goal. Talk to the others with !startConversation when it helps.}"
settings="$("$node_bin" -e 'console.log(JSON.stringify({
  host: "127.0.0.1", minecraft_version: process.argv[2], auth: "offline",
  base_profile: "survival", auto_open_ui: false, load_memory: false,
  allow_insecure_coding: true, init_message: process.argv[1]
}))' "$init_message" "$minecraft_version")"
echo "$settings" > "$run/settings.json"
printf '%s\n' "$agents" > "$run/agents.txt"
printf '%s\n' "${roster[@]}" > "$run/roster.txt"
cat > "$run/run.json" <<EOF
{"agents":$agents,"minutes":$minutes,"ceilingUsd":$ceiling,"model":"$model","reasoningEffort":"$reasoning_effort","cooldownMs":$min_ms_between_agent_model_calls,"upstream":"$upstream","mindcraftCommit":"$(git -C "$mindcraft" rev-parse HEAD)","minecraftVersion":"$minecraft_version","paperJar":"$paper_jar_name","paperSha256":"$paper_sha","seed":"$seed"}
EOF

paper_pid="" proxy_pid="" mindcraft_pid="" watchdog_pid=""
cleaned=0
stop_watchdog_before_it_can_restart_a_stopping_agent() {
  if [ -n "$watchdog_pid" ]; then kill -TERM "$watchdog_pid" 2> /dev/null || true; wait "$watchdog_pid" 2> /dev/null || true; fi
}
cleanup() {
  [ "$cleaned" -eq 1 ] && return
  cleaned=1
  echo "stopping run $run"
  stop_watchdog_before_it_can_restart_a_stopping_agent
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

keep_macos_awake_while_this_script_runs() {
  if command -v caffeinate > /dev/null 2>&1; then caffeinate -i -w $$ & fi
}
keep_macos_awake_while_this_script_runs

paper_console_fifo_for_stop_and_operator_commands="$run/paper-console"
mkfifo "$paper_console_fifo_for_stop_and_operator_commands"
exec 3<> "$paper_console_fifo_for_stop_and_operator_commands"
(cd "$server" && exec "$java_bin" -Xms2G -Xmx4G -jar paper.jar nogui < "$paper_console_fifo_for_stop_and_operator_commands" > "$run/paper-stdout.log" 2>&1) &
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
  --inflight "$run/proxy-inflight.json" \
  > "$run/proxy-stdout.log" 2>&1 &
proxy_pid=$!
for _ in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$proxy_port/" || true)" = 404 ] && break
  kill -0 "$proxy_pid" 2> /dev/null || { cat "$run/proxy-stdout.log" >&2; exit 1; }
  sleep 1
done

link_mindcraft_bot_dirs_into_run() {
  for name in "${roster[@]}"; do
    mkdir -p "$run/bots/$name"
    rm -f "$mindcraft/bots/$name"
    ln -s "$run/bots/$name" "$mindcraft/bots/$name"
  done
}
link_mindcraft_bot_dirs_into_run
profiles_json="[$(IFS=,; echo "${profiles[*]}")]"
all_profiles_json="$profiles_json"
dead_agents_listed_by_watchdog="$run/dead-agents.txt"
profiles_of_living_agents() {
  "$node_bin" -e '
const fs = require("fs")
const dead = fs.existsSync(process.argv[2]) ? fs.readFileSync(process.argv[2], "utf8").split("\n").filter(Boolean) : []
console.log(JSON.stringify(JSON.parse(process.argv[1]).filter((p) => !dead.some((d) => p.endsWith("/" + d + ".json")))))
' "$all_profiles_json" "$dead_agents_listed_by_watchdog"
}
start_mindcraft() {
  (cd "$mindcraft" && exec env OPENAI_API_KEY=sk-loom-proxy-holds-the-key LOG_ALL=true \
    MINECRAFT_PORT="$server_port" MINDSERVER_PORT="$mindserver_port" PROFILES="$profiles_json" SETTINGS_JSON="$1" \
    "$node_bin" main.js >> "$run/mindcraft-stdout.log" 2>&1) &
  mindcraft_pid=$!
}
start_mindcraft "$settings"
"$node_bin" "$here/watchdog.js" --run "$run" --mindcraft "$mindcraft" --mindserver-port "$mindserver_port" \
  --roster "$(IFS=,; echo "${roster[*]}")" --console "$paper_console_fifo_for_stop_and_operator_commands" --observer "$operator" \
  --grace-seconds "$seconds_off_server_before_watchdog_restarts_agent" > "$run/watchdog-stdout.log" 2>&1 &
watchdog_pid=$!
echo "mindcraft started: ${roster[*]} on $model ($reasoning_effort effort), ceiling \$$ceiling, $minutes min"
echo "join with Minecraft Java $minecraft_version at localhost:$server_port as $operator (operator, put in spectator on join); MindServer UI http://localhost:$mindserver_port"

deadline=$(($(date +%s) + minutes * 60))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if ! kill -0 "$mindcraft_pid" 2> /dev/null; then
    echo "mindcraft exited early; starting it again from saved memory" >&2
    date -u +%Y-%m-%dT%H:%M:%SZ >> "$run/mindcraft-relaunches.txt"
    profiles_json="$(profiles_of_living_agents)"
    start_mindcraft "$("$node_bin" -e 'const s = JSON.parse(process.argv[1]); s.load_memory = true; s.init_message = "Agent process restarted."; console.log(JSON.stringify(s))' "$settings")"
    sleep 10
  fi
  kill -0 "$paper_pid" 2> /dev/null || { echo "paper exited early" >&2; break; }
  sleep 5
done
echo "run time over"
