"""Pre-registered edge studies EDGE-E1 to EDGE-E6 on public data (research/prereg/).

Modules: ``sources`` (downloads and manifests), ``build`` (raw files to the small derived
fixtures committed under ``data/fixtures/edge/``), ``common`` (bootstrap, Holm, verdicts,
ledger), ``e1_auction`` to ``e6_carry`` (one study each), ``run`` (the programme: every study,
Holm across the family, ledger rows, presets). The notebooks ``research/notebooks/edge_*.ipynb``
recompute every number from the derived fixtures.
"""

READ_END = "2026-06-30"
"""Last date any study reads. Later dates are the pre-registered reserve and stay unread."""
