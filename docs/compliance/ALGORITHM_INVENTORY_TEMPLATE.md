# Algorithm inventory

One row per algorithm (PRA SS5/18, RTS 6 Article 1 and MAR 7A records). The `id` is written to every order record's execution-within-firm field (Annex II Table 3 field 4) by the book mapper, so every order traces back to a row here. A row changes only through a reviewed pull request, and the change is listed in the annual self-assessment.

## Fields

| Field | Meaning |
|---|---|
| id | Stable identifier, `ALG-<area>-<function>-<nn>`, never reused |
| name | Short name |
| description | What it does, in two sentences |
| instruments | Instrument groups it trades |
| venues | Venues and protocols (RFQ, CLOB) |
| risk tier | High, medium or low, by the firm's tiering method (market impact, size, speed, autonomy) |
| owner | The person accountable for its behaviour |
| approver | The person who approved its deployment |
| limits | The configuration keys that bound it (for example `limits.maxOrderNotionalMinor`) |
| tests | The tests and back-tests that evidence it, with their pre-registration where one applies |
| deployed since | Date and release |
| last material change | Date and pull request |
| kill scope | The kill switch scope id that stops it |
| status | development, test, production or retired |

## Inventory (simulated environment)

| id | name | description | instruments | venues | risk tier | owner | approver | limits | tests | deployed since | last material change | kill scope | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALG-SWP-RFQ-01 | Swap RFQ quoter | Answers dealer-to-client RFQs in USD SOFR swaps with an inventory-skewed two-way price. Win probability is a declared band (DR-008). | USD SOFR swaps | D2C RFQ (simulated) | high | owner | owner | `limits.*`, `quoting.instruments[*].maxQuoteSizeMinor` | P3 quoting tests, P5 pre-registered back-test | not deployed | not applicable | instrument group | development |
| ALG-UST-MM-01 | Treasury order-book quoter | Places and manages resting orders in on-the-run Treasuries on a central limit order book. | US Treasuries | D2D CLOB (simulated) | high | owner | owner | `limits.*` | P3 and P4 tests | not deployed | not applicable | instrument group | development |
| ALG-HEDGE-01 | DV01 hedger | Hedges residual DV01 into futures and benchmarks when the ladder exceeds its band. | Treasury futures, benchmarks | CLOB (simulated) | medium | owner | owner | `limits.maxNetDv01Minor` | P3 hedger tests | not deployed | not applicable | process | development |
| ALG-ROUTE-01 | Smart order router | Chooses venue and size for hedge and client orders and records decision inputs. | All | All (simulated) | medium | owner | owner | `limits.maxMessagesPerSecond` | P4 router tests, markout report | not deployed | not applicable | process | development |
