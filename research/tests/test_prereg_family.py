"""Registered families: the Python twin of the Java ``FamilyRules``, ``DataSets.looksOf`` and
``FamilyAdjustment`` with ``family:ID`` (the Java ``FamilyRulesTest`` asserts the same strings)."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

import pytest
import yaml

from ratesresearch import prereg

ROOT = Path(__file__).resolve().parents[2]
DRAFTS = ["SIM3-BASE-001"] + [f"R5-00{k}" for k in range(1, 8)]

FAMILY_PREREG = (
    "prereg_version: 2\nid: F\ntitle: t\nhypothesis: h\nmetric: m\nbaseline: default\n"
    "treatments: [v2, x]\ntest: t\ndata_set:\n  seed_base: 31000000\n  sessions: 2000\n"
    "  simulator_params: data/calibration/sim_v2_params.json\n  simulator_params_sha256:"
    " 3e2159814ee4e9892ae0dba33f5f35fe0bdd33739df625ea4ae46162c1154fd4\n"
    "success_threshold: s\nassumption_band: {preset: sim_v2_params, parameters: [impact,"
    " drift_tau], points: [low, central, high]}\n"
    "author: a\ndate: 2026-11-23\nconfig: c.yaml\nconfig_sha256: CONFIG_SHA\n"
)
FAMILY_STATS = (
    "seeds: {pilot_base: 29000000, confirmation_base: 31000000}\n"
    "statistics: {block_sessions: 21, resamples: 2000, bootstrap_seed: 5}\n"
    "smallest_effect: {usd_per_session: 100}\narms: {default: a, v2: b, x: c}\n"
)
GOOD_FAMILY = FAMILY_STATS.replace("x: c}", "x: c, legacy: d}") + (
    "family: {id: F, q: 0.05, reference_arm: default, guards_vs_reference: {client_dv01: 0.25},"
    " descriptive_arms: [legacy], structural_declines: not_above_baseline, hypotheses: ["
    "{id: H1, treatment: v2, baseline: default, guards: {quoted_share: 0.8}},"
    " {id: H2, treatment: v2, baseline: x}]}\n"
    "rows: [{row: central, arms: [default, v2, x, legacy], sessions: 2000},"
    " {row: 'impact:low', arms: [default, v2], sessions: 1000},"
    " {row: 'impact:high', arms: [default, v2], sessions: 1000}]\n"
    "excluded_band_points: [{row: 'drift_tau:low', reason: refused},"
    " {row: 'drift_tau:high', reason: refused}]\n"
)
BAD_FAMILY = FAMILY_STATS + (
    "family: {q: 2, reference_arm: ghost, guards_vs_reference: {speed: 1},"
    " descriptive_arms: [v2, ghost], hypotheses: ["
    "{id: H1, treatment: v9, baseline: default, guards: {hit_rate: 0}},"
    " {id: H1, treatment: x, baseline: x}, {treatment: v2}, {id: H3, treatment: ghost,"
    " baseline: default}]}\n"
    "rows: [{row: 'impact:low', arms: [default], sessions: 2000},"
    " {row: 'impact:low', arms: [default, nobody], sessions: 5000},"
    " {row: 'speed:low', arms: [], sessions: 0}]\n"
    "excluded_band_points: [{row: 'impact:low'}, {row: 'drift_tau:middle', reason: r}]\n"
)
NEITHER = " is neither run (config rows) nor excluded with a reason (config excluded_band_points)"
SESSIONS = "sessions must be a positive integer no larger than data_set.sessions"
BAD_FAMILY_PROBLEMS = [
    "config family.id is missing",
    "config family.q must be a number between 0 and 1",
    "config family.descriptive_arms: 'v2' is also the baseline or a treatment",
    "config family.descriptive_arms: 'ghost' is not named under config arms",
    "hypothesis H1: treatment 'v9' is not an arm of the design",
    "hypothesis H1: guards.hit_rate must be a positive ratio",
    "config family: hypothesis id 'H1' is listed twice",
    "hypothesis H1: treatment and baseline are the same arm",
    "config family.hypotheses[2] needs an id",
    "hypothesis H3: treatment 'ghost' is a descriptive arm and enters no hypothesis",
    "config family.reference_arm 'ghost' is a descriptive arm",
    "config family.guards_vs_reference.speed is not a guard; known: [quoted_share, hit_rate,"
    " client_dv01]",
    "config rows[0] must be the central row",
    "config rows: 'impact:low' is listed twice",
    "config rows 'impact:low': arm 'nobody' is not an arm of the design",
    f"config rows 'impact:low': {SESSIONS}",
    "config rows: 'speed:low' is not a low or high row of the band's parameters",
    "config rows 'speed:low': arms must be a non-empty list",
    f"config rows 'speed:low': {SESSIONS}",
    "config excluded_band_points: 'impact:low' is also run as a row",
    "config excluded_band_points 'impact:low' needs a reason",
    "config excluded_band_points: 'drift_tau:middle' is not a low or high row of the band's"
    " parameters",
    "assumption_band point 'impact:high'" + NEITHER,
    "assumption_band point 'drift_tau:low'" + NEITHER,
    "assumption_band point 'drift_tau:high'" + NEITHER,
]


@pytest.fixture()
def repo(tmp_path: Path) -> Path:
    (tmp_path / "data/calibration").mkdir(parents=True)
    shutil.copy(
        ROOT / "data/calibration/sim_v2_params.json",
        tmp_path / "data/calibration/sim_v2_params.json",
    )
    return tmp_path


def _problems(repo: Path, text: str, config: str) -> list[str]:
    (repo / "c.yaml").write_text(config, encoding="utf-8")
    sha = hashlib.sha256(config.encode("utf-8")).hexdigest()
    (repo / "p.yaml").write_text(text.replace("CONFIG_SHA", sha), encoding="utf-8")
    return prereg.validate(repo / "p.yaml", repo)


def test_the_registered_family_validates_and_replaces_the_drafts() -> None:
    assert prereg.validate(ROOT / "research/prereg/R5-FAM-001.yaml", ROOT) == []
    raw = yaml.safe_load((ROOT / "research/prereg/R5-FAM-001.yaml").read_text(encoding="utf-8"))
    assert raw["status"].startswith("draft"), "unsigned: the runner refuses it until signed"
    assert raw["data_set"]["seed_base"] == 31_000_000
    assert raw["data_set"]["sessions"] == 2000
    assert raw["prior_looks"] == []
    assert raw["prereg_version"] == 2
    config = yaml.safe_load(
        (ROOT / "research/prereg/R5-FAM-001.config.yaml").read_text(encoding="utf-8")
    )
    assert [h["id"] for h in config["family"]["hypotheses"]] == [f"H{k}" for k in range(1, 9)]
    assert len(config["arms"]) == 10
    assert config["family"]["descriptive_arms"] == ["legacy"]
    assert config["family"]["reference_arm"] == "baseline" == raw["baseline"]
    for h in config["family"]["hypotheses"]:
        assert "legacy" not in (h["treatment"], h["baseline"])
    assert "legacy" in config["rows"][0]["arms"]
    assert [r["row"] for r in config["rows"]] == [
        "central",
        "all_in_cost:low",
        "winner_half_spread:low",
    ]
    assert {e["row"] for e in config["excluded_band_points"]} == {
        "all_in_cost:high",
        "winner_half_spread:high",
    }
    for draft in DRAFTS:
        old = yaml.safe_load(
            (ROOT / "research/prereg" / f"{draft}.yaml").read_text(encoding="utf-8")
        )
        assert old["superseded_by"] == "R5-FAM-001"
        assert str(old["superseded_on"]) == "2026-11-23"
        assert old["data_set"]["seed_base"] != 31_000_000, "a new base, named by no draft"
        assert prereg.validate(ROOT / "research/prereg" / f"{draft}.yaml", ROOT) == []
    sim3 = (ROOT / "research/prereg/SIM3-BASE-001.yaml").read_text(encoding="utf-8")
    assert "0.0311" not in sim3 and "high: 0.0312" in sim3


def test_family_rules_name_their_problems(repo: Path) -> None:
    assert _problems(repo, FAMILY_PREREG, GOOD_FAMILY) == []
    assert _problems(
        repo, FAMILY_PREREG, GOOD_FAMILY.replace("reference_arm: default", "reference_arm: nobody")
    ) == ["config family.reference_arm 'nobody' is not an arm of the design"]
    assert _problems(repo, FAMILY_PREREG, BAD_FAMILY) == BAD_FAMILY_PROBLEMS
    not_maps = FAMILY_STATS + "family: [1]\nrows: [1]\nexcluded_band_points: 3\n"
    assert _problems(repo, FAMILY_PREREG, not_maps) == [
        "config family must be a mapping with id, q and hypotheses",
        "config rows[0] must be a mapping of row, arms and sessions",
        "config excluded_band_points must be a list of {row, reason}",
        "assumption_band point 'impact:low'" + NEITHER,
        "assumption_band point 'impact:high'" + NEITHER,
        "assumption_band point 'drift_tau:low'" + NEITHER,
        "assumption_band point 'drift_tau:high'" + NEITHER,
    ]
    no_default = FAMILY_PREREG.replace("baseline: default", "baseline: d").replace(
        "treatments: [v2, x]", "treatments: [v2]"
    )
    cfg = FAMILY_STATS.replace("default: a", "d: a") + (
        "family: {id: F, q: 0.05, guards_vs_default: {client_dv01: 1}, guards_vs_reference:"
        " {client_dv01: 1}, descriptive_arms: 3, hypotheses: [{id: H1,"
        " treatment: v2, baseline: d, guards: [1]}]}\nrows: [{row: central, arms: [d],"
        " sessions: 7}]\nexcluded_band_points: [{row: 'impact:low', reason: r},"
        " {row: 'impact:high', reason: r}, {row: 'drift_tau:low', reason: r},"
        " {row: 'drift_tau:high', reason: r}]\n"
    )
    assert _problems(repo, no_default, cfg) == [
        "config family.descriptive_arms must be a list of arm names",
        "hypothesis H1: guards must be a mapping of guard name to minimum ratio",
        "config family.guards_vs_default is replaced by reference_arm and guards_vs_reference",
        "config family.guards_vs_reference needs family.reference_arm",
        "config rows[0] (central) must run every arm of the design",
        "config rows[0] sessions 7 differs from data_set.sessions 2000",
    ]


def _family_row(run: str, hid: str, p: float, low: float, high: float, quoted: float) -> dict:
    return {
        "prereg_id": "F",
        "family_id": "F",
        "family_run": run,
        "hypothesis_id": hid,
        "session_seeds": {"base": 31_000_000, "first": 0, "count": 3},
        "scope": "confirmation",
        "look": 1,
        "verdict": "pending_family_adjustment",
        "guards_min": {"quoted_share": 0.8},
        "results": {
            "primary": {
                f"t_vs_{hid}": {
                    "paired_p": p,
                    "ci_low": low,
                    "ci_high": high,
                    "guards": {"quoted_share_ratio": quoted},
                }
            },
            "band_secondary": {"impact:low": {"sign": -1}},
        },
    }


def _ledger(path: Path, rows: list[dict[str, Any]]) -> Path:
    prev = prereg.GENESIS
    lines = []
    for n, r in enumerate(rows, 1):
        full = dict(r, ledger_version=1, seq=n, prev_sha256=prev)
        line = json.dumps(full, sort_keys=True, separators=(",", ":"))
        lines.append(line)
        prev = prereg.sha256_text(line)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


FAMILY_TABLE = (
    "family family:F (q = 0.05): 4 tests from 4 ledger rows\n"
    "  F H1 t_vs_H1  seq 1  p 0.00100000  bh 0.00400000  ci [10.00, 20.00]  guards ok"
    "  verdict supported\n"
    "  F H2 t_vs_H2  seq 2  p 0.03000000  bh 0.04000000  ci [1.00, 9.00]  guards fail"
    "  verdict trading less\n"
    "  F H3 t_vs_H3  seq 3  p 0.01200000  bh 0.02400000  ci [-9.00, -1.00]  guards ok"
    "  verdict reversed\n"
    "  F H4 t_vs_H4  seq 4  p 0.60000000  bh 0.60000000  ci [-5.00, 9.00]  guards ok"
    "  verdict inconclusive\n"
)


def family_rows() -> list[dict[str, Any]]:
    description = {
        "prereg_id": "F",
        "family_id": "F",
        "family_run": "r1",
        "hypothesis_id": "D0",
        "session_seeds": {"base": 31_000_000, "first": 0, "count": 3},
        "scope": "confirmation",
        "look": 1,
        "verdict": "description",
        "results": {"description": {}},
    }
    return [
        _family_row("r1", "H1", 0.001, 10.0, 20.0, 0.9),
        _family_row("r1", "H2", 0.030, 1.0, 9.0, 0.5),
        _family_row("r1", "H3", 0.012, -9.0, -1.0, 0.9),
        _family_row("r1", "H4", 0.600, -5.0, 9.0, 0.9),
        description,
    ]


def test_a_family_run_is_one_look_with_its_own_bh_table(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rows = family_rows()
    entries = prereg.read_ledger(_ledger(tmp_path / "ledger.jsonl", rows))
    report = prereg.looks_report(entries)
    assert ": 1 look\n" in report
    assert "  look 1  seq 1..5  F  confirmation  family F, 5 rows  (recorded as look 1)\n" in report
    assert "warning" not in report
    # a band row's negative sign does not enter a family's verdict
    assert prereg.family_report(entries, "family:F") == FAMILY_TABLE
    family = tmp_path / "family.yaml"
    family.write_text("family: F\nfamily_id: F\nq: 0.05\nmembers: [F]\n", encoding="utf-8")
    assert (
        prereg.family_report(entries, str(family)).splitlines()[1:]
        == (FAMILY_TABLE.splitlines()[1:])
    )
    assert prereg.main(["bh", str(tmp_path / "ledger.jsonl"), "family:F"]) == 0
    assert "verdict reversed" in capsys.readouterr().out
    # a second run of the family on the same data is a second look, and is warned about
    again = prereg.read_ledger(
        _ledger(tmp_path / "again.jsonl", [*rows, dict(rows[0], family_run="r2")])
    )
    text = prereg.looks_report(again)
    assert ": 2 looks\n" in text and "warning: 2 looks" in text
    unchecked = prereg.read_ledger(
        _ledger(tmp_path / "unchecked.jsonl", [dict(rows[0], guards_min={})])
    )
    assert "supported if the guards hold" in prereg.family_report(unchecked, "family:F")
    assert prereg.family_report(unchecked, "family:G") == (
        "family family:G (q = 0.05): 0 tests from 0 ledger rows\n"
    )


def test_the_family_file_of_r5_fam_001_names_it() -> None:
    raw = yaml.safe_load(
        (ROOT / "research/prereg/families/R5-FAM-001.yaml").read_text(encoding="utf-8")
    )
    assert raw["members"] == ["R5-FAM-001"]
    assert raw["q"] == 0.05
    old = (ROOT / "research/prereg/families/R5.yaml").read_text(encoding="utf-8")
    assert "superseded" in old
