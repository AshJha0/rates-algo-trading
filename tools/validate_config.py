"""Validate every deploy/config/*.yaml against the process configuration schema.

The Java processes run the same check at start (io.ratesalgo.ops.config.ProcessConfig); this
script is the independent cross-check with the reference Python jsonschema implementation, run
in CI by .github/workflows/ops.yml. It also checks that the known-bad fixture is refused, so a
schema that accepts everything cannot pass.

Usage: python tools/validate_config.py [repo root]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import jsonschema
import yaml


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
        errors = sorted(validator.iter_errors(yaml.safe_load(path.read_text(encoding="utf-8"))), key=str)
        if errors:
            failures += 1
            print(f"INVALID {path.relative_to(root)}")
            for e in errors:
                print(f"        {'/'.join(str(p) for p in e.absolute_path)}: {e.message}")
        else:
            print(f"valid   {path.relative_to(root)}")
    bad = root / "tools" / "fixtures" / "invalid-config.yaml"
    bad_errors = list(validator.iter_errors(yaml.safe_load(bad.read_text(encoding="utf-8"))))
    if len(bad_errors) < 3:
        print(f"schema accepted the known-bad fixture ({len(bad_errors)} errors, expected at least 3)")
        failures += 1
    else:
        print(f"refused {bad.relative_to(root)} with {len(bad_errors)} errors, as expected")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
