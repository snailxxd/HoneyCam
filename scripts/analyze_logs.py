#!/usr/bin/env python3
"""
Analyze HoneyCam JSONL logs and export deception metrics.

Outputs:
  - metrics-summary.json: conversion rate + dwell stats
  - dwell-time-cdf.csv: empirical CDF points for dwell time
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


def parse_iso(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone(timezone.utc)


def read_jsonl(path: Path) -> Iterable[dict]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def collect_interactions(log_dir: Path) -> List[dict]:
    rows: List[dict] = []
    for file in sorted(log_dir.glob("interactions-*.jsonl")):
        rows.extend(read_jsonl(file))
    return rows


def collect_login_attempts(log_dir: Path) -> List[dict]:
    rows: List[dict] = []
    for file in sorted(log_dir.glob("login-attempts-*.jsonl")):
        rows.extend(read_jsonl(file))
    return rows


def build_metrics(interactions: List[dict], login_attempts: List[dict]) -> Tuple[dict, List[Tuple[float, float]]]:
    scans_by_ip = defaultdict(int)
    for row in login_attempts:
        ip = row.get("ipAddress")
        if ip:
            scans_by_ip[ip] += 1

    session_start: Dict[str, datetime] = {}
    dwell_seconds: List[float] = []
    interacted_ips = set()

    for row in interactions:
        action = row.get("actionType")
        session_id = row.get("sessionId")
        ip = row.get("ipAddress")
        ts = row.get("timestamp")
        if action in {"PAN", "TILT", "ZOOM", "DRAG", "CLICK"} and ip:
            interacted_ips.add(ip)

        if not session_id or not ts:
            continue

        try:
            parsed = parse_iso(ts)
        except ValueError:
            continue

        if action == "SESSION_START":
            session_start[session_id] = parsed
        elif action == "SESSION_END" and session_id in session_start:
            seconds = (parsed - session_start[session_id]).total_seconds()
            if seconds >= 0:
                dwell_seconds.append(seconds)

    scanned_ips = set(scans_by_ip.keys())
    conversion_rate = (len(interacted_ips) / len(scanned_ips)) if scanned_ips else 0.0
    mean_dwell = (sum(dwell_seconds) / len(dwell_seconds)) if dwell_seconds else 0.0

    summary = {
        "scan_ip_count": len(scanned_ips),
        "interaction_ip_count": len(interacted_ips),
        "conversion_rate": round(conversion_rate, 4),
        "session_count_with_dwell": len(dwell_seconds),
        "avg_dwell_seconds": round(mean_dwell, 2),
    }

    cdf_points: List[Tuple[float, float]] = []
    if dwell_seconds:
        sorted_vals = sorted(dwell_seconds)
        n = len(sorted_vals)
        for i, value in enumerate(sorted_vals, start=1):
            cdf_points.append((value, i / n))

    return summary, cdf_points


def write_outputs(output_dir: Path, summary: dict, cdf_points: List[Tuple[float, float]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = output_dir / "metrics-summary.json"
    cdf_path = output_dir / "dwell-time-cdf.csv"

    with summary_path.open("w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    with cdf_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["dwell_seconds", "cdf"])
        writer.writerows(cdf_points)


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze HoneyCam deception logs.")
    parser.add_argument("--log-dir", default="logs", help="Path to HoneyCam logs directory.")
    parser.add_argument("--out-dir", default="analysis-output", help="Directory for analysis artifacts.")
    args = parser.parse_args()

    log_dir = Path(args.log_dir)
    out_dir = Path(args.out_dir)

    interactions = collect_interactions(log_dir)
    login_attempts = collect_login_attempts(log_dir)
    summary, cdf_points = build_metrics(interactions, login_attempts)
    write_outputs(out_dir, summary, cdf_points)

    print(f"Written: {out_dir / 'metrics-summary.json'}")
    print(f"Written: {out_dir / 'dwell-time-cdf.csv'}")
    print(f"Summary: {summary}")


if __name__ == "__main__":
    main()
