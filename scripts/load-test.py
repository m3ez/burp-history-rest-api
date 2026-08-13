#!/usr/bin/env python3
"""Bounded local load test for Burp History REST API.

Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
Profile: http://x.com/supakiad_mee
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.error
import urllib.request
from collections import Counter


def one(url: str, token: str, timeout: float) -> tuple[int, float]:
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        method="GET",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        error.read()
        status = error.code
    return status, (time.perf_counter() - started) * 1000.0


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--token", required=True)
    parser.add_argument("--requests", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()
    if not 1 <= args.requests <= 1_000_000:
        parser.error("--requests must be between 1 and 1000000")
    if not 1 <= args.concurrency <= 128:
        parser.error("--concurrency must be between 1 and 128")

    url = args.base_url.rstrip("/") + "/api/v1/history?limit=1&sort=id&order=desc"
    started = time.perf_counter()
    results: list[tuple[int, float]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(one, url, args.token, args.timeout) for _ in range(args.requests)]
        for future in concurrent.futures.as_completed(futures):
            try:
                results.append(future.result())
            except Exception:
                results.append((0, 0.0))

    elapsed = time.perf_counter() - started
    statuses = Counter(status for status, _ in results)
    latencies = [latency for status, latency in results if status > 0]
    report = {
        "requests": args.requests,
        "concurrency": args.concurrency,
        "elapsedSeconds": round(elapsed, 3),
        "requestsPerSecond": round(args.requests / elapsed, 2) if elapsed else 0,
        "statuses": dict(sorted(statuses.items())),
        "latencyMs": {
            "mean": round(statistics.fmean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if statuses.get(200, 0) == args.requests else 1


if __name__ == "__main__":
    raise SystemExit(main())
