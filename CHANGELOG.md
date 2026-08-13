# Changelog

## 1.4.1 — 2026-07-21

- Added a Runtime-tab selector for active local IPv4 and IPv6 interface addresses.
- Added a separate confirmation-protected **Allow all interfaces** checkbox for wildcard binding.
- Preserved loopback-only binding as the default and fail-closed validation for unavailable or non-local selected addresses.
- Added network-exposure warnings, listener diagnostics, and health/capabilities metadata for bind address, wildcard state, and exposure state.
- Updated OpenAPI generation, dashboard status, security guidance, production acceptance checks, and binding self-tests.

## 1.4.0 — 2026-07-20

- Added project-scoped multi-client tokens with salted-hash persistence, permission scopes, and a reported user-preferences fallback when project storage is unavailable.
- Added event-driven Montoya response capture and a bounded resumable SSE ring.
- Added rotating metadata-only audit logging and audit query endpoint.
- Added JSON and Prometheus metrics.
- Added per-token rate limits, configurable worker/query/event concurrency and bounded queues.
- Disabled exact raw endpoints by default and separated raw authorization.
- Hardened HTTP parsing against request smuggling and added chunked JSON support.
- Moved active settings into project persistence with preference fallback.
- Added reproducible builds, checksum-verified Gradle bootstrap and CI.
- Expanded integration, concurrency, persistence, event-ordering, scan-cap and parser-hardening tests to 117 assertions.

## 1.3.0 — 2026-07-20

- Added incremental synchronization with `after_id`, `before_id`, opaque cursors, instance IDs, high-watermark IDs, and pagination headers.
- Added structured `POST /api/v1/history/search` with nested filter, search, sort, pagination, output, and sync sections.
- Added configurable sensitive-data redaction for structured output, text/Base64 envelopes, previews, and SSE summaries.
- Preserved exact unredacted bytes for `format=raw` and added explicit sensitivity headers.
- Added configurable request-body, history-scan, query-timeout, query-concurrency, and event-stream limits.
- Added authenticated resumable Server-Sent Events with heartbeats, `Last-Event-ID`, cursors, and bounded connections.
- Expanded dashboard, settings UI, health/capabilities metadata, OpenAPI, examples, and integration tests.

## 1.2.1 — 2026-07-20

- Added author attribution throughout Java source, examples, dashboard, Burp settings UI, README, API documentation, OpenAPI metadata, JAR manifest, health response, and capabilities response.
- Added project ownership constants for Supakiad S. (m3ez) and E-CQURITY (Thailand).
- Corrected stale JAR filenames in the README.
- No REST query behavior or endpoint compatibility changes.

## 1.2.0 — 2026-07-20

- Replaced `com.sun.net.httpserver.HttpServer` with a self-contained loopback HTTP/1.1 server to work inside Burp's extension classloader.
- Added URL, query-string, query-parameter, reason-phrase, ID/range, and request/response size filters.
- Added request/response/any header, content-type, cookie, and body filters.
- Added ISO-8601, local date/time plus timezone, Unix second/millisecond, date, and relative `last` time filtering.
- Added stable sorting aliases, page-based pagination, JSON Lines output, pagination headers, and `/api/v1/capabilities`.
- Added optional structured headers, cookies, query parameters, and body previews in history-list responses.
- Added structured request/response metadata to single-item responses.
- Expanded dashboard filters, OpenAPI documentation, clients, and integration tests.

## 1.0.0 — 2026-07-19

- Initial read-only Montoya extension.
- Added authenticated localhost REST listener.
- Added history filtering, literal/optional regex search, sorting, and pagination.
- Added metadata, Base64/text envelopes, and exact raw message downloads.
- Added Burp configuration/status tab.
- Added self-contained browser dashboard and OpenAPI 3.1 document.
- Added dependency-free integration self-tests and example clients.