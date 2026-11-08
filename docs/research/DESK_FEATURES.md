# Desk features: internalisation, axes, sizing, tiering, hedge timing, funding, desk view

Module `rates-strategy` (package `io.ratesalgo.strategy`), API_SPEC.md Section 19, decisions DR-R2B-01 to DR-R2B-10. Every number on this page comes from the ledger records R2B-001 to R2B-007 (`data/ledger/ledger.jsonl`, sequence numbers 12 to 18 (2 to 8 on the business branch before the round 2 integration, DECISIONS.md DR-INT-04), recorded 2026-10-31T14:14:02Z from commit `8c4eacb`), unless the sentence names another test.

**Caveat that applies to every result below.** These are simulator consistency checks, not evidence of edge (DR-008). The competitors, the win-probability slope and the informed-flow share are declared assumptions, so each result is reported at three points of a declared band, and a result that holds only at some points is reported as such. Sessions are synthetic (`SyntheticDay`), the hedge instrument is the 10-year note future only, and 20 sessions per band point give low power against inventory noise.

## How the experiments ran

- Baseline: today's engine, the hit-rate-optimal quoter of P5-001 (`model_spread`: adverse selection by tier 0.0241, 0.0667, 0.1300 bp; hedge cost 0.2459 bp; grid 0.01 bp up to 2.0 bp) hedging all net DV01 into the note future once it exceeds 100,000 USD per bp. `DeskQuoter` with every feature at its default sends the same messages as `ModelSpreadStrategy` byte for byte (`DeskQuoterBaselineTest`, session 2026-02-17, central band point).
- Treatments: one feature switched on over the baseline (R2B-001 to R2B-006), and all six together (R2B-007).
- Sessions: the 20 business days 2026-03-02 to 2026-03-27; journal seed 6,000,000 plus the epoch day; outcome seed 7, so every strategy faces the same competitors and the same uniform draw on each RFQ (common random numbers). Parameters were chosen on the fitting period 2026-02-16 to 2026-02-27 before the pre-registrations were written and committed (`8c4eacb`). The holdout 2026-04-06 to 2026-04-10 has not been opened.
- Assumption band (config `data/experiments/R2B.config.yaml`, DR-R2B-03): win-probability slope per bp of spread-to-cover -1.25, -2.5, -5.0 (preset `usd_rfq_hit_rate`); dealers per RFQ 2 to 4, 2 to 5, 3 to 6; informed-flow share of every tier times 0.5, 1.0, 1.5 (central: 5, 20, 45 percent for tiers 1 to 3). Low is a benign market, high a harsh one.
- Statistics: per band point, a paired t-test of the daily difference of the pre-registered metric (oriented so positive is better), Holm-adjusted over all 21 tests (7 treatments times 3 points), and a 95 percent moving-block bootstrap interval of the mean difference (blocks of 2 sessions, 2,000 resamples, seed 11). A point is improved when the adjusted p is below 0.05 and the interval lies above zero. A feature is supported when improved at all three points, and adopted as the default only if supported and, at every point, it adds no limit breach on a session where the baseline had none (net DV01 above 1,000,000 USD per bp, or a position above 2,000 million) and the interval of the daily P&L difference is not entirely below zero.

## Results

Verdicts:

| Pre-registration | Feature | Metric | Verdict | Guards | Adopted as default | Ledger seq |
|---|---|---|---|---|---|---:|
| R2B-001 | internalisation | hedge_slippage | supported | pass | yes | 12 |
| R2B-002 | axes | inventory_half_life_s | not supported | fail | no | 13 |
| R2B-003 | sizing | capture_per_dv01 | not supported | pass | no | 14 |
| R2B-004 | tiering | adverse_selection | not supported | pass | no | 15 |
| R2B-005 | timing | risk_adjusted_hedge_cost | not supported | fail | no | 16 |
| R2B-006 | funding | funding_net | not supported | pass | no | 17 |
| R2B-007 | desk_all | pnl_per_dv01 | not supported | fail | no | 18 |

Per band point. Baseline and feature are means over the 20 sessions; improvement is the mean oriented difference with its interval; the P&L column is the mean daily P&L difference (feature minus baseline) with its interval.

| Pre-registration | Feature | Metric | Band point | Baseline | Feature | Improvement (95% interval) | Holm p | Point verdict | P&L difference per day, USD (95% interval) | New limit breaches |
|---|---|---|---|---:|---:|---|---:|---|---|---:|
| R2B-001 | internalisation | hedge_slippage, USD | low | -1,207,890 | -914,932 | 292,958 (244,172 to 346,752) | 0.0000 | improved | 334,517 (-70,978 to 708,617) | 0 |
| R2B-001 | internalisation | hedge_slippage, USD | central | -749,484 | -612,631 | 136,853 (107,323 to 174,729) | 0.0001 | improved | -241,541 (-622,409 to 151,464) | 0 |
| R2B-001 | internalisation | hedge_slippage, USD | high | -200,736 | -157,620 | 43,116 (24,300 to 63,788) | 0.0052 | improved | -31,668 (-284,762 to 258,567) | 0 |
| R2B-002 | axes | inventory_half_life_s, s | low | 871.2 | 845.6 | 25.6 (8.4 to 49.1) | 0.4251 | inconclusive | 222,746 (-169,140 to 904,656) | 2 |
| R2B-002 | axes | inventory_half_life_s, s | central | 1,675 | 1,547 | 128.4 (10.0 to 218.0) | 0.6872 | inconclusive | 7,564 (-145,180 to 232,110) | 0 |
| R2B-002 | axes | inventory_half_life_s, s | high | 8,132 | 8,009 | 123.0 (-227.3 to 498.0) | 1.0000 | inconclusive | -27,816 (-69,412 to 3,385) | 0 |
| R2B-003 | sizing | capture_per_dv01, bp | low | 0.7969 | 0.8175 | 0.0206 (0.0111 to 0.0314) | 0.0100 | improved | -881,570 (-2,939,440 to 617,579) | 0 |
| R2B-003 | sizing | capture_per_dv01, bp | central | 0.7857 | 0.7982 | 0.0125 (-0.0009 to 0.0233) | 0.4251 | inconclusive | -961,811 (-2,076,904 to 34,426) | 0 |
| R2B-003 | sizing | capture_per_dv01, bp | high | 0.7815 | 0.7808 | -0.0007 (-0.0201 to 0.0154) | 1.0000 | inconclusive | -44,275 (-648,775 to 567,469) | 0 |
| R2B-004 | tiering | adverse_selection, USD | low | -38,315 | -40,387 | -2,072 (-7,312 to 6,048) | 1.0000 | inconclusive | 25,795 (-44,121 to 107,331) | 0 |
| R2B-004 | tiering | adverse_selection, USD | central | -94,568 | -98,335 | -3,767 (-69,057 to 53,049) | 1.0000 | inconclusive | 25,420 (-202,826 to 395,982) | 0 |
| R2B-004 | tiering | adverse_selection, USD | high | 6,659 | 5,911 | -748 (-6,999 to 6,302) | 1.0000 | inconclusive | -109,940 (-261,723 to 49,331) | 0 |
| R2B-005 | timing | risk_adjusted_hedge_cost, USD | low | -1,264,524 | -1,109,751 | 154,773 (114,435 to 202,910) | 0.0001 | improved | 351,350 (59,781 to 579,667) | 0 |
| R2B-005 | timing | risk_adjusted_hedge_cost, USD | central | -810,703 | -762,487 | 48,215 (25,995 to 71,085) | 0.1403 | inconclusive | -315,295 (-565,302 to -32,930) | 0 |
| R2B-005 | timing | risk_adjusted_hedge_cost, USD | high | -265,686 | -274,993 | -9,307 (-33,577 to 26,115) | 1.0000 | inconclusive | 29,199 (-156,139 to 250,385) | 0 |
| R2B-006 | funding | funding_net, USD | low | -2,826 | -1,723 | 1,103 (603 to 1,781) | 0.1403 | inconclusive | 72,242 (-27,614 to 193,906) | 0 |
| R2B-006 | funding | funding_net, USD | central | -1,018 | -729 | 288 (186 to 387) | 0.0003 | improved | -38,838 (-104,493 to 43,636) | 0 |
| R2B-006 | funding | funding_net, USD | high | -265.8 | -221.7 | 44.0 (11.5 to 71.9) | 0.6866 | inconclusive | -39,208 (-74,381 to 1,433) | 0 |
| R2B-007 | desk_all | pnl_per_dv01, bp | low | 0.6082 | 0.8668 | 0.2587 (-0.1033 to 0.5845) | 1.0000 | inconclusive | -278,162 (-2,348,157 to 1,262,315) | 0 |
| R2B-007 | desk_all | pnl_per_dv01, bp | central | 0.8573 | 0.6056 | -0.2517 (-0.6084 to 0.1036) | 1.0000 | inconclusive | -1,295,275 (-2,526,629 to -263,247) | 0 |
| R2B-007 | desk_all | pnl_per_dv01, bp | high | 1.5034 | 0.9265 | -0.5769 (-1.6906 to 0.4318) | 1.0000 | inconclusive | -284,586 (-1,087,349 to 396,124) | 0 |

Secondary means at the central point (baseline, then feature), from the same records:

| Feature | Hedge orders per day | Mean absolute net DV01 | Inventory half-life, s | Spread capture, USD | Client DV01 traded | Largest net DV01 / limit | P&L, USD |
|---|---|---|---|---|---|---|---|
| internalisation | 6.65, 5.85 | 29,849, 66,313 | 1,675, 4,485 | 2,189,103, 2,189,103 | 2,784,028, 2,784,028 | 0.779, 0.777 | 2,220,313, 1,978,772 |
| axes | 6.65, 7.35 | 29,849, 29,383 | 1,675, 1,547 | 2,189,103, 2,220,741 | 2,784,028, 2,957,872 | 0.779, 0.787 | 2,220,313, 2,227,876 |
| sizing | 6.65, 5.15 | 29,849, 31,106 | 1,675, 1,961 | 2,189,103, 1,229,616 | 2,784,028, 1,537,990 | 0.779, 0.267 | 2,220,313, 1,258,502 |
| tiering | 6.65, 7.00 | 29,849, 29,427 | 1,675, 1,605 | 2,189,103, 2,260,953 | 2,784,028, 2,881,168 | 0.779, 0.794 | 2,220,313, 2,245,733 |
| timing | 6.65, 20.70 | 29,849, 54,889 | 1,675, 4,127 | 2,189,103, 2,189,103 | 2,784,028, 2,784,028 | 0.779, 0.780 | 2,220,313, 1,905,018 |
| funding | 6.65, 6.65 | 29,849, 28,921 | 1,675, 1,600 | 2,189,103, 2,164,118 | 2,784,028, 2,744,484 | 0.779, 0.775 | 2,220,313, 2,181,475 |
| desk_all | 6.65, 7.30 | 29,849, 78,576 | 1,675, 6,042 | 2,189,103, 1,208,850 | 2,784,028, 1,551,336 | 0.779, 0.286 | 2,220,313, 925,038 |

The baseline breaches its own limits on some sessions (at least 12 of 20 at the low point, 7 at central and 3 at high: the internalisation run breached on those sessions and added none). The baseline hedges only after a fill, so one large RFQ is enough: a 1,000 million 30-year swap, the size cap of the declared size distribution, is about 1.7 million USD per bp. That is why the limit guard counts only breaches the baseline did not have (DR-R2B-04).

## Feature 1, internalisation and netting (R2B-001)

**Finding.** Holding client risk for 120 seconds and hedging only beyond a budget cut hedge slippage at all three band points, by 24, 18 and 21 percent (low, central, high), and passed both guards, so it is adopted as the desk default (DR-R2B-07). It roughly doubles mean absolute net DV01 (29,849 to 66,313 USD per bp at central) and the daily P&L difference has a negative mean at central and high with intervals that include zero; the P&L guard is a non-inferiority screen, not evidence that P&L is unchanged.

**Design.** Every client fill lands in the `RiskLadder` (net DV01 and the 2Y, 5Y, 10Y, 30Y buckets across swaps, Treasuries and the future), where opposing flow nets before anything is hedged. `NettingHedgeBudget` hedges `X - sign(X) * soft` at once when `|X|` exceeds the hard budget, and `X - sign(X) * retain` once `|X|` exceeds the soft budget and no client fill has arrived for the holding time. The default `ThresholdHedgeBudget` hedges all of `X` once `|X|` exceeds the threshold.

**Parameters.** Soft 150,000, hard 400,000, retain 50,000 USD per bp; hold 120 s.

## Feature 2, axe management (R2B-002)

**Finding.** Axes shortened the median inventory half-life by 2.9, 7.7 and 1.5 percent (low, central, high), with no point significant after Holm, and added a limit breach on 2 low-point sessions that the baseline did not have. Not supported, not adopted. With the baseline hedging at 100,000 USD per bp, exposures rarely reach the 150,000 budget at which the axe is at its full 0.10 bp, so the improvement is small.

**Design.** `AxeBook` implements the hedger's existing `AxeSource` interface (`rates-hedge`). For instrument `i` and our side, the targeted exposure is `e = (1 - w) * netDv01 + w * bucketDv01(i)`; if trading that side reduces `e`, the price improves by `maxBp * min(1, |e| / budget)`, and the axe size is `|e| / |dv01PerUnit(i)|`. `publish()` returns the axe list for display. No SBE message was added: the axe list is read in-process by the quoting engine through `AxeSource`, and nothing outside the process consumes it yet (DR-R2B-06).

**Parameters.** Largest improvement 0.10 bp, budget 150,000 USD per bp, bucket weight 0.5.

## Feature 3, quote sizing (R2B-003)

**Finding.** Sizing from the risk envelope raised spread capture per DV01 traded at the low point (0.0206 bp, interval 0.0111 to 0.0314) but not significantly at central or high. It cut client DV01 traded by 45 percent at central and lowered largest-net-DV01 utilisation from 0.78 to 0.27, at a mean P&L cost of 961,811 USD per day at central (interval -2,076,904 to 34,426). Not supported, not adopted.

**Design.** `EnvelopeSizer` quotes the full size of risk-reducing requests; declines a risk-adding request that would take absolute net DV01 above `hardUtil` of the limit; and otherwise adds a premium `riskPremiumBp * ((u - softUtil) / (hardUtil - softUtil))^2` plus `blockPremiumBp * ln(size / blockSize)` for sizes above a quantile of the declared size distribution (preset `usd_rfq_flow`, lognormal). The premium enters the hit-rate optimiser as a cost, so the hit-rate model decides how much of it to pass on. The back-tester settles the full requested size only, so "a smaller size" can only mean a decline (DR-R2B-09).

**Parameters.** Limit 1,000,000 USD per bp, soft 10 percent, hard 50 percent, risk premium 0.20 bp, block quantile z 1.6449 (95th percentile of the swap size distribution, about 353 million), block premium 0.05 bp.

## Feature 4, client analytics loop (R2B-004)

**Finding.** Learned tiers did not change adverse selection cost at any band point (all Holm p equal 1.0). This is the expected outcome in this simulator: the baseline's static table is exactly the tier structure the synthetic market was generated with, and clients within a tier are identical, so the test checks that learning costs little against an oracle table, and it does. Not supported, not adopted.

**Design.** `MarkoutTiering` keeps an exponentially weighted mean of each client's 60 s markout in bp (`ToxicityTable` from `rates-quoting`) and moves a client one tier down when the mean falls below the tier's demotion threshold and one tier up when it rises above the promotion threshold, only after a minimum number of fills; the thresholds overlap by 0.01 bp so a client on a boundary does not flap. A changed assignment becomes a new `TierTable` version proposed by one id and approved by another through the existing `TierTableControl`, at most once per review interval. The learned state is carried across sessions in date order at each band point.

**Parameters.** Demote below -0.045 and -0.098 bp; promote above -0.035 and -0.088 bp; minimum 30 fills; weight 0.05; start in tier 2; review every 1,800 s; proposer 9001, approver 9002.

## Feature 5, hedge timing (R2B-005)

**Finding.** The stopping rule lowered the risk-adjusted hedge cost at the low point (154,773 USD per day, interval 114,435 to 202,910) but not significantly at central or high, and at central its daily P&L difference interval lies entirely below zero (-565,302 to -32,930), so the P&L guard fails. Not supported, not adopted. It more than triples hedge orders (6.65 to 20.70 a day at central) because it often slices.

**Design.** `OptimalStoppingTiming` is a one-step look-ahead rule. With `a = |X|`, it waits one step `dt` while the expected saving `c * min(a, lambda * dt)` from offsetting client flow (rate `lambda` from `FlowRateEstimator`) beats the variance charge `gamma / 2 * sigma^2 * a^2 * dt` and the maximum wait is not reached; otherwise it slices into `n` orders when the impact saved, `k * a^2 * (1 - 1/n)`, beats the extra variance of the slower hedge; otherwise it hedges at once. `c` and `k` come from the hedge cost model (half the futures spread plus the fee per DV01: 0.1302 USD per USD of DV01; impact 6.11e-7 USD per DV01 squared). The metric uses the same `gamma`, so the rule and its test agree on the objective.

**Parameters.** Gamma 1e-6 per USD; sigma 0.35 / sqrt(60) bp per sqrt(second) (preset `usd_synthetic_market`); flow time constant 900 s, prior 50 USD per bp per second; wait step 30 s, at most 300 s; 3 slices 20 s apart.

## Feature 6, funding and balance sheet (R2B-006)

**Finding.** The funding penalty improved net funding at the central point (288 USD per day, interval 186 to 387) but not significantly at low or high after Holm. Not supported, not adopted. The amounts are small next to the other lines: the baseline's balance-sheet charge is about 1,100 USD per day at central.

**Design.** `FundingPenalty` charges, in bp of the trade's DV01, repo funding net of coupon carry plus a balance-sheet charge on the change in gross inventory over an expected holding period, and credits trades that reduce inventory. The experiment books coupon carry for the two Treasuries through the back-tester's `CarryModel` hook (`CouponCarry`, coupons 3.60 and 3.95 percent against the book's 4.25 percent repo), so bond inventory is carry-negative long and carry-positive short. The metric is funding plus carry minus the balance-sheet charge at the same rates.

**Parameters.** Balance-sheet charge 0.5 percent a year on bond value and 0.05 percent on swap notional; holding period 5 days (changed from 1 on the fitting period, where the central-point effect was about 1 USD a day).

## Feature 7, the desk P&L view and all features together (R2B-007)

**Finding.** All six features together did not raise P&L per DV01 traded at any band point, and at central the daily P&L difference interval lies entirely below zero (-2,526,629 to -263,247 USD), driven by sizing's declines and timing's slower hedging. Not supported, not adopted.

**Design.** `DeskMetrics` and `DeskReport` (`desk_report.json`, `desk_report.md`, `DeskReportCli`) give, per session: P&L per DV01 traded; the waterfall spread captured, adverse selection (mid move over 60 s after each client fill), other inventory P&L, hedge cost, carry, funding and fees, which sums to position P&L less the attribution residual (`DeskReportTest`, to the cent, session 2026-02-19); hit rate by tier, product and tenor bucket; median inventory half-life; hedge ratio (hedged DV01 over client DV01); and the balance-sheet charge beside funding. The existing `DailyReport` has no section extension point, so the desk section is written next to it (DR-R2B-09). Diagrams: "Internalisation flow" and "Desk P&L waterfall" on the diagrams page.

## Hedge execution after internalisation (V1H-001, V1H-002)

Added 2026-11-01 (DR-V1H-01 to DR-V1H-03). Records V1H-001 and V1H-002 are ledger seq 23 and 24, from code c9d911e, with copies in `research/hedge/results/`. The config is `data/experiments/V1H.config.yaml`: the R2B config with day seed base 9,000,000 and two new sections. Market hedges fill by the depth walk of audit fix package D and resting hedges by the queue model. Holm runs over 6 tests. The DR-008 caveat applies: these are simulator consistency checks, not edge.

| Pre-registration | Treatment against | Metric, USD per session | Low | Central | High | Verdict |
|---|---|---|---|---|---|---|
| V1H-001 | sliced, against netting with one market order | hedge shortfall | +446,304 (350,454 to 560,595) | +300,077 (200,750 to 402,994) | +70,935 (39,865 to 109,365) | supported, adopted |
| V1H-002 | cost-aware trigger, against fixed 100,000 | risk-adjusted shortfall | -6,517 (-38,223 to 26,037) | +33,677 (18,323 to 57,939) | +26,796 (7,924 to 50,805) | not supported |

**Slicing (V1H-001).** `DeskQuoter(name, features, HedgeSlicing)` hedges the budget's excess in children. Each child is `depth_fraction` (1.0) times the touch quantity on the side a market order would take, and at least 10 lots. It is placed first as a limit order at our own touch, then cancelled and sent as a market order after 5 s. While net DV01 is above 400,000 it goes as a market order at once. Children continue until net DV01 reaches the parent's target, so client flow that nets meanwhile reduces what is left.

At central, the sliced desk sends 49 hedge orders a session against 6, with 9.5 passive fills and 20.4 escalations. Mean absolute net DV01 is 62,581 against 59,805. The residual-risk guard reads +280,920 (182,582 to 385,517). Slicing is adopted: `DeskFactory.deskDefault()` uses `ADOPTED_SLICING`, and `nettingOnly()` is the old default.

On the fitting period market-only children did as well, so the measured gain comes from depth-sized children. Two features of the queue model favour the passive leg: it has no adverse selection beyond the price path, and it fills at once an order that arrives behind a best that has moved.

**Cost-aware trigger (V1H-002).** `CostAwareHedgeBudget` hedges all net DV01 `X` when `gamma / 2 * sigma^2 * X^2 * horizon` exceeds `(h + fee) |X| + t * sum_k k * min(D, |X| - k D)+`, or when `|X|` exceeds the cap. The live half-spread `h`, tick `t` and touch DV01 `D` come from `HedgeBudget.observeMarket`. Parameters: gamma 1e-6, sigma 0.35 / sqrt(60) bp per sqrt(s), horizon 2,700 s (chosen on the fitting period from 900 to 7,200 s), cap 500,000. It is better only at central, so it stays opt-in. With constant volatility and spread its threshold is about 98,000 DV01 at median depth, close to the fixed trigger.

Reproduce:

```bash
research/hedge/run_hedge_experiment.sh --preregs data/preregs/V1H-001.yaml,data/preregs/V1H-002.yaml --commit "$(git rev-parse HEAD)"
```

## How long the desk carries risk, and a carry-time limit (V1C-001, V1C-002)

Added 2026-11-07 (DR-V1C-01 to DR-V1C-03). The question came from a reader: is hedge latency modelled, or is the hedge instant? It is modelled. A hedge order reaches the simulated venue `order_latency_ms` after it is sent (2 ms, a declared assumption in preset `usd_hedge_cost`, not fitted) and fills against the book at that moment. The more useful question turned out to be where the time between a client fill and its hedge actually goes.

**Measure.** `RiskCarry` (`rates-backtest`) takes the fills of a run and, for each client fill, measures the time to the first hedge fill at or after it. A client fill with no later hedge in the session is counted as unhedged (netted by later flow or held to the close). It is the time to the next hedge, not proof that this hedge offset that fill: with netting, one hedge covers the net of many fills.

**Latency sweep (exploratory).** `LatencySweep` replays the V1H-001 fitting period (2026-04-13 to 2026-04-24) with order latency 0, 2, 20, 200, 1,000 and 5,000 ms, for the baseline and the netting desk. It records nothing in the ledger and gives no verdict. Results: `research/hedge/results/latency_sweep.json`.

| Netting desk, central | 0 ms | 2 ms | 200 ms | 1 s | 5 s |
|---|---|---|---|---|---|
| P&L, USD per session | 782,758 | 785,291 | 782,635 | 782,421 | 726,883 |
| Median carry, s | 2,373 | 2,373 | 2,373 | 2,374 | 2,378 |
| 90th percentile carry, s | 10,822 | 10,822 | 10,822 | 10,823 | 10,827 |

From 0 to 1 s the latency barely shows in P&L or carry; at 5 s it costs about 7 percent of P&L at central. The median client fill waits about 40 minutes for a hedge and the 90th percentile about three hours, and 18 percent of client fills are never hedged before the close. Wire latency is milliseconds against carry of tens of minutes, so the time is spent in the hedge decision, not on the wire. The budget hedges only above a DV01 trigger, so a book just under it can stay unhedged for hours.

**Fix tested.** `CarryLimitHedgeBudget` wraps another budget. If the inner budget asks for a hedge, that hedge is sent as before. Otherwise, once absolute net DV01 has stayed above a floor for `max_carry`, it hedges down to `retain`. The clock starts when the exposure rises above the floor and resets when it falls back, so steady small flow does not keep restarting it. `HedgeBudget` gained a time-aware `excessDv01` and a `nextCheckNanos`, which `DeskQuoter` uses to schedule the check. Floor 50,000 USD per bp, retain 0 and limit 900 s were chosen on the fitting period from floors 50,000 and 100,000 and limits 300, 900 and 1,800 s. The 300 s limit had the better risk-adjusted cost but cost 16 to 17 percent of P&L at low and central; 900 s kept P&L within 10 percent at every point.

**Result.** Pre-registered as V1C-001 against the desk default (netting with sliced hedges) on 20 new sessions, 2026-06-15 to 2026-07-10, with config `data/experiments/V1C.config.yaml` (day seed base 9,100,000). Ledger seq 31, code 7545578, copy in `research/hedge/results/V1C-001.json`. Holm runs over 3 tests.

| Band point | Risk-adjusted shortfall, desk default | With carry limit | Difference (95% interval) | Holm p | P&L difference (95% interval) | Median carry, s | Unhedged client fills |
|---|---|---|---|---|---|---|---|
| Low | -640,708 | -617,684 | +23,024 (-6,140 to 53,428) | 0.26 | -120,299 (-290,474 to 68,152) | 1,850 to 1,217 | 9.0 to 2.2 percent |
| Central | -411,802 | -346,030 | +65,773 (42,720 to 85,928) | 0.0003 | -126,269 (-411,625 to 104,182) | 5,037 to 2,315 | 12.7 to 8.0 percent |
| High | -271,565 | -141,950 | +129,615 (76,573 to 182,537) | 0.0003 | +50,702 (-146,003 to 309,321) | 15,132 to 5,639 | 44.7 to 16.6 percent |

Improved at central and high and inconclusive at low, so not supported under its own rule (all three points), and not adopted. The guards passed: no new limit breach and no P&L interval entirely below zero. The rule cuts the risk charge by 36 to 78 percent and roughly halves median carry at central and high, for 17 to 41 percent more hedge orders. At the low point flow is heavy and netting already keeps carry short, so there is less for the limit to do. The holdout (2026-07-13 to 2026-07-17) is unopened. `DeskFactory.deskDefault()` is unchanged; the rule is opt-in through `CarryLimitExperiment.treatment`.

**Flow-aware follow-up (V1C-002).** V1C-001 failed at the low point, where client flow is heaviest. The follow-up gives the limit a quiet gap: while client fills keep arriving within `quiet` of each other, opposing flow may still net the exposure, so the limit waits, and it hedges once the flow goes quiet. Floor, retain and limit are unchanged. A quiet gap of 600 s was chosen on the fitting period from 60, 120, 300 and 600 s. There, every gap moved the metric by less than 26,000 USD per session against the rule without one, and the pre-registration says a large change was not expected. It ran on new sessions (day seed base 9,200,000, config `data/experiments/V1C2.config.yaml`) over a new evaluation period, 2026-07-20 to 2026-08-14. Ledger seq 32, code 9aee3ec, copy in `research/hedge/results/V1C-002.json`.

| Band point | Risk-adjusted shortfall, desk default | With flow-aware limit | Difference (95% interval) | Holm p | P&L difference (95% interval) | Median carry, s |
|---|---|---|---|---|---|---|
| Low | -598,651 | -585,781 | +12,870 (-13,609 to 40,292) | 0.39 | -127,975 (-315,662 to 58,960) | 1,867 to 1,476 |
| Central | -388,033 | -347,062 | +40,971 (8,878 to 82,597) | 0.094 | -108,297 (-384,883 to 120,401) | 3,018 to 1,651 |
| High | -172,110 | -107,792 | +64,318 (42,923 to 95,212) | 0.0055 | -155,413 (-376,151 to 17,006) | 18,941 to 7,064 |

Improved at high only, so not supported and not adopted; the guards passed. The quiet gap did not rescue the low point, and on these sessions central lost significance too.

**What the two tests show together.** In all six band-point results the limit shortened median carry (by 21 to 63 percent), cut the share of client fills never hedged, and lowered the risk charge (by 22 to 78 percent), for 14 to 70 percent more hedge orders. The risk-adjusted gain is clear when flow is sparse (high point, both tests) and within noise when flow is heavy (low point, both tests), where netting already keeps carry short. Mean P&L differences were negative at five of six points, but every interval included zero. The limit is a dial between hedge cost and risk carried, not a free improvement, and which side of it a desk should sit on is a risk-appetite choice that this simulator cannot make. Two sequential tests of the same idea are enough. More tuning against these sessions would be fitting to noise, so the rule stays opt-in and `DeskFactory.deskDefault()` is unchanged.

These are simulated sessions under declared assumptions (DR-008): a consistency check of the rule against its own objective, not evidence of edge.

Reproduce:

```bash
research/hedge/run_latency_sweep.sh
research/hedge/run_carry_limit.sh --prereg data/preregs/V1C-001.yaml --scope fitting
research/hedge/run_carry_limit.sh --prereg data/preregs/V1C-001.yaml --commit "$(git rev-parse HEAD)"
research/hedge/run_carry_limit.sh --prereg data/preregs/V1C-002.yaml --work target/carry-limit2 --commit "$(git rev-parse HEAD)"
```

## Reproduce

```bash
mvn -B -ntp -pl rates-strategy -am -DskipTests install
java -cp "<rates-strategy classpath>" io.ratesalgo.strategy.experiment.DeskExperimentCli --preregs data/preregs/R2B-001.yaml,data/preregs/R2B-002.yaml,data/preregs/R2B-003.yaml,data/preregs/R2B-004.yaml,data/preregs/R2B-005.yaml,data/preregs/R2B-006.yaml,data/preregs/R2B-007.yaml --commit "$(git rev-parse HEAD)"
```

A second run appends seven more records (look 2) with the same journal hash; the ledger shows how many times the data has been looked at. The run takes about 36 s on the development laptop (2026-10-31).
