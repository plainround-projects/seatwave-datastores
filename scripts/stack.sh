#!/usr/bin/env bash
# Starts, tests and stops Module 15's lab Redis under the Compose project name
# seatwave-datastores, its one port on 127.0.0.1 only.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      generate the two lab passwords and the ACL file into .secrets/redis (scripts/make-acl.sh)
#           if missing, start Redis, wait until healthy
#   down    remove the project's container, network and volume (down -v) and the passwords of the
#           Redis that is now gone, then check none is left
#   test    up, ./gradlew stackTest stackCoverage, then down, on every path
#   status  list the project's containers (read-only)
set -euo pipefail

readonly PROJECT="seatwave-datastores"
readonly WAIT_SECONDS=60
readonly DOCKER_TIMEOUT_SECONDS=10

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  scripts/make-acl.sh if .secrets/redis/users.acl is missing; ${compose[*]} up -d --wait --quiet-pull" ;;
  down) echo "  ${compose[*]} down -v --remove-orphans; remove .secrets/redis; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest stackCoverage, then down -v (always)" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
docker_answers() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

up() {
  # A running Redis keeps reading the files it started with, so existing ones are kept.
  if [[ ! -s .secrets/redis/users.acl ]]; then
    scripts/make-acl.sh
  fi
  # --wait returns when the healthcheck passes, or fails after the timeout.
  "${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS" --quiet-pull
  audit "up: started $PROJECT"
}

down() {
  "${compose[@]}" down -v --remove-orphans
  # The passwords belonged to the Redis in the volume just removed; the next up makes new ones.
  rm -rf .secrets/redis
  audit "down: removed $PROJECT and its passwords"
  local left=""
  left+="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest stackCoverage || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac
