"""Validate every deploy/config/*.yaml against the process configuration schema.

The Java processes run the same check at start (io.ratesalgo.ops.config.ProcessConfig); this
script is the independent cross-check with the reference Python jsonschema implementation, run
in CI by .github/workflows/ops.yml. It also checks that the known-bad fixtures are refused, so a
schema that accepts everything cannot pass.

Beyond the schema it applies the rules a schema of this subset cannot state, the same ones
ProcessConfig applies: every password is named by exactly one of ...Env and ...File, transport
tls has its trust store and key store, and a bind address that is not loopback has http.tls.
What it cannot check statically (that a variable is set, that a file is readable) the process
checks when it starts.

Each tools/fixtures/invalid-security-*.yaml carries a first line "# expect: TEXT"; the fixture
must be refused with a message that contains TEXT.

Usage: python tools/validate_config.py [repo root]
"""

from __future__ import annotations

import ipaddress
import json
import sys
from pathlib import Path
from typing import Any

import jsonschema
import yaml

EXPECT = "# expect: "


def is_loopback(address: str) -> bool:
    if address == "localhost":
        return True
    try:
        return ipaddress.ip_address(address).is_loopback
    except ValueError:
        return False


def section(parent: dict[str, Any], key: str) -> dict[str, Any]:
    value = parent.get(key)
    return value if isinstance(value, dict) else {}


def check_store(part: dict[str, Any], where: str, kind: str, errors: list[str]) -> None:
    by_env = f"{kind}PasswordEnv" in part
    by_file = f"{kind}PasswordFile" in part
    if f"{kind}Path" in part:
        if by_env == by_file:
            errors.append(f"{where}: give exactly one of {kind}PasswordEnv and {kind}PasswordFile")
    elif by_env or by_file:
        errors.append(f"{where}: a {kind} password is named but {kind}Path is missing")


def structural_errors(doc: dict[str, Any]) -> list[str]:
    """The rules of ProcessConfig.structuralErrors, plus the static half of the bind rule."""
    errors: list[str] = []
    http = section(doc, "http")
    check_store(section(http, "tls"), "http/tls", "keyStore", errors)
    if "tls" not in http and not is_loopback(str(http.get("bindAddress", ""))):
        errors.append("http: a bindAddress that is not loopback needs http.tls")
    fix = section(doc, "fix")
    tls = section(fix, "tls")
    acceptor = section(fix, "acceptor")
    check_store(tls, "fix/tls", "trustStore", errors)
    check_store(tls, "fix/tls", "keyStore", errors)
    check_store(acceptor, "fix/acceptor", "keyStore", errors)
    check_store(acceptor, "fix/acceptor", "trustStore", errors)
    if fix.get("transport") == "tls":
        if "trustStorePath" not in tls:
            errors.append("fix: transport tls needs tls.trustStorePath")
        if "keyStorePath" not in acceptor:
            errors.append("fix: transport tls needs acceptor.keyStorePath")
    if acceptor.get("requireClientCertificate") is True and not (
            "trustStorePath" in acceptor and "keyStorePath" in tls):
        errors.append("fix/acceptor: requireClientCertificate needs acceptor.trustStorePath and tls.keyStorePath")
    credentials = fix.get("credentials")
    if isinstance(credentials, dict) and ("passwordEnv" in credentials) == ("passwordFile" in credentials):
        errors.append("fix/credentials: give exactly one of passwordEnv and passwordFile")
    reconnect = section(fix, "reconnect")
    if reconnect.get("initialBackoffMillis", 100) > reconnect.get("maxBackoffMillis", 2000):
        errors.append("fix/reconnect: initialBackoffMillis is above maxBackoffMillis")
    return errors


def all_errors(validator: jsonschema.Draft202012Validator, path: Path) -> list[str]:
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    found = sorted(validator.iter_errors(doc), key=str)
    if found:
        return [f"{'/'.join(str(p) for p in e.absolute_path)}: {e.message}" for e in found]
    # like the Java loader: the structural rules read a document of the right shape only
    return structural_errors(doc)


def main(root: Path) -> int:
    schema_path = root / "deploy" / "config" / "schema" / "process-config.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    failures = 0
    configs = sorted((root / "deploy" / "config").glob("*.yaml"))
    if not configs:
        print("no configuration files found")
        return 1
    for path in configs:
        errors = all_errors(validator, path)
        if errors:
            failures += 1
            print(f"INVALID {path.relative_to(root)}")
            for e in errors:
                print(f"        {e}")
        else:
            print(f"valid   {path.relative_to(root)}")
    fixtures = root / "tools" / "fixtures"
    bad = fixtures / "invalid-config.yaml"
    bad_errors = all_errors(validator, bad)
    if len(bad_errors) < 3:
        print(f"schema accepted the known-bad fixture ({len(bad_errors)} errors, expected at least 3)")
        failures += 1
    else:
        print(f"refused {bad.relative_to(root)} with {len(bad_errors)} errors, as expected")
    security = sorted(fixtures.glob("invalid-security-*.yaml"))
    if len(security) < 6:
        print(f"only {len(security)} invalid-security fixtures found, expected at least 6")
        failures += 1
    for path in security:
        first = path.read_text(encoding="utf-8").splitlines()[0]
        expected = first[len(EXPECT):].strip() if first.startswith(EXPECT) else ""
        errors = all_errors(validator, path)
        if expected and len(errors) == 1 and expected in errors[0]:
            print(f"refused {path.relative_to(root)}: {errors[0]}")
        else:
            failures += 1
            print(f"WRONG   {path.relative_to(root)}: expected one error containing '{expected}', found {errors}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
