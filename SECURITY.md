# Security model

The extension exposes potentially sensitive Proxy history. Its production defaults are deliberately restrictive.

## Defaults

- Listener: selected interface address `127.0.0.1`
- Allow all interfaces: disabled
- CORS: not enabled
- Structured redaction: enabled
- Exact raw access: disabled
- Audit logging: enabled
- Write/replay actions: not implemented

## Token security

Tokens are project-scoped when Burp project persistence is available; otherwise the extension reports and uses a user-preferences fallback. Tokens are individually revocable and stored only as salted SHA-256 hashes. Secrets are shown once. Use one token per client and least-privilege scopes.

`history:raw` bypasses redaction and can expose passwords, cookies, authorization headers, tokens and customer data. Keep it disabled unless required.

## Audit log

The JSONL log contains request ID, timestamp, client address, principal ID/label, method, path, status, duration, response size and whether raw data was returned. It intentionally excludes token values, URL query strings and Burp HTTP message contents.

## Network exposure

The Runtime tab can bind to one detected local IPv4/IPv6 address. A separate, confirmation-protected checkbox enables wildcard binding to all interfaces. Both a selected non-loopback address and wildcard mode are reported as `networkExposed=true` by the health and capabilities endpoints.

The embedded server provides HTTP, not HTTPS. Do not expose it directly to the public internet. For LAN or remote clients:

- Restrict source addresses with the host firewall.
- Create one least-privilege token per client and set expiry where appropriate.
- Keep redaction enabled and raw access disabled unless required.
- Use a trusted private network, VPN, SSH tunnel, or TLS-capable reverse proxy.

Local forwarding remains the preferred option:

```bash
ssh -L 8090:127.0.0.1:8090 user@burp-host
```

## Incident response

1. Stop the API from the **History REST** tab.
2. Revoke affected client tokens.
3. Review the rotating audit log and Burp extension errors.
4. Rotate credentials observed in any raw traffic that may have been accessed.
5. Restart with raw access disabled and create replacement scoped tokens.
