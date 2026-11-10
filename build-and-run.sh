#!/usr/bin/env bash
# 原型构建+运行：需要 JDK 21；libs/ 里放 asm 9.7.1 / asm-tree / asm-commons、gson 2.11.0、tomlj 1.1.1、antlr4-runtime 4.11.1
set -e; ROOT="$(cd "$(dirname "$0")" && pwd)"; LIBS=$(ls "$ROOT"/libs/*.jar | tr '\n' ':'); L="$ROOT/build/loader"
javac -nowarn -d "$L" -cp "$LIBS" $(find "$ROOT/core/src" "$ROOT/shims/src" -name '*.java')
cd "$ROOT/demo"
python3 - <<'PY'
import os
os.makedirs('game/src/net/minecraft/gen', exist_ok=True)
for i in range(3000):
    b = "\n".join(f"    public int m{j}(int a){{ int r=a; for(int k=0;k<{j+2};k++){{ r = r*31 + k ^ (r>>>3); if (r%7==0) r+= {i}; }} return r; }}" for j in range(12))
    open(f"game/src/net/minecraft/gen/C{i}.java", "w").write(f"package net.minecraft.gen;\npublic class C{i} {{\n{b}\n}}\n")
PY
rm -rf out run && mkdir -p out/game out/fab out/neo run/mods
javac -nowarn -d out/game $(find game/src -name '*.java') && (cd out/game && jar cf ../../run/game.jar .)
javac -d out/fab -cp "$L:stub-int" $(find fabricmod/src -name '*.java') && cp fabricmod/res/* out/fab/ && (cd out/fab && jar cf ../../run/mods/fabricdemo-1.2.0.jar .)
javac -d out/neo -cp "$L:stub-named:out/fab" $(find neomod/src -name '*.java') && rm -rf out/neo/com/example/fabricdemo && cp -r neomod/res/* out/neo/ && (cd out/neo && jar cf ../../run/mods/forgedemo-0.3.0.jar .)
for i in 1 2; do echo "== 第 $i 次启动"; java -Dstdout.encoding=UTF-8 -Dgame.classes=3000 -cp "$L:$LIBS" amber.ul.Launcher --game run/game.jar --mods run/mods --mappings mappings.tiny --cache run/cache --main net.minecraft.client.main.Main; done
