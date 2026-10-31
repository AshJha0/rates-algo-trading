"""QuantLib-based golden values for the Java analytics (API_SPEC.md Section 8).

``python -m ratesresearch.goldens`` writes ``data/golden/analytics_golden.json``. QuantLib is an
optional dependency (``pip install -e "research[golden]"``); nothing else in the package imports it.
"""

from ratesresearch.goldens.generator import GOLDEN_VERSION, generate

__all__ = ["GOLDEN_VERSION", "generate"]
