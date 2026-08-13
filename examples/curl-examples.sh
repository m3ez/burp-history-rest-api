#!/usr/bin/env sh
set -eu

BASE_URL="${BURP_HISTORY_BASE_URL:-http://127.0.0.1:8090}"
: "${BURP_HISTORY_TOKEN:?Set BURP_HISTORY_TOKEN to the token shown in the Burp History REST tab}"
AUTH="Authorization: Bearer ${BURP_HISTORY_TOKEN}"

printf '%s\n' '--- Health (public) ---'
curl --fail --show-error --silent "${BASE_URL}/api/v1/health"

printf '\n\n%s\n' '--- Capabilities (public) ---'
curl --fail --show-error --silent "${BASE_URL}/api/v1/capabilities"

printf '\n\n%s\n' '--- Structured filtered history ---'
curl --fail --show-error --silent \
  -H "$AUTH" \
  "${BASE_URL}/api/v1/history?host=*.example.com&method=POST&status=2xx&request_header=Authorization%3ABearer&cookie=session&query_param=debug%3Dtrue&last=2h&include=headers,cookies,query_parameters&limit=25"

printf '\n\n%s\n' '--- NDJSON / JSONL ---'
curl --fail --show-error --silent \
  -H "$AUTH" \
  "${BASE_URL}/api/v1/history?search_in=headers&q=Authorization&sort=id&order=asc&format=ndjson&limit=100"

# Replace 42 with a real history ID.
# curl --fail --show-error --silent -H "$AUTH" \
#   "${BASE_URL}/api/v1/history/42?format=text"
#
# curl --fail --show-error --silent -H "$AUTH" \
#   --output request-42.http \
#   "${BASE_URL}/api/v1/history/42/request?format=raw"

printf '\n\n%s\n' '--- Incremental cursor page ---'
curl --fail --show-error --silent \
  -H "$AUTH" \
  "${BASE_URL}/api/v1/history?after_id=0&limit=100"

printf '\n\n%s\n' '--- Structured POST search ---'
curl --fail --show-error --silent \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"filters":{"host":["*.example.com"],"method":["POST"],"status":["2xx"]},"search":{"keywords":["access_token"],"location":"response"},"pagination":{"limit":100},"sync":{"afterId":0}}' \
  "${BASE_URL}/api/v1/history/search"

printf '\n\n%s\n' '--- Live SSE stream (5 seconds) ---'
curl -N --fail --show-error --silent \
  -H "$AUTH" \
  -H 'Accept: text/event-stream' \
  "${BASE_URL}/api/v1/events?after_id=0&timeout=5&heartbeat=5"
