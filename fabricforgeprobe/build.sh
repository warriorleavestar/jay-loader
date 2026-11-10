#!/usr/bin/env bash
# Fabric-on-Forge probe mod (Fabric API 0.92): Mojmap sources compiled against the baked Forge 1.20.1 game + universal and the
# baked (Mojmap) Fabric API jars of the Forge census bake, then remapped named -> intermediary like a real Fabric 1.20.1 mod.
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R"; export MSYS_NO_PATHCONV=1
GAME=$(ls -t mc/ulcache-census-forge/baked/game-forge/*.jar | grep -v -- '-cm-\|-fcm-' | head -1)
UNI=$(ls -t mc/ulcache-census-forge/baked/forge-mods/*/*forge-1.20.1-*-universal.jar | head -1)
LIBS=$(find mc/libraries-1.20.1 -name '*.jar' | tr '\n' ';')
FAPI=$(for j in $(ls mc/ulcache-census-forge/baked/mods/*.jar | grep -v '\.pre\.jar'); do unzip -l "$j" 2>/dev/null | grep -q ' net/fabricmc/fabric/' && printf '%s;' "$j"; done; true)
rm -rf fabricforgeprobe/out && mkdir -p fabricforgeprobe/out
javac --release 17 -nowarn -encoding UTF-8 -d fabricforgeprobe/out -cp "$GAME;$UNI;$FAPI;build/loader;$LIBS" $(find fabricforgeprobe/src -name '*.java')
cp -r fabricforgeprobe/res/* fabricforgeprobe/out/
(cd fabricforgeprobe/out && rm -f ../named.jar && jar --create --date=2026-01-01T00:00:00Z --file ../named.jar .)
tools/jre21/bin/java.exe -cp "build/loader;$(cat build/loader-cp.txt)" amber.ul.tools.RemapJar fabricforgeprobe/named.jar fabricforgeprobe/amber-fabricforgeprobe-0.1.0.jar mc/mappings-1.20.1/merged.tiny named intermediary "$GAME" "$UNI" $(echo "$FAPI" | tr ';' ' ') $(echo "$LIBS" | tr ';' ' ')
echo built fabricforgeprobe
