#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cache="$here/.cache"
mindcraft_commit=e1c1fc3ffa481506c2445fc639e16dba504b5920
node_major_that_builds_mindcraft_gl=20
mineflayer_version_of_mindcraft_patches=4.33.0
registry=https://registry.npmjs.org/
mkdir -p "$cache"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) platform=darwin-arm64 ;;
  Darwin-x86_64) platform=darwin-x64 ;;
  Linux-x86_64) platform=linux-x64 ;;
  Linux-aarch64) platform=linux-arm64 ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

install_node_verified_against_nodejs_shasums() {
  local dl="$cache/node-download" tarball
  rm -rf "$dl" && mkdir -p "$dl"
  curl -fsSL "https://nodejs.org/dist/latest-v$node_major_that_builds_mindcraft_gl.x/SHASUMS256.txt" -o "$dl/SHASUMS256.txt"
  tarball="$(grep -o "node-v$node_major_that_builds_mindcraft_gl[.0-9]*-$platform.tar.gz" "$dl/SHASUMS256.txt" | head -1)"
  curl -fsSL "https://nodejs.org/dist/latest-v$node_major_that_builds_mindcraft_gl.x/$tarball" -o "$dl/$tarball"
  (cd "$dl" && grep " $tarball\$" SHASUMS256.txt | shasum -a 256 -c -)
  tar xzf "$dl/$tarball" -C "$dl"
  rm -rf "$cache/node" && mv "$dl/${tarball%.tar.gz}" "$cache/node"
  rm -rf "$dl"
}
if [ ! -x "$cache/node/bin/node" ]; then install_node_verified_against_nodejs_shasums; fi
export PATH="$cache/node/bin:$PATH"
echo "node $(node --version)"

if [ ! -d "$cache/mindcraft/.git" ]; then
  git init -q "$cache/mindcraft"
  git -C "$cache/mindcraft" remote add origin https://github.com/mindcraft-bots/mindcraft.git
fi
git -C "$cache/mindcraft" fetch -q --depth 1 origin "$mindcraft_commit"
git -C "$cache/mindcraft" checkout -q -f --detach "$mindcraft_commit"
echo "mindcraft $(git -C "$cache/mindcraft" rev-parse HEAD)"

cd "$cache/mindcraft"
npm install --registry="$registry" > "$cache/npm-install.log" 2>&1 || {
  tail -20 "$cache/npm-install.log" >&2
  echo "npm install failed; full log in $cache/npm-install.log" >&2
  exit 1
}
npm install --no-save --ignore-scripts --registry="$registry" "mineflayer@$mineflayer_version_of_mindcraft_patches" >> "$cache/npm-install.log" 2>&1
patches="$(npx --no-install patch-package 2>&1)"
echo "$patches" | grep -E '✔|ERROR'
if echo "$patches" | grep -q ERROR || [ "$(echo "$patches" | grep -c '✔')" -ne "$(ls patches | wc -l | tr -d ' ')" ]; then
  echo "not every Mindcraft patch applied" >&2
  exit 1
fi
apply_loom_patch_unless_it_survived_a_rerun_in_node_modules() {
  if git -C "$cache/mindcraft" apply --reverse --check "$1" 2> /dev/null; then
    echo "already applied $(basename "$1")"
  else
    git -C "$cache/mindcraft" apply "$1"
    echo "applied $(basename "$1")"
  fi
}
for loom_patch in "$here"/patches/*.patch; do
  apply_loom_patch_unless_it_survived_a_rerun_in_node_modules "$loom_patch"
done
npm ls --all > "$cache/deps.txt" 2>/dev/null || true
echo "mineflayer $(node -p 'require("./node_modules/mineflayer/package.json").version')"
echo "setup done; dependency tree in $cache/deps.txt"
