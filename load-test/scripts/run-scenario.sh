#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:-}
SCENARIO=${2:-}
if [ -z "$VERSION" ] || [ -z "$SCENARIO" ]; then
  echo "usage: $0 <before|after> <s1|s2|s3|s4>" >&2
  exit 1
fi

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOADTEST_DIR="$REPO_ROOT/load-test"
RESULTS_DIR="$LOADTEST_DIR/results"
mkdir -p "$RESULTS_DIR"

JAR="$LOADTEST_DIR/builds/${VERSION}.jar"
if [ ! -f "$JAR" ]; then
  echo "Jar missing: $JAR. Run scripts/build-jars.sh first." >&2
  exit 1
fi

case "$SCENARIO" in
  s1) export DELAY_MODE=fixed; export DELAY_MS=200;   SCRIPT=s1-baseline.js ;;
  s2) export DELAY_MODE=fixed; export DELAY_MS=200;   SCRIPT=s2-saturation.js ;;
  s3) export DELAY_MODE=fixed; export DELAY_MS=5000;  SCRIPT=s3-degradation.js ;;
  s4) export DELAY_MODE=fixed; export DELAY_MS=30000; SCRIPT=s4-outage.js ;;
  s5) export DELAY_MODE=fixed; export DELAY_MS=5000;  SCRIPT=s5-blast-radius.js ;;
  s6) export DELAY_MODE=fixed; export DELAY_MS=200;   SCRIPT=s6-cache-miss.js ;;
  *) echo "unknown scenario $SCENARIO" >&2; exit 1 ;;
esac

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TAG="${TIMESTAMP}-${VERSION}-${SCENARIO}"

cleanup() {
  echo "==> Cleanup"
  if [ -n "${SPRING_PID:-}" ]; then
    kill -TERM "$SPRING_PID" 2>/dev/null || true
    wait "$SPRING_PID" 2>/dev/null || true
  fi
  (cd "$LOADTEST_DIR" && docker compose down -v --remove-orphans) >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> [Infra] DELAY_MODE=$DELAY_MODE DELAY_MS=$DELAY_MS"
cd "$LOADTEST_DIR"
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
docker compose up -d --wait

echo "==> [Spring] starting $VERSION jar"
DB_URL="jdbc:postgresql://localhost:5433/itda" \
DB_USERNAME=dev \
DB_PASSWORD=devpw \
EMBEDDING_SERVER_URL=http://localhost:8000 \
EMBEDDING_SERVER_API_KEY=local-dev-key \
JWT_SECRET_KEY=loadtest-jwt-secret-key-loadtest-jwt-secret-key \
SPRING_PROFILES_ACTIVE=loadtest \
java -jar "$JAR" > "$RESULTS_DIR/${TAG}.spring.log" 2>&1 &
SPRING_PID=$!

echo "==> [Spring] waiting for readiness (pid=$SPRING_PID)"
READY=0
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/actuator/health" 2>/dev/null || echo "000")
  if [ "$code" = "200" ]; then
    echo "    actuator healthy after ${i}s"
    READY=1
    break
  fi
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8080/api/v1/auth/login" 2>/dev/null || echo "000")
  if [ "$code" = "400" ] || [ "$code" = "415" ] || [ "$code" = "401" ]; then
    echo "    spring auth reachable after ${i}s (no actuator)"
    READY=1
    break
  fi
  sleep 1
done
if [ "$READY" != "1" ]; then
  echo "Spring failed to become ready. See $RESULTS_DIR/${TAG}.spring.log" >&2
  exit 1
fi

echo "==> [k6] running $SCRIPT"
k6 run \
  --summary-export="$RESULTS_DIR/${TAG}.summary.json" \
  "$LOADTEST_DIR/scripts/$SCRIPT" || true

if curl -sf "http://localhost:8080/actuator/metrics/hikaricp.connections.active" > "$RESULTS_DIR/${TAG}.hikari-active.json" 2>/dev/null; then
  curl -sf "http://localhost:8080/actuator/metrics/hikaricp.connections.pending" > "$RESULTS_DIR/${TAG}.hikari-pending.json" || true
  curl -sf "http://localhost:8080/actuator/metrics/executor.queued" > "$RESULTS_DIR/${TAG}.executor.json" || true
  echo "==> [Metrics] captured (after-only)"
fi

echo "==> Done. Results: $RESULTS_DIR/${TAG}.*"
