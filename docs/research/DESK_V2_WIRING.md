# Desk v2 wiring (v1.2)

Date: 2026-11-23. Scope: the four components of Rounds 3 to 6 (simulator v2, pricing v2, strategy v2, the ladder router) wired into one desk on one loop, across three order-book venues. Code: `rates-app` `loop/` (`DeskProfile`, `DeskGroup`, `DeskLoopFactory`, `LoopOrderGateway`), `market/LoopBook`, `pricing/` (`FuturesOverlay`, `UsdUniverse.simV2`, `V2InputIds`), `gateway/` (`FeedBinding`, `InboundRouter`), `sim/` (`V2DeskProcess`, `CurveFeedV2`, `V2Reconciliation`, `SimulatedVenuesV2`); `rates-venues` (`ClobSimulator`, `BookFeedPublisher`, `SimV2CurveRound`, `Venues.simV2`).

Status of every number: printed by the test named beside it on 2026-11-23, JDK 25, Windows 11, a shared machine. No P&L was read at any point, and no parameter of any component was changed: the strategy runs as `DeskSettings.strategyV2()` declares it, the router as `RouterPresets.ladder()` with `ExecutionModel.defaults()`, pricing as `PricingV2.Options.defaults()`. Every count below is a record of one seed, not a result.

The recorded desk is unchanged. `SmokeTest` prints 36 RFQs, 27 quotes, 16 fills, 210 hedge parents, 213 hedge fills and replay hash -4423185950475908182 before and after this work.

## 1. The profile

**Plain idea.** A desk is four choices: which market it trades on, how it prices, how it quotes and hedges, how it routes. Each has a recorded version and a second one. A profile names one of each.

**Formal statement.** `DeskProfile(settings, pricing, router, market, markoutLoad, markoutSave)`:

| Component | Recorded | Second version |
|---|---|---|
| market | `Market.STANDARD`: D2C venue, one CLOB, four swap templates | `Market.SIM_V2`: D2C venue, two Treasury CLOBs, a futures book; swaps at eight tenors, four Treasuries, TYZ6 |
| pricing | `PricingBindings.Version.V1` | `V2` |
| strategy | `DeskSettings.defaults()` | `DeskSettings.strategyV2()` |
| router | `RouterModel.TOUCH` (`RouterPresets.global()`) | `LADDER` (`RouterPresets.ladder()`, `ExecutionModel.defaults()`) |

`DeskProfile.defaults(settings)` is the recorded desk and is what `new DeskLoopFactory(universe, settings, journal)` still builds. `DeskProfile.v2()` is all four second versions. `withPricing`, `withRouter`, `withMarket` and `withSettings` switch one at a time (`DeskGroupV2Test.eachComponentCanBeSwitchedAlone`). The live loop and its replay must be built from equal profiles.

**Worked example.** `V2DeskProcess.start(new Options(dir, seed, DeskProfile.v2(), 85 minutes))` runs the whole profile, stepped, in one thread. `TradingProcess` does not construct it yet; Section 7 lists what it has to change.

## 2. Venues, ids and units

Three order-book venues, each with its own FIX session, feed, sequence, book age and order reconciliation in `DeskGroup`:

| Venue | Venue id | Gateway / outbound ring | Feed status id | Lists |
|---|---|---|---|---|
| D2C | 101 | 0 | - | swaps, Treasuries (RFQ) |
| first CLOB | 102 | 1 | 3 | Treasuries |
| control | - | 2 | - | - |
| second CLOB | 103 | 4 | 6 | Treasuries |
| futures | 104 | 5 | 7 | TYZ6 |

Gateway ids and feed status ids share the GatewayStatus id space, so the second CLOB could not take 3; a v2 loop has six outbound rings and ring 3 is never written.

Instruments: swaps 9012 (1Y), 2001, 9105, 2002, 9106, 2003, 9107, 2004; the four on-the-run Treasuries; TYZ6 as 3003 with contract size 100,000 and tick 1/64 from the reference data, which `UsdUniverse.simV2` checks against the market model. The 2-year note trades in 1/256, the other Treasuries in 1/128; each router and the strategy's tick table get the instrument's own tick. The 1-year swap is priced and risk-managed and listed on no venue.

**Units.** The desk counts face in minor units everywhere (API_SPEC.md Section 0); one TY contract is 10,000,000. The futures venue counts contracts on its wire, 100 minor units each. The conversion (a factor of 100,000) is made where a quantity enters or leaves the loop: `LoopBook.quantityScale` for book levels and prints, `LoopOrderGateway` for orders and cancels, `DeskGroup` for execution reports, overfills, busts and the reconciliation's cumulative quantities. The journals hold what crossed the wire, so the post-trade keeper and the venues reconcile in venue units (`V2Reconciliation`).

**Worked example** (`DeskGroupV2Test.aRefusedCancelAndAnOverfillAreHandledOnTheVenueTheyHappenOn`): a parent of two contracts leaves as a NewOrder of quantity 200; the venue fills three; the risk position is 30,000,000 of face, the overfill 10,000,000.

## 3. Market data

- **Books.** One `FeedBinding` per order-book venue, under the fix round's rules (DR-RV-R02): a snapshot before any live frame, a frame forwarded only if next in sequence, a gap or a refused frame followed by DISCONNECTED, a whole snapshot and CONNECTED.
- **Trade prints.** A simulator v2 venue publishes a TradePrint (template 4) for every trade, on its own sequence, before the book delta of that trade. `FeedBinding` forwards it while the book is whole; a print the loop cannot take is counted (`printsDropped`) and is never a gap, because it changes no level. `DeskGroup.onTradePrint` applies it to `LoopBook.applyTrade` and to the instrument's router (`SmartOrderRouter.onTrade`), and takes it as a sign of life for the venue's book age. The version 1 venue publishes none.
- **Curve.** `CurveFeedV2` sends, every 500 ms and with one timestamp, exactly the build set of pricing v2 on the business date: the overnight fixing, SR1 x2, SR3 x3 and par swaps at the eight risk tenors, then the heartbeat. `PricingV2.unfedInputs()` is 0 after the first round. The values are the model's (`SimV2CurveRound`): model swaps at the venue's reference price; everything shorter than two years off the two-year node, as SIM_V2_DESIGN.md Section 3 applies that node to shorter maturities. What that leaves open is declared, not fitted: the front opens flat at the two-year rate, a SOFR future is 100 less the front rate on a 0.0025 tick, and the overnight rate does not move within a session.
- **Health and age.** `DeskGroup.venueUsable(venueId)` and `venueBookNanos(venueId)` per venue; `V2DeskProcess.statusJson()` shows them with the spread ladder.

## 4. What DeskGroup does differently under a v2 profile

- Pricing is built by `PricingBindings.create`, as of the start of the business day (Section 8, fourth row). With futures in the universe it sits under `FuturesOverlay`, which adds TY to the directory: fair value is the mid of the futures book; rate risk is the contract's declared price change per basis point at its node (-0.0715 at 7 years), which is the market model's definition of the contract and not a cheapest-to-deliver calculation; spread risk is the same ladder.
- CurveInput kinds Fixing and Future are accepted. After a call that reprices, every instrument's fair value goes to the risk engine and the spread ladder is recomputed (`afterReprice`).
- The loop timer calls `advanceTo` every 10 ms, and `flush` once a held round has waited the binding's conflation time on the loop's clock. Calling `flush` on every tick, as PRICING_V2.md Section 12 item 4 reads, applies a round in pieces whenever it straddles a tick: three reprices a round instead of one in `DeskV2RecoveryTest` before the rule was added.
- `SpreadRiskBook` follows the risk engine's positions after every message that can fill (`syncSpreadRisk`); fills are booked inside rates-quoting and rates-risk, so the book is brought to the engine's position rather than told of each fill. `DeskGroup.spreadRisk()` exposes it. Nothing limits, quotes or hedges on it: the hedger has no input for a second ladder.
- Strategy v2 gets what STRATEGY_V2.md Section 10 asks of the desk: a tick per instrument, a `HedgeAvailability` that is true for an instrument while a venue listing it has its session up, its book whole and an update within 10 s, the futures book through the composite `LoopBook`, the markout store loaded at build and saved by `DeskGroup.endSession()`. Covers reach the engine with the venue's QuoteResponse as before; QuoteWithdraw goes to the D2C gateway, which sends FIX Z, which the venue honours.
- One router per hedge instrument with every venue that lists it: both CLOBs for a Treasury, the futures book for TY. The `AgedView` forwards `levelPrice` and each venue's own age. An order the quoting side rests is registered with its instrument's router (`registerOwnOrder`) and follows its execution reports.
- Kill, gap, staleness, cancel-reject, overfill and reconciliation are per venue. A venue that is down, gapped or silent is excluded and the others keep trading.

**The markout file and replay.** A desk reads the store when it is built, and a replay builds the desk again. If the session saved over the file it loaded, the replay would start from another store and quote differently. So the profile has two files: `markouts.start`, read at build and never written during a session, and `markouts.end`, written at the end; the next start moves end to start (`V2DeskProcess`).

## 5. A smoke day

`SmokeV2Test`, seed 28,091,007, desk logged on from 09:00 to 09:30 New York, 20 ms steps.

| | Day | Treasury feeds silent from 09:03 |
|---|---:|---:|
| RFQs seen / quoted / won | 20 / 20 / 9 (1 swap, 8 Treasuries) | 20 / 10 / 2 |
| quotes on swaps / on Treasuries | 6 / 14 | - |
| hedge parents routed / hedge fills | 28 / 63 | 89 / 92 |
| fills on CLOB 1 / CLOB 2 / futures | 38 / 25 / 0 | 4 / 1 / 87 (4 / 1 / 0 at the outage) |
| curve rounds / reprices | 3,600 / 3,601 | - |
| book frames CLOB 1 / CLOB 2 / futures | 10,665 / 10,035 / 7,324 | - |
| trade prints applied | 7,236 | - |
| feed gaps / limit breaches / overfills | 0 / 0 / 0 | 0 / 0 / 0 |
| trades reconciled with the four venues | 72 of 72 | 94 of 94 |
| replay | hash equal, digest equal | hash equal, digest equal |

Asserted: quotes on swaps and on Treasuries, RFQs won, covers reaching the cover model, hedge fills on both CLOBs, no curve input seeded, one reprice a round, every feed whole, no limit breach, the hedger and the routers agreeing on what is working, the kill switch leaving nothing working on any venue, positions reconciling with every venue, and a replay that sends the same bytes and leaves a desk with the same state digest.

**Why 09:00.** The version 1 smoke test starts at 08:25. The desk pulls its quotes from a minute before a scheduled release to half a minute after and quotes wider for five minutes more (08:30 and 10:00). On this seed the twenty minutes from 08:25 held nine RFQs, of which the desk won two Treasuries and no swap, and nothing reached the hedger: the desk as declared, and almost none of the wiring exercised. The window was moved for coverage. No P&L was looked at in either window.

**The future was not chosen on the plain day.** The factor hedge planner hedged every residual in cash Treasuries. That is what its objective says at the declared costs: half a 1/64 tick on a TY contract is 0.109 bp of its DV01 against 0.048 bp for half a 1/128 tick on the 10-year note, and the future carries basis variance on top. Nothing was changed to make it choose otherwise. The futures path is exercised by the second test, a venue outage: both Treasury feeds go silent without a gap, their books pass the 10 s age limit, Treasury RFQs are declined on a stale input, the planner is left with the future, and the desk hedges its swap trades there (87 fills, positions in whole contracts, reconciled, replayed). Whether TY should be a hedge the planner reaches for on a normal day is a question for the strategy's pre-registered runs, not for the wiring.

## 6. Cost on the loop and allocation

`WiredLoopV2AllocationTest`: the v2 desk stepped with a manual clock through curve rounds, books and prints on three venues, RFQs, their outcomes and hedge fills; bytes allocated inside each `doWork` are counted against the kind of message that caused them. Four windows of 400 cycles after 1,600 of warm-up, under the version 1 gate's bound: no window after the first above 1,024 bytes and at least one at zero. Observed over four runs: 0 bytes in every later window in two, and one later window of 56 bytes in two, counted against an RFQ.

| Path | Median | 99th percentile |
|---|---:|---:|
| one RFQ, QuoteRequest to Quote | 7.8 to 43 microseconds | 26 to 161 microseconds |
| one complete curve round's reprice (bootstrap, leave-one-out, bonds, every fair value pushed) | 132 to 789 microseconds | 362 to 2,302 microseconds |

Four runs of the test under the build's coverage agent on a shared machine; the low end of each range is the one run made while no other build was running, the high end the worst of the three made beside other builds. Read the low end as nearer the truth and the spread as what a loaded host does. An RFQ that arrives during a reprice waits for it.

Hot-path rules: `DeskGroup`, `LoopBook`, `InboundRouter`, `FeedBinding`, `RouterHedgeSink` and the new `FuturesOverlay` are `@HotPath` and pass `AppHotPathRulesTest`.

## 7. What TradingProcess has to change

`TradingProcess`, `TradingProcessMain` and the gateway threading were not edited. `V2DeskProcess` is the same assembly in the shape the process already has; the changes are:

1. `Options`: a `DeskProfile profile` (default `DeskProfile.defaults(settings)`); `universe = profile.newUniverse()`.
2. Venues: for `Market.SIM_V2`, `new SimulatedVenuesV2(clock, seed)` on a clock at `V2DeskProcess.SESSION_OPEN_EPOCH_NANOS`, `startFlow()`, `runAlone(clock, openNanos)`; no `ClobLiquidity`.
3. Loop: `V2DeskProcess.loopBuilder(loopClock, inboundRings[g], outbound)` with `AppIds.V2_OUTBOUND_RINGS` rings; `new DeskLoopFactory(profile.withMarkouts(start, end), universe, routeSinks[g])`.
4. Sessions: two more `VenueSession`s, `(CLOB2_VENUE, CLOB2_GATEWAY)` and `(FUTURES_VENUE, FUTURES_GATEWAY)`, both `withReconcileOrders(true)`; `connect`, the logon wait, `doWork`, `inSync`, `recovered` and `close` over all four.
5. Feeds: three `FeedBinding`s with status ids 3, 6 and 7; `requireRebuild` on each after a restart.
6. Curve: `new CurveFeedV2(universe, venues.session(), gatewayInbound, clock, CURVE_INTERVAL_NANOS)` in place of `CurveFeed`.
7. Restart: the journaled DISCONNECTED for gateways 0, 1, 4 and 5.
8. End of session: `group.desk().endSession()` before the journals close; at start, move `markouts.end` to `markouts.start`.
9. `TradingProcessMain.replayGroup(dir)`: `LoopReplay.run(dir, "in", "out", new DeskLoopFactory(profile, profile.newUniverse(), FrameSink.NONE))`; the profile has to come from the configuration that wrote the journals.
10. Status: the venues and the spread ladder as `V2DeskProcess.statusJson` prints them.
11. Post-trade: `V2Reconciliation.keeper` and `run` in place of `JournalFeed.catalogue(AnalyticsPricing)` and `VenueReconciliation.run(keeper, d2c, clob)`.

Not built: `SimulatedVenuesV2` starts its acceptors plain, without the TLS, credentials and store directories `SimulatedVenues` takes, so a process restart against simulator v2 venues that keep their sequence numbers is not possible yet. The desk-level restart is tested (`DeskV2RecoveryTest`).

## 8. Defects found while wiring

| Where | What | State |
|---|---|---|
| `FeedBinding.rebuild` (rates-app) | The first snapshot of a book that already had levels was delivered without DISCONNECTED. Its frames all carry the snapshot's sequence, which `DeskGroup` and the input health read as a gap in a feed they believed whole: one false gap per venue at every start against a venue that was already trading. The version 1 simulators start empty, so it never showed. | Fixed: a first snapshot with frames is announced like any other; a DISCONNECTED before any frame is not counted as a gap |
| `Venues.simV2` / `ClobSimulator` (rates-venues) | All three simulator v2 order books published their feed as venue 102, so a desk reading more than one could not tell them apart | Fixed: 102, 103, 104 |
| `PRICING_V2.md` Section 12 item 4 | "A timer calling `flush`" applies a round in pieces when it straddles the timer; the conflation time is only checked when another input arrives | Worked around in `DeskGroup` (flush after the conflation time on the loop clock); the binding is unchanged |
| `PricingV2` constructor (rates-app pricing, lines 268 to 378) | The binding stamps its start-of-day marks with the clock at construction, and the residuals it carries afterwards depend on that stamp. A live loop is built before its first input and its replay on the first journaled frame, so a desk rebuilt from its journals priced a Treasury 5e-9 of a point away from the desk that wrote them (`DeskV2RecoveryTest`, with the desk built 1 ms before its first frame). On a process running on its own threads the two instants always differ | Worked around in `PricingBindings.create`: version 2 is built as of the last New York cut, which is the same for both. The binding is unchanged; a desk built just before a cut and replayed from a first frame just after it would still differ |
| `PricingV2.inputId` (rates-app pricing) | Returns `V2InputIds.parSwap(months)` for every pillar swap, where PRICING_V2.md Section 12 says "the template id where one exists". Both ids reach the input, so nothing breaks; the feed sends the id the binding lists | Not changed |
| `Venues.T0` (rates-venues), `SimV2VenuesTest.OPEN`, `SimulatedVenuesV2Test` | The constants are read in comments as 13:30Z / 08:30 and 12:00Z / 07:00 New York; they are 14:30Z and 13:00Z, an hour later, because daylight saving ended on 1 November 2026. The values are what the recorded runs use and are right for them (the trading process starts 65 minutes before `T0`, at 08:25 New York); only the reading was wrong. It matters to pricing v2, whose business date and cut are read off the epoch clock, and to a simulator v2 session, which stands for 07:00 at whatever instant it is opened | `Venues.T0` comment corrected, value unchanged; `V2DeskProcess.SESSION_OPEN_EPOCH_NANOS` is the true 12:00Z; the two test constants are not mine and are unchanged |
| `ChildOrderBook.execSeen` (rates-router, line 138) | The set of execution ids is sized for an expected number of fills and doubles on the loop thread: a 131 kB array when a router has seen about 4,500 fills in a day | Not fixed (not owned); the allocation gate is kept below it and says so |
| `STRATEGY_V2.md` Section 10 | Expects futures to become hedges "once a futures book exists"; at the declared tick costs the planner never chose TY on the smoke day | Not a code defect; recorded in Section 5 |
| `DeskSettings.V2_CLIENT_TIERS` | Maps five client ids to tiers A, A, B, B, C; simulator v2 sends 2001, 2002, 2003 for tiers A, B, C (SIM_V2_DESIGN.md D12), so tier B clients are priced as tier A and tier C as tier B until their own markouts take over | Not changed: a strategy parameter, and its document says it follows the simulator when the ids are fixed. Owner decision |

## 9. Known limits

- The process is not switched: Section 7.
- TY risk is the model's declared sensitivity, not a cheapest-to-deliver calculation; the futures basis is not a risk factor of the desk.
- Spread risk is visible and neither limited nor hedged.
- The front of the simulated curve is flat at the open and moves with the two-year node; the overnight rate is constant through a session.
- A trade print lost on a full ring is not recovered; the router's queue estimate then reads the size decrement as cancellations.
- One book-age limit (10 s) serves every venue.
- The 1-year swap is never quoted: no venue lists it.

## 10. Where it is tested

`DeskGroupV2Test` (16: profile, kill on every venue, a gapped venue, a session down, a silent venue, a first snapshot, cancel-reject, overfill and bust per venue and in futures units, curve input kinds and the push after a reprice, the timer flush, prints, the QuoteWithdraw, the route journal's templates 83 and 84, own orders, the markout files), `DeskV2RecoveryTest`, `SmokeV2Test` (2), `WiredLoopV2AllocationTest`, `TradePrintRoutingTest` (5), `V2UniverseTest` (6), `CurveFeedV2Test` (3), `FeedBindingTest` (first snapshot), `SimV2PublishingTest` (3, rates-venues).

## 11. Text for the documents this round did not edit

### CHANGELOG, Unreleased, Added

- The v2 desk profile, selectable and off by default (`DeskProfile.v2()`; the recorded profile and every recorded figure unchanged): simulator v2's two Treasury CLOBs and futures book, pricing v2 fed whole by the simulator's curve round, strategy v2 and the ladder router on one loop, with session, feed, sequence, age and reconciliation kept per venue. Each component can be switched alone. `V2DeskProcess` runs it stepped; `TradingProcess` is not switched. Design and evidence: `docs/research/DESK_V2_WIRING.md`.
- `UsdUniverse.simV2()`: SOFR swaps at the eight risk tenors, the on-the-run Treasuries with their own ticks, TYZ6 with the reference data's contract terms. `FuturesOverlay`: a Treasury future as an instrument of the pricing binding.
- Trade prints (template 4) from the simulator v2 order books, through `FeedBinding` and `InboundRouter` to `LoopBook.applyTrade` and `SmartOrderRouter.onTrade`. `CurveFeedV2` and `SimV2CurveRound`: the overnight fixing, SOFR futures and par swaps of the pricing v2 build set from the simulator's factor model.
- `AppIds`: `CLOB2_VENUE` 103, `FUTURES_VENUE` 104, gateways 4 and 5, feed status ids 6 and 7, `V2_OUTBOUND_RINGS` 6, `SWAP_1Y` 9012.
- `SmokeV2Test`, `DeskGroupV2Test`, `DeskV2RecoveryTest`, `WiredLoopV2AllocationTest`.

### CHANGELOG, Unreleased, Changed

- `Venues.simV2`: the three order books publish as venues 102, 103 and 104 (all were 102).
- `DeskGroup`: one state per order-book venue; `pricing()` throws for a desk built with pricing v2 (use `binding()`); `clobUsable()` is the first venue's.

### CHANGELOG, Unreleased, Fixed

- `FeedBinding`: the first snapshot of a book that already has levels is delivered between DISCONNECTED and CONNECTED, so the loop no longer reads its frames as a gap. A DISCONNECTED before any frame is not counted in `DeskGroup.feedGaps()`.

### DECISIONS

**DR-V12-D01: A desk profile selects each component; the recorded profile stays the default.** Context: four components were built off by default and every recorded figure depends on version 1 of each. Decision: `DeskProfile` with `defaults()` and `v2()`; `DeskLoopFactory(universe, settings, journal)` builds the recorded profile. The default changes in a pre-registered step.

**DR-V12-D02: Per-venue state in the desk; ids 103 and 104, gateways 4 and 5.** Context: gateway ids and feed status ids share one id space and 3 is the first CLOB's feed. Decision: Section 2; a v2 loop has six outbound rings with ring 3 unused.

**DR-V12-D03: The desk counts a future in face, the venue in contracts; the loop converts at its edge.** Context: API_SPEC.md Section 0 fixes face in minor units, and `Instrument.future` values a position per 100 of face; the futures venue's FIX quantities are contracts. Decision: Section 2. Consequence: the journals and the post-trade keeper are in venue units; the risk engine, the hedger and the routers in face.

**DR-V12-D04: A trade print is not part of the delta stream.** Context: DR-RV-R02 makes a refused book delta a gap. Decision: prints have their own sequence, are forwarded while the book is whole, and a lost one is counted. The version 1 venue publishes none, so its recorded feed is unchanged.

**DR-V12-D05: The simulated front end follows the two-year node; the overnight rate is constant in a session.** Context: the market model has no instrument shorter than two years and pricing v2 needs a fixing, five futures and a 1-year swap. Decision: Section 3. Assumptions, declared in `SimV2CurveRound`.

**DR-V12-D06: A Treasury future is priced off its book and risked at the model's declared sensitivity.** Context: the hedge planner needs TY as an instrument; a cheapest-to-deliver calculation is pricing work this round did not own. Decision: `FuturesOverlay`. The basis is not a desk risk factor.

**DR-V12-D07: The markout store is loaded from one file and saved to another.** Context: a replay rebuilds the desk, which reads the store again. Decision: Section 4. `Desk.saveMarkouts`, which writes the file it loaded, is not used by the v2 process.

**DR-V12-D08: The loop timer flushes a held curve round after its conflation time, not on every tick.** Context: Section 8, third row. Decision: `DeskGroup` records when a round's first input arrived on the loop clock and flushes 100 ms later.

**DR-V12-D12: Pricing v2 is built as of the last New York cut, not the instant of construction.** Context: Section 8, fourth row; replay and restart build the desk at another instant than the live process did. Decision: `PricingBindings.create` passes the binding a clock standing at `BusinessDate.lastCut`. The business date is the same; start-of-day marks are stamped 17:00 of the previous day, so the first quote of the day is taken whole.

**DR-V12-D09: The first snapshot of a non-empty book is announced.** Context: Section 8, first row. Decision: supersedes nothing in DR-RV-R02; it applies its rule to the first snapshot. The default profile's journals are unchanged because its venue starts empty.

**DR-V12-D10: The smoke day asserts hedges on both CLOBs; the future is exercised by an outage.** Context: Section 5. Decision: no parameter was changed to make the planner choose TY. Open for the strategy round.

**DR-V12-D11: Spread risk follows the risk engine's positions and is displayed only.** Context: fills are booked inside rates-quoting. Decision: Section 4. A limit or a hedge on spread risk needs an input the hedger does not have.

### API_SPEC

Section 18.2, add the ids of Section 2 above. Section 18.4, add: `DeskProfile`; `DeskLoopFactory(DeskProfile, UsdUniverse, FrameSink)`; `DeskGroup.binding()`, `pricingV2()`, `spreadRisk()`, `venueUsable(venueId)`, `venueBookNanos(venueId)`, `hedgeAvailable(instrumentId)`, `endSession()`. Section 18.5, add: a TradePrint on a feed channel is forwarded by `FeedBinding` while the book is whole and routed by `InboundRouter` to its instrument's group as market data; a CurveInput whose id is one of `V2InputIds` goes to every group. Section 10.5, add: simulator v2 venues publish TradePrint on a per-venue sequence separate from BookUpdate's. Section 0, add: a futures venue's wire quantity is contracts; the loop converts to face at its edge.

### LEARN

A short section, "One market, three books": why two CLOBs showing shares of one book and a futures book need a state each, with the gapped-venue example of `DeskGroupV2Test`. One, "Why the desk did not hedge in futures", from Section 5: the planner's objective, the two half-tick costs, and the outage in which TY is the only hedge left. A note in the replay section on the two markout files.
