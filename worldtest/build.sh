#!/usr/bin/env bash
# 世界测试模组：用 Mojmap 写、对 named 游戏 + 已烘焙的 Fabric API 编译，再用我们自己的 tiny-remapper 包装转成 intermediary（和真实 Fabric 模组一样发布形态）
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R"; export MSYS_NO_PATHCONV=1
NAMED=$(ls -t mc/ulcache-*/baked/game-named/*.jar | head -1)
LIBS=$(python -I -c "
import json,os;v=json.load(open('mc/versions/1.21.1/1.21.1.json'))
print(';'.join('mc/libraries/'+l['downloads']['artifact']['path'] for l in v['libraries'] if 'artifact' in l['downloads'] and os.path.exists('mc/libraries/'+l['downloads']['artifact']['path'])))")
# baked mod jars are named by key only: the ones carrying Fabric API classes, from the vanilla (Fabric) census bake
FAPI=$(for j in $(ls mc/ulcache-census-vanilla/baked/mods/*.jar 2>/dev/null | grep -v '\.pre\.jar'); do unzip -l "$j" 2>/dev/null | grep -q ' net/fabricmc/fabric/' && printf '%s;' "$j"; done; true)
rm -rf worldtest/out && mkdir -p worldtest/out
javac --release 21 -nowarn -encoding UTF-8 -d worldtest/out -cp "$NAMED;$LIBS;$FAPI;build/loader" $(find worldtest/src -name '*.java')
cp -r worldtest/res/* worldtest/out/ && (cd worldtest/out && jar cf ../worldtest-named.jar .)
LCP="build/loader;$(cat build/loader-cp.txt)"
"$R/tools/jre21/bin/java.exe" -cp "$LCP" amber.ul.tools.RemapJar worldtest/worldtest-named.jar worldtest/amber-worldtest-0.1.0.jar mc/mappings/merged.tiny named intermediary $NAMED $(echo "$LIBS" | tr ';' ' ')
