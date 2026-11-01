# P&L audit: where the engine loses money, leaves it on the table, or wastes cost

Every measured number below comes from a simulator whose competitors, informed flow and win-probability slope are declared assumptions, so it is a simulator consistency check reported across the assumption band, never evidence of edge (DR-008).

Written 2026-10-31 on branch `claude/r2-pnl-audit-e32be7` from the seat of a desk head who has to make the business pay. The audit read rates-quoting, rates-risk, rates-hedge, rates-router, rates-venues, rates-posttrade and rates-backtest on `main` (4facfef), and the unmerged branches r2-wiring, r2-review, r2-business and r2-edge-research, so that nothing below recommends work already done there.

## How the numbers were made

- **Runs.** Four pre-registrations, `research/prereg/AUDIT-01.yaml` to `AUDIT-04.yaml`, were committed with their configs and the harness in 3283b6e before the evaluation period was read. Each was run once with `research/audit/run_audit.sh`. Each appended one row to `data/ledger/ledger.jsonl`: seq 8 (AUDIT-01), seq 9 (AUDIT-02), seq 10 (AUDIT-03) and seq 11 (AUDIT-04), all recorded on 2026-10-31 at code commit 3283b6e. AUDIT-01 ran on a clean tree. AUDIT-02, AUDIT-03 and AUDIT-04 were recorded with `code_dirty: true` (ledger seq 9 to 11). An earlier version of this document said all four ran on a clean tree; the ledger is right and its rows stay as recorded. The flag is set because the working tree differed from the commit, and the difference was the ledger file itself, appended to by the previous audit row (the same cause as for AUDIT-12 to AUDIT-14, below). The code, configs and pre-registrations those runs read were the committed ones; the flag does not record that by itself, so a reader who needs proof can re-run the three pre-registrations at 3283b6e and compare with the recorded results. A copy of each row is in `research/audit/results/AUDIT-0n.json`. The rows were seq 2 to 5 on the audit branch and became seq 8 to 11 when the round 2 branches were combined, with only `seq` and `prev_sha256` changed (DECISIONS.md DR-INT-04).
- **Days and seeds.** 20 synthetic sessions, 2026-03-02 to 2026-03-27. The day seed is 6,000,000 plus the epoch day (6,020,514 to 6,020,539) and the outcome seed is 7. Each session holds on average 367 RFQs, 65 of them from informed clients.
- **Assumption band.** Every variant is settled at the three points of `usd_rfq_hit_rate.spread_to_cover_bp`: low (L) -1.25, central (C) -2.5, high (H) -5.0. In the text, "C" means the central point of that run.
- **Statistics.** Sizes are paired daily differences against the engine: the mean, and a 95 percent moving-block bootstrap interval (blocks of 2 sessions, 2,000 resamples, seed 11). They are also given in USD per 1,000,000 USD of client DV01 traded (the engine's mean per day at that band point). Dividing that figure by 1,000,000 gives bp of DV01.
- **Registered verdicts.** The registered rule also asks for a Holm-adjusted Welch p below 0.05. Inventory noise makes the unpaired test powerless: every Holm p is 1.0, so every registered verdict is "inconclusive". The paired intervals carry the sizing. Where an interval excludes zero, the text says so.
- **Cross-check.** `research/audit/summarize.py` recomputes every interval and Holm p from the daily P&L stored in the rows (through `ratesresearch.backtest_analysis`) and asserts that they match. `results/summary.md` holds its output.
- **What the engine is.** The "engine" is the back-tester's `model_spread` strategy with the P5-001 inputs: hit-rate-optimal half-spread, tier adverse charges and hedging into the 10-year note future above 100,000 USD net DV01. The harness reproduced it to the cent on all 240 checks: `results.replica_check`, 60 per row, 0 mismatches.
- **Not the production engine.** rates-quoting's `QuotingEngine` is not what the back-tester runs. r2-wiring's `DeskBacktest` (rates-app) binds the production desk to the back-tester, so items 1 to 10 should be re-run through it once that branch is merged.
- **What "estimate" means.** An item marked "estimate" could not be measured with what exists. It is an order of magnitude, with the assumptions stated in its section.
- **Synthetic scale.** Synthetic swap tickets have a median of 21mm and a tail capped at 1,000mm, so absolute USD per day is large: the engine at C makes 2.22mm USD a day on 2.78mm USD of client DV01. Read sizes as bp of client DV01 or as a share of the engine's P&L.

## Ranked leaks and opportunities

Engine P&L for reference: 3.45mm (L), 2.22mm (C) and 0.97mm (H) USD per day, from AUDIT-01, seq 8. Plan-days are rough estimates for one engineer, including tests and a back-test run.

| Rank | Leak or opportunity | Evidence | Size (C unless stated; 95% interval) | Fix proposed | Module | Plan-days | Already covered by |
|---:|---|---|---|---|---|---:|---|
| 1 | Hedging in one market-order block into the future spends a third of gross spread | AUDIT-01 seq 8 and AUDIT-03 seq 10, engine attribution | -704k USD/day (band -278k H to -1.05mm L); 0.328 bp per DV01 hedged; 253k per 1mm client DV01 | Net client flow before hedging, slice hedges, passive first with escalation | rates-hedge, rates-backtest strategy | 3 | r2-business (NettingHedgeBudget, OptimalStoppingTiming), in progress; back-tester depth-walk hedge fills: fix D, 503be68 (DR-FIX-D-01) |
| 2 | Hit-rate slope frozen at the central value; production learning bounded to [-2.4, -1.0] | AUDIT-01 seq 8, slope_known | H: +949k/day (89k to 2.48mm), 918k per 1mm DV01; L: +147k (-1.40mm to 1.27mm); C: 0 by construction | Learn the slope from own RFQ outcomes, widen the bounds | rates-quoting HitRateModel | 3 | Fixed: fix A1, 18da1a1 (DR-FIX-A1-01) |
| 3 | Volume rests on clients accepting quotes behind the cover | AUDIT-01 seq 8, engine diagnostics and band rows | Won trades 0.44 bp behind cover (DV01-weighted); engine P&L -2.49mm/day from L to H (-72%) | Record cover and rank on every RFQ; back-test a best-price-wins client | rates-quoting, rates-backtest | 2 | Recording fixed: fix A2, 78615e5 (DR-FIX-A2-01); back-tester best-price client: fix D, 503be68 (DR-FIX-D-02) |
| 4 | Adverse selection charge too low by tier, and the production toxicity markout nets out the captured spread | AUDIT-01 seq 8 and AUDIT-02 seq 9 | 5-min adverse move tier 3: 0.31 bp vs 0.13 charged, tier 2: 0.22 vs 0.067; widen_tier3 L +93k (59k to 130k), C -12k (-67k to 50k) | Mark toxicity from mid at trade, not from the traded price; price toxic tiers, do not decline them | rates-quoting QuotingEngine:675, ToxicityTable | 1 | r2-business MarkoutTiering uses ToxicityTable (inherits the markout definition); Fixed: fix A2, 78615e5 (DR-FIX-A2-02) |
| 5 | Declining informed flow costs more than it saves at the engine's spreads | AUDIT-02 seq 9 | oracle_decline_informed C -486k (-1.33mm to 81k), L -1.13mm (-2.15mm to -358k); decline_tier3 C -638k (-1.38mm to -24k) | Keep blocked-tier and decline rules off by default; widen instead | rates-quoting TierTable defaults | 0.5 | Fixed: fix A1, 18da1a1 (DR-FIX-A1-03) |
| 6 | CLOB quotes are off the tick grid, so the venue rejects every one | code reading: ClobQuoter.java:230 vs ClobBook.java:66 | estimate: CLOB passive revenue is 0 today; gross opportunity about 29k USD/day at 500mm a day per Treasury, before adverse selection | Round bid down and ask up to the venue tick | rates-quoting ClobQuoter | 0.5 | Fixed: r2-review c53ce91 (tick rounding, DR-R2R-12); tick-rule test in fix B, 52b6df1 |
| 7 | No inventory skew in the back-tested engine or on the CLOB | AUDIT-04 seq 11, skew variants | skew_0p05 C +97k (-48k to 366k), L +61k (-14k to 176k), H -35k (-276k to 127k); skew_0p20 L +151k (17k to 284k), C -143k (-607k to 373k) | Calibrate the RFQ skew through DeskBacktest; add skew to ClobQuoter | rates-quoting | 2 | r2-business AxeBook (RFQ side only); CLOB side fixed: fix B, 52b6df1 (DR-FIX-B-01) |
| 8 | Firm quotes stand through market moves for their whole time to live | AUDIT-01 seq 8 diagnostics; estimate for 5 s | 1.5 s window: +31.7k/day moved in clients' favour, positive part 50.3k/day; estimate at a 5 s time to live with clients exercising: about 92k/day (0.033 bp of client DV01) | Withdraw or reprice live quotes when fair value moves more than a threshold | rates-quoting QuotingEngine poll | 2 | r2-wiring adds QuoteWithdraw (60) to the gateway; no engine trigger; Fixed: fix A2, 78615e5 (DR-FIX-A2-03) |
| 9 | Quoting through scheduled releases earns nothing | AUDIT-01 seq 8 diagnostics, AUDIT-04 seq 11 release_guard | Within 2 min of 08:30 and 10:00 ET: spread 36.7k/day, 5-min move -37.5k/day; guard C +20k (-14k to 70k), L +21k (-11k to 70k), H -3k (-19k to 7k) | Configure the release calendar so the event widening fires; pull from 60 s before to 30 s after | rates-quoting QuotingConfig, rates-app config | 1 | r2-edge-research E3 measures release variance (supported); nobody configures it; Fixed: fix A2, 78615e5 (DR-FIX-A2-04) |
| 10 | Fixed DV01 hedge trigger is right at one band point and wrong at another | AUDIT-03 seq 10, thresholds | 25k: C -247k (-374k to -90k), L -357k (-504k to -249k); 250k: C -334k (-546k to -61k), H +214k (24k to 446k) | Cost-aware timing rule instead of a fixed trigger | rates-hedge, rates-backtest | 2 | r2-business OptimalStoppingTiming |
| 11 | Spread optimiser uses a point estimate of the cover | AUDIT-01 seq 8, cover_integrated | C +69k (-50k to 214k), L +29k (-1k to 57k), H -5k (-41k to 23k); 25k per 1mm DV01 at C | Average win probability over the cover distribution; feed the observed cover gap back | rates-quoting SpreadModel | 2 | Fixed: fix A1, 18da1a1 (DR-FIX-A1-02) |
| 12 | Package RFQs skip the controls single RFQs have | code reading (PackageQuoter) | estimate: one stale 50k DV01 package hit through a 2.5 bp release costs about 125k USD; package revenue not modelled | Route packages through kill, health, throttle, reservation and time-to-live checks; price on net risk | rates-quoting PackageQuoter | 3 | Fixed: fix A2, 78615e5 (DR-FIX-A2-05) |
| 13 | Late Done responses can miss risk and the hedger | code reading: QuotingEngine.java:338, 396, 437 | estimate: tail; an unhedged 1,000mm 10Y fill is about 811k DV01, one standard deviation about 4.9mm USD a day at 6 bp | Keep the slot for a grace period, or book unmatched Dones to risk | rates-quoting | 1 | Fixed: r2-review c53ce91 (DR-R2R-13) and fix A2, 78615e5 (DR-FIX-A2-06) |
| 14 | Limits and the kill switch make risk harder to reduce | code reading: RiskEngine.java:349-386, 696; Hedger.java:228 | estimate: tail; in a breach the cheapest risk reduction (client flow) is refused and hedges stop | Exempt risk-reducing quotes; let the kill keep flattening hedges | rates-risk, rates-hedge | 2 | r2-review 2f5a41d and 4838726 (partly); Fixed: fix C, b0521ec (DR-FIX-C-01) |
| 15 | Router prices passive fills without adverse selection | code reading: Allocator.java:78-99; AUDIT-03 seq 10 passive_ty | passive_ty C: hedge cost +130k vs -704k but inventory -185k vs +781k, net -123k (-1.10mm to 841k); estimate of the bias: half a tick (0.11 bp) on passive hedge DV01, about 195k/day | Add an adverse term to the filled branch; use per-instrument volatility | rates-router | 2 | No (r2-review f6ce7fd fixed a different cost term); Fixed (off by default): fix C, b0521ec (DR-FIX-C-03) |
| 16 | All risk hedged in one future; basis and swap-spread risk neither limited nor attributed | AUDIT-03 seq 10 bucketed; Desk.java:83 | bucketed C -80k (-308k to 142k), daily std 2.93mm vs 2.82mm; estimate: 100k DV01 of swap risk in futures carries about 150k USD a day of swap-spread standard deviation | Limits and attribution on basis and swap spread; a swap or swap-future hedge leg | rates-risk, rates-hedge | 2 | Fixed: fix C, b0521ec and b69d8c1 (DR-FIX-C-02) |
| 17 | Funding, balance sheet, initial margin and the CCP basis are not priced | AUDIT-01 seq 8 funding line; estimate | Back-tester funding -3.4k to +0.8k/day (no coupon carry); estimate: initial-margin funding about 200 USD a day per 500k DV01 held overnight | Funding and balance-sheet penalty in the spread; CCP choice in pricing | rates-strategy, rates-quoting | 2 | r2-business FundingPenalty, CouponCarry, InventoryPenalty; Coupon carry fixed: fix D, 503be68 (DR-FIX-D-03); balance sheet, margin and CCP basis open |
| 18 | Message and cancel cost not counted | code reading: TokenBucket, Allocator, EodReportMain:79 | estimate: small in fees, but the order-to-trade ratio exposes the desk to venue message policies | Count cancels in the stuffing detector; cost messages in the router; native amend | rates-router, rates-posttrade | 1 | Fixed: fix C, b0521ec (DR-FIX-C-03) |
| 19 | Response latency not modelled where it decides the win | code reading: D2cRfqSimulator.java:439-504 | estimate: negligible for D2C RFQ at millisecond latency; matters for streaming, which is missing | None for RFQ; model it when streaming is added | rates-venues | 0 | No |

## Findings

### 1. Hedge execution spends a third of the spread

Market-order hedges cost the engine 704k USD a day at C, which is 32 percent of its 2.19mm of gross spread capture (AUDIT-01 seq 8). The engine hedges 77 percent of client DV01 (2.15mm of 2.78mm a day). Each hedge pays 0.328 bp per DV01 hedged at every band point, which is 1.5 TY ticks from mid. The back-tester engine sends one block once net DV01 passes 100,000 USD, which is 1,400 or more contracts against about 500 displayed at the touch. The cost model rounds mid plus half-spread plus impact to the tick against us, so any positive impact adds a full tick; for blocks of that size this is close to the cost of a real sweep.

Two cheaper alternatives were measured, and neither is a fix on its own:
- Passive hedging at the touch, with no cancel (AUDIT-03 seq 10, passive_ty): the hedge line goes from -704k to +130k a day at C, but unhedged inventory gives back 965k, a net of -123k (interval -1.10mm to +0.84mm).
- Raising the trigger to 250k: net -334k at C (interval -546k to -61k).

The value is in hedging less, by netting opposing client flow, and in slicing. Production `Hedger` already stages tranches. r2-business is building the netting and timing rules, and this audit sizes the prize they are after: up to 0.25 bp of client DV01.

### 2. The hit-rate slope is a guess, and production cannot learn a steep one

When the strategy knows the slope at which the run is settled (AUDIT-01 seq 8, slope_known), it earns +949k a day at H (interval 89k to 2.48mm), 918k per 1mm DV01 traded, nearly double the engine. At L it earns +147k (interval -1.40mm to +1.27mm). At C the difference is 0 by construction.

Production `HitRateModel` learns online but holds b1 within [-2.4, -1.0] and starts at -1.6 (`quoting-hit-rate-default.yaml`), so a market where price decides (H, -5.0) cannot be learned. The fix: estimate the slope from the desk's own wins, losses and covers, widen the bounds, and report the learned slope daily. Public data cannot supply this, because public tapes show no losing quotes (DR-008), so r2-edge-research does not cover it.

### 3. The engine wins from behind the cover

On the trades the engine won, its price was on average 0.44 bp worse than the best competitor (DV01-weighted, C; 0.48 at L and 0.36 at H; AUDIT-01 seq 8). Money left on the table, where we were inside the cover, is only 2.4k a day. The simulated client picks a dealer by a soft logistic, not by best price. The engine's P&L therefore falls from 3.45mm (L) to 0.97mm (H) a day as clients become price-driven.

For a desk head this is the largest risk to the plan: the engine's revenue depends on client loyalty that nothing has measured. The fix is data, not code:
- record the cover and our rank on every RFQ, including passes and losses (production keeps neither);
- add a best-price-wins client to the back-tester so this exposure is reported every day.

### 4. Toxic tiers are under-charged, and production would charge them nothing

The 5-minute mid move after our fills is -0.31 bp of DV01 for tier 3 and -0.22 bp for tier 2 at C (AUDIT-01 seq 8 diagnostics). The engine charges 0.13 and 0.067 bp, the P5 fitting-period estimates at 1 minute.

Production is worse. `ToxicityTable` charges max(0, -EWMA markout), with the markout measured from the traded price (`QuotingEngine.java:675`), so the captured half-spread (about 0.84 bp for tier 3) hides an adverse move of 0.31 bp and the charge is zero.

Adding 0.10 bp to tier 3 (AUDIT-02 seq 9, widen_tier3) earns +93k at L (interval 59k to 130k), -12k at C and -8k at H. The pricing effect is small at these spreads. The definition is the bug, and fixing it is a one-line change of reference price. r2-business `MarkoutTiering` reads the same `ToxicityTable`, so it inherits the definition unless it is changed first.

The per-size split (adverse move -0.19 bp for tickets of 200mm and up, against -0.04 bp from 10mm to 200mm) is sampling noise here, because the generator draws informed status independently of size. r2-edge-research E4 (supported) is the place for real size effects.

### 5. Declining informed flow loses money

Declining exactly the informed RFQs, the best any toxicity detector could do, loses 486k a day at C (interval -1.33mm to +81k) and 1.13mm at L (interval -2.15mm to -358k) (AUDIT-02 seq 9). Declining tier 3 loses 638k at C (interval -1.38mm to -24k). At the engine's spreads informed flow still pays: the half-spread of about 0.84 bp exceeds the 0.31 bp adverse move. So blocked tiers and pass rules should stay off by default, and toxicity should be priced rather than refused. This depends on the informed share and horizon, which are declared assumptions (`usd_synthetic_market`).

### 6. Every CLOB quote is rejected

`ClobQuoter` sets its prices with `Decimal9.floor` and `ceil` of fair value ± 0.25 bp (`ClobQuoter.java:230-231`) and never rounds to the instrument tick. `ClobBook.java:66` rejects any price not on the tick grid. A price of fair ± 0.25 bp lands on a 1/128 or 1/256 point grid only by chance, so CLOB liquidity provision earns nothing today. This was established by code reading, not by a run.

Estimate of what is lost, with the assumptions stated: if 500mm a day is filled passively in each of the 2Y and 10Y notes, half a tick is 19.5 and 39 USD per 1mm, which gives about 29k USD a day gross before adverse selection. The fix takes half a day.

### 7. Inventory skew is missing in the back-tested engine and on the CLOB

Shifting the quote centre towards the risk-reducing side by 0.05 bp per 100k of net DV01 gives:
- +97k a day at C (interval -48k to +366k);
- +61k at L (interval -14k to +176k);
- -35k at H.

At 0.20 bp it gives +151k at L (interval 17k to 284k) and -143k at C (AUDIT-04 seq 11). The gain is real but the level matters, so the parameter should be tuned through `DeskBacktest` on the production engine, which has a covariance skew with kappa 2e-6, rather than in this simplified quoter. `ClobQuoter` has no skew at all. r2-business AxeBook covers the RFQ side.

### 8. Firm quotes are never repriced inside their time to live

In the back-tester the client decides 1.5 s after our quote. Over that window the mid moved in the client's favour by 31.7k a day net at C, and the favourable moves alone summed to 50.3k a day (AUDIT-01 seq 8 diagnostics). The simulated client trades whatever the move; a real client holding a firm quote can choose.

Production quotes are firm for 5 s and nothing withdraws them on a market move: `QuotingEngine` sees no book updates, and `poll` runs only on a new RFQ or a heartbeat. Estimate, scaling the positive part by the square root of 5 / 1.5: about 92k a day, 0.033 bp of client DV01, if clients exercised every favourable move. The fix is to withdraw or reprice on a fair-value move above a threshold. r2-wiring added the QuoteWithdraw message to the gateway; the engine trigger is missing.

### 9. Release windows earn nothing

Within 2 minutes of the 08:30 and 10:00 ET releases, the engine's client trades capture 36.7k a day of spread and lose 37.5k a day to the 5-minute move (C, AUDIT-01 seq 8). Pulling quotes from 60 s before to 30 s after (AUDIT-04 seq 11, release_guard) earns +20k at C (interval -14k to +70k), +21k at L and -3k at H.

Production has an event multiplier, but no events are configured by default (`QuotingConfig.java:133-154`). r2-edge-research E3 found release-window variance in public data (supported), so the calendar should be configured from its schedule. This is a config change and a test.

### 10. The fixed DV01 trigger is band-dependent

Against the 100k trigger:
- 25k loses at every point: -247k at C (interval -374k to -90k) and -357k at L (interval -504k to -249k);
- 250k loses 334k at C (interval -546k to -61k) but gains 214k at H (interval 24k to 446k);
- 1mm is inconclusive, with daily standard deviation rising from 2.82mm to 4.29mm at C (AUDIT-03 seq 10).

No fixed number is right across the band, which is the case for a cost-aware timing rule. r2-business OptimalStoppingTiming is that rule; this audit's thresholds are its baseline.

### 11. The cover is a point estimate

The optimiser evaluates the win probability at the expected minimum of the competitors' quotes. Averaging it over the cover distribution earns +69k a day at C (interval -50k to +214k, 25k per 1mm DV01) and +29k at L (interval -1k to +57k), and loses 5k at H (AUDIT-01 seq 8). This is a small, cheap correction. Production's `SpreadModel` uses an additive winner's-curse cost of 0.10·sqrt(2 ln n) instead, and the cover gap it observes (`meanCoverDistanceBp`) is computed but never fed back.

### 12. Package RFQs skip the controls

By code reading, `PackageQuoter` prices each leg at its own optimal spread, subtracts the netting saving and adds an inventory term. It skips the kill switch, input health, throttle, risk reservation, time to live and volatility and event widening that single RFQs pass through. It also hard-codes the liquid session to 1.0.

Estimate of the tail: a 50k DV01 curve package quoted on stale inputs and hit through a 2.5 bp release costs about 125k USD. The synthetic day carries no packages, so their revenue was not measured (see the coverage table).

### 13. A Done that arrives after local expiry is lost to risk

`validUntil` is the earlier of now plus 5 s and the RFQ expiry (`QuotingEngine.java:338`). `poll` frees the slot at `validUntil` (:437), and a later Done finds no slot and is counted in `unmatchedResponses` (:396). It never reaches `risk.onFill`, so the hedger does not hedge it, although `PositionKeeper` books it.

How often the venue's Done lands after the local expiry depends on the venue's decision time and network delay; this audit did not run it. Estimate of the tail: one unhedged 1,000mm 10Y fill is about 811k DV01, about 4.9mm USD of daily standard deviation at 6 bp a day. Handed to r2-review as a correctness item.

### 14. Limits and the kill switch obstruct risk reduction

By code reading:
- After any bucket, net or gross breach, headroom is zero for every instrument in that currency, and `checkQuote` has no risk-reducing exemption (`RiskEngine.java:349-386`). So client flow that would reduce risk is refused.
- Hedge orders count as risk-reducing on net DV01 only (:696), so a bucket breach with flat net DV01 blocks them.
- The daily-loss kill cancels hedge parents (`Hedger.java:228-235`).

r2-review's 2f5a41d and 4838726 fix neighbouring cases; these three are open. The cost is a tail cost, not a daily one.

### 15. The router's passive cost is optimistic

`Allocator` applies its adverse term (0.5σ√T) only to the unfilled branch and prices a filled passive child at the touch. It uses one σ of 0.5 points a day for every instrument and no message cost.

The passive_ty run shows what that misses: the hedge line improves by 0.40 bp per DV01 hedged (from -0.328 to +0.073), but unhedged inventory gives back more (AUDIT-03 seq 10, C). Estimate of the bias: half a TY tick (0.11 bp) of adverse selection on passively hedged DV01 (1.78mm a day at C in passive_ty), about 195k a day, which the router would book as savings.

### 16. One hedge instrument, unlimited basis

Hedging the 2Y and 5Y bucket in the 2Y note instead of the future (AUDIT-03 seq 10, bucketed) changes P&L by -80k at C (interval -308k to +142k) and does not lower the daily standard deviation (2.93mm against 2.82mm). In this simulator the curve moves almost in parallel (level volatility 0.35 against tenor-specific 0.06 bp per root minute), so the test has little to find. In the real market, hedging swaps with the future leaves swap-spread risk.

`RiskEngine` computes basis and swap-spread exposure but has no limit on either, and `Desk` wires `BasisAttribution.NONE`. Estimate: 100k DV01 of swap risk hedged in futures carries about 150k USD a day of swap-spread standard deviation at 1.5 bp.

### 17. Funding, balance sheet, margin and CCP basis

The back-tester's funding line is -3.4k to +0.8k a day (AUDIT-01 seq 8). It charges bond funding without coupon carry, so a net short bond position appears to earn the funding rate. Production prices none of funding, balance sheet, initial margin or the LCH-CME basis.

Estimate: initial margin on 500k DV01 of swaps held overnight, at about 30 bp of DV01 for a 5-day 99 percent move, is about 15mm USD; funding it at 50 bp costs about 200 USD a day. That is small for a desk that ends the day close to flat, and larger for one that warehouses. r2-business FundingPenalty and CouponCarry cover the pricing side; the CCP choice is not covered anywhere.

### 18. Message cost

The router always cancels and then sends a new order (there is no amend), the token bucket defaults to 1e9 a second, and the quote-stuffing detector counts Quotes and NewOrders but not cancels (`EodReportMain.java:79`). The fee cost is small. The exposure is to venue order-to-trade ratio policies, which nothing here monitors.

### 19. Response latency

Neither simulator makes win probability depend on response time at millisecond scale: the D2C simulator decides on price and uses time only as a tie-break and a 5 s cut-off. For RFQ that is a fair simplification. Latency matters for streaming and click-to-trade prices, which the platform does not offer (coverage table).

## Product and business coverage

Expected value is stated relative to the engine's central P&L where the simulator allows it, otherwise as an order of magnitude from stated assumptions; every figure in this table is an estimate. Plan-days cover the first useful version with a back-test.

| Line of business | Status | What exists | Expected value of adding or finishing it | Plan-days |
|---|---|---|---|---:|
| RFQ market making, swaps | Covered | QuotingEngine, hit-rate spread, tiers, skew, back-tested through model_spread | Items 1 to 11 above: about 0.1 to 0.35 bp of client DV01 recoverable | (items above) |
| RFQ market making, Treasuries | Covered | Same engine; UST 2Y and 10Y in the synthetic day | As for swaps; repo specials and on-the-run versus off-the-run not modelled | 3 for repo specials |
| CLOB liquidity provision | Partly | ClobQuoter with refresh, imbalance and futures-move pulls; venue workup | Zero today (item 6); about 10k to 30k USD a day gross at 500mm a day per Treasury, net of adverse selection unknown; workup participation not handled | 3 (tick fix, skew, toxicity, workup) |
| Curve and spread packages | Partly | PackageQuoter (leg sum less netting) | Packages are not in the synthetic flow. Assuming they add 25 percent to swap RFQ DV01 at the engine's margin (swaps are about two thirds of its flow): about 0.35mm USD a day (C units), plus removal of the item 12 tail | 5 (controls, net-risk pricing, synthetic package flow) |
| Futures basis and roll | Missing | TY as hedge only; no roll, no CTD or basis model, BasisAttribution.NONE | Roll of the hedge book four times a year at about 0.25 tick a contract is a cost to manage; basis trading is a separate business | 6 |
| Auction cycle flow | Missing | None in the engine | r2-edge-research E1 (pre- and post-auction concession) not supported on its holdout: little systematic value found in public data | 2 only if E1 is revisited |
| Month-end and index flow | Missing | None | r2-edge-research E2 not supported; value is in liquidity at the fix, not a curve signal; size unknown | 3 |
| Client segmentation and axes | Partly | Static tiers with propose/approve, per-client toxicity, axe source in the hedger | Item 4 fix plus r2-business MarkoutTiering and AxeBook; items 4, 5 and 7 bound it at about 0.1 to 0.2mm USD a day (C units) | r2-business |
| Compression and portfolio trades | Missing | None | Balance-sheet and margin release, plus list-trade revenue; needs net-risk pricing first (packages) | 10 |
| Cross-currency hedging | Missing | Hedge universe single-currency; one covariance used for every currency (Desk.java:72) | Needed only when EUR or GBP flow is quoted; until then none | 8 |
| Funding and balance sheet | Partly | Back-tester bond funding without coupon carry; FailCost in posttrade | r2-business FundingPenalty and CouponCarry; item 17 | r2-business |
| Streaming and click-to-trade prices | Missing | None | Where latency and last look matter (item 19); large on electronic Treasury venues, not estimated here | 10 |

## Fix packages for the next round

The fixes above, grouped into four packages that share no source file, so four people can work on them at the same time.

- **Shared documents.** Edits to API_SPEC.md, DECISIONS.md, CHANGELOG.md and STATUS.md go in each package's hand-over note and are applied by the coordinator at merge, so no package edits a shared document.
- **Not in any package.** Items 1 and 10 (hedge netting and timing) are r2-business's rates-strategy work. Item 19 needs nothing.
- **Plan-days** are the sums of the item estimates above.

| Package | Items | Files it owns (and no other package touches) | Plan-days | Done when |
|---|---|---|---:|---|
| A. RFQ pricing and quote lifecycle | 2, 3 (recording side), 4, 5, 8, 9, 11, 12, 13 | rates-quoting: QuotingEngine.java, QuotingConfig.java, QuotingEventHandler.java, HitRateModel.java, SpreadModel.java, ToxicityTable.java, RfqOutcome.java, PassReason.java, PackageQuoter.java, resources/quoting-hit-rate-default.yaml, and their tests | 15 | Slope learned outside [-2.4, -1.0] with a daily report of the learned value; cover and rank recorded on every RFQ, including passes and losses; toxicity marked from mid at trade; live quotes withdrawn or repriced on a fair-value move above a configured threshold; release calendar on by default; win probability averaged over the cover distribution; packages pass the kill, health, throttle, reservation and time-to-live checks; a late Done still reaches risk |
| B. CLOB quoting | 6, 7 (CLOB side) | rates-quoting: ClobQuoter.java and its test | 3 | Every CLOB price on the venue tick (bid down, ask up); inventory skew on both sides with the RFQ engine's kappa; a test against ClobBook's tick rule |
| C. Risk, hedge, router and surveillance controls | 14, 15, 16, 18 | rates-risk: RiskEngine.java, RiskLimits.java; rates-hedge: Hedger.java, a BasisAttribution implementation; rates-router: Allocator.java, RouterParams.java, RouterPresets.java; rates-posttrade: QuoteStuffingDetector.java, EodReportMain.java; rates-quoting: Desk.java (wiring only); their tests | 7 | Risk-reducing quotes and hedges allowed through a breach; the daily-loss kill keeps flattening hedges; basis and swap-spread limits and attribution wired; adverse term on filled passive children and per-instrument volatility; cancels counted by the stuffing detector and costed by the router. Start from r2-review's branch after it merges, because r2-review changed RiskEngine, Hedger and Allocator |
| D. Back-tester realism and the audit re-run | 3 (back-test side), 17 (carry), and the HedgeCostModel note | rates-backtest: model/HedgeCostModel.java, model/RfqOutcomeModel.java, BacktestRunner.java, pnl/PositionBook.java, pnl/CarryModel.java; new research/prereg/AUDIT-1n files and ledger rows | 5 | Market hedges filled level by level against displayed depth; an optional best-price-wins client; bond coupon carry next to funding; AUDIT-01 to AUDIT-04 re-run through rates-app DeskBacktest (after r2-wiring merges) under new pre-registration ids. Public signatures stay compatible, because rates-app's DeskBacktest builds BacktestConfig |

## Follow-ups for other owners

- **r2-business.** Item 1 is the prize for NettingHedgeBudget and OptimalStoppingTiming: up to 704k USD a day at C, 0.25 bp of client DV01. Item 10 is their threshold baseline. MarkoutTiering inherits item 4's markout definition.
- **r2-review.** Items 6, 13 and 14 are correctness defects found by reading; none has a test yet.
- **After the r2-wiring merge.** Re-run AUDIT-01 to AUDIT-04 through `DeskBacktest` to size items 1 to 11 on the production engine, under new pre-registration ids.
- **Back-tester owner.** In `HedgeCostModel`, any positive impact rounds a market hedge one full tick beyond the touch. That is fair for 1,400-lot blocks, but it overstates the cost of small hedges, and a sliced hedger will look worse than it is until the model fills by displayed depth.

## Production-engine re-run (audit fix package D, AUDIT-11 to AUDIT-14)

Added 2026-10-31 on branch `claude/fix-d-backtest-cdd6aa`. The DR-008 caveat applies to every number here: these are simulator consistency checks, not edge.

### What changed in the back-tester first

- **Hedge fills by displayed depth.** `HedgeCostModel.withDepthWalk(true)` fills a market order against the touch's displayed quantity, then the same quantity one tick further out per level, up to `max_impact_ticks` beyond the touch, at the quantity-weighted price. The books carry only the top level, so each deeper level is assumed to show the touch's quantity. The rule before package D (any positive impact rounds one full tick beyond the touch) stays the default of `fromPreset`, because P5-001 and AUDIT-01 to AUDIT-04 must still reproduce from their ledger rows (`FirstExperimentTest` pins P5-001). Switching the default is a decision for DECISIONS.md, not this package.
- **Best-price client.** `RfqOutcomeModel.ClientChoice.BEST_PRICE` wins only when our spread is inside the cover; a tie is decided on the uniform draw. It uses the same random draws as the hit-rate client, so the two compare on common random numbers. The default is unchanged (`HIT_RATE`).
- **Bond coupon carry.** `CarryModel.bondCoupons` accrues each bond's coupon (ACT/365, on face) on the carry line next to funding. `BacktestConfig.fromPresets` still uses `CarryModel.NONE`.
- **Tests.** `FixPackageDTest` covers all three rules, including two end-to-end runner checks.

### How the re-run was made

- **Engine.** rates-app's `DeskStrategy` runs the production QuotingEngine, RiskEngine, Hedger and routers, replayed by the P5 back-tester as `DeskBacktest.run` does. The harness is `research/audit/DeskAudit.java`, launched by `run_desk_audit.sh`.
- **Variants.** They change only what the production code exposes:
  - the desk's live `QuotingConfig` (half-spread clamp, kappa, skew cap, event multiplier);
  - the `DeskSettings` record (hedge interval, lots per plan);
  - the back-tester's client rule, hedge fill rule and carry model.
- **Baseline ("engine").** `DeskSettings.defaults()` with depth-walk hedge fills, coupon carry and the hit-rate client.
- **Sessions.** 20 recordings of rates-app's `TradingProcess`, the D2C RFQ and CLOB simulators with the production desk in the loop, driven single-threaded. Each runs 15 simulated minutes from 08:25 New York on 2026-11-02, so it spans the 08:30 release. The seed is 8,000,000 plus the epoch day of the labels 2026-03-02 to 2026-03-27 (8,020,514 to 8,020,539). The outcome seed is 7. Each session holds 53.25 RFQs on average.
- **Scope of a session.** A session is 15 minutes, not a day, because one replay of the desk costs about 3 s of CPU per simulated minute.
- **Statistics.** As in AUDIT-01 to AUDIT-04: paired session differences with a 95 percent moving-block bootstrap interval (blocks of 2, 2,000 resamples, seed 11), and Holm-adjusted Welch p. Every registered verdict is "inconclusive" except one, `unclamped` at L. Where an interval excludes zero, the text says so.
- **Determinism.** A pilot on fitting-period labels gave identical session P&L with 10 and 3 replay threads.
- **Attribution.** The residual was below 1e-5 USD on every run.
- **Code commit.** Every run used commit 04edc03, which comes before b4658da (DR-INT-05, `winnersCurseBp` 0.02 in `DeskSettings`). The production engine has changed since these runs. Re-run AUDIT-11 to AUDIT-14 under new ids before using these sizes for planning.
- **Ledger rows.** Seq 19 (AUDIT-11), 20 (AUDIT-12), 21 (AUDIT-13) and 22 (AUDIT-14). They were seq 12 to 15 when recorded and were re-chained after R2B-001 to R2B-007 when round2-integration was merged, with only `seq` and `prev_sha256` changed, as in DR-INT-04. The copies in `research/audit/results/AUDIT-1n.json` are the rows as recorded.
- **The `code_dirty` flag.** The AUDIT-12 to AUDIT-14 rows record `code_dirty: true` only because the ledger file itself had been appended to by the previous row. AUDIT-02 to AUDIT-04 carry the same flag for the same reason.

### The production engine against model_spread

The two engines ran on different days, so absolute USD values are not comparable. The comparison is of signs and of sizes per 1mm USD of client DV01. Engine figures are at C.

| Measure | model_spread (AUDIT-01, synthetic day) | production desk (AUDIT-11, 15-min session) |
|---|---|---|
| P&L | +2.22mm USD/day, +799k per 1mm client DV01 | -72k USD/session (interval -122k to -22k), -129k per 1mm client DV01 |
| Spread capture per 1mm DV01 | about 788k | 120k (half-spread clamped to [0.02, 0.08] bp) |
| Hedge cost per DV01 hedged | 0.328 bp (blocks, linear rule) | 0.089 bp (depth walk); 0.174 bp under the linear rule |
| Hedge orders | one block above 100k net DV01 | 403 child orders a session for 27 client fills |
| Won trades against the cover (DV01-weighted) | 0.44 bp behind | 0.09 bp inside |
| Engine P&L across the band, L / C / H | 3.45mm / 2.22mm / 0.97mm | -82k / -72k / -105k |

The production desk at its default settings loses money in every band point. Its clamp keeps it inside the cover, so it wins about half of what it quotes at a spread too thin to pay for the 5-minute adverse move (client markout -51k a session at C) and its hedging. The loss is consistent across the band because the clamp, not the hit-rate slope, sets the price. Under DR-008 this is a statement about the default settings in this simulator, not about the market.

### Item by item, production sizes against the original ones

All differences are paired means per session at C unless stated, with 95 percent intervals.

| Item | Original (model_spread) | Production desk | Reading |
|---|---|---|---|
| 1, 10: hedge execution | -704k/day, 0.328 bp per DV01 hedged; 250k trigger -334k | Hedge line -49k a session, 0.089 bp per DV01 hedged. Measuring the residual every 5 s instead of 250 ms cuts orders from 403 to 120 a session but changes P&L by -7k (-23k to +2k). At most 100 lots per plan: -0.6k (-1.2k to +0.1k). 2,000 lots: identical to the engine, because the 500-lot cap never binds | The sliced production hedger pays about a quarter of the block cost per DV01. Hedging less often does not help in a 15-minute session |
| Cost-model bias (the HedgeCostModel note) | the linear rule charged blocks about a full sweep | The linear rule lowers P&L by 48k a session (-55k to -41k) at C, -46k at L, -51k at H. It doubles the hedge line (0.174 against 0.089 bp) | The note is confirmed. Under the old rule a sliced hedger looks twice as expensive as it is |
| 2, 11: slope and spread setting | slope_known H +949k; cover_integrated C +69k | With the clamp opened to [0, 2] bp, the hit-rate optimiser earns +128k at C (73k to 181k), +176k at L (128k to 226k; supported, Holm p 0.002) and +105k at H (the desk then quotes outside the cover and trades nothing). Raising only the cap to 0.25 bp: +63k at C (27k to 95k), +76k at L, +83k at H | The largest lever on the production engine is the [0.02, 0.08] bp clamp in DeskSettings, not the slope |
| 3: wins behind the cover | wins 0.44 bp behind cover; P&L falls 72 percent from L to H | A best-price client lowers P&L by 64k at C (-139k to -2k), 55k at L (-138k to +9k) and 32k at H (-92k to +22k). The desk wins inside the cover already, so the loss comes from the trades it still does: hedge orders rise from 403 to 642 and the hedge line from -49k to -75k | The production desk does not live on clients accepting worse prices. Its exposure to price-driven clients is about -114k per 1mm DV01, through hedging rather than lost volume |
| 4, 5: adverse flow | widen_tier3 L +93k; declining informed loses | Widening every client by 0.05 bp: +6k at C (-23k to +34k), +25k at L (12k to 41k), +22k at H (2k to 46k). By 0.10 bp: +33k at C (4k to 63k), +42k at L (32k to 53k), +56k at H (21k to 93k). The decline variants could not be expressed: there is one tier, and the simulator labels no informed clients | Widening helps at every point. This is the same clamp finding seen from the other side |
| 7: inventory skew | skew_0p05 C +97k (-48k to 366k) | Skew off: -40k at C (-59k to -25k), -23k at L, -48k at H. Skew 4x (kappa 8e-6, cap 0.20 bp): +31k at C (4k to 53k), +60k at L (39k to 78k), +75k at H (-1k to +148k) | The production skew pays. A stronger skew pays more at every point; the intervals exclude zero at L and C |
| 9: release guard | +20k at C (-14k to +70k) | Event multiplier 4 from 60 s before to 30 s after 08:30: exactly 0 at every point | The multiplier acts before the clamp, and the desk already quotes at the 0.08 bp cap, so widening for a release has no effect while the clamp is in place |
| 17: coupon carry | not modelled; funding -3.4k to +0.8k/day | Carry +8 USD a session at C (-1 at L, +22 at H); funding -12. no_coupon_carry differs by less than 25 USD | Small, because the desk ends a 15-minute session nearly flat in bonds. It matters for a desk that warehouses overnight, which these sessions do not test |

### What this changes in the ranked table

- **The clamp is a new top item.** `DeskSettings.quoting()` holds the half-spread in [0.02, 0.08] bp. On the production engine, opening it is the largest measured gain (+128k a session at C). Opening it also restores the effect of the event multiplier, so item 9 depends on it. The clamp belongs to rates-app (r2-wiring, DR-R2W-09), not to package A.
- **Item 1 shrinks.** Most of its size was model_spread's block hedge priced by the linear rule. On the sliced production hedger with depth-walk fills it is 0.089 bp per DV01 hedged.
- **Item 3's risk moves.** The production desk does not depend on loyal clients the way model_spread does. A best-price client costs it through extra hedging, not through lost volume.
- **Item 7 is confirmed on production** with the existing covariance skew, and a stronger setting looks better.
- **Re-run before planning.** These runs predate DR-INT-05 (b4658da). Re-run them under new ids before any size here is used for planning.
