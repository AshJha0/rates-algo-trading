"""Forward log (paper trading) inputs: real end-of-day rates for the desk's daily cycle.

See docs/research/PAPER_TRADING_PLAN.md. ``fetch`` downloads the day's public data and writes
one input file per business day; the Java ``ForwardCli`` reads it, marks the committed book and
writes the next decision to the hash-chained ledger. ``report`` re-reads the ledger and checks
the Java arithmetic.
"""
