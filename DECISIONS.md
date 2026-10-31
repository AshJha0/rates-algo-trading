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
