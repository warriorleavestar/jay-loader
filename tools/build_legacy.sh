#!/usr/bin/env bash
# Builds build/core-legacy.jar (+ its tests) and, when Mojang's launchwrapper 1.12 is in the local test setup, the
# forge-1.12.2 bridge build/fmlbridge-1.12.2.jar. Called by build.sh; can be run alone.
set -e; cd "$(dirname "$0")/.."; export MSYS_NO_PATHCONV=1
LWJAR=${AMBER_UL_MC:-mc}/1.12.2/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar
rm -rf build/core-legacy build/test-legacy && mkdir -p build/core-legacy build/test-legacy
javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d build/core-legacy $(find core-legacy/src -name '*.java')
if [ -f "$LWJAR" ]; then javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d build/core-legacy -cp "build/core-legacy;$LWJAR" $(find core-legacy/lw -name '*.java'); fi
(cd build/core-legacy && rm -f ../core-legacy.jar && jar --create --date=2026-01-01T00:00:00Z --file ../core-legacy.jar .)
if [ -f "$LWJAR" ]; then cp build/core-legacy.jar build/fmlbridge-1.12.2.jar; else rm -f build/fmlbridge-1.12.2.jar; fi
javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d build/test-legacy -cp build/core-legacy $(find core-legacy/test -name '*.java')
# test-only world test mod (tools/bake_1122_test.py, tools/run_1122.py); compiled against the local Forge universal jar, never packaged
FGJAR=${AMBER_UL_MC:-mc}/1.12.2/libraries/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar
if [ -f "$FGJAR" ]; then
  rm -rf legacytest/out && mkdir -p legacytest/out
  javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d legacytest/out -cp "$FGJAR" $(find legacytest/src -name '*.java')
  (cd legacytest/out && rm -f ../amber-worldtest-1122.jar && jar --create --date=2026-01-01T00:00:00Z --file ../amber-worldtest-1122.jar .)
fi
echo built core-legacy
