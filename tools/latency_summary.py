"""Print the percentiles of a latency harness report as a Markdown table.

CI's latency job runs this after the short run and appends the output to the job summary
($GITHUB_STEP_SUMMARY), so the numbers can be read on the run page without downloading the
artifact. The table has the same columns as the results table in docs/BENCHMARKS.md.

Usage: python tools/latency_summary.py rates-bench/target/latency/latency-*.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SERIES = (("decodeToEncode", "core decode-to-encode"), ("scheduledToEncode", "scheduled-to-encode"))


def table(report: dict) -> str:
    machine = report.get("machine", {})
    config = report.get("config", {})
    counts = report.get("counts", {})
    lines = [
        f"### Latency report `{report.get('label', '')}` at commit `{report.get('commit', '')}`",
        "",
        f"Started {report.get('started', '')}; {machine.get('cpu', '?')}, {machine.get('logicalCpus', '?')} "
        f"logical CPUs, {machine.get('os', '?')}; JVM {report.get('jvm', {}).get('version', '?')}.",
        f"Rate {config.get('rate', '?')}/s, warm-up {config.get('warmupSeconds', '?')} s, measured "
        f"{config.get('measureSeconds', '?')} s, idle {config.get('idle', '?')}, journal {config.get('journal', '?')}; "
        f"GC collections {counts.get('gcCollections', '?')}, allocation delta "
        f"{report.get('allocationDeltaBytes', '?')} bytes.",
        "",
        "| Interval | Samples | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) |",
        "|---|---|---|---|---|---|---|",
    ]
    for key, name in SERIES:
        h = report.get(key)
        if not h:
            continue
        p9999 = f"{h['p9999Us']:.3f}" if h.get("p9999Supported") else "n/a"
        lines.append(
            f"| {name} | {h['samples']} | {h['p50Us']:.3f} | {h['p99Us']:.3f} | {h['p999Us']:.3f} | {p9999} "
            f"| {h['maxUs']:.3f} |"
        )
    return "\n".join(lines) + "\n"


def main(paths: list[str]) -> int:
    if not paths:
        print("no latency report found")
        return 1
    for path in paths:
        print(table(json.loads(Path(path).read_text(encoding="utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
