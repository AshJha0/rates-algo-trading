"""Lint the Grafana dashboards and the Prometheus alert rules.

Checks, for every deploy/grafana/dashboards/*.json:
  - valid JSON with uid, title, schemaVersion and a non-empty panels list;
  - unique panel ids, each panel with a title, a type, a datasource uid and at least one target
    with an expression;
  - every metric an expression names is in deploy/prometheus/metrics.txt.
And for deploy/prometheus/alerts.yml:
  - each rule has alert, expr, a severity label, a summary and a runbook that exists;
  - every metric it names is in the catalogue (Prometheus's own `up` is allowed).

Run in CI by .github/workflows/ops.yml. Usage: python tools/lint_dashboards.py [repo root]
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

import yaml

METRIC = re.compile(r"\brates_[a-z0-9_]+")
SUFFIXES = ("_bucket", "_sum", "_count")
BUILTIN = {"up"}


def catalogue(root: Path) -> set[str]:
    lines = (root / "deploy" / "prometheus" / "metrics.txt").read_text(encoding="utf-8").splitlines()
    return {line.strip() for line in lines if line.strip() and not line.startswith("#")}


def unknown_metrics(expr: str, known: set[str]) -> list[str]:
    out = []
    for name in METRIC.findall(expr):
        base = name
        for suffix in SUFFIXES:
            if base.endswith(suffix) and base[: -len(suffix)] in known:
                base = base[: -len(suffix)]
        if base not in known:
            out.append(name)
    return out


def lint_dashboard(path: Path, known: set[str]) -> list[str]:
    errors: list[str] = []
    try:
        board: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return [f"{path.name}: not valid JSON: {e}"]
    for key in ("uid", "title", "schemaVersion", "panels"):
        if key not in board:
            errors.append(f"{path.name}: missing {key}")
    panels = board.get("panels") or []
    if not panels:
        errors.append(f"{path.name}: no panels")
    seen: set[int] = set()
    for p in panels:
        pid = p.get("id")
        where = f"{path.name} panel {pid}"
        if pid in seen:
            errors.append(f"{where}: duplicate id")
        seen.add(pid)
        for key in ("title", "type"):
            if not p.get(key):
                errors.append(f"{where}: missing {key}")
        if not (p.get("datasource") or {}).get("uid"):
            errors.append(f"{where}: datasource uid missing")
        targets = p.get("targets") or []
        if not targets:
            errors.append(f"{where}: no targets")
        for t in targets:
            expr = t.get("expr", "")
            if not expr:
                errors.append(f"{where}: target without expr")
            for name in unknown_metrics(expr, known):
                errors.append(f"{where}: unknown metric {name}")
    return errors


def lint_alerts(root: Path, known: set[str]) -> list[str]:
    path = root / "deploy" / "prometheus" / "alerts.yml"
    errors: list[str] = []
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    rules = [r for g in doc.get("groups", []) for r in g.get("rules", [])]
    if not rules:
        errors.append("alerts.yml: no rules")
    for r in rules:
        name = r.get("alert", "<unnamed>")
        if "alert" not in r or "expr" not in r:
            errors.append(f"alerts.yml {name}: alert and expr are required")
        if (r.get("labels") or {}).get("severity") not in {"page", "warn"}:
            errors.append(f"alerts.yml {name}: severity must be page or warn")
        ann = r.get("annotations") or {}
        if not ann.get("summary"):
            errors.append(f"alerts.yml {name}: summary missing")
        runbook = ann.get("runbook", "")
        if not runbook or not (root / runbook).is_file():
            errors.append(f"alerts.yml {name}: runbook '{runbook}' does not exist")
        for metric in unknown_metrics(str(r.get("expr", "")), known | BUILTIN):
            errors.append(f"alerts.yml {name}: unknown metric {metric}")
    return errors


def main(root: Path) -> int:
    known = catalogue(root)
    errors: list[str] = []
    boards = sorted((root / "deploy" / "grafana" / "dashboards").glob("*.json"))
    if not boards:
        errors.append("no dashboards found")
    for b in boards:
        errors.extend(lint_dashboard(b, known))
    errors.extend(lint_alerts(root, known))
    for e in errors:
        print(e)
    if not errors:
        print(f"dashboards: {len(boards)} dashboard(s) and the alert rules are clean")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
