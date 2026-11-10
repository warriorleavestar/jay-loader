#!/usr/bin/env bash
# 阶段 3 计时：NeoForge 官方启动 vs Amber，空模组 / 3 个 NeoForge 内容模组；混合包。标记：Sound engine started（两边都有）
cd "$(dirname "$0")/.."; P=$(python -I tools/proto_root.py); cd "$P"
run() { python -I tools/run_mc.py "$@" --wait-after 1 --timeout 600 | grep RESULT; }
mkdir -p mc/game/mods; rm -f mc/game/mods/*.jar
for i in 1 2; do run p3-nfoff-empty-$i neoforge-official; done
for i in 1 2; do run p3-amber-empty-$i amber --neoforge --mods $P/mc/mods-nf-empty --cache $P/mc/ulcache-nf; done
cp mc/nf-mod-downloads/torchmaster-*.jar mc/nf-mod-downloads/ironfurnaces-*.jar nfworldtest/amber-nfworldtest-0.1.0.jar mc/game/mods/
for i in 1 2; do run p3-nfoff-3mods-$i neoforge-official; done
rm -f mc/game/mods/*.jar
for i in 1 2; do run p3-amber-3mods-$i amber --neoforge --mods $P/mc/mods-nf --cache $P/mc/ulcache-nf; done
for i in 1 2; do run p3-amber-mixed-$i amber --neoforge --mods $P/mc/mods-mixed2 --cache $P/mc/ulcache-mixed2; done
run p3-fabric-regress amber --mods $P/mc/mods-world --cache $P/mc/ulcache-world
echo BENCH3 DONE
