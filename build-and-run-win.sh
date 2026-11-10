#!/usr/bin/env bash
# Windows (Git Bash) 版：javac 用 PATH 上的 JDK（--release 21），运行用 Java 21（JAVA21 环境变量或 Mojang java-runtime-delta）
set -e; cd /u; ROOT="U:"   # subst U: = 本目录（系统代码页 1252，Java 无法处理中文路径）
JAVA21="${JAVA21:-tools/jre21/bin/java.exe}"
LIBS=$(ls libs/*.jar | sed "s|^|$ROOT/|" | tr '\n' ';'); L="$ROOT/build/loader"
rm -rf build/loader && javac --release 21 -nowarn -encoding UTF-8 -d "$L" -cp "$LIBS" $(find core/src shims/src -name '*.java')
cd demo
python - <<'PY'
import os
os.makedirs('game/src/net/minecraft/gen', exist_ok=True)
for i in range(3000):
    b = "\n".join(f"    public int m{j}(int a){{ int r=a; for(int k=0;k<{j+2};k++){{ r = r*31 + k ^ (r>>>3); if (r%7==0) r+= {i}; }} return r; }}" for j in range(12))
    open(f"game/src/net/minecraft/gen/C{i}.java", "w").write(f"package net.minecraft.gen;\npublic class C{i} {{\n{b}\n}}\n")
PY
rm -rf out run && mkdir -p out/game out/fab out/neo run/mods
find game/src -name "*.java" > out/game-src.txt; javac --release 21 -nowarn -encoding UTF-8 -d out/game @out/game-src.txt && (cd out/game && jar cf ../../run/game.jar .)
javac --release 21 -encoding UTF-8 -d out/fab -cp "$L;stub-int" $(find fabricmod/src -name '*.java') && cp fabricmod/res/* out/fab/ && (cd out/fab && jar cf ../../run/mods/fabricdemo-1.2.0.jar .)
javac --release 21 -encoding UTF-8 -d out/neo -cp "$L;stub-named;out/fab" $(find neomod/src -name '*.java') && rm -rf out/neo/com/example/fabricdemo && cp -r neomod/res/* out/neo/ && (cd out/neo && jar cf ../../run/mods/forgedemo-0.3.0.jar .)
for i in 1 2; do echo "== 第 $i 次启动"; "$JAVA21" -Dstdout.encoding=UTF-8 -Dgame.classes=3000 -cp "$L;$LIBS" amber.ul.Launcher --game run/game.jar --mods run/mods --mappings mappings.tiny --cache run/cache --main net.minecraft.client.main.Main; done
