#!/usr/bin/env bash
# Repeats the synthetic GC workload under each collector configuration, then compares them with
# the same statistics `velox bench compare` uses. Usage: run-gc-sim.sh <velox-launcher.jar> [runs] [seconds]
set -euo pipefail
JAR="$1"; RUNS="${2:-3}"; SECS="${3:-20}"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/results"
javac -cp "$JAR" -d "$HERE" "$HERE/GcFrameSim.java"
rm -rf "$OUT"; mkdir -p "$OUT"
declare -A CFG=(
  [g1-default]="-XX:+UseG1GC"
  [g1-pause50]="-XX:+UseG1GC -XX:MaxGCPauseMillis=50"
  [zgc]="-XX:+UseZGC $( [ "$(java -XX:+UseZGC -XX:+ZGenerational -version 2>&1 | grep -ci 'warn')" = 0 ] && echo -XX:+ZGenerational )"
)
for run in $(seq 1 "$RUNS"); do
  for name in g1-default g1-pause50 zgc; do   # interleaved, so drift affects all configs equally
    java -Xms4g -Xmx4g ${CFG[$name]} -cp "$JAR:$HERE" GcFrameSim "$name" "$SECS" "$OUT/$name" 2>&1 | grep -v JAVA_TOOL_OPTIONS
  done
done
for cand in g1-pause50 zgc; do
  echo; echo "=== g1-default (baseline) vs $cand ==="
  java -cp "$JAR" dev.velox.launcher.Main bench compare --baseline "$OUT/g1-default" --candidate "$OUT/$cand" 2>&1 | grep -v JAVA_TOOL_OPTIONS
done
