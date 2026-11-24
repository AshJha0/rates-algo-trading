# Decision records

Each record states the context at the time, the decision, and the consequences we accept. Records are never edited after the fact; a later record supersedes an earlier one and says so.

## DR-001: Repository name and licence

Date: 2026-10-31

Context. The project needs a name that says what it is and a licence that lets anyone read, run and reuse it from the first commit, including people assessing the work.

Decision. The repository is `AshJha0/rates-algo-trading`, licensed under Apache License 2.0 with copyright 2026 Ashish Jha.

Amended 2026-10-31 (owner). The repository was created public and switched to private the same day; it stays private until the owner is satisfied with the finished work, then becomes public. Everything is still written as if public: no raw downloaded data, credentials or third-party licensed content is committed (see DR-006), and the word policy (DR-007) applies throughout.

*Annotation 2026-11-23: the amendment above, that the repository stays private, is superseded by DR-REV-01; the repository is public. The text is kept as history.*

Consequences. GitHub Pages does not publish from a private repository on a free plan, so the Docs workflow builds the site with `--strict` on every push but the deploy step stays skipped until the repository is public and the Pages source is set to GitHub Actions. Apache 2.0's patent grant and notice requirements apply to contributors.

*Annotation 2026-11-23: the Pages consequence above applied while the repository was private. Superseded by DR-REV-01: the repository is public and `docs.yml` deploys the site from main.*

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

Diagram: [multi-curve bootstrap](docs/site/pages/diagrams.md#multi-curve-bootstrap).

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

Decision. The golden file is produced by `research/ratesresearch/goldens/generator.py` with QuantLib 1.44 (pinned in the `golden` extra). The generator bootstraps the curves itself, a sequential Brent search in which each instrument is a QuantLib object built with explicit schedules and calendars, so QuantLib does all the pricing and nothing depends on helper defaults. The first file was generated on Windows 11 on 2026-10-31. `research/tests/test_goldens.py` regenerates it and compares every number to 1e-12 relative with an absolute floor of 1e-6 (amended 2026-10-31 at integration: the first Linux run differed from Windows on par-swap PVs at the 1e-9 level, rounding noise on sums of cancelling 1e6 terms, so the floor is one millionth of a currency unit, far below a cent); CI installs QuantLib and runs it on Linux and Windows, so a platform difference fails a build rather than passing silently.

Consequences. This is an exception to the Linux-only rule for one file, made visible by the regeneration test. If the Linux job ever disagrees beyond that tolerance, the file is regenerated on Linux and this record is superseded.

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

## DR-P2V-01: Engine-side FIX codec of our own; QuickFIX/J only in the simulators

Date: 2026-10-31

Context. The engine must not allocate per message (CONTRIBUTING.md coding rules). QuickFIX/J builds a `Message` object graph with `String` and boxed field values for every message, so it cannot meet that rule on the engine side. It is, however, a mature FIX engine with dictionary validation, which is exactly what a venue simulator should have, because certification against a strict counterparty is the point.

Decision. The engine side of every FIX connection is `rates-fix`: a flyweight parser and writer over Agrona buffers for the tags in API_SPEC.md Section 10.2, and a session layer. The simulators are QuickFIX/J 3.0.2 acceptors with FIX 4.4 dictionary validation on.

Consequences. Our session layer is tested against a different implementation in every certification script, which caught one fault (DR-P2V-04). We own a FIX session implementation and its edge cases; the rules are pinned in API_SPEC.md Section 10.1 and tested in `FixSessionTest`. Tags outside our set are carried by the parser but not interpreted.

## DR-P2V-02: New SBE messages without a schema version bump

Date: 2026-10-31

Context. The venue layer needs a market data reject message, outbound cancel, replace and quote-cancel messages, a trade bust, and a done-away RFQ status. API_SPEC.md Section 2 says adding a field to an existing message increments the schema version. The brief for this work said to bump the version to 1 for new fields. Recipe 3 prints the schema version, and other sessions edit the same schema in parallel.

Decision. Add messages 40 to 44, the enum `MdRejectReason` and `QuoteStatus.DoneAway` as additions only, and keep the schema version at 0. No existing field changes, so a v0 decoder decodes every message it knew exactly as before.

Consequences. An old decoder reading `QuoteStatus` 6 gets the null value, which only matters for a journal written after this change and read by older code; no journal has been released. If a later change adds fields to an existing message, that change bumps the version as Section 2 requires. Recorded as a deviation from the brief, where the repository's own rule takes precedence.

## DR-P2V-03: The engine answers resend requests with a gap fill, never by resending

Date: 2026-10-31

Context. FIX allows a session to resend application messages on request. A resent quote or order would arrive late, stamped as a possible duplicate, and could trade at a price the engine no longer wants.

Decision. `FixSession` answers every ResendRequest with one SequenceReset-GapFill to its next outbound number. Inbound, it requests resends and accepts resent messages from the venue.

Consequences. After a gap the venue never sees our stale messages; the engine re-establishes intent explicitly (quote again, or query with QuoteStatusRequest or OrderMassStatusRequest). Venues that require application resends of orders are out of scope until one needs it.

## DR-P2V-04: Simulator-defined tags and the FIX 4.4 QuoteCancel

Date: 2026-10-31

Context. FIX 4.4 has no standard tags for dealer count, cover price, the traded-away or done-away distinction, the CLOB workup state, SEF transaction class and block flag, or a venue reject code, and real venues define their own. FIX 4.4 also has no QuoteCancelType for cancelling one quote by QuoteID (value 5 arrived in FIX 5.0); QuickFIX/J's dictionary rejected our first QuoteCancel with 298=5.

Decision. Use user-defined tags 9501 to 9510 (API_SPEC.md Section 10.2). Cancel a single quote with QuoteCancelType 1, the QuoteID and one NoQuoteEntries instrument.

Consequences. A real venue's certification document replaces these tags with its own; the gateway's translation tables are the only place that changes.

## DR-P2V-05: Simulator parameters are declared assumptions

Date: 2026-10-31

Context. Spreads, response times, client tolerance, informed share and arrival bumps of competing dealers cannot be fitted from public data (DR-008), and the free public tapes (DTCC SDR, TRACE aggregates) have not been downloaded yet.

Decision. Every simulator parameter lives in `rates-venues/src/main/resources/io/ratesalgo/venues/presets.json` as a named preset, documented in API_SPEC.md Section 11.3, and is described as an assumption wherever it is used. Arrival times and size distributions are replaced by fits from the SDR and TRACE manifests in P5, through `data/presets.yaml`.

Consequences. Results from the simulators speak about the engine's behaviour under stated conditions, not about any real venue's market. Outcome shares in the synthetic day (536 of 568 RFQs done) reflect the declared client tolerance and the auto-quoter, nothing else.

## DR-P2V-06: Price-level book feed keyed by price, recovery by snapshot

Date: 2026-10-31

Context. The schema's `BookUpdate` carries one price level with an action and a level number. A level-number feed requires the consumer to shift levels on insert and delete, which is a common source of book corruption after a missed frame.

Decision. Levels are keyed by price: Add, Change and Delete name a price; `level` is the rank and is informational. One sequence number per venue across instruments. A consumer that sees a gap recovers by requesting a full snapshot, never by retransmission of incrementals.

Consequences. The consumer is simple and its state after recovery equals the venue's book (`ChaosTest.feedGapsDuplicatesAndReorderingRecoverToTheVenueBook`). Only the top five levels are published, so a consumer cannot see deeper liquidity.

## DR-P2V-07: SpotBugs exposure warnings excluded in the venue packages

Date: 2026-10-31

Context. SpotBugs reports EI_EXPOSE_REP and EI_EXPOSE_REP2 whenever a constructor stores, or a getter returns, a mutable object. In these packages that is how components are wired: a gateway holds its session, a simulator its clock, a master its maps.

Decision. `config/spotbugs-exclude.xml` excludes only those two patterns for `io.ratesalgo.refdata`, `io.ratesalgo.fix` and `io.ratesalgo.venues`, with the reason in a comment. Internal arrays and buffers stay private or are copied.

Consequences. A genuinely leaked internal array would not be reported in these packages; code review covers it. Every other SpotBugs pattern stays enabled at effort Max, threshold Low.

## DR-P2V-08: One lock per simulator, quiescence in tests

Date: 2026-10-31

Context. QuickFIX/J delivers messages on its own thread while tests move time on theirs. Without coordination the order of events, and so the random draws, would depend on thread timing.

Decision. Every simulator entry point (message, logon, logout, time) runs under the simulator's monitor; the acceptor is the single-threaded `SocketAcceptor`. Tests advance a `ManualClock` under the same lock and call `EngineSession.quiesce()` after every action.

Consequences. Same seed, same messages and same clock give the same run (`D2cModelTest.sameSeedSameDay`). Simulators are test infrastructure, so the lock is not subject to the hot-path rules.

## DR-P2V-09: Simplified workup rules

Date: 2026-10-31

Context. BrokerTec's workup has more detail than the engine needs at this stage (separate phases, size priority for the trade's participants, behaviour at other prices).

Decision. The simulator implements: a trade at P opens a workup at P; an exclusive window for the opening trade's participants; during workup marketable orders trade and rest at P, orders that would trade at another price wait; each trade restarts the timer; held orders run in arrival order when their window ends (API_SPEC.md Section 11.2).

Consequences. Fill sizes in workup are realistic in shape but not in allocation detail. A quoting or routing result that depends on workup allocation needs a richer model first.

## DR-P2V-10: FIX quantities in currency units, SBE quantities in minor units

Date: 2026-10-31

Context. Venues quote face and notional in currency units (OrderQty 10000000 for 10 million). API_SPEC.md Section 0 pins SBE quantities to minor units.

Decision. The gateway scales FIX quantities by exactly 10^2 in both directions with the same integer decimal routine it uses for prices; the feed publisher does the same for book quantities.

Consequences. A currency with a different minor unit (none in scope: USD, EUR, GBP) needs a per-instrument scale from the securities master.

## DR-P2V-11: Numeric FIX identifiers

Date: 2026-10-31

Context. The SBE messages carry rfqId, quoteId, clOrdId, venueOrderId and execId as int64. Converting free text to int64 by hashing would lose the original and could collide.

Decision. The gateway requires QuoteReqID, QuoteID, ClOrdID, OrigClOrdID, OrderID, ExecID and ExecRefID to be numeric text and parses them exactly; the simulators generate numeric ids.

Consequences. A venue with alphanumeric ids needs an id map in the gateway; that is local to `VenueGateway`.

## DR-P2V-12: Cookbook recipe 11 follows the repository plan

Date: 2026-10-31

Context. The brief asked for recipe 11 to show adding a venue. COOKBOOK.md already plans recipe 11 as "How do I subscribe to the SBE book feed and rebuild a book?".

Decision. Recipe 11 is the book feed recipe, as planned in the repository. Adding a venue is documented as a procedure in docs/ARCHITECTURE.md, venue layer.

Consequences. The planned list stays as published; anyone looking for how to add a venue finds it next to the module map.

## DR-P3-01: A P3-owned interface module, rates-api, with its own PV01 value type

Date: 2026-10-31

Context. P3 was built in parallel with pricing (P1), the event core and venues (P2) and the router (P4), none of which existed on this branch. P3 needs fair values, sensitivities, a composite book, a securities master, an outbound message path and a router entry point.

Decision. A module `rates-api` (package `io.ratesalgo.api`) declares the seams P3 depends on: `InstrumentDirectory`, `FairValueSource`, `BookSource`, `SensitivityProvider`, `HedgeOrderSink` and `OutboundSink`, each allocation-free, with in-memory stubs. Sensitivities are returned as a P3 value type, `Dv01Vector`, on eight fixed pillars (1Y, 2Y, 3Y, 5Y, 7Y, 10Y, 20Y, 30Y). The sign convention is PV01 = PV(rates + 1bp) minus PV(base), so a long bond is negative and a payer swap positive.

Consequences. The coordinator writes adapters from P1's `io.ratesalgo.analytics.risk.Dv01Ladder` (re-bucketing to the eight pillars, and flipping sign if P1 reports a long bond's DV01 as positive), from P2's market state and from P4's router entry point. P3 code depends only on `rates-api`, so the adapters are the only place the phases meet.

## DR-P3-02: Win probability is a logistic with a declared band; the default preset ships with the module

Date: 2026-10-31

Context. DR-008 makes win probability a declared assumption. The research session owns `data/presets.yaml` and had not written a quoting preset when P3 was built.

Decision. P(win) is logistic in spread from fair, log size relative to a typical clip, tenor, a liquid-session indicator and dealer count (API_SPEC.md Section 12.2). Coefficients have a central value and a band. The engine reads the preset named `quoting_hit_rate_default` from `data/presets.yaml` if present, otherwise the copy shipped as `rates-quoting/src/main/resources/quoting-hit-rate-default.yaml`, in the same schema (Section 12.3). Win and loss feedback moves the intercept and spread coefficient by stochastic gradient steps and clamps them to the band.

Consequences. Learning cannot leave the declared assumption. When the research session adds the preset to `data/presets.yaml`, it takes precedence without a code change. The back-tester (P5) sweeps the band.

Diagram: [research to production parameters](docs/site/pages/diagrams.md#research-to-production-parameters).

## DR-P3-03: A fair-value signal needs an out-of-sample validation record

Date: 2026-10-31

Context. A predictive adjustment to fair value is the easiest way to lose money by overfitting.

Decision. `FairValueSignal` defaults to zero. `FairValueEngine.install` accepts a non-zero signal only with a `ValidationRecord` whose held-out window starts after the fitting window ends and ends before installation, with at least 250 held-out observations, an absolute held-out t-statistic of at least 2, and an approver who is not the author.

Consequences. A signal is evidence-gated and reviewed by a second person. The thresholds are a starting rule and can only be tightened by a later record.

## DR-P3-04: Schema v1 gains QuoteCancel, OrderCancel, CancelReason and two limit kinds

Date: 2026-10-31

Context. Lifecycle controls must withdraw an RFQ quote before its time to live and cancel resting CLOB orders, and v1 had no message for either. Message-rate and fat-finger checks had no `LimitKind`.

Decision. In `rates-schema-v1.xml`, within the P3 template range 60 to 79: `QuoteCancel` (60) and `OrderCancel` (61), a `CancelReason` enum, and `LimitKind` values `MessageRate` (10) and `FatFinger` (11). v1 is edited in place because no journal written with it exists in a release (API_SPEC.md Section 2 versioning rule).

Consequences. The coordinator merges these with other sessions' schema additions; if P2 also adds an order-cancel message, a later record retires one of the two. Gateways must translate both new messages.

Amendment (2026-10-31, branch integration). P2 venues defined `OrderCancel` (41) and `QuoteCancel` (43) for the gateway requests, so the two P3 messages are renamed `QuoteWithdraw` (60) and `OrderWithdraw` (61) with their fields, ids and `CancelReason` unchanged (DR-INT-01).

## DR-P3-05: Pre-trade checks use the worst case over every reservation

Date: 2026-10-31

Context. A desk with many live firm quotes can be filled on all of them at once; checking each quote against current positions alone lets the sum breach.

Decision. Live quotes, resting orders and hedge parents reserve their quantity. A check passes only if every combination of reservations filling or not stays inside the limits: exact for positions, each pillar and net PV01; for gross PV01 the bound is the sum over pillars of the larger absolute extreme. A withdrawn quote keeps its reservation until the venue answers or the time to live passes, because a fill can race the withdrawal. Orders whose parallel PV01 offsets the currency's net without overshooting skip the PV01 and daily-loss checks, so a book in breach can always be hedged; size, collar, position and rate checks still apply.

Consequences. The engine quotes less than a check against positions alone would allow. A quote that would reduce a breached book is still refused; only orders get the risk-reducing exemption. The million-RFQ and chaos tests audit every message at the sink against a brute-force recomputation of this envelope.

## DR-P3-06: The hedge is a box-constrained convex QP solved by accelerated projected gradient

Date: 2026-10-31

Context. The hedge should trade residual key-rate variance against transaction cost with linear (half spread, basis) and quadratic (impact) terms.

Decision. Objective 0.5 kappa (r + Hx)' Sigma (r + Hx) + sum a_j |x_j| + b_j x_j^2 with |x_j| bounded, rewritten with x = p - m, p and m non-negative, as a convex QP with box constraints, solved by FISTA with gradient restart and step 1/L from the Gershgorin bound (API_SPEC.md Section 14). Tests compare it with a brute-force enumeration of active sets.

Consequences. The solver is a few dozen lines with no dependency. Its cost grows with the square of the number of hedge instruments, which is small. Lots are continuous in the plan and rounded when sent.

## DR-P3-07: Kill switch with a four-eyes reset; a kill during a pending reset is a full kill

Date: 2026-10-31

Context. A kill switch must be one call, idempotent, observable, and hard to undo by mistake.

Decision. States ACTIVE, KILLED and RESET_PENDING (docs/diagrams/kill_switch_state.mmd). Triggering from ACTIVE calls every listener (quotes pulled, orders cancelled, hedge parents cancelled) and sends one KillSwitch message. Resuming needs a request by one person and an approval by another with healthy inputs. A trigger while a reset is pending returns to KILLED as a full kill with its own message; the chaos test found that the first version was silent here.

Consequences. Every kill is visible to the control plane. Resuming takes two people.

## DR-P3-08: How the allocation-free RFQ path is asserted

Date: 2026-10-31

Context. The per-RFQ path must not allocate after warm-up. Measured with the per-thread allocation counter, compiled runs occasionally show a few hundred bytes in one window of 20,000 RFQs.

Decision. `QuotingAllocationTest` warms up with 100,000 RFQs and then measures five windows of 20,000. At least one window must be exactly zero and none may reach 4 KB. One 16-byte object per RFQ would add 320,000 bytes per window. The residual bytes were traced on 2026-10-31: the same loop run with `-Xint` and a JFR recording of every allocation (thread-local allocation buffers off) shows no allocation from the RFQ path, so they come from JIT deoptimisation and recompilation.

Consequences. The assertion catches any per-RFQ allocation without failing on JIT activity. Post-trade alerts written to the recording test sink allocate in the stub, not in the engine, so the test uses the stub in non-recording mode.

## DR-P3-09: SpotBugs EI_EXPOSE_REP exclusion for the hedge and quoting packages

Date: 2026-10-31

Context. SpotBugs at threshold Low reports every constructor that stores a collaborator such as the risk engine as exposing internal representation.

Decision. `config/spotbugs-exclude.xml` excludes EI_EXPOSE_REP and EI_EXPOSE_REP2 for `io.ratesalgo.hedge` and `io.ratesalgo.quoting`, with the reason in the file. Arrays crossing the boundary are still copied.

Consequences. Wiring collaborators on the single writer is not flagged. Other bug patterns still fail the build.

## DR-P3-10: Subject quotes, reference stamps and quote rounding

Date: 2026-10-31

Context. Quotes must be firm for their time or marked subject, carry a reference timestamp, and never be better for the client than computed.

Decision. A subject quote is a Quote with `validUntilNanos` zero; firm quotes carry their expiry and are never last-looked. `referenceNanos` is the newest input the price depends on, as API_SPEC.md Section 2 says; staleness is judged on the oldest input, which the engine holds. Bids round down and asks round up to the Decimal9 grid.

Consequences. The venue gateway (P2) maps validUntil zero to its subject flag. Rounding costs at most one unit of the ninth decimal.

## DR-P3-11: Cookbook numbers follow the repository plan, not the brief

Date: 2026-10-31

Context. The P3 brief asked for recipes 12 to 15; COOKBOOK.md already assigns 12 and 13 to P2 and 14 to 16 to P3.

Decision. P3 writes recipes 14 (quote an RFQ and see the skew), 15 (read the ladder and compute a cost-aware hedge) and 16 (set a limit, see a breach, exercise the kill switch). The four topics of the brief are covered by these three.

Consequences. P2's recipes 12 and 13 keep their numbers. The repository plan wins over the brief, as the brief itself directs.

## DR-P3-12: Limits and P&L are per currency, without FX conversion

Date: 2026-10-31

Context. PV01 and P&L in different currencies cannot be added without FX rates, which P3 does not have.

Decision. PV01 limits, daily loss and the kill-on-loss rule apply to each currency separately, in that currency's major units. A fill's first mark is the pricing source's fair value, so the trade's edge shows in P&L at once.

Consequences. A desk-wide limit in one currency needs FX from P1 or P6 and a later record.

## DR-INT-01: P3 cancel messages renamed QuoteWithdraw and OrderWithdraw at integration

Date: 2026-10-31

Context. The P2 venue branch and the P3 branch each added an SBE message named `QuoteCancel` and one named `OrderCancel` to `rates-schema-v1.xml`: P2 with ids 43 and 41 (the outbound gateway requests, no reason field), P3 with ids 60 and 61 (the quoting engine's withdrawals, with a `CancelReason`). SBE generates one codec class per message name, so the union of the two schemas does not build.

Decision. Keep every message from both branches. The P3 messages become `QuoteWithdraw` (60) and `OrderWithdraw` (61); ids, fields, field order and `CancelReason` are unchanged, and the rename touches only `rates-quoting`, `rates-api` and the P3 text in API_SPEC.md, ARCHITECTURE.md and CHANGELOG.md. The schema version stays 0 because no shipped message changed.

Consequences. Two message pairs now describe a cancel: the engine's withdrawal with a reason and the gateway's venue request. Binding the quoting engine's `OutboundSink` to the P2 gateway (a later step) translates one into the other, or a later record retires one pair, as DR-P3-04 anticipated.

## DR-INT-02: Windowed loop allocation check

Date: 2026-10-31

Context. On the ubuntu-latest CI job (JDK 25, Temurin) `LoopAllocationTest` failed with 296 bytes allocated on the loop thread over the measured million messages; the same test passed on windows-latest and on the Windows laptop. The test read the counter once after warm-up and once after the measured phase and required a zero delta, with settle rounds of 100,000 messages before the measurement. A single delta cannot tell a one-off cost from a per-message leak. We could not reproduce the Linux run locally, so its exact source is not confirmed. Two findings came out of the investigation on the laptop. First, with the test process limited to two CPUs (the size of a hosted runner for a private repository), the drainer thread falls behind and 40 to 50 per cent of outbound Quotes are dropped, so the loop runs the backpressure path for much of the run. Second, when backpressure first happens after warm-up, the loop thread allocated about 113 KB in that window when the test ran alone: `EventLoop` refers to `ControlMessages` only through compile-time constants until the first dropped message, so the first backpressure event loaded and initialised the class on the loop thread. In the full module run another test had already loaded it, which is why the gate passed. With that fixed, a residual one-off of 0 to 1,112 bytes remained in the first window (a recompile or call-site resolution on the newly taken path), the same kind of cost as the 296 bytes on Linux.

Decision. `EventLoop`'s constructor now encodes a Backpressure message into its scratch buffer and sets its `BackpressureEvent` once, so the backpressure path's classes are loaded on the building thread. The test measures three consecutive windows of one million messages after a one-million-message warm-up: the last window must allocate exactly zero, the second and third at most 1,024 bytes each, and the first is reported but not bounded. The counter is read from the test thread with the loop thread's id, and the bean is called once before warm-up. The failure message prints every window's bytes, dropped messages and backpressure events. The inbound journal is sized at 768 MiB so that it holds a Backpressure frame for every message if the drainer drops all of them.

Consequences. The gate still fails on any per-message allocation, since one byte per message is a megabyte per window, and on any allocation in the last window. A one-off cost on a path first taken just after warm-up no longer fails the build, but it shows in the message. The test now always sends 4 million messages, where before it sent between 2.1 and 4 million.

## DR-INT-03: The trading process binds its loop journals through LoopJournals

Date: 2026-10-31

Context. Round 2 ran two branches at once. `claude/r2-journal-latency-ee2034` took segment creation, mapping and page faults off the loop thread (DR-R2J-01, DR-R2J-02), added a ring mode (DR-R2J-03) and made the mode a loop configuration choice through `LoopJournals` and `EventLoop.Builder.journals(LoopJournals)`. `claude/r2-wiring-c31ae4` built `TradingProcess` in the new `rates-app` module against the P2 API: it created its inbound, outbound and route journals with `SegmentedJournal.create(dir, name, size, clock)`, without preallocation, so each roll mapped a new file on the loop thread, which is the cost DR-R2J-02 removed. Neither branch changed the other's files; the overlap is the call site.

Decision. The journal branch's design stands unchanged. At integration `TradingProcess` creates the loop's inbound and outbound journals with `LoopJournals.create(dir, "in", "out", SEGMENT_BYTES, clock)` in the default mode (`DIRECT`, DR-R2J-04) and passes them with `.journals(...)`; the route journal, which the desk also appends to on the loop thread, is created with preallocation (`SegmentedJournal.create(..., true)`). File names, segment size and the frame format are unchanged, so `JournalFeed`, `JournalExport` and `--replay` read the same files. `WiredLoopAllocationTest` keeps its single-file `JournalWriter` journals, which do not roll.

Consequences. A roll in the trading process no longer maps, creates or allocates on the loop thread. Each journal now keeps one prepared 64 MiB segment and one background thread from the start. Switching the process to the ring mode is a one-argument change to the `LoopJournals.create` call.

## DR-INT-04: Ledger rows from parallel branches are appended in merge order with new seq and prev_sha256

Date: 2026-10-31

Context. `data/ledger/ledger.jsonl` is an append-only hash chain: each line carries `seq` (1-based) and `prev_sha256`, the SHA-256 of the previous line's text, and both readers (`Ledger.entries()` in `rates-backtest`, `prereg.read_ledger` in `research/ratesresearch`) refuse a line whose `seq` is not its position or whose `prev_sha256` does not match. In round 2, several branches ran experiments in parallel and each appended its rows after the same base, P5-001 at seq 1. `claude/r2-edge-research-6aba2e` appended EDGE-E1 to EDGE-E6 as seq 2 to 7, `claude/r2-pnl-audit-e32be7` appended AUDIT-01 to AUDIT-04 as seq 2 to 5, and `claude/r2-business-6cda18` appended R2B-001 to R2B-007 as seq 2 to 8, so the chains cannot all stand.

Decision. Rows already on `round2-integration` are kept unchanged, and each later branch's rows are appended after them in their original order. The edge rows merged first, onto a ledger that held only seq 1, so they kept seq 2 to 7. The audit rows were re-appended with the project's own `Ledger.append` (`rates-backtest`), which assigns `seq` and `prev_sha256`. Everything else in each row stays as recorded: results, `config_sha256`, `journal_sha256`, `prereg_sha256`, `code_commit`, `code_dirty`, `look`, `recorded_utc` and the journal list. Each written line was checked to be byte-identical to the original with only those two values replaced. The business rows were then re-appended the same way after the audit rows, as seq 12 to 18. The chain verifies end to end in both `Ledger.entries()` and `prereg.read_ledger` (18 rows). `research/audit/summarize.py` re-reads the rows, recomputes every interval and Holm p from the stored daily P&L and asserts them against the rows; it reproduces `research/audit/results/summary.md` except the four headings, which print the new seq. References to the old numbers in `docs/research/PNL_AUDIT.md`, `research/audit/README.md` and `summary.md` were renumbered, with the experiment id added beside each seq. The copies in `research/audit/results/AUDIT-0n.json` carry no seq or chain field and are unchanged. For the business rows, the seq column and the record range in `docs/research/DESK_FEATURES.md`, the CHANGELOG entry, DR-R2B-07's context and the `DeskFactory` comment were renumbered; the R2B pre-registrations cite no seq.

| Experiment | Original seq (own branch) | Seq on round2-integration | prev_sha256 before | prev_sha256 after |
|---|---:|---:|---|---|
| AUDIT-01 | 2 | 8 | `850a5e40a004...` | `2f8551ce748d...` |
| AUDIT-02 | 3 | 9 | `f16a70a4cc23...` | `300529794edf...` |
| AUDIT-03 | 4 | 10 | `05fbad304512...` | `be7e052c8319...` |
| AUDIT-04 | 5 | 11 | `1d9907487c7a...` | `3ac99a2f43d0...` |
| R2B-001 | 2 | 12 | `850a5e40a004...` | `79abbbcb0fb2...` |
| R2B-002 | 3 | 13 | `f37fd5be9247...` | `09d5e8022aa1...` |
| R2B-003 | 4 | 14 | `f5101a7c10e5...` | `8cf5f04d08ce...` |
| R2B-004 | 5 | 15 | `481745c8c1ee...` | `e3ebb73c0d0e...` |
| R2B-005 | 6 | 16 | `a0ca41a043fd...` | `c2af0c8facb2...` |
| R2B-006 | 7 | 17 | `968558726727...` | `9d5d645bbf1a...` |
| R2B-007 | 8 | 18 | `62a00dc6e056...` | `ccbaf1e16ff3...` |
| AUDIT-11 to AUDIT-14 | 19 to 22 | 19 to 22 (no collision, unchanged) | - | - |
| V1H-001, V1H-002 | 23, 24 | 23, 24 (v1-integration; appended after main's 22 rows, no collision, unchanged) | - | - |

Consequences. A ledger row's seq says where the row sits in the combined chain, not when or on which branch it was run. Its `recorded_utc` and `code_commit` say that, and both are unchanged. The audit branch's own commits still show the old numbers. Later branches that append experiments (the fix packages) follow the same rule, and each move is listed here or in a later DR-INT record.

## DR-INT-05: The wired desk declares a winner's curse that fits its half-spread cap

Date: 2026-10-31

Context. `claude/r2-review-6e9f43` added a pass to `QuotingEngine.onQuoteRequest`: when the expected cost of filling (client toxicity, plus the winner's curse `winnersCurseBp * sqrt(2 ln dealers) * volRatio`, plus the hedge cost) is at or above `maxHalfSpreadBp`, no spread up to the cap has positive expected edge, so the engine returns `PassReason.NEGATIVE_EDGE` instead of quoting at the cap. `claude/r2-wiring-c31ae4` holds the wired desk's half spread between 0.02 and 0.08 bp so it can compete with the simulated dealers (DR-R2W, `DeskSettings.quoting()`), but it kept the default `winnersCurseBp` of 0.10 bp. With three dealers that term alone is 0.10 * sqrt(2 ln 3) = 0.148 bp, above the 0.08 bp cap. After the merge every RFQ was passed, and `SmokeTest`, `BacktestBindingTest`, `TradingProcessMainTest`, `VenueBindingTest` and recipe 22 failed because the desk never quoted.

Decision. Review's guard stays as written. `DeskSettings.quoting()` declares `winnersCurseBp(0.02)`, which puts the term at about 0.030 bp, inside the desk's 0.02 to 0.08 bp band. Toxicity starts at zero and the guard still passes a client whose learned adverse cost reaches the cap. Like the band itself, 0.02 bp is a declared value for the synthetic smoke day, not a fit. Recipe 22's recorded output (`rates-cookbook/src/test/resources/expected/recipe22.txt` and COOKBOOK.md) and the smoke-day counts in README.md and STATUS.md were re-recorded: 39 RFQs, 34 quotes, 22 RFQ fills, 302 hedge parents and 291 hedge fills, against 43, 43, 25, 261 and 266 before. The other review changes on the quoting and risk path (the reservation-aware risk-reducing check, late fills after expiry, one hit-rate label per RFQ, the ticked CLOB prices) move the counts too.

Also at this merge, `data/presets.yaml`: the review branch changed the hit-rate slope rationale in `ratesresearch/assumptions.py` and edited the same text by hand in the presets file, with different YAML line wrapping. The shipped file was replaced by the output of the project's generator (`ratesresearch.hitrate.main`). The parsed YAML is identical; only the wrapping of four lines changed, so the file is again byte-for-byte reproducible.

Also, `VenueBindingTest.aLostSessionIsPulledOnTheLoopCancelledAtTheVenueAndQuotingResumesAfterLogon` failed once in the first full verify after this merge and once in three idle runs. The D2C simulator logs `cancel-on-disconnect` from its own FIX thread when it notices the closed socket, in wall-clock time, and the test read the log straight after a 200 ms simulated step. The test now waits for the entry for up to 10 s of wall-clock time, stepping 10 ms at a time (`awaitLogged`); every other assertion is unchanged. It passed five runs in a row.

Consequences. A desk configuration whose cap is below its own expected cost now quotes nothing rather than quoting at a loss. Any new desk configuration has to keep `winnersCurseBp`, the hedge cost and the cap consistent, and a smoke day that quotes nothing fails `SmokeTest`.

## DR-INT-06: Load-sensitive tests: exact accounting in the latency harness test, a longer wait for the first quote, a JIT-settled warm-up for the wired allocation test

Date: 2026-10-31

Context. `LatencyHarnessTest.shortRunWritesAReportWhoseHistogramsReadBack` failed twice under full-build load in the fix-a1 session and passed when run alone. Among its checks, it required the gateway callback to see exactly `generated - generatorDropped` Quotes. That holds only if the loop publishes every Quote. When the machine is loaded, the gateway thread that drains the outbound ring can fall behind, and the loop then drops Quotes on the full ring and counts them as `outboundDropped` (DR-P2C backpressure). So a correct run could fail the check. The other checks in the test (counts against the measured window, histogram read-back, report rows) do not depend on scheduling.

Decision. The single equality becomes three exact equalities over the counters the harness writes to its JSON report. The loop processed exactly what the generator did not drop (`inbound == generated - generatorDropped`). Each processed request was either published or counted as dropped (`inbound == outbound + outboundDropped`). The gateway handed on exactly what was published (`seen == outbound`). Every other assertion is unchanged, and a failure prints the report.

Also, `TradingProcessMainTest.theTradingRoleQuotesTakesTheKillAndItsJournalsReplay` failed with "no quote within 30 s" under full-build load in the fix-c session and passed alone. The process runs in wall-clock time at time scale 60, so a loaded machine delays its first quote. The test already polled `/control/status` until `quotesSent` was positive; the ceiling is now 180 s, and the failure message prints the last status. The kill, metrics and replay assertions are unchanged.

Also, `WiredLoopAllocationTest` (rates-app) failed once under full-build load in the fix-d session and passed alone. The failing report was not kept. Five runs at integration, each in a fresh JVM with all 12 cores held busy by spinning processes, all passed, so no allocation was reproduced and no code path was found that allocates. The one mechanism that fits a failure that appears only under load is JIT timing. The test warms up for a fixed 600,000 messages; when the compiler threads are starved, C2 compilations and deoptimisations of the desk's paths land in the measured windows, and a deoptimisation that rematerialises scalar-replaced objects allocates on the loop thread. The test now keeps warming up, one window at a time, until a whole window passes with no new JIT compilation time, for at most 4 extra windows, and its report gives the number of extra windows. The measured windows are unchanged: the same four windows of 150,000 messages, no later window above 1,024 bytes, and at least one window with zero bytes. A genuine per-message allocation still fails, because it would show in every window. If the failure recurs with the JIT quiet, the report now says so, and the cause is then a real one-off allocation to fix as in DR-INT-02.

Consequences. The test still fails if a message is lost without being counted on any path, which is what the old check guarded, and it now also checks the loop's own counters against the generator. A run where the loop drops Quotes on backpressure passes, as it should. The harness itself is unchanged.


Amended 2026-11-01, `LoopAllocationTest` and the journals' wait paths. On pull request 4 the DIRECT case failed once on windows-latest: window 2 allocated 1,288 bytes, one roll waited and the test took 183 s on a 2-CPU runner. The bound was not changed. The test now reports roll waits per window. The investigation ran on the development laptop (12 cores, Windows 11, JDK 25.0.5) with 4 cores held busy, each run in a fresh JVM.

- **Before.** 3 of 10 runs failed, each with exactly 176 bytes in the DIRECT window that held the run's first waited roll. All 27 DIRECT windows without a waited roll allocated 0 bytes. RING never failed, because there the journal thread waits, not the loop. A journal-only test, which waits from its first roll, allocated nothing; that misled the first reading of this amendment.
- **Cause.** With `-Xlog:deoptimization=debug` on the failing run (run 10 of a second batch), the loop thread's only trap in the measured part was `SegmentedJournal$Preparer.take()`, an OSR `unstable_if` trap at the exit of the wait loop. The loop's warm-up never waits, so C2 compiles the wait as never taken. The first real wait then deoptimises compiled frames on the loop thread and re-creates objects that escape analysis had removed. The 176 bytes are that one-off; they are not per message. A production loop would take the same deoptimisation, at its first waited roll, while quoting.
- **Fix.** `JournalWarmUp.exerciseWaitPaths` runs both wait paths before any loop starts: the roll that waits for its segment, and the append to a full ring (`RingJournal.append`). It uses scratch journals with 64 KiB segments and a 64 KiB ring, appended in a tight loop until each path has waited 200 times, then deletes them. Branch profiles belong to the method, so the production journals are never written. `TradingProcess` calls it before it builds the loops, as do `LatencyHarness` and the test. The JIT-settled extra warm-up stays.
- **After.** 10 of 10 runs passed on the same setup. Seven of them had a waited roll inside a measured window, and each such window allocated 0 bytes. The deoptimisation logs of all ten show no trap in `Preparer.take` on any thread. `SegmentedJournalTest.aWaitedRollAllocatesNothingOnTheAppendingThreadAfterWarmUp` checks the journal on its own.
- **Window exclusion.** Excluding windows that contain a roll was not needed, so the measured windows, the 1,024-byte bound and the zero-byte last window are unchanged.

Amended 2026-11-01, latency harness accounting. The second of the three equalities above was wrong. `LoopCounters.OUTBOUND` counts every Quote the loop produced, delivered or not, so a run with drops failed on `inbound == outbound + outboundDropped` (windows-latest on pull request 4: inbound 58,025, outbound 58,025, outboundDropped 457). The equalities are now: processed equals generated less generator drops; produced equals processed (`inbound == outbound`, one Quote per request); and the gateway sees the produced less the dropped (`outbound - outboundDropped == seen`). Backpressure events are at most the drops, since one event covers a run of drops on a gateway. A Quote lost without being counted still fails the test.

Amended 2026-11-01 (v1.0.0 release pass). Main CI on ecfc591 failed once in Java 25 on ubuntu-latest: `VenueBindingTest.aLostSessionIsPulledOnTheLoopCancelledAtTheVenueAndQuotingResumesAfterLogon` checked that both FIX sessions were active after a fixed 500 ms of simulated time. Our gateways run on the test thread, but the venues answer from QuickFIX/J threads over sockets in wall-clock time, so a loaded runner can leave the logon behind any fixed simulated step. 75d5ea5 had fixed the same race for the cancel-on-disconnect log entry. Every read in the test that depends on a venue now goes through one helper, `await`, which steps 10 ms of simulated time at a time until the condition holds, for up to 10 s of wall-clock time. That covers the venue's replies, its event log, its CLOB orders, a session logon, health after a logon or snapshot, and the desk's decision on an injected RFQ. The assertions are unchanged; each must still become true, and a condition that never does still fails after 10 s. Reads of state written only on the test thread stay direct: the loop's own counters after a synchronous input, the sessions' counters, and the injected-fault test. Stability: 20 runs of the test class, each in a fresh JVM, all passed (STATUS.md, "P6 remainder and release"). On pull request 4 the same test then failed once on ubuntu-latest: the sessions did not log on again within the whole 10 s wait, so this was not a short timing window. The test had waited only for the D2C venue to see the old session go. A QuickFIX/J acceptor that still holds a session refuses a new Logon for it, and our initiator does not retry. The test now waits until both venues have logged the old session out (`logout RALGO` in each event log) before it reconnects. If the logon still does not come, the failure message gives both sessions' states and the venues' last log lines. A reconnect that retries a refused Logon was then added to `VenueSession` (DR-INT-09).
## DR-FIX-A1-01: The hit-rate intercept and slope are learned by a forgetting Newton step within wide bounds

Date: 2026-10-31

Context. P&L audit item 2 (docs/research/PNL_AUDIT.md; AUDIT-01, seq 8, `slope_known`): the production hit-rate slope was held near its central value, and learning was bounded to [-2.4, -1.0]. When the settling slope was known, the strategy earned +949k a day at the H band point, so a desk that cannot move its slope toward what its own outcomes show leaves money on the table.

Decision. `HitRateModel` learns b0 and b1 from the desk's own wins and losses. Each outcome takes one Newton step on the logistic log-likelihood, using an information matrix that starts at the prior precision `1 / learningRate` and decays with a window of 5,000 RFQs, so the first steps equal the old gradient ascent and later ones shrink as evidence accumulates. Both coefficients are clamped to learning bounds wider than the declared band: slope -8.0 to -0.2 (AUDIT-01 sweeps to -5.0; the slope stays negative) and intercept -3.0 to 5.0. `base()` excludes the intercept, so a base stored when a quote is sent stays valid after the intercept moves. The daily report can read `learnedSlope()`, `slopeStandardError()` and `slopeOutsideDeclaredBand()`.

Consequences. The desk's slope can reach a market where price decides or one where it hardly matters, and the report shows when its data has left the assumption band. One RFQ is still one label (DR-R2R-08). The learning is allocation-free. Wins are scarce at wide spreads, so the slope's standard error is reported beside it.

## DR-FIX-A1-02: The spread optimiser averages over the cover with Gauss-Hermite quadrature, the observed gaps setting a floor

Date: 2026-10-31

Context. Audit item 11 (AUDIT-01, `cover_integrated`): the optimiser evaluated the hit rate at a point estimate of the cover, while the cover is uncertain. Averaging over it earned +69k a day at C (interval -50k to 214k) and +29k at L.

Decision. `SpreadModel` maximises `Pbar(win | s) * (s - c)`, where `Pbar` averages the hit-rate model over a cover distributed normally around the expected cover. It uses 8-point Gauss-Hermite quadrature and a golden-section search on [0, maxHalfSpread], with no derivative and no allocation. The cover's standard deviation comes from the gaps between our winning prices and the disclosed covers, fed through `observeCoverGap`, with a window of 500. Until a gap arrives it is the prior given at construction, 0 by default, which reproduces the point estimate exactly.

Consequences. Gaps are seen only on wins, so the observed spread is that of a truncated sample and understates the true one; it is treated as a floor. Engine output is unchanged until the engine feeds gaps, which is left to the engine package (fix A2).

## DR-FIX-A1-03: Default tier tables price toxic tiers wider instead of blocking them

Date: 2026-10-31

Context. Audit item 5 (AUDIT-02, seq 9): at the engine's spreads, declining informed flow cost more than it saved: `oracle_decline_informed` -486k a day at C and -1.13mm at L; declining tier 3 lost 638k at C.

Decision. `TierTable.standard` builds three firm tiers with nothing blocked and spread multipliers 1.0, 1.15 and 1.35. Unmapped clients get tier 2. `unblocked(version, widening)` converts any blocked tier of an existing table into a quoting tier with its multiplier times `widening` (default 1.5), and `blockedTiers()` lets a check flag a table that still refuses quotes. The multipliers are declared, not fitted; they follow AUDIT-02's `widen_tier3` direction (+93k at L).

Consequences. Blocking stays possible through an explicit `ClientTier`, but no default builds one. Which table a desk runs remains a configuration choice approved through `TierTableControl`.

## DR-FIX-B-01: The CLOB quoter skews for inventory with the RFQ engine's kappa, before the tick rounding

Date: 2026-10-31

Context. P&L audit item 7 (docs/research/PNL_AUDIT.md; AUDIT-04, seq 11): neither the back-tested engine nor the CLOB quoter leaned its prices against inventory. `skew_0p05` earned +97k a day at C (interval -48k to 366k) and +61k at L; `skew_0p20` +151k at L but -143k at C. Item 6: CLOB quotes were off the venue's tick grid, which the review fixed (DR-R2R-12).

Decision. `ClobQuoter.inventorySkew` applies the RFQ engine's own skew, `kappa * u' Sigma p / |U|` clamped to `maxSkewBp`, to both CLOB targets, with the same parameters and covariance, so both channels lean the same way on the same book. The skew moves the targets before the tick rounding, so prices stay on the tick. It is off until called, which keeps today's CLOB output unchanged. A test checks every resulting price against `ClobBook`'s tick rule at 1/32, 1/128 and 1/256 ticks on flat, long and short books.

Consequences. Whether a desk turns the skew on, and at which kappa, is configuration. AUDIT-04 shows that the level matters (gain at L, loss at C for 0.20 bp), so it should be tuned through a pre-registered run rather than set by hand.

## DR-FIX-C-01: A breach may shrink but not grow, and the engine's own daily-loss kill lets hedges flatten

Date: 2026-10-31

Context. P&L audit item 14 (docs/research/PNL_AUDIT.md; code reading of RiskEngine and Hedger): once a PV01 limit was breached, the checks refused flow that would have reduced the breach, and a kill froze the hedger with the loss on the book.

Decision. Each worst-case PV01 figure (pillar, net, gross) may stay where it is when already past its limit, with a relative tolerance for rounding, but may not move further past it or overshoot through zero into a new breach. That applies to quotes, orders and headroom. A one-sided quote whose fill offsets net PV01 skips only the daily-loss check, as a risk-reducing order already did. When the kill comes from the engine's own post-trade daily-loss breach, `flattenOnly()` is true: quotes stay withdrawn and other orders are refused, but risk-reducing orders still pass. The `Hedger` keeps its parents working and keeps sending tranches. Any other kill still cancels everything. `flattenOnly()` is set before the trigger, because the listeners read it during the kill.

*Annotation 2026-11-24 (1.2.0): the relative tolerance for rounding named above is removed. It let a breached 10-year pillar figure of about -82k grow by up to 10 minor units. The figures before and after an order are now built from the same partial sum of the other reservations, so a breached figure may stay exactly where it is or fall, and may not rise by one minor unit; the same holds in the basis and swap-spread checks (commit 700c2e9, `BreachedLimitPropertiesTest`, `RiskControlsTest`). The rest of the decision stands.*

Consequences. The desk can always trade its way back inside a limit and flatten after a daily-loss stop. An operator kill or a kill of any other cause is unchanged. The desk-level oracle in `SinkAuditor` and `ChaosTest` follows the new rule.

## DR-FIX-C-02: Basis and swap-spread exposure have their own limits and attribution

Date: 2026-10-31

Context. Item 16 (AUDIT-03, seq 10, `bucketed`; Desk.java): all risk was hedged in one future, and the basis and swap-spread risk this opens was neither limited nor attributed.

Decision. `RiskLimits` gains `basisPv01` and `swapSpreadPv01`, checked on positions plus the checked quantity at either extreme; other reservations are left out because, depending on product, they move the offset either way. An exposure already past its limit may shrink but not grow. The limit schema has no kind for them, so a refusal returns `REJECT_BASIS` or `REJECT_SWAP_SPREAD` without a breach message, and post-trade breaches are counted. Risk-reducing orders skip the PV01 envelope but not these limits, so a futures hedge of swap risk cannot open unlimited spread risk. `ExposureBasisAttribution` attributes each hedge fill's basis and swap-spread exposure and slippage, one per currency in `Desk`; SpotBugs treats its reference to the shared risk engine as it does the Hedger's.

Consequences. The limits default to off until configured. A breach message kind for them is a schema change left for later.

## DR-FIX-C-03: The router prices adverse selection on filled passive children and the cost of each message, off by default

Date: 2026-10-31

Context. Item 15 (AUDIT-03, `passive_ty`): passive hedging at the touch turned the hedge line from -704k to +130k a day at C, but the router valued a filled passive child as free of adverse selection. Item 18: message and cancel costs were not counted anywhere, while venue order-to-trade policies count cancels.

Decision. `RouterParams` gains `filledAdverseFactor` (the expected adverse move after a passive fill, in sigma over the expected queue wait, capped at the passive horizon), `messageCostPoints` (per new order or cancel, per lot) and per-instrument volatility. All three are off in the existing constructor and presets. `RouterPresets.globalWithControls` sets 0.75 sigma and 0.0001 points per lot per message, about 1 USD on one million of cash face. These are assumptions, not fits, and stay out of `data/presets.yaml` until the audit re-run has measured them. `QuoteStuffingDetector` now counts cancels, replaces and withdrawals, and the end-of-day report writes per-instrument message and cancel counts.

Consequences. Routing is unchanged until a desk selects the controls preset. The order-to-trade figures are visible daily either way.

## DR-FIX-A2-01: Every RFQ's decision and outcome is recorded with the signed cover gap and our rank

Date: 2026-10-31

Context. P&L audit item 3 (docs/research/PNL_AUDIT.md; AUDIT-01, seq 8): won trades sat 0.44 bp behind the cover (DV01-weighted, C), so the desk's volume rested on clients accepting quotes behind the best competitor, and production did not record it.

Decision. The engine reports each decision and each response to an `RfqRecordListener`, with the cover gap signed from the client's side and our rank as far as the cover shows it, and it counts wins from behind the cover. This is the recording side of the item. Changing prices in response is left to a pre-registered run on the recorded data.

Consequences. The cost of winning from behind the cover can be measured on live and back-tested days alike. The listener is allocation-free and off by default (`NONE`).

## DR-FIX-A2-02: Client markouts are measured from fair value at the trade

Date: 2026-10-31

Context. Item 4 (AUDIT-01, AUDIT-02): the toxicity markout was measured from the traded price, so the half spread the desk captured netted out the adverse move. The 5-minute adverse move for tier 3 was 0.31 bp against 0.13 bp charged.

Decision. Markouts are measured from the fair value at the time of the trade.

Consequences. Toxicity estimates rise by about the captured half spread, and the adverse cost the engine charges per client rises with them. With the review's NEGATIVE_EDGE pass (DR-INT-05), a client whose learned cost reaches the cap is no longer quoted.

## DR-FIX-A2-03: A live quote is withdrawn when fair value moves against it

Date: 2026-10-31

Context. Item 8 (AUDIT-01 diagnostics): firm quotes stood through market moves for their whole time to live. Within a 1.5 s window, +31.7k a day moved in clients' favour.

Decision. `poll` withdraws a live quote with `AdverseSignal` when fair value has moved against it by more than `fairMoveWithdrawBp`, default 0.25 bp, and 0 turns it off.

Consequences. A withdrawn firm quote can still be lifted if the venue accepts the lift first; such fills are booked as late fills (DR-R2R-13).

## DR-FIX-A2-04: The release calendar is on by default

Date: 2026-10-31

Context. Item 9 (AUDIT-01, AUDIT-04 `release_guard`): quoting within 2 minutes of the 08:30 and 10:00 New York releases captured 36.7k a day of spread and lost 37.5k to the 5-minute move. Pulling from 60 s before to 30 s after earned +20k at C. Edge study E3 measured the release variance (supported).

Decision. The default `QuotingConfig` carries the 08:30 and 10:00 New York releases on weekdays of 2026 and 2027. From 60 s before to 30 s after, quotes are pulled and RFQs passed with `RELEASE_WINDOW`. For 5 minutes after the window, half spreads are multiplied by 2.355, the E3 holdout ratio. The calendar is a declared schedule of release times, not a list of actual release days.

Consequences. Every desk and test that runs across 08:30 or 10:00 New York sees the pull. The rates-app smoke day starts at 08:25, so its counts changed, and recipe 22 was re-recorded. A real calendar source is a follow-up.

## DR-FIX-A2-05: Package RFQs pass the single-RFQ controls and pay for their net risk

Date: 2026-10-31

Context. Item 12 (code reading of PackageQuoter): package RFQs skipped the kill switch, health, throttle, input-age and risk checks that single RFQs pass, reserved nothing, and were priced on the sum of their legs.

Decision. `PackageQuoter` applies the single-RFQ gates in the same order and reserves every leg for the time to live. It books Dones to risk, including late ones. It charges the PV01-weighted mean of the legs' optimal half spreads on the net ladder's risk-equivalent PV01, plus hedge cost less the netting saving plus the inventory term, floored at the minimum half spread on the largest leg.

Consequences. A DV01-neutral curve or fly is charged for its curve risk only, which recipe 27 now shows. The desk does not yet register the package quoter with the kill switch; until it does, the quoter reads the switch on every quote. Amended for v1.0.0 (2026-10-31): `Desk` now registers the package quoter, so a kill withdraws every live package at once (`AuditFixesTest.packagesPassTheSameControlsAsSingleRfqs`, `PackageQuoter.livePackages`).

## DR-FIX-A2-06: A late single-RFQ Done reaches the recorder and the hedger

Date: 2026-10-31

Context. Item 13 (code reading of QuotingEngine): a Done that raced our expiry could miss risk and the hedger. The review books such fills (DR-R2R-13).

Decision. A late Done is also reported to the recorder, and it reaches the hedger through the risk engine's fill, which a test shows.

Consequences. No trade the venue accepted is left unhedged because of the race.

## DR-INT-07: One winner's-curse setting for the wired desk, and the final smoke-day numbers

Date: 2026-10-31

Context. Fix package A2 (`claude/fix-a2-engine-8168e0`) met the same NEGATIVE_EDGE problem as DR-INT-05 and set `DeskSettings.winnersCurseBp(0.01)` in commit 2031b7a. When it merged round2-integration it took DR-INT-05's 0.02 bp, and it then re-recorded recipes 22 and 27 on that tree (ac1bba3).

Decision. The wired desk keeps `winnersCurseBp(0.02)` (DR-INT-05), so there is one setting. Recipe 22's output on the merged tree with packages A1, B, C and A2 equals A2's recording, and `RecipesTest` and `check_cookbook.py` pass on it. README.md and STATUS.md quote that output: 36 RFQs, 27 quotes, 16 RFQ fills, 210 hedge parents, 213 hedge fills, 229 trades reconciled, and a back-test of 36 RFQs, 14 client fills and 171 hedge fills.

Consequences. The smoke-day numbers move again whenever a quoting default changes. Recipe 22's recorded output is the reference, and README and STATUS follow it.

## DR-FIX-D-01: The back-tester can walk the displayed depth for market hedges; the linear rule stays the default

Date: 2026-10-31

Context. The P&L audit measured hedge cost with a linear rule on one block (item 1: 0.328 bp per DV01 hedged), while the production router sends child orders against displayed depth.

Decision. `HedgeCostModel.withDepthWalk(true)` fills market hedges level by level against the displayed touch quantity. `fromPreset` and the default keep the linear rule, so P5-001 and AUDIT-01 to AUDIT-04 still reproduce from their ledger rows.

Consequences. Runs that want realistic child-order costs opt in. AUDIT-11 measured 0.089 bp per DV01 with the depth walk against 0.174 bp under the linear rule for the production desk.

## DR-FIX-D-02: A best-price client rule beside the hit-rate client, on the same random draws

Date: 2026-10-31

Context. Item 3: volume rested on clients accepting quotes behind the cover, which the soft logistic client allows.

Decision. `RfqOutcomeModel.ClientChoice.BEST_PRICE` makes the client trade with the best price, with a tie on a fair coin. Both rules draw the same numbers in the same order, so every RFQ's cover is the same under either and a paired comparison isolates the client rule.

Consequences. A strategy's dependence on clients who accept worse prices can be measured directly. `HIT_RATE` stays the default.

## DR-FIX-D-03: Bond coupons accrue on the carry line

Date: 2026-10-31

Context. Item 17 (AUDIT-01 funding line): the back-tester charged bond funding without coupon carry, so a net short bond position looked cheaper to hold than it is.

Decision. `CarryModel.bondCoupons` accrues each bond's annual coupon ACT/365 next to funding.

Consequences. Balance sheet, initial margin and the CCP basis are still not priced. Item 17 is fixed for coupons only.

## DR-INT-08: Two allocation tests that failed on CI after the round 2 merge

Date: 2026-10-31

Context. On PR #3 (round2-integration f5dc492), CI failed in two allocation tests that had passed locally.

First, on ubuntu, `DeskQuoterAllocationTest.perRfqAndPerFillPathsAreAllocationFreeAfterWarmUp` (rates-strategy) failed with "expected <20> but was <50>". This is a count, not bytes. The loop runs windows of 200 RFQs until at least 50 windows have run and the last 20 in a row allocated nothing, so on exit the number of trailing quiet windows is at least 20. The assertion `assertEquals(20, quiet)` held only when the path settled after window 30. On the Linux runner it allocated nothing from the first window, so all 50 windows were quiet. That is the strongest possible pass, failed by an off-by-design equality. None of the merged fix packages changed the count's meaning; they only shifted when the path settles.

Second, on windows, `RouterAllocationTest.perOrderPathIsAllocationFreeAfterWarmUp` (rates-router) measured 256 bytes, where 0 is required. Fix C's additions on the per-order path are primitive arithmetic and a linear scan of two final arrays (`RouterParams.Volatility.sigmaDailyPoints`), and with `RouterPresets.global()` the new terms are zero. There is no boxing, capture, lazy initialisation or string building on the path. The test measured in a second loop after the warm-up loop. A freshly compiled loop's first pass, through on-stack replacement, allocates on the measuring thread, which `DeskQuoterAllocationTest` recorded on 2026-10-31 as a 168-byte first window. So the 256 bytes are warm-up noise, not router code.

Decision. In the strategy test the count assertion becomes `quiet >= 20`, the condition the loop guarantees; the zero-byte rule on each of those windows is unchanged. In the router test the measurement moves into the warmed-up loop and call site: after at least 50 windows of 20 parents, the loop must end with 5 quiet warm-up windows followed by 3 measured windows (60 parents, as before), each allocating exactly zero bytes. The zero rule is not loosened.

Consequences. Both tests still fail on any allocation in the measured windows. They no longer fail when a runner reaches steady state early, or because of the compilation of the measuring loop.

## DR-INT-09: The venue session retries a Logon that the venue did not accept, bounded and journaled

Date: 2026-11-01

Context. `VenueBindingTest` failed on CI at three different assertions across pull requests 3 and 4, the last on ubuntu-latest: after an explicit disconnect and reconnect, the sessions were not active again within 10 s of wall-clock time. The engine-side reconnect path had no retry. `TcpInitiator.connect` either connects or throws. A venue that closes the connection before answering our Logon moves `FixSession` to DISCONNECTED; so does a Logon that times out after one heartbeat interval. `VenueSession` then stayed down until someone called `connect()` again. A QuickFIX/J acceptor that still holds the previous session refuses the new Logon in exactly this way, so a gateway could stay down after a routine reconnect. For a trading gateway that is a defect, not a test-timing problem.

Decision. `VenueSession` takes a `ReconnectPolicy(maxAttempts, initialBackoffNanos, maxBackoffNanos)`.

- **Default.** 5 retries, waiting 100 ms and doubling up to a 2 s cap, about 3.1 s in all.
- **Opt out.** `ReconnectPolicy.NONE` keeps the old behaviour.
- **Retried.** A connection is retried when it ends, or its TCP connect is refused, before the venue has accepted our Logon on it.
- **Not retried.** A session that was active and then dropped, because the loop has pulled its quotes and bringing a live session back is an operator decision. Also not after `disconnect()`, which stops retrying.
- **Journaling.** Each attempt publishes GatewayStatus RECONNECTING (status 3, attempt number in byte 13, `ControlMessages.encodeGatewayReconnecting`) on the inbound ring, so the loop journals every attempt. Consumers treat any status other than CONNECTED as down, so the desk needs no change.
- **Give-up.** After the last retry the session stays down and counts the give-up (`reconnectGiveUps`). The DISCONNECTED status of the failed attempt has already been journaled.
- **Allocation.** The retry path allocates nothing except when a TCP connect is refused, which already left the hot path.

`VenueBindingTest` keeps its wait for both venues to log the old session out (DR-INT-06), as an extra. On timeout it now reports, for each session, the engine-side state, reconnect attempts, give-ups and whether a retry is pending, plus the last 20 lines of each venue's log.

Consequences. A refused Logon no longer leaves a session down for good. The retry is bounded, so a venue that refuses for good is not hammered. Each attempt is in the journal with its number. `VenueSessionReconnectTest` checks four things against a venue on a real socket:
- a first Logon closed unanswered, a second accepted, and the journaled order DISCONNECTED, RECONNECTING (attempt 1), CONNECTED;
- bounded retries with doubling capped backoff and no attempt after giving up;
- no retry after an explicit disconnect;
- the old throwing behaviour under `NONE`.

The policy is set in code (`TradingProcess` uses the default); exposing it in the deployment config is an open item.

Amended 2026-11-01, `VenueSessionReconnectTest`. On pull request 5 the test failed on windows-latest only. In `retriesAreBoundedAndBackOff` the venue had seen 0 connections when the session gave up, where 3 were expected. In `aLogonRefusedByTheVenueIsRetriedAndTheSecondIsAccepted` the session ended DISCONNECTED with statuses [2,3,2,3,2,3,2,3,2,3,2]: six attempts, every one ended by the logon timeout. It did not fail in 20 runs on the 12-core laptop with 4 cores busy.

- **Cause.** The session's logon timeout (the 30 s heartbeat interval) and its backoff run on the session clock, which in the test is a `ManualClock` that `drive` advanced 10 ms per turn. The fake venue answers on its own thread in real time. A probe of the same loop against a venue that never answers (`LogonTimeoutProbe`, a scratch program, 2026-11-01) measured the first logon timeout after 17 to 29 ms of wall-clock time and the whole DEFAULT retry budget, six attempts, spent in 48 to 83 ms. A venue thread not scheduled within about 20 ms therefore lost every attempt. On a 2-vCPU hosted runner, with the test thread spinning, that happens, and it produces exactly the two failures seen. In production the clock is real time and the timeout really is 30 s, so `VenueSession` is not at fault.
- **Fix.** `drive` advances the session clock by the wall-clock time each turn took, so the socket and the session timers share one time base. The 10 s wall-clock ceiling and every assertion are unchanged, and the failure messages now give the state, the statuses, the venue's connection count, attempts, disconnects, logons and give-ups.
- **After.** 20 fresh JVMs with 4 cores busy ran all three tests and passed.

## DR-INT-10: Shutdown waits on a journal follow progress, and an unused prepared segment is abandoned, not flushed

Date: 2026-11-01

Context. Main CI on 75a7e48 (merge of pull request 5) failed in Java 25 on windows-latest, in `LoopAllocationTest`, RING case. The error was `IllegalStateException: journal thread did not stop within one minute` from `RingJournal.close`, reached through `LoopJournals.close` in the test's teardown; the class took 556 s on that runner. It was not an allocation failure: on a slow Windows runner the ring journal's drain exceeded the old fixed 60 s close limit although it was making progress. Two fixed one-minute joins were involved:

- **`RingJournal.close`** joined its journal thread for a fixed 60 s, and left the segmented journal open if the join ran out.
- **`SegmentedJournal.Preparer.stop`** joined the background preparer the same way.

A probe with thread dumps, run by a separate session with 4 to 24 cores held busy, showed where the time went. The 64 MiB ring backlog drained in 0.6 to 1.3 s, yet `close()` took 15 to 56 s. Most of that was `SegmentedJournal.close` → `Preparer.stop` joining the preparer while it pretouched the next 256 MiB segment, which would never be used. That segment was then `force()`d to disk before being deleted. On a starved runner the journal thread can also wait in a roll for that same slow preparation, and while it waits the ring's consumed position does not move. So a deadline on the consumed position alone could still give up wrongly.

Measurements on the development laptop (12 cores, Windows 11, JDK 25.0.5, 4 cores held busy, a fresh JVM per run), closing a full 64 MiB ring that drains into 64 KiB segments:
- the original code closed in 9.3 s, 24.6 s and 24.1 s, under its fixed 60 s, so the full CI failure did not reproduce here;
- with a progress rule and a 5 s stall limit, the close finished after 54.9 s, 9.3 s and 11.2 s;
- the old fixed rule at 5 s would have failed all three while work was still being done.
- with the full change, five more runs under the same load all passed. Closing a journal with 256 MiB segments right after creating it took 0.3 to 3.0 s, against 0.4 to 19.8 s when only pretouching stopped early and the preparer still finished creating and syncing the unused file. The full 64 MiB ring over 64 KiB segments took 11 to 25 s, because that is a real drain through about 1,000 rolls; the close now waits for it instead of failing at a fixed limit.

Decision.
- **One progress rule.** `ShutdownProgress` drives both waits. They poll every 100 ms and keep waiting while a monotonic count moves, giving up only after `SegmentedJournal.STALL_NANOS` (60 s) with no movement.
  - For `RingJournal.close` the count is the ring's consumed position plus `SegmentedJournal.preparerProgress()`. That counter rises by the pages touched and by each segment created or closed, so a journal thread waiting in a roll for a slow preparation still counts as progress.
  - For `Preparer.stop` the count is the preparer's own progress.
- **Diagnosable give-up.** If a wait gives up, the exception gives the backlog in bytes, both ring positions, roll waits, preparer progress and the advances seen. An interrupt is reported the same way and the flag is restored.
- **No close under a writer.** `RingJournal` closes the segmented journal only once its journal thread has stopped.
- **Abandon the unused segment.** Once the journal is closing, the preparer stops working on the segment it is preparing. File creation checks after each 1 MiB zero-fill chunk (`JournalWriter.create(..., keepGoing)`); if abandoned, the half-made `.pending` file is deleted without a sync. Pretouching checks every 16 pages (`JournalWriter.pretouch(keepGoing)`). Both checks count as preparer progress. The unused segment is unmapped without `force()` (`JournalWriter.discard`) and deleted. A segment a waiting roll needs is finished, because stopping is set only after the journal thread has stopped.
- **One path.** The test's teardown and the trading process's shutdown both reach this through `LoopJournals.close`.
- **Test volume kept.** The test still leaves a full ring backlog, because the ring capacity (64 MiB per journal) bounds it and a production loop could hold the same at shutdown. Its warm-up windows and zero-byte assertions are unchanged.

Consequences. A close takes as long as the drain needs and no longer pays to touch and flush a segment nobody will use. A truly stuck thread is still reported after 60 s with no progress, with the numbers needed to tell why. One limit remains: a single `force()` of a new segment is one blocking call with no progress inside it, so a sync that takes longer than 60 s would still be reported as a stall. Tests:
- `RingJournalTest.theCloseGivesUpOnlyAfterTheStallLimitWithNoProgress` checks the rule;
- `RingJournalTest.aBacklogThatTakesManyPollCyclesToDrainStillCloses` closes a full 4 MiB ring over 64 KiB segments with a 10 s stall limit and finds every frame (a 1 s limit failed once under load, during a single segment create and sync);
- `SegmentedJournalTest.pretouchStopsAtTheFirstCheckOnceAbandonedAndReportsProgress` and `closeDuringAPreparationAbandonsItAndRemovesTheUnusedSegment` check the abandonment and the clean-up.

`awaitDrained` keeps its explicit timeout, because its callers pass one.

## DR-R2J-01: Cause of the journaling-on tail: time to safepoint from a page-touching zero-fill, made frequent by the harness's own garbage

Date: 2026-10-31

Context. The P2 harness run with journaling on (`docs/benchmarks/latency-20261031-072915.json`) had a core decode-to-encode p99.9 of 271 ms, and the generator thread, which does no I/O, was late by up to 554 ms. A reproduction on the same laptop on 2026-10-31 at commit 4facfef57c3e (`docs/benchmarks/latency-20261031-114010.json`, journaling on, 512 MiB segments, 60 s warm-up, 300 s measured) ran with `-Xlog:safepoint,gc`, a thread that sleeps 1 ms and logs any oversleep above 20 ms, a watcher of the journal directory, and Windows performance counters at 1 s. Every whole-process stall in that run (226, 94 and 77 ms) was a safepoint whose time to safepoint was the stall (224.0, 92.4 and 76.1 ms), and each began within 0.3 s of a `.pending` segment file appearing, that is, as the background thread started to create the next segment. Available memory stayed between 4.6 and 7.1 GB and hard page reads stayed near zero, so memory pressure and disk reads are ruled out.

Two things combine. First, `JournalWriter.create` called Agrona's `IoUtil.mapNewFile(file, size, true)`, which zero-fills by storing one byte per 4 KiB page through the new mapping in a counted `int` loop. Each store is a page fault taken in Java code, and under `-XX:+UseParallelGC` (the harness flag) C2 removes safepoint polls from counted loops (`UseCountedLoopSafepoints` is false; it is true under G1). Once the loop is compiled, a safepoint requested while it runs waits for the whole remaining fill, and every other Java thread, the event loop included, stops at its next poll and waits too. Second, the harness's gateway thread passed a capturing lambda to `outbound.read` inside its polling loop, creating an object per poll; the garbage caused a young collection, and so a safepoint, about every 0.6 s, which made it likely that one landed during a fill. The 199,968 bytes allocated on the loop thread in the P2 run came from the roll mapping a file on the loop thread (DR-P2C-03) and are a separate issue, fixed by DR-R2J-02.

`SafepointStallProbe` (`rates-bench`) isolates the mechanism: one thread creates four 512 MiB files, another calls `System.gc()` every 20 ms, a third spins and records its longest gap. On the laptop, 2026-10-31, JDK 25.0.5, `-Xms1g -Xmx1g`: Agrona's fill under ParallelGC, 145.9 ms; the same with `-XX:+UseCountedLoopSafepoints -XX:LoopStripMiningIter=1000`, 4.9 ms; under G1, 5.9 ms; zeros written with `FileChannel.write`, 3.0 ms; channel writes followed by a plain page-touch loop, 106.7 ms; the same touch loop with `Thread.yield()` every 16 pages, 2.6 ms.

Decision. New segment files are zero-filled with `FileChannel.write` from a 1 MiB direct buffer and then mapped; the bytes and the format are unchanged. Every loop that touches mapped pages off the loop thread (`JournalWriter.pretouch`) calls `Thread.yield()` every 16 pages, because the return from a native call is a safepoint check, so a safepoint waits for at most 16 page faults. The harness creates its gateway handler once, and its JSON report now gives the number of collections and their total time over the measured phase (`gcCollections`, `gcMillis`). The harness keeps ParallelGC so that its numbers stay comparable with the P2 rows.

Consequences. A safepoint can no longer be held by journal file creation, on any collector. A collection still stops the loop thread for its duration, so garbage on any thread of the loop's process, not only on the loop thread, is a latency risk; the harness report now shows it. Any future code that walks a mapping page by page in Java must keep a safepoint check in the walk. The probe stays in `rates-bench` so the result can be repeated on the pinned Linux host DR-005 calls for.

## DR-R2J-02: Segments are prepared and every page touched off the loop thread, one segment ahead, with an allocation-free hand-over

Date: 2026-10-31

Context. With preallocation (DR-P2C-03), the background thread created the next segment once the current one was half full, but the roll still ran on the loop thread: it waited on a `Future`, submitted the full segment's close to an executor and mapped the new file with `JournalWriter.open`, which allocated about 200 KB and left every page of the new mapping to fault on the loop thread's first store to it (one fault per 4 KiB, about every 40 inbound frames). The brief suggests touching every page and keeping two segments ahead.

Decision. `SegmentedJournal` with preallocation runs one background thread per journal (`journal-prepare-<name>`). `create` touches every page of the first segment before it returns. As soon as a segment becomes current, the thread creates the next one under its `.pending` name, forces it, renames it, maps it and touches every page (`JournalWriter.pretouch`, with the safepoint check of DR-R2J-01). At a roll the loop thread reads the prepared writer from a volatile field, sets its first sequence and previous hash (`continueChain`), writes the full writer to a second volatile field and unparks the thread, which forces and unmaps the full segment. A roll that finds no prepared segment spins until one is ready and counts it (`rollWaits()`). One segment ahead, not two: at 50,000 messages per second the outbound journal fills a 512 MiB segment in about 80 s and preparing one took 0.5 to 12 s in the runs of 2026-10-31 (the 12 s while the disk was also writing back the full segments), so one prepared segment leaves a margin of more than six times; a second would keep another 512 MiB per journal mapped and touched for no measured gain. `rollWaits` is in the harness report so a run that needed a deeper queue would show it.

Consequences. The loop thread never maps, unmaps, forces or creates a file, takes no lock and allocates nothing on a roll; `SegmentedJournalTest.aPreparedRollAllocatesNothingOnTheAppendingThread` measures 0 bytes over 20 rolls and `LoopAllocationTest` now rolls inside its measured windows. Disk use is the current segment plus one prepared segment per journal from the start, where before the second appeared at half full. The prepared segment's header stamp is the time it was prepared. Small segments (tests use 1 to 8 KiB) make rolls wait for the preparer, which is correct but slower; tools that want rolls without a background thread keep `preallocate` false. The volatile hand-over is an exception to the rule that only ring buffers cross threads (docs/ARCHITECTURE.md): it carries a journal writer between the loop and the journal's own background thread, never handler state.

## DR-R2J-03: A second journal mode in which a journal thread writes the mapped file from an off-heap ring

Date: 2026-10-31

Context. The brief asks for a mode in which the loop thread never touches the mapped file, in case the mapped write itself is the problem on Windows, with the replay hash unchanged and both modes covered by the same tests.

Decision. `RingJournal` (`rates-journal`) owns a `SegmentedJournal` and an Agrona `OneToOneRingBuffer` over a direct buffer (64 MiB by default, `LoopJournals.create` takes another size). `append` on the producer thread claims a record whose type id is the template id plus one (the ring refuses type id 0), writes the producer clock's `epochNanos()`, the schema version and the payload, commits it and returns the sequence the frame will get, counted on the producer side. A journal thread per journal (`journal-drain-<name>`, Agrona `BackoffIdleStrategy` from spinning to a 100 microsecond park) drains the ring into `SegmentedJournal.append(templateId, schemaVersion, timestampNanos, ...)`, a new overload that takes the stamp instead of reading the clock, so the sequence and the chain hash are assigned by the same `JournalWriter` code from the same bytes in the same order. A full ring makes the producer spin until there is room and counts it (`producerWaits()`); nothing is dropped. A frame too large for an empty segment is refused on the producer thread. A failure on the journal thread is rethrown on the producer's next append. `close` drains the ring before closing the journal. `JournalMode` (`DIRECT`, `RING`) and `LoopJournals` in `rates-core` make the mode a loop configuration choice; `EventLoop.Builder.inboundJournal(JournalSink)` and `outboundJournal(JournalSink)` are unchanged.

Consequences. The files are identical in both modes frame for frame (`RingJournalTest.ringModeWritesTheSameFramesAsTheDirectMode`), so `LoopReplay` and every reader work on either; `LoopReplayTest`, `LoopPropertiesTest.replayOfAnyRunReproducesTheOutboundHash` and `LoopAllocationTest` run in both modes. The chain hash moves off the loop thread in ring mode, which shortens the loop's append. The costs: one more thread per journal, which needs a core of its own to keep up at high rates; frames in the ring are lost if the process dies, where in direct mode a frame is in the page cache once appended; and a journal thread that falls behind for longer than the ring holds (about 0.5 million QuoteRequest frames at 64 MiB) stalls the loop.

## DR-R2J-04: The direct mode is the default

Date: 2026-10-31

Context. The brief asks for the direct mapped write as the default if it meets the target with journaling on, and the ring mode otherwise.

Decision. `DIRECT` is the default (`LoopJournals.DEFAULT_MODE`, the harness default and `-Dlatency.journalMode`). Measured on the laptop of docs/BENCHMARKS.md on 2026-10-31 with the code of commit 04d12a503a6b, 50,000 QuoteRequests per second, 60 s warm-up, 300 s measured, 512 MiB segments, ParallelGC, core decode-to-encode in the runs whose generator kept to schedule: direct p50 0.2 us, p99.9 1.6 us, maximum 336 us (`latency-20261031-115541.json`); ring p50 0.1 us, p99.9 1.6 us, maximum 440 us (`latency-20261031-120234.json`); journaling off p50 0.1 us, p99.9 1.1 us (`latency-20261031-121134.json`). No safepoint was logged, no roll waited for its segment, no ring filled and the loop thread allocated nothing. In four further runs taken while other load ran on the laptop (discarded by the generator rule), direct reached p99.9 3.6 and 275 us and ring 2.0 and 4.1 us; under a controlled load of ten spinning threads, journaling off reached 11.4 ms and direct 5.6 ms. The JMH append costs 62 ns direct and 19 to 39 ns into the ring on the loop thread (`JournalModeAppendBenchmark`).

Consequences. At p99.9 the two modes are within the noise of unpinned laptop runs of each other, so the choice rests on what else differs: the direct mode has no extra thread per journal and keeps every appended frame through a process crash; the ring mode moves the chain hash and the mapped store off the loop thread, which matters if the mapped write itself becomes the bottleneck (larger frames, slower storage, a host where page faults on touched pages return). The ring mode stays available and tested. The comparison should be repeated on the pinned Linux host (DR-005) and at rates where one journal thread is near saturation.

## DR-R2J-05: Interfaces added or changed by the journal work, for the coordinator

Date: 2026-10-31

Context. A sibling session wires the modules into `rates-app` at the same time and binds journals through the public API of `JournalWriter`, `SegmentedJournal`, `FrameSource` and the `EventLoop` builder.

Decision. Additions only, except where noted. `JournalWriter.append(templateId, schemaVersion, timestampNanos, payload, offset, length)` and `SegmentedJournal.append` with the same parameters take the stamp explicitly; the existing overloads read the clock as before. `SegmentedJournal.rollWaits()` is new. `SegmentedJournal.create(..., true)` now touches the first segment before returning and prepares each next segment immediately rather than at half full (behaviour, not signature). New public types: `RingJournal` (`rates-journal`), `JournalMode` and `LoopJournals` (`rates-core`), and `EventLoop.Builder.journals(LoopJournals)`. `FrameSource`, `JournalReader`, `SegmentedJournalReader`, `Replayer` and the file format are unchanged. In `rates-bench`, the `HarnessConfig` record gains two components, `journalMode` and `ringBytes`, after `journal` (a source change for any caller of its canonical constructor; `HarnessConfig.parse` is unchanged for existing options), the harness takes `--journal-mode` and `--ring-bytes` (`-Dlatency.journalMode`), and its JSON report gains `config.journalMode`, `config.ringBytes`, `counts.journalRollWaits`, `counts.journalRingWaits`, `counts.gcCollections` and `counts.gcMillis` under the same schema name, since the change only adds fields.

Consequences. Code that passes `SegmentedJournal::append` as a `JournalSink` keeps working and gets the touched, allocation-free roll if it creates the journal with preallocation. Code that wants the mode switch uses `LoopJournals`.

## DR-R2W-01: Dv01Ladder and Dv01Vector stay two types, joined by an adapter

Date: 2026-10-31

Context. P1's `Dv01Ladder` (rates-analytics) is immutable, allocates, and has one pillar per curve instrument (for the SOFR curve of the trading process: 2Y, 5Y, 10Y, 12Y, 30Y). P3's `Dv01Vector` (rates-api) is mutable, allocation-free and has the eight key-rate tenors of `KeyRateTenors`. Both are the value change for a one basis point rise: positive for a payer swap, negative for a long bond.

Decision. Keep both. `io.ratesalgo.app.pricing.Dv01Adapter` splits each ladder pillar's DV01 onto the two neighbouring key-rate tenors linearly in curve years (`KeyRateTenors.split`), with no sign flip. The pricing binding applies the same weights in place on the loop; `toVector(Dv01Ladder)` is the allocating form for start of day and tests.

Consequences. Unifying would have meant either an allocating type on the hot path or a mutable type in P1's public API. The parallel DV01 is preserved exactly; `PricingBindingTest` asserts each tenor against `KeyRateRisk.fromGradient` mapped by the adapter to 1e-12 of the parallel DV01.

## DR-R2W-02: The pricing binding: one OIS curve, rebuilt one pillar at a time on the loop

Date: 2026-10-31

Context. P3 needs a fair mid and a key-rate PV01 per instrument without allocating; P1 provides the bootstrap, the pricers and their gradients.

Decision. `AnalyticsPricing` bootstraps a USD SOFR OIS curve (linear in log discount factor, sequential solver) from the par rates of the five SOFR swap templates. A CurveInput of kind ParSwap moves one pillar through `CurveBuilder.rebuild`, which re-solves that pillar and the ones after it; then every swap's par rate and every instrument's unit ladder are recomputed into preallocated arrays: swaps from `OisSwapPricer.pvGradient`, bonds from `BondPricer.dirtyFromCurveGradient`, both mapped through `CurveBuilder.quoteSensitivities`. The unit swap's fixed rate is the template's starting par rate, so the swap object is built once. Bond fair value is the composite book (the quoting engine already prefers the microprice). Before the first CurveInput the reference time is `NO_VALUE`, so the desk does not quote off the starting curve.

Consequences. A 30-year pillar rebuild costs milliseconds of loop time (daily SOFR compounding over about 7,500 days); at the curve feed's 500 ms interval that is acceptable, and a faster feed would need a coalescing step. `PricingBindingTest` asserts par rates and PVs equal a fresh bootstrap of the same quotes to 1e-12.

## DR-R2W-03: Instrument groups are currencies; a QuoteResponse follows its QuoteRequest

Date: 2026-10-31

Context. API_SPEC.md Section 9 runs one loop per instrument group, but the risk engine's limits and the hedger's residual are per currency (DR-P3-12), so splitting a currency across loops would split one risk book.

Decision. A group is a currency. The universe is USD only, so the process runs one group, `usd`, and its journals live in `<journal dir>/usd/`. The inbound router partitions by instrument id; a QuoteResponse carries none, so it goes to the group that received its QuoteRequest (remembered by venue and RFQ id until a final, non-Rejected response); Heartbeat, GatewayStatus and KillSwitch go to every group.

Consequences. Adding EUR or GBP adds a group without changing the gateways. `InboundRouterTest` covers two groups; the process itself has run with one.

## DR-R2W-04: The venue gateway accepts the quoting engine's withdrawals

Date: 2026-10-31

Context. DR-INT-01 left two cancel pairs: the quoting engine sends QuoteWithdraw (60) and OrderWithdraw (61), and the venue gateway knew only QuoteCancel (43) and OrderCancel (41).

Decision. `VenueGateway.offer` translates 60 to FIX Z exactly as 43, and 61 to FIX F; OrderWithdraw has no quantity, so F takes the order quantity `OrderStateTracker` recorded from the NewOrder (new accessor `orderQuantity`). The router's cancels are encoded by the binding as OrderCancel (41) with full fields.

Consequences. No schema change. Retiring one pair remains open.

## DR-R2W-05: Feed gaps reach the loop; a snapshot rebuild is announced with GatewayStatus

Date: 2026-10-31

Context. P3's input health marks a book-feed gap when a venue sequence number jumps and clears it on `onFeedRecovered`, which nothing called. P2's `BookFeedConsumer` recovers from a snapshot inside the gateway, out of the loop's sight. The loop accepts only GatewayStatus from the control schema.

Decision. `FeedBinding` forwards every frame, the gapped one included, so the loop sees the gap; the desk's BookUpdate handler polls the quoting engine at once when input is unhealthy, which pulls every quote (the quote-pull-on-gap path). The binding then forwards the venue's snapshot (a Clear and the levels at the snapshot sequence) and publishes GatewayStatus CONNECTED with the feed's status id 3; the desk calls `onFeedRecovered` on it.

Consequences. Recovery is journaled and replays. `VenueBindingTest` drops two feed frames at the venue and asserts that the pull reached the venue and that quoting resumed.

## DR-R2W-06: An RFQ trade is booked once, from the QuoteResponse

Date: 2026-10-31

Context. The D2C simulator confirms a won RFQ twice: a QuoteResponse (AJ, type 1) and an ExecutionReport (8) carrying the quote id and the client's side. The gateway turns both into SBE. The position keeper books the first as the RFQ trade `venue:R:quoteId` and would book the second as an Execution trade, with the opposite side.

Decision. The second is a confirmation. The desk counts and ignores ExecutionReports from the RFQ venue (the quoting engine books the fill from the QuoteResponse), and `JournalFeed` does not feed them to the keeper.

Consequences. The trade reconciliation matches every RFQ trade against the venue's own register by quote id (`TradeRecord.quoteId`, added).

## DR-R2W-07: One router per hedge instrument; hedger urgency as a fraction of the schedule

Date: 2026-10-31

Context. P4's `BookView` has no instrument argument, so a `SmartOrderRouter` routes one instrument. P3's `HedgeOrderSink` has `cancel` and `cancelAll`, which the router lacked, and states urgency as a deadline in nanoseconds where the router takes a number in [0, 1].

Decision. The desk builds one router per hedge instrument (the four on-the-run Treasuries), each with its own client order id range (`ROUTER_BASE + (j << 40)`). `RouterHedgeSink` dispatches by instrument, maps urgency to `1 - (deadline - now) / scheduleHorizon` clipped to [0, 1] (a deadline of 0 is fully urgent), and refuses with false what a router cannot take. `SmartOrderRouter` gains `cancel(parentId)`, `cancelAll()`, `parentsLive()` and `maxParents()`: a cancelled parent stops routing, cancels its live children and finishes once each child has filled or confirmed. A cancel's ClOrdID is the child id with bit 61 set, so the venue's acknowledgement maps back without a table.

Consequences. Router fills reach the hedger through the desk's ExecutionReport handler (`onHedgeFill`, then `onParentDone` when the router has finished the parent); a fill after the kill switch released the parent goes to the risk engine directly.

## DR-R2W-08: The router assumes a configured trade rate; no futures venue yet

Date: 2026-10-31

Context. The router's queue model needs a traded quantity per second, and the SBE book feed carries no trades. P2 has no futures simulator.

Decision. `LoopBook.view` reports a configured rate (2,000,000,000 minor units per second, 20 million of face, `DeskSettings.tradeRatePerSecond`). The routers route to the CLOB only.

Consequences. A trade feed or an average of our own fills would replace the constant; a futures venue adds a row to `VenueParams[]` when P2 provides one. Both are deferred.

## DR-R2W-09: The smoke day's declared assumptions

Date: 2026-10-31

Context. The simulators were built separately from the desk; run together they need a common market and settings under which the desk trades.

Decision. The day starts at 2026-11-02T13:25Z, 08:25 New York, five minutes before the 08:30 peak of the D2C arrival model; `Venues.T0` is 14:30Z (09:30 New York), not 08:30 as its comment says. The curve feed publishes the D2C simulator's swap mids every 500 ms, and a market maker (`ClobLiquidity`, owner `LP`) keeps three levels a side on the CLOB around the D2C bond mids, refreshed every second. The desk's half spread is held between 0.02 and 0.08 bp because the simulated dealers quote a median of 0.25 bp for swaps; the hedger hedges once the residual's daily standard deviation exceeds 20,000 USD. These are declared values, not fits.

Consequences. On seed 7 the smoke day sends 43 quotes and is filled 25 times (`SmokeTest`, 2026-10-31); with a 0.25 bp maximum half spread it was filled none of 5 times.

## DR-R2W-10: The kill switch enters the loop as a message

Date: 2026-10-31

Context. The control endpoint runs on the HTTP thread; the kill switch belongs to the loop's state.

Decision. `POST /control/kill` publishes a KillSwitch (31, reason Manual, scope 0, requestedBy from `X-Requested-By`) into every group's inbound ring; the desk's handler triggers its `KillSwitchController`, whose listeners pull quotes and cancel every router parent through the real gateways, and the controller's own KillSwitch goes to the control ring. With the loops on their own threads the endpoint waits up to two seconds for every group to report the switch engaged and no live quote.

Consequences. The kill is journaled as an input, so replay reproduces the pulls; the rehearsal's "kill switch journaled" check reads the inbound journal.

## DR-R2W-11: The back-test binding reshapes the journal and runs the desk as a strategy

Date: 2026-10-31

Context. P5's back-tester reads one RAJ1 file of schema-1 frames, keeps a top of book from level-0 BookUpdates, settles RFQs itself, has no resting-order cancel, and does not pass Heartbeats to strategies.

Decision. `JournalExport` copies QuoteRequest, CurveInput and the curve feed's Heartbeat from a loop's inbound journal and turns every change of each instrument's top of book into Clear, Add bid and Add ask at level 0 with a renumbered sequence. `DeskStrategy` runs the desk's `DeskGroup` on its own loop, synchronously per callback; it sends every child order as a market order, drops router cancels, and restores the curve heartbeat with each curve input. `DeskBacktest` runs it under `data/presets.yaml` at the central band point with the universe's own instrument set and writes P5's daily report.

Consequences. The back-test's hedge fills come from P5's hedge cost model, not from the CLOB's queue, so the report's hedge slippage is that model's. The replay of the same journal through `LoopReplay` is the exactness test; the back-test produces the report.

## DR-R2W-12: The allocation and hot-path gates with the real desk

Date: 2026-10-31

Context. rates-core's gates cover the loop with a trivial handler. With the desk registered, two allocations showed (JFR with TLABs off): `SmartOrderRouter.venues()` clones its array, which the hedge sink called per parent, and `CurveBuilder.maxRepricingError` iterated an immutable list on every rebuild. After both were removed, a few hundred bytes still appeared in some windows at irregular times, attributed by JFR to enum class initialisation and to call sites that do not allocate (deoptimisation).

Decision. The hedge sink caches each router's lot increment; `CurveBuilder` uses an indexed loop. `WiredLoopAllocationTest` sends 600,000 messages of warm-up and four windows of 150,000: no window after the first may allocate more than 1,024 bytes and at least one must allocate nothing. `AppHotPathRulesTest` applies rates-core's ArchUnit rules to the classes marked `@HotPath` in rates-app, except that Agrona's primitive-keyed maps are allowed.

Consequences. A per-message leak of one byte still fails (150,000 bytes a window). The gate is weaker than rates-core's in one way: the clean window need not be the last.

## DR-R2W-13: Simulated time, two ways to run, and quiescence by FIX sequence numbers

Date: 2026-10-31

Context. The simulators run their own QuickFIX/J threads; the process must run a seeded day reproducibly in tests and in real time in a container.

Decision. One `SimClock` (an immutable reading behind a volatile field) drives the venues, the curve feed and the liquidity. The loops read it through `AnchoredClock`, so a latched monotonic time and wall time always come from one reading. The FIX sessions take intervals from the system monotonic clock and stamps from simulated time, so heartbeats keep real time with QuickFIX/J. `TradingProcess.step` drives everything on one thread and, after each step, pumps until no work is left and each venue's QuickFIX/J sequence numbers equal ours (`getExpectedSenderNum` is the venue's own outgoing number). `startThreads` runs each loop on a `LoopRunner` and the rest on a driver thread pacing simulated time.

Consequences. Two runs of the smoke day gave the same counts on the laptop (2026-10-31). The threaded run is not reproducible step for step, but its journals replay exactly (`BacktestBindingTest`).

## DR-R2W-14: The rehearsal starts rates-app

Date: 2026-10-31

Context. The P6 rehearsal started `OpsProcessMain`'s simulated quoter and checked that a quote was live right after readiness.

Decision. Both images and the process-mode rehearsal start `io.ratesalgo.app.TradingProcessMain`; the trading role runs the simulators in process at ten times real time from 08:25 New York, and the venue-sim role serves health and metrics. A real quote lives for the venue's five-second window, so the rehearsal polls the status for up to 60 s until one is live instead of reading it once. The trading image copies `data/refdata`.

Consequences. `OpsProcessMain` and `SimulatedQuoter` remain in rates-ops but no image uses them.

## DR-R2W-15: SpotBugs in rates-app

Date: 2026-10-31

Context. SpotBugs reported 17 items on the new module. Twelve were EI_EXPOSE_REP and EI_EXPOSE_REP2: the assembly hands collaborators to each other and returns them to tests and the status endpoint. The rest were real: `TradingProcess` synchronised on its own instance (reachable by any caller) and read its driver thread field without the lock, `start` rethrew a caught exception, two fields were set after construction, and the pricing binding compared quotes with `!=`.

Decision. `config/spotbugs-exclude.xml` excludes EI_EXPOSE_REP and EI_EXPOSE_REP2 for `io.ratesalgo.app` and its subpackages, as DR-P2V-07 and DR-P3-09 did for theirs. The other findings are fixed: a private lock object and a volatile driver field, a success flag in `start`, constructor-set fields (an unbound child lookup that throws until the router exists), and `Double.compare` for the quote change test.

Consequences. Every other pattern stays enabled for rates-app.

## DR-R2B-01: Desk features in a new module with a byte-identical baseline

Date: 2026-10-31

Context. The R2 business brief asks for seven desk features measured against today's engine, built while other sessions change `rates-quoting`, `rates-hedge`, `rates-backtest` and the venues on their own branches.

Decision. The features live in a new module `rates-strategy` that depends on the others as libraries and plugs into their existing interfaces (`Strategy`, `CarryModel`, `AxeSource`, `TierTableControl`, `ToxicityTable`); no other module's source changed. Each feature is an interface whose default is today's behaviour. `DeskQuoter` with every default sends the same messages as `ModelSpreadStrategy` (P5-001's treatment, the best tested quoter) on the same journal, and `DeskQuoterBaselineTest` checks the output chain hash, so every experiment compares one feature against today's engine and nothing else.

Consequences. The features run in the back-tester today. The live `QuotingEngine` takes `AxeSource` and `TierTableControl` already; the hedge budget, hedge timing, quote sizer and inventory penalty need extension points in `Hedger` and `QuotingEngine` before they can run live (DR-R2B-09).

## DR-R2B-02: Pre-registrations under data/preregs

Date: 2026-10-31

Context. The brief names `research/prereg/` for the pre-registrations. The repository keeps them in `data/preregs/`, where `PreRegistration`, `ExperimentCli`, `ratesresearch.prereg.validate` and the Python tests look, with configs in `data/experiments/`.

Decision. R2B-001 to R2B-007 are `data/preregs/R2B-00x.yaml`, sharing `data/experiments/R2B.config.yaml`, validated by both validators.

Consequences. One place for every pre-registration; the brief's path is not used.

## DR-R2B-03: The assumption band covers slope, dealer count and informed share together

Date: 2026-10-31

Context. DR-008 requires every simulator result across a declared band. The back-tester's `BandPoint` varies only the win-probability slope; the brief asks for dealer count and informed-flow share too, which change the journals themselves.

Decision. One joint band with three points, declared in the experiment config: slope -1.25, -2.5, -5.0 per bp (preset `usd_rfq_hit_rate`), dealers 2 to 4, 2 to 5, 3 to 6, and informed share of every tier times 0.5, 1.0, 1.5. `BandScenario` applies the market parameters to a copy of `data/presets.yaml` for each point, so each point has its own journals; presets on disk are unchanged.

Consequences. Low is a benign market and high a harsh one; the three parameters move together, so a result at one point cannot be attributed to one of them. A factorial band (27 points) is a possible later experiment.

## DR-R2B-04: Statistics and guards of the desk experiments

Date: 2026-10-31

Context. The seven experiments share journals and the baseline run, and common random numbers pair every session; P5-001 used Welch's unpaired test. The brief asks for Holm across the seven and adoption only without limit breaches.

Decision. Per treatment and band point: a paired t-test of the daily difference of the pre-registered metric, Holm over all 21 (treatment, point) tests, and a moving-block bootstrap interval of the mean difference (blocks 2, 2,000 resamples, seed 11). Supported when improved (adjusted p below 0.05 and the interval above zero) at all three points. Adopted only if supported, no limit breach on a session where the baseline did not breach, and the daily P&L difference interval not entirely below zero, at every point. The relative limit guard was chosen on the fitting period, where the baseline itself breached the 1,000,000 USD per bp net DV01 limit on some sessions (one large RFQ is enough); an absolute guard would have made adoption impossible for any feature that leaves sizing alone.

Consequences. The P&L guard is a non-inferiority screen: it fails only when P&L is significantly worse, so an adopted feature may still have a negative mean P&L difference (R2B-001 at central and high does).

## DR-R2B-05: The learned tiering carries its state across sessions

Date: 2026-10-31

Context. Each client trades about a dozen times a session, below the 30-fill minimum sample, so a tiering that starts empty every session never moves anyone.

Decision. The experiment keeps one `MarkoutTiering` per band point (one for R2B-004, another for R2B-007) and runs the sessions in date order, as a desk's rolling client analytics would.

Consequences. Sessions are no longer independent for the tiering treatments; the paired comparison per session is unaffected, but the bootstrap blocks of 2 sessions understate the dependence of the learning curve. The simulator's static tiers are the truth the market was generated with, so R2B-004 tests the cost of learning against an oracle table.

## DR-R2B-06: No SBE message for the axe list

Date: 2026-10-31

Context. The brief allows an axe-list SBE message in template ids 120 to 129 only if needed.

Decision. None is added. The quoting engine reads axes in-process through `AxeSource`; `AxeBook.publish()` returns the list for the desk report and for a later publisher. API_SPEC Section 2 is unchanged and ids 120 to 129 stay free.

Consequences. Publishing axes to clients or venues (an axe feed) needs a message and a record when it is built.

## DR-R2B-07: Adopt the netting hedge budget as the desk default

Date: 2026-10-31

Context. R2B-001 (ledger seq 12, commit 8c4eacb, 20 sessions 2026-03-02 to 2026-03-27) met its pre-registered rule: hedge slippage improved at all three band points (by 292,958, 136,853 and 43,116 USD per day, Holm p at most 0.0052) with no new limit breach and no significantly worse P&L. R2B-002 to R2B-007 did not.

Decision. `DeskFactory.ADOPTED` is `[internalisation]` and `deskDefault()` builds the baseline with `NettingHedgeBudget` (soft 150,000, hard 400,000, retain 50,000 USD per bp, hold 120 s). The experiment baseline (`baseline()`) stays today's engine so that later experiments remain comparable with these.

Consequences. Mean absolute net DV01 roughly doubles (29,849 to 66,313 USD per bp at central) and the daily P&L difference has a negative mean at central and high with intervals that include zero; risk limits must allow the larger intraday inventory. The holdout (2026-04-06 to 2026-04-10) has not been opened; opening it once is the next check. These are simulator consistency checks (DR-008), not evidence of edge.

## DR-R2B-08: SpotBugs exclusion for shared desk state

Date: 2026-10-31

Context. SpotBugs reports EI_EXPOSE_REP and EI_EXPOSE_REP2 where `AxeBook` and `DeskQuoter.Features` hold the shared `RiskLadder`, and where `DeskReport` holds its `DeskMetrics`.

Decision. A class-level exclusion for those three classes and those two patterns in `config/spotbugs-exclude.xml`, as DR-P3-09 did for the hedger and quoting engine: reading the ladder by reference at call time is the design. Other classes copy what crosses their boundary (`DeskExperiment.Outcome`, `DeskFactory`).

Consequences. Every other pattern stays enabled for the module.

## DR-R2B-09: Extension points the features needed and did not find

Date: 2026-10-31

Context. The brief asks to plug into existing extension points and to note missing ones for the coordinator.

Decision. Worked around inside `rates-strategy`, and listed here:
1. `StrategyContext.quote` has no quantity and the runner settles the requested size only, so a smaller quoted size can only be a decline (feature 3).
2. `StrategyContext` exposes net DV01 but no ladder; `DeskQuoter` rebuilds the ladder from positions on every RFQ and fill.
3. `Experiment` builds strategies from a closed switch and tests only total P&L with Welch; the desk experiments have their own harness (`DeskExperiment`) reusing `BacktestRunner`, `Stats`, `PreRegistration`, `Ledger` and `SyntheticDay`.
4. `DailyReport` has a router fill-quality hook but no general section hook; the desk section is a separate `desk_report.json` and `desk_report.md`.
5. `Hedger` has its own trigger and decay schedule with no pluggable budget or timing policy, and `QuotingEngine` has no hook for a size policy (beyond the tier's maximum quantity) or an inventory funding penalty; features 1, 3, 5 and 6 run only in the back-tester until those exist.
6. The back-tester delivers no Heartbeats to strategies (noted by r2-wiring); `DeskQuoter` does not need them.

Consequences. Each item is a small change in a module another session owns; with items 1 and 5 the adopted and future features can run live.

## DR-R2B-10: Coupon carry in the desk experiments

Date: 2026-10-31

Context. The back-tester's `CarryModel` is `NONE` by default, so bond inventory pays repo but earns no coupon, and every bond position is carry-negative regardless of the curve.

Decision. The desk experiments pass `CouponCarry` with declared coupons (UST 2Y 3.60, UST 10Y 3.95 percent, against the book's 4.25 percent repo) to every run, baseline included. The coupons are in the experiment config and covered by its hash.

Consequences. P&L in these records is not directly comparable with P5-001's, which had no carry; within the R2B records every strategy has the same carry rule.

## DR-R2R-01: Coupons for carry and basis are selected by record date

Date: 2026-10-31

Context. `BasisAnalytics` and `BondCarry` counted a coupon when its payment date fell in (settlement, horizon] and settlement preceded the ex-dividend date. A gilt held from before its ex-dividend date to a date inside the ex-dividend period, but before the payment, receives the coupon, while the accrued at the horizon is already negative. The coupon was dropped: on the Long Gilt about 2 per 100 of carry and an implied repo that rules the bond out of the cheapest-to-deliver choice.

Decision. `FixedRateBond.entitlementDate(i)` returns the ex-dividend date when the convention has one and the coupon date otherwise. A position held from s to h is paid coupon i exactly when s < entitlementDate(i) <= h. A coupon paid after h enters at its value at h to first order in the repo rate (the existing linear form, with a negative day count).

Consequences. UST and Bund results are unchanged (record date equals coupon date). The golden basis cases are UST only, so the test is hand-computed (`BondAnalyticsTest.giltHeldIntoTheExDividendPeriodCarriesTheCouponPaidAfterTheHorizon`); gilt and Bund baskets in the golden file remain open.

## DR-R2R-02: A kill completes even when a listener throws

Date: 2026-10-31

Context. `Hedger.onHedgeFill` called `risk.onFill` before updating the parent. A fill that breached the daily loss limit fired the kill switch from inside the call; `onKill` released the pre-fill remainder and `RiskEngine.release` threw. `KillSwitchController.trigger` had already set KILLED, so the exception left the later listeners (other currencies' hedgers) uncalled and no KillSwitch message sent.

Decision. The hedger brings the parent up to date before the fill reaches the risk engine. `trigger` calls every listener inside its own try block, sends the KillSwitch message, counts failures (`listenerFailures`) and then rethrows the first one. SpotBugs' THROWS_METHOD_THROWS_RUNTIMEEXCEPTION is excluded for this class only, as for `TimerWheel`.

Consequences. A listener bug still reaches the caller as an exception, but after the desk is safe.

## DR-R2R-03: A fill after a confirmed cancel is booked and does not shorten the parent

Date: 2026-10-31

Context. When a venue confirmed a cancel with a cumulative quantity above what had been received, the router freed the rest and could route it again. A later fill beyond the child's outstanding quantity was subtracted from the parent's outstanding a second time, so the parent finished while a re-routed child was still live, and its slot could be reused under that child.

Decision. `ChildOrderBook` reports the part of a fill beyond the child's outstanding quantity (`lastOverfill`, `overfills`); the router subtracts only the rest from outstanding. A parent finishes when filled to at least its quantity and nothing is outstanding.

Consequences. The parent can end over-executed by the overfill, which the venue did trade; the counter is there for an operator alert. Cancelling working children to absorb the excess is not done.

## DR-R2R-04: The router's cost model charges a venue's price offset

Date: 2026-10-31

Context. API_SPEC.md Section 15.1 measured every cost from the venue's own mid. Two venues with the same spread and parameters but books a tick apart cost the same, and the tie went to row 0, at the worse price.

Decision. Both costs gain o = s (venueMid - m), with s the side sign and m the consolidated mid (highest bid and lowest ask over the decision's rows with a two-sided quote). The offset is zero when all mids agree.

Consequences. The scenario `ust10y_three_venue_v1` re-run gives a router cost of -0.2565 bps and savings of 2.9388 [2.7792, 3.0631] and 2.7673 [2.5802, 2.9026] bps (before: -0.1957, 2.8780, 2.7065). `SmartOrderRouterTest.bookMovingAwayCancelsAndReroutes` asserted the old behaviour (all quantity back to the venue that had moved two ticks away) and now asserts a split.

## DR-R2R-05: FIX integers, tag numbers and timestamps are parsed strictly

Date: 2026-10-31

Context. Tag numbers were accumulated in an int without a digit limit (4294967345 read as 49, SenderCompID) and accepted leading zeros. Integer fields were parsed as scale-0 decimals, so "34=1.0", "+5" and a checksum of "+05" were accepted and Long.MIN_VALUE refused. A UTCTimestamp accepted day 31 in every month.

Decision. A tag is at most nine digits with no leading zero; an integer field is an optional '-' and digits, accumulated negative so the full long range parses; the day of a timestamp is checked against the month's length with the Gregorian leap rule.

Consequences. A counterparty sending a non-canonical integer is refused with MALFORMED or a format error, the conservative reading of FIX 4.4.

## DR-R2R-06: The holiday fixture covers 2026 and 2027 and refuses other years

Date: 2026-10-31

Context. `holidays.csv` ended on 1 January 2027 and `FixtureCalendarSource` treated every date it did not list as a business day, so 2027 settlement dates ran through MLK Day, Good Friday and Easter Monday.

Decision. `tools/gen_refdata_fixtures.py` writes 2026 and 2027 for US-GS (SIFMA recommended full closes), UK (England and Wales bank holidays) and TARGET. The source refuses a date outside the years between its earliest and latest holiday.

Consequences. Code that asks about 2028 fails loudly until the fixture is extended or the rates-analytics calendars replace it.

## DR-R2R-07: Fail days stop at the cancel date

Date: 2026-10-31

Context. `SettlementObligation.daysFailed` froze its end date only on settlement, so a fail later cancelled kept accruing days, and fail cost, with the as-of date.

Decision. The end date is set on SETTLED or CANCELLED.

Consequences. None beyond the corrected count.

## DR-R2R-08: No quote at negative edge; one hit-rate label per RFQ

Date: 2026-10-31

Context. When the expected adverse cost (toxicity plus winner's curse plus hedge cost) reached `maxHalfSpreadBp`, the optimiser returned the cap and the engine quoted although E(s) = P(s)(s - c) is not positive for any allowed s. A Done with a cover taught the hit-rate model two wins, the second at the cover distance with abs(), so a cover on the wrong side also counted.

Decision. The engine passes with `NEGATIVE_EDGE` when cost >= maxHalfSpreadBp. A Done teaches one win; a cover on the client's side of the trade adds to the mean cover distance only.

Consequences. The online intercept no longer drifts toward its upper band from double counting. `QuotingEngineTest.doneBooksTheFillAndTeachesTheModelIncludingTheCover` asserted two updates and now asserts one.

## DR-R2R-09: The risk-reducing exemption counts working reservations

Date: 2026-10-31

Context. DR-P3-05 exempts an order that offsets the net PV01 "without overshooting" from the PV01 and loss checks. The test used the position only, so with a working hedge parent reserved on the same side a second order could carry the book through zero in the worst case.

Decision. The overshoot test adds the other instruments' worst-case net reservation on the order's side (the instrument's own reservation is left out, as in the envelope check).

Consequences. Some hedge parents are now checked and refused. The P3 gate tests re-run: `MillionRfqTest` 533,266 quotes, 55,600 fills, 3,298 hedge parents, 3,288 risk rejects, 0 violations (before 476,215, 30,933, 4,446, 1,654); `ChaosTest` 9,269 kills and 643 resets (before 9,272 and 646). Less of the envelope is reserved by stacked hedge parents, so more RFQs find capacity.

## DR-R2R-10: An instrument without a hedge cost is not traded

Date: 2026-10-31

Context. `TableHedgeCostModel` priced an unknown instrument at Double.MAX_VALUE / 1e6. `BoxQpSolver` scaled its stop tolerance by max |c_i|, so one missing entry made the stop about 1e293: the solver stopped after one iteration and reported converged with the residual barely reduced (sd 508,293 from 557,600 in the reviewer's probe).

Decision. An unknown instrument costs positive infinity; the planner fixes any instrument without a finite cost at zero lots; the solver's scale is taken over free variables (lo < hi) only.

Consequences. The documented plan (sd 10,352 at cost 62,418) is unchanged.

## DR-R2R-11: Swap golden tolerances sized to what the gate must catch

Date: 2026-10-31

Context. The swap PV tolerance was 1e-8 of notional and the par-rate tolerance 1e-6, against measured worst cases of 8.5e-15 and 3.5e-16. 54 of the 83 golden swaps are spot par swaps, whose PV and par rate move by less than those tolerances under a payment-lag or day-count slip.

Decision. 1e-12 of notional and 1e-10. The Python regeneration test keeps the 1e-6 absolute floor of DR-P1-06, a cross-platform rounding allowance rather than a model tolerance; API_SPEC.md Section 8.1, which said 1e-12, is corrected.

Consequences. Three orders of magnitude of headroom remain on the Java side.

## DR-R2R-12: CLOB quotes are rounded to the venue tick

Date: 2026-10-31

Context. `ClobQuoter` rounded targets to the Decimal9 grid; `ClobBook` refuses a price off its tick, so every order on a 1/128 or 1/256 venue would be rejected.

Decision. `enable(instrumentId, tickMantissa)` takes the venue tick; the bid rounds down and the ask up to it. `enable(instrumentId)` keeps tick 1 so existing callers are unchanged until the wiring passes the tick from reference data.

Consequences. The binding in rates-app must call the two-argument form (handed to r2-wiring).

## DR-R2R-13: Late fills on expired quotes and killed hedge parents are booked

Date: 2026-10-31

Context. A fill can race a quote's expiry or a kill's cancel-all (DR-P3-05 says so). The quoting engine freed an expired quote at once, so a late Done was counted as unmatched and never reached risk; the hedger dropped its parents on a kill, so a late fill threw "unknown hedge parent".

Decision. The quoting engine keeps the last 1,024 expired quotes as tombstones and books a late Done from them once, without teaching the hit-rate model. The hedger remembers the parents dropped by the last kill (recorded before the cancel-all, which may report them done) and books a late fill up to each parent's remainder. A fill matching neither is still unmatched or refused.

Consequences. Positions follow what the venues traded. Both paths are allocation-free (fixed arrays, linear scan).

## DR-R2R-14: SpotBugs exclusions name classes, not packages

Date: 2026-10-31

Context. EI_EXPOSE_REP and EI_EXPOSE_REP2 were excluded for whole packages in rates-router, rates-refdata, rates-fix, rates-hedge and rates-quoting, so a new class that leaked an internal array would pass unnoticed.

Decision. Each match now names the classes SpotBugs flags with the match removed (router: ChildOrderBook, DecisionCodec, SmartOrderRouter, AggressiveBaseline; refdata: RefData; FIX codec: FixParser; hedge: Hedger, HedgePlanner; quoting: ClobQuoter, Desk, FairValueEngine, PackageQuoter, QuotingEngine). The FIX gateway and net packages and rates-venues keep their package matches because other sessions own and are changing them.

Consequences. A new class in these modules that stores or returns a mutable collaborator needs its own entry and a reason.

## DR-REL-01: Daily-P&L risk across the band is derived, never recorded in the ledger

Date: 2026-10-31

Context. The release needed daily-P&L Sharpe ratio, maximum drawdown and P&L volatility across the assumption band in the daily report and the back-test outputs. The ledger's `results` are compared byte for byte with a re-run (`FirstExperimentTest`), so adding fields to them would change every future record's shape and break that comparison for P5-001.

Decision. `Stats.pnlRisk` computes the figures from a daily P&L series; `BandRisk` renders them with a fixed label that calls them a simulator consistency check (DR-008). `ReportCli` and `ExperimentCli` write them as separate files and as a report section. The ledger record is unchanged; the table can be recomputed from any recorded `daily_pnl` at any time. Sharpe uses a zero risk-free rate and annualises by the square root of 252; drawdown is measured from zero.

Consequences. Old ledger rows get the table for free (README.md quotes P5-001 and AUDIT-11). The daily report runs each journal once more on its own to get a daily series, which roughly doubles its run time with two or more journals. A session shorter than a day (AUDIT-11 to 14) still gives a per-session ratio, which must not be annualised; the README says so.

## DR-V1H-01: The back-tested desk can slice hedges, passive first with escalation

Date: 2026-11-01

Context. PNL_AUDIT.md item 1: the back-tested desk sends the internalisation budget's whole excess as one market order, which walks the book. The back-tester had no cancel and no view of displayed quantity, so a passive-then-market policy could not be expressed.

Decision. `StrategyContext` gains `cancelOrder`, `touchPrice` and `touchQuantity`, as default methods so other implementations are unchanged. `DeskQuoter` takes an optional `HedgeSlicing(depthFraction, minChildLots, passiveWaitNanos, urgentDv01)`. Children are sized from the opposite touch. Each is sent first as a limit order at our touch, then cancelled and sent as a market order after the wait. Children continue until the parent's excess is done (net DV01 reaches the parent's target). The strategy also records the implementation shortfall against the mid at each decision. Without slicing the messages sent are unchanged (DeskQuoterBaselineTest).

Consequences. The policy runs inside the strategy because the back-tester has no router; production keeps `Hedger` through `SmartOrderRouter`. A first version asked the budget again after each child. On the fitting period that left the residual at the hard budget and failed the residual-risk guard, so the parent target was added before pre-registration.

## DR-V1H-02: A cost-aware hedge trigger, tested and kept opt-in

Date: 2026-11-01

Context. PNL_AUDIT.md item 10: no fixed DV01 trigger is right across the band. R2B-005 (OptimalStoppingTiming) was not supported.

Decision. `CostAwareHedgeBudget` hedges all net DV01 in two cases: when the variance charge of holding it over a horizon exceeds the depth-walk cost of hedging it now, or when net DV01 is above a cap. `HedgeBudget.observeMarket` passes it the live spread, tick and touch depth. It was pre-registered as V1H-002 against the fixed 100,000 trigger (horizon 2,700 s, cap 500,000). Result, ledger seq 24: improved at central only, so not supported. It is not adopted, and `DeskFactory.baseline()` keeps the 100,000 trigger.

Consequences. Item 10 stays open. In the synthetic market volatility and spread are constant, so the rule is close to a fixed threshold (about 98,000 DV01 at median depth). A better test needs varying volatility and expected netting flow.

## DR-V1H-03: Hedge slicing is the desk default

Date: 2026-11-01

Context. V1H-001 (ledger seq 23, code c9d911e) tested slicing against the netting desk default sending one market order. It used depth-walk fills on 20 sessions, with Holm over 6 tests.

Decision. Slicing was supported at every band point: shortfall +446k at L, +300k at C and +71k at H per session, Holm p at most 0.0022. The residual-risk, P&L and limit guards all passed. `DeskFactory.ADOPTED_SLICING` (children of the full touch quantity, at least 10 lots, 5 s passive, market above 400,000) is used by `deskDefault()`. `nettingOnly()` keeps the previous default for comparison.

Consequences. Later experiments compare against the sliced default. The passive leg added nothing measurable on the fitting period, and the queue model is optimistic for it, so the attributable gain is from depth-sized children. Only the back-tested desk changes.

## DR-V1G-01: Recipe 35 verifies a committed Java journal; the fixture is pinned by a Java test

Date: 2026-11-01

Context. Recipe 35 shows Python checking a journal written by Java. Python recipes run in tools/check_cookbook.py without a JVM, so the journal has to be a committed file, and a committed binary can fall behind the writer.

Decision. data/fixtures/journal/recipe02.raj holds recipe 2's three frames in a 256-byte file. JournalFixtureTest in rates-cookbook rewrites it with the current JournalWriter on every build and fails on any byte difference (regenerate with -Dfixture.regenerate=true). The recipe compares its final hash with the value Java printed in recipe02.txt, read from that file. Recipe 36 prints comparisons, not byte counts, for the allocating path, because the count depends on the JDK's string layout.

Consequences. A change to the frame layout or chain hash fails JournalFixtureTest first, then the cookbook check until the fixture and recorded output are regenerated.

## DR-V1G-02: No UK DMO golden test; the formulae paper has no conventional worked example

Date: 2026-11-01

Context. A golden test reproducing a DMO worked example was planned. On 2026-11-01 the paper "Formulae for Calculating Gilt Prices from Yields" was fetched from https://www.dmo.gov.uk/media/1sljygul/yldeqns.pdf (37 pages, PDF created 2011-09-06). It gives the conventional gilt price, yield and accrued interest formulae without a numerical example. Its only worked number is the three-month-lag reference RPI for 20 July 2001 (173.77419 from the April and May 2001 RPI), which applies to index-linked gilts. The platform has no index-linked gilt code.

Decision. No golden test is added and no numbers are invented. The conventional gilt formulae stay checked by recipe 7 and the existing analytics tests. If index-linked gilts are added, the reference RPI example is the first golden test for them.

Consequences. The UK DMO worked example stays an open gap. It needs a published DMO price and yield pair (for example a reference price with its yield on a stated date), downloaded by hand because the /data/ pages are behind bot protection (data/SOURCES.md).

## DR-V1G-03: CI builds every image in deploy/ and runs the rehearsal inside the trading image

Date: 2026-11-01

Context. The ops rehearsal job built the three images when Docker was present but never started one, so a missing jar or a broken entrypoint would only show up on a real host.

Decision. A separate Ops job, "Container images and in-image rehearsal", builds every deploy/*/Dockerfile, resolves compose.yaml, starts the report image to check its argument handling (exit 2, "missing --journal"), and runs deploy/rehearsal.py in process mode inside a throwaway layer over the trading image that adds python3. The rehearsal runs as the image's non-root user with --classpath /opt/rates/lib/*, so it uses the jars the image ships.

Consequences. The images are exercised on every push to main and every pull request. The throwaway layer is not published; the production image stays without Python.

## DR-V1G-04: The CI latency run writes its percentiles to the job summary

Date: 2026-11-01

Context. The informational latency job only uploaded a JSON artifact, so reading a Linux number meant downloading it.

Decision. tools/latency_summary.py prints each report's percentiles as a Markdown table, and the job appends it to $GITHUB_STEP_SUMMARY. docs/BENCHMARKS.md cites one such run by URL, labelled as a shared GitHub-hosted runner, not a tuned host. It never gates (DR-005).

Consequences. A Linux reference number is one click from each CI run. It is not comparable with a pinned, isolated host.

## DR-V1C-01: Measure risk carry time; hedge latency is a minor term

Date: 2026-11-07

Context. A reader asked whether hedge latency is modelled. The back-tester has always delayed hedge orders by `order_latency_ms` (2 ms), but nothing reported how long the desk carries client risk.

Decision. Add `RiskCarry` (time from each client fill to the next hedge fill) and an exploratory `LatencySweep` on the V1H-001 fitting period, with no ledger record. From 0 to 1,000 ms, latency barely moves P&L or carry, while median carry is 20 minutes to over an hour. The time goes into the hedge decision, not the order path.

Consequences. Latency stays a declared 2 ms. A result that depended on it would need a fitted value and jitter.

## DR-V1C-02: A carry-time limit, tested and kept opt-in

Date: 2026-11-07

Context. DR-V1C-01: exposure under the hedge trigger can stay on the book for hours.

Decision. `CarryLimitHedgeBudget` hedges net DV01 to zero once it has stayed above 50,000 USD per bp for 900 s without the inner budget hedging it. `HedgeBudget` gains `excessDv01(ladder, sinceFill, now)` and `nextCheckNanos()`, with defaults, so existing budgets are unchanged. Pre-registered as V1C-001 against the desk default. Result, ledger seq 31: improved at central and high, inconclusive at low, so not supported, and not adopted.

Consequences. The desk default is unchanged. A next test could make the limit depend on flow rate, or on the risk charge directly, since the low point is where netting already does the work.

## DR-V1C-03: A flow-aware carry limit, tested; the idea stops here

Date: 2026-11-07

Context. DR-V1C-02: the carry limit helped at central and high but not at low, where client flow is heaviest.

Decision. `CarryLimitHedgeBudget` gains an optional quiet gap: the limit waits while client fills keep arriving within the gap. Pre-registered as V1C-002 (quiet 600 s, chosen on the fitting period) on new sessions and a new evaluation period. Result, ledger seq 32: improved at high only, so not supported, and not adopted.

Consequences. Across both tests the limit always shortens carry and lowers the risk charge, gains clearly only when flow is sparse, and has a negative but not significant mean P&L effect. It is a risk-appetite dial, not a free improvement. No further tuning on these sessions; the rule stays opt-in.


## DR-FWD-01: Forward-log swap marks from DTCC prints, with a Treasury-plus-spread fallback

Date: 2026-11-07

Context. The desk quotes SOFR OIS swaps, and no free source publishes a SOFR swap curve that a script may fetch. CME settlements were checked on 2026-11-07: the site answered HTTP 403 with a notice that its Data Terms of Use forbid scripts. DTCC's end-of-day public dissemination file, already used by P5 and EDGE-E5, has real traded SOFR OIS rates.

Decision. The owner chose option (a) of docs/research/PAPER_TRADING_PLAN.md Section 8.1. A swap mark is the median fixed rate of spot-starting, non-package USD SOFR OIS prints executed 14:30 to 16:00 New York time, if there are at least five (EDGE-E5's central window and rule). Otherwise it is the Treasury par yield plus the spread of the latest earlier DTCC mark of that tenor, or a declared spread before there is one. The source of each mark is written into the input file and the decision line. The Treasuries and the note future are model prices from the Treasury par curve; there is no free futures settlement feed.

Consequences. On a fallback day the swap mark moves with Treasuries only, and switching between sources adds a mark change the market did not make; the ledger records the source so these days can be separated. In the ten-day dry run 38 of 40 tenor-days had a DTCC mark.

## DR-FWD-02: The forward ledger lives on the forward-log branch

Date: 2026-11-07

Context. The scheduled job must commit every day. main is protected by a ruleset with nine required checks.

Decision. Option (a) of Section 8.2: the job commits to a separate `forward-log` branch with the default `GITHUB_TOKEN`, and never writes main. main holds the code, the settings and the plan; the branch holds `forward/inputs/`, `forward/ledger.jsonl` and `forward/reports/`.

Consequences. No ruleset bypass is needed. If a ruleset is ever added that covers every branch, the job's push will fail visibly. Readers have to switch branch to see the log; README links to it.

## DR-FWD-03: No back-fill; parameters frozen

Date: 2026-11-07

Context. A forward log is only worth its name if every line was written before its outcome existed.

Decision. Options 8.3 and 8.4: the first decision is written by the first scheduled run after the merge. The ten-day run on past data (PAPER_TRADING_PLAN.md Section 9) was a plumbing test in a temporary directory and is not committed to the log. The desk is `DeskFactory.deskDefault()` under `config/forward/desk.yaml`; a change to that file, the experiment config or the presets changes the hashes written into every decision line.

Consequences. The log starts empty. Any later change of desk parameters is visible as a change of hash on a dated line.

## DR-FWD-04: Each forward session starts flat on a flat intraday market

Date: 2026-11-07

Context. The back-tester runs one session from a flat book, and the synthetic generator makes up intraday rate paths. A forward log that kept the generator's paths would mix synthetic market moves into the P&L.

Decision. Every curve input of a forward session carries the committed mid of the previous close (no level, idiosyncratic, event or basis moves). The session's end-of-day book is marked once at the real close and then counts as closed; there is no multi-day inventory. Carry and funding accrue inside the session: declared bond coupons, swap carry at the committed SOFR and bond funding at the committed SOFR. Weekend and overnight carry are not booked.

Consequences. The only real outcome is the close mark. Informed clients carry no information on a flat market, so spread capture is an upper bound with no adverse selection, and it is much larger than the simulator audits found; README "Limits" says so. The flat market is a setting (`flat_intraday`), recorded by hash, if a later round wants to change it.

## DR-REV-01: Governance documents describe solo practice; the repository is public

Date: 2026-11-23

Context. The review of 2026-11-23 (docs/reviews/REVIEW_2026-11-23.md, findings E2, E3 and E9) found that CONTRIBUTING.md, docs/AI_USE_POLICY.md and the RTS 6 inventory described one approving review per pull request, an `ai-assisted` label and an extended AI change log, none of which was in use, and that STATUS.md still called the repository private under the DR-001 amendment. Checked with `gh api` on 2026-11-23: the repository is public; the `main` ruleset requires nine status checks and 0 approving reviews; pull requests #1 to #9 have no reviews.

Decision. The documents describe what happens: one engineer directs AI coding sessions and merges; the nine required checks are the only merge gate; assisted commits are identified by their `Co-Authored-By` trailer. The RTS 6 inventory uses the scale illustrated in simulation, document only, absent and out of scope, and claims no compliance. The DR-001 amendment that kept the repository private is superseded; the repository is public.

Consequences. Rows of the RTS 6 inventory that rested on review or approval are rated absent or document only. `tools/check_inventory.py` must accept the new status words. Adding an independent reviewer later would be a new decision record and would change these documents back with evidence.

## DR-RV-S01: The kill has two paths, a gateway-side halt that withdraws and a journaled message for replay

Date: 2026-11-23

Context. Review findings L1 and L2: the kill switch was an ordinary message behind market data on the loop thread, the loop most likely to be dead when a kill is needed, and `POST /control/kill` answered 200 whatever happened. DR-R2W-10 put the kill on the loop so that replay reproduces it.

Decision. This extends DR-R2W-10. `TradingProcess.kill` first sets a halt flag that the gateway thread reads on its next duty cycle; each `VenueSession` then cancels every quote and order it knows to be live from its own record (`LiveSet`), without the loop. The KillSwitch message is still offered to every group's inbound ring, so the journal and replay keep the pull; a full ring is retried by the supervisor and does not hold up the withdrawal. Only a confirmed withdrawal is reported as done; the endpoint answers 503 with the reason otherwise. A halt is final for the process: it is never cleared, and quoting again needs a restart.

Consequences. The kill no longer depends on the loop or on ring space. The journaled KillSwitch can arrive after the venues have already cancelled, which is harmless because the loop's pull is idempotent. A halted process cannot be re-armed from the control endpoint. The live record holds 8,192 quotes and 8,192 orders per session; past that the session logs out instead of confirming by acknowledgement.

## DR-RV-S02: A halted session drops loop outbound, cancels from its own record and escalates to logout

Date: 2026-11-23

Context. DR-RV-S01 needs a withdrawal that does not trust the loop's view and does not wait for ever on a venue that does not answer.

Decision. A halted session lets nothing from the loops' outbound rings out. It cancels from its own live set, repeats unanswered cancels every 250 ms for three rounds (`VenueSession.WithdrawPolicy.DEFAULT`), then logs out so the venue's cancel-on-disconnect applies, and closes the socket if the venue's Logout has not come within 1 s. A live set that overflowed logs out at once.

Consequences. The withdrawal depends on the venue honouring cancel-on-disconnect, as every venue simulator does (API_SPEC.md Section 11.1). A real venue's cancel-on-disconnect setting would have to be confirmed during onboarding.

## DR-RV-S03: Another thread may only close a session's socket, never drive it

Date: 2026-11-23

Context. The supervisor and the kill call run on other threads than the gateway thread that owns `VenueSession`, `FixSession` and `TcpInitiator`, which are single-threaded.

Decision. Other threads may read a session's halt state and unconfirmed count and may call `abort()`, which closes the socket. They never send a message or change session state.

Consequences. The session classes stay single-threaded and allocation-free. When the gateway thread is dead or stuck, the only remedy from outside is closing the sockets, which relies on cancel-on-disconnect.

## DR-RV-S04: Must-deliver outbound retries touch the ring only, never the journal

Date: 2026-11-23

Context. Review finding L2: an outbound message dropped on a full ring was counted and never sent again, which for a cancel leaves a quote or order live.

Decision. `EventLoop.Builder.retryOnDrop(int... templateIds)` names templates that are queued and offered again when the ring was full. The wired process names QuoteCancel, QuoteWithdraw, OrderCancel and OrderWithdraw. The message is journaled once, when the handler publishes it; the retry writes only to the ring.

Consequences. Replay is unchanged, because the outbound journal is the same with or without retries. A message the retry could not deliver is counted in `outboundRetryLost`.

## DR-RV-R01: Additive schema entries keep schema version 0

Date: 2026-11-23

Context. Review finding S3 needed OrderCancelReject (FIX 35=9) to reach the loop, and S2 needed a journaled reason for excluding a stale venue. The schema header version is written into every journaled frame, and the experiment ledger records journal hashes.

Decision. Template 45 `OrderCancelReject` and `VenueExclusion.Stale` (6) are added to `rates-schema-v1.xml` without a version bump, as in DR-P2V-02 and DR-P6-01. A bump was tried and made `FirstExperimentTest.ledgerRecordReproduces` fail.

Consequences. Every recorded frame decodes as before and every ledger row still reproduces. A reader of a pre-1.1.0 journal never meets the new entries.

## DR-RV-R02: A refused book delta is a gap

Date: 2026-11-23

Context. Review finding S1: a BookUpdate dropped on a full inbound ring was counted and never recovered, so a lost delete left a phantom level. DR-R2W-05 forwarded every frame, including the first after a gap.

Decision. Supersedes the forwarding rule of DR-R2W-05. The feed binding takes a snapshot before forwarding anything; it forwards a frame only if it is next in sequence and advances only when the ring took it. A gap on the wire and a refused frame are the same event: the binding publishes GatewayStatus DISCONNECTED for the feed, delivers a full snapshot, and publishes CONNECTED only when every snapshot frame was taken.

Consequences. A full ring now pulls quotes and stops routing to the CLOB until the rebuild completes, which is the safe direction. The feed has no heartbeat, so a silent feed is detected only by the routers' 10 s book-age limit.

## DR-RV-R03: Router timeouts are safety bounds outside the cost-model parameters

Date: 2026-11-23

Context. Review findings S3 and S4: a child stuck in CancelRequested never freed its quantity, nothing timed out an unacknowledged or IOC child, and a parent had no terminal failure state.

Decision. `RouterTimeouts` (ack, IOC and cancel waits of 2 s each, three cancels, twenty retries after the deadline, an optional book-age limit) sit outside `RouterParams` and the presets, because no allocation depends on them. A child with no answer after the last cancel is flagged unresolved; its quantity stays reserved and its parent ends Failed. A parent that cannot be placed after its retries ends Expired. `ParentListener` reports each terminal state once.

Consequences. The cost model, its presets and the decision journal are unchanged. Unresolved quantity needs an operator or a reconciliation to settle it.

## DR-RV-R04: Absence from mass status is not evidence of no fill

Date: 2026-11-23

Context. Review finding S8: reconcile closed a child the venue did not list as cancelled with nothing filled. A mass status lists live orders, so an order that filled while the session was down is absent too, and the router would hedge that quantity twice.

Decision. A child missing from mass status frees nothing. It keeps its reservation and is sent a cancel; the venue's answer for that order settles it, and with no answer it ends unresolved under DR-RV-R03.

Consequences. After a reconnect some quantity can stay reserved until the venue answers. That is preferred to a double hedge.

## DR-RV-R05: An IOC in a workup is cancelled, never held

Date: 2026-11-23

Context. Review finding S5: an immediate-or-cancel order could rest on the CLOB simulator during a workup.

Decision. An IOC is never held and never rests, in or out of a workup. Whatever it cannot trade at once is cancelled at once.

Consequences. The simulator matches the meaning of the time-in-force. Router IOC children now always end at once, which the IOC timeout of DR-RV-R03 relies on.

## DR-RV-P01: The fixture calendar follows SIFMA full closes only

Date: 2026-11-23

Context. Review finding A6: the holiday fixture made Good Friday 2026 a holiday while the analytics calendar made it a business day. SIFMA recommends a 12:00 early close for US government securities on 2026-04-03.

Decision. The US-GS fixture lists full closes only; an early close is a business day. The 2026-04-03 row is removed from US-GS (it stays on the UK calendar). `CalendarConsistencyTest` compares the calendars on every day of 2026 and 2027.

Consequences. Settlement and accrual treat early-close days as full days. Early-close hours are not modelled.

## DR-RV-P02: The dated date is reference data, not derived

Date: 2026-11-23

Context. Review finding A5: bonds accrued from the issue date, so on-the-run notes issued after a weekend month end had a short first coupon.

Decision. `bonds.csv` gains a `datedDate` column and `Bond.datedDate` is read from it, validated between announcement and issue. Accrual starts at the dated date. `DatedDateTest` checks accrued interest against QuantLib 1.44.

Consequences. A new bond row needs its dated date from the auction announcement. The on-the-run 2Y and 5Y fixtures are dated 2026-10-31 and the 10Y and 30Y 2026-11-15.

## DR-RV-P03: Curve quotes beyond a 100 bp band are rejected; rejects do not refresh staleness

Date: 2026-11-23

Context. Review finding A8: a failed curve solve left the builder truncated and stopped the loop, and nothing checked incoming quotes.

Decision. `AnalyticsPricing` rejects a pillar quote that moves more than `maxQuoteMove` (default 0.01, 100 bp) or whose rebuild fails, counts it in `rejectedInputs()` and keeps the last good curve. A rejected quote does not refresh the input's reference time, so the input ages as if the bad quotes had not arrived.

Consequences. A genuine jump above 100 bp between two updates would be refused; the band is far above any move between two updates and well below a unit or decimal-place error (3.75 percent sent as 37.5).

## DR-RV-P04: Bond risk at market is optional until pre-registered

Date: 2026-11-23

Context. Review finding A3: bond DV01 is taken at zero spread to the OIS curve, which overstates long-bond risk.

Decision. `AnalyticsPricing.Options.bondRiskAtMarket` takes bond ladders at the z-spread that reprices the market dirty price (`BondPricer.marketGradient`). It is off by default, because it changes every bond DV01 and with it every recorded simulation figure.

Consequences. The default still overstates long-bond DV01. On the lane's run of `PricingRobustnessTest`, which prints and does not assert the figures, the 30-year DV01 per 1mm fell by about 21% and the 10-year by about 6% at market. Turning it on belongs to a pre-registered run (review fix plan, round 4).

## DR-RV-Q01: A hedge needs a fresh mid and healthy inputs; a stalled parent stops counting

Date: 2026-11-23

Context. Review finding Q10: hedge parents were priced at arrival mid with no staleness check, the hedger ignored input health, and a working parent counted as hedged for ever.

Decision. `Hedger.Config.maxMidAgeNanos` (5 s) and the `HedgeInputGate` stop a parent being sent off a stale mid or unhealthy inputs. `parentTimeoutNanos` (60 s) cancels a parent still working and stops counting it toward the residual; its reservation stays until the router reports it done.

Consequences. Neither bound is reached on the smoke day. A venue that stops answering now shows as open risk after 60 s instead of looking hedged.

## DR-RV-Q02: The risk-reducing exemption covers daily loss and flatten-only, never the PV01 envelope

Date: 2026-11-23

Context. Review finding Q12: risk-reducing orders skipped the pillar, net and gross checks on the strength of lower parallel PV01 (DR-P3-05, DR-R2R-09), so a 30-year order could push 2-year risk further past its limit.

Decision. Every order passes the PV01 envelope, with basis and swap-spread inside it. A risk-reducing order skips only the daily-loss check, and still passes while flatten-only is in force after a daily-loss kill.

Consequences. A hedge that would breach a pillar limit is refused. `RiskEngineTest.riskReducingHedgesPassWhenTheBookIsInBreach` covers it: with a 50k pillar limit a 100mm 7-year future against 10-year cash is refused, 70mm passes.

## DR-RV-Q03: A mismatched Done is flagged and booked by default

Date: 2026-11-23

Context. Review finding Q7: there was no price check at acceptance.

Decision. A Done at a price other than our quote (beyond `acceptPriceToleranceBp`, default 0) is counted and reported. By default it is still booked at the venue's price, so the risk engine is not blind to a position the venue believes we have; `rejectMismatchedDone` turns booking off. A Done for zero quantity books nothing and teaches no win.

Consequences. A venue error shows up as a counted mismatch and a position, to be disputed, rather than as a silent difference between our books and the venue's.

## DR-RV-Q04: Client DV01 is gross: dealt today plus live quotes

Date: 2026-11-23

Context. Review finding Q9 asked for per-RFQ and per-client DV01 limits.

Decision. `RiskLimits.rfqDv01` bounds one quote's DV01 (`REJECT_RFQ_DV01`, -5). `clientDv01` bounds a client's DV01 dealt today plus its live quotes plus the new quote (`REJECT_CLIENT_DV01`, -6). Both default to unlimited. Package legs count toward the client limit.

Consequences. Netting between a client's buys and sells is not credited. There is no breach message for these limits, because the schema has no `LimitKind` for them; refusals are counted in `dv01Rejects()`.

## DR-RV-Q05: Back-test limits stay the default; a desk-sized preset is available

Date: 2026-11-23

Context. Review finding Q9: the default limits are sized so that the smoke day never breaches them; they are not controls.

Decision. The default limit preset stays the back-test one, so every recorded figure reproduces. `DeskSettings.deskSized()` sizes limits from a 40k USD largest RFQ DV01 and 45k expected daily income: daily loss 180k, RFQ 40k, client 200k, net 85k, pillar 60k, gross 340k, basis and swap spread 120k each, price collar 10 bp, fat finger 200mm, maximum order 250mm, position 500mm, firm quotes to 100mm.

Consequences. The desk-sized preset is not recommended yet: on the lane's run of the seeded day (`DeskSettingsTest`, printed, not asserted) it ended with net DV01 of about -15.7k against about -64 under the default, and that difference is not explained.

## DR-RV-Q06: A venue bust names an RFQ trade by its quote id

Date: 2026-11-23

Context. Review finding Q14: the risk engine reversed busts with no de-duplication, and the position keeper keyed RFQ trades differently from venue busts, so a bust of an RFQ trade stayed pending.

Decision. A venue's TradeBust carries our quote id as `clOrdId` for an RFQ trade; the position keeper applies it to that Rfq trade when no execution matches. On the loop a bust is applied once, and only for an execution the desk booked.

Consequences. Unknown and repeated busts are counted, not applied. A bust does not reduce the router parent's filled quantity.

## DR-RV-C01: The forward summary prints no mean-over-deviation ratio

Date: 2026-11-23

Context. Review Section 2.5: the forward report would print an annualised mean over standard deviation after 60 sessions on mostly synthetic, near-deterministic income.

Decision. The forward summary reports the real close mark alone with its mean and standard error, and labels session P&L synthetic. No ratio of mean to standard deviation is printed at any sample size.

Consequences. The forward log cannot be quoted as a Sharpe ratio. It still prints the synthetic cumulative figure and table, labelled as synthetic.

## DR-RV-C02: Looks are counted per data set, not per pre-registration id

Date: 2026-11-23

Context. Review Section 2.4: ten pre-registrations that each read the same sessions once all recorded look 1.

Decision. `DataSets` keys each ledger row by the data it read (dates and seeds, or a data hash) and counts looks per key. History is reported, never rewritten. A new run is refused unless its pre-registration lists the earlier ids under `prior_looks`; new rows carry `data_set`, `data_set_look` and `prior_looks`.

Consequences. As of 2026-11-23 three data sets were read more than once: 11, 9 and 2 looks. Results on those sets must be read with that number of looks in mind.

## DR-RV-C03: Paired methods for common-random-number designs; Benjamini-Hochberg across experiments

Date: 2026-11-23

Context. Review Section 2.4: the registered test was an unpaired Welch test on a paired design, and nothing corrected across the ledger.

Decision. `Stats.pairedT` and `pairedBlockBootstrapCi` (and Python twins) are the methods for designs where both arms run on the same sessions with the same random numbers. `benjaminiHochberg` is applied across experiments; Holm stays the rule within one pre-registered family. Existing verdicts stand as registered.

Consequences. New pre-registrations should name the paired test as primary. Old ledger rows are not re-scored.

## DR-RV-C04: Waits on the segment preparer are bounded by thread liveness

Date: 2026-11-23

Context. A journal roll could spin for ever when the preparing thread had died or hung, and rates-journal line coverage depended on thread timing.

Decision. A roll waits while the preparer is alive and making progress, under the 60 s progress rule of DR-INT-10, and fails otherwise. A failed `JournalWriter.open` or `create` releases its mapping, and a `RingJournal.close` that gave up can be called again. Timing-dependent branches are forced in tests by a gating clock (`JournalWaitPathsTest`).

Consequences. A dead preparer surfaces as a failure instead of a hang. Coverage of rates-journal no longer moves with scheduling.

## DR-RV-C05: Branch-coverage floor of 0.75 per module

Date: 2026-11-23

Context. Review finding E6: coverage was line coverage only.

Decision. JaCoCo enforces a branch-coverage minimum of 0.75 per module (`jacoco.minimum.branch` in the root pom), under the lowest module measured on 2026-11-23, rates-strategy at 0.783.

Consequences. The floor stops regressions; it does not show that the tests are good. Mutation testing (DR-RV-C06) is the stronger check.

## DR-RV-C06: Mutation testing is opt-in and non-gating

Date: 2026-11-23

Context. Review findings E6 and the round 7 plan asked for mutation testing on risk, quoting, journal and analytics.

Decision. PIT runs with `-Ppit` on rates-risk, rates-quoting, rates-journal and rates-analytics, and weekly in `.github/workflows/mutation.yml`, which is informational. It does not gate a merge.

Consequences. Mutation scores are reported, not enforced. Gating on them waits until the scores are measured on several runs.

## DR-RV-C07: Actions pinned by SHA; Python pinned by a constraints file without hashes

Date: 2026-11-23

Context. Review finding E7: Actions were referenced by tag and Python dependencies by lower bounds only.

Decision. Every workflow pins each action by commit SHA with the tag in a comment. `research/requirements-lock.txt` pins every Python dependency and is used as a pip constraints file by CI and the forward-log job; `research/pyproject.toml` keeps the supported ranges. Dependabot proposes grouped weekly updates; CodeQL scans Java and Python.

Consequences. The lock has no hashes, so it pins versions, not artefacts. Container images are still referenced by tag and there is no bill of materials.

## DR-RV-X01: State is restored only by replay through the live loop; divergence refuses to start

Date: 2026-11-23

Context. Review finding L6: the trading process always created new journals and threw if segments existed, so a crash left no way back but to start empty. A restart needs the loop's state as it was at the stop: positions, risk, live quotes, router children and hedge parents.

Decision. `LoopRecovery` replays each group's inbound journal through the loop that will go live, on the existing replay path (`Replayer`, `EventLoop.onReplayFrame`), and compares every message it produces with the recorded outbound journal. There is no snapshot and no second way of restoring state. A produced message that differs, recorded outbound frames that replay does not produce, or a journal ending in a LoopError make recovery throw, and the process does not start. Messages replay produces after the recorded outbound journal ends are appended to it and counted (`outboundCompleted`), not sent. `RecoveryClock` carries time on from the last replayed frame.

Consequences. A recovered loop is in the state the tests hold to an equal hash: `RestartRecoveryTest` compares the state digest and replay hash with those of the stopped process, and `LoopRecoveryTest` shows that a restarted loop's journals equal, frame by frame and by chain hash, those of a loop that never stopped. Start-up time grows with the journal's length. A build that does not reproduce its own journals cannot restart on them. A refused restart is not repaired automatically; the operator reconciles at the venues and starts empty (docs/runbooks/restart.md). The route journal is silent during replay, so a routing decision made at the crash instant can be missing from it.

## DR-RV-X02: A torn tail is a failed last frame followed only by zeros; anything else is corruption

Date: 2026-11-23

Context. A process that dies while appending leaves a partial frame. The writer publishes a frame's length last and segments are zero-filled, so a partial frame shows as a frame that fails its checks at the end of the data. Damage elsewhere in a journal can look the same to a reader that stops at the first bad frame.

Decision. `JournalRecovery.repair` cuts a failed frame only if every byte after it, to the end of its segment, is zero and no later segment holds a frame. A last segment shorter than its header declares is extended with zeros first, which turns a file cut short into an ordinary torn tail. A bad frame with data after it, a missing segment, or a header that is not a journal header is corruption: `JournalCorruptionException`, and the process does not start. A cut is reported in the `recovery: ` lines and counted (`journalTornTails`).

Consequences. A crash at any byte of the last frame opens cleanly at the previous frame (`JournalRecoveryTest.aFileCutAtEveryByteOfItsLastFrameOpensCleanlyAtThePreviousFrame`). Damage that happens to be followed only by zeros cannot be told from a torn tail and is cut; the chain hash still protects every frame before it.

## DR-RV-X03: Mapped sequence store; an unclean restart resumes from the journaled watermark; ExecID dedupe makes resends harmless

Date: 2026-11-23

Context. Review finding L6: `resetOnLogon` was always on, so fills a venue sent while the session was down were never resent. Resuming needs the sequence numbers to survive the process, and they change on every message on the session thread.

Decision. `MappedSequenceStore` keeps each session's numbers in one memory-mapped 4 KiB page with two slots, each with a generation and a CRC32C. A write goes to the slot the previous write did not use; opening takes the good slot with the higher generation. Nothing is forced to disk on the message path. The inbound number has a second watermark, moved up only when the loops have journaled what came before it; after an unclean stop the session resumes from that lower number, so anything received but perhaps not processed is asked for again. `DeskGroup` books a fill once per venue and ExecID. `fix.resetOnLogon` is configurable: on in the simulator preset, off in the deployment files.

Consequences. A resend after a crash books nothing twice (`RestartRecoveryTest.withSequenceNumbersResumedARestartBooksNothingTwice`, `VenueResumeTest`). The numbers survive a process crash, not a power loss or kernel crash; after one, a stale outbound number makes the venue refuse the Logon and an operator agrees a sequence reset. A file whose two slots both fail their checks is refused.

## DR-RV-X04: Our own messages are gap-filled, never resent

Date: 2026-11-23

Context. A resumed session can be asked by the venue to resend what we sent before a drop or a crash. DR-P2V-03 already answers every ResendRequest with a gap fill; resuming makes such requests routine rather than rare.

Decision. DR-P2V-03 stands for resumed sessions. A ResendRequest is answered with one SequenceReset-GapFill to our next number. A quote or order is never resent: the loop decides afresh once the venue is reconciled.

Consequences. A stale quote or order never reaches a venue late (`SessionRecoveryTest.ourOwnMessagesAreGapFilledNeverResent`). What we had working at the venue is learned from the mass status after logon (DR-RV-X05), not from our own resend.

## DR-RV-X05: RECONCILING and RECONCILED are journaled inputs

Date: 2026-11-23

Context. After a drop or a restart the CLOB may still hold, or may have filled, orders the loop believes in. DR-RV-R04 says absence from mass status is not evidence of no fill. The loop also must not route to the venue before the answers are in.

Decision. A returning CLOB session publishes GatewayStatus RECONCILING (4), sends an OrderMassStatusRequest, passes the answers to the loop and then publishes RECONCILED (5). Both go on the inbound ring, so the loop journals them and replay sees them. The loop keeps the venue down, and its quotes withdrawn, until RECONCILED. An unanswered request is repeated twice, 5 s apart; then the connection is dropped and made again. A dropped session reconnects under `fix.reconnect`, up to `maxAttempts`; a halted one never does.

Consequences. Replay reproduces when the venue became usable again. Readiness and the status JSON say `reconciling` while it lasts. Reconciliation with resting orders is tested at `DeskGroup` level (`DeskGroupTest.aReturningClobSessionStaysDownUntilItsOrdersAreReconciled`) and against a venue that never answers (`VenueSessionReconnectTest`), not against a venue holding our orders across a process restart.

## DR-RV-X06: SSLEngine allocation on the session path is accepted and measured

Date: 2026-11-23

Context. Review finding L12: FIX had no TLS or Logon credentials. The hot path is allocation-free by rule (DR-002), and the JDK's `SSLEngine` allocates inside `wrap` and `unwrap`.

Decision. `TcpInitiator` uses the JDK `SSLEngine`, TLS 1.3 only by default, with host-name and certificate checks, a handshake deadline and bounded writes; a plain connection is allowed to loopback only. Our own code reuses its buffers and allocates nothing. The engine's allocation is accepted on the session path and measured by `TlsAllocationTest`: about 1.3 KB per message sent and 1.4 KB received on JDK 25 with heap buffers (direct buffers measured higher), held under a 2 KB ceiling so that a regression fails the build. The plain path stays at zero.

Consequences. Under TLS the gateway thread makes short-lived garbage of constant size per record, which a young collection clears. A link that must be allocation-free end to end needs TLS ended outside the JVM, on loopback. The loop thread is unaffected. The TLS KeyUpdate path is untested.

## Records of release 1.2.0

Rounds 3 to 7 of the review's plan (docs/reviews/REVIEW_2026-11-23.md Section 9) and the v1.2 integration. Several records were written by the rounds into their design documents first; the source is named in each. Common to all of them: the default desk is unchanged, and every v2 component is opt-in until a registered experiment says otherwise.

## DR-R3-01: One market model, in its own module, for the venue simulators and the back-test

Date: 2026-11-23

Context. Review Sections 2.1 and 2.2: every simulated P&L figure followed from hand-declared constants, instruments moved as independent walks, and the venue simulators and the back-tester each had their own idea of the market. A result from one could not be checked against the other.

Decision. A new module, `rates-sim`, holds the one market model (`MarketModel`, `RfqFlow`, `BookModel`, `SessionSeeds`), specified in docs/research/SIM_V2_DESIGN.md and calibrated in docs/research/SIM_V2_CALIBRATION.md. The venue simulators (`SimV2Session`, `Venues.simV2`) and the back-test (`SimDay`, `SyntheticDay.generateSimV2`, `BacktestConfig.forSimV2`) both read it. `SplitMix64` moves to `io.ratesalgo.common.random` so that both sides draw from the same generator.

Consequences. A session is a function of its seed wherever it runs; acceptance test A7 compares the back-test and the venues against the shared model. Callers of `SplitMix64` change one import. The module adds a dependency to rates-backtest and rates-venues.

## DR-R3-02: No simulation constant in code; parameters load strictly

Date: 2026-11-23

Context. The old simulator's adverse-selection constants were declared in code and presets, and the calibration finds them outside what the public data supports.

Decision. Every market parameter comes from `data/calibration/sim_v2_params.json`, each marked fitted, bounded or assumption with its band. The loader refuses a missing, unknown or duplicate key, a unit that does not match, loadings not of unit length, shares that do not sum to one, and an informed fraction and move whose product is not the calibrated adverse move (SIM_V2_DESIGN.md Section 14 D2). Structure that is not a parameter lives in `sim_v2_structure.json`.

Consequences. A parameter cannot change without a visible change to a committed file with a hash; a pre-registration names that hash. Constants the parameter file does not yet carry are listed in D1 of the design's Section 14 as text constants.

## DR-R3-03: Simulator version 1 stays the default until a registered run

Date: 2026-11-23

Context. Every recorded figure, recipe and ledger reproduction test depends on simulator version 1.

Decision. `SyntheticDay.generate`, `BacktestConfig.fromPresets` and the version 1 venue configurations are unchanged. Simulator v2 is reached through its own entry points and presets (D17 of the design).

Consequences. No recorded figure moved. The old simulator's figures stay as records; docs/research/PNL_AUDIT.md says they are superseded for new work.

## DR-R3-04: The desk's own impact is a law of total volume, not of each slice

Date: 2026-11-23

Context. Section 8 of the design applied I = Y sigma sqrt(Q / V) to every marketable order. Read literally, twenty 2.5 mm slices leave sqrt(20) = 4.5 times the permanent impact of one 50 mm order, which contradicts acceptance test A9 and rewards slicing for no reason.

Decision. Own permanent impact is a function of the total volume the desk has traded, over the day and recently, not of each slice (`BookModel`; D7 of the design's Section 14).

Consequences. Slicing an order no longer lowers its permanent impact below that of the whole. The decay of the recent component is an assumption with a band. A restarted market forgets the desk's earlier impact (DR-V12-P01).

## DR-R3-05: The two Treasury venues show shares of one book

Date: 2026-11-23

Context. The design has one book per instrument; the desk needs two order-book venues to route between.

Decision. The two Treasury venues show 0.6 and 0.4 of the model's provider size at each level (`treasury_venue_shares`, an assumption) and receive the same shares of each other taker's trade, in whole lots. Depth taken on one is gone from both (D10).

Consequences. Routing between them changes queue position and fees, not the price of the depth. One `BookModel` is shared by two venues, which needed one owner on threaded runs (DR-V12-P02).

## DR-R3-06: Workup is off under simulator v2

Date: 2026-11-23

Context. With 0.13 taker trades a second and a 5 s workup the book would be in workup almost always, and immediate-or-cancel takers would be cancelled in every exclusive window.

Decision. The `sim_v2` order-book preset turns workup off; `ClobBook` itself is unchanged (D11).

Consequences. The v2 venues do not test the workup path; version 1 still does.

## DR-R3-07: Test tolerances are the design's; sample sizes are raised for a 99 percent pass rate

Date: 2026-11-23

Context. Some acceptance tests of the design's Section 11 would fail a correct implementation more often than one run in a hundred at 400 sessions.

Decision. Tolerances stay as written. Session counts are raised where needed: second moments use 1,000 sessions, so that the tolerance is 3.6 standard errors (D15).

Consequences. The acceptance tests are slower. A tolerance is never widened to make a test pass.

## DR-R4-01: Pricing v2 is selectable and version 1 stays the default

Date: 2026-11-23

Context. Every recorded figure depends on version 1. Source: docs/research/PRICING_V2.md Section 14.

Decision. `PricingBindings.create(version, ...)`; `AnalyticsPricing` gains only `implements PricingBinding`. The default changes with simulator v2 in a pre-registered step.

Consequences. Pricing v2 runs only in a desk profile that selects it (DR-V12-D01).

## DR-R4-02: Step-then-tension-spline, not monotone convex

Date: 2026-11-23

Context. `SofrCurveTest.interpolationEvidence`. Monotone convex has continuous forwards, but its ladders differ from a 1 bp bump by 4.3 percent of parallel DV01, and a first-order leave-one-out value drifts 0.30 bp in a 10 bp move, because its regions switch within a basis point.

Decision. Tension spline at 0.5 per year after the meeting steps; monotone convex stays selectable.

Consequences. The spline is not local and can overshoot: a swap between pillars shows small risk of the other sign on neighbouring tenors (PRICING_V2.md Section 7).

## DR-R4-03: Fair value is leave-one-out plus a half-life residual

Date: 2026-11-23

Context. Review finding A1: a quoted swap that is a curve input was its own fair value.

Decision. PRICING_V2.md Section 5; half-life 300 s; an exact reference every 5 bp of input move. A single skewed quote moves its own fair value by 1 - 2^(-dt / 300 s) of the skew.

Consequences. A persistent skew is absorbed with a 5 minute half-life: accepted, and stated.

## DR-R4-04: The Treasury curve is a fitted z-spread to SOFR on a Nelson-Siegel basis

Date: 2026-11-23

Context. Four to ten bonds are not enough to fit a yield curve of their own.

Decision. PRICING_V2.md Section 6.

Consequences. With four bonds, the universe the process trades, the on-the-run premium is not identified and at most one bad book is found.

## DR-R4-05: Two risk factors per tenor; the swap-rate ladder by Jacobian

Date: 2026-11-23

Context. Review findings A3 and A4.

Decision. PRICING_V2.md Section 7. `Dv01Vector` and `KeyRateTenors` are unchanged; `SpreadSensitivityProvider` is added beside `SensitivityProvider`.

Consequences. A swap hedged with a Treasury shows its whole risk on the spread ladder. Nothing limits, quotes or hedges on that ladder yet (DR-V12-D11).

## DR-R4-06: In-arrears Hull-White convexity for SOFR futures; parameters remain assumptions

Date: 2026-11-23

Context. DR-P1-05 used Hull's forward-looking formula.

Decision. PRICING_V2.md Section 4. `HullWhite.futuresConvexity` and the first golden file are unchanged.

Consequences. a = 0.03 and sigma = 0.0085 are assumptions; the repository holds no option or futures prices to fit them.

## DR-R4-07: Reprice on the loop, conflated, as a pure function of journaled inputs

Date: 2026-11-23

Context. Replay determinism; no new message template.

Decision. PRICING_V2.md Section 10. Revisit if the measured cost of a round becomes a problem for the loop's latency budget.

Consequences. A reprice is several hundred microseconds on the loop thread, and an RFQ that arrives during one waits for it.

*Annotation 2026-11-24: the binding as merged still read the clock at construction, so its state was not yet a pure function of its inputs. DR-V12-P05 removes that; DR-V12-P06 separates recording an input from repricing.*

## DR-R4-08: Business date from the clock with a 17:00 New York cut

Date: 2026-11-23

Context. Review finding A7.

Decision. PRICING_V2.md Section 9. The instrument universe is still built on `Venues.TRADE_DATE`.

Consequences. Settlement and accrued interest roll with the date; the list of on-the-run issues does not.

*Annotation 2026-11-24: the date is fixed by the binding's first timed call, not read at construction (DR-V12-P05).*

## DR-R4-09: Newton before Levenberg-Marquardt in the global bootstrap; tolerance in rate

Date: 2026-11-23

Context. On the v2 curve the damped solver stalled at the rounding floor of futures prices: milliseconds per update and occasional failed residual checks.

Decision. PRICING_V2.md Section 10. The tolerance of 1e-12 now applies to the error in rate, so a futures price may be off by 1e-10.

Consequences. Sequential solves, and so version 1 pricing, are bit for bit unchanged.

## DR-R5-01: Strategy capabilities sit behind switches, all off by default

Date: 2026-11-23

Context. Review findings Q1 to Q8. Each capability changes recorded figures, and their effects interact.

Decision. One switch per capability (`DeskSettings.StrategySwitches`, carried by `DeskSettings`; `Hedger.Config` for internalisation). `DeskSettings.defaults()` quotes and hedges exactly as before; `DeskSettings.strategyV2()` turns everything on. docs/research/STRATEGY_V2.md.

Consequences. Each capability can be run alone or left out, which the family R5-FAM-001 uses for its ablations. Nothing is adopted without a registered result.

## DR-R5-02: Never quote below break-even; widen rather than pass; bounds from the cover

Date: 2026-11-23

Context. Q1: the fixed clamp of 0.02 to 0.08 bp sits below every client's break-even on the calibrated market (STRATEGY_V2.md Section 1).

Decision. The quote is the distance above break-even that maximises expected profit given the predicted best competing quote (`CoverModel`, learned from covers and censored outcomes). The search runs from break-even to a bound set by the cover distribution, never a fixed clamp. When cost rises the quote widens; nothing passes on cost.

Consequences. The desk wins less from informed clients. If the cover prior is far from the simulator's competitors and learning is slow, it quotes in the wrong place for many sessions.

## DR-R5-03: Inventory cost is capped at the planner's hedge cost; the axe is not added on this path

Date: 2026-11-23

Context. Q4: skew and size terms saturated far below a desk's inventory, and the default path counted inventory twice (skew and hedger axe).

Decision. One reservation term from the whole book including working hedges, kappa (Delta' Sigma p + Delta' Sigma Delta / 2), clamped at the cost of the hedge planner's mix for the trade (STRATEGY_V2.md Section 3). kappa = 4e-7 in the preset, from the hedger's trigger.

Consequences. The desk never pays more through its price than hedging would cost. If the planner's cost understates real costs, the cap is too low.

## DR-R5-04: Level is always hedged; slope when it pays

Date: 2026-11-23

Context. Q2: the assumed correlation decay gave 10s30s about 0.30; the fitted factors give 0.93.

Decision. `FactorModel` from the calibration's three factors; `FactorHedgePlanner` zeroes level with one instrument and adds a second for slope only when the variance removed is worth the second crossing, at per-instrument tick costs (`TickHedgeCostModel`). Futures carry basis variance and are used only when a futures book is available.

Consequences. A level-only hedge of 30-year risk with the 10-year note leaves 13 percent of its variance (STRATEGY_V2.md Section 4). At declared costs the planner never chose the future on the v2 smoke day (DR-V12-D10).

## DR-R5-05: Resting hedges do not count as hedged; the time stop sits inside the stall timeout

Date: 2026-11-23

Context. Design change 5 of the review: internalise inside a variance budget and hedge passively first.

Decision. Below the trigger nothing is done; between trigger and budget the residual is held and, with `passiveFirst`, the hedge rests at the touch; at the budget or after the time stop (45 s in the preset) resting orders are withdrawn and the hedge crosses. Resting orders count neither in the residual nor in the reservation price. DR-RV-Q01's freshness and timeout rules still apply; the time stop is inside its 60 s timeout.

Consequences. Whether resting hedges fill often enough, and how adversely, is a question for the registered run (H5 of R5-FAM-001). The budget and time stop are declared, not derived.

## DR-R5-06: Client markouts shrink to tier priors; the store is written at session end

Date: 2026-11-23

Context. Q6: one client tier was wired and the toxicity table never learned.

Decision. `MarkoutStore` keeps each client's markouts by side at 1 s, 5 s, 30 s, 60 s and 5 min, shrunk to a tier prior with n0 = 20. It is written at the end of a session (temporary file, atomic move, the same store gives the same bytes) and read at the next start.

Consequences. A client's own history takes half the weight only after 20 fills, deliberately slow. Replay needs the store the session read, which led to DR-V12-D07.

## DR-R6-R01: The ladder model is opt-in; the touch model stays for the ledger

Date: 2026-11-23

Context. Review Section 5 and finding S6: the router priced the touch half-spread while a child could walk several levels, with an 8-tick collar.

Decision. `ExecutionModel` and `LadderAllocator`: a consolidated, fee-adjusted sweep in which each aggressive child is limited at the last level it needs, a risk charge on the time an order stays open, a 2-tick collar as a safety bound (`RouterPresets.ladder()`). A router built without an `ExecutionModel` runs the touch model of `RouterPresets.global()` unchanged.

Consequences. The ledger's recorded runs stay reproducible. Ladder decisions are journaled with their inputs (templates 83 and 84).

## DR-R6-R02: Queue and fill rates are learned; simulator parameters are never passed in

Date: 2026-11-23

Context. Review Section 5.1: the old benchmark gave the router's queue model the simulator's own generating parameters.

Decision. Cancellation and market-order rates are learned from trade prints and from touch decrements no print explains, starting from a declared prior (0.2 per second); the passive fill probability is calibrated from the router's own outcomes per venue and queue bucket (`FillCalibration`). No simulator parameter reaches the router.

Consequences. A fresh router knows only its prior. A trade print lost on a full ring makes the router read the size decrement as cancellations.

## DR-R6-R03: A venue scorecard with throttles and outage exclusion

Date: 2026-11-23

Context. The review's fix direction: a venue scorecard (rejects, fill rate, markout, latency) feeding the router.

Decision. `VenueScorecard` keeps decaying averages of reject and cancel-reject rates, IOC fill ratio, markouts at 1, 5 and 30 s, acknowledgement latency and print rates, each applied from the event's own clock so the state is a function of what the router was told. It holds a token bucket and an order-to-trade ratio per venue, and excludes a venue that rejects or ignores orders in a row for a cool-down that doubles on each repeat (`VenueExclusion` 7 Throttled, 8 Outage).

Consequences. Penalties are declared assumptions, not fits. The values a decision used are journaled with it.

## DR-R6-R04: Child timing gaps are gamma-distributed

Date: 2026-11-23

Context. A uniform jitter of plus or minus 25 percent keeps slices near a fixed period that another participant can detect.

Decision. Slice gaps are drawn from a gamma distribution with the schedule's mean and a coefficient of variation near 1 / sqrt(2), clamped (`ChildRandomiser`, `ChildRandomiserTest.gammaGapsHaveNoDetectablePeriod`).

Consequences. Same seed, same gaps: replay is unaffected.

## DR-R6-R05: A benchmark scenario fixed before the run; the declared check and its failure are recorded

Date: 2026-11-23

Context. Review Section 5.1: the 2.94 bp row compared the router with orders fired whole into thin touch depth.

Decision. Scenario `ust10y_impact_v1` (`RouterBenchmarkScenario`, `ImpactMarket`) was fixed in a commit before the comparison, with real baselines: a sweep on arrival, the router's schedule on the best venue, TWAP, and passive with a deadline. It was run once. Declared before the run: the router's risk-adjusted cost (mean + 0.5 sd) no worse than any of them by more than 0.25 ticks. docs/research/ROUTER_BENCHMARK.md is generated by `RouterBenchmarkTest`.

Consequences. The check held against three baselines and failed against passive-with-deadline (router worse by 0.40 ticks, 95 percent [0.2, 0.5]). The scenario was not changed. The ladder router is not distinguishable from the touch router in this simulator. The result is a statement about the router's own simulator, not a market finding (DR-008), and replaces the 2.94 bp row.

## DR-R6-R06: Schema additions for routing are additive

Date: 2026-11-23

Context. The ladder model needs trade prints and a journal of its inputs; the schema is pinned (API_SPEC.md).

Decision. New templates 4 `TradePrint`, 83 `RouteDecisionLadder` and 84 `RouteDecisionModel`, and `VenueExclusion` values 7 and 8. No existing field changes; the schema version is unchanged.

Consequences. Old journals decode as before. A reader that does not know the new templates skips them by template id.

## DR-R6-L01: Core affinity through the foreign function API

Date: 2026-11-23

Context. Review finding L3: no thread was ever pinned.

Decision. `CoreAffinity` calls `sched_setaffinity` through the foreign function API on Linux; elsewhere it reports that pinning is not available. A CPU is given per thread in the `runtime` section.

Consequences. No native library is shipped; the process needs `--enable-native-access`. Pinning is measured only off Linux so far, where it is refused.

## DR-R6-L02: One thread per FIX session and feed; two runtime profiles

Date: 2026-11-23

Context. L3: one gateway thread served both sessions, parking 200 us, and the deployed threading was not what was benchmarked.

Decision. Each FIX session and each feed has its own thread on `DutyCycleRunner`, with its own idle strategy and CPU. `runtime.profile` names a set of values: `default` (parking as before, nothing pinned, direct journals, FNV-1a) or `lowLatency` (loop, session, feed and journal threads busy-spin, ring journals with WORD64, locked segments, curve inputs conflated). Individual keys override the profile. `deploy/config/trading-lowlatency.yaml` is an example.

Consequences. The default profile has more threads but the same idle behaviour. A busy-spinning thread belongs on a reserved core; the profile does not reserve one.

## DR-R6-L03: The chain hash is versioned in the segment header; FNV-1a stays the default

Date: 2026-11-23

Context. L8: byte-at-a-time FNV-1a costs tens of nanoseconds per frame on the loop. The review suggested CRC32C.

Decision. Format version 1 is FNV-1a, byte for byte as before, and is what a journal is written with by default. Format version 2 names its hash at header offset 20; WORD64 hashes eight bytes a step. Readers verify either and refuse a mix or a version-2 file naming FNV-1a. CRC32C is rejected because it keeps 32 bits and the chain keeps 64.

Consequences. Recorded journals and replay hashes are unchanged. WORD64 measured 4.8 to 7.8 times faster than FNV-1a on a loaded laptop (docs/BENCHMARKS.md). The Python readers follow the same rules (`ratesresearch.fnv`, `tools/journal_query.py`). The hash detects damage; it is not cryptographic.

## DR-R6-L04: Ring journal back-pressure is bounded and crash-ordered; DIRECT stays the default

Date: 2026-11-23

Context. A full ring made the producer spin for ever, and so did the segment preparer's retire wait when the journal thread had died. Review finding L9: pretouch does not hold on a shared mapping.

Decision. A full-ring wait fails after a stall limit without progress, or when the journal thread has died; the backlog high-water mark is reported. The preparer's retire wait is bounded the same way and keeps the window ahead of the appender writable. Frames reach the file in order, so a ring-mode crash image restarts like a direct one (`RestartRecoveryTest`). Segments can be locked in memory on Linux. `journalMode: direct` stays the default.

Consequences. A stalled journal halts the process instead of hanging it. Whether the write-protect fault of L9 is gone is not measured.

## DR-R6-L05: Curve-input conflation is opt-in

Date: 2026-11-23

Context. Review finding L10: a curve rebuild runs inline on the loop, ahead of RFQs and the kill.

Decision. `EventLoop.Builder.conflate`: a level input is held back per key, the rest of the ring is dispatched first, and at most a set number of held inputs per duty cycle are dispatched. A held input is journaled when dispatched; one replaced by a newer is counted and never journaled.

Consequences. The inbound journal holds exactly what the handlers saw, so replay is exact. In the benchmark at 1,000 requests a second conflation replaced 10 inputs in one run and none in the others.

## DR-R6-L06: JVM flags justified one by one; ZGC kept

Date: 2026-11-23

Context. L3: the deployment used ZGC at 512 MB while benchmarks used the Parallel collector at 1 GB.

Decision. Each flag in `deploy/trading/Dockerfile` is justified in a comment, and the wired-desk benchmark runs with exactly those flags (a test keeps the two lists equal). ZGC stays: its pauses in the benchmark were at most 0.47 ms, and a Parallel young collection would stop the loop under TLS every few seconds.

Consequences. ZGC against Parallel on the TLS configurations on a quiet Linux host is still to be measured.

## DR-R6-L07: Allocation tests also run without escape analysis

Date: 2026-11-23

Context. L13: allocation tests ran only at C2 steady state, so a deoptimised path could allocate unseen.

Decision. `NoEscapeAnalysisAllocationTest` in rates-core and rates-app runs the allocation checks in a child JVM with `-XX:-DoEscapeAnalysis`.

Consequences. The 1,024-byte tolerance is not tightened in this round.

## DR-R6-L08: The FIX writer lends its buffer to the transport

Date: 2026-11-23

Context. L11: the FIX send path copied the body twice.

Decision. `FixWriter` writes the header in front of the body in place and the plain transport sends from the writer's buffer (`FixWriter.buffer()`). The date part of the three timestamps is cached.

Consequences. The buffer is valid only until the next message, which SpotBugs is told (`config/spotbugs-exclude.xml`). The JMH run on a loaded laptop could not tell the old and new encoders apart.

## DR-V12-B01: Each session's journal is written once; every arm gets a fresh model of the seed

Date: 2026-11-23

Context. A paired comparison needs every arm to see the same exogenous session, while each arm's own orders change the market it trades in.

Decision. For each session and row the harness writes one journal from the seed. Every arm replays that journal against its own fresh `MarketModel` of the same seed (`SessionExecutor`), so the exogenous path, the RFQs, the competitors' quotes and the clients' reserves are identical and only the arm's actions and their impact differ.

Consequences. Common random numbers hold across arms by construction. A changed journal fails the session (`FamilyHarnessTest`).

## DR-V12-B02: Under simulator v2 the strategy sees the live model's books

Date: 2026-11-23

Context. A journal's book is written with no desk in it. A desk that saw it kept re-sending IOC children at prices its own orders had moved, without end.

Decision. In simulator v2 the back-tester publishes the live model's top every step and after each of the desk's orders, with the desk's own impact; the journal's book frames are not passed on (commit 16b4bd5).

Consequences. The desk's curve still comes from the published swap rates of the journal, which carry no own impact (D18 of the design).

## DR-V12-B03: Experiment sessions publish every second

Date: 2026-11-23

Context. Reference prices every 5 s against a 3 s heartbeat timeout made the desk read every input as unhealthy and pass every RFQ.

Decision. Experiment sessions publish every grid step (preset `sim_v2_publication`); version 1 publication is untouched.

Consequences. Larger journals per session. A v2 pilot run passes RFQs only for its own reasons.

## DR-V12-B04: Benjamini-Hochberg is computed only by command

Date: 2026-11-23

Context. DR-RV-C03 adopted Benjamini-Hochberg across experiments. Computing it on every ledger append would make an earlier row's adjusted value change silently.

Decision. `LedgerCli --bh family:ID` (and `python -m ratesresearch.prereg bh`) prints the table from the ledger on request. A family run prints it once when it appends its rows. The ledger stores raw p-values only.

Consequences. The adjusted values are reproducible from the rows at any time and are never stored.

## DR-V12-B05: A dated pre-registration stays on the version 1 rules

Date: 2026-11-23

Context. Pre-registrations written before version 2 are dated by session date; version 2 uses session-seed designs. A file that claims version 2 but keeps date keys would mix the rules.

Decision. A pre-registration keyed by dates is validated under version 1 whatever version it claims, and the date-key problem is reported for a file that claims version 2 (commit 384df09; the Python twin mirrors it).

Consequences. `LedgerTest`'s version 1 refusal is unchanged.

## DR-V12-D01: A desk profile selects each component; the recorded profile stays the default

Date: 2026-11-23

Context. Four components were built off by default and every recorded figure depends on version 1 of each. Source: docs/research/DESK_V2_WIRING.md Section 11.

Decision. `DeskProfile` with `defaults()` and `v2()`; `DeskLoopFactory(universe, settings, journal)` builds the recorded profile. The default changes in a pre-registered step.

Consequences. Each component can also be switched alone. The live loop and its replay must be built from equal profiles.

## DR-V12-D02: Per-venue state in the desk; ids 103 and 104, gateways 4 and 5

Date: 2026-11-23

Context. Gateway ids and feed status ids share one id space, and 3 is the first CLOB's feed.

Decision. DESK_V2_WIRING.md Section 2. A v2 loop has six outbound rings with ring 3 unused.

Consequences. Kill, gap, staleness, cancel-reject, overfill and reconciliation are per venue; a venue that is down is excluded and the others keep trading.

## DR-V12-D03: The desk counts a future in face, the venue in contracts; the loop converts where quantities cross it

Date: 2026-11-23

Context. API_SPEC.md Section 0 fixes face in minor units; the futures venue's FIX quantities are contracts.

Decision. DESK_V2_WIRING.md Section 2: `LoopBook`, `LoopOrderGateway` and `DeskGroup` convert where a quantity enters or leaves the loop.

Consequences. The journals and the post-trade keeper are in venue units; the risk engine, the hedger and the routers in face.

## DR-V12-D04: A trade print is not part of the delta stream

Date: 2026-11-23

Context. DR-RV-R02 makes a refused book delta a gap.

Decision. Prints have their own sequence, are forwarded while the book is whole, and a lost one is counted (`printsDropped`), never a gap.

Consequences. The version 1 venue publishes none, so its recorded feed is unchanged.

## DR-V12-D05: The simulated front end follows the two-year node; the overnight rate is constant in a session

Date: 2026-11-23

Context. The market model has no instrument shorter than two years; pricing v2 needs a fixing, five futures and a 1-year swap.

Decision. DESK_V2_WIRING.md Section 3; assumptions declared in `SimV2CurveRound`.

Consequences. Pricing v2's front end on the v2 market carries no independent information.

## DR-V12-D06: A Treasury future is priced off its book and risked at the model's declared sensitivity

Date: 2026-11-23

Context. The hedge planner needs TY as an instrument; a cheapest-to-deliver calculation was not in scope.

Decision. `FuturesOverlay`: fair value is the futures book mid; rate risk is the contract's declared price change per basis point at its node.

Consequences. The futures basis is not a risk factor of the desk.

## DR-V12-D07: The markout store is loaded from one file and saved to another

Date: 2026-11-23

Context. A replay rebuilds the desk, which reads the store again; a session that saved over the file it loaded would replay from another store.

Decision. `markouts.start` is read at build and never written during a session; `markouts.end` is written at the end; the next fresh start promotes end to start. `Desk.saveMarkouts`, which writes the file it loaded, is not used by the v2 process.

Consequences. A restart does not promote, so the rebuilt desk reads the store the stopped process read (DESK_V2_WIRING.md Section 12.1).

## DR-V12-D08: The loop timer flushes a held curve round after its conflation time, not on every tick

Date: 2026-11-23

Context. A timer calling `flush` on every tick applied a round in pieces whenever it straddled a tick.

Decision. `DeskGroup` records when a round's first input arrived on the loop clock and flushes 100 ms later.

Consequences. Superseded by DR-V12-P06, which makes the binding itself apply a round whole; the workaround was removed on 2026-11-24.

## DR-V12-D09: The first snapshot of a non-empty book is announced

Date: 2026-11-23

Context. `FeedBinding.rebuild` delivered the first snapshot of a book that already had levels without DISCONNECTED, which the loop read as a gap at every start against a venue already trading.

Decision. Supersedes nothing in DR-RV-R02; it applies that rule to the first snapshot. A DISCONNECTED before any frame is not counted as a gap.

Consequences. The default profile's journals are unchanged, because its venue starts empty.

## DR-V12-D10: The smoke day asserts hedges on both CLOBs; the future is exercised by an outage

Date: 2026-11-23

Context. At declared costs the factor hedge planner never chose TY on the v2 smoke day: half a 1/64 tick of TY is 0.109 bp of its DV01 against 0.048 bp for the 10-year note.

Decision. No parameter was changed to make the planner choose TY. `SmokeV2Test` exercises the futures path with a Treasury feed outage.

Consequences. Whether TY should be a normal-day hedge is a question for a registered run.

## DR-V12-D11: Spread risk follows the risk engine's positions and is displayed only

Date: 2026-11-23

Context. Fills are booked inside rates-quoting and rates-risk.

Decision. `SpreadRiskBook` is brought to the risk engine's positions after every message that can fill. Nothing limits, quotes or hedges on it.

Consequences. A desk long swaps and short Treasuries carries its swap-spread risk unhedged. A limit or hedge needs an input the hedger does not have.

## DR-V12-D12: Pricing v2 is built as of the last New York cut

Date: 2026-11-23

Context. Replay and restart build the desk at another instant than the live process did, and the binding stamped its start-of-day marks with the construction clock.

Decision. `PricingBindings.create` passed the binding a clock standing at the last cut.

Consequences. Superseded by DR-V12-P05 the same day: the binding no longer reads a clock at construction.

## DR-V12-P01: The trading process runs desk profiles; the selection is recorded beside the journals

Date: 2026-11-23

Context. The v2 desk ran stepped in `V2DeskProcess` only; `TradingProcess` could not run it, and a replay must be built from the profile that wrote the journals.

Decision. `TradingProcess.Options` carries a `DeskProfile`. The `desk` config section names it (`profile: default|v2`, then `market`, `pricing`, `strategy`, `router` on top; `ProcessConfig.DeskSelection`). A selection other than the recorded desk is written to `desk.selection` in the journal directory before anything is journaled; a start on journals another selection wrote is refused; `--replay` follows the file. On the v2 market the process runs four FIX sessions, three feeds and `CurveFeedV2`, and starts at 09:00 New York. A restarted v2 market is the seed's market taken alone to the restart time.

Consequences. The recorded profile is built exactly as before. A restarted v2 market forgets the desk's earlier impact and the venues' trade registers start again. Only the two whole profiles are run by tests at process level; per-component selections are built and started but not run.

## DR-V12-P02: One owner for the simulator v2 market model

Date: 2026-11-23

Context. The four v2 venues share one `MarketModel`, and the two Treasury venues each `BookModel`. Each venue handled the desk's messages on its own FIX thread under its own lock, so two threads could be inside one `BookModel` at once (DESK_V2_WIRING.md Section 8).

Decision. The venues of a session share a `SimSequencer`. A FIX thread only puts a message in its venue's inbox; the thread that moves the venues' clock drains the inboxes venue by venue (D2C, CLOB 1, CLOB 2, futures), each first in first out; a second draining thread is refused. The stepped process settles each session before the next (`SteppedDelivery`). Partitioning the model was rejected, because one book on two venues is the design; one lock across venues was rejected, because it leaves the order to whichever thread wins.

Consequences. What a venue does is a function of the sequence of (time, venue, message) its owner handed it. If the venues' thread stops, no venue answers, and a halt ends with the sockets closed.

## DR-V12-P03: What determinism is promised

Date: 2026-11-23

Context. The brief asked for a test in which a threaded day run several times gives identical journals.

Decision. The promise is: replay of any journal is exact; a stepped day (`V2DeskProcess`, `TradingProcess.step`) repeats frame for frame, timestamps included; a threaded live v2 day is not repeatable run to run and is not claimed to be. Two threaded runs of one seed gave 43,462 and 43,104 inbound frames; each reconciled with 0 breaks and replayed (`SupervisorV2Test`, `V2SteppedDeterminismTest`, `TradingProcessV2Test`).

Consequences. No test claims a threaded day repeats. A repeatable threaded run would need time to advance only when every thread is idle, which is the stepped process.

## DR-V12-P04: Execution-id dedupe is bounded to the children in the book

Date: 2026-11-23

Context. `ChildOrderBook` kept every (venue, ExecID) in a set that grew all day on the loop thread: a 131 kB array after about 4,500 fills.

Decision. Ids are held per child while it is in the book, cancelled children waiting for late fills included, in a preallocated pool (`ChildExecIds`); release returns them. At the bound a new fill is refused, counted as an anomaly and left outstanding, never booked without its id. A report for a child that has left the book is unknown and books nothing, as before. `resetDay()` is removed.

Consequences. Duplicates, reordered reports and resends after a reconnect are dropped as before. 50,127 fills allocate 0 bytes (`ChildOrderBookAllocationTest`). A venue reusing an ExecID on another order after the first is released is booked.

## DR-V12-P05: Pricing v2 state is a function of the journaled inputs

Date: 2026-11-23

Context. The binding stamped its start-of-day marks with the clock at construction, and its residuals depended on that stamp, so a desk rebuilt from its journals priced a Treasury 5e-9 of a point away from the desk that wrote them. DR-V12-D12 moved the stamp to the last cut, which still differed for a desk built just before a cut and replayed after it.

Decision. The binding reads no clock for its state. It stands on the universe's trade date with the starting quotes as marks until its first timed call; that call fixes the business date and dates the marks at the last New York cut. `PricingBindings.businessDayStart` is removed. Supersedes DR-V12-D12 and amends DR-R4-07 and DR-R4-08.

Consequences. Two bindings built seconds apart, either side of a cut, price bit-identically after the same timed calls (commit 8c471ec). The first quote of each instrument is taken whole.

## DR-V12-P06: Recording an input and repricing are separate

Date: 2026-11-23

Context. A loop that conflates its inputs wants to reprice once per burst; a timer that flushed on every tick split a round (DR-V12-D08).

Decision. `PricingBinding.setInput` records an input and never reprices; `repriceIfDirty` applies everything recorded with one solve; `advanceTo` applies a held round once and whole when its conflation time has passed, and `flush` only then. `onCurveInput` keeps its behaviour. Version 1 holds nothing, so its defaults apply each input at once. Supersedes DR-V12-D08.

Consequences. A burst then one reprice costs one solve. Setting an input and repricing do not allocate. The `DeskGroup` workaround of DR-V12-D08 was removed on 2026-11-24.

## DR-V12-E01: One registered family on shared sessions replaces the eight drafts

Date: 2026-11-23

Context. Eight drafts (SIM3-BASE-001, R5-001 to R5-007) would have run on eight seed bases with up to 15 parameter rows each: weeks of compute and eight looks.

Decision. Owner's decision, taken by the maintainer on the owner's delegation before any result existed: one family, `research/prereg/R5-FAM-001.yaml`, on one data set (seed base 31,000,000, 2,000 sessions), every arm on the same seeds, Benjamini-Hochberg across its eight hypotheses, the default arm's P&L as a descriptive row. The eight drafts are marked superseded before signing and kept; none was run.

Consequences. One look at the data set. Ablations are read at the central parameters only; the full band of the design is not tested. The registration is unsigned: the runner refuses it until the owner signs and commits it.

## DR-V12-E02: The configuration is one value

Date: 2026-11-23

Context. An arm was described by presets, switches and router choices in several places.

Decision. An arm is a `DeskProfile` plus its `DeskSettings.StrategySwitches`; `DeskArms` builds the arms of the family (nine on the common footing and the descriptive `legacy`); the profile gives its resolved form and a configuration hash.

Consequences. A ledger row names one configuration. The default desk and the v2 desk are two values of it.

## DR-V12-E03: One source for simulator client ids per tier

Date: 2026-11-23

Context. `DeskSettings.V2_CLIENT_TIERS` mapped five client ids to tiers A, A, B, B, C, while simulator v2 sends 2001, 2002 and 2003 for tiers A, B and C, so tier B was priced as tier A.

Decision. `DeskSettings.V2_CLIENT_ID_BASE` (2001) plus the tier index, checked against the simulator.

Consequences. Each simulated tier is priced as its own.

## DR-V12-E04: v2 arms trade the full universe; structural declines are a guard

Date: 2026-11-23

Context. The recorded universe has four swap templates; the simulator sends swap RFQs at seven tenors.

Decision. v2 arms run the simulator v2 universe. The default and cover-only arms run the recorded universe and decline the 3, 7 and 20-year swap RFQs (about 18 percent of swap flow by the calibrated tenor mix). Every arm's structural declines are counted, and a treatment may not decline more for that reason than its baseline.

Consequences. H1 compares the two desks as built, not two strategies on one universe; the registration says so.

## DR-V12-E05: The back-test shows the v2 market as the venues do

Date: 2026-11-23

Context. The back-tested desk saw the v2 market differently from the wired desk: one Treasury venue, no futures, a curve not built like the venues' round, and a business date in 2030 at which the reference data's on-the-run Treasuries had matured.

Decision. The back-test runs a v2 profile on the v2 loop with both Treasury CLOBs at their venue shares and TY on the futures venue; the curve comes from the published swap rates through the pricing v2 round; the desk is priced as of the v2 desk's business date (`DeskStrategy.atSessionOpen`).

Consequences. The back-test publishes no trade prints, so the ladder router's queue estimates see book changes only there.

## DR-V12-E06: Two sensitivity rows, descriptive; the high points are excluded with reasons

Date: 2026-11-23

Context. Of the parameters the data could not identify, two decide whether market making is viable at all: the competitors' all-in cost (which sets the adverse-selection level) and their winning half-spread.

Decision. Two rows at N = 1,000 for the default and v2 arms only: `all_in_cost:low` and `winner_half_spread:low`. Their high points are refused at start-up by the simulator's own consistency checks; the runner checks that each excluded point is in fact refused. The rows are descriptive and enter no verdict and no Benjamini-Hochberg.

Consequences. Both sensitivity rows lie on the side that makes market making harder. The rest of the band is not tested.

## DR-V12-E07: One ledger row per hypothesis; one look per family run

Date: 2026-11-23

Context. DR-RV-C02 counts looks per data set. A family reads one data set for several hypotheses at once.

Decision. A family run appends one ledger row per hypothesis and one for the descriptive baseline, all with the family id, as one look. A second run of the family on the same data set is refused.

Consequences. Error control is across the family, by Benjamini-Hochberg on command (DR-V12-B04).

## DR-V12-E08: Checkpoints per session and arm; a timing-only pilot writes no P&L

Date: 2026-11-23

Context. A 2,000-session family takes about 19 hours on eight workers; the machine restarts itself; and a pilot must size the run without becoming a look.

Decision. `ExperimentRunner` checkpoints each finished session and each finished arm inside a session; a relaunch with the same command resumes. No summary and no ledger row is written until the last session. A pilot runs on the pilot base 29,000,000, which supports no claim; `--timing-only` writes timings and structural counts and no P&L.

Consequences. The pilots of 2026-11-23 on the pilot base measured about 25 s per full-day session for the default desk run alone, and a session sd of about 214k USD from 8 sessions of the default desk (imprecise); no mean P&L was looked at. The sd sized N (the registration's `why_2000`) and is used for nothing else.

## DR-V12-E09: A cancel that finds the order filled is refused

Date: 2026-11-24

Context. On simulator v2 the back-tester books a resting order's fills when the market moves. A cancel asked for in between booked the last fills, reported them `PartiallyFilled`, and then reported `Cancelled` with nothing left to cancel. The router had released the child by then, so the loop counted an unrouted report. R5-FAM-001's draft listed the count as a possible dropped fill.

Decision. When the fills booked by a cancel complete the order, it is reported `Filled` and the cancel is refused (`StrategyContext.cancelOrder` returns false), as a venue does. An order whose booked fills reach its quantity is complete whether or not the queue model has said so.

Consequences. `SimV2OrdersTest` covers it; `DeskSimV2Test` asserts zero unrouted reports on the baseline and v2 arms. Version 1 runs are unchanged: their orders fill whole or not at all. No registered session had been generated.

## DR-V12-D13: A fill of a released child is a position

Date: 2026-11-24

Context. The loop dropped every execution report of a router order its router no longer held, including one with a fill: one that crossed the cancel's confirmation, or an order left from before a restart. The posttrade keeper books such a fill, so risk and books would differ.

Decision. `DeskGroup` books the fill to the risk engine at its own side, price and instrument, once by venue and execution id, and counts it (`lateFills`). The hedger sees it in the next residual. A report without a fill is still only counted.

Consequences. A later bust of the same execution reverses it. The parent's accounting is not reopened; the router's outstanding quantity was freed when the child ended.
