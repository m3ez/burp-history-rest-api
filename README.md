# Burp History REST API

Production-focused, read-only REST API and browser dashboard for Burp Proxy HTTP history.

**Author:** Supakiad S. (m3ez) - E-CQURITY (Thailand)  
**Profile:** http://x.com/supakiad_mee

## v1.4.1 production networking

- Self-contained HTTP/1.1 server; no `jdk.httpserver` or `com.sun.*` dependency.
- Detected-interface bind selector with explicit wildcard/all-interface opt-in, exposure warnings, and a bounded worker pool and queue.
- Event-driven SSE through Montoya `ProxyResponseHandler`; no repeated whole-history polling.
- Bounded resumable event ring with stale-cursor detection.
- Project-scoped, multi-client tokens stored as salted hashes; user-preference fallback when Burp project persistence is unavailable.
- Permission scopes: `history:read`, `history:raw`, `history:events`, `metrics:read`, `audit:read`.
- Exact raw endpoints disabled by default and protected by a separate scope.
- Rotating metadata-only JSONL audit log.
- Per-token rate limiting, query concurrency limits, query deadlines and history scan caps.
- Request-smuggling defenses: conflicting `Content-Length`, `Transfer-Encoding` plus `Content-Length`, invalid header names, duplicate/missing `Host`, unsupported HTTP versions and oversized targets are rejected.
- Chunked JSON request-body support.
- JSON and Prometheus runtime metrics.
- Sensitive structured-output redaction enabled by default.
- Reproducible JAR configuration, checksum-verified Gradle bootstrap and CI checks.

## Requirements

- Burp Suite with Montoya API 2026.4 compatibility
- Java 21

## Build

```bash
./gradlew --no-daemon clean check jar
```

Output:

```text
build/libs/burp-history-rest-api-1.4.1.jar
```

The Gradle bootstrap verifies the pinned Gradle 8.14.3 binary ZIP with SHA-256 before extraction.

## Install

1. Open **Burp Suite → Extensions → Installed**.
2. Click **Add** and choose **Java**.
3. Select `burp-history-rest-api-1.4.1.jar`.
4. Open the **History REST** tab.
5. Copy the initial project token shown once, or create separate client tokens.
6. Keep exact raw access disabled unless a trusted integration requires it.

## Network binding

The default remains the IPv4 loopback address `127.0.0.1`. The Runtime tab now enumerates active local interface addresses and lets an operator bind the API to one specific IPv4 or IPv6 address. Press **Refresh** after VPN, Wi-Fi, Ethernet, or container-network changes.

The separate **Allow all interfaces (wildcard bind)** checkbox binds the listener to the operating system wildcard address. It is disabled by default, requires confirmation, and is reported by `/api/v1/health` as `allowAllInterfaces=true` and `networkExposed=true`. In wildcard mode the **Open dashboard** button still uses `http://127.0.0.1:<port>`; remote clients must connect to an actual address of the Burp host.

Binding to a non-loopback interface or to all interfaces exposes the API beyond the local process. Apply host-firewall restrictions, use one scoped token per client, retain audit records, and provide transport protection through a trusted private network, VPN, SSH tunnel, or TLS-capable reverse proxy. The embedded listener is HTTP, not HTTPS.

## Token model

Tokens belong to the current Burp project when project persistence is available. In environments without project persistence, the extension uses a clearly reported user-preferences fallback. Only salted hashes are persisted, and a secret is displayed once when created.

Recommended separation:

```text
Dashboard / search client: history:read
Collector:                 history:read, history:events, metrics:read
Raw evidence exporter:     history:read, history:raw
Operations administrator: audit:read, metrics:read
```

Do not give `history:raw` to normal dashboards or AI integrations.

## Examples

```bash
export BURP_HISTORY_TOKEN='project-token'

curl --fail --show-error \
  -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/history?host=*.example.com&method=POST&status=2xx&cookie=session&last=2h&sort=time&order=desc'
```

Structured search:

```bash
curl --fail --show-error \
  -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{
    "filters":{"host":"*.example.com","method":["POST"],"status":"2xx"},
    "search":{"keywords":["access_token"],"location":"response"},
    "pagination":{"limit":100}
  }' \
  http://127.0.0.1:8090/api/v1/history/search
```

Incremental sync:

```bash
curl -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/history?after_id=1000&sort=id&order=asc&limit=500'
```

Event stream:

```bash
curl -N -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/events?after_id=1000&timeout=300&heartbeat=15'
```

Use an initial incremental history query, then connect to SSE. The event ring contains traffic observed while the extension is running and returns `409 event_cursor_expired` when a consumer falls behind the retained window.

Metrics:

```bash
curl -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/metrics?format=prometheus'
```

## API endpoints

```text
GET  /api/v1/health                 Public liveness and feature status
GET  /api/v1/capabilities           Public machine-readable capabilities
GET  /api/v1/openapi.json           Public OpenAPI 3.1 document
GET  /api/v1/history                Search/filter/sort/page history
POST /api/v1/history/search         Structured JSON search
GET  /api/v1/history/{id}           Redacted details
GET  /api/v1/history/{id}/request   text/base64 or exact raw request
GET  /api/v1/history/{id}/response  text/base64 or exact raw response
GET  /api/v1/events                 Event-driven SSE stream
GET  /api/v1/metrics                JSON or Prometheus metrics
GET  /api/v1/audit                  Recent access-audit metadata
GET  /ui/                           Browser dashboard
```

## Production deployment guidance

- Prefer `127.0.0.1` for local-only use. When remote access is required, bind one specific interface rather than all interfaces.
- Use the all-interface checkbox only with explicit firewall policy and transport protection. SSH forwarding, a VPN, or a managed authenticated/TLS tunnel remains preferred.
- Create one token per external client and revoke it when unused.
- Apply least-privilege scopes and token expiry for raw/admin clients.
- Keep redaction enabled.
- Keep raw access disabled unless required.
- Store the audit log on an encrypted local volume and set suitable retention.
- Monitor `/api/v1/metrics` for active queries, event streams, rate limiting, dropped events and error rates.
- Size `eventBufferSize` for the expected traffic rate and maximum client outage.
- Raise scan limits carefully; large custom searches still require Burp Proxy history traversal.

## Production acceptance tools

```bash
export BURP_HISTORY_TOKEN='token-with-read-events-metrics'
./scripts/production-smoke-test.sh
python3 scripts/load-test.py --token "$BURP_HISTORY_TOKEN" --requests 1000 --concurrency 8
```

See `PRODUCTION_CHECKLIST.md` for Burp load, capacity, security, and soak-test acceptance steps.

## Validation

`SelfTest` currently runs 134 integration and security assertions covering filtering, sorting, redaction, exact raw-byte integrity, cursor sync, JSON POST search, scoped authorization, token persistence fallback, audit/metrics endpoints, event ordering and rollover, concurrent clients, scan caps, duplicate/conflicting `Content-Length`, `TE+CL` rejection, required `Host`, chunked JSON requests, interface enumeration, selected-interface binding, and wildcard binding through a non-loopback adapter.

A final acceptance test should still be run in the exact Burp Suite edition/version, operating system and project size used in production.
