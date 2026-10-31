"""Read, validate and write ``data/presets.yaml``.

The file is a contract between the research package (which writes fitted and declared
parameters) and the Java side (``io.ratesalgo.backtest.model.Presets`` now, the quoting engine
in P3). Schema, per preset (API_SPEC.md Section 16.2):

- ``name``: identifier used in code and reports
- ``fitted_from``: the manifest under ``data/manifests/``, a label set, or ``assumption``
- ``data_range``: ``{start: YYYY-MM-DD, end: YYYY-MM-DD}``
- ``sample_size``: integer
- ``method``: one line naming the estimator and the function that ran it
- ``parameters``: name -> ``{value: number, standard_error: number or null}``
- ``assumptions`` (optional): name -> ``{band: {low, central, high}, rationale: text}``
- ``notes`` (optional)

``write_preset`` replaces the preset of the same name or appends it, and keeps the comment
header of the file untouched, so presets written by other modules survive.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import yaml

REQUIRED_KEYS = ("name", "fitted_from", "data_range", "sample_size", "method", "parameters")


class PresetError(ValueError):
    """A preset does not follow the schema."""


def _split_header(text: str) -> tuple[str, str]:
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith("presets:"):
            return "".join(lines[:i]), "".join(lines[i:])
    return text, "presets: []\n"


def load_presets(path: str | Path) -> list[dict[str, Any]]:
    """Return the list of presets in the file (dates stay as strings)."""
    text = Path(path).read_text(encoding="utf-8")
    data = yaml.safe_load(text) or {}
    presets = data.get("presets") or []
    if not isinstance(presets, list):
        raise PresetError("presets must be a list")
    return [dict(p) for p in presets]


def get_preset(path: str | Path, name: str) -> dict[str, Any]:
    for p in load_presets(path):
        if p.get("name") == name:
            return p
    raise KeyError(f"no preset named {name} in {path}")


def value(preset: dict[str, Any], parameter: str) -> float:
    """The value of one parameter of a preset."""
    return float(preset["parameters"][parameter]["value"])


def validate_preset(preset: dict[str, Any]) -> list[str]:
    """Every schema problem of one preset; empty when it is valid."""
    problems: list[str] = []
    for key in REQUIRED_KEYS:
        if key not in preset or preset[key] in (None, ""):
            problems.append(f"missing key {key}")
    if problems:
        return problems
    rng = preset["data_range"]
    if not isinstance(rng, dict) or "start" not in rng or "end" not in rng:
        problems.append("data_range needs start and end")
    if not isinstance(preset["sample_size"], int):
        problems.append("sample_size must be an integer")
    params = preset["parameters"]
    if not isinstance(params, dict) or not params:
        problems.append("parameters must be a non-empty mapping")
    else:
        for name, p in params.items():
            if not isinstance(p, dict) or "value" not in p or "standard_error" not in p:
                problems.append(f"parameter {name} needs value and standard_error")
                continue
            v = p["value"]
            if not isinstance(v, int | float) or not math.isfinite(float(v)):
                problems.append(f"parameter {name} value is not a finite number")
    for name, a in (preset.get("assumptions") or {}).items():
        band = a.get("band") if isinstance(a, dict) else None
        if not isinstance(band, dict) or not {"low", "central", "high"} <= set(band):
            problems.append(f"assumption {name} needs band with low, central, high")
            continue
        lo, c, hi = float(band["low"]), float(band["central"]), float(band["high"])
        if not (lo <= c <= hi or lo >= c >= hi):
            problems.append(f"assumption {name} band is not ordered")
    return problems


def param(v: float, se: float | None = None) -> dict[str, float | None]:
    """A parameter entry; values rounded to 10 significant digits for a stable file."""
    return {"value": float(f"{v:.10g}"), "standard_error": None if se is None else float(f"{se:.6g}")}


def write_preset(path: str | Path, preset: dict[str, Any]) -> None:
    """Insert or replace ``preset`` (by name) in the presets file, keeping its header."""
    problems = validate_preset(preset)
    if problems:
        raise PresetError(f"preset {preset.get('name')}: " + "; ".join(problems))
    p = Path(path)
    text = p.read_text(encoding="utf-8") if p.exists() else ""
    header, _ = _split_header(text)
    presets = load_presets(p) if p.exists() else []
    replaced = False
    for i, existing in enumerate(presets):
        if existing.get("name") == preset["name"]:
            presets[i] = preset
            replaced = True
    if not replaced:
        presets.append(preset)
    presets.sort(key=lambda x: str(x["name"]))
    body = yaml.safe_dump({"presets": presets}, sort_keys=False, width=100, allow_unicode=False)
    p.write_text(header + body, encoding="utf-8", newline="\n")
