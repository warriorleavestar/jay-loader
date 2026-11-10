#!/usr/bin/env bash
# 阶段 2 计时。A = 阶段 1 模组组 (FerriteCore+Lithium+测试模组)；B = Fabric API + Mod Menu + Placeholder + Copper Hopper + 测试模组
cd "$(dirname "$0")/.."; P=$(python -I tools/proto_root.py); cd "$P"
run() { python -I tools/run_mc.py "$@" --wait-after 1 --timeout 600 | grep RESULT; }
A=$P/mc/mods-ferrite-lithium; B=$P/mc/mods-fapi
for i in 1 2; do run p2-vanilla-$i vanilla; done
for set in A B; do
  M=${!set}; C=$P/mc/bench2-$set
  rm -rf "$C"; run p2-$set-cold amber --mods $M --cache $C --marker TitleScreen.init            # 全冷：游戏重映射+模组烘焙+类缓存冷
  rm -rf "$C/classcache"; run p2-$set-baked amber --mods $M --cache $C --marker TitleScreen.init # 烘焙已缓存，类缓存冷
  for i in 1 2 3; do run p2-$set-warm-$i amber --mods $M --cache $C --marker TitleScreen.init; done
done
echo BENCH2 DONE
