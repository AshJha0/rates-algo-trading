"""Pre-registration validator and ledger reader, the Python twin of
``io.ratesalgo.backtest.prereg`` (API_SPEC.md Section 16.8).

The rules are the Java ones: every key present and non-blank; ISO dates with start not after
end; the evaluation period and every holdout date outside the fitting period; no holdout date
inside the evaluation period; band points exactly low, central and high; the config file's
SHA-256 (text, LF line endings) equal to ``config_sha256``; ``prior_looks``, when present, a list.

Version 2 files (``prereg_version: 2``) are session-seed designs on simulator v2 and follow the
rules of the Java ``SessionDesignRules``: no date keys; a ``data_set`` of seed base, first session,
session count and the simulator parameter file with its SHA-256; band parameters by name; a
config that names every arm, the statistics, the pilot base and, for a comparison, the smallest
effect. A draft (``status`` starting with ``draft``) validates but is never run. A registered
family (Java ``FamilyRules``) adds to the config its hypotheses (``family``), its data-set rows
(``rows``: the central row with every arm, then band rows each with its own arms and session
count) and the band points it leaves out with a reason (``excluded_band_points``); its ledger rows
carry ``family_id``, ``family_run`` and ``hypothesis_id``, one row per hypothesis, and the rows of
one run count as one look.

``looks_report`` counts looks per evaluation data set across pre-registration ids, with the keys
of the Java ``DataSets``: the ledger's ``look`` field counts runs of one id, so several ids that
each read the same sessions once all say look 1. ``family_report`` prints the Benjamini-Hochberg
adjusted p and verdict of every primary test of a registered family, as ``LedgerCli --bh``.

    python -m ratesresearch.prereg LEDGER                 verify the chain, print the looks
    python -m ratesresearch.prereg bh LEDGER FAMILY       the same, then the family's BH table
                                                          (FAMILY: a family file, family:ID or
                                                          data_set:KEY)
    python -m ratesresearch.prereg validate FILE [...]    problems of each pre-registration

CI runs the first on ``data/ledger/ledger.jsonl``.
"""

from __future__ import annotations

import hashlib
import json
import sys
from datetime import date, timedelta
from pathlib import Path
from typing import Any, TypeGuard

import yaml

REQUIRED = (
    "prereg_version",
    "id",
    "title",
    "hypothesis",
    "metric",
    "baseline",
    "treatments",
    "period",
    "fitting_period",
    "holdout",
    "success_threshold",
    "assumption_band",
    "author",
    "date",
    "config",
    "config_sha256",
)
GENESIS = "0" * 64

# version 2: session-seed designs on simulator v2 (Java SessionDesignRules)
V2_REQUIRED = (
    "id",
    "title",
    "hypothesis",
    "metric",
    "baseline",
    "treatments",
    "data_set",
    "success_threshold",
    "assumption_band",
    "author",
    "date",
    "config",
    "config_sha256",
)
VERSION_1_KEYS = ("period", "fitting_period", "holdout")
BAND_PARAMETERS = (
    "winner_half_spread",
    "all_in_cost",
    "informed_fraction",
    "drift_tau",
    "level_vol",
    "spread_factor",
    "impact",
    "basis",
    "taker_skill",
    "impact_half_life",
)
GUARDS = ("quoted_share", "hit_rate", "client_dv01")
SPENT_BASES = (5_000_000, 8_000_000)
ACCEPTANCE_BASE = 28_000_000
PILOT_BASE = 29_000_000
SPAN = 1_000_000
MINIMUM_SESSIONS = 2000
MAX_SESSION_NUMBER = 80_000
MINIMUM_DESCRIPTION_SESSIONS = 2
LABEL_EPOCH = date(2030, 1, 1)


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def sha256_text_file(path: str | Path) -> str:
    return sha256_text(Path(path).read_bytes().decode("utf-8"))


def _date(v: Any, key: str, problems: list[str]) -> date | None:
    if isinstance(v, date):
        return v
    try:
        return date.fromisoformat(str(v).strip())
    except ValueError:
        problems.append(f"{key} is not an ISO date: {v}")
        return None


def _range(raw: dict[str, Any], key: str, problems: list[str]) -> tuple[date, date] | None:
    v = raw.get(key)
    if not isinstance(v, dict):
        problems.append(f"{key} must have start and end")
        return None
    s = _date(v.get("start"), f"{key}.start", problems)
    e = _date(v.get("end"), f"{key}.end", problems)
    if s is None or e is None:
        return None
    if s > e:
        problems.append(f"{key} starts after it ends: {s} > {e}")
        return None
    return s, e


def validate(path: str | Path, repo_root: str | Path) -> list[str]:
    """Every problem with the pre-registration at ``path``; empty when it is valid."""
    p = Path(path)
    if not p.is_file():
        return [f"pre-registration not found: {p}"]
    raw = yaml.safe_load(p.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return [f"{p}: not a YAML mapping"]
    dated = [k for k in VERSION_1_KEYS if k in raw]
    if raw.get("prereg_version") == 2 and not dated:
        return validate_v2(raw, Path(repo_root))
    problems = _validate_dated(raw, repo_root)
    if raw.get("prereg_version") == 2:
        # a dated file is read by the version 1 rules, whatever version it claims
        problems += [
            f"version 1 date key '{k}' is not allowed in a session-seed design" for k in dated
        ]
    return problems


def _validate_dated(raw: dict[str, Any], repo_root: str | Path) -> list[str]:
    problems = [f"missing or blank key '{k}'" for k in REQUIRED if raw.get(k) in (None, "")]
    if problems:
        return problems
    if raw["prereg_version"] != 1:
        problems.append(f"prereg_version must be 1, found {raw['prereg_version']}")
    period = _range(raw, "period", problems)
    fitting = _range(raw, "fitting_period", problems)
    holdout: list[date] = []
    if isinstance(raw["holdout"], list) and raw["holdout"]:
        for v in raw["holdout"]:
            d = _date(v, "holdout", problems)
            if d is not None:
                holdout.append(d)
    else:
        problems.append("holdout must be a non-empty list of dates")
    if len(holdout) == 1:
        problems.append("holdout needs at least two dates: the daily statistics need two days")
    if period and len(business_days(period[0], period[1])) < 2:
        problems.append("evaluation period needs at least two business days")
    _date(raw["date"], "date", problems)
    if period and fitting and not (period[1] < fitting[0] or period[0] > fitting[1]):
        problems.append(f"evaluation period {period[0]}..{period[1]} overlaps the fitting period")
    for d in holdout:
        if fitting and fitting[0] <= d <= fitting[1]:
            problems.append(f"holdout date {d} overlaps the fitting period")
        if period and period[0] <= d <= period[1]:
            problems.append(f"holdout date {d} lies inside the evaluation period")
    if not isinstance(raw["treatments"], list) or not raw["treatments"]:
        problems.append("treatments must be a non-empty list")
    band = raw["assumption_band"]
    if not isinstance(band, dict) or {str(x).lower() for x in band.get("points") or []} != {
        "low",
        "central",
        "high",
    }:
        problems.append("assumption_band.points must be low, central and high (DR-008)")
    if raw.get("prior_looks") is not None and not isinstance(raw["prior_looks"], list):
        problems.append("prior_looks must be a list of pre-registration ids")
    config = Path(repo_root) / str(raw["config"])
    if not config.is_file():
        problems.append(f"config file not found: {raw['config']}")
    elif sha256_text_file(config) != str(raw["config_sha256"]):
        problems.append("config_sha256 does not match the config file")
    return problems


def _java_list(items: Any) -> str:
    return "[" + ", ".join(str(x) for x in items) + "]"


def _blank(v: Any) -> bool:
    return v is None or (isinstance(v, str) and not v.strip())


def _is_int(v: Any) -> TypeGuard[int]:
    return isinstance(v, int) and not isinstance(v, bool)


def _is_number(v: Any) -> TypeGuard[int | float]:
    return isinstance(v, int | float) and not isinstance(v, bool)


def spent_refusal(base: int) -> str | None:
    """Why no simulator v2 run may use this seed base, or None; ``SpentData.spentRefusal``."""
    for b in SPENT_BASES:
        if b <= base < b + SPAN:
            return (
                f"seed base {base} is spent: every session under {b} was generated by the old"
                " simulators and read by AUDIT-01 to AUDIT-26 (SIM_V2_DESIGN.md section 12)"
            )
    if base < ACCEPTANCE_BASE or base % SPAN != 0:
        return (
            f"seed base {base} is not a simulator v2 base (a whole million of at least"
            f" {ACCEPTANCE_BASE})"
        )
    return None


def registered_refusal(base: int) -> str | None:
    """Why a registered experiment may not use this base; ``SpentData.registeredRefusal``."""
    spent = spent_refusal(base)
    if spent is not None:
        return spent
    if base == ACCEPTANCE_BASE:
        return f"seed base {base} belongs to the simulator's acceptance tests and supports no claim"
    if base == PILOT_BASE:
        return (
            f"seed base {base} is the pilot base: it sizes a confirmation set and is then"
            " discarded for inference"
        )
    return None


def validate_v2(raw: dict[str, Any], repo_root: Path) -> list[str]:
    """Every problem with a version 2 pre-registration, in the Java order and wording."""
    problems = [f"missing or blank key '{k}'" for k in V2_REQUIRED if _blank(raw.get(k))]
    problems += [
        f"version 1 date key '{k}' is not allowed in a session-seed design"
        for k in VERSION_1_KEYS
        if k in raw
    ]
    if problems:
        return problems
    draft = raw.get("status") is not None and str(raw["status"]).strip().startswith("draft")
    treatments: list[str] = []
    if isinstance(raw["treatments"], list):
        treatments = [str(t) for t in raw["treatments"]]
    else:
        problems.append("treatments must be a list (empty for a description)")
    if treatments and _blank(raw.get("test")):
        problems.append("missing or blank key 'test': a comparison names its test")
    if str(raw["baseline"]) in treatments:
        problems.append("the baseline arm is also listed as a treatment")
    try:
        date.fromisoformat(str(raw["date"]).strip())
    except ValueError:
        problems.append(f"date is not an ISO date: {raw['date']}")
    if raw.get("prior_looks") is not None and not isinstance(raw["prior_looks"], list):
        problems.append("prior_looks must be a list of pre-registration ids")
    base = _v2_data_set(raw, not treatments, draft, repo_root, problems)
    _v2_band(raw, problems)
    config = repo_root / str(raw["config"])
    if not config.is_file():
        problems.append(f"config file not found: {raw['config']}")
    else:
        actual = sha256_text_file(config)
        if actual != str(raw["config_sha256"]):
            problems.append(
                f"config_sha256 {raw['config_sha256']} does not match the config file ({actual})"
            )
        cfg = yaml.safe_load(config.read_text(encoding="utf-8"))
        cfg = cfg if isinstance(cfg, dict) else {}
        _v2_config(cfg, str(raw["baseline"]), treatments, base, problems)
        if "family" in cfg or "rows" in cfg or "excluded_band_points" in cfg:
            _v2_family(raw, cfg, [str(raw["baseline"]), *treatments], problems)
    return problems


def _band_labels(raw: dict[str, Any]) -> list[str]:
    band = raw.get("assumption_band")
    names = band.get("parameters") if isinstance(band, dict) else None
    if not isinstance(names, list):
        return []
    return [f"{n}:{point}" for n in names for point in ("low", "high")]


def _v2_family(
    raw: dict[str, Any], cfg: dict[str, Any], tested: list[str], problems: list[str]
) -> None:
    """A registered family (Java ``FamilyRules``): hypotheses, data-set rows, excluded points.

    ``tested`` is the baseline then the treatments; the family's descriptive arms are added.
    """
    arms = list(tested)
    fam = cfg.get("family")
    if not isinstance(fam, dict):
        problems.append("config family must be a mapping with id, q and hypotheses")
    else:
        if _blank(fam.get("id")):
            problems.append("config family.id is missing")
        q = fam.get("q")
        if not (_is_number(q) and 0 < q < 1):
            problems.append("config family.q must be a number between 0 and 1")
        descriptive: list[str] = []
        if fam.get("descriptive_arms") is not None:
            if isinstance(fam["descriptive_arms"], list):
                named = cfg.get("arms")
                for o in fam["descriptive_arms"]:
                    a = str(o)
                    if a in tested:
                        problems.append(
                            f"config family.descriptive_arms: '{a}' is also the baseline or a"
                            " treatment"
                        )
                    elif not isinstance(named, dict) or a not in named:
                        problems.append(
                            f"config family.descriptive_arms: '{a}' is not named under config arms"
                        )
                    descriptive.append(a)
            else:
                problems.append("config family.descriptive_arms must be a list of arm names")
        arms += [a for a in dict.fromkeys(descriptive) if a not in arms]
        hyps = fam.get("hypotheses")
        if not isinstance(hyps, list) or not hyps:
            problems.append("config family.hypotheses must be a non-empty list")
            hyps = []
        seen: set[str] = set()
        for n, h in enumerate(hyps):
            if not isinstance(h, dict) or _blank(h.get("id")):
                problems.append(f"config family.hypotheses[{n}] needs an id")
                continue
            hid = str(h["id"])
            if hid in seen:
                problems.append(f"config family: hypothesis id '{hid}' is listed twice")
            seen.add(hid)
            for role in ("treatment", "baseline"):
                a = str(h.get(role))
                if a not in arms:
                    problems.append(f"hypothesis {hid}: {role} '{a}' is not an arm of the design")
                elif a in descriptive:
                    problems.append(
                        f"hypothesis {hid}: {role} '{a}' is a descriptive arm and enters no"
                        " hypothesis"
                    )
            if str(h.get("treatment")) == str(h.get("baseline")):
                problems.append(f"hypothesis {hid}: treatment and baseline are the same arm")
            _guard_map(h.get("guards"), f"hypothesis {hid}: guards", problems)
        if fam.get("guards_vs_default") is not None:
            problems.append(
                "config family.guards_vs_default is replaced by reference_arm and"
                " guards_vs_reference"
            )
        reference = fam.get("reference_arm")
        if reference is not None:
            if str(reference) not in arms:
                problems.append(
                    f"config family.reference_arm '{reference}' is not an arm of the design"
                )
            elif str(reference) in descriptive:
                problems.append(f"config family.reference_arm '{reference}' is a descriptive arm")
        if fam.get("guards_vs_reference") is not None:
            _guard_map(fam["guards_vs_reference"], "config family.guards_vs_reference", problems)
            if reference is None:
                problems.append("config family.guards_vs_reference needs family.reference_arm")
    sessions = raw["data_set"].get("sessions") if isinstance(raw.get("data_set"), dict) else None
    band = _band_labels(raw)
    rows = cfg.get("rows")
    run: list[str] = []
    if not isinstance(rows, list) or not rows:
        problems.append("config rows must be a non-empty list of {row, arms, sessions}")
        rows = []
    for n, r in enumerate(rows):
        if not isinstance(r, dict):
            problems.append(f"config rows[{n}] must be a mapping of row, arms and sessions")
            continue
        label = str(r.get("row"))
        listed = r.get("arms")
        r_arms = [str(a) for a in listed] if isinstance(listed, list) else []
        if label in run:
            problems.append(f"config rows: '{label}' is listed twice")
        run.append(label)
        if n == 0:
            if label != "central":
                problems.append("config rows[0] must be the central row")
            elif sorted(r_arms) != sorted(arms):
                problems.append("config rows[0] (central) must run every arm of the design")
            if _is_int(sessions) and r.get("sessions") != sessions:
                problems.append(
                    f"config rows[0] sessions {r.get('sessions')} differs from data_set.sessions"
                    f" {sessions}"
                )
            continue
        if label not in band:
            problems.append(
                f"config rows: '{label}' is not a low or high row of the band's parameters"
            )
        if not r_arms:
            problems.append(f"config rows '{label}': arms must be a non-empty list")
        problems.extend(
            f"config rows '{label}': arm '{a}' is not an arm of the design"
            for a in r_arms
            if a not in arms
        )
        s = r.get("sessions")
        if not (_is_int(s) and s > 0 and (not _is_int(sessions) or s <= sessions)):
            problems.append(
                f"config rows '{label}': sessions must be a positive integer no larger than"
                " data_set.sessions"
            )
    excluded = cfg.get("excluded_band_points") or []
    if not isinstance(excluded, list):
        problems.append("config excluded_band_points must be a list of {row, reason}")
        excluded = []
    dropped: list[str] = []
    for e in excluded:
        label = str(e.get("row")) if isinstance(e, dict) else str(e)
        dropped.append(label)
        if label not in band:
            problems.append(
                f"config excluded_band_points: '{label}' is not a low or high row of the band's"
                " parameters"
            )
        if label in run:
            problems.append(f"config excluded_band_points: '{label}' is also run as a row")
        if not isinstance(e, dict) or _blank(e.get("reason")):
            problems.append(f"config excluded_band_points '{label}' needs a reason")
    problems.extend(
        f"assumption_band point '{label}' is neither run (config rows) nor excluded with a"
        " reason (config excluded_band_points)"
        for label in band
        if label not in run and label not in dropped
    )


def _guard_map(guards: Any, where: str, problems: list[str]) -> None:
    if guards is None:
        return
    if not isinstance(guards, dict):
        problems.append(f"{where} must be a mapping of guard name to minimum ratio")
        return
    for k, v in guards.items():
        if str(k) not in GUARDS:
            problems.append(f"{where}.{k} is not a guard; known: {_java_list(GUARDS)}")
        elif not (_is_number(v) and v > 0):
            problems.append(f"{where}.{k} must be a positive ratio")


def _v2_data_set(
    raw: dict[str, Any], description: bool, draft: bool, repo_root: Path, problems: list[str]
) -> int:
    m = raw["data_set"]
    if not isinstance(m, dict):
        problems.append("data_set must be a mapping with seed_base, sessions and simulator_params")
        return -1
    base = -1
    if _is_int(m.get("seed_base")):
        base = int(m["seed_base"])
        refusal = registered_refusal(base)
        if refusal is not None:
            problems.append(f"data_set.seed_base: {refusal}")
    else:
        problems.append("data_set.seed_base must be an integer")
    first = 0
    if m.get("first") is not None:
        if _is_int(m["first"]) and m["first"] >= 0:
            first = int(m["first"])
        else:
            problems.append("data_set.first must be a non-negative integer")
    floor = MINIMUM_DESCRIPTION_SESSIONS if description else MINIMUM_SESSIONS
    sessions = m.get("sessions")
    if _is_int(sessions):
        if sessions < floor:
            problems.append(f"data_set.sessions {sessions} is below the floor of {floor}")
        elif first + sessions > MAX_SESSION_NUMBER:
            problems.append(
                f"data_set.first + sessions exceeds {MAX_SESSION_NUMBER}: later sessions' label"
                " dates (2030-01-01 plus the session number in days) overflow epoch nanoseconds"
            )
    elif not (draft and isinstance(sessions, str) and sessions.strip()):
        problems.append(
            "data_set.sessions must be an integer, or the rule as text while the file is a draft"
            if draft
            else f"data_set.sessions must be an integer of at least {floor} once the file is"
            " not a draft"
        )
    params = m.get("simulator_params")
    if _blank(params):
        problems.append("data_set.simulator_params is missing")
    else:
        file = repo_root / str(params)
        if not file.is_file():
            problems.append(f"data_set.simulator_params not found: {params}")
        elif sha256_text_file(file) != str(m.get("simulator_params_sha256")):
            problems.append(
                f"data_set.simulator_params_sha256 does not match {params}"
                f" ({sha256_text_file(file)})"
            )
    return base


def _v2_band(raw: dict[str, Any], problems: list[str]) -> None:
    band = raw["assumption_band"]
    if not isinstance(band, dict):
        problems.append("assumption_band must be a mapping")
        return
    if str(band.get("preset")) != "sim_v2_params":
        problems.append(f"assumption_band.preset must be sim_v2_params, found {band.get('preset')}")
    names = band.get("parameters")
    if isinstance(names, list):
        for n in names:
            if str(n) not in BAND_PARAMETERS:
                problems.append(
                    f"assumption_band.parameters has an unknown parameter '{n}'; known:"
                    f" {_java_list(BAND_PARAMETERS)}"
                )
    else:
        problems.append("assumption_band.parameters must be a list of band parameter names")
    points = band.get("points")
    if isinstance(points, list):
        seen = set()
        for pt in points:
            if str(pt).lower() in ("low", "central", "high"):
                seen.add(str(pt).lower())
            else:
                problems.append(f"assumption_band.points has an unknown point '{pt}'")
        if seen != {"low", "central", "high"}:
            problems.append("assumption_band.points must be low, central and high (DR-008)")
    else:
        problems.append("assumption_band.points must be a list")


def _v2_config(
    config: dict[str, Any], baseline: str, treatments: list[str], base: int, problems: list[str]
) -> None:
    arms = config.get("arms")
    if isinstance(arms, dict):
        if baseline not in arms:
            problems.append(f"config arms do not name the baseline '{baseline}'")
        problems.extend(
            f"config arms do not name the treatment '{t}'" for t in treatments if t not in arms
        )
    else:
        problems.append("config must name the arms under 'arms'")
    st = config.get("statistics")
    if isinstance(st, dict):
        for key in ("block_sessions", "resamples"):
            if not (_is_int(st.get(key)) and st[key] > 0):
                problems.append(f"config statistics.{key} must be a positive integer")
        if not _is_int(st.get("bootstrap_seed")):
            problems.append("config statistics.bootstrap_seed must be an integer")
    else:
        problems.append("config must state the statistics under 'statistics'")
    seeds = config.get("seeds")
    if isinstance(seeds, dict):
        pilot = seeds.get("pilot_base")
        if not (_is_int(pilot) and pilot == PILOT_BASE):
            shown = "null" if pilot is None else pilot
            problems.append(
                f"config seeds.pilot_base must be the pilot base {PILOT_BASE}, found {shown}"
            )
        for key in ("confirmation_base", "base"):
            v = seeds.get(key)
            if base >= 0 and _is_int(v) and v != base:
                problems.append(f"config seeds.{key} {v} differs from data_set.seed_base {base}")
        v = seeds.get("minimum_sessions")
        if _is_int(v) and v < MINIMUM_SESSIONS:
            problems.append(
                f"config seeds.minimum_sessions {v} is below the design's floor of"
                f" {MINIMUM_SESSIONS}"
            )
    else:
        problems.append("config must state the pilot base under 'seeds.pilot_base'")
    guards = config.get("guards")
    if guards is not None:
        if isinstance(guards, dict):
            for k, v in guards.items():
                if str(k) not in GUARDS:
                    problems.append(
                        f"config guards.{k} is not a guard; known: {_java_list(GUARDS)}"
                    )
                elif not (_is_number(v) and v > 0):
                    problems.append(f"config guards.{k} must be a positive ratio")
        else:
            problems.append("config guards must be a mapping of guard name to minimum ratio")
    if treatments:
        effect = config.get("smallest_effect")
        value = effect.get("usd_per_session") if isinstance(effect, dict) else None
        if not (_is_number(value) and value > 0):
            problems.append(
                "config smallest_effect.usd_per_session must be a positive number, stated before"
                " the pilot"
            )


def label_date(seed: int) -> date:
    """The label date of a simulator v2 session, as ``PairedSessions.labelDate``."""
    return LABEL_EPOCH + timedelta(days=seed % SPAN)


def sessions_key(base: int, first: int, count: int) -> str:
    """The data set key of sessions ``first .. first + count - 1`` of a seed base."""
    return _sessions_key(
        {f"{label_date(base + n)}|{base + n}" for n in range(first, first + count)}
    )


def business_days(start: date, end: date) -> list[date]:
    out = []
    d = start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def read_ledger(path: str | Path) -> list[dict[str, Any]]:
    """Ledger entries in order, after verifying seq and the SHA-256 chain."""
    p = Path(path)
    if not p.exists():
        return []
    entries: list[dict[str, Any]] = []
    prev = GENESIS
    for n, line in enumerate(
        (x for x in p.read_text(encoding="utf-8").splitlines() if x.strip()), 1
    ):
        entry = json.loads(line)
        if entry.get("prev_sha256") != prev:
            raise ValueError(f"{p}: hash chain broken at line {n}")
        if entry.get("seq") != n:
            raise ValueError(f"{p}: seq {entry.get('seq')} at line {n}")
        entries.append(entry)
        prev = sha256_text(line)
    return entries


def _sessions_key(lines: set[str]) -> str:
    text = "".join(line + "\n" for line in sorted(lines))
    return "sessions:" + sha256_text(text)[:16]


def data_set_key(entry: dict[str, Any]) -> str | None:
    """The data set a ledger row read, as ``DataSets.key`` in Java; None when it names none.

    Rows with journals: the sorted distinct ``<date>|<seed>`` lines, hashed. Two runs with the
    same dates and seeds share a key even when a model change altered the generated bytes.
    """
    seeds = entry.get("session_seeds")
    if isinstance(seeds, dict):
        return sessions_key(int(seeds["base"]), int(seeds["first"]), int(seeds["count"]))
    journals = entry.get("journals")
    if isinstance(journals, list) and journals:
        return _sessions_key({f"{j['date']}|{int(j['seed'])}" for j in journals})
    sha = entry.get("data_sha256")
    if isinstance(sha, str) and len(sha) >= 16:
        return "data:" + sha[:16]
    return None


def _describe(entry: dict[str, Any]) -> str:
    seeds = entry.get("session_seeds")
    if isinstance(seeds, dict):
        first = int(seeds["base"]) + int(seeds["first"])
        count = int(seeds["count"])
        return f"{count} simulator v2 sessions, seeds {first}..{first + count - 1}"
    journals = entry.get("journals")
    if isinstance(journals, list) and journals:
        dates = sorted({str(j["date"]) for j in journals})
        seeds = sorted({int(j["seed"]) for j in journals})
        return f"{len(dates)} sessions {dates[0]}..{dates[-1]}, seeds {seeds[0]}..{seeds[-1]}"
    data = entry.get("data")
    files = data if isinstance(data, list) else [data]
    return "files " + ", ".join(str(f["name"]) if isinstance(f, dict) else str(f) for f in files)


def looks_by_data_set(entries: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Ledger rows per data set key, keys in order of first use, rows in ledger order."""
    out: dict[str, list[dict[str, Any]]] = {}
    for e in entries:
        k = data_set_key(e)
        if k is not None:
            out.setdefault(k, []).append(e)
    return out


def undeclared_looks(
    entries: list[dict[str, Any]], key: str, prereg_id: str, declared: list[str]
) -> list[str]:
    """Ids that have read ``key`` and are neither ``prereg_id`` nor declared."""
    out: list[str] = []
    for e in entries:
        pid = str(e.get("prereg_id"))
        if data_set_key(e) == key and pid != prereg_id and pid not in declared and pid not in out:
            out.append(pid)
    return out


def looks_of(rows: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Rows grouped into looks, as ``DataSets.looksOf``: the rows of one family run (same
    ``family_run``) are one look, every other row is a look of its own."""
    out: list[list[dict[str, Any]]] = []
    runs: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        run = r.get("family_run")
        if isinstance(run, str) and run:
            if run not in runs:
                runs[run] = []
                out.append(runs[run])
            runs[run].append(r)
        else:
            out.append([r])
    return out


def looks_report(entries: list[dict[str, Any]]) -> str:
    """The looks of every data set as text, the same text as ``DataSets.report`` in Java."""
    groups = looks_by_data_set(entries)
    lines: list[str] = []
    repeated = 0
    unkeyed = len(entries)
    for key, rows in groups.items():
        unkeyed -= len(rows)
        looks = looks_of(rows)
        lines.append(
            f"{key}  {_describe(rows[0])}: {len(looks)} look{'' if len(looks) == 1 else 's'}"
        )
        for n, look in enumerate(looks, 1):
            r = look[0]
            if len(look) == 1:
                lines.append(
                    f"  look {n}  seq {r.get('seq')}  {r.get('prereg_id')}  {r.get('scope')}"
                    f"  {r.get('verdict')}  (recorded as look {r.get('look')})"
                )
            else:
                lines.append(
                    f"  look {n}  seq {r.get('seq')}..{look[-1].get('seq')}  {r.get('prereg_id')}"
                    f"  {r.get('scope')}  family {r.get('family_id')}, {len(look)} rows"
                    f"  (recorded as look {r.get('look')})"
                )
        if len(looks) > 1:
            repeated += 1
            ids = ", ".join(str(look[0].get("prereg_id")) for look in looks)
            lines.append(
                f"  warning: {len(looks)} looks at the same data by {ids}; treat their verdicts"
                " as one family, not as independent results"
            )
    tail = f"{len(entries)} ledger rows, {len(groups)} data sets, {repeated} read more than once"
    if unkeyed > 0:
        tail += f", {unkeyed} rows name no data"
    lines.append(tail)
    return "\n".join(lines) + "\n"


def benjamini_hochberg(p: list[float]) -> list[float]:
    """Adjusted p in input order, as ``Stats.benjaminiHochberg``."""
    m = len(p)
    order = sorted(range(m), key=lambda i: (p[i], i))
    adjusted = [0.0] * m
    running = 1.0
    for k in range(m - 1, -1, -1):
        i = order[k]
        running = min(running, p[i] * m / (k + 1))
        adjusted[i] = running
    return adjusted


def _family(spec: str) -> tuple[str, float, list[str], str | None, str | None]:
    """(name, q, members, data set key, family id) of a family spec, as ``Family.of``."""
    if spec.startswith("data_set:"):
        return spec, 0.05, [], spec[len("data_set:") :], None
    if spec.startswith("family:"):
        return spec, 0.05, [], None, spec[len("family:") :]
    path = Path(spec)
    if not path.is_file():
        raise ValueError(f"family file not found: {spec}")
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    q = raw.get("q")
    return (
        str(raw.get("family")),
        float(q) if _is_number(q) else 0.05,
        [str(x) for x in raw["members"]],
        None,
        None,
    )


def _member(
    e: dict[str, Any], members: list[str], data_set: str | None, family_id: str | None
) -> bool:
    if data_set is not None:
        return e.get("data_set") == data_set
    if family_id is not None:
        return e.get("family_id") == family_id
    return str(e.get("prereg_id")) in members


def family_report(entries: list[dict[str, Any]], spec: str) -> str:
    """Benjamini-Hochberg over a family's primary tests, the same text as ``LedgerCli --bh``.

    A row of a registered family (it carries ``hypothesis_id``) is one test; its verdict does not
    use the band rows, which the family reports as a secondary description only.
    """
    name, q, members, data_set, family_id = _family(spec)
    latest: dict[str, dict[str, Any]] = {}
    for e in entries:
        results = e.get("results")
        if (
            _member(e, members, data_set, family_id)
            and isinstance(results, dict)
            and isinstance(results.get("primary"), dict)
        ):
            latest[f"{e.get('prereg_id')}|{e.get('hypothesis_id', '')}"] = e
    tests = []
    for row in latest.values():
        for comparison, c in row["results"]["primary"].items():
            p = c.get("paired_p")
            tests.append(
                (
                    str(row["prereg_id"]),
                    comparison,
                    int(row["seq"]),
                    float(p) if _is_number(p) else 1.0,
                    c,
                    row,
                )
            )
    lines = [
        f"family {name} (q = {q:.2f}): {len(tests)} test{'' if len(tests) == 1 else 's'} from"
        f" {len(latest)} ledger row{'' if len(latest) == 1 else 's'}"
    ]
    if tests:
        adjusted = benjamini_hochberg([t[3] for t in tests])
        for (pid, comparison, seq, p, c, row), adj in zip(tests, adjusted, strict=True):
            guards = _guards(row, c)
            ci = f"ci [{_num(c.get('ci_low')):.2f}, {_num(c.get('ci_high')):.2f}]"
            if row.get("hypothesis_id") is not None:
                lines.append(
                    f"  {pid} {row['hypothesis_id']} {comparison}  seq {seq}  p {p:.8f}"
                    f"  bh {adj:.8f}  {ci}  guards {guards}"
                    f"  verdict {_family_verdict(c, adj, q, guards)}"
                )
                continue
            plus, minus = _signs(row, comparison)
            lines.append(
                f"  {pid} {comparison}_vs_baseline  seq {seq}  p {p:.8f}  bh {adj:.8f}  {ci}"
                f"  signs +{plus}/-{minus}  guards {guards}"
                f"  verdict {_verdict(c, adj, q, plus, minus, guards)}"
            )
    return "\n".join(lines) + "\n"


def _family_verdict(c: dict[str, Any], adjusted: float, q: float, guards: str) -> str:
    if guards == "fail":
        return "trading less"
    low, high = _num(c.get("ci_low")), _num(c.get("ci_high"))
    if adjusted < q and low > 0.0:
        return "supported if the guards hold" if guards == "unchecked" else "supported"
    if adjusted < q and high < 0.0:
        return "reversed"
    return "inconclusive"


def _num(v: Any) -> float:
    return float(v) if _is_number(v) else float("nan")


def _signs(row: dict[str, Any], comparison: str) -> tuple[int, int]:
    by = row["results"].get("signs_by_band", {}).get(comparison, {})
    return sum(1 for v in by.values() if v > 0), sum(1 for v in by.values() if v < 0)


def _guards(row: dict[str, Any], c: dict[str, Any]) -> str:
    minimums = row.get("guards_min")
    if not isinstance(minimums, dict) or not minimums:
        return "unchecked"
    ratios = c.get("guards", {})
    for k, v in minimums.items():
        r = ratios.get(f"{k}_ratio")
        if not _is_number(r) or r < float(v):
            return "fail"
    return "ok"


def _verdict(
    c: dict[str, Any], adjusted: float, q: float, plus: int, minus: int, guards: str
) -> str:
    if guards == "fail":
        return "trading less"
    low, high = _num(c.get("ci_low")), _num(c.get("ci_high"))
    if adjusted < q and low > 0.0 and minus == 0 and plus > 0:
        return "supported if the guards hold" if guards == "unchecked" else "supported"
    if adjusted < q and high < 0.0:
        return "reversed"
    if plus > 0 and minus > 0:
        return "not robust"
    return "inconclusive"


def main(argv: list[str] | None = None) -> int:
    """Verifies a ledger's chain and prints its looks per data set; 1 when the chain is broken.

    ``bh LEDGER FAMILY`` adds the family's Benjamini-Hochberg table; ``validate FILE...`` prints
    each pre-registration's problems (paths relative to the repository root, the working
    directory) and returns 2 when any has one.
    """
    args = sys.argv[1:] if argv is None else argv
    if args and args[0] == "validate":
        status = 0
        for f in args[1:]:
            problems = validate(f, Path.cwd())
            print(f"{f}: valid" if not problems else f"{f}: {len(problems)} problem(s)")
            for problem in problems:
                print(f"  {problem}")
            status = status if not problems else 2
        return status
    family = None
    if args and args[0] == "bh":
        if len(args) != 3:
            print("usage: python -m ratesresearch.prereg bh LEDGER FAMILY", file=sys.stderr)
            return 2
        family = args[2]
        args = [args[1]]
    if len(args) != 1:
        print("usage: python -m ratesresearch.prereg LEDGER", file=sys.stderr)
        return 2
    path = Path(args[0])
    if not path.is_file():
        print(f"ledger not found: {path}")
        return 1
    try:
        entries = read_ledger(path)
    except ValueError as e:
        print(e)
        return 1
    print(f"ledger chain verified: {len(entries)} lines")
    print(looks_report(entries), end="")
    if family is not None:
        try:
            print(family_report(entries, family), end="")
        except ValueError as e:
            print(e)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
