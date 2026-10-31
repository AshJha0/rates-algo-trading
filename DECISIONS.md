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

## DR-P4-05: Cookbook recipe numbered 17 (amended: renumbered 16)

Date: 2026-10-31

Context. The brief names the router recipe 16; COOKBOOK.md's planned list gives 16 to the P3 kill-switch recipe and 17 to "route a hedge order and read the markout report (P4)".

Decision. The repository wins: the router recipe is 17, and it prints a markout line as the planned title says.

Consequences. No collision with the P3 recipe.

Amendment (2026-10-31, branch integration). The phase plan assigns P3 recipes 12 to 15, P4 recipe 16 and P5 recipes 17 to 19, and the P5 branch wrote 17 to 19. To avoid a duplicate, the router recipe is renumbered 16 (`Recipe16RouteHedge`, `expected/recipe16.txt`); its content is unchanged.

## DR-P4-06: The router routes one instrument across venues; the hedger chooses the instrument

Date: 2026-10-31

Context. A rates hedge can be done in cash bonds or futures; a futures contract and a cash bond are different instruments with basis risk between them.

Decision. A parent names one instrument; the router splits it across the venues that list it. Venue kind (CLOB or futures) selects default parameters only. Choosing between cash and futures, and the hedge ratio, belongs to the P3 hedger.

Consequences. The router does not net or convert between instruments. A cross-instrument router would be a later change with its own decision record.
