#!/usr/bin/env bash
# Builds the loader (Windows, Git Bash; needs JDK 21 and Python 3 on PATH). The bridges are built only when a local test
# setup in mc/ has the platform jars they compile against (Mojang/NeoForge/Forge files are never in the repository).
# tools/proto_root.py: if the JVM cannot represent the checkout path (non-ASCII), it uses an ASCII directory junction.
set -e; cd "$(dirname "$0")"; ROOT=$(python -I tools/proto_root.py); cd "$ROOT"; export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"; export JAVA_TOOL_OPTIONS="-Duser.language=en"
# local platform setup (Mojang/NeoForge/Forge files for the bridges and local tests): <repo>/mc, or $AMBER_UL_MC
MC="${AMBER_UL_MC:-mc}"
# loader libraries: libs.lock.json (official Maven, sha256 pinned) -> deps/, in class-path order
python -I tools/fetch_libs.py
LIBS="$(python -I -c "import json; print(';'.join(f['path'] for f in json.load(open('libs.lock.json'))['files']))")"
rm -rf build/loader; mkdir -p build
find core/src shims/src -name '*.java' > build/sources.txt
javac --release 21 -nowarn -encoding UTF-8 -d build/loader -cp "$LIBS" @build/sources.txt
cp -r core/res/* build/loader/
echo "$LIBS" > build/loader-cp.txt
python -I tools/loader_libs.py build/loader-cp.txt build/loader/amber-ul/loader-libs.json   # requirements + packaged manifest come from the real class path (interface v1.3)
# hardening tests (security scan H13-H16): plain main, no framework; run with tools/jre21 java -cp "build/loader;build/test;$LIBS" amber.ul.HardeningTest
rm -rf build/test && javac --release 21 -nowarn -encoding UTF-8 -d build/test -cp "build/loader;$LIBS" $(find core/test -name '*.java')
# FML bridge: compiled against the real NeoForge/FML jars, runs inside the universal loader (only when the NeoForge platform is active)
if [ -d $MC/neoforge/libraries ]; then
  NFLIBS="$(find $MC/neoforge/libraries -name '*.jar' | grep -v -- '-sources' | tr '\n' ';')$(find $MC/libraries -name 'slf4j-api*.jar' -o -name 'gson-*.jar' -o -name 'guava-*.jar' | tr '\n' ';')"
  rm -rf build/fmlbridge && mkdir -p build/fmlbridge
  javac --release 21 -nowarn -encoding UTF-8 -d build/fmlbridge -cp "build/loader;$NFLIBS" $(find fmlbridge/src -name '*.java')
  (cd build/fmlbridge && rm -f ../fmlbridge.jar && jar --create --date=2026-01-01T00:00:00Z --file ../fmlbridge.jar .)
fi
# M4: Forge FML 47 bridge (Forge 1.20.1 platform), compiled against Forge's FML/modlauncher jars
if [ -d $MC/forge/libraries ]; then
  FGLIBS="$(find $MC/forge/libraries -name '*.jar' | grep -v -- '/net/minecraft/client/' | tr '
' ';')"
  rm -rf build/fmlbridge-forge && mkdir -p build/fmlbridge-forge
  javac --release 21 -nowarn -encoding UTF-8 -d build/fmlbridge-forge -cp "build/loader;$FGLIBS$(find $MC/libraries-1.20.1 -name 'slf4j-api*.jar' -o -name 'guava-*.jar' | tr '
' ';')" $(find fmlbridge-forge/src -name '*.java')
  (cd build/fmlbridge-forge && rm -f ../fmlbridge-forge.jar && jar --create --date=2026-01-01T00:00:00Z --file ../fmlbridge-forge.jar .)
fi
# Fabric bridge mod (phase 5): a Fabric-format mod in named (Mojmap) names, compiled against the NeoForge-patched game; added
# by the loader on the NeoForge platform when Fabric mods are present
NFGAME=$(ls $MC/ulcache-*/baked/game-neoforge/neoforge-*.jar 2>/dev/null | grep -v -- '-cm-' | head -1)
if [ -n "$NFGAME" ] && [ -d $MC/neoforge/libraries ]; then
  NFU=$(find $MC/neoforge/libraries -name 'neoforge-*-universal.jar' | head -1)
  rm -rf build/fabricbridge && mkdir -p build/fabricbridge
  # Fabric API (baked, Mojmap) is on the compile path: adapters under amber.ul.fabricbridge.fapi only load when the module is present
  # baked mod jars are named by key only (Baker.bakeMods): pick the ones that carry Fabric API classes (net/fabricmc/fabric/);
  # not from the Forge 1.20.1 caches (Fabric API 0.92 for Fabric-on-Forge lives there)
  FAPI=$(for j in $(ls $MC/ulcache-*/baked/mods/*.jar 2>/dev/null | grep -v '\.pre\.jar' | grep -v -E 'ulcache-(census-)?forge'); do unzip -l "$j" 2>/dev/null | grep -q ' net/fabricmc/fabric/' && printf '%s;' "$j"; done; true)
  echo "-cp \"build/loader;$LIBS;$NFGAME;$NFU;$NFLIBS;$FAPI$(find $MC/libraries -name '*.jar' | tr '\n' ';')\"" > build/bridge-cp.txt
  IMPLSP=$(ls -d fabricbridge/impl/*/ | tr '\n' ';')   # bridge code sees Amber's impl replacements (sources win), without emitting them
  javac --release 21 -nowarn -encoding UTF-8 -d build/fabricbridge @build/bridge-cp.txt -sourcepath "$IMPLSP" -Xprefer:source -implicit:none $(find fabricbridge/src -name '*.java')
  cp -r fabricbridge/res/* build/fabricbridge/
  # re-based Fabric API impl classes (same FQNs as the official impl.*), compiled against the baked (Mojmap) Fabric API jars;
  # packed under amber-impl/<module id>/ and served by the loader in place of the official ones when that module is present
  for d in fabricbridge/impl/*/; do
    mid=$(basename "$d"); mkdir -p "build/fabricbridge/amber-impl/$mid"
    echo "-cp \"build/fabricbridge;build/loader;$LIBS;$NFGAME;$NFU;$NFLIBS;$FAPI$(find $MC/libraries -name '*.jar' | tr '\n' ';')\"" > build/impl-cp.txt
    javac --release 21 -nowarn -encoding UTF-8 -d "build/fabricbridge/amber-impl/$mid" @build/impl-cp.txt $(find "$d" -name '*.java')
  done
  (cd build/fabricbridge && rm -f ../fabricbridge.jar && jar --create --date=2026-01-01T00:00:00Z --file ../fabricbridge.jar .)   # reproducible: the jar sha is part of the isolation key
fi
# Forge 1.21.1 API layer (Forge 52 mods on the NeoForge 21.1 platform): a NeoForge mod whose amber.forgecompat classes
# mirror net.minecraftforge; ForgeCompat's bake renames Forge references to them
if [ -n "$NFGAME" ] && [ -d $MC/neoforge/libraries ]; then
  rm -rf build/forgecompat && mkdir -p build/forgecompat
  javac --release 21 -nowarn -encoding UTF-8 -d build/forgecompat -cp "$NFGAME;$NFU;$NFLIBS$(find $MC/libraries -name '*.jar' | tr '
' ';')" $(find forgecompat/src -name '*.java')
  cp -r forgecompat/res/* build/forgecompat/
  (cd build/forgecompat && rm -f ../forgecompat.jar && jar --create --date=2026-01-01T00:00:00Z --file ../forgecompat.jar .)
fi
# Fabric bridge for Forge 47 (Fabric mods on the Forge 1.20.1 platform): Mojmap, compiled against the baked Forge game + universal
FGGAME=$(ls -t $MC/ulcache-census-forge/baked/game-forge/*.jar 2>/dev/null | grep -v -- '-cm-\|-fcm-' | head -1)
FGUNI=$(ls -t $MC/ulcache-census-forge/baked/forge-mods/*/*forge-1.20.1-*-universal.jar 2>/dev/null | head -1)
if [ -n "$FGGAME" ] && [ -n "$FGUNI" ]; then
  # Fabric API 0.92.12 (baked, Mojmap) from the Forge census bake: adapters under amber.ul.fabricbridge.forge.mixin.fapi only apply when the module is present
  FGFAPI=$(for j in $(ls $MC/ulcache-census-forge/baked/mods/*.jar 2>/dev/null | grep -v '\.pre\.jar'); do unzip -l "$j" 2>/dev/null | grep -q ' net/fabricmc/fabric/' && printf '%s;' "$j"; done; true)
  rm -rf build/fabricbridge-forge && mkdir -p build/fabricbridge-forge
  FGCP="build/loader;$LIBS;$FGGAME;$FGUNI;$FGFAPI$FGLIBS$(find $MC/libraries-1.20.1 -name '*.jar' | tr '
' ';')"
  FGIMPLSP=$(ls -d fabricbridge-forge/impl/*/ 2>/dev/null | tr '
' ';')   # bridge code sees Amber's impl replacements, without emitting them
  javac --release 17 -nowarn -encoding UTF-8 -d build/fabricbridge-forge -cp "$FGCP" -sourcepath "$FGIMPLSP" -Xprefer:source -implicit:none $(find fabricbridge-forge/src -name '*.java')
  cp -r fabricbridge-forge/res/* build/fabricbridge-forge/
  # re-based Fabric API impl classes for Forge, packed under amber-impl/<module id>/ (served only when that module is present)
  for d in fabricbridge-forge/impl/*/; do
    [ -d "$d" ] || continue; mid=$(basename "$d"); mkdir -p "build/fabricbridge-forge/amber-impl/$mid"
    javac --release 17 -nowarn -encoding UTF-8 -d "build/fabricbridge-forge/amber-impl/$mid" -cp "build/fabricbridge-forge;$FGCP" $(find "$d" -name '*.java')
  done
  (cd build/fabricbridge-forge && rm -f ../fabricbridge-forge.jar && jar --create --date=2026-01-01T00:00:00Z --file ../fabricbridge-forge.jar .)
fi
# local test setup only (needs Mojang's mappings in mc/, never in the repository)
if [ -f $MC/mappings/client_mappings.txt ] && [ ! -f $MC/mappings/merged.tiny ]; then
  "${JAVA:-java}" -cp "build/loader;$LIBS" amber.ul.tools.BuildMappings deps/intermediary-1.21.1-v2.jar $MC/mappings/client_mappings.txt $MC/mappings/merged.tiny
fi
echo built
