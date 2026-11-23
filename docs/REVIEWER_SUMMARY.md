# Reviewer summary

One page for a reader with five minutes. Written 2026-11-23 from the review of that date ([docs/reviews/REVIEW_2026-11-23.md](reviews/REVIEW_2026-11-23.md)).

## What it is

A USD rates dealer desk in miniature, in Java 25 with a Python research package: analytics, in-process venue simulators behind FIX 4.4, a single-writer event loop with hash-chained journals, quoting, risk, hedging, a smart order router, a pre-registered back-tester and post-trade. Everything trades against simulators in the same process.

It was built by parallel AI coding sessions directed by one engineer, mostly between 2026-10-31 and 2026-11-08 (pull requests #1 to #9). Verification is CI (nine required checks on Linux and Windows) plus external goldens. There has been no independent human review. Pull requests are merged by the owner with no approving review (the `main` ruleset requires 0 approvals).

## Three things that are real evidence

1. **Pricing against external references.** 224 instruments priced by QuantLib 1.44 are reproduced to about 1e-15 (`AnalyticsGoldenTest`, API_SPEC.md 7.2). All 84 note and bond and 332 bill auctions of 2024 reproduce to the published digits (`PublishedExamplesTest`). Compounded legs reproduce the published 2024 SOFR, EUR STR and SONIA indices (`CompoundedIndexHoldoutTest`). Some other goldens (Hull-White, basis, carry, the bootstrap) are the same formulas retyped in Python, so they check transcription, not the model (review A10).
2. **Deterministic replay.** A recorded day replays through the same desk code to an equal outbound hash (`SmokeTest`, `LoopReplayTest`, `BacktestBindingTest`). The journal chain is checked from Python too (COOKBOOK recipe 35).
3. **A pre-registration ledger that records failures.** 32 rows in `data/ledger/ledger.jsonl`; most verdicts are "not supported", and they are kept. Pre-registrations were committed before results. The ledger has weaknesses: looks are counted per pre-registration rather than per data set, and no correction is applied across rows (review Section 2.4).

## Three biggest limits

1. **Every P&L figure is fixed by declared simulator constants.** The desk's half-spread is clamped to 0.02 to 0.08 bp (`DeskSettings.java:59-60`); the simulator's informed flow costs about 0.20 bp per swap trade in expectation (20% informed x 4 x 0.25 bp, `rates-venues/.../presets.json`). The loss of about 109k USD per session (AUDIT-21) is that arithmetic, not a market finding. The positive figures (P5-001) come from clients who follow the model the quoter is optimised against.
2. **The trading process has no independent view of value.** Swap fair value comes from a curve whose pillars are the swaps being quoted; bond fair value is the book mid; bond DV01 is taken on the SOFR OIS curve (`AnalyticsPricing.java:99-108, 234, 278-283`). Most of `rates-analytics` is not used by the wired process.
3. **Safety and performance claims are narrower than they look.** The review found that a dead loop thread can leave quotes live and that the kill switch travels through that thread (review L1, L2); a fix round is under way and STATUS.md records its state. The router benchmark compares against single-shot orders in the router's own simulator. The sub-microsecond latency figures time a constant-arithmetic bench quoter on a Windows laptop with a 100 ns clock tick, not the wired desk.

## Where to look in five minutes

| Minute | Look at | What it shows |
|---|---|---|
| 1 | README "Limits" | What is simulated, missing or not wired |
| 2 | `rates-analytics/src/test/java/io/ratesalgo/analytics/golden/AnalyticsGoldenTest.java` | The external pricing check |
| 3 | `rates-app/src/main/java/io/ratesalgo/app/loop/DeskSettings.java` | The desk's spread clamp, limits and single client tier |
| 4 | `data/ledger/ledger.jsonl` and docs/research/PNL_AUDIT.md | Every experiment and its verdict |
| 5 | [docs/reviews/REVIEW_2026-11-23.md](reviews/REVIEW_2026-11-23.md) Section 9 | The ordered fix plan |
