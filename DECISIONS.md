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
