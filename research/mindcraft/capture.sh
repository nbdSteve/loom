#!/usr/bin/env bash
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <run-directory>" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
run="$(cd "$1" && pwd)"
cache="$here/.cache"

git -C "$cache/mindcraft" rev-parse HEAD > "$run/mindcraft-commit.txt"
git -C "$cache/mindcraft" diff > "$run/mindcraft-local.diff"
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
