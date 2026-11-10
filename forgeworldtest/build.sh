#!/usr/bin/env bash
# M4 Forge 1.20.1 world test mod: Mojmap (the runtime names), compiled against the baked Forge platform game jar + baked Forge universal
set -e; cd "$(dirname "$0")/.."; export MSYS_NO_PATHCONV=1
GAME=$(ls -t mc/ulcache-census-forge/baked/game-forge/*.jar | head -1)
UNI=$(ls -t mc/ulcache-census-forge/baked/forge-mods/*/*forge-1.20.1-*-universal.jar | head -1)
FL=$(find mc/forge/libraries -name '*.jar' | grep -v "net/minecraftforge/forge/\|net/minecraft/client" | tr '\n' ';')
LIBS=$(find mc/libraries-1.20.1 -name '*.jar' | tr '\n' ';')
rm -rf forgeworldtest/out && mkdir -p forgeworldtest/out
javac --release 17 -nowarn -encoding UTF-8 -d forgeworldtest/out -cp "$GAME;$UNI;$FL;$LIBS" $(find forgeworldtest/src -name '*.java')
cp -r forgeworldtest/res/* forgeworldtest/out/ && (cd forgeworldtest/out && jar cf ../amber-forgeworldtest-0.1.0.jar .)
echo built forgeworldtest
