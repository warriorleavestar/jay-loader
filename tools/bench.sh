#!/usr/bin/env bash
# 计时：每种配置多次，结果追加到 mc/runs/results.jsonl
cd "$(dirname "$0")/.."; P=$(python -I tools/proto_root.py); cd "$P"; R="python -I tools/run_mc.py"; M=$P/mc/mods-ferrite-lithium
run() { $R "$@" --wait-after 1 --timeout 300 | grep RESULT; }
for i in 1 2 3; do run bench-vanilla-$i vanilla; done
for i in 1 2 3; do run bench-ul-novmods-$i amber --cache $P/mc/ulcache; done
rm -rf mc/ulcache-bench-cold; run bench-mods-cold amber --mods $M --cache $P/mc/ulcache-bench-cold --marker TitleScreen.init
for i in 1 2 3; do run bench-mods-warm-safe-$i amber --mods $M --cache $P/mc/ulcache-bench-cold --marker TitleScreen.init; done
run bench-mods-offcache amber --mods $M --cache $P/mc/ulcache-bench-cold --classcache off --marker TitleScreen.init
run bench-mods-full-prime amber --mods $M --cache $P/mc/ulcache-bench-cold --classcache full --marker TitleScreen.init
for i in 1 2 3; do run bench-mods-warm-full-$i amber --mods $M --cache $P/mc/ulcache-bench-cold --classcache full --marker TitleScreen.init; done
echo BENCH DONE
