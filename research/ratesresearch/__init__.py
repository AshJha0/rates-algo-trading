"""Research package for rates-algo-trading.

Phase 0 contents: data-source provenance records and the FNV-1a 64 chain hash
that mirrors the Java journal (API_SPEC.md Section 1). Calibration, curve
fitting and back-test analysis arrive in later phases.
"""

from ratesresearch.fnv import FNV_OFFSET_BASIS, FNV_PRIME, fnv1a64
from ratesresearch.provenance import (
    SOURCES,
    DataSource,
    load_manifest,
    sha256_of,
    write_manifest,
)

__all__ = [
    "FNV_OFFSET_BASIS",
    "FNV_PRIME",
    "SOURCES",
    "DataSource",
    "fnv1a64",
    "load_manifest",
    "sha256_of",
    "write_manifest",
]

__version__ = "0.0.1"
