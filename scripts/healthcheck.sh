#!/bin/sh
set -eu

HEALTH_FILE="${HEALTH_FILE:-/tmp/fileserversync.heartbeat}"
MAX_AGE_SECONDS="${HEALTH_MAX_AGE_SECONDS:-900}"
MAX_CHILD_AGE_SECONDS="${MAX_CHILD_AGE_SECONDS:-7200}"

pid="$(pgrep -f 'java .*application[.]jar|org[.]example[.]Main' | head -n 1 || true)"
if [ -z "$pid" ]; then
  echo "filesync java process is not running"
  exit 1
fi

if [ ! -s "$HEALTH_FILE" ]; then
  echo "heartbeat file is missing: $HEALTH_FILE"
  exit 1
fi

now="$(date +%s)"
mtime="$(stat -c %Y "$HEALTH_FILE")"
age="$((now - mtime))"
if [ "$age" -gt "$MAX_AGE_SECONDS" ]; then
  echo "heartbeat is stale: ${age}s"
  exit 1
fi

for child in $(pgrep -f '^(rsync|ssh) ' || true); do
  elapsed="$(ps -o etimes= -p "$child" | tr -d ' ')"
  if [ -n "$elapsed" ] && [ "$elapsed" -gt "$MAX_CHILD_AGE_SECONDS" ]; then
    echo "child process $child is too old: ${elapsed}s"
    exit 1
  fi
done

exit 0
