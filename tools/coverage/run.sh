#!/usr/bin/env bash
# Runs every runtime's coverage suite, then checks the floors in floors.json.
# Usage: tools/coverage/run.sh [jvm] [rust] [body] [research]   (default: all four)
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
runtimes=("$@")
[ ${#runtimes[@]} -eq 0 ] && runtimes=(jvm rust body research)

# Gradle 8.14 refuses a JDK newer than 21; prefer an installed 21 on macOS.
if [ -x /usr/libexec/java_home ] && java21="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
  export JAVA_HOME="$java21"
fi

jvm() {
  cd "$root/plugin"
  ./gradlew test jacocoTestReport --no-daemon --console=plain
}

rust() {
  cd "$root/cognition"
  cargo llvm-cov clean --workspace
  cargo llvm-cov --no-report --workspace --all-features
  local out=target/llvm-cov
  mkdir -p "$out"
  cargo llvm-cov report --lcov --output-path "$out/lcov.info"
  cargo llvm-cov report --cobertura --output-path "$out/cobertura.xml"
  cargo llvm-cov report --json --summary-only --output-path "$out/summary.json"
  cargo llvm-cov report --html --output-dir "$out"
  cargo llvm-cov report --summary-only
}

body() { cd "$root/body" && npm run coverage; }
research() { cd "$root/research" && npm run coverage; }

log_of() {
  case "$1" in
    jvm) echo "$root/plugin/build/coverage.log" ;;
    rust) echo "$root/cognition/target/llvm-cov/coverage.log" ;;
    *) echo "$root/$1/coverage/run.log" ;;
  esac
}

for runtime in "${runtimes[@]}"; do
  case "$runtime" in jvm | rust | body | research) ;; *) echo "unknown runtime: $runtime" >&2; exit 2 ;; esac
  log="$(log_of "$runtime")"
  # Logged to a temp file first: c8 empties its reports directory when it starts.
  tmp="$(mktemp)"
  start=$SECONDS
  if ! ("$runtime") >"$tmp" 2>&1; then
    mkdir -p "$(dirname "$log")" && mv "$tmp" "$log"
    tail -n 40 "$log" >&2
    echo "$runtime coverage suite failed after $((SECONDS - start))s; full log: $log" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$log")" && mv "$tmp" "$log"
  echo "$runtime suite: $((SECONDS - start))s (log: ${log#"$root"/})"
done
echo

node "$root/tools/coverage/check.js" "${runtimes[@]}"
