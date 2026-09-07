#!/usr/bin/env bash
set -euo pipefail

BASE_COMPOSE=(docker compose -f deploy/docker-compose.yml)
SCALED_COMPOSE=(docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.scaled.yml)
SEARCH_URL='http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z'

cleanup() {
  "${SCALED_COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}

diagnostics() {
  echo '--- compose ps ---'
  "${SCALED_COMPOSE[@]}" ps || true
  echo '--- compose logs (tail) ---'
  "${SCALED_COMPOSE[@]}" logs --no-color --tail=200 || true
}

finish() {
  status=$?
  if (( status != 0 )); then
    diagnostics
  fi
  cleanup
  exit "$status"
}
trap finish EXIT

sql() {
  "${BASE_COMPOSE[@]}" exec -T db psql -U egobb -d egobb -Atc "$1" | tr -d '\r'
}

wait_for_search_events() {
  local attempts=${1:-36}
  local payload count
  for ((i = 1; i <= attempts; i++)); do
    if payload="$(curl -fsS "$SEARCH_URL" 2>/dev/null)"; then
      count="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["events"]))' <<<"$payload" 2>/dev/null || true)"
      if [[ "$count" == '3' ]]; then
        return 0
      fi
    fi
    sleep 5
  done
  echo 'Timed out waiting for the bundled snapshot to yield exactly 3 searchable events.' >&2
  return 1
}

wait_for_requeue_log() {
  for ((i = 1; i <= 20; i++)); do
    if "${BASE_COMPOSE[@]}" logs --no-color worker-process 2>&1 | grep -q 'Requeued stuck staged plans count=3'; then
      return 0
    fi
    sleep 2
  done
  echo 'Timed out waiting for stale-work requeue evidence.' >&2
  return 1
}

count_running_replicas() {
  local service=$1
  "${SCALED_COMPOSE[@]}" ps --status running -q "$service" | sed '/^$/d' | wc -l | tr -d ' '
}

echo '== Default clean-checkout path =='
cleanup
WORKER_PROCESS_STUCK_TTL_MS=5000 WORKER_PROCESS_REQUEUE_INTERVAL_MS=2000 make run
wait_for_search_events

plans_count="$(sql 'select count(*) from plans;')"
staging_count="$(sql 'select count(*) from staging_plans;')"
if [[ "$plans_count" != '4' ]]; then
  echo "Expected 4 canonical plans, got $plans_count" >&2
  exit 1
fi
if (( staging_count < 4 )); then
  echo "Expected at least 4 staged rows, got $staging_count" >&2
  exit 1
fi

echo "Observed $staging_count staged rows, 4 canonical plans and 3 searchable events."

echo '== Safe stale-work recovery demonstration =='
recovery_rows="$(sql "with picked as (select id from staging_plans order by created_at desc limit 3) update staging_plans s set status='PROCESSING', claimed_at=now() - interval '1 hour' from picked p where s.id=p.id returning s.id;")"
recovery_count="$(printf '%s\n' "$recovery_rows" | sed '/^$/d' | wc -l | tr -d ' ')"
if [[ "$recovery_count" != '3' ]]; then
  echo "Expected to inject 3 stale rows, got $recovery_count" >&2
  exit 1
fi
wait_for_requeue_log
wait_for_search_events 12
echo 'Observed the worker requeue exactly 3 synthetic stale PROCESSING rows.'

echo '== Scaled clean-checkout path =='
cleanup
API=2 PROCESS=2 make run-scaled
wait_for_search_events

api_replicas="$(count_running_replicas api)"
process_replicas="$(count_running_replicas worker-process)"
if [[ "$api_replicas" != '2' ]]; then
  echo "Expected 2 running api replicas, got $api_replicas" >&2
  exit 1
fi
if [[ "$process_replicas" != '2' ]]; then
  echo "Expected 2 running worker-process replicas, got $process_replicas" >&2
  exit 1
fi

curl -fsS 'http://localhost:18080/actuator/health' | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
echo 'Observed 2 API replicas and 2 process-worker replicas behind the local Traefik path, with health UP and 3 searchable events.'
