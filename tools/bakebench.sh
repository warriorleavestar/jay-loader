#!/usr/bin/env bash
# Cold mod bake timing (93 corpus mods): a fresh cache with the game jars already baked (copied from the census
# cache), so only the mod bake runs. Usage: tools/bakebench.sh <label> [runs] [extra --jvm args...]
set -e; cd "$(dirname "$0")/.."; P=$(python -I tools/proto_root.py); cd "$P"
label=$1; runs=${2:-3}; shift 2 || shift $#
SRC=$P/mc/ulcache-census-vanilla
for i in $(seq 1 $runs); do
  C=$P/mc/ulcache-bakebench; rm -rf "$C"; mkdir -p "$C/baked"
  cp -r "$SRC/baked/game" "$SRC/baked/game-named" "$SRC/baked/game-intermediary" "$C/baked/"; cp -r "$SRC/nested" "$C/"
  python -I tools/run_mc.py bakebench-$label-$i amber --mods $P/mc/corpus --cache "$C" --census $P/mc/census/bakebench-$label-$i.json \
    --marker 普查完成 --wait-after 0 --timeout 600 --jvm=-Damber.ul.mixinErrors=warn --jvm=-Damber.ul.ignoreDeps=true --jvm=-Damber.ul.censusFresh=true "$@" > /dev/null
  echo "$label run $i: $(grep -a '烘焙准备\|重映射+后处理\|模组烘焙 93' mc/runs/bakebench-$label-$i.log | sed 's/^ *[0-9.]* \[amber-ul\] //' | tr '\n' ' ')"
done
