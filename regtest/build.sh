#!/usr/bin/env bash
# 注册表同步测试模组：用 Mojmap 写、对 named 游戏 + 已烘焙的 Fabric API 编译，再用我们自己的 tiny-remapper 包装转成 intermediary（和真实 Fabric 模组一样发布形态）
set -e; cd "$(dirname "$0")/.."; R=$(python -I tools/proto_root.py); cd "$R"; export MSYS_NO_PATHCONV=1
NAMED=$(ls mc/ulcache-fapi/baked/game-named/*.jar | head -1)
LIBS=$(python -I -c "
import json,os;v=json.load(open('mc/versions/1.21.1/1.21.1.json'))
print(';'.join('mc/libraries/'+l['downloads']['artifact']['path'] for l in v['libraries'] if 'artifact' in l['downloads'] and os.path.exists('mc/libraries/'+l['downloads']['artifact']['path'])))")
FAPI=$(ls mc/ulcache-5a/baked/mods/fabric-*.jar | grep -v '[.]pre[.]jar' | tr '\n' ';')
rm -rf regtest/out && mkdir -p regtest/out
javac --release 21 -nowarn -encoding UTF-8 -d regtest/out -cp "$NAMED;$LIBS;$FAPI;build/loader" $(find regtest/src -name '*.java')
cp regtest/res/* regtest/out/ && (cd regtest/out && jar cf ../regtest-named.jar .)
LCP="build/loader;$(cat build/loader-cp.txt)"
"$R/tools/jre21/bin/java.exe" -cp "$LCP" amber.ul.tools.RemapJar regtest/regtest-named.jar regtest/amber-regtest-0.1.0.jar mc/mappings/merged.tiny named intermediary $NAMED $(echo "$LIBS" | tr ';' ' ')
