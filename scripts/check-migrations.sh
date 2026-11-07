#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --tests ) ]]; then
  echo "Usage: $0 [--tests]" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
project="kkd-migration-check-$(date +%s)-$$"
compose=(docker compose -p "$project" -f scripts/compose.check.yaml)
cleanup() { "${compose[@]}" down -v --remove-orphans; }
trap cleanup EXIT
"${compose[@]}" up -d --wait db
if [[ "${1:-}" == --tests ]]; then
  "${compose[@]}" run --build --rm --no-deps tests
fi
"${compose[@]}" up --build -d --wait app
"${compose[@]}" run --rm --no-deps oracle
count=$("${compose[@]}" exec -T db psql -U kkd -d kkd -Atc 'SELECT count(*) FROM goal')
[[ "$count" == 24 ]]
"${compose[@]}" stop app
"${compose[@]}" run --build --rm migrator liquibase:rollback -Dliquibase.rollbackTag=baseline
count=$("${compose[@]}" exec -T db psql -U kkd -d kkd -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name NOT IN ('databasechangelog','databasechangeloglock')")
[[ "$count" == 0 ]]
"${compose[@]}" run --rm migrator liquibase:update
"${compose[@]}" up -d --wait app
"${compose[@]}" run --rm --no-deps oracle
"${compose[@]}" restart app
"${compose[@]}" up -d --wait app
"${compose[@]}" run --rm --no-deps oracle
echo 'Apply → rollback → reapply and restart passed.'
