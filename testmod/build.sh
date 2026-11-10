#!/usr/bin/env bash
# 自制测试模组：针对 intermediary 编译（和真实 Fabric 模组一样），refmap 手写
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R/testmod"; export MSYS_NO_PATHCONV=1
INT=$(ls ../mc/ulcache*/baked/game-intermediary/*.jar | head -1)
rm -rf out && mkdir -p out
javac --release 21 -nowarn -encoding UTF-8 -d out -cp "$INT;../build/loader;../mc/loaderlibs/sponge-mixin-0.15.4+mixin.0.8.7.jar" $(find src -name '*.java')
cp res/* out/ && (cd out && jar cf ../amber-testmod-0.1.0.jar .)
echo built testmod
