#!/usr/bin/env bash
# Compare benchmark runs: compare.sh <baseline-dir> <candidate-dir> [min-effect-pct]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$HERE/../launcher/build/libs/velox-launcher-0.1.0.jar" bench compare --baseline "$1" --candidate "$2" --min-effect "${3:-1.0}"
