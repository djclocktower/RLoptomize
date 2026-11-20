#!/bin/bash
# Builds rlperf-<version>.jar. Needs a JDK 9+ (javac --release 8) and two inputs:
#   LIBS    a Minecraft "libraries" folder with Forge 1.12.2 installed (launchwrapper, asm-debug-all, log4j, forge)
#   BC_JAR  bettercaves-1.12.2-2.0.4.jar
#   LY_JAR  lycanitesmobs-1.12.2-2.0.8.9.jar
# usage: LIBS=~/.minecraft/libraries BC_JAR=mods/bettercaves-1.12.2-2.0.4.jar LY_JAR=mods/lycanitesmobs-1.12.2-2.0.8.9.jar ./build.sh [outdir]
set -euo pipefail
cd "$(dirname "$0")"
VERSION=1.1.0
OUT=${1:-build}
CP=$(find "$LIBS" -name 'launchwrapper-1.12.jar' -o -name 'asm-debug-all-5.2.jar' -o -name 'log4j-api-2.*.jar' -o -name 'forge-1.12.2-14.23.5.*.jar' | tr '\n' ':')
rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/donor" "$OUT/jar"
JAVAC="javac --release 8 -nowarn -Xlint:-options"
$JAVAC -d "$OUT/stubs" $(find stubs -name '*.java')       # stand-ins for Minecraft classes: compile against, never shipped
$JAVAC -cp "$CP" -d "$OUT/jar" $(find src -name '*.java')
$JAVAC -cp "$OUT/stubs:$BC_JAR:$LY_JAR" -d "$OUT/donor" $(find donor -name '*.java')
# Donor classes ship as data (.class.bin) so nothing loads them by accident.
(cd "$OUT/donor" && find . -name '*.class' | while read -r f; do mkdir -p "../jar/rlperf/donor/$(dirname "$f")"; cp "$f" "../jar/rlperf/donor/$f.bin"; done)
printf 'Manifest-Version: 1.0\nFMLCorePlugin: rlperf.core.RLPerfPlugin\n' > "$OUT/MANIFEST.MF"
cp LICENSE "$OUT/jar/LICENSE"
jar cfm "$OUT/rlperf-$VERSION.jar" "$OUT/MANIFEST.MF" -C "$OUT/jar" .
echo "built $OUT/rlperf-$VERSION.jar"
