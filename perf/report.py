"""Summarize a JMeter CSV JTL. Run: python perf/report.py result.jtl"""
import csv
import sys
from collections import Counter
from pathlib import Path
rows = list(csv.DictReader(Path(sys.argv[1]).open(encoding="utf-8-sig")))
if not rows:
    raise SystemExit("No samples")
counts = Counter(r["responseCode"] for r in rows)
elapsed = [int(r["elapsed"]) for r in rows]
seconds = (max(int(r["timeStamp"]) + int(r["elapsed"]) for r in rows) - min(int(r["timeStamp"]) for r in rows)) / 1000
success = sum(r["responseCode"] == "201" and r["success"] == "true" for r in rows)
print(f"Requests: {len(rows)}; duration: {seconds:.3f}s")
print(f"HTTP QPS: {len(rows)/seconds:.2f}; order QPS: {success/seconds:.2f}")
print(f"Average: {sum(elapsed)/len(rows):.2f}ms; max: {max(elapsed)}ms")
print(f"Successful orders: {success}; sold out: {counts['409']}; other failures: {len(rows)-success-counts['409']}")
print(f"HTTP status counts: {dict(counts)}")
