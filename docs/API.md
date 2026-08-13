# API reference

Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)  
Profile: http://x.com/supakiad_mee

Default base URL:

```text
http://127.0.0.1:8090
```

The active port, selected bind address, wildcard state, network-exposure state, and configured limits are returned by `GET /api/v1/health`.

## Network binding

The Runtime tab enumerates active local IPv4 and IPv6 interface addresses. The API can bind to one selected address, or to the operating-system wildcard address only when **Allow all interfaces (wildcard bind)** is explicitly enabled.

`GET /api/v1/health` reports `bindAddress`, `bindHost`, `allowAllInterfaces`, `networkExposed`, `listener`, and a locally connectable `baseUrl`. In wildcard mode, external clients must replace the loopback host in `baseUrl` with an actual address of the Burp machine. The embedded listener is plain HTTP; use firewall policy and a VPN, SSH tunnel, private network, or TLS reverse proxy for remote access.

## Authentication

Protected endpoints accept either:

```http
Authorization: Bearer <token>
```

or:

```http
X-API-Key: <token>
```

Do not put the token in a URL. Health, capabilities, OpenAPI, and the dashboard shell do not require a token. They are local-only under the default loopback configuration, but become network-visible when a non-loopback or wildcard bind is enabled.

Tokens are created and revoked in Burp's **History REST** tab. They are stored as salted hashes. Project persistence is used when available; `/api/v1/health` and `/api/v1/capabilities` report `tokenStorage` when the preferences fallback is active.

### Permission scopes

| Scope | Access |
|---|---|
| `history:read` | Filtered/redacted history, item details, text and Base64 envelopes |
| `history:raw` | Exact unredacted request/response bytes; project raw access must also be enabled |
| `history:events` | Server-Sent Events stream |
| `metrics:read` | JSON and Prometheus metrics |
| `audit:read` | Recent metadata-only API audit events |

Use one token per external client. Raw access is disabled by default and should be granted only to trusted evidence exporters.

## Service endpoints

| Endpoint | Authentication | Purpose |
|---|---:|---|
| `GET /api/v1/health` | No | Status, active URL, limits, API and extension versions |
| `GET /api/v1/capabilities` | No | Machine-readable filter names, formats, search locations, and sort fields |
| `GET /api/v1/openapi.json` | No | OpenAPI 3.1 contract |
| `GET /ui/` | No | Browser dashboard shell |

## `GET /api/v1/history`

All supplied filter categories are ANDed. Repeated values inside one category are ORed unless stated otherwise.

### Core request filters

| Parameter | Aliases | Meaning |
|---|---|---|
| `id` | `history_id` | One or more exact Burp history IDs; repeat or comma-separate |
| `id_from` / `id_to` | — | Inclusive ID range |
| `after_id` | `since_id` | Exclusive lower ID bound for forward incremental synchronization |
| `before_id` | — | Exclusive upper ID bound for reverse synchronization |
| `cursor` | — | Opaque instance-bound cursor returned by a previous response |
| `host` | `domain` | Case-insensitive host glob; `*` and `?` supported |
| `method` | `http_method` | HTTP methods; repeat or comma-separate |
| `url` | `url_contains` | URL substring |
| `path` | `url_path` | Path or path-with-query substring |
| `query_string` | `query_contains` | Raw query-string substring |
| `query_param` | `parameter`, `param` | `NAME` for presence or `NAME=VALUE` for a value substring |
| `port` | `service_port` | Destination service port |
| `protocol` | — | `http` or `https` |
| `secure` | — | Boolean alternative to `protocol` |
| `extension` | `ext` | Request file extension, with or without a leading dot |
| `listener_port` | `listener` | Burp Proxy listener port |
| `edited` | — | Whether Burp marked the history item as edited |
| `in_scope` | `scope` | Final-request target-scope state |

### Response filters

| Parameter | Aliases | Meaning |
|---|---|---|
| `has_response` | `response` | Response presence |
| `status` | `status_code` | Exact code, class (`2xx`), inclusive range (`200-299`), or `none` |
| `reason` | `reason_phrase` | Reason-phrase substring |
| `mime` | `content_type` | Burp-detected MIME enum name or description |

`content_type` is retained as a MIME alias. To filter actual HTTP `Content-Type` header values, use `request_content_type` or `response_content_type`.

### Header filters

| Parameter | Meaning |
|---|---|
| `request_header` | Match a request header |
| `response_header` | Match a response header |
| `header` | Match either side |
| `request_content_type` | Request `Content-Type` value substring |
| `response_content_type` | Response `Content-Type` value substring |

Header syntax:

```text
request_header=Authorization
request_header=Authorization:Bearer
request_header=Authorization=Bearer
response_header=Content-Type:application/json
```

Header names are case-insensitive. A value is matched as a substring and follows `case_sensitive`.

### Cookie filters

| Parameter | Aliases | Meaning |
|---|---|---|
| `request_cookie` | `req_cookie` | Cookie request-header name or name/value |
| `response_cookie` | `resp_cookie`, `set_cookie` | `Set-Cookie` name or name/value |
| `cookie` | — | Match request or response cookies |

Cookie syntax:

```text
cookie=PHPSESSID
cookie=session=abc
response_cookie=access_token
```

Cookie names are case-insensitive. Values are substring matches.

### Body and size filters

| Parameter | Aliases | Meaning |
|---|---|---|
| `request_body` | `req_body` | Request-body text substring |
| `response_body` | `resp_body` | Response-body text substring |
| `body` | — | Match either body |
| `request_length_min` / `request_length_max` | `request_size_min/max`, `min/max_request_length` | Inclusive exact-message byte-size range |
| `response_length_min` / `response_length_max` | `response_size_min/max`, `min/max_response_length` | Inclusive exact-message byte-size range |

Body-specific matching decodes bytes as UTF-8 and may be lossy for binary messages. General `q` search uses Montoya message search for request/response locations.

### General keyword search

| Parameter | Meaning |
|---|---|
| `q` | Search term; aliases: `keyword`, `search`, `query` |
| `keywords` | Comma-separated terms |
| `keyword_mode` | `any`/`or` or `all`/`and` |
| `search_in` | `any`, `both`, `request`, `response`, `metadata`, `url`, `headers`, `cookies`, `body`, or `query` |
| `case_sensitive` | Boolean; default `false` |
| `regex` | Boolean; default `false`, and must be enabled in Burp |

`any` searches request/response bytes plus metadata. `both` searches only the request and response messages.

### Timestamp and datetime filters

| Parameter | Meaning |
|---|---|
| `date` | Calendar day in `YYYY-MM-DD`, interpreted in `timezone` |
| `last` | Relative window such as `30s`, `15m`, `2h`, `7d`, `2w` |
| `from` | Inclusive lower bound |
| `to` | Inclusive upper bound |
| `timezone` | IANA timezone for local datetimes and dates; default `UTC` |

Aliases include `since`, `start`, `start_time`, `start_datetime`, `from_timestamp`, `timestamp_from`, `datetime_from`, `until`, `end`, `end_time`, `end_datetime`, `to_timestamp`, `timestamp_to`, `datetime_to`, and `tz`.

Accepted time formats:

```text
2026-07-19T10:15:30Z
2026-07-19T18:15:30+08:00
2026-07-19T18:15:30        # requires timezone=Asia/Singapore
2026-07-19                 # start/end of that date
1784474104                 # Unix seconds
1784474104000              # Unix milliseconds
```

`date` cannot be combined with `from`, `to`, or `last`. `last` cannot be combined with a lower bound but can be anchored with `to`.

### Sorting

`sort` aliases: `sort_by`, `orderby`.

```text
time
id
host
method
url
path
port
status
mime
listener_port
request_length
response_length
```

`order` aliases: `sort_order`, `direction`. Values: `asc`, `ascending`, `oldest`, `desc`, `descending`, `newest`.

Sorting is stable. When the primary values are equal, the Burp history ID is used as the tie-breaker in the same direction.

### Pagination

| Parameter | Meaning |
|---|---|
| `offset` | Zero-based result offset |
| `limit` | Page size |
| `page` | One-based page number; cannot be combined with `offset` |
| `page_size` | Alias for `limit` |
| `per_page` | Alias for `limit` |

JSON response metadata includes `total`, `page`, `pageSize`, `offset`, `returned`, `hasMore`, `nextOffset`, and `previousOffset`.

Response headers also include:

```text
X-Total-Count
X-Returned-Count
X-Page
X-Page-Size
X-Offset
X-Has-More
X-Sort
X-Sort-Order
X-Scanned-Count
X-Instance-Id
X-High-Watermark-Id
X-Next-Cursor
X-Redaction-Applied
```

### Incremental synchronization

Use `after_id=<id>` or `cursor=<opaque>` to retrieve entries in increasing ID order. These modes require `sort=id`, `order=asc`, and `offset=0`; the parser applies safe defaults when they are omitted. Use `before_id=<id>` for reverse synchronization in ID-descending order.

Every JSON page includes:

```json
{
  "sync": {
    "instanceId": "ephemeral-extension-uuid",
    "highWatermarkId": 1842,
    "minimumReturnedId": 501,
    "maximumReturnedId": 1000,
    "nextCursor": "opaque-value",
    "cursorCompatible": true
  }
}
```

Cursors are bound to `instanceId`. If Burp or the extension restarts, a stale cursor returns `409 cursor_instance_mismatch`; clients must discard it and start from a known ID or `after_id=0`.

### Optional structured fields

Use `include` or `expand`; comma-separate values:

```text
headers
request_headers
response_headers
cookies
request_cookies
response_cookies
query_parameters
body_preview
request_body_preview
response_body_preview
all
```

Body previews are UTF-8, may be lossy, and are limited to 2,048 characters per side. Use the item-specific raw or Base64 endpoints for complete data.

### Output formats

| `format` | Response |
|---|---|
| `json` | JSON page object; default |
| `ndjson` / `jsonl` | One history item JSON object per line; pagination metadata is in response headers |

### Examples

Newest API requests with session cookies:

```bash
curl --fail --show-error \
  -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/history?host=*.example.com&method=POST&path=/api/&cookie=session&status=2xx&last=2h&sort=time&order=desc&limit=100'
```

Filter by structured data and include it in the result:

```bash
curl --fail --show-error \
  -H "X-API-Key: $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/history?request_header=Authorization:Bearer&query_param=redirect_uri&response_body=access_token&include=headers,cookies,query_parameters&limit=50'
```

Use Unix milliseconds and NDJSON:

```bash
curl --fail --show-error \
  -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  'http://127.0.0.1:8090/api/v1/history?from=1784470000000&to=1784479999999&sort=id&order=asc&format=ndjson&limit=500'
```

Example JSON page:

```json
{
  "generatedAt": "2026-07-20T01:02:03Z",
  "total": 128,
  "page": 1,
  "pageSize": 50,
  "offset": 0,
  "limit": 50,
  "returned": 50,
  "hasMore": true,
  "nextOffset": 50,
  "previousOffset": null,
  "sort": "time",
  "order": "desc",
  "durationMs": 18.4,
  "filters": {
    "host": ["*.example.com"],
    "method": ["POST"],
    "cookie": [{"name": "session", "valueContains": null}],
    "from": "2026-07-19T00:00:00Z",
    "sort": "time",
    "order": "desc"
  },
  "items": [
    {
      "id": 412,
      "time": "2026-07-19T23:15:04+08:00[Asia/Singapore]",
      "timestamp": "2026-07-19T15:15:04Z",
      "timestampEpoch": 1784474104,
      "timestampEpochMillis": 1784474104000,
      "method": "POST",
      "url": "https://example.com/api/login?debug=true",
      "path": "/api/login?debug=true",
      "pathWithoutQuery": "/api/login",
      "query": "debug=true",
      "host": "example.com",
      "port": 443,
      "protocol": "https",
      "status": 200,
      "reasonPhrase": "OK",
      "mimeType": "JSON",
      "requestContentType": "application/json",
      "responseContentType": "application/json",
      "requestLength": 481,
      "responseLength": 1294,
      "requestBodyLength": 52,
      "responseBodyLength": 1140,
      "requestCookieCount": 1,
      "responseCookieCount": 2
    }
  ]
}
```

## `POST /api/v1/history/search`

This endpoint accepts `Content-Type: application/json` and maps a structured document to the same query engine as `GET /api/v1/history`. It is recommended for SDKs, large filter sets, and typed arrays.

```json
{
  "filters": {
    "host": ["*.example.com"],
    "method": ["POST", "PUT"],
    "status": ["2xx"],
    "request_header": ["Authorization"],
    "cookie": ["session"]
  },
  "search": {
    "keywords": ["access_token", "refresh_token"],
    "mode": "any",
    "location": "response",
    "caseSensitive": false,
    "regex": false
  },
  "sort": {"field": "id", "order": "asc"},
  "pagination": {"limit": 100},
  "output": {"format": "json", "include": ["headers", "cookies"]},
  "sync": {"afterId": 0}
}
```

The recognized top-level sections are `filters`, `search`, `sort`, `pagination`, `output`, and `sync`. Direct top-level query fields are also accepted. URL query parameters are merged with the body. The configured POST-body limit applies before JSON parsing.

## `GET /api/v1/events`

Authenticated Server-Sent Events stream for history entries whose IDs are greater than the supplied starting point.

Parameters:

| Parameter/header | Meaning |
|---|---|
| `after_id` | Exclusive starting history ID |
| `cursor` | Opaque instance-bound cursor |
| `Last-Event-ID` header | Numeric SSE reconnection ID |
| `timeout` | Stream lifetime in seconds, 1–3600; default 300 |
| `heartbeat` | Heartbeat interval in seconds, 5–60; default 15 |
| `limit` | Maximum items returned per polling batch |

Use only one of `after_id`, `cursor`, or `Last-Event-ID`.

```bash
curl -N --fail --show-error \
  -H "Authorization: Bearer $BURP_HISTORY_TOKEN" \
  -H 'Accept: text/event-stream' \
  'http://127.0.0.1:8090/api/v1/events?after_id=0&timeout=300&heartbeat=15'
```

Event types:

- `ready` — stream metadata and effective starting ID.
- `history` — one redacted history summary; the SSE `id` is the Burp history ID.
- `complete` — final cursor after normal stream timeout.
- Comment heartbeats keep intermediaries and clients aware that the connection is alive.

At most two event streams are connected by default. A saturated stream pool returns `429` with `Retry-After`.

## Redaction

When enabled, redaction applies before structured JSON, NDJSON, text/Base64 envelopes, body previews, and SSE summaries are emitted. Configurable names cover request/response headers and common sensitive parameter names; all structured cookie values are masked.

Exact `format=raw` endpoints bypass redaction by design and return:

```text
X-Redaction-Applied: false
X-Sensitive-Data: raw-unredacted
```

Redaction is best-effort and should not be treated as a data-loss-prevention boundary for arbitrary binary or custom-encoded content.

## Query safety limits

The settings tab controls maximum request-body size, page size, message size, scanned history entries, and query duration. Concurrent list/detail operations and live streams are bounded independently. A client disconnect closes the associated stream.

## `GET /api/v1/history/{id}`

Returns summary metadata, structured headers/cookies/body lengths for both sides, plus complete message envelopes.

Query parameter:

- `format=base64` — default, redacted bytes encoded in Base64 when redaction is enabled
- `format=text` — redacted UTF-8 text and potentially lossy

## `GET /api/v1/history/{id}/request`
## `GET /api/v1/history/{id}/response`

Query parameter:

- `format=raw` — default, exact **unredacted** bytes as `application/octet-stream`
- `format=base64` — redacted JSON envelope when redaction is enabled
- `format=text` — redacted JSON UTF-8 envelope when redaction is enabled

Messages above the configured maximum return `413` for raw access or an omitted envelope for item details.

## Errors

```json
{
  "error": {
    "code": "invalid_status",
    "message": "Invalid status filter"
  }
}
```

| Status | Meaning |
|---:|---|
| `400` | Invalid filter, range, time, format, or unknown parameter |
| `401` | Missing or incorrect token |
| `404` | Unknown history ID or unavailable request/response |
| `405` | Unsupported method |
| `409` | Cursor belongs to a different extension instance |
| `413` | Raw message or POST body exceeds the configured maximum |
| `415` | Unsupported POST content type or transfer encoding |
| `422` | Configured maximum scanned history entries exceeded |
| `429` | Concurrent query or event-stream slots are occupied |
| `503` | Configured query timeout exceeded; retry metadata may be present |
| `500` | Unexpected extension error |


## Production endpoints

### `GET /api/v1/events`

Requires `history:events`. The event stream is backed by a bounded, arrival-ordered ring populated by Montoya's Proxy response handler.

- `after_id` starts from a Burp history high-watermark.
- `cursor` resumes from the opaque event cursor returned by the stream.
- `Last-Event-ID` resumes from the monotonic SSE event sequence.
- `timeout` is 1–3600 seconds.
- `heartbeat` is 5–60 seconds.
- `limit` controls the maximum events emitted per broker batch.

A client that falls behind the retained ring receives `409 event_cursor_expired` and should perform an incremental history sync before reconnecting.

### `GET /api/v1/metrics`

Requires `metrics:read`.

```text
/api/v1/metrics
/api/v1/metrics?format=prometheus
```

### `GET /api/v1/audit`

Requires `audit:read`. `limit` is 1–2000. Audit entries include request metadata only and never contain API secrets, URL query strings, or Burp traffic.

### Exact raw messages

Requires `history:raw` and the **Enable exact raw access** project setting:

```text
GET /api/v1/history/{id}/request?format=raw
GET /api/v1/history/{id}/response?format=raw
```

Raw responses carry:

```text
X-Redaction-Applied: false
X-Sensitive-Data: raw-unredacted
```

## Operational status codes

| Status | Meaning |
|---|---|
| `401` | Missing or invalid token |
| `403` | Insufficient scope or raw access disabled |
| `409` | Instance-bound cursor mismatch or expired event position |
| `413` | Request/message exceeds configured size |
| `415` | POST search is not JSON |
| `422` | Configured history scan cap exceeded |
| `429` | Per-token rate limit or concurrency backpressure |
| `503` | Query deadline or worker capacity exhausted |
