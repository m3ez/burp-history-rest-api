# Production acceptance checklist

Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)  
Profile: http://x.com/supakiad_mee

## Before deployment

- Use a current Burp Suite build compatible with Montoya API 2026.4 and Java 21.
- Verify the JAR against `burp-history-rest-api-1.4.1-SHA256SUMS.txt`.
- Load the extension from **Extensions → Installed** and inspect the Output and Errors tabs.
- Confirm `/api/v1/health` reports `status=ok`, the intended `bindAddress`, `allowAllInterfaces` value, `networkExposed` state, token storage mode, and raw access disabled.
- Create one token per external client with the minimum scopes required.
- Keep redaction enabled and configure additional organization-specific secret names.
- Store the rotating audit log on an encrypted local volume.

## Acceptance test

- Exercise loopback mode, one selected non-loopback interface when used, interface refresh after adapter changes, and wildcard mode only when deployment policy requires it.
- Verify an unavailable saved address fails closed instead of silently falling back to another interface.


```bash
export BURP_HISTORY_TOKEN='token-with-read-events-metrics'
./scripts/production-smoke-test.sh
python3 scripts/load-test.py --token "$BURP_HISTORY_TOKEN" --requests 1000 --concurrency 8
```

Test representative Proxy traffic: HTTP/1.1, HTTP/2, JSON, XML, multipart, compressed responses, binary bodies, large messages, requests without responses, and edited history entries.

## Capacity test

- Import or generate a project with the expected maximum history size.
- Run common filtered queries and record `/api/v1/metrics` latency/error behavior.
- Verify scan caps return controlled `422` responses rather than destabilizing Burp.
- Size the event ring for peak traffic multiplied by the maximum expected consumer outage.
- Disconnect an SSE consumer until the ring rolls over and confirm recovery through incremental history sync.
- Run a multi-hour soak while monitoring Burp heap, extension errors, audit rotation, active queries, dropped events, and rate limiting.

## Security test

- Verify invalid, expired, and revoked tokens return `401`.
- Verify a read token receives `403` from exact raw endpoints, metrics, and audit endpoints when scopes are absent.
- Verify raw endpoints remain unavailable until both `history:raw` and the project raw-access switch are enabled.
- Confirm redacted JSON, NDJSON, text, Base64, previews, SSE, logs, and errors contain no test secrets.
- Confirm exact raw byte output matches Burp only for explicitly authorized raw clients.
- Confirm reachability exactly matches the selected interface. When wildcard mode is enabled, verify host-firewall rules block every unauthorized source network.
- Confirm remote traffic uses a trusted private network, VPN, SSH tunnel, or TLS-capable reverse proxy; the embedded listener is HTTP.

## Operations

- Review and rotate client tokens on a defined schedule.
- Monitor audit-log disk usage and rotation.
- Back up project settings according to organizational policy, but never copy plaintext token secrets into a Burp project note.
- Stop the API and revoke affected tokens immediately if raw traffic access is suspected to be compromised.
