#!/bin/bash
# Runs the equivalence test against a finished build. Same inputs as build.sh.
# usage: LIBS=... BC_JAR=... LY_JAR=... ./test.sh [build-dir]      (TIMING=1 to time the pipeline without the noise hashing)
set -euo pipefail
cd "$(dirname "$0")"
OUT=${1:-build}
CP=$(find "$LIBS" -name 'launchwrapper-1.12.jar' -o -name 'asm-debug-all-5.2.jar' -o -name 'log4j-api-2.*.jar' -o -name 'log4j-core-2.*.jar' -o -name 'forge-1.12.2-14.23.5.*.jar' | tr '\n' ':')
EXTRA=$(find "$LIBS" -name 'vecmath-*.jar' -o -name 'gson-2.8.0.jar' | tr '\n' ' ')
mkdir -p "$OUT/test/scenario" "$OUT/test/main"
javac -nowarn -cp "$OUT/stubs:$BC_JAR:$LY_JAR" -d "$OUT/test/scenario" test/src/Scenario.java test/src/SpawnScanScenario.java
javac -nowarn -cp "$OUT/jar:$CP" -d "$OUT/test/main" test/src/rlperf/core/EquivalenceTest.java
java ${TIMING:+-Dscenario.timing=true} -cp "$OUT/test/main:$OUT/jar:$CP" rlperf.core.EquivalenceTest "$OUT/stubs" "$OUT/test/scenario" "$OUT/donor" "$BC_JAR" "$LY_JAR" $EXTRA
