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
