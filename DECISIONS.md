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

## DR-P1-01: Dates as epoch-day integers

Date: 2026-10-31

Context. Pricing touches dates in every inner loop (schedules, daily compounding over thirty years, calendar lookups). `java.time.LocalDate` is an object: every arithmetic step allocates.

Decision. Analytics hold dates as `int` days since 1970-01-01 (`io.ratesalgo.analytics.date.Dates`, civil-date conversion after H. Hinnant). Calendars precompute business days from 1950 to 2149 into a bit set. `java.time` is used only by builders, tests and messages.

Consequences. Date arithmetic allocates nothing and is exact. Property tests compare every field and `addMonths` with `java.time` for random dates (`DatePropertiesTest`). A date outside 1950 to 2149 still works but evaluates the holiday rule on each call.

## DR-P1-02: Versioned calendars, separate fixing and payment calendars

Date: 2026-10-31

Context. Holidays are added at short notice (the UK bank holiday for the State Funeral of Queen Elizabeth II on 2022-09-19 was announced on 2022-09-10). A replay of 9 September 2022 must use the calendar as it was known then. Separately, an overnight index is published on its own calendar, which is not the payment calendar (SOFR is not published on Good Friday even when SIFMA recommends only an early close, as on 2021-04-02 and 2023-04-07).

Decision. Every calendar is a `VersionedCalendar`: an unchanging standing rule plus ad hoc closures and openings, each with the date from which it was known; `asOf(date)` returns the calendar as known on that date. The standing rules reproduce QuantLib 1.44 so that the golden comparisons hold (checked on every weekday from 2000 to 2060). `SwapConvention` names three calendars: schedule, payment and fixing. USD swaps accrue and pay on the joint New York Fed and SIFMA calendar and compound over SOFR publication days.

Consequences. Adding a holiday never rewrites history. `CompoundedIndexHoldoutTest` checks every day of 2021, 2023 and 2024 on which the USD fixing and payment calendars differ (one day each in 2021 and 2023, none in 2024) and that the 2024 daily grid equals the published fixing dates exactly.

## DR-P1-03: Curves on log discount factors; sequential or global bootstrap

Date: 2026-10-31

Context. The curve must reprice its inputs exactly, support three interpolation schemes (linear on log discount factors, monotone convex, tension spline), rebuild quickly when one quote changes, and give exact derivatives for risk.

Decision. Nodes are log discount factors at instrument pillars; extrapolation extends the last average forward. The bootstrap solves one instrument per pillar to 1e-12 in quote units. If the interpolation is local and no instrument depends on dates past its pillar, pillars are solved in order by Brent's method; otherwise all are solved together by Levenberg-Marquardt with the analytic Jacobian, started from a sequential sweep (`CurveBuilder.Method.AUTO`). An incremental rebuild re-solves only from the changed pillar (sequential) or warm-starts the global solve. Overnight forwards follow central bank meetings by pillars at meeting dates under linear interpolation; turns are explicit spreads.

Consequences. Linear interpolation matches QuantLib's `PiecewiseLogLinearDiscount`, which the golden uses; the two non-local schemes are checked by round trip, incremental rebuild and gradient tests only (QuantLib's convex-monotone implementation has different defaults). Instruments may not share a pillar.

## DR-P1-04: Key-rate DV01 by algorithmic differentiation through the bootstrap

Date: 2026-10-31

Context. The risk engine (P3) needs a DV01 ladder by curve input for every position, often and cheaply. Bumping each of thirty inputs costs sixty curve builds per position.

Decision. Pricers compute the gradient of value with respect to the curve's log discount factors in one adjoint pass (interpolation weights are differentiated exactly, including the monotone convex region formulas). The bootstrap Jacobian maps that gradient to sensitivities with respect to the quotes (one transposed solve). The key-rate DV01 of an input is its sensitivity times one basis point of rate (minus 0.01 in price for a future); the parallel DV01 is their sum. Bumping (central, one basis point) stays as an independent check. The ladder is the value type `Dv01Ladder` in API_SPEC.md Section 8.3: currency units per basis point, positive when the position gains as rates rise.

Consequences. The key-rate sum equals the parallel DV01 to rounding by construction. Agreement with bumping is a test, not an assumption (1.2e-8 of the parallel DV01 for a GBP 7Y swap; 1.4e-6 relative against QuantLib's bumps, which is the bumps' own truncation error).

## DR-P1-05: Futures convexity by Hull-White one-factor with stated parameters

Date: 2026-10-31

Context. SOFR futures settle daily, so their rate exceeds the forward rate. The adjustment needs a model and parameters, and the project has no licensed volatility data.

Decision. Hull-White one-factor closed form (Hull, Options, Futures and Other Derivatives, Section 6.4) with mean reversion 0.03 and short-rate volatility 0.0085 per year, applied with t1 and t2 the start and end of the reference period. For in-arrears compounded futures this is an approximation (the exact in-arrears adjustment differs at second order); its size is not measured in this phase.

Consequences. The adjustment is an input recorded in the golden file and checked to 1e-15 against the Java formula. Parameters are declared, not fitted; a fit to listed options would replace them with a new record.

## DR-P1-06: QuantLib golden generation and the platform rule

Date: 2026-10-31

Context. CONTRIBUTING.md says goldens are regenerated on Linux only. This session had only a Windows machine. QuantLib's helpers also carry defaults (schedule calendar of an OIS helper, pillar choice) that could hide a convention mismatch.

Decision. The golden file is produced by `research/ratesresearch/goldens/generator.py` with QuantLib 1.44 (pinned in the `golden` extra). The generator bootstraps the curves itself, a sequential Brent search in which each instrument is a QuantLib object built with explicit schedules and calendars, so QuantLib does all the pricing and nothing depends on helper defaults. The first file was generated on Windows 11 on 2026-10-31. `research/tests/test_goldens.py` regenerates it and compares every number to 1e-12 relative (absolute floor 1e-12); CI installs QuantLib and runs it on Linux and Windows, so a platform difference fails a build rather than passing silently.

Consequences. This is an exception to the Linux-only rule for one file, made visible by the regeneration test. If the Linux job ever disagrees beyond 1e-12, the file is regenerated on Linux and this record is superseded.

## DR-P1-07: Three bond yield conventions, named

Date: 2026-10-31

Context. "Yield" means different things: the street convention (nominal coupon dates, compounded fractional period), the true yield (business-day payment dates) and the Treasury's auction formula (31 CFR 356 Appendix B, simple interest over the first fractional period, with the dirty price and accrued interest each rounded to six decimals).

Decision. `BondPricer` offers each under its own name: `yieldFromClean(..., adjusted=false)` street, `adjusted=true` true yield, `treasuryAuctionPrice` and `treasuryAuctionYield` for Appendix B. Gilts use the street form with the seven-business-day ex-dividend rule. Repo term points beyond overnight are declared inputs to `RepoCurve`, because the NY Fed publishes only overnight TGCR and BGCR.

Consequences. All 2024 note, bond and bill auction results reproduce to the published digits, which the street formula alone does not (it misses 44 of 84 note and bond prices by up to 0.0066). No UK DMO worked example is in the gate yet: the DMO site refused scripted downloads.

## DR-P1-08: Declared market quotes for the golden valuation date

Date: 2026-10-31

Context. Swap and futures quotes for a historical date are licensed data. Fixings, Treasury auctions and central bank indices are public.

Decision. The golden valuation date is 2025-01-15. Curve quotes (overnight estimate, FOMC meeting OIS, SOFR futures, OIS par rates in USD, EUR and GBP, EUR 6M swaps) are declared values close to the market levels of that period, written in the generator. Past fixings are the published SOFR, EUR STR and SONIA from `data/fixtures`. Bond yields and futures baskets are declared from a smooth curve.

Consequences. The golden file tests the analytics, not a market. No result is presented as a historical market value.

## DR-P1-09: Swap schedule details

Date: 2026-10-31

Context. Several OIS details vary between documents and systems: the end-of-month rule, the pillar of an OIS rate helper, how the payment lag is counted.

Decision. End-of-month rule off for swaps; payment date is the period end moved by the payment lag in business days on the payment calendar (following); the pillar of a par OIS is its last payment date on either leg (QuantLib's `LastRelevantDate`); a forward-starting swap starts at spot plus the forward period moved to the next business day; an IMM swap starts on the first IMM date after spot.

Consequences. These are the definitions the golden file uses. A desk convention that differs is a new `SwapConvention`, not a change to these.
## DR-P4-01: Router messages as fixed-length header and rows, template ids 80 to 82, schema version unchanged

Date: 2026-10-31

Context. Every routing decision must be reconstructible from the journal, so the per-venue cost inputs and the split must be journaled. A decision has a variable number of venues. SBE supports repeating groups, but schema v1 is all fixed-length messages (API_SPEC.md Section 2) and the brief reserves template ids 80 to 89 for the router.

Decision. A decision is one RouteDecision (80) followed by one RouteDecisionVenue (81) per venue, linked by `decisionId` and a row index; child order audit records are ChildOrderTransition (82). Five enums are appended to the types. The schema version stays 0: the change only adds types and messages with new ids, no existing field moves, and no journal written with v1 exists in a release.

Consequences. Readers of a decision must collect rows; `DecisionCodec` does so and rejects rows out of sequence. If another phase adds messages in the same round, the coordinator merges the XML; if the coordinator prefers to bump the schema version for the combined additions, these messages move with it unchanged.

## DR-P4-02: Re-route only quantity the venue has confirmed cancelled

Date: 2026-10-31

Context. A child cancelled for escalation or a book move may have fills in flight. Re-routing its whole remainder at once can make the children exceed the parent when those fills arrive.

Decision. The router re-routes only quantity a Cancelled report (or reconciliation) confirms will not fill: ordered minus the larger of our cumulative and the venue's reported cumulative. The state machine keeps the invariant cum + leaves + cancelled = ordered, with late fills reducing leaves.

Consequences. Escalation waits one venue round trip. The parent can never be overfilled by the router (`RouterChaosPropertiesTest`). A venue that reported a cancel with a cumulative below what it had filled would break the guarantee; the book counts that as an anomaly.

## DR-P4-03: Declared cost-model defaults; refit deferred

Date: 2026-10-31

Context. The brief asks for square-root impact parameters refitted from simulator data. The P2 venue simulators are not available to this phase.

Decision. Ship a declared preset (config version 1) in `data/presets.yaml` under `router`, mirrored in `RouterPresets` and checked by `RouterPresetsTest`. The queue model and the passive-or-aggressive comparison reuse the maths of the owner's earlier execution library (AshJha0/electronic-trading 2.0.0, API_SPEC.md Sections 8.3, 13.1 and 13.2), adapted to this project's units and rewritten to be allocation-free.

Consequences. Costs are model estimates under stated assumptions. A refit from P2 simulator output, with a manifest, replaces the preset under a new config version.

## DR-P4-04: Greedy marginal allocation

Date: 2026-10-31

Context. The allocation is a small integer program over a handful of venues with minimum sizes, lots and caps, solved on the hot path.

Decision. Greedy by marginal cost per unit in blocks of a common increment, at most `maxSteps` steps, ties to the lower row.

Consequences. Optimal on the increment lattice for convex per-venue costs without minimum sizes (checked against enumeration); a heuristic otherwise. Bounded, deterministic and allocation-free.

## DR-P4-05: Cookbook recipe numbered 17

Date: 2026-10-31

Context. The brief names the router recipe 16; COOKBOOK.md's planned list gives 16 to the P3 kill-switch recipe and 17 to "route a hedge order and read the markout report (P4)".

Decision. The repository wins: the router recipe is 17, and it prints a markout line as the planned title says.

Consequences. No collision with the P3 recipe.

Amendment (2026-10-31, branch integration). When the phase branches were combined, the recipes were renumbered to run contiguously from 1 in phase order: P1 4 to 9, P2 venues 10 to 11, P2 event core 12 to 13, P3 14 to 16, P4 17, P5 18 to 20, P6 21. The router recipe stays 17 (`Recipe17RouteHedge`, `expected/recipe17.txt`); P5 moved from 17 to 19 to 18 to 20, and P6 from 20 to 21. Recipe content is unchanged.

## DR-P4-06: The router routes one instrument across venues; the hedger chooses the instrument

Date: 2026-10-31

Context. A rates hedge can be done in cash bonds or futures; a futures contract and a cash bond are different instruments with basis risk between them.

Decision. A parent names one instrument; the router splits it across the venues that list it. Venue kind (CLOB or futures) selects default parameters only. Choosing between cash and futures, and the hedge ratio, belongs to the P3 hedger.

Consequences. The router does not net or convert between instruments. A cross-instrument router would be a later change with its own decision record.
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
## DR-P6-01: Post-trade messages in schema v1, named PostTradeBust

Date: 2026-10-31

Context. The post-trade side needs a message that cancels a booked trade and one that corrects it, journaled so that replay reproduces the books. The brief named the bust message `TradeBust` with template id 100. While P6 was being built, the P2 venues branch added its own `TradeBust` (template 44) for a venue's FIX ExecType H on an order fill. Two messages with one name cannot both be generated into `io.ratesalgo.messages.sbe`.

Decision. P6 adds `PostTradeBust` (100) and `TradeAmend` (101) to `rates-schema-v1.xml` with three new enums, without changing any existing message and without bumping the schema version. `PostTradeBust` covers every cancellation after execution: a venue bust of an order fill or an RFQ trade, a clearing rejection, an erroneous-trade decision and a manual bust. At integration the coordinator keeps P2's `TradeBust` (44) as the gateway's message and adds one case to `PositionKeeper.onFrame` that applies it as a `PostTradeBust` with reason VenueError.

Consequences. The name differs from the brief; the repository wins and this record says why. Whether adding messages warrants a schema version bump is decided once for all branches at integration, since every phase adds messages in its own id range. Ids 102 to 119 remain free for P6.

## DR-P6-02: Positions recomputed from live trades in transaction-time order

Date: 2026-10-31

Context. Late fills, busts and amendments all change a position's history, not only its present. An incremental keeper would need a reversal rule for each case, and weighted average cost depends on order.

Decision. The keeper stores every trade version and computes each position from its live trades sorted by transaction time (then journal sequence, then key), with weighted average cost, caching the result until a trade in that position changes. Money is `BigDecimal` with 34 significant digits; rounding to the minor unit happens only when a figure is reported.

Consequences. A late fill or a bust gives the same position as if it had arrived in order, which `PositionKeeperTest.lateFillLandsInTransactionOrder` checks. Recomputation costs a sort per changed position, acceptable off the hot path at a rates desk's trade count and far simpler to audit than reversal rules. The event loop's own risk position (P3) is incremental and is reconciled to this keeper, not the other way round.

## DR-P6-03: Marking policy for official P&L

Date: 2026-10-31

Context. Official P&L must reconcile line by line with the back-tester's attribution and must not depend on a desk's own mid.

Decision. Official P&L uses official closes only and stops if one is missing; positions are re-struck at the previous official close at start of day; realised, unrealised and carry are rounded half-even to the minor unit separately and the total is their sum; the reconciliation with the attribution file has a tolerance of zero minor units per component. The attribution format is pinned in API_SPEC.md Section 17.5 for P5 to produce.

Consequences. A missing close is an operational incident, not a guess. Rounding per component can differ from rounding the total by up to one minor unit per component; both sides round the same way, so the reconciliation is exact.

## DR-P6-04: Reconciliation breaks are explained from the journal

Date: 2026-10-31

Context. A reconciliation run at a cut sees breaks that are not errors: fills that arrive after the cut, busts after the cut, clearing rejections, audited amendments. Treating them as errors hides the real ones; ignoring them hides them all.

Decision. Every break is classified (MISSING_OURS, MISSING_THEIRS, QUANTITY, PRICE, TIMING) and then explained from a keeper that has read the whole journal (`KeeperFacts`). Only explanations backed by a journaled event clear a break. Position reconciliations adjust our side for the explained trade breaks so that the same fact is not counted twice. The acceptance measure is zero unexplained breaks.

Consequences. The break report shows explained breaks rather than suppressing them, so operations sees the late fills and busts of the day. A break is closed by a journaled action, never by editing a report.

## DR-P6-05: Operations layer without frameworks

Date: 2026-10-31

Context. The processes need health, readiness and control endpoints, Prometheus metrics and configuration validation. Web frameworks, metrics client libraries and JSON Schema libraries bring dependency trees larger than the platform.

Decision. The JDK's built-in HTTP server on one thread; a hand-written Prometheus text-format exporter (counters, gauges, fixed-bucket histograms); YAML parsed with SnakeYAML's safe constructor; a validator for the subset of JSON Schema the configuration schema uses, which refuses any other keyword so a schema cannot rely on a rule it would skip. CI cross-checks the configuration with the Python `jsonschema` package. SnakeYAML is the only new runtime dependency.

Consequences. About 600 lines of code to own instead of several dependencies to track. The control endpoint is token protected and bound to the management network; it is not a general web service.

## DR-P6-06: Warm standby by journal replication with a fenced quoting lease

Date: 2026-10-31

Context. RTS 6 Article 14 asks for tested business continuity arrangements. A standby must take over without a second process ever quoting the same book.

Decision. The standby applies the active process's journal frames, received over Aeron, through the same handlers; nothing but the journal is handed over. A quoting lease with an epoch number fences the two processes: the active stops quoting by itself if it cannot renew, the standby takes the lease only after expiry, and order ids carry the epoch. On takeover the standby runs the restart procedure, which reconciles with every venue before quoting. Design in docs/runbooks/warm-standby-design.md; the restart procedure, venue status hooks and blue/green switch are built now, the replication channel and lease at integration.

Consequences. Recovery, planned switches and disaster recovery use one mechanism. Takeover time is bounded by the lease expiry and the venue round trips, which the failover test at integration measures.

## DR-P6-07: Order records in the Annex II layout, filed by date

Date: 2026-10-31

Context. UK RTS 6 Article 28 and Annex II set the content and format of order records; records must be kept for five years and produced on request, typically by order id. legislation.gov.uk marks UK RTS 6 as revoked by the Financial Services and Markets Act 2023 from a date set by commencement regulations, as the FCA restates it in its Handbook.

Decision. One record per order event with the Annex II Table 2 and Table 3 fields that apply to the flow, written as CSV in one directory per trading date, append only, retained for five years (configurable to seven) and searchable by venue order id. The record links back to the journal sequence. The commencement status is flagged in docs/compliance/RTS6_INVENTORY.md for the owner to confirm.

Consequences. The record layout is readable without the platform. A production archive would add an index and write-once storage; the layout is the contract.
## DR-P2C-01: Two journals per loop, and replay compares the outbound stream by chain hash

Date: 2026-10-31

Context. The brief asks for every inbound and every outbound message to be journaled, and for replay to compare the outbound messages it produces with the recorded ones. A RAJ1 frame carries a template id and a schema version but no direction, and some messages (Heartbeat, KillSwitch) can travel either way.

Decision. Each loop writes two journals with the same clock: an inbound journal (inputs, plus the loop's own clock ticks, backpressure events and errors) and an outbound journal (every message the loop produced). Replay feeds the inbound journal to a loop built by the same `LoopFactory` and chains each produced message with `ChainHash` from the genesis hash, comparing frame by frame with the recorded outbound journal (API_SPEC.md Section 9.7).

Consequences. The comparison needs no new format: the outbound journal's own chain hash is the recorded hash. The order between an inbound message and the outbound messages it caused is not in one file; it is reproduced exactly by replay and can be derived from the latched timestamps, which both journals share per duty cycle. A single interleaved audit file, if a reader wants one, is a tool over the two journals.

## DR-P2C-02: Time is latched once per duty cycle, and timer firings are journaled as clock ticks

Date: 2026-10-31

Context. Handlers stamp messages with the current time, and timers fire whenever the loop polls. Live, the loop polls between messages at times no journal records; in replay, time moves only from frame to frame. Without care, outbound stamps and timer outputs differ between live and replay.

Decision. `EventClock` reads the source clock once at the start of each duty cycle; every handler in the cycle and every frame journaled in it see that value. When a timer poll fires at least one timer, the loop journals a clock tick (a schema-1 Heartbeat with `sourceId` 0, reserved for the loop) after the cycle's messages; replay polls the timer wheel only on clock ticks. The live clock is `AnchoredClock`, which derives wall time from the monotonic clock and an anchor taken at start of day, because `SystemClock.epochNanos()` may allocate.

Consequences. Replay reproduces stamps and timer outputs exactly (`LoopReplayTest`, `LoopPropertiesTest`). Messages drained in one cycle share a timestamp, so the journal's resolution is one duty cycle. If a timer handler throws, the tick for that poll is not journaled, so replay of a run that ended in an error is exact up to the last completed input. Wall time drifts from the system clock between anchors; a re-anchor never moves it backwards.

## DR-P2C-03: Segment rolling continues the chain across segments; the single-file classes gain package-private seams only

Date: 2026-10-31

Context. A single RAJ1 file has a fixed capacity, and a day's journal for a busy group does not fit a 2 GiB mapping. The brief asks for rolling with a chain that continues across segments and for the single-file classes to stay unchanged.

Decision. `SegmentedJournal` writes `name.000001.raj` onward and rolls when the next frame does not fit; the first frame of each segment continues the previous segment's sequence and chain hash. `SegmentedJournalReader` walks segments in order. `JournalWriter` and `JournalReader` keep their public API and behaviour; they gain package-private factories that start a segment at a given sequence and previous hash, and `JournalReader` implements a new `FrameSource` interface that the `Replayer` accepts. The file header is unchanged; the segment naming rule is API_SPEC.md Section 1.5.

Consequences. A segment cannot be verified alone, by design: a swapped segment is caught at its first frame. Creating a segment zero-fills it, which took about 340 ms for 512 MiB on the development laptop on 2026-10-31, and the first latency run (`docs/benchmarks/latency-20261031-070816.json`) showed rolls as loop stalls of up to 1.3 s. `SegmentedJournal` therefore offers preallocation: once the current segment is half full, a background thread creates the next one under a `.pending` name and renames it atomically, and the roll only maps the ready file. Rolling still maps a file and allocates a few objects on the loop thread; segments are sized so that rolls are rare (the latency harness uses 512 MiB), and the allocation test journals into one large file.

## DR-P2C-04: Agents and day hooks are not journaled and do not run in replay

Date: 2026-10-31

Context. The duty cycle calls agents after the rings and timers, and the runner calls start-of-day and end-of-day hooks. Live, an agent runs once per cycle, and cycle boundaries depend on load, so agent work cannot be reproduced from the journal.

Decision. Agent work and hooks are outside the replayed function. An agent or hook that needs to change what handlers see publishes a message into the loop's inbound ring, where it is journaled like any other input. Replay does not call agents or hooks.

Consequences. Replay determinism holds for anything that reaches state through a ring. An agent that publishes outbound messages or changes handler state directly breaks replay, and the outbound comparison reports it. API_SPEC.md Section 9.4 states the rule.

## DR-P2C-05: A full outbound ring drops the message and raises a journaled BackpressureEvent after the handler

Date: 2026-10-31

Context. When a gateway drains slowly, the loop must not block and must not allocate. If `publish` returned false, handler control flow would depend on gateway speed, which replay cannot reproduce.

Decision. `publish` returns nothing. A full ring drops the message and counts it. After the handler, timer poll or agent that caused the drops returns, the loop journals one Backpressure control message per affected gateway and calls the `BackpressureListener`. Replay calls the listener at the same point from the journaled message. The outbound journal records every produced message, delivered or not.

Consequences. Replay reproduces the quoting layer's reaction (pulling quotes) exactly. The outbound journal is what the loop decided, not what the venue received; the Backpressure frames say which messages did not leave. A listener that publishes into a still-full ring causes a further event at the next flush point.

## DR-P2C-06: Hot-path rules are checked with ArchUnit over bytecode, with string concatenation compiled inline

Date: 2026-10-31

Context. The hot-path rules (no `synchronized`, no locks, no boxed collections, no string concatenation in message paths) must be checked, not asserted. Since JDK 9, `a + b` compiles to an `invokedynamic` that ArchUnit does not report as a method call, and ArchUnit sees `synchronized` methods but not `synchronized` blocks.

Decision. Packages and classes carry a `@HotPath` annotation (`io.ratesalgo.core` is marked in `package-info.java`). `HotPathRulesTest` imports the compiled classes and checks: no synchronized methods; no dependency on `java.util.concurrent.locks`; no dependency on any `java.util.Collection` or `java.util.Map` type; no calls to a boxed type's `valueOf` (autoboxing); and no `StringBuilder`, `StringConcatFactory`, `String.format` or `String.concat` in methods named `onMessage`, `doWork`, `publish` and the other message-path methods listed in the test. `rates-core` compiles with `-XDstringConcat=inline` so concatenation is visible as `StringBuilder` calls. A source scan rejects `synchronized` blocks. Every rule is also run against a test fixture package that breaks it, and must fail there. `archunit-junit5` is the dependency, as the brief asks; the rules run inside ordinary JUnit tests rather than through ArchUnit's own engine.

Consequences. Error messages on hot paths are built in a separate class (`ContractBreach`), off the message path. The rules apply to every class in a marked package, including configuration code, so builders use arrays instead of lists.

## DR-P2C-07: Loop control messages live in their own schema, not in SBE schema v1

Date: 2026-10-31

Context. The loop needs three messages that schema v1 does not have: an error record, a backpressure record and a gateway connect or disconnect event. Schema v1 is shared with every other session and is versioned by rule (API_SPEC.md Section 2).

Decision. A core control schema with schema id 100, version 0, holds LoopError (65001), Backpressure (65002) and GatewayStatus (65003), hand-encoded in `ControlMessages` with the standard SBE header (API_SPEC.md Section 9.6). Template ids start at 65001 so a frame's template id alone never collides with schema 1.

Consequences. Schema v1 stays untouched by P2. A reader that interprets journal frames must check the schema id in the payload header, which Section 4 already requires. If the control messages grow, they move to an XML schema with generated codecs under the same ids.

## DR-P2C-08: The harness reports decode-to-encode (T3 minus T2) and scheduled-to-encode (T3 minus T0)

Date: 2026-10-31

Context. docs/BENCHMARKS.md defines core decode-to-encode as T3 minus T1, with T1 stamped by the gateway after publishing. In-process, the generator cannot stamp T1 into a message after publishing it, and a side channel written after the publish races the loop. The brief asks for decode-to-encode recorded with `recordValueWithExpectedInterval`.

Decision. The harness records two intervals on the loop thread: decode-to-encode, T3 minus T2 (handler entry, before the SBE decode, to the Quote published and journaled), with `recordValueWithExpectedInterval` at the generator's interval; and scheduled-to-encode, T3 minus T0 (the generator's scheduled send time to the same point), which includes generator lateness, ring queueing and inbound journaling and is free of coordinated omission because it starts from the schedule. T1 is not stamped. Both are reported with the machine, JVM flags and warm-up policy.

Consequences. Decode-to-encode is the handler's own cost; scheduled-to-encode is the figure to compare with a desk's latency budget for the core. The difference between them is queueing plus inbound journaling. Tick-to-wire stays a hook (`TickToWireProbe`) for the FIX codec.

## DR-P2C-09: The allocation test warms up until a round allocates nothing

Date: 2026-10-31

Context. On 2026-10-31 the allocation test measured 296 bytes and, in another run, 72 bytes on the loop thread in the first million messages after a 300,000-message warm-up, and exactly 0 in each of three later windows of one million. The pattern points to late JIT activity at the start of measurement rather than allocation in the steady state; JFR could not be dumped from inside the Surefire fork to confirm it.

Decision. `LoopAllocationTest` warms up with one million messages, then runs rounds of 100,000 until a round allocates nothing (at most 20), then asserts exactly zero bytes over the next one million.

Consequences. The assertion is unchanged: zero bytes, not a tolerance. A real steady-state allocation still fails the test, because every settle round would allocate and the measured million would too. If the settle rounds start to need more than a few rounds, that is investigated with JFR outside Surefire.

## DR-P2C-10: Cookbook numbers for the event-core recipes follow the cookbook's plan

Date: 2026-10-31

Context. The brief for this work names the event-core recipes 8 and 9. COOKBOOK.md's plan assigns 8 and 9 to P1 (Treasury futures CTD and QuantLib comparison) and assigns the event-loop determinism recipe to 12 and the latency histogram recipe to 19.

Decision. The repository wins: the two recipes are 12 (run the event loop against a journal and check determinism) and 19 (run the latency job and read the histogram). Recipe 19 was planned for P5; the harness exists now, so P2 writes it and P5 extends it with the latency job's rate sweep.

Consequences. No collision with the P1 recipes. The P5 session should extend recipe 19 rather than write a new one.

Amendment (2026-10-31, branch integration). The P5 branch used 18 and 19 for its own recipes, and the combined cookbook is renumbered to run contiguously by phase (DR-P4-05 amendment). The latency recipe is now 13 (`Recipe13LatencyHistogram`, `expected/recipe13.txt`), next to the event-loop recipe 12; its content is unchanged. The rate sweep P5 was to add belongs in recipe 13.
