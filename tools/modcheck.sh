#!/usr/bin/env bash
# Golden check of Amber's mod remapper against tiny-remapper (amber.ul.map.ModCheck) on mc/corpus. Output: mc/modcheck/
set -e; cd "$(dirname "$0")/.."; ROOT=$(python -I tools/proto_root.py); cd "$ROOT"; export MSYS_NO_PATHCONV=1
CP="$ROOT/build/loader;$(cat build/loader-cp.txt | tr ';' '\n' | sed "s|^|$ROOT/|" | tr '\n' ';')"
INTER=$(ls mc/ulcache-*/baked/game-intermediary/1.21.1-*.jar | head -1)
"$ROOT/tools/jre21/bin/java.exe" -Xmx6G $JOPTS -cp "$CP" amber.ul.map.ModCheck mc/mappings-cache/1.21.1 mc/versions/1.21.1/1.21.1.jar mc/libraries "$INTER" mc/mappings/merged.tiny mc/corpus mc/modcheck mc/modcheck/report.json > mc/modcheck/run.log 2>&1
python -I -c "import json;r=json.load(open('mc/modcheck/report.json'));print(json.dumps({k:r['amber'][k] for k in ('totalMs','strings') if k in r['amber']}));print(json.dumps(r['text']));c=r['compare'];print({k:c[k] for k in ('classesSame','classesDebugInfoOnly','classesSelectorFormOnly','classesDiffer','classesDifferMixin','intermediaryNamesLeftAmber','intermediaryNamesLeftTiny','resourcesDiffer')})"
