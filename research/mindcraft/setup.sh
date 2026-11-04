#!/usr/bin/env bash
# Install Mindcraft, as is, at a pinned commit into research/mindcraft/.cache.
#
# Mindcraft ships no lockfile, so the install pins what it must: Node 20 (its
# native `gl` dependency does not compile on Node 24+), and mineflayer 4.33.0,
# the version Mindcraft's own patches/ were made against. A fresh install
# resolves mineflayer 4.39.0, on which Mindcraft's mineflayer patch fails, and
# on Paper 1.21.11 that combination left a body the server would not let move
# (smoke run 20261104T211535Z), so the launcher runs Mindcraft's recommended
# Minecraft 1.21.6 with every one of its patches applied. The resolved tree is
# written to .cache/deps.txt so every run can record exactly what it ran.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cache="$here/.cache"
commit=e1c1fc3ffa481506c2445fc639e16dba504b5920
node_line=20
registry=https://registry.npmjs.org/
mkdir -p "$cache"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) platform=darwin-arm64 ;;
  Darwin-x86_64) platform=darwin-x64 ;;
  Linux-x86_64) platform=linux-x64 ;;
  Linux-aarch64) platform=linux-arm64 ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

# Node 20, verified against nodejs.org's own SHASUMS256.txt.
if [ ! -x "$cache/node/bin/node" ]; then
  dl="$cache/node-download"
  rm -rf "$dl" && mkdir -p "$dl"
  curl -fsSL "https://nodejs.org/dist/latest-v$node_line.x/SHASUMS256.txt" -o "$dl/SHASUMS256.txt"
  tarball="$(grep -o "node-v$node_line[.0-9]*-$platform.tar.gz" "$dl/SHASUMS256.txt" | head -1)"
  curl -fsSL "https://nodejs.org/dist/latest-v$node_line.x/$tarball" -o "$dl/$tarball"
  (cd "$dl" && grep " $tarball\$" SHASUMS256.txt | shasum -a 256 -c -)
  tar xzf "$dl/$tarball" -C "$dl"
  rm -rf "$cache/node" && mv "$dl/${tarball%.tar.gz}" "$cache/node"
  rm -rf "$dl"
fi
export PATH="$cache/node/bin:$PATH"
echo "node $(node --version)"

# Mindcraft at the pinned commit.
if [ ! -d "$cache/mindcraft/.git" ]; then
  git init -q "$cache/mindcraft"
  git -C "$cache/mindcraft" remote add origin https://github.com/mindcraft-bots/mindcraft.git
fi
git -C "$cache/mindcraft" fetch -q --depth 1 origin "$commit"
git -C "$cache/mindcraft" checkout -q -f --detach "$commit"
echo "mindcraft $(git -C "$cache/mindcraft" rev-parse HEAD)"

# Loom's own fixes to Mindcraft, on top of the pinned commit (patches/).
for patch in "$here"/patches/*.patch; do
  git -C "$cache/mindcraft" apply "$patch"
  echo "applied $(basename "$patch")"
done

cd "$cache/mindcraft"
npm install --registry="$registry" > "$cache/npm-install.log" 2>&1 || {
  tail -20 "$cache/npm-install.log" >&2
  echo "npm install failed; full log in $cache/npm-install.log" >&2
  exit 1
}
npm install --no-save --ignore-scripts --registry="$registry" mineflayer@4.33.0 >> "$cache/npm-install.log" 2>&1
patches="$(npx --no-install patch-package 2>&1)"
echo "$patches" | grep -E '✔|ERROR'
if echo "$patches" | grep -q ERROR || [ "$(echo "$patches" | grep -c '✔')" -ne "$(ls patches | wc -l | tr -d ' ')" ]; then
  echo "not every Mindcraft patch applied" >&2
  exit 1
fi
npm ls --all > "$cache/deps.txt" 2>/dev/null || true
echo "mineflayer $(node -p 'require("./node_modules/mineflayer/package.json").version')"
echo "setup done; dependency tree in $cache/deps.txt"
