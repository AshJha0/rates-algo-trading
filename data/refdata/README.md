# Reference data fixtures

`v1/` holds the fixtures `rates-refdata` loads (API_SPEC.md Section 11.4). They are written by `tools/gen_refdata_fixtures.py`; regenerate them with that script and commit the new `MANIFEST.sha256` together with the files, because the loader refuses any file whose sha256 does not match.

Provenance (DECISIONS.md DR-006):

| File | Source | Licence |
|---|---|---|
| bonds.csv, auctions.csv | Synthetic. ISINs and CUSIPs are invented with valid check digits; coupons, maturities and the auction pattern imitate the issuers' usual schedules (US Treasury, UK DMO, Finanzagentur) but are not real issues | written for this project, Apache 2.0 |
| swap_templates.csv | Market conventions as described in LEARN.md Section 1; MAT flags and block thresholds are declared assumptions, not the CFTC's published values | Apache 2.0 |
| futures.csv | Contract sizes and tick sizes from the exchanges' published contract specifications (CBOT Treasury futures, Eurex Euro-Bund); contract months are examples | facts, no data copied |
| parties.csv | Invented names | Apache 2.0 |
| holidays.csv | Holiday dates for 2026 and 2027 (a date outside those years is refused) from the SIFMA US recommendation, UK bank holidays and the TARGET2 calendar | facts, no data copied |

No downloaded file is committed. When P1 calendars land, `CalendarSource` is implemented over them and `holidays.csv` remains only as a test fixture.
