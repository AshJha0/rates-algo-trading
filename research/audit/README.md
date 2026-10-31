# P&L audit runs

Source of the measured numbers in `docs/research/PNL_AUDIT.md`.

- `PnlAudit.java`: the harness. Runs the variants named in a pre-registration's config (`research/prereg/AUDIT-*.config.yaml`) through the existing back-tester (`rates-backtest`, public API only) on seeded synthetic days at the three band points. It appends one ledger row and refuses a second evaluation look. Its `engine` variant must reproduce the back-tester's `model_spread` to the cent on every run (`results.replica_check`).
- `run_audit.sh`: builds `rates-backtest` from this checkout and launches the harness against the checkout's own `target/classes`, never jars in `~/.m2` from another checkout.
- `results/AUDIT-0n.json`: copies of the four ledger rows (seq 2 to 5 of `data/ledger/ledger.jsonl` on this branch). `results/summary.md` is the output of `summarize.py`, which re-verifies the ledger chain and recomputes every interval and Holm p from the stored daily P&L.
- `pilot/pilot_ledger.jsonl`: the harness check on fitting-period days (2026-01-05 to 2026-01-07) before the pre-registrations were committed. Its numbers are not used.

To reproduce one run on a scratch ledger:

```bash
research/audit/run_audit.sh research/prereg/AUDIT-01.yaml --ledger /tmp/ledger.jsonl --work /tmp/audit
```

Note on merging: the ledger is a hash chain. Rows appended on two branches (this one and r2-edge-research both append after seq 1) conflict on merge, and the later rows need `seq` and `prev_sha256` recomputed in merge order. Row content and the pre-registration hashes stay unchanged.
