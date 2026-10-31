"""Deployment rehearsal: start the processes, check readiness, exercise the kill switch, check
that every quote is pulled and that the kill is journaled.

Modes:
  process  start the trading and venue-simulator processes as local JVMs (no Docker). This is
           the mode CI runs (.github/workflows/ops.yml).
  docker   bring up deploy/compose.yaml and run the same checks against the published ports.

Before process mode, package the jars:
    mvn -B -ntp -DskipTests package -pl rates-app -am

The trading process is rates-app's TradingProcessMain (API_SPEC.md Section 18.1): the D2C and
CLOB simulators run inside it on simulated time from 08:25 New York, paced at ten times real
time, so RFQs arrive within seconds of readiness.

Usage: python deploy/rehearsal.py --mode process [--java PATH] [--timeout SECONDS]
Exit status 0 when every check passes.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADD_OPENS = [
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
]
SCHEMA = ROOT / "deploy" / "config" / "schema" / "process-config.schema.json"
KILL_SWITCH_TEMPLATE = 31
REQUESTED_BY = 9001


class Rehearsal:
    def __init__(self) -> None:
        self.failures = 0

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        print(f"{'PASS' if ok else 'FAIL'}  {name}{(': ' + detail) if detail else ''}", flush=True)
        if not ok:
            self.failures += 1


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def http(method: str, url: str, headers: dict[str, str] | None = None) -> tuple[int, str]:
    req = urllib.request.Request(url, method=method, headers=headers or {})
    if method == "POST":
        req.data = b""
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def wait_until(predicate: Callable[[], bool], timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return True
        except OSError:
            pass
        time.sleep(0.25)
    return False


def journal_templates(path: Path) -> list[int]:
    """Template ids of every frame in a RAJ1 journal (API_SPEC.md Section 1)."""
    data = path.read_bytes()
    if data[:4] != b"RAJ1":
        raise ValueError(f"{path} is not a RAJ1 journal")
    out = []
    pos = 32
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            break
        (template,) = struct.unpack_from("<H", data, pos + 20)
        out.append(template)
        pos += (length + 7) & ~7
    return out


def classpath(module: str) -> str:
    target = ROOT / module / "target"
    jars = sorted(target.glob(f"{module}-*.jar"))
    jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars or not (target / "lib").is_dir():
        raise SystemExit(f"{module} is not packaged; run: mvn -B -ntp -DskipTests package -pl {module} -am")
    return os.pathsep.join([str(jars[0]), str(target / "lib" / "*")])


def checks(r: Rehearsal, trading: str, venue: str, token: str, journal_dir: Path | None) -> None:
    # A quote lives for the venue's five-second response window, so poll until one is live.
    seen: dict[str, object] = {}

    def quoting() -> bool:
        status, body = http("GET", f"{trading}/control/status")
        seen.update(json.loads(body) if status == 200 else {})
        return int(str(seen.get("liveQuotes", 0))) > 0

    r.check("trading process quoting before the kill", wait_until(quoting, 60.0), json.dumps(seen))
    r.check("venue simulator ready", http("GET", f"{venue}/health/ready")[0] == 200)
    status, _ = http("POST", f"{trading}/control/kill")
    r.check("kill refused without the control token", status == 403, f"status {status}")
    status, body = http("POST", f"{trading}/control/kill",
                        {"X-Control-Token": token, "X-Requested-By": str(REQUESTED_BY)})
    r.check("kill accepted", status == 200, body.strip())
    state = json.loads(body) if status == 200 else {}
    r.check("every quote pulled", state.get("liveQuotes") == 0 and state.get("killSwitch") is True, body.strip())
    r.check("quoting disabled", state.get("quoting") is False)
    metrics = http("GET", f"{trading}/metrics")[1]
    r.check("metrics show zero live quotes", "\nrates_quotes_live 0\n" in metrics)
    r.check("metrics show the kill switch engaged", "\nrates_kill_switch_engaged 1\n" in metrics)
    r.check("still live after the kill", http("GET", f"{trading}/health/live")[0] == 200)
    if journal_dir is not None:
        journals = sorted(journal_dir.glob("*/in.*.raj"))
        templates = journal_templates(journals[0]) if journals else []
        r.check("kill switch journaled", KILL_SWITCH_TEMPLATE in templates, f"templates {templates}")


def run_process_mode(java: str, timeout: float) -> int:
    r = Rehearsal()
    work = Path(tempfile.mkdtemp(prefix="rates-rehearsal-"))
    token = secrets.token_hex(16)
    procs: list[subprocess.Popen[bytes]] = []
    try:
        cp = classpath("rates-app")
        main = "io.ratesalgo.app.TradingProcessMain"

        bad = work / "bad.yaml"
        bad.write_text((ROOT / "deploy/config/trading-blue.yaml").read_text(encoding="utf-8")
                       .replace("years: 5", "years: 1"), encoding="utf-8")
        refused = subprocess.run([java, *ADD_OPENS, "-cp", cp, main, "--config", str(bad), "--schema", str(SCHEMA)],
                                 capture_output=True, timeout=timeout, check=False)
        r.check("invalid configuration refused at start", refused.returncode == 2,
                f"exit {refused.returncode}")

        trading_port, venue_port = free_port(), free_port()
        env = dict(os.environ, RATES_CONTROL_TOKEN=token)
        for config, port, journal in (("trading-blue", trading_port, work / "journal"),
                                      ("venue-sim", venue_port, work / "venue-journal")):
            log = (work / f"{config}.log").open("wb")
            procs.append(subprocess.Popen(
                [java, *ADD_OPENS, "-cp", cp, main, "--config", str(ROOT / "deploy/config" / f"{config}.yaml"),
                 "--schema", str(SCHEMA)],
                env=dict(env, RATES_HTTP_PORT=str(port), RATES_JOURNAL_DIR=str(journal)),
                stdout=log, stderr=subprocess.STDOUT))
        trading = f"http://127.0.0.1:{trading_port}"
        venue = f"http://127.0.0.1:{venue_port}"
        ready = wait_until(lambda: http("GET", f"{trading}/health/ready")[0] == 200
                           and http("GET", f"{venue}/health/ready")[0] == 200, timeout)
        r.check("processes ready", ready)
        if ready:
            checks(r, trading, venue, token, work / "journal")
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
        if r.failures:
            for log in sorted(work.glob("*.log")):
                print(f"--- {log.name}")
                print(log.read_text(encoding="utf-8", errors="replace"))
        shutil.rmtree(work, ignore_errors=True)
    print(f"rehearsal (process mode): {'passed' if r.failures == 0 else str(r.failures) + ' check(s) failed'}")
    return 1 if r.failures else 0


def run_docker_mode(timeout: float) -> int:
    if shutil.which("docker") is None:
        print("docker is not installed; use --mode process")
        return 2
    r = Rehearsal()
    token = secrets.token_hex(16)
    compose = ["docker", "compose", "-f", str(ROOT / "deploy" / "compose.yaml")]
    env = dict(os.environ, RATES_CONTROL_TOKEN=token)
    try:
        subprocess.run([*compose, "up", "-d", "--build", "trading-blue", "venue-sim"], env=env, check=True)
        trading, venue = "http://127.0.0.1:8081", "http://127.0.0.1:8090"
        ready = wait_until(lambda: http("GET", f"{trading}/health/ready")[0] == 200, timeout)
        r.check("containers ready", ready)
        if ready:
            checks(r, trading, venue, token, None)
    finally:
        subprocess.run([*compose, "down", "-v"], env=env, check=False)
    print(f"rehearsal (docker mode): {'passed' if r.failures == 0 else str(r.failures) + ' check(s) failed'}")
    return 1 if r.failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["process", "docker"], default="process")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    if args.mode == "docker":
        return run_docker_mode(args.timeout)
    return run_process_mode(args.java, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
