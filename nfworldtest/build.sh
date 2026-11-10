#!/usr/bin/env bash
# NeoForge 世界测试模组：Mojmap，对 NeoForge 平台游戏 jar（已烘焙）+ universal + FML 编译；NeoForge 1.21.1 模组本来就是 Mojmap，不用重映射
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R"; export MSYS_NO_PATHCONV=1
GAME=$(ls -t mc/ulcache-nf/baked/game/game-*.jar | head -1)
NF=$(find mc/neoforge/libraries -name '*.jar' | grep -v sources | tr '\n' ';')
LIBS=$(find mc/libraries -name '*.jar' | tr '\n' ';')
rm -rf nfworldtest/out && mkdir -p nfworldtest/out
javac --release 21 -nowarn -encoding UTF-8 -d nfworldtest/out -cp "$GAME;$NF;$LIBS" $(find nfworldtest/src -name '*.java')
cp -r nfworldtest/res/* nfworldtest/out/ && (cd nfworldtest/out && jar cf ../amber-nfworldtest-0.1.0.jar .)
echo built nfworldtest
