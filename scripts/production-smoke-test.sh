#!/usr/bin/env sh
# Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee
set -eu

BASE_URL="${BURP_HISTORY_BASE_URL:-http://127.0.0.1:8090}"
TOKEN="${BURP_HISTORY_TOKEN:-}"

if [ -z "$TOKEN" ]; then
  echo "Set BURP_HISTORY_TOKEN to a token with history:read, history:events, and metrics:read." >&2
  exit 2
fi

request() {
  name="$1"; url="$2"; expected="$3"
  body="$(mktemp)"; trap 'rm -f "$body"' EXIT HUP INT TERM
  status="$(curl --silent --show-error --output "$body" --write-out '%{http_code}' \
    --header "Authorization: Bearer $TOKEN" --header 'Accept: application/json' "$url")"
  if [ "$status" != "$expected" ]; then
    echo "[FAIL] $name: HTTP $status (expected $expected)" >&2
    cat "$body" >&2
    exit 1
  fi
  echo "[PASS] $name: HTTP $status"
  rm -f "$body"; trap - EXIT HUP INT TERM
}

request "health" "$BASE_URL/api/v1/health" 200
request "capabilities" "$BASE_URL/api/v1/capabilities" 200
request "history search" "$BASE_URL/api/v1/history?limit=1&sort=id&order=desc" 200
request "metrics" "$BASE_URL/api/v1/metrics" 200

sse="$(mktemp)"; trap 'rm -f "$sse"' EXIT HUP INT TERM
set +e
curl --silent --show-error --no-buffer --max-time 3 \
  --header "Authorization: Bearer $TOKEN" --header 'Accept: text/event-stream' \
  "$BASE_URL/api/v1/events?after_id=0&timeout=2&heartbeat=5&limit=1" > "$sse"
code=$?
set -e
# curl may return 28 when max-time closes the stream; the ready event still proves the endpoint works.
if ! grep -q '^event: ready' "$sse"; then
  echo "[FAIL] event stream did not return a ready event (curl exit $code)" >&2
  cat "$sse" >&2
  exit 1
fi
echo "[PASS] event stream returned ready event"
rm -f "$sse"; trap - EXIT HUP INT TERM

echo "Production smoke test passed for $BASE_URL"
