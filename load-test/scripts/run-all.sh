#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPTS_DIR="$REPO_ROOT/load-test/scripts"

bash "$SCRIPTS_DIR/build-jars.sh"

for v in before after; do
  for s in s1 s2 s3 s4; do
    echo ""
    echo "========== $v / $s =========="
    bash "$SCRIPTS_DIR/run-scenario.sh" "$v" "$s"
    sleep 5
  done
done

echo ""
echo "All runs finished. Results in load-test/results/"
