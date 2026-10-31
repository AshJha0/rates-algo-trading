# Public data sources

Every source the project may use, with the terms a reviewer must verify before the data is downloaded. Nothing has been downloaded as of 2026-10-31; every row is marked "not yet downloaded, P1". Raw downloads are never committed (DECISIONS.md DR-006). When a file is used, its SHA-256 and size are recorded with `ratesresearch.provenance.write_manifest` and the manifest is committed.

The machine-readable copy of this table is `ratesresearch.provenance.SOURCES`; `research/tests/test_provenance.py` checks that it has thirteen entries, as this table does.

| Name | URL | What it provides | Licence or terms to verify | How it is used | Limits | Status |
|---|---|---|---|---|---|---|
| DTCC SDR public dissemination (rates) | https://pddata.dtcc.com/ppd/ | Real-time public dissemination of US swap transactions: price, notional, tenor, timestamps | DTCC public dissemination terms; redistribution of raw data to be checked | Fit trade-arrival and size distributions for the RFQ simulator (P2, P5) | Capped notionals, delayed block trades, no quotes, no counterparty | not yet downloaded, P1 |
| FINRA TRACE Treasury aggregate statistics | https://www.finra.org/finra-data/fixed-income/treasury-aggregate-statistics | Daily aggregate Treasury volumes by product and venue type | FINRA data terms | Volume profiles for the Treasury venue simulator (P2) | Aggregates only; no trade-level prices | not yet downloaded, P1 |
| US Treasury par yield curves and auctions | https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics | Daily par yield curve; auction announcements and results | US government work, public domain | Curve fixtures and auction calendar (P1) | Par curve is a fitted summary, not tradable prices | not yet downloaded, P1 |
| CME current-day settlements | https://www.cmegroup.com/markets/interest-rates.html | Current-day settlement prices for Treasury and SOFR futures | CME website terms; current day only, historical data is licensed | Futures fixtures for CTD and basis checks (P1) | Current day only | not yet downloaded, P1 |
| NY Fed SOFR | https://www.newyorkfed.org/markets/reference-rates/sofr | Daily SOFR fixing, percentiles, volumes | NY Fed terms of use | Overnight index for USD curves (P1) | Published T+1 morning; revisions possible | not yet downloaded, P1 |
| ECB EUR STR | https://www.ecb.europa.eu/stats/financial_markets_and_interest_rates/euro_short-term_rate/html/index.en.html | Daily EUR STR fixing and volumes | ECB copyright; reuse with attribution | Overnight index for EUR curves (P1) | Published T+1 at 08:00 CET | not yet downloaded, P1 |
| Bank of England SONIA | https://www.bankofengland.co.uk/markets/sonia-benchmark | Daily SONIA fixing and compounded index | BoE terms; bulk-download licence to verify | Overnight index for GBP curves (P1) | Published T+1 at 09:00 London | not yet downloaded, P1 |
| Deutsche Bundesbank statistics | https://www.bundesbank.de/en/statistics | Bund yields, term-structure parameters, money-market statistics | Bundesbank terms of use | EUR government curve fixtures (P1) | Fitted parameters, not trade prices | not yet downloaded, P1 |
| Finanzagentur | https://www.deutsche-finanzagentur.de/en/ | Bund auction calendar and results, outstanding issues | Website terms | Bund securities master and auction calendar (P1) | Issuance data only | not yet downloaded, P1 |
| UK Debt Management Office | https://www.dmo.gov.uk/ | Gilt issuance, auction results, gilts in issue, ex-dividend dates | Open Government Licence where stated; verify per dataset | Gilt securities master and calendar (P1) | Reference prices, not trades | not yet downloaded, P1 |
| CFTC Commitments of Traders | https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm | Weekly positioning by trader category in rates futures | US government work, public domain | Positioning context (P5) | Weekly, aggregated, lagged | not yet downloaded, P1 |
| NY Fed TGCR and BGCR | https://www.newyorkfed.org/markets/reference-rates/tgcr | Tri-party and broad general collateral repo rates | NY Fed terms of use | Repo and carry inputs (P1) | General collateral only | not yet downloaded, P1 |
| OFR short-term funding monitor | https://www.financialresearch.gov/short-term-funding-monitor/ | Repo volumes and rates across segments | US government work, public domain | Repo market context (P1, P5) | Aggregates; segment definitions change | not yet downloaded, P1 |

## Procedure when a source is first used

1. Read the terms at the URL on that day and record the date and a one-line summary in this table.
2. Download to a directory outside the repository.
3. Run `write_manifest` over the files and commit the manifest under `data/manifests/`.
4. Fit or derive what is needed and commit only the result (`data/presets.yaml` or a small fixture) with the manifest name in its `fitted_from` field.
