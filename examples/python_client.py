#!/usr/bin/env python3
# Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
# Profile: http://x.com/supakiad_mee
"""Standard-library client and CLI for Burp History REST API 1.4.1."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable, Mapping


class BurpHistoryError(RuntimeError):
    def __init__(self, status: int | None, message: str, body: Any = None) -> None:
        super().__init__(message)
        self.status = status
        self.body = body


@dataclass(frozen=True)
class BurpHistoryClient:
    base_url: str = "http://127.0.0.1:8090"
    token: str | None = None
    timeout: float = 30.0

    def health(self) -> dict[str, Any]:
        return self._json("/api/v1/health", authenticated=False)

    def capabilities(self) -> dict[str, Any]:
        return self._json("/api/v1/capabilities", authenticated=False)

    def search(self, document: Mapping[str, Any]) -> dict[str, Any]:
        return self._json_request("/api/v1/history/search", document)

    def history(self, **filters: Any) -> dict[str, Any]:
        filters["format"] = "json"
        return self._json("/api/v1/history" + self._query(filters))

    def history_ndjson(self, **filters: Any) -> list[dict[str, Any]]:
        filters["format"] = "ndjson"
        raw = self._request("/api/v1/history" + self._query(filters))
        result: list[dict[str, Any]] = []
        for line in raw.splitlines():
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise BurpHistoryError(None, "Unexpected NDJSON value", value)
                result.append(value)
        return result

    def details(self, history_id: int, message_format: str = "base64") -> dict[str, Any]:
        query = urllib.parse.urlencode({"format": message_format})
        return self._json(f"/api/v1/history/{history_id}?{query}")

    def message_json(self, history_id: int, side: str, message_format: str) -> dict[str, Any]:
        self._validate_side(side)
        if message_format not in {"base64", "text"}:
            raise ValueError("message_format must be base64 or text")
        query = urllib.parse.urlencode({"format": message_format})
        return self._json(f"/api/v1/history/{history_id}/{side}?{query}")

    def raw_message(self, history_id: int, side: str) -> bytes:
        self._validate_side(side)
        return self._request(f"/api/v1/history/{history_id}/{side}?format=raw")

    @staticmethod
    def _query(filters: Mapping[str, Any]) -> str:
        pairs: list[tuple[str, str]] = []
        for key, value in filters.items():
            if value is None or value == "" or value == []:
                continue
            if isinstance(value, bool):
                pairs.append((key, str(value).lower()))
            elif isinstance(value, (list, tuple, set)):
                pairs.extend((key, str(item)) for item in value)
            else:
                pairs.append((key, str(value)))
        query = urllib.parse.urlencode(pairs, doseq=True)
        return "?" + query if query else ""

    def _json(self, path: str, authenticated: bool = True) -> dict[str, Any]:
        raw = self._request(path, authenticated=authenticated)
        try:
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise BurpHistoryError(None, "Server returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise BurpHistoryError(None, "Server returned an unexpected JSON value", value)
        return value

    def _json_request(self, path: str, document: Mapping[str, Any]) -> dict[str, Any]:
        raw = self._request(
            path,
            method="POST",
            body=json.dumps(document).encode("utf-8"),
            content_type="application/json",
        )
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise BurpHistoryError(None, "Server returned an unexpected JSON value", value)
        return value

    def event_stream_url(self, **parameters: Any) -> str:
        return self.base_url.rstrip("/") + "/api/v1/events" + self._query(parameters)

    def _request(
        self,
        path: str,
        authenticated: bool = True,
        method: str = "GET",
        body: bytes | None = None,
        content_type: str | None = None,
    ) -> bytes:
        url = self.base_url.rstrip("/") + path
        headers = {"Accept": "application/json, application/x-ndjson"}
        if authenticated:
            if not self.token:
                raise BurpHistoryError(None, "A bearer token is required")
            headers["Authorization"] = f"Bearer {self.token}"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, headers=headers, data=body, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            body: Any = None
            try:
                body = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError):
                pass
            message = (
                body.get("error", {}).get("message")
                if isinstance(body, dict)
                else None
            ) or f"HTTP {exc.code}: {exc.reason}"
            raise BurpHistoryError(exc.code, message, body) from exc
        except urllib.error.URLError as exc:
            raise BurpHistoryError(None, f"Unable to connect to {url}: {exc.reason}") from exc

    @staticmethod
    def _validate_side(side: str) -> None:
        if side not in {"request", "response"}:
            raise ValueError("side must be request or response")


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--base-url",
        default=os.environ.get("BURP_HISTORY_BASE_URL", "http://127.0.0.1:8090"),
        help="API base URL (default: %(default)s)",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("BURP_HISTORY_TOKEN"),
        help="Bearer token (default: BURP_HISTORY_TOKEN)",
    )
    parser.add_argument("--timeout", type=float, default=30.0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    add_common(parser)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("health", help="Show listener health")
    commands.add_parser("capabilities", help="Show supported filters and formats")

    listing = commands.add_parser("list", help="List filtered Proxy history")
    listing.add_argument("--id", action="append")
    listing.add_argument("--id-from", type=int)
    listing.add_argument("--id-to", type=int)
    listing.add_argument("--after-id", type=int)
    listing.add_argument("--before-id", type=int)
    listing.add_argument("--cursor")
    listing.add_argument("--host", action="append")
    listing.add_argument("--method", action="append")
    listing.add_argument("--url")
    listing.add_argument("--path")
    listing.add_argument("--query-string")
    listing.add_argument("--query-param", action="append")
    listing.add_argument("--port", type=int)
    listing.add_argument("--protocol", choices=["http", "https"])
    listing.add_argument("--status", action="append")
    listing.add_argument("--reason")
    listing.add_argument("--mime", action="append")
    listing.add_argument("--extension", action="append")
    listing.add_argument("--listener-port", type=int)
    listing.add_argument("--request-header", action="append")
    listing.add_argument("--response-header", action="append")
    listing.add_argument("--header", action="append")
    listing.add_argument("--request-cookie", action="append")
    listing.add_argument("--response-cookie", action="append")
    listing.add_argument("--cookie", action="append")
    listing.add_argument("--request-body", action="append")
    listing.add_argument("--response-body", action="append")
    listing.add_argument("--body", action="append")
    listing.add_argument("--request-length-min", type=int)
    listing.add_argument("--request-length-max", type=int)
    listing.add_argument("--response-length-min", type=int)
    listing.add_argument("--response-length-max", type=int)
    listing.add_argument("--search", dest="q")
    listing.add_argument(
        "--search-in",
        choices=["any", "both", "request", "response", "metadata", "url", "headers", "cookies", "body", "query"],
        default="any",
    )
    listing.add_argument("--keyword-mode", choices=["any", "all"], default="any")
    listing.add_argument("--case-sensitive", action="store_true")
    listing.add_argument("--regex", action="store_true")
    listing.add_argument("--in-scope", choices=["true", "false"])
    listing.add_argument("--has-response", choices=["true", "false"])
    listing.add_argument("--date")
    listing.add_argument("--last")
    listing.add_argument("--from", dest="from_time")
    listing.add_argument("--to", dest="to_time")
    listing.add_argument("--timezone", default="UTC")
    listing.add_argument(
        "--sort",
        choices=["time", "id", "host", "method", "url", "path", "port", "status", "mime", "listener_port", "request_length", "response_length"],
        default="time",
    )
    listing.add_argument("--order", choices=["asc", "desc"], default="desc")
    listing.add_argument("--include", action="append")
    listing.add_argument("--format", choices=["json", "ndjson"], default="json")
    listing.add_argument("--page", type=int)
    listing.add_argument("--offset", type=int)
    listing.add_argument("--limit", type=int, default=100)

    details = commands.add_parser("get", help="Get one history item")
    details.add_argument("id", type=int)
    details.add_argument("--format", choices=["base64", "text"], default="text")

    raw = commands.add_parser("raw", help="Download an exact request or response")
    raw.add_argument("id", type=int)
    raw.add_argument("side", choices=["request", "response"])
    raw.add_argument("--output", type=pathlib.Path, required=True)

    return parser


def print_json(value: Any) -> None:
    json.dump(value, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")


def list_filters(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "id": args.id,
        "id_from": args.id_from,
        "id_to": args.id_to,
        "after_id": args.after_id,
        "before_id": args.before_id,
        "cursor": args.cursor,
        "host": args.host,
        "method": args.method,
        "url": args.url,
        "path": args.path,
        "query_string": args.query_string,
        "query_param": args.query_param,
        "port": args.port,
        "protocol": args.protocol,
        "status": args.status,
        "reason": args.reason,
        "mime": args.mime,
        "extension": args.extension,
        "listener_port": args.listener_port,
        "request_header": args.request_header,
        "response_header": args.response_header,
        "header": args.header,
        "request_cookie": args.request_cookie,
        "response_cookie": args.response_cookie,
        "cookie": args.cookie,
        "request_body": args.request_body,
        "response_body": args.response_body,
        "body": args.body,
        "request_length_min": args.request_length_min,
        "request_length_max": args.request_length_max,
        "response_length_min": args.response_length_min,
        "response_length_max": args.response_length_max,
        "q": args.q,
        "search_in": args.search_in,
        "keyword_mode": args.keyword_mode,
        "case_sensitive": args.case_sensitive,
        "regex": args.regex,
        "in_scope": args.in_scope,
        "has_response": args.has_response,
        "date": args.date,
        "last": args.last,
        "from": args.from_time,
        "to": args.to_time,
        "timezone": args.timezone,
        "sort": args.sort,
        "order": args.order,
        "include": args.include,
        "page": args.page,
        "offset": args.offset,
        "limit": args.limit,
    }


def main(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    client = BurpHistoryClient(args.base_url, args.token, args.timeout)
    try:
        if args.command == "health":
            print_json(client.health())
        elif args.command == "capabilities":
            print_json(client.capabilities())
        elif args.command == "list":
            filters = list_filters(args)
            print_json(client.history_ndjson(**filters) if args.format == "ndjson" else client.history(**filters))
        elif args.command == "get":
            print_json(client.details(args.id, args.format))
        elif args.command == "raw":
            data = client.raw_message(args.id, args.side)
            args.output.write_bytes(data)
            print(f"Wrote {len(data)} bytes to {args.output}")
        else:
            raise AssertionError(f"Unhandled command: {args.command}")
        return 0
    except (BurpHistoryError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
