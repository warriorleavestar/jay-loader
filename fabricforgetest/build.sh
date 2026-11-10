#!/usr/bin/env bash
# Fabric-on-Forge window test mod: Mojmap sources compiled against the baked Forge 1.20.1 game, then remapped
# named -> intermediary (RemapJar), so the jar is shaped like a real Fabric 1.20.1 mod. No Fabric API.
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R"; export MSYS_NO_PATHCONV=1
GAME=$(ls -t mc/ulcache-census-forge/baked/game-forge/*.jar | grep -v -- '-cm-\|-fcm-' | head -1)
UNI=$(ls -t mc/ulcache-census-forge/baked/forge-mods/*/*forge-1.20.1-*-universal.jar | head -1)
LIBS=$(find mc/libraries-1.20.1 -name '*.jar' | tr '\n' ';')
rm -rf fabricforgetest/out && mkdir -p fabricforgetest/out
javac --release 17 -nowarn -encoding UTF-8 -d fabricforgetest/out -cp "$GAME;$UNI;build/loader;$LIBS" $(find fabricforgetest/src -name '*.java')
cp -r fabricforgetest/res/* fabricforgetest/out/
(cd fabricforgetest/out && rm -f ../named.jar && jar --create --date=2026-01-01T00:00:00Z --file ../named.jar .)
tools/jre21/bin/java.exe -cp "build/loader;$(cat build/loader-cp.txt)" amber.ul.tools.RemapJar fabricforgetest/named.jar fabricforgetest/amber-fabricforgetest-0.1.0.jar mc/mappings-1.20.1/merged.tiny named intermediary "$GAME" "$UNI" $(echo "$LIBS" | tr ';' ' ')
echo built fabricforgetest
