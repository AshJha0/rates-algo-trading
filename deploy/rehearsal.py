"""Deployment rehearsal: start the processes, check readiness, exercise the kill switch, check
that every quote is pulled and that the kill is journaled, then (process mode) kill the trading
process hard and check that it comes back from its journals.

The processes run as deployed: FIX over TLS with logon credentials and sequence resume, and the
health, metrics and control endpoints over HTTPS. The rehearsal makes its own throwaway test
material each run, with the keytool that sits next to the java it starts: one self-signed
certificate for localhost and 127.0.0.1 in a PKCS#12 key store (the in-process FIX acceptors and
the HTTP server), a trust store holding that certificate (the FIX initiators), and the
certificate as PEM (this script and curl). Passwords are random, reach keytool and the processes
through environment variables only, and are deleted with the work directory. Nothing made here
is for production and nothing is committed.

Modes:
  process  start the trading and venue-simulator processes as local JVMs (no Docker). This is
           the mode CI runs (.github/workflows/ops.yml).
  docker   bring up deploy/compose.yaml and run the same checks against the published ports.

Before process mode, package the jars:
    mvn -B -ntp -DskipTests package -pl rates-app -am

The trading process is rates-app's TradingProcessMain (API_SPEC.md Section 18.1): the D2C and
CLOB simulators run inside it on simulated time from 08:25 New York, paced at ten times real
time, so RFQs arrive within seconds of readiness.

Inside the trading image (ops.yml "images" job) the jars are already in /opt/rates/lib, so pass
--classpath "/opt/rates/lib/*" instead of packaging them again.

Usage: python deploy/rehearsal.py --mode process [--java PATH] [--classpath CP] [--timeout SECONDS]
Exit status 0 when every check passes.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import secrets
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from http.client import HTTPException
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

# Where the committed configurations expect the stores (deploy/config/*.yaml). Process mode
# rewrites these paths to the generated files; docker mode mounts the generated directory there.
CERTS_MOUNT = "/run/rates/certs"
KEY_STORE = "server-keystore.p12"
TRUST_STORE = "truststore.p12"
CA_PEM = "ca.pem"
# The variables the committed configurations name for the store passwords.
STORE_PASSWORD_ENVS = ("RATES_HTTP_KEYSTORE_PASSWORD", "RATES_FIX_TRUSTSTORE_PASSWORD",
                       "RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD")
FIX_PASSWORD_ENV = "RATES_FIX_PASSWORD"
KEYTOOL_PASS_ENV = "RATES_KEYTOOL_STOREPASS"

# Set once the test material exists: every https request verifies against the generated certificate.
TLS_CONTEXT: ssl.SSLContext | None = None


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


@dataclasses.dataclass(frozen=True)
class Material:
    """Throwaway TLS material and passwords for one rehearsal."""

    directory: Path
    key_store: Path
    trust_store: Path
    ca_pem: Path
    store_password: str
    fix_password: str

    def env(self) -> dict[str, str]:
        """The password variables the configurations name. Values never go on a command line."""
        out = {name: self.store_password for name in STORE_PASSWORD_ENVS}
        out[FIX_PASSWORD_ENV] = self.fix_password
        return out


def keytool_for(java: str) -> str:
    """The keytool next to the java the rehearsal runs, so both agree on store formats."""
    resolved = shutil.which(java) or java
    name = "keytool.exe" if os.name == "nt" else "keytool"
    beside = Path(resolved).resolve().parent / name
    if beside.is_file():
        return str(beside)
    found = shutil.which("keytool")
    if found is None:
        raise SystemExit(f"no keytool next to {resolved} or on the PATH; the rehearsal needs it for test certificates")
    return found


def generate_material(java: str, directory: Path) -> Material:
    """A self-signed certificate for localhost and 127.0.0.1, its key store, trust store and PEM."""
    global TLS_CONTEXT
    directory.mkdir(parents=True, exist_ok=True)
    material = Material(directory, directory / KEY_STORE, directory / TRUST_STORE, directory / CA_PEM,
                        secrets.token_urlsafe(24), secrets.token_urlsafe(24))
    keytool = keytool_for(java)
    # -storepass:env keeps the password out of the process listing
    env = dict(os.environ, **{KEYTOOL_PASS_ENV: material.store_password})
    store = ["-storetype", "PKCS12", "-storepass:env", KEYTOOL_PASS_ENV]
    for args in (
        ["-genkeypair", "-alias", "rehearsal", "-keyalg", "EC", "-groupname", "secp256r1",
         "-dname", "CN=localhost, OU=test material, O=rates-algo-trading",
         "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-startdate", "-1d", "-validity", "2",
         "-keystore", str(material.key_store), *store],
        ["-exportcert", "-rfc", "-alias", "rehearsal", "-keystore", str(material.key_store), *store,
         "-file", str(material.ca_pem)],
        ["-importcert", "-noprompt", "-alias", "rehearsal", "-file", str(material.ca_pem),
         "-keystore", str(material.trust_store), *store],
    ):
        done = subprocess.run([keytool, *args], env=env, capture_output=True, timeout=120, check=False)
        if done.returncode != 0:
            raise SystemExit(f"keytool {args[0]} failed (exit {done.returncode}): "
                             f"{done.stdout.decode(errors='replace').strip()}")
    TLS_CONTEXT = tls13_context(str(material.ca_pem))
    return material


def rehearsal_config(name: str, material: Material, work: Path) -> Path:
    """A copy of a committed configuration whose store paths point at the generated files."""
    text = (ROOT / "deploy" / "config" / f"{name}.yaml").read_text(encoding="utf-8")
    for file, path in ((KEY_STORE, material.key_store), (TRUST_STORE, material.trust_store)):
        # a quoted scalar: a Windows or oddly named temporary directory stays one YAML string
        text = text.replace(f"{CERTS_MOUNT}/{file}", json.dumps(path.as_posix()))
    out = work / f"{name}.yaml"
    out.write_text(text, encoding="utf-8")
    return out


def http(method: str, url: str, headers: dict[str, str] | None = None) -> tuple[int, str]:
    req = urllib.request.Request(url, method=method, headers=headers or {})
    if method == "POST":
        req.data = b""
    try:
        with urllib.request.urlopen(req, timeout=15, context=TLS_CONTEXT if url.startswith("https") else None) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def tls13_context(cafile: str | None = None) -> ssl.SSLContext:
    """A verifying client context that refuses anything older than TLS 1.3."""
    context = ssl.create_default_context(cafile=cafile)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    return context


def tls_version(url: str) -> str:
    """The protocol a verified handshake with the endpoint settles on, or the reason it failed."""
    host, port = url.split("://", 1)[1].split(":")
    try:
        context = TLS_CONTEXT if TLS_CONTEXT is not None else tls13_context()
        with (socket.create_connection((host, int(port)), timeout=10) as raw,
              context.wrap_socket(raw, server_hostname=host) as tls):
            return str(tls.version())
    except OSError as e:
        return f"handshake failed: {e}"


def plain_http_refused(url: str) -> tuple[bool, str]:
    """True when the port gives no HTTP answer at all to a request sent in clear text."""
    plain = "http://" + url.split("://", 1)[1] + "/health/live"
    try:
        with urllib.request.urlopen(plain, timeout=10) as r:
            return False, f"answered {r.status}"
    except urllib.error.HTTPError as e:
        return False, f"answered {e.code}"
    except (OSError, HTTPException) as e:
        return True, type(e).__name__


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
        status, body = http("GET", f"{trading}/control/status", {"X-Control-Token": token})
        seen.update(json.loads(body) if status == 200 else {})
        return int(str(seen.get("liveQuotes", 0))) > 0

    r.check("trading process quoting before the kill", wait_until(quoting, 60.0), json.dumps(seen))
    r.check("venue simulator ready", http("GET", f"{venue}/health/ready")[0] == 200)
    status, _ = http("GET", f"{trading}/control/status")
    r.check("status refused without the control token", status == 403, f"status {status}")
    version = tls_version(trading)
    r.check("control endpoint served over TLS", trading.startswith("https://") and version == "TLSv1.3", version)
    refused, detail = plain_http_refused(trading)
    r.check("plain HTTP refused on the control port", refused, detail)
    status, _ = http("POST", f"{trading}/control/kill")
    r.check("kill refused without the control token", status == 403, f"status {status}")
    status, body = http("POST", f"{trading}/control/kill",
                        {"X-Control-Token": token, "X-Requested-By": str(REQUESTED_BY)})
    # 200 only when the process confirmed the withdrawal; it answers 503 with the reason otherwise
    r.check("kill confirmed", status == 200, body.strip())
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


def restart_check(r: Rehearsal, restart: Callable[[], None], trading: str, token: str, timeout: float) -> None:
    """Kill the trading process hard, start it on the same journals, check that it recovered."""
    status, body = http("GET", f"{trading}/control/status", {"X-Control-Token": token})
    before = json.loads(body) if status == 200 else {}
    if "recovered" not in before:
        print('SKIP  restart recovery: the status JSON has no "recovered" key, so this build does not '
              "report restart recovery", flush=True)
        return
    restart()
    seen: dict[str, object] = {}

    def back() -> bool:
        code, text = http("GET", f"{trading}/control/status", {"X-Control-Token": token})
        seen.update(json.loads(text) if code == 200 else {})
        return code == 200

    r.check("trading process back after a hard kill", wait_until(back, timeout), json.dumps(seen))
    r.check("restarted process recovered from its journals", seen.get("recovered") is True, json.dumps(seen))
    ready: list[tuple[int, str]] = [(0, "")]

    def settled() -> bool:
        ready[0] = http("GET", f"{trading}/health/ready")
        return ready[0][0] == 200 or "kill" in ready[0][1].lower()

    # Whether the kill switch outlives the restart is the journal's call; either answer is a pass,
    # anything else (still recovering, a session down) is not.
    wait_until(settled, timeout)
    code, text = ready[0]
    r.check("restarted process ready, or held by the journaled kill switch",
            code == 200 or "kill" in text.lower(), f"status {code}: {text.strip()}")
    back()
    print(f"INFO  after the restart: readiness {code} ({text.strip()}), status {json.dumps(seen)}", flush=True)


def run_process_mode(java: str, timeout: float, cp: str | None = None) -> int:
    r = Rehearsal()
    work = Path(tempfile.mkdtemp(prefix="rates-rehearsal-"))
    token = secrets.token_hex(16)
    procs: list[subprocess.Popen[bytes]] = []
    try:
        cp = cp or classpath("rates-app")
        main = "io.ratesalgo.app.TradingProcessMain"
        material = generate_material(java, work / "certs")
        configs = {name: rehearsal_config(name, material, work) for name in ("trading-blue", "venue-sim")}

        bad = work / "bad.yaml"
        bad.write_text(configs["trading-blue"].read_text(encoding="utf-8").replace("years: 5", "years: 1"),
                       encoding="utf-8")
        refused = subprocess.run([java, *ADD_OPENS, "-cp", cp, main, "--config", str(bad), "--schema", str(SCHEMA)],
                                 capture_output=True, timeout=timeout, check=False)
        r.check("invalid configuration refused at start", refused.returncode == 2,
                f"exit {refused.returncode}")

        trading_port, venue_port = free_port(), free_port()
        env = dict(os.environ, RATES_CONTROL_TOKEN=token, **material.env())

        def launch(config: str, port: int, journal: Path, log_name: str) -> subprocess.Popen[bytes]:
            log = (work / log_name).open("wb")
            return subprocess.Popen(
                [java, *ADD_OPENS, "-cp", cp, main, "--config", str(configs[config]), "--schema", str(SCHEMA)],
                env=dict(env, RATES_HTTP_PORT=str(port), RATES_JOURNAL_DIR=str(journal)),
                stdout=log, stderr=subprocess.STDOUT)

        procs.append(launch("trading-blue", trading_port, work / "journal", "trading-blue.log"))
        procs.append(launch("venue-sim", venue_port, work / "venue-journal", "venue-sim.log"))
        trading = f"https://127.0.0.1:{trading_port}"
        venue = f"https://127.0.0.1:{venue_port}"
        ready = wait_until(lambda: http("GET", f"{trading}/health/ready")[0] == 200
                           and http("GET", f"{venue}/health/ready")[0] == 200, timeout)
        r.check("processes ready", ready)
        if ready:
            checks(r, trading, venue, token, work / "journal")

            def restart() -> None:
                # no shutdown hook runs: this is the crash the journals are there for
                procs[0].kill()
                procs[0].wait(timeout=30)
                procs[0] = launch("trading-blue", trading_port, work / "journal", "trading-blue-restart.log")

            restart_check(r, restart, trading, token, timeout)
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


def run_docker_mode(java: str, timeout: float) -> int:
    if shutil.which("docker") is None:
        print("docker is not installed; use --mode process")
        return 2
    r = Rehearsal()
    token = secrets.token_hex(16)
    compose = ["docker", "compose", "-f", str(ROOT / "deploy" / "compose.yaml")]
    certs = Path(tempfile.mkdtemp(prefix="rates-rehearsal-certs-"))
    try:
        # The file names are the ones the committed configurations expect under CERTS_MOUNT; the
        # compose file mounts this directory there, read-only. The containers run as their own
        # user, so the throwaway files are made readable to it.
        material = generate_material(java, certs)
        certs.chmod(0o755)
        for file in certs.iterdir():
            file.chmod(0o644)
        env = dict(os.environ, RATES_CONTROL_TOKEN=token, RATES_CERTS_DIR=str(certs), **material.env())
        try:
            subprocess.run([*compose, "up", "-d", "--build", "trading-blue", "venue-sim"], env=env, check=True)
            trading, venue = "https://127.0.0.1:8081", "https://127.0.0.1:8090"
            ready = wait_until(lambda: http("GET", f"{trading}/health/ready")[0] == 200, timeout)
            r.check("containers ready", ready)
            if ready:
                checks(r, trading, venue, token, None)
        finally:
            subprocess.run([*compose, "down", "-v"], env=env, check=False)
    finally:
        shutil.rmtree(certs, ignore_errors=True)
    print(f"rehearsal (docker mode): {'passed' if r.failures == 0 else str(r.failures) + ' check(s) failed'}")
    return 1 if r.failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["process", "docker"], default="process")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--classpath", help="use this class path instead of rates-app/target")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    if args.mode == "docker":
        return run_docker_mode(args.java, args.timeout)
    return run_process_mode(args.java, args.timeout, args.classpath)


if __name__ == "__main__":
    sys.exit(main())
