#!/usr/bin/env bash
set -euo pipefail

platform_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_dir="$platform_dir/build/ci-smoke"
compose=(docker compose -p testforge-smoke -f "$platform_dir/infra/compose.yaml" -f "$platform_dir/infra/compose.test.yaml")
mkdir -p "$log_dir"
export MYSQL_PORT=13306 REDIS_PORT=16379 MINIO_API_PORT=19000 MINIO_CONSOLE_PORT=19001

cleanup() {
  if [[ -n "${app_pid:-}" ]]; then kill "$app_pid" 2>/dev/null || true; fi
  "${compose[@]}" logs --no-color >"$log_dir/compose.log" 2>&1 || true
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" up -d --wait mysql redis minio
(cd "$platform_dir/testforge-app" && ./gradlew :app:bootJar --no-daemon)

export TESTFORGE_DB_URL="jdbc:mysql://127.0.0.1:13306/testforge?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
export MYSQL_USER="testforge"
export MYSQL_PASSWORD="testforge_mysql"
export REDIS_HOST="127.0.0.1"
export REDIS_PORT="16379"
export REDIS_PASSWORD="testforge_redis"
export TESTFORGE_DISPATCH_REDIS_ENABLED="false"
export TESTFORGE_DISPATCH_RELAY_ENABLED="false"
export TESTFORGE_RELIABILITY_ENABLED="false"
java -jar "$platform_dir/testforge-app/app/build/libs/testforge-app.jar" --server.port=18081 >"$log_dir/app.log" 2>&1 &
app_pid=$!

for _ in {1..60}; do
  if curl --fail --silent http://127.0.0.1:18081/actuator/health >"$log_dir/health.json"; then
    curl --fail --silent http://127.0.0.1:18081/actuator/prometheus >"$log_dir/prometheus.txt"
    curl --fail --silent http://127.0.0.1:18081/api/v1/monitoring/resources >"$log_dir/resources.json"
    exit 0
  fi
  sleep 2
done

echo "TestForge application did not become healthy" >&2
exit 1
