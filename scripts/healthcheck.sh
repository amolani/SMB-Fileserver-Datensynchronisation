#!/bin/sh
set -eu
HEALTH_FILE="${HEALTH_FILE:-/tmp/fileserversync.heartbeat}"
MAX_AGE_SECONDS="${HEALTH_MAX_AGE_SECONDS:-90}"
case "$MAX_AGE_SECONDS" in ''|*[!0-9]*) echo "Invalid health age limit"; exit 1 ;; esac
[ -s "$HEALTH_FILE" ] || { echo "No successful audit cycle heartbeat"; exit 1; }
pid=$(sed -n 's/^pid=//p' "$HEALTH_FILE")
case "$pid" in ''|*[!0-9]*) echo "Invalid heartbeat PID"; exit 1 ;; esac
kill -0 "$pid" 2>/dev/null || { echo "Application process is not running"; exit 1; }
now=$(date +%s)
mtime=$(stat -c %Y "$HEALTH_FILE")
age=$((now - mtime))
[ "$age" -ge 0 ] && [ "$age" -le "$MAX_AGE_SECONDS" ] || { echo "Audit heartbeat stale or in the future"; exit 1; }
grep -qx 'status=healthy' "$HEALTH_FILE" || { echo "Delivery retry or backlog requires attention"; exit 1; }
