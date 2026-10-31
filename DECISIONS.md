# Decision records

Each record states the context at the time, the decision, and the consequences we accept. Records are never edited after the fact; a later record supersedes an earlier one and says so.

## DR-001: Repository name and licence

Date: 2026-10-31

Context. The project needs a name that says what it is and a licence that lets anyone read, run and reuse it from the first commit, including people assessing the work.

Decision. The repository is `AshJha0/rates-algo-trading`, licensed under Apache License 2.0 with copyright 2026 Ashish Jha.

Amended 2026-10-31 (owner). The repository was created public and switched to private the same day; it stays private until the owner is satisfied with the finished work, then becomes public. Everything is still written as if public: no raw downloaded data, credentials or third-party licensed content is committed (see DR-006), and the word policy (DR-007) applies throughout.

Consequences. GitHub Pages does not publish from a private repository on a free plan, so the Docs workflow builds the site with `--strict` on every push but the deploy step stays skipped until the repository is public and the Pages source is set to GitHub Actions. Apache 2.0's patent grant and notice requirements apply to contributors.

## DR-002: Java for the hot path and venue layer, Python for research

Date: 2026-10-31

Context. The platform needs a low-latency event core and venue gateways, and a separate research workflow for calibration and analysis. Choices for the core were Java, C++ and Rust.

Decision. Java (JDK 25 LTS, DR-003) for the event core, analytics used on the hot path, venue gateways and simulators. Python 3.12 for research, calibration and analysis. No C++ or Rust unless a measured latency gap on the pinned benchmark host (DR-005) demands it, in which case a decision record states the gap and the component.

Consequences. The Java hot path must be allocation-free and single-writer to meet latency goals without native code; this is checked by tests, not assumed. Research code is not on the latency path and may use pandas freely. The Python and Java sides share pinned contracts (API_SPEC.md) rather than code.

## DR-003: JDK 25 LTS toolchain with a JDK 27 forward-compatibility leg

Date: 2026-10-31

Context. The project should build on a long-term-support JDK and learn early about breakage on the next release.

Decision. The gating toolchain is JDK 25 LTS, on Linux and Windows. CI also runs an informational leg on JDK 27 (not gating). The gating toolchain moves to JDK 29 LTS when it ships and the dependencies support it.

Consequences. Language features up to Java 25 may be used. A JDK 27 failure is a tracked issue, not a blocked merge. The move to 29 will need a decision record naming the date and the dependency versions.

## DR-004: USD SOFR swaps and US Treasuries first; EUR and GBP follow with a stated assumption

Date: 2026-10-31

Context. Free transaction-level data exists for USD rates (DTCC SDR public dissemination, FINRA TRACE Treasury aggregates) but there is no comparable free tape for EUR or GBP swaps or government bonds.

Decision. P1 analytics implement USD SOFR swaps and US Treasuries first, with EUR and GBP calendars, day counts and schedule conventions in the same phase. EUR and GBP quoting arrive in P3. Venue-simulator calibration for EUR and GBP reuses the USD-fitted distribution shapes (arrival rates, size distributions, response times) with a scaling assumption stated in `data/presets.yaml`, because no free EUR or GBP transaction tape exists to fit them.

Consequences. Any EUR or GBP result carries a note that the venue parameters are scaled from USD fits. If a free EUR or GBP tape becomes available, a decision record replaces the scaling with a fit.

## DR-005: Where latency is measured

Date: 2026-10-31

Context. Hosted CI runners share hardware with unknown neighbours, so their timings are not comparable run to run.

Decision. For CI, latency jobs run on the development machine (recorded by model, CPU, kernel or OS build, and JVM flags in every report) and are informational. Gating thresholds apply only on a pinned Linux host when one is available. Every report records the machine it ran on.

Consequences. `docs/BENCHMARKS.md` carries no numbers until a report exists, and every number it later carries names its machine and date. A regression seen only on a hosted runner is investigated, not acted on.

## DR-006: Public data licensing

Date: 2026-10-31

Context. The project uses public data from government and market sources, each with its own terms. Redistribution of raw downloads may be prohibited or require attribution.

Decision. The repository commits fitted parameters (`data/presets.yaml`) and small fixtures needed by tests, never raw downloads. For each source, `data/SOURCES.md` records the licence or terms to verify, and the research package records a SHA-256 and a manifest for each file used (`ratesresearch.provenance`). Fixtures are small enough to be clearly derived works and name their source.

Consequences. Reproducing a fit requires re-downloading the source; the manifest lets a reader confirm they have the same bytes. A source whose terms turn out to forbid even derived parameters is removed and the record says so.

## DR-007: Naming of role-preparation material

Date: 2026-10-31

Context. The learning text includes question-and-answer material useful for preparing for technical discussions. The owner's standing instruction is that public repositories do not use a particular word for such material.

Decision. All such material is titled as learning material or as a knowledge check (LEARN.md Section 15). The repository does not use the word that names a hiring conversation, and the pull request template includes a check for it.

Consequences. Contributors run a grep before pushing. The content is unchanged; only the framing is.

## DR-008: Competition model for RFQ win probability

Date: 2026-10-31

Context. A dealer quoting into a multi-dealer RFQ needs a model of the probability of winning as a function of distance from mid. Fitting that model requires seeing the quotes of competing dealers. Public data (DTCC SDR) shows executed trades only, never the quotes that lost.

Decision. Win probability is a declared assumption band, not a fitted quantity. The quoting engine (P3) takes a parametrised curve (for example logistic in price distance with a stated slope and midpoint) and the back-tester (P5) reports results across the declared band, not at a point. The band and its rationale are written in `data/presets.yaml` and in LEARN.md Section 7.

Consequences. No result from this project can claim a fitted hit rate. Sensitivity to the band is reported with every quoting result. If a data source with quote-level RFQ data becomes available under acceptable terms, a decision record replaces the assumption with a fit.

## DR-P5-01: Preset schema gains declared assumption bands

Date: 2026-10-31

Context. DR-008 makes the RFQ win-probability slope a declared band, and the P0 preset skeleton had nowhere to write a band. The quoting engine (P3) and the back-tester both read the hit-rate preset, so the field is a cross-module contract.

Decision. A preset may carry `assumptions`: a map from parameter name to `{band: {low, central, high}, rationale}`. Declared presets use `fitted_from: assumption`, `sample_size: 0` and `standard_error: null`. Dates are written as quoted strings so that YAML readers do not turn them into timestamps. The schema is pinned in API_SPEC.md Section 16.2 and validated on both sides (`ratesresearch.presets.validate_preset`, `io.ratesalgo.backtest.model.Presets`).

Consequences. `usd_rfq_hit_rate` carries fitted coefficients with standard errors and the band for `spread_to_cover_bp` (low -1.25, central -2.5, high -5.0 per bp). Any preset written by another module keeps working: the field is optional and `write_preset` replaces only the preset of its own name.

## DR-P5-02: Common random numbers keyed by RFQ id

Date: 2026-10-31

Context. A back-test compares strategies over the same journals. If each run drew outcomes from one sequential stream, a strategy that declines one RFQ would shift every later draw, and the difference between strategies would carry noise unrelated to the strategies.

Decision. Each RFQ settles from its own SplitMix64 stream derived from the run seed and the RFQ id; the first draw is the uniform that decides the outcome, then the competitors. RFQ ids are unique across sessions (`epochDay x 1,000,000 + n`).

Consequences. Two strategies on the same journal face the same competitors and uniforms, so paired comparisons are much tighter than unpaired ones (in P5-001 the paired intervals were above zero at two band points while Welch's test was not significant). Outcomes stay deterministic whatever the strategy quotes. The pre-registered test of P5-001 was Welch's, which ignores the pairing; a paired test needs a new pre-registration.

## DR-P5-03: Pre-registration, refusals and an append-only, hash-chained ledger

Date: 2026-10-31

Context. The owner's research-governance practice (the intraday-alpha-platform repository) pre-registers experiments, counts every look at the data, keeps holdouts unread and keeps negative results. The back-tester needs the same discipline, adapted to synthetic sessions and to one maintainer.

Decision. An experiment runs only through `ExperimentCli` with a valid pre-registration (API_SPEC.md Section 16.8). It refuses a missing or invalid file, a holdout overlapping the fitting or evaluation period, an evaluation period overlapping the fitting period, a changed config (SHA-256 pinned) and a second opening of a holdout. A refused run reads no data and is not recorded. A started run is recorded in `data/ledger/ledger.jsonl` whatever its verdict, with the look number and the SHA-256 of the previous line. Results are always reported at the three band points (DR-008).

Consequences. The first experiment, P5-001, is recorded as not supported (ledger line 1, 2026-10-31) and stays in the ledger. The hash chain detects edits but not a rewrite of the whole file; review of the ledger's diff in pull requests is the control for that, as CONTRIBUTING.md asks for a signed pre-registration to have its own pull request.

## DR-P5-04: Public trade arrivals stand in for RFQ arrivals; FINRA Treasury data shipped as a fixture

Date: 2026-10-31

Context. No public source shows RFQs. DTCC SDR public dissemination shows executed USD swap trades with time, tenor and capped notional, and its end-of-day cumulative files download without an account. FINRA's Treasury transaction data is served through an API that returned HTTP 401 without an authenticated account on 2026-10-31.

Decision. The RFQ intensity shape and the swap size distribution are fitted from DTCC trades (USD SOFR OIS new trades, not part of a package, spot-starting, standard tenors; capped notionals treated as right-censored) over nine business days, 2025-10-01 to 2025-10-14 without 2025-10-13, a SIFMA-recommended bond market close. The level is scaled by a declared `rfq_share_of_market`. For FINRA, `ratesresearch.data.finra` is a loader against a documented layout and `data/fixtures/finra/treasury_trades_fixture.csv` is a synthetic file in that layout, labelled synthetic; nothing fitted from it feeds the simulator.

Consequences. Treasury RFQ flow uses declared assumptions (`bond_rfq_ratio`, `bond_size_mu_ln_mm`) until FINRA data can be fetched with an account; replacing them needs a decision record and a manifest. The DTCC manifest `data/manifests/dtcc_rates_2025-10.json` lets a reader confirm they downloaded the same bytes.

## DR-P5-05: Linear valuation in the back-tester until P1 pricers are wired

Date: 2026-10-31

Context. The P1 analytics (curves, swap and bond pricers) are built in parallel and are not available to this phase.

Decision. The back-tester values positions linearly in the quoted price with a fixed value per unit: annuity times 1e-4 for swaps (long is the payer swap), 1e-4 for bonds and futures; DV01 uses a fixed price change per bp. Carry and roll is a `CarryModel` hook returning zero; funding accrues on bonds at a fixed 4.25 percent Act/360.

Consequences. The attribution identity holds exactly, which is what the reconciliation test needs, but convexity and curve shape are absent. When P1 is wired, the instrument set and the carry hook change and the report schema does not.

## DR-P5-06: Floating-point reproducibility relies on HotSpot's Math on x86-64

Date: 2026-10-31

Context. The synthetic generator, the competitor model and the hit-rate model use `Math.log`, `Math.exp`, `Math.pow` and `Math.sin`/`cos`, whose results may differ by an ulp between JVM implementations or CPU architectures. `StrictMath` is bit-identical everywhere but would change every number already recorded in the ledger.

Decision. Keep `Math`. Determinism is claimed for the same JVM build on x86-64, which is what CI runs on Linux and Windows; the determinism test, the cookbook outputs and `FirstExperimentTest` check it on every push. Moving to `StrictMath` is a change of the synthetic world and would need a new pre-registration for any experiment re-run on it.

Consequences. A result produced on another architecture (for example ARM) may differ in the last digits; such a run is a new look, not a reproduction. The Java-Python statistics cross-check does not depend on this, since the bootstrap uses only integer draws.

## DR-P5-07: SnakeYAML in rates-backtest

Date: 2026-10-31

Context. The back-tester reads `data/presets.yaml`, pre-registrations, experiment configs and ledger lines, all YAML or JSON.

Decision. `rates-backtest` depends on SnakeYAML 2.5 with its safe constructor and an implicit resolver without timestamps (dates stay strings). JSON output is written by a small canonical writer (`util.Json`) so ledger lines have stable bytes.

Consequences. One more dependency, confined to a module that is off the hot path; hot-path modules (P2) do not depend on `rates-backtest`.
