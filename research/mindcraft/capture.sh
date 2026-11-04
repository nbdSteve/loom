#!/usr/bin/env bash
# Complete the record of one Mindcraft run, inside its own run directory.
#
#   research/mindcraft/capture.sh <run-directory>
#
# run.sh calls this when a run stops. The run directory already holds the
# profiles, settings, seed, server.properties, Paper's logs, the proxy's call
# log and Mindcraft's bots/<name>/ (LOG_ALL prompt and response per call,
# memory.json, histories/). This adds what pins the software, then joins the
# whole run into events.jsonl and lists every file with its sha256.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
run="$(cd "$1" && pwd)"
cache="$here/.cache"

git -C "$cache/mindcraft" rev-parse HEAD > "$run/mindcraft-commit.txt"
cp "$cache/deps.txt" "$run/mindcraft-deps.txt"
cp "$here/prices.json" "$run/prices.json"
git -C "$here" rev-parse HEAD > "$run/loom-commit.txt"
"$cache/node/bin/node" --version > "$run/node-version.txt"
"$cache/node/bin/node" "$here/join.js" "$run"
(cd "$run" && find . -type f ! -name files.sha256 ! -path './server/world*' ! -path './server/libraries/*' \
  ! -path './server/cache/*' ! -path './server/versions/*' ! -name paper.jar ! -name paper-console -print0 |
  sort -z | xargs -0 shasum -a 256 > files.sha256)
echo "record complete in $run"
[ -f "$run/proxy-summary.json" ] && cat "$run/proxy-summary.json"
