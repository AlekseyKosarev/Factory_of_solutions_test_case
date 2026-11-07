#!/usr/bin/env bash
# Same acceptance cycle with an isolated local PostgreSQL cluster, without Docker.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d /tmp/kkd-migration-check.XXXXXX)
db_port=${CHECK_DB_PORT:-55433}
app_port=${CHECK_APP_PORT:-58081}
app_pid=''
stop_app() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; app_pid=''; fi
}
cleanup() {
  stop_app
  pg_ctl -D "$work/db" -m fast stop >/dev/null 2>&1 || true
  if [[ "${KEEP_CHECK_LOGS:-false}" == true ]]; then echo "Logs: $work"; else rm -rf "$work"; fi
}
trap cleanup EXIT
initdb -D "$work/db" -A trust -U kkd > "$work/init.log"
pg_ctl -D "$work/db" -l "$work/postgres.log" -o "-p $db_port -h 127.0.0.1 -k $work" start
createdb -h 127.0.0.1 -p "$db_port" -U kkd kkd
export JDBC_URL="jdbc:postgresql://127.0.0.1:$db_port/kkd" DB_USER=kkd DB_PASSWORD=unused APP_PORT="$app_port" DEMO_ENABLED=true
if [[ "${SKIP_BUILD:-false}" != true ]]; then ./mvnw -B package; fi
java_cmd=${JAVA_HOME:+$JAVA_HOME/bin/}java
start_app() {
  "$java_cmd" -jar target/kkd-service-1.0.0.jar > "$work/app.log" 2>&1 & app_pid=$!
  for attempt in {1..90}; do
    if curl -fsS "http://127.0.0.1:$app_port/actuator/health/readiness" >/dev/null 2>&1; then return; fi
    if ! kill -0 "$app_pid" 2>/dev/null; then cat "$work/app.log"; return 1; fi
    sleep 1
  done
  cat "$work/app.log"; return 1
}
start_app
python3 scripts/assert-demo.py "http://127.0.0.1:$app_port"
[[ "$(psql -h 127.0.0.1 -p "$db_port" -U kkd -d kkd -Atc 'SELECT count(*) FROM goal')" == 24 ]]
stop_app
./mvnw -B liquibase:rollback -Dliquibase.rollbackTag=baseline
count=$(psql -h 127.0.0.1 -p "$db_port" -U kkd -d kkd -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name NOT IN ('databasechangelog','databasechangeloglock')")
[[ "$count" == 0 ]]
./mvnw -B liquibase:update
start_app
python3 scripts/assert-demo.py "http://127.0.0.1:$app_port"
stop_app
start_app
python3 scripts/assert-demo.py "http://127.0.0.1:$app_port"
echo 'Apply → rollback → reapply and restart passed.'
