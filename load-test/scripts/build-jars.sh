#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILDS_DIR="$REPO_ROOT/load-test/builds"
WORKTREE_DIR="$REPO_ROOT/.loadtest-worktrees"

mkdir -p "$BUILDS_DIR" "$WORKTREE_DIR"

build_from_ref() {
  local ref=$1
  local label=$2
  local wt="$WORKTREE_DIR/$label"

  echo "==> Preparing worktree for $label ($ref)"
  if [ ! -d "$wt" ]; then
    git -C "$REPO_ROOT" worktree add "$wt" "$ref"
  else
    git -C "$wt" fetch origin 2>/dev/null || true
    git -C "$wt" checkout "$ref"
  fi

  echo "==> Building jar from $label"
  (cd "$wt/backend" && ./gradlew clean bootJar -x test --quiet)

  local src
  src=$(ls "$wt/backend/build/libs/"*.jar | grep -v plain | head -1)
  cp "$src" "$BUILDS_DIR/${label}.jar"
  echo "    -> $BUILDS_DIR/${label}.jar"
}

build_from_ref main before
build_from_ref refactor/async-embedding-refresh after

echo ""
echo "Done. Jars in $BUILDS_DIR:"
ls -lh "$BUILDS_DIR"
