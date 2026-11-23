"""Print the percentiles of latency reports as Markdown tables.

CI's latency job runs this after the short runs and appends the output to the job summary
($GITHUB_STEP_SUMMARY), so the numbers can be read on the run page without downloading the
artifact. Two report kinds are understood:

- the event-core harness (schema rates-bench-latency/1), with the same columns as the results table
  in docs/BENCHMARKS.md;
- the wired-desk benchmark (schema rates-bench-wired/1, docs/BENCHMARKS_V2.md): one row per
  configuration for the headline (scheduled send to quote written) with the generator's lateness
  beside it, then one table per configuration with every stage.

Every report given is printed; nothing is dropped for generator lateness or anything else.

Usage: python tools/latency_summary.py rates-bench/target/latency/latency-*.json
       python tools/latency_summary.py rates-bench/target/wired/wired-*.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SERIES = (("decodeToEncode", "core decode-to-encode"), ("scheduledToEncode", "scheduled-to-encode"))

WIRED_STAGES = (
    ("scheduledToQuoteWritten", "scheduled send to quote written (T0 to T4), headline"),
    ("scheduledToQuoteReceived", "scheduled send to quote read by the generator (T0 to T5)"),
    ("generatorLateness", "generator lateness (write returned minus T0)"),
    ("wireInToPublished", "request written to published on the loop's ring"),
    ("ringWait", "inbound ring wait"),
    ("journalAppend", "inbound journal append"),
    ("handler", "handler (pricing, risk, quoting, publish)"),
    ("outboundRingWait", "outbound ring wait"),
    ("encode", "FIX encode"),
    ("finishAndSocketWrite", "FIX finish and socket write"),
    ("wireOut", "quote written to read by the generator"),
)


def _fmt(h: dict, key: str) -> str:
    return f"{h[key]:.1f}" if key in h else "n/a"


def _p9999(h: dict) -> str:
    if "p9999Us" not in h:
        return "n/a"
    value = f"{h['p9999Us']:.1f}"
    return value if h.get("p9999Supported") else value + " (few samples)"


def table(report: dict) -> str:
    machine = report.get("machine", {})
    config = report.get("config", {})
    counts = report.get("counts", {})
    host = (
        f"Started {report.get('started', '')}; {machine.get('cpu', '?')}, {machine.get('logicalCpus', '?')} "
        f"logical CPUs, {machine.get('os', '?')}; JVM {report.get('jvm', {}).get('version', '?')}."
    )
    run = (
        f"Rate {config.get('rate', '?')}/s, warm-up {config.get('warmupSeconds', '?')} s, measured "
        f"{config.get('measureSeconds', '?')} s, idle {config.get('idle', '?')}, journal {config.get('journal', '?')}; "
        f"GC collections {counts.get('gcCollections', '?')}, allocation delta "
        f"{report.get('allocationDeltaBytes', '?')} bytes."
    )
    lines = [
        f"### Latency report `{report.get('label', '')}` at commit `{report.get('commit', '')}`",
        "",
        host,
        run,
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


def wired_headline(reports: list[dict]) -> str:
    """One row per configuration: the headline interval and the generator's lateness."""
    first = reports[0]
    machine = first.get("machine", {})
    jvm = first.get("jvm", {})
    lines = [
        f"### Wired desk over loopback FIX, `{first.get('label', '')}` at commit `{first.get('commit', '')}`",
        "",
        f"{machine.get('cpu', '?')}, {machine.get('logicalCpus', '?')} logical CPUs, {machine.get('os', '?')}; "
        f"JVM {jvm.get('version', '?')}; clock resolution {first.get('clockResolutionNs', '?')} ns. "
        "In-process plus loopback: no NIC, no kernel bypass, no real venue. Every run is listed.",
        "",
        f"JVM flags: `{' '.join(jvm.get('flags', []))}`",
        "",
        "| Configuration | Requests | Answered | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) "
        "| Lateness p99 (us) | Lateness max (us) | GCs | GC ms |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in reports:
        h = r.get("scheduledToQuoteWritten", {})
        late = r.get("generatorLateness", {})
        counts = r.get("counts", {})
        lines.append(
            f"| {r.get('configuration', '?')}{'' if r.get('label') == first.get('label') else ' (' + r.get('label', '') + ')'} "
            f"| {counts.get('requestsMeasured', '?')} | {counts.get('answered', '?')} "
            f"| {_fmt(h, 'p50Us')} | {_fmt(h, 'p99Us')} | {_fmt(h, 'p999Us')} | {_p9999(h)} | {_fmt(h, 'maxUs')} "
            f"| {_fmt(late, 'p99Us')} | {_fmt(late, 'maxUs')} "
            f"| {counts.get('gcCollectionsMeasured', '?')} | {counts.get('gcMillisMeasured', '?')} |"
        )
    return "\n".join(lines) + "\n"


def wired_stages(report: dict) -> str:
    """Every stage of one configuration."""
    lines = [
        f"#### {report.get('configuration', '?')} ({report.get('label', '')}): stages",
        "",
        "| Interval | Samples | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) |",
        "|---|---|---|---|---|---|---|",
    ]
    for key, name in WIRED_STAGES:
        h = report.get(key)
        if not h or not h.get("samples"):
            continue
        lines.append(
            f"| {name} | {h['samples']} | {_fmt(h, 'p50Us')} | {_fmt(h, 'p99Us')} | {_fmt(h, 'p999Us')} "
            f"| {_p9999(h)} | {_fmt(h, 'maxUs')} |"
        )
    counts = report.get("counts", {})
    extra = ", ".join(f"{k} {v}" for k, v in counts.items())
    return "\n".join(lines) + "\n\n" + extra + "\n"


def main(paths: list[str]) -> int:
    if not paths:
        print("no latency report found")
        return 1
    core: list[dict] = []
    wired: list[dict] = []
    for path in sorted(paths):
        report = json.loads(Path(path).read_text(encoding="utf-8"))
        (wired if report.get("schema") == "rates-bench-wired/1" else core).append(report)
    for report in core:
        print(table(report))
    if wired:
        print(wired_headline(wired))
        for report in wired:
            print(wired_stages(report))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
