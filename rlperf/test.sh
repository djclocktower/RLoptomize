#!/bin/bash
# Runs the equivalence test against a finished build. Same inputs as build.sh.
# usage: LIBS=... BC_JAR=... ./test.sh [build-dir]      (TIMING=1 to time the pipeline without the noise hashing)
set -euo pipefail
cd "$(dirname "$0")"
OUT=${1:-build}
CP=$(find "$LIBS" -name 'launchwrapper-1.12.jar' -o -name 'asm-debug-all-5.2.jar' -o -name 'log4j-api-2.*.jar' -o -name 'log4j-core-2.*.jar' -o -name 'forge-1.12.2-14.23.5.*.jar' | tr '\n' ':')
VECMATH=$(find "$LIBS" -name 'vecmath-*.jar' | head -1)
mkdir -p "$OUT/test/doubles" "$OUT/test/scenario" "$OUT/test/main"
javac -nowarn -d "$OUT/test/doubles" $(find test/doubles -name '*.java')
javac -nowarn -cp "$OUT/test/doubles:$BC_JAR" -d "$OUT/test/scenario" test/src/Scenario.java
javac -nowarn -cp "$OUT/jar:$CP" -d "$OUT/test/main" test/src/rlperf/core/EquivalenceTest.java
java ${TIMING:+-Dscenario.timing=true} -cp "$OUT/test/main:$OUT/jar:$CP" rlperf.core.EquivalenceTest "$BC_JAR" "$OUT/test/doubles" "$OUT/test/scenario" "$OUT/donor" "$VECMATH"
