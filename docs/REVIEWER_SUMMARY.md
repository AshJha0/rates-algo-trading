# Reviewer summary

One page for a reader with five minutes. Written 2026-11-23 from the review of that date ([docs/reviews/REVIEW_2026-11-23.md](reviews/REVIEW_2026-11-23.md)); updated 2026-11-24 for release 1.2.0.

## What it is

A USD rates dealer desk in miniature, in Java 25 with a Python research package: analytics, in-process venue simulators behind FIX 4.4, a single-writer event loop with hash-chained journals, quoting, risk, hedging, a smart order router, a pre-registered back-tester and post-trade. Everything trades against simulators in the same process.

It was built by parallel AI coding sessions directed by one engineer, from 2026-10-31 (pull requests #1 to #10 and the 1.2.0 integration). Verification is CI (nine required checks on Linux and Windows) plus external goldens. There has been no independent human review. Pull requests are merged by the owner with no approving review (the `main` ruleset requires 0 approvals).

Release 1.2.0 builds the review's Rounds 3 to 7 as a second version of each component (a calibrated simulator, pricing, strategy, router), selectable as a desk profile. **The default desk is unchanged, no experiment on the new components has been run, and no P&L claim is made.**

## Four things that are real evidence

1. **Pricing against external references.** 225 instruments priced by QuantLib 1.44 reproduce to about 1e-15 (`AnalyticsGoldenTest`). All 84 note and bond and 332 bill auctions of 2024 reproduce to the published digits (`PublishedExamplesTest`). Pricing v2 has its own golden in which QuantLib prices every instrument and SciPy solves the curves (`PricingV2GoldenTest`, docs/research/PRICING_V2.md Section 11).
2. **A simulator calibrated to public data.** Three rate factors explain 98.9% of daily par-yield variance; intraday swap volatility, competitor spreads and arrivals are fitted or bounded from DTCC prints, with 95% intervals. What the data cannot identify is stated as an assumption with a band (docs/research/SIM_V2_CALIBRATION.md). The old simulator's adverse-selection constants are outside what the data supports.
3. **Deterministic replay, and an honest statement of its scope.** A recorded day replays to an equal outbound hash (`SmokeTest`, `LoopReplayTest`). A stepped v2 day repeats frame for frame; a threaded live v2 day does not repeat run to run and is not claimed to (DR-V12-P03).
4. **A ledger that records failures, and a benchmark that records its own.** 32 ledger rows, most "not supported", kept. The router benchmark's check, declared before its one run, failed against passive-with-deadline and is recorded as failed (docs/research/ROUTER_BENCHMARK.md).

## Four biggest limits

1. **The default desk's P&L is still fixed by declared constants.** Its half-spread is clamped to 0.02 to 0.08 bp; the version 1 simulator's informed flow costs about 0.20 bp per swap trade. The loss of about 109k USD per session (AUDIT-21) is that arithmetic. Both stay the defaults in 1.2.0.
2. **Nothing new has been measured on P&L.** The family registration R5-FAM-001 (v2 desk against default, six ablations, 2,000 shared sessions, Benjamini-Hochberg) is unsigned and not run. <!-- FAMILY-REG --> Its sensitivity rows cover only the harder side of two parameters.
3. **The v2 components have stated gaps.** Spread risk is shown, not hedged; four Treasuries do not identify the on-the-run premium; a pricing v2 reprice costs several hundred microseconds on the loop; the hedge planner never chose the future at its declared costs.
4. **Latency evidence is from a loaded laptop.** The wired-desk benchmark measures the real process over loopback FIX with no run discarded, but on a Windows laptop running other builds: default profile p50 about 25 ms (idle threads waiting on the Windows timer), `lowLatency` over TLS p50 0.2 to 0.7 ms, the desk's own handler 7.5 to 15 us. <!-- QUIET-RUN --> A quiet-machine run is owed.

## Where to look in five minutes

| Minute | Look at | What it shows |
|---|---|---|
| 1 | README "Limits" | What is simulated, opt-in, missing or not wired |
| 2 | [docs/research/SIM_V2_CALIBRATION.md](research/SIM_V2_CALIBRATION.md) Section 1 | What the public data supports, and what is assumed |
| 3 | `rates-app/src/main/java/io/ratesalgo/app/loop/DeskProfile.java` | The recorded desk and the v2 desk as two values |
| 4 | [docs/research/ROUTER_BENCHMARK.md](research/ROUTER_BENCHMARK.md) and `data/ledger/ledger.jsonl` | Results, failures included |
| 5 | [docs/reviews/REVIEW_2026-11-23.md](reviews/REVIEW_2026-11-23.md), follow-up section | Each finding's status after 1.2.0 |
