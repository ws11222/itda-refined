#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILDS_DIR="$REPO_ROOT/load-test/builds"
WORKTREE_DIR="$REPO_ROOT/.loadtest-worktrees"

mkdir -p "$BUILDS_DIR" "$WORKTREE_DIR"

build_from_dir() {
  local dir=$1
  local label=$2

  echo "==> Building jar from $label ($dir)"
  (cd "$dir/backend" && ./gradlew clean bootJar -x test --quiet)

  local src
  src=$(ls "$dir/backend/build/libs/"*.jar | grep -v plain | head -1)
  cp "$src" "$BUILDS_DIR/${label}.jar"
  echo "    -> $BUILDS_DIR/${label}.jar"
}

build_from_ref() {
  local ref=$1
  local label=$2
  local current_ref
  current_ref=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)

  if [ "$current_ref" = "$ref" ]; then
    build_from_dir "$REPO_ROOT" "$label"
    return
  fi

  local wt="$WORKTREE_DIR/$label"
  echo "==> Preparing worktree for $label ($ref)"
  if [ ! -d "$wt" ]; then
    git -C "$REPO_ROOT" worktree add "$wt" "$ref"
  else
    git -C "$wt" fetch origin 2>/dev/null || true
    git -C "$wt" checkout "$ref"
  fi
  build_from_dir "$wt" "$label"
}

AFTER_REF=${AFTER_REF:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)}
BEFORE_REF=${BEFORE_REF:-main}

build_from_ref "$BEFORE_REF" before
build_from_ref "$AFTER_REF" after

echo ""
echo "Done. Jars in $BUILDS_DIR:"
ls -lh "$BUILDS_DIR"
