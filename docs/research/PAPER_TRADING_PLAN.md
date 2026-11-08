# Paper trading plan: a forward log for the desk

Status: built on 2026-11-07 (branch `paper-trading`). The owner took the recommended option on all four decisions in Section 8 that day. The first forward line is written by the first scheduled run after the pull request merges. Section 9 records what was built and a dry run on ten past days.

## 1. Why, and what "paper trading" means here

Today every result in this repository comes from synthetic replay: the D2C and CLOB simulators make up the market and the clients, and the back-tester scores the desk against that made-up world. That is useful for testing code paths. It says nothing about how the desk behaves on days that had not happened when the code was written.

A forward log fixes the second problem, and only that one. Each business day the desk writes down, before the next day's market data exists, exactly what it holds and how it will quote. The next day's real published rates then mark that book. Because the record is hash-chained and committed to git before the outcome is published, nobody (including us) can quietly rerun a bad day.

What stays synthetic is said plainly in Section 4. The short version: the rates are real, the clients and the fills are not.

## 2. Data sources

Checked on 2026-11-07 from this machine with plain `curl`, no key, no account.

| Source | Endpoint | What we take | Published | Terms | Access on 2026-11-07 |
|---|---|---|---|---|---|
| US Treasury daily par yield curve | `home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/<year>/all?type=daily_treasury_yield_curve&_format=csv` | Par yields 1M to 30Y | Same business day, late afternoon New York time | US government work, public domain (recorded in `data/SOURCES.md` on 2026-10-31) | HTTP 200, row for 2026-11-06 present |
| NY Fed SOFR (and TGCR, BGCR) | `markets.newyorkfed.org/api/rates/secured/sofr/last/<n>.json` | SOFR fixing, percentiles, volume, revision flag | Next business day about 08:00 New York time | NY Fed Terms of Use: licence to use and redistribute with the notice "(c) [year] Federal Reserve Bank of New York" (recorded 2026-10-31) | HTTP 200, fixing for 2026-11-05 present |
| FRED `SOFR` | `fred.stlouisfed.org/graph/fredgraph.csv?id=SOFR` | Same series as the NY Fed | Lags the NY Fed | FRED terms; the series belongs to the NY Fed | Timed out after 20 s; fallback only, never primary |
| DTCC SDR public dissemination, rates | end-of-day files already used by P5 and the edge studies | Traded USD SOFR OIS rates by tenor, to build a daily swap mark | End of day | DTCC terms, CFTC Part 43 public data; raw files not redistributed, only derived numbers committed (as today) | Already in use; files older than about two years go to deep archive, which does not matter going forward |
| CME settlements | `cmegroup.com` settlement pages | Treasury and SOFR futures settlements | End of day | CME Data Terms of Use forbid scripts | HTTP 403 with an explicit anti-scraping notice. **Excluded.** |

No source needs a card, an account or a paid plan. Nasdaq Data Link and LOBSTER are not used, as the owner decided earlier. Every new download follows the procedure at the end of `data/SOURCES.md`: read the terms that day, record a manifest with SHA-256, commit only derived numbers.

There is no free futures settlement feed we may script, so the desk's hedge instrument (the 10-year note future) is marked from the Treasury 10-year par yield through the existing analytics, with the basis held at zero. That is a model price and is labelled as such in every report.

## 3. Cadence

End of day. The par curve is the anchor and it is published once a day. Intraday-delayed data is not free for rates (CME is excluded, Treasury and the NY Fed publish daily), so an intraday forward log would be synthetic in all but name.

One cycle, run on day D after the Treasury curve for D is out:

1. **Fetch** the Treasury curve for D, the latest SOFR fixing on or before D, and the DTCC end-of-day file for D. The input file records each download's URL, size and SHA-256.
2. **Mark** yesterday's committed book with D's curve: P&L, carry, risk, hedge cost. This closes entry D-1.
3. **Decide** for the next weekday (no flow for it is simulated yet): the quoting parameters, hedge-budget parameters, the starting book, and a random seed for the synthetic RFQ flow, all computed from data dated D or earlier.
4. **Append** one ledger line holding the decision and its inputs' hashes, and commit it. Nothing about D+1 exists yet.
5. On D+1's run, step 2 replays the committed seed's synthetic flow against D's curve as mid (the desk quotes on the last curve it knew), books the fills, then marks the end-of-day book at D+1's real curve.

So the information order is strict: decision at D close, flow priced off D, mark at D+1 close.

## 4. How the desk runs forward

| Part | In the forward run | Real or synthetic |
|---|---|---|
| Curve | Built by the existing `rates-analytics` curve code from the Treasury par yields and SOFR; swap par rates from the DTCC daily mark, or from Treasuries plus a fixed spread (decision 8.1) | **Real** inputs, model curve |
| Quoting | `DeskQuoter` (`rates-strategy`) with its parameters frozen in a config file under `config/forward/`, changed only by a dated, committed config change | Real code, unchanged |
| Hedging | `NettingHedgeBudget` with frozen soft, hard, retain and hold parameters; hedges into the 10-year note future | Real code; hedge price is a model price (Section 2) |
| Client RFQs | The P5 `usd_rfq_flow` preset (fitted to DTCC trades) drives `D2cRfqSimulator` with the committed seed | **Synthetic**, labelled |
| Fills and competitor quotes | `RfqOutcomeModel` and `CompetitorModel` from `data/presets.yaml` | **Synthetic**, labelled |
| Hedge fills | `HedgeCostModel` at the preset half-spread | **Synthetic**, labelled |
| End-of-day mark | D+1 real curve | **Real** |
| Carry and roll | `CarryModel` with real SOFR for financing | **Real** inputs |

The forward runner reuses the back-tester rather than adding a second engine: `ForwardSession` in `rates-strategy` generates the session with `SyntheticDay` pinned to the committed mids, runs `BacktestRunner` with `DeskFactory.deskDefault()` (the same desk the experiments adopted), and returns the end-of-session positions. A new module is not needed. The hedge-latency work going on in parallel (`LatencySweep`, `RiskCarry`) is not touched; carry uses the existing `CarryModel` hook (Section 9).

Every report and ledger line carries `"flow": "synthetic"` and `"marks": "real"`. The README and LEARN sections say the same in words.

## 5. The ledger, the job and the report

**Ledger.** `forward/ledger.jsonl` on the `forward-log` branch, one canonical JSON line per event, with `seq` and `prev_sha256` exactly like the experiment ledger (`rates-backtest/.../prereg/Ledger.java`, API_SPEC.md Section 16.8). The code uses that class, so the chain check is the same code. Lines are never edited; three kinds are appended:

- `decision` (written at the close of D): the session date, the input file and its SHA-256, the hashes of the settings, experiment config and presets, the code commit, the committed mids, SOFR, the source of each swap mark, and the flow seed (taken from the hash of the previous line, so it cannot be picked).
- `outcome` (written at the close of the session day, or the next Treasury business day after a holiday): `closes_seq`, the session's attribution (spread capture, hedge cost, carry, funding, fees), RFQ and fill counts, each instrument's end position, committed and marked mid, close mark and DV01, and the total.
- `skipped`: a decision whose session went unmarked for more than seven days, with the reason.

A SOFR revision would show in the next input file; old lines are not touched.

**Job.** `.github/workflows/forward.yml`, cron at 01:30 UTC Tuesday to Saturday, after both the Treasury curve (late afternoon New York time) and the DTCC end-of-day file (about 00:15 UTC) for the day before. It runs on the free GitHub-hosted runner for a public repository. It fetches, builds with Maven, runs `ForwardCli`, re-checks the ledger in Python and commits to `forward-log` with the default `GITHUB_TOKEN`. On a Treasury holiday the fetch exits with status 3 and the job stops cleanly; a failed download fails the job so it shows in Actions, and the open decision is marked by the next successful run.

**Report.** `forward/reports/<session>.md` per marked session and a rolling `SUMMARY.md`, both rebuilt from ledger lines only: P&L lines, close mark per instrument, end DV01, cumulative P&L, drawdown, mean and standard error. `ratesresearch.forward.check` recomputes the close marks and totals from the ledger and fails on a difference of more than a cent.

**Tests.** `ForwardCycleTest` covers the decision-then-outcome order, the close-mark arithmetic, the look-ahead rule (the decision line is byte-identical when later input files exist), reproducibility from the seed, idempotence, a holiday, a long gap and a tampered line. `research/tests/test_forward.py` covers the parsers, the fallbacks and the Python check. The dry run on ten past days is in Section 9.

## 6. What the results can and cannot claim

Can claim:
- How the desk's held inventory and hedges would have marked against real end-of-day rate moves, given the synthetic flow it was shown.
- That the decisions were fixed before the outcomes, checked by the hash chain and git history.
- Real carry and financing effects on that inventory.

Cannot claim:
- Any real hit rate, real client behaviour, real fill or real spread capture. Those come from fitted models and are synthetic.
- Any intraday effect. The desk quotes all day on yesterday's close; real intraday moves are missing.
- Profitability of a real desk. A forward P&L with synthetic flow is a test of the risk and hedging logic under real rate moves, not of a business.
- Statistical significance for some time. With daily marks, a Sharpe-type figure needs on the order of a year of business days before its standard error is small; the summary will show the standard error next to every ratio and will not print a ratio before 60 logged days.

## 7. Work plan

| Step | What | Estimate |
|---|---|---|
| 1 | Fetchers (`research/ratesresearch/forward/fetch.py`) with manifests; SOURCES.md rows updated | 0.5 day |
| 2 | Curve build from real inputs, golden test on a past date | 0.5 day |
| 3 | `ForwardSession` and `ForwardCycle` in `rates-strategy`, decision and mark, reusing back-tester and `Ledger` chain | 1 day |
| 4 | Report writer, Python cross-check, dry run, tamper and look-ahead tests | 1 day |
| 5 | `forward.yml` workflow, docs (README, LEARN, COOKBOOK, ARCHITECTURE, CHANGELOG, DECISIONS) | 0.5 day |

About 3.5 plan-days, so roughly one wall-clock working session plus a CI cycle. One branch, one PR. Full local tests before pushing.

Actual: one working session on 2026-11-07, plan to pull request in about five hours wall-clock, close to the estimate.

## 8. Owner decisions

1. **Swap curve input.** (a) Daily SOFR OIS marks derived from the DTCC end-of-day file (real traded rates, more work, depends on DTCC staying free); or (b) Treasury par yields plus a fixed swap spread from `data/presets.yaml` (simple, but the swap curve then moves only with Treasuries). Recommendation: (a), with (b) as the fallback written to the ledger when DTCC is missing.
2. **Where the bot commits.** (a) A separate `forward-log` branch, so the main ruleset with nine required checks stays untouched; or (b) `main`, which needs a ruleset bypass for the Actions bot. Recommendation: (a).
3. **Start date.** The first decision line is written by the first scheduled run after the PR merges. Recommendation: yes, no back-filled forward days (back-fill would not be forward).
4. **Frozen parameters.** Use today's `DeskQuoter` and `NettingHedgeBudget` defaults from the P5 and desk-feature experiments, frozen in `config/forward/desk.yaml`, changed only by a dated commit that the ledger records. Recommendation: yes.

## 9. What was built, and a dry run

Decisions taken by the owner on 2026-11-07: 8.1 (a) DTCC marks with the Treasury-plus-spread fallback; 8.2 (a) the `forward-log` branch; 8.3 no back-fill; 8.4 frozen desk-default parameters. DECISIONS.md DR-FWD-01 to DR-FWD-04 record them and the design choices below.

| Piece | Where |
|---|---|
| Fetcher (Treasury, NY Fed SOFR, DTCC) | `research/ratesresearch/forward/fetch.py` |
| Independent ledger check (chain, sessions, close-mark and total arithmetic) | `research/ratesresearch/forward/check.py`, COOKBOOK.md recipe 37 |
| Frozen settings | `config/forward/desk.yaml` |
| Session, cycle, report, command line | `rates-strategy/src/main/java/io/ratesalgo/strategy/forward/` |
| Run script | `research/forward/run_forward.sh` |
| Scheduled job | `.github/workflows/forward.yml` (01:30 UTC, Tuesday to Saturday) |
| Tests | `ForwardCycleTest` (Java), `research/tests/test_forward.py` |

Three choices made while building, each simpler than the first sketch above:

- **Each session starts flat.** The book a session leaves is marked once, at the real close of the session day, and then counts as closed at that mark. There is no multi-day inventory. Carry and funding accrue inside the session (bond coupons, swap carry and bond funding at the committed SOFR), not overnight; weekend carry is missing.
- **The synthetic market is flat inside the session.** Every curve input carries the committed mid. That means informed clients carry no information: the generator picks their side from a price move that is zero, so it is a coin flip. Spread capture in the forward log is therefore an upper bound with no adverse selection, and it is far larger than anything the desk earned in the simulator audits (README "Limits").
- **The Treasuries and the note future are model prices**: 100 plus price change per bp times the par-yield move from the declared coupon, and the future from `future_price_0` at a 3.95 percent reference yield, basis zero. Only changes in them matter for the close mark.

**Dry run, 2026-10-26 to 2026-11-06, run on 2026-11-07.** Real input files for ten business days were fetched after the fact into a temporary directory and the cycle was run day by day. Because the outcomes were already published, this is a test of the plumbing and not a forward result, and it is not committed to the log. Nine sessions were marked; the ledger verified (19 lines) in Java and in `ratesresearch.forward.check`.

| | Range over the nine sessions | Real or synthetic |
|---|---|---|
| Session P&L (spread capture less hedge cost, carry, funding) | 0.84 to 1.78 million USD a day | synthetic flow, no adverse selection: not evidence of anything |
| Close mark of the end-of-session book | -1.67 to +2.23 million USD | real rate moves, on an end-of-session net DV01 of up to 109,000 USD per bp |
| DTCC swap marks | 38 of 40 tenor-days; 2 fell back to Treasury plus spread (2Y on 2026-11-04, 30Y on 2026-11-06) | real |

The close mark is the number to watch, and it shows the risk the desk actually ran. On 2026-10-29 the session left a book whose net DV01 was small (17,484 USD per bp) but whose legs were not: about 1 billion of 10-year and 136 million of 30-year swap risk the gains-when-rates-fall way, against 13,622 note futures the other way (dry-run report for session 2026-10-29). The Treasury 10-year par yield fell 7 bp and the 10-year SOFR swap mark 5.7 bp, so the futures leg lost 6.82 million while the swap legs made 5.72 million, and the day closed 1.67 million down on the mark. That is swap-spread risk: the desk hedges swap flow with the Treasury future, and the forward log puts a real price on that mismatch every day, which the synthetic simulator could not. A fallback to Treasury plus spread on one day and back to DTCC on the next adds a mark change that the market did not make (up to the size of the spread error); the source of every mark is in the ledger so it can be separated later.
