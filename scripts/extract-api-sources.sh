#!/usr/bin/env bash
# Downloads and extracts the sources of Create and its companion libraries, the JEI API
# (compile-only, optional integration) plus the decompiled NeoForge/Minecraft sources into
# build/api-src/ for API lookups.
# build/ is gitignored, so ripgrep-based searches of the project skip these files.
# Run `./gradlew compileJava` once before, so build/moddev/artifacts exists.
set -euo pipefail
cd "$(dirname "$0")/.."

prop() { grep -E "^$1=" gradle.properties | cut -d= -f2-; }
MC=$(prop minecraft_version)
CREATE=$(prop create_version)
PONDER=$(prop ponder_version)
FLYWHEEL=$(prop flywheel_version)
VANILLIN=$(prop vanillin_version)
REGISTRATE=$(prop registrate_version)
NEO=$(prop neo_version)
JEI=$(prop jei_version)

OUT=build/api-src
mkdir -p "$OUT/jars"

fetch() { # name url [directory below build/api-src, default: name]
  local jar="$OUT/jars/$1-sources.jar" dir="$OUT/${3:-$1}"
  [ -s "$jar" ] || curl -fsSL -o "$jar" "$2"
  rm -rf "${dir:?}" && mkdir -p "$dir" && unzip -q -o "$jar" -d "$dir"
  echo "extracted $1"
}

CM=https://maven.createmod.net
fetch create     "$CM/com/simibubi/create/create-$MC/$CREATE/create-$MC-$CREATE-sources.jar"
fetch ponder     "$CM/net/createmod/ponder/ponder-neoforge/$PONDER+mc$MC/ponder-neoforge-$PONDER+mc$MC-sources.jar"
fetch flywheel   "$CM/dev/engine-room/flywheel/flywheel-neoforge-$MC/$FLYWHEEL/flywheel-neoforge-$MC-$FLYWHEEL-sources.jar"
fetch vanillin   "$CM/dev/engine-room/vanillin/vanillin-neoforge-$MC/$VANILLIN/vanillin-neoforge-$MC-$VANILLIN-sources.jar"
fetch registrate "https://maven.ithundxr.dev/snapshots/com/tterrag/registrate/Registrate/$REGISTRATE/Registrate-$REGISTRATE-sources.jar"

# JEI API only (mezz.jei.api.*): the common API and the NeoForge API, both into build/api-src/jei.
# The jars are cached without a version, so delete build/api-src/jars/jei-* after changing jei_version.
JM=https://maven.blamejared.com/mezz/jei
fetch jei-common-api   "$JM/jei-$MC-common-api/$JEI/jei-$MC-common-api-$JEI-sources.jar"     jei/common-api
fetch jei-neoforge-api "$JM/jei-$MC-neoforge-api/$JEI/jei-$MC-neoforge-api-$JEI-sources.jar" jei/neoforge-api

NEO_SRC="build/moddev/artifacts/neoforge-$NEO-sources.jar"
if [ -f "$NEO_SRC" ]; then
  rm -rf "$OUT/neoforge" && mkdir -p "$OUT/neoforge" && unzip -q -o "$NEO_SRC" -d "$OUT/neoforge"
  echo "extracted neoforge (+ decompiled minecraft)"
else
  echo "missing $NEO_SRC - run ./gradlew compileJava first" >&2
fi
