# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine. Recipes 4 to 8 (P1) print values rounded well above double-precision noise so that they are the same on every platform. Recipes 14 to 16 (P3) were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11.

## The planned recipes

1. How do I use the ManualClock and TimerWheel in a test? (P0, written below)
2. How do I write frames to a journal and replay them? (P0, written below)
3. How do I encode and decode a QuoteRequest with the SBE codecs? (P0, written below)
4. How do I bootstrap a USD SOFR discount curve from OIS quotes? (P1, written below)
5. How do I price a swap and get its par rate and DV01? (P1, written below)
6. How do I price a Treasury from yield and get accrued interest? (P1, written below)
7. How do I compute a gilt's accrued interest inside the ex-dividend period? (P1, written below)
8. How do I find the CTD and net basis for a Treasury futures contract? (P1, written below)
9. How do I compare a result to QuantLib within a stated tolerance? (P1, written below; a test, not a program)
10. How do I start a venue simulator and connect a FIX 4.4 session? (P2, written below)
11. How do I subscribe to the SBE book feed and rebuild a book? (P2, written below)
12. How do I run the event loop against a journal and check determinism? (P2, written below)
13. How do I run the latency job and read the histogram? (P2, written below; P5 adds the rate sweep)
14. How do I quote an RFQ through the engine and see a quoting parameter's effect on skew? (P3, written below)
15. How do I read the DV01 ladder and compute a cost-aware hedge? (P3, written below)
16. How do I set a risk limit and exercise the kill switch? (P3, written below)
17. How do I route a hedge order and read the markout report? (P4, written below)
18. How do I run the back-tester on one synthetic day? (P5, written below)
19. How do I pre-register an experiment and record it in the ledger? (P5, written below; covers the earlier planned "pre-register a back-test and open the holdout once")
20. How do I reconcile attribution to position P&L? (P5, written below; a Python recipe)
21. How do I run the chaos test and read the break report? (P6, written below)
22. How do I check that a hot-path package allocates nothing? (P2, not yet written)
23. How do I verify a Java journal's hash chain from Python? (P5, not yet written)

The recipes were renumbered when the phase branches were combined, so that they run contiguously by phase (DECISIONS.md DR-P4-05 and DR-P2C-10, amended).

## 1. How do I use the ManualClock and TimerWheel in a test?

`ManualClock` is a `Clock` whose time only moves when the test calls `advance`; both readings move together. `TimerWheel(tickNanos, wheelSize, initialCapacity, startNanos)` is a hashed wheel: `schedule(deadlineNanos, handler)` returns an id, `cancel(id)` removes a timer, and `poll(nowNanos)` fires every timer whose deadline is at or before `nowNanos`, in deadline order and then id order. There is no scheduler thread; the owner calls `poll` from its own loop. The handler receives its timer id and the `nowNanos` of the poll that fired it, so a late poll shows how late it is.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.common.time.TimerWheel;
import java.util.ArrayList;
import java.util.List;

/** COOKBOOK.md recipe 1: a ManualClock drives a TimerWheel in a test. */
public final class Recipe01TimerWheel {

  private Recipe01TimerWheel() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock(1_700_000_000_000_000_000L);
    final long t0 = clock.nanoTime();
    // 1 ms ticks, 1024 slots, room for 16 timers before the wheel grows, tick zero at t0
    final TimerWheel wheel = new TimerWheel(1_000_000L, 1024, 16, t0);
    final List<String> fired = new ArrayList<>();

    final long a = wheel.schedule(t0 + 5_000_000L, (id, now) -> fired.add("a@" + (now - t0)));
    final long b = wheel.schedule(t0 + 2_000_000L, (id, now) -> fired.add("b@" + (now - t0)));
    final long c = wheel.schedule(t0 + 5_000_000L, (id, now) -> fired.add("c@" + (now - t0)));
    wheel.cancel(c);

    clock.advance(1_000_000L);
    final int n1 = wheel.poll(clock.nanoTime());
    System.out.println("after 1 ms: fired=" + n1 + " " + fired);

    clock.advance(4_000_000L);
    final int n2 = wheel.poll(clock.nanoTime());
    System.out.println("after 5 ms: fired=" + n2 + " " + fired);
    System.out.println("ids distinct: " + (a != b && b != c) + ", live: " + wheel.liveCount());
  }
}
```

Expected output:

```
after 1 ms: fired=0 []
after 5 ms: fired=2 [b@5000000, a@5000000]
ids distinct: true, live: 0
```

Timer `b` was due at 2 ms but the loop only polled at 5 ms, so its handler sees `now - t0 = 5000000`; the firing order is still by deadline, `b` before `a`. Timer `c` was cancelled and never fires, and nothing is live afterwards.

## 2. How do I write frames to a journal and replay them?

`JournalWriter.create(path, capacityBytes, clock)` maps a new file and appends frames with `append(templateId, schemaVersion, buffer, offset, length)`; the timestamp comes from the writer's clock. Each frame carries sequence, timestamp, template id, schema version and a chain hash (API_SPEC.md Section 1). `Replayer` reads them back in order, drives its `ManualClock` to each frame's stamp, recomputes the chain and throws `JournalCorruptionException` naming the sequence of the first bad frame.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalCorruptionException;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.journal.Replayer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 2: write frames to a journal, replay them, and see the chain check fire. */
public final class Recipe02Journal {

  private Recipe02Journal() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe02");
    final Path file = dir.resolve("recipe.raj");
    final ManualClock clock = new ManualClock(0L);

    final UnsafeBuffer payload = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    try (JournalWriter writer = JournalWriter.create(file, 1 << 16, clock)) {
      for (int i = 1; i <= 3; i++) {
        clock.advance(1_000L);
        payload.putLong(0, 100L * i);
        writer.append(/* templateId */ 1, /* schemaVersion */ 0, payload, 0, 8);
      }
    }

    final ManualClock replayClock = new ManualClock(0L);
    final long frames =
        new Replayer(replayClock)
            .replay(
                file,
                (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) ->
                    System.out.println(
                        "seq="
                            + sequence
                            + " ts="
                            + timestampNanos
                            + " template="
                            + templateId
                            + " value="
                            + buffer.getLong(offset)));
    System.out.println(
        "frames=" + frames + " finalHash=" + Long.toHexString(Replayer.finalHash(file)));

    // flip one payload byte of the second frame: header 32 + first frame 40 + frame header 32
    final byte[] bytes = Files.readAllBytes(file);
    bytes[32 + 40 + 32] ^= 0x01;
    Files.write(file, bytes);
    try {
      Replayer.finalHash(file);
    } catch (final JournalCorruptionException e) {
      System.out.println("corruption detected at sequence " + e.sequence());
    }
    Files.delete(file);
    Files.delete(dir);
  }
}
```

Expected output:

```
seq=1 ts=1000 template=1 value=100
seq=2 ts=2000 template=1 value=200
seq=3 ts=3000 template=1 value=300
frames=3 finalHash=c6d0336109f10ed2
corruption detected at sequence 2
```

The final hash is reproduced by `research/ratesresearch/fnv.py` from the three eight-byte payloads alone, since only payload bytes and the previous hash are hashed (API_SPEC.md Section 1.3). The flipped byte sits in the payload of frame 2, so frame 1 replays and frame 2 is reported.

## 3. How do I encode and decode a QuoteRequest with the SBE codecs?

The codecs are generated by `sbe-tool` from `rates-messages/src/main/resources/sbe/rates-schema-v1.xml` into `io.ratesalgo.messages.sbe`. Encoders and decoders are flyweights: wrap them over a buffer, set or read fields, and reuse the instance. `wrapAndApplyHeader` writes or reads the standard SBE message header (block length, template id, schema id, version) ahead of the body, and `encoder.limit()` is the total encoded length.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 3: encode and decode a QuoteRequest with the generated SBE codecs. */
public final class Recipe03QuoteRequest {

  private Recipe03QuoteRequest() {}

  public static void main(final String[] args) {
    final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    final QuoteRequestEncoder encoder = new QuoteRequestEncoder();
    encoder
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .venueId(7)
        .rfqId(42L)
        .clientId(7)
        .instrumentId(1001)
        .side(Side.Buy)
        .quantity(10_000_000_000L) // 100m notional in minor units (API_SPEC.md Section 0)
        .currency("USD")
        .dealerCount((short) 4)
        .receivedNanos(1_700_000_000_000_000_000L)
        .expiryNanos(1_700_000_000_000_000_000L + 30_000_000_000L);
    final int encodedLength = encoder.limit();

    final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    final QuoteRequestDecoder decoder = new QuoteRequestDecoder();
    decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);

    System.out.println(
        "templateId="
            + headerDecoder.templateId()
            + " schemaId="
            + headerDecoder.schemaId()
            + " version="
            + headerDecoder.version()
            + " blockLength="
            + headerDecoder.blockLength());
    System.out.println(
        "rfqId="
            + decoder.rfqId()
            + " clientId="
            + decoder.clientId()
            + " instrumentId="
            + decoder.instrumentId()
            + " side="
            + decoder.side()
            + " quantity="
            + decoder.quantity()
            + " currency="
            + decoder.currency()
            + " dealers="
            + decoder.dealerCount()
            + " expiryNanos="
            + decoder.expiryNanos());
    System.out.println("encodedLength=" + encodedLength);
  }
}
```

Expected output:

```
templateId=10 schemaId=1 version=0 blockLength=49
rfqId=42 clientId=7 instrumentId=1001 side=Buy quantity=10000000000 currency=USD dealers=4 expiryNanos=1700000030000000000
encodedLength=57
```

The block length of 49 is the sum of the fixed fields in the XML (4 + 8 + 4 + 4 + 1 + 8 + 3 + 1 + 8 + 8) and the encoded length adds the 8-byte header. Field names come from the XML, which is the source of truth (API_SPEC.md Section 2).

## 4. How do I bootstrap a USD SOFR discount curve from OIS quotes?

A `CurveBuilder` takes one instrument per pillar and solves for log discount factors until every instrument's implied quote equals its market quote. The front of the curve is anchored to the overnight rate (today's SOFR is not published until tomorrow, so the quote is an estimate), followed by meeting-dated OIS between FOMC effective dates, a three-month SOFR future with its Hull-White convexity adjustment, and par OIS swaps. With linear interpolation on log discount factors and no instrument depending on dates past its pillar, the builder chooses the sequential solver (Brent's method, pillar by pillar); LEARN.md Section 2 explains why.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.HullWhite;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.curve.PeriodRateInstrument;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.ImmDates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 4: bootstrap a USD SOFR discount curve from OIS quotes. */
public final class Recipe04SofrCurve {

  private Recipe04SofrCurve() {}

  /** The recipe's curve, shared with recipe 5. */
  public static CurveBuilder build() {
    final int today = Dates.of(2025, 1, 15);
    final List<CurveInstrument> inst = new ArrayList<>();
    // today's SOFR is published tomorrow: the overnight quote is an estimate
    inst.add(new PeriodRateInstrument("SOFR ON", "ON", today,
        Calendars.US_SOFR.advanceBusinessDays(today, 1), DayCount.ACT_360, 0.0430));
    // meeting-dated OIS between FOMC effective dates: a step function in the overnight forward
    final int[] meetings = {Dates.of(2025, 1, 30), Dates.of(2025, 3, 20), Dates.of(2025, 5, 8)};
    final double[] meetingRates = {0.0430, 0.0429, 0.0422};
    int prev = today;
    for (int i = 0; i < meetings.length; i++) {
      inst.add(new PeriodRateInstrument("FOMC " + Dates.format(meetings[i]), "M" + (i + 1), prev,
          meetings[i], DayCount.ACT_360, meetingRates[i]));
      prev = meetings[i];
    }
    // a 3-month SOFR future with its Hull-White convexity adjustment
    final int start = ImmDates.thirdWednesday(2025, 9);
    final int end = ImmDates.thirdWednesday(2025, 12);
    final double ca = HullWhite.futuresConvexity(HullWhite.DEFAULT_A, HullWhite.DEFAULT_SIGMA,
        (start - today) / 365.0, (end - today) / 365.0);
    inst.add(OisFutureInstrument.threeMonth("SR3U5", 2025, 9, Calendars.US_SOFR, DayCount.ACT_360,
        ca, 96.05));
    // par OIS swaps
    final int[] months = {24, 60, 120, 360};
    final double[] rates = {0.0380, 0.0383, 0.0399, 0.0397};
    for (int i = 0; i < months.length; i++) {
      inst.add(new OisSwapInstrument(SwapConvention.USD_SOFR, today, months[i],
          (months[i] / 12) + "Y", rates[i]));
    }
    final CurveBuilder b = new CurveBuilder(today, inst, Interpolation.LINEAR_LOG_DF, 0.0, null,
        FixingHistory.EMPTY);
    b.build(CurveBuilder.Method.AUTO);
    return b;
  }

  public static void main(final String[] args) {
    final CurveBuilder b = build();
    final DiscountCurve curve = b.curve();
    System.out.println("method " + b.resolve(CurveBuilder.Method.AUTO));
    for (int i = 0; i < curve.pillarCount(); i++) {
      final int d = curve.pillarDate(i);
      System.out.println(String.format(Locale.ROOT, "%-6s %s  df=%.10f  zero=%.6f%%",
          b.instruments().get(i).tenor(), Dates.format(d), curve.df(d), 100 * curve.zeroRate(d)));
    }
    System.out.println("every instrument reprices within 1e-12: "
        + (b.maxRepricingError() <= CurveBuilder.SOLVER_TOLERANCE));
  }
}
```

Expected output:

```
method SEQUENTIAL
ON     2025-01-16  df=0.9998805698  zero=4.359462%
M1     2025-01-30  df=0.9982115377  zero=4.355821%
M2     2025-03-20  df=0.9924166514  zero=4.341360%
M3     2025-05-08  df=0.9867488755  zero=4.308842%
SR3U5  2025-12-17  df=0.9630277429  zero=4.092460%
2Y     2027-01-21  df=0.9266847083  zero=3.776058%
5Y     2030-01-22  df=0.8258776501  zero=3.809474%
10Y    2035-01-19  df=0.6710374866  zero=3.982756%
30Y    2055-01-21  df=0.3056156251  zero=3.946738%
every instrument reprices within 1e-12: true
```

The zero rates are continuously compounded, Act/365F from 2025-01-15. The front steps at the FOMC dates; the meeting-dated quotes fix the forward between meetings exactly. `CurveBuilderTest` checks the same round trip for all three interpolation schemes and that an incremental rebuild after one quote change equals a full rebuild to 1e-10 on discount factors.

## 5. How do I price a swap and get its par rate and DV01?

`OisSwap.spot` builds the schedules and the daily compounding grid once; `OisSwapPricer` prices without allocating. `pvGradient` returns the derivative of the present value with respect to the curve's log discount factors, and `KeyRateRisk.fromGradient` maps it through the bootstrap Jacobian to a DV01 per curve input (key-rate DV01). `KeyRateRisk.byBumping` computes the same ladder by moving each quote one basis point up and down and rebuilding the curve.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.risk.Dv01Ladder;
import io.ratesalgo.analytics.risk.KeyRateRisk;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.Locale;

/** COOKBOOK.md recipe 5: price a 10-year SOFR swap, its par rate and its DV01 ladder. */
public final class Recipe05SwapDv01 {

  private Recipe05SwapDv01() {}

  public static void main(final String[] args) {
    final CurveBuilder b = Recipe04SofrCurve.build();
    final DiscountCurve curve = b.curve();
    final int today = curve.valuationDate();
    // pay fixed 4% on 100 million for 10 years from spot
    final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, today, 120, 1e8, 0.04, true);
    final OisSwapPricer pricer = new OisSwapPricer();
    final FixingHistory none = FixingHistory.EMPTY;
    System.out.println(String.format(Locale.ROOT, "par rate %.6f%%",
        100 * pricer.parRate(swap, curve, none, today)));
    System.out.println(String.format(Locale.ROOT, "pv %.2f USD", pricer.pv(swap, curve, none,
        today)));
    System.out.println(String.format(Locale.ROOT, "annuity %.6f",
        pricer.annuity(swap, curve, today)));

    // key-rate DV01 by algorithmic differentiation through the bootstrap
    final double[] g = new double[curve.pillarCount()];
    pricer.pvGradient(swap, curve, curve, none, today, g, g);
    final Dv01Ladder ladder = KeyRateRisk.fromGradient(b, "USD", g);
    for (int i = 0; i < ladder.size(); i++) {
      System.out.println(String.format(Locale.ROOT, "  %-5s %12.2f", ladder.tenors().get(i),
          Math.round(ladder.dv01(i) * 100) / 100.0 + 0.0));
    }
    System.out.println(String.format(Locale.ROOT, "parallel DV01 %.2f USD per bp",
        ladder.parallel()));
    // the same by bumping each quote 1bp up and down and rebuilding
    final Dv01Ladder bumped = KeyRateRisk.byBumping(b, "USD",
        () -> pricer.pv(swap, curve, none, today), KeyRateRisk.BP);
    System.out.println(String.format(Locale.ROOT, "bumped parallel DV01 %.2f USD per bp",
        bumped.parallel()));
  }
}
```

Expected output:

```
par rate 3.990000%
pv -82325.52 USD
annuity 8.232552
  ON            0.02
  M1            0.05
  M2            0.08
  M3            0.08
  SR3U5         0.38
  2Y            2.89
  5Y           14.72
  10Y       82349.03
  30Y           0.00
parallel DV01 82367.26 USD per bp
bumped parallel DV01 82367.28 USD per bp
```

The swap has the same dates as the 10Y curve instrument, so its risk sits almost entirely on the 10Y pillar: a par swap's value moves only with its own quote. The key rates sum to the parallel DV01, and the bumped figure agrees to 2e-7 relative because the central difference removes the second-order term. The ladder type is `io.ratesalgo.analytics.risk.Dv01Ladder` (API_SPEC.md Section 8.3).

## 6. How do I price a Treasury from yield and get accrued interest?

`FixedRateBond` holds the coupon schedule; `BondPricer` prices from a yield on the street convention (nominal coupon dates, compounded semi-annually), or the true yield (coupon dates moved to business days). `treasuryAuctionDirtyPrice` is the Treasury's own auction formula (31 CFR 356 Appendix B), which discounts the first fractional period with simple interest; the Treasury rounds the dirty price and the accrued interest to six decimals each and subtracts.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import java.util.Locale;

/** COOKBOOK.md recipe 6: price a Treasury from yield, with accrued interest, duration and DV01. */
public final class Recipe06Treasury {

  private Recipe06Treasury() {}

  public static void main(final String[] args) {
    final BondPricer p = new BondPricer();
    // the 4 1/4% note of 15 November 2034 (CUSIP 91282CLW9)
    final FixedRateBond note = new FixedRateBond("91282CLW9", BondConvention.UST, 0.0425,
        Dates.of(2024, 11, 15), Dates.of(2034, 11, 15));
    final int settle = note.settlementDate(Dates.of(2025, 1, 15));
    final double y = 0.0466;
    System.out.println("settlement " + Dates.format(settle));
    System.out.println(String.format(Locale.ROOT, "clean %.6f  accrued %.6f  dirty %.6f",
        p.cleanPrice(note, y, settle), p.accrued(note, settle),
        p.dirtyPrice(note, y, settle, false)));
    System.out.println(String.format(Locale.ROOT, "modified duration %.6f  convexity %.4f",
        p.modifiedDuration(note, y, settle, false), p.convexity(note, y, settle, false)));
    System.out.println(String.format(Locale.ROOT, "DV01 per 1mm face %.2f USD",
        p.dv01(note, y, settle, false) * 1e4));
    final double clean = p.cleanPrice(note, y, settle);
    System.out.println(String.format(Locale.ROOT, "street yield %.8f%%  true yield %.8f%%",
        100 * p.yieldFromClean(note, clean, settle, false),
        100 * p.yieldFromClean(note, clean, settle, true)));
    // the reopening auction of 11 December 2024: high yield 4.235%, published price 100.114150
    final int issue = Dates.of(2024, 12, 16);
    final long dirty6 = Math.round(p.treasuryAuctionDirtyPrice(note, 0.04235, issue) * 1e6);
    final long accrued6 = Math.round(p.accrued(note, issue) * 1e6);
    System.out.println(String.format(Locale.ROOT, "auction price %.6f (published 100.114150)",
        (dirty6 - accrued6) / 1e6));
  }
}
```

Expected output:

```
settlement 2025-01-16
clean 96.790759  accrued 0.727901  dirty 97.518659
modified duration 7.860048  convexity 74.0594
DV01 per 1mm face 766.50 USD
street yield 4.66000000%  true yield 4.65971296%
auction price 100.114150 (published 100.114150)
```

The true yield is lower than the street yield because coupon dates that fall on a weekend or holiday are paid on the next business day, a little later than the street convention assumes. The auction line reproduces the price the Treasury published for the 11 December 2024 reopening; `PublishedExamplesTest` does the same for all 84 note and bond auctions and all 332 bill auctions of 2024.

## 7. How do I compute a gilt's accrued interest inside the ex-dividend period?

A gilt goes ex-dividend seven London business days before each coupon date. A buyer settling on or after that date does not receive the coming coupon, so the accrued interest is negative: the seller owes the buyer interest for the days up to the coupon date. `BondConvention.GILT` carries the seven days and `BondPricer.accrued` applies the sign.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import java.util.Locale;

/** COOKBOOK.md recipe 7: a gilt's accrued interest inside the ex-dividend period. */
public final class Recipe07GiltExDividend {

  private Recipe07GiltExDividend() {}

  public static void main(final String[] args) {
    final BondPricer p = new BondPricer();
    // 4 1/8% Treasury Gilt 2027, coupons 22 January and 22 July
    final FixedRateBond gilt = new FixedRateBond("UKT 4 1/8 2027", BondConvention.GILT, 0.04125,
        Dates.of(2022, 7, 22), Dates.of(2027, 1, 22));
    final int coupon = Dates.of(2025, 1, 22);
    int k = 0;
    while (gilt.couponDate(k) != coupon) {
      k++;
    }
    System.out.println("ex-dividend date " + Dates.format(gilt.exDividendDate(k)));
    for (final int trade : new int[] {Dates.of(2025, 1, 9), Dates.of(2025, 1, 10),
        Dates.of(2025, 1, 21)}) {
      final int settle = gilt.settlementDate(trade);
      System.out.println(String.format(Locale.ROOT, "settle %s  accrued %+.6f  clean at 4.36%% %.6f",
          Dates.format(settle), p.accrued(gilt, settle), p.cleanPrice(gilt, 0.0436, settle)));
    }
  }
}
```

Expected output:

```
ex-dividend date 2025-01-13
settle 2025-01-10  accrued +1.927989  clean at 4.36% 99.546230
settle 2025-01-13  accrued -0.100883  clean at 4.36% 99.550463
settle 2025-01-22  accrued +0.000000  clean at 4.36% 99.554539
```

On 10 January the buyer still receives the coupon and pays 1.93 of accrued interest. From 13 January the bond trades ex-dividend and the accrued interest is minus the interest for the days left to 22 January. The clean price moves smoothly across the ex-dividend date because the dirty price drops by the coupon at the same moment as the accrued interest does.

## 8. How do I find the CTD and net basis for a Treasury futures contract?

For each deliverable bond, `ConversionFactors.cme` gives the CBOT conversion factor and `BasisAnalytics.analyse` the gross basis, carry to delivery at the repo rate, net basis and implied repo. The cheapest to deliver has the highest implied repo. The prices here are the TY March 2025 basket of the golden file (data/golden/analytics_golden.json), computed from a smooth yield curve, with the futures price set just below the fair value of the cheapest bond.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.futures.BasisAnalytics;
import io.ratesalgo.analytics.futures.ConversionFactors;
import io.ratesalgo.analytics.futures.TreasuryFutureContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 8: conversion factors, net basis, implied repo and the CTD. */
public final class Recipe08Ctd {

  private Recipe08Ctd() {}

  public static void main(final String[] args) {
    final TreasuryFutureContract ty = TreasuryFutureContract.TY;
    final int settle = Dates.of(2025, 1, 16);
    final int delivery = Dates.of(2025, 3, 31); // last delivery day of March 2025
    final double futures = 108.359375; // 108-11+ in 32nds
    final double repo = 0.0430;
    // coupon, dated date, maturity, clean price
    final double[][] basket = {
        {0.01375, Dates.of(2021, 11, 15), Dates.of(2031, 11, 15), 82.1546},
        {0.04125, Dates.of(2024, 11, 30), Dates.of(2031, 11, 30), 97.5543},
        {0.02750, Dates.of(2022, 8, 15), Dates.of(2032, 8, 15), 88.8734},
        {0.03500, Dates.of(2023, 2, 15), Dates.of(2033, 2, 15), 93.0604},
    };
    final BasisAnalytics a = new BasisAnalytics();
    final List<BasisAnalytics.Result> results = new ArrayList<>();
    final int first = Dates.of(2025, 3, 1);
    for (final double[] row : basket) {
      final int dated = (int) row[1];
      final int mat = (int) row[2];
      final boolean ok = ty.isDeliverable(ConversionFactors.wholeMonths(first, mat),
          ConversionFactors.wholeMonths(dated, mat));
      final FixedRateBond b = new FixedRateBond(Dates.format(mat), BondConvention.UST, row[0],
          dated, mat);
      final double cf = ConversionFactors.cme(row[0], mat, 2025, 3, ty);
      final BasisAnalytics.Result r = a.analyse(b, row[3], futures, cf, settle, delivery, repo);
      results.add(r);
      System.out.println(String.format(Locale.ROOT,
          "%5.3f%% %s deliverable=%b CF=%.4f gross=%.4f carry=%.4f net=%.4f repo=%.4f%%",
          100 * row[0], r.bond(), ok, cf, r.grossBasis(), r.carry(), r.netBasis(),
          100 * r.impliedRepo()));
    }
    System.out.println("CTD " + results.get(BasisAnalytics.cheapestToDeliver(results)).bond());
  }
}
```

Expected output:

```
1.375% 2031-11-15 deliverable=true CF=0.7541 gross=0.4408 carry=-0.4472 net=0.8880 repo=-0.9431%
4.125% 2031-11-30 deliverable=true CF=0.9003 gross=-0.0016 carry=-0.0284 net=0.0267 repo=4.1674%
2.750% 2032-08-15 deliverable=true CF=0.8111 gross=0.9831 carry=-0.2300 net=1.2132 repo=-2.3159%
3.500% 2033-02-15 deliverable=true CF=0.8468 gross=1.3017 carry=-0.1156 net=1.4172 repo=-3.0752%
CTD 2031-11-30
```

At yields below the 6 percent notional coupon the bond with the shortest duration among the basket tends to be cheapest; here the 4 1/8% of November 2031 has an implied repo of 4.17 percent, just under the 4.30 percent repo rate, and a net basis of 2.7 cents. `AnalyticsGoldenTest.basisImpliedRepoAndCtdMatch` checks these quantities and the CTD choice against the golden file for five contracts.

## 9. How do I compare a result to QuantLib within a stated tolerance?

The comparison is a test, not a program: `rates-analytics/src/test/java/io/ratesalgo/analytics/golden/AnalyticsGoldenTest.java` reads `data/golden/analytics_golden.json` (written by `python -m ratesresearch.goldens` with QuantLib 1.44, API_SPEC.md Section 8.4) and checks every priced instrument with the tolerances of API_SPEC.md Section 7. Each check prints its largest deviation on a line beginning `GOLDEN`.

```bash
mvn -B -ntp -pl rates-analytics -am test -Dtest=AnalyticsGoldenTest -Dsurefire.failIfNoSpecifiedTests=false
```

The figures measured on 2026-10-31 (JDK 25.0.5, Windows 11) are listed in API_SPEC.md Section 7.2. To add a comparison, add the instrument to `research/ratesresearch/goldens/generator.py`, regenerate, and add the assertion with its tolerance named as a constant in the test.

## 10. How do I start a venue simulator and connect a FIX 4.4 session?

`VenueHarness` starts a simulator in the current process on a free local port with a `ManualClock` that only the harness moves; `harness.connect(compId, handler)` opens an engine-side `FixSession` (our codec, not QuickFIX/J) over TCP and waits for the logon. `engine.quiesce()` waits until each side has processed everything the other sent, so the program can print what arrived without racing the network. Here the D2C RFQ simulator asks four dealers for an offer on 10 million of the 10-year; three simulated dealers answer, we quote by hand through the session, and the client buys from the best offer.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.d2c.ClientDecision;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqSpec;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;

/**
 * COOKBOOK.md recipe 10: start the D2C RFQ simulator, connect an engine-side FIX 4.4 session,
 * and quote one RFQ by hand.
 */
public final class Recipe10VenueSession {

  private static final int[] SHOWN = {35, 131, 117, 54, 38, 9501, 297, 694, 44, 9502, 150, 39, 31, 32};

  private Recipe10VenueSession() {}

  public static void main(final String[] args) {
    final String isin = "US91282CZG94"; // on-the-run 10-year in data/refdata/v1 on 2026-11-02
    final ManualClock clock = new ManualClock(Venues.T0);
    final D2cRfqSimulator venue =
        (D2cRfqSimulator) Venues.create("d2c", clock, 7, new InProcessFeedChannel());
    final List<String> received = new ArrayList<>();
    try (VenueHarness harness = new VenueHarness(venue, clock);
        EngineSession engine = harness.connect("RALGO", (s, m, possDup) -> received.add(show(m)))) {
      System.out.println("session " + engine.session().state());

      // A client buys 10mm; three simulated dealers offer 98.03, 98.02 and 98.05.
      final long rfq =
          venue.injectRfq(
              new RfqSpec(isin, true, 10_000_000, 4,
                  List.of(Px.price("98.03"), Px.price("98.02"), Px.price("98.05")),
                  2003, ClientDecision.BEST, false));
      engine.quiesce();

      // Quote by hand: 97.99 bid, 98.015 offer, 10mm each side.
      engine.session().begin(MsgType.QUOTE)
          .put(FixTags.QUOTE_REQ_ID, rfq)
          .put(FixTags.QUOTE_ID, 1)
          .putText(FixTags.SYMBOL, isin)
          .putText(FixTags.SECURITY_ID, isin)
          .putChar(FixTags.SECURITY_ID_SOURCE, (byte) '4')
          .putDecimal(FixTags.BID_PX, Px.price("97.99"), 9)
          .putDecimal(FixTags.OFFER_PX, Px.price("98.015"), 9)
          .put(FixTags.BID_SIZE, 10_000_000)
          .put(FixTags.OFFER_SIZE, 10_000_000);
      engine.session().send();
      engine.quiesce();

      venue.decide(rfq); // the client trades with the best offer
      engine.quiesce();

      received.forEach(System.out::println);
      System.out.println("sequence errors " + engine.session().stats().sequenceErrors());
    }
  }

  private static String show(final FixParser m) {
    final StringBuilder sb = new StringBuilder();
    for (final int tag : SHOWN) {
      final int i = m.indexOf(tag);
      if (i >= 0) {
        sb.append(sb.length() == 0 ? "" : " ").append(tag).append('=').append(m.getStringAt(i));
      }
    }
    return sb.toString();
  }
}
```

Expected output:

```
session ACTIVE
35=R 131=1 54=1 38=10000000 9501=4
35=AI 131=1 117=1 297=0
35=AJ 131=1 117=1 54=1 38=10000000 694=1 44=98.015 9502=98.02
35=8 131=1 117=1 54=1 38=10000000 150=F 39=2 31=98.015 32=10000000
sequence errors 0
```

We won at 98.015 and the venue disclosed the cover, 98.02: 500 USD was left on the table on 10 million face (LEARN.md Section 6). The QuoteStatusReport with 297=0 acknowledges our quote. The fill (35=8, 150=F) carries the QuoteID and no ClOrdID, which is how the gateway tells an RFQ trade from an order fill. The venue validated our Quote against the QuickFIX/J FIX 4.4 dictionary before accepting it.

## 11. How do I subscribe to the SBE book feed and rebuild a book?

The CLOB publishes every change of a top-five price level as an SBE `BookUpdate` with one sequence number per venue; `BookFeedConsumer` applies the frames in sequence. When a frame is missing, it asks the publisher (which is also the snapshot service) for a snapshot, applies it, and carries on from the snapshot's sequence number. Quantities on the feed are minor units, so 5 million face shows as 500000000.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.Px;

/**
 * COOKBOOK.md recipe 11: subscribe to the CLOB's SBE book feed, rebuild the book, and recover
 * from a gap by snapshot.
 */
public final class Recipe11BookFeed {

  private Recipe11BookFeed() {}

  public static void main(final String[] args) {
    final int tenYear = 1007; // US91282CZG94
    final ManualClock clock = new ManualClock(Venues.T0);
    final InProcessFeedChannel feed = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 1, feed);
    final BookFeedConsumer book = new BookFeedConsumer(clob.feed()); // the feed is also the snapshot service

    clob.submitInternal("DLR-A", tenYear, true, Px.price("97.984375"), 5_000_000, 0);
    clob.submitInternal("DLR-B", tenYear, false, Px.price("98.015625"), 3_000_000, 0);
    feed.poll(book, 100);
    print("after 2 orders", book, tenYear);

    feed.dropNext(2); // lose two frames on the wire
    clob.submitInternal("DLR-C", tenYear, true, Px.price("98"), 2_000_000, 0);
    clob.submitInternal("DLR-D", tenYear, false, Px.price("98.0078125"), 4_000_000, 0);
    clob.submitInternal("DLR-E", tenYear, true, Px.price("97.984375"), 1_000_000, 0);
    feed.poll(book, 100);
    print("after a gap", book, tenYear);
    System.out.println("gaps " + book.gaps() + ", recoveries " + book.recoveries()
        + ", venue sequence " + clob.feed().lastSequence() + ", consumer sequence " + book.lastSequence());
  }

  private static void print(final String label, final BookFeedConsumer book, final int instrument) {
    System.out.println(label + ": bids " + levels(book, instrument, Side.Buy) + " offers "
        + levels(book, instrument, Side.Sell));
  }

  private static String levels(final BookFeedConsumer book, final int instrument, final Side side) {
    final StringBuilder sb = new StringBuilder("[");
    book.levels(instrument, side).forEach((price, qty) ->
        sb.append(sb.length() == 1 ? "" : ", ").append(Px.text(price)).append('x').append(qty));
    return sb.append(']').toString();
  }
}
```

Expected output:

```
after 2 orders: bids [97.984375x500000000] offers [98.015625x300000000]
after a gap: bids [98x200000000, 97.984375x600000000] offers [98.0078125x400000000, 98.015625x300000000]
gaps 1, recoveries 1, venue sequence 5, consumer sequence 5
```

The two dropped frames were the Add at 98 and the Add at 98.0078125. The next frame, sequence 5, showed a gap; the snapshot at sequence 5 restored both levels and the consumer ends at the venue's sequence.

## 12. How do I run the event loop against a journal and check determinism?

A live `EventLoop` journals every inbound message into one segmented journal and every message it produces into another (API_SPEC.md Section 9.7). `LoopReplay.run(dir, inboundName, outboundName, factory)` builds a fresh loop from the same `LoopFactory`, replays the inbound journal with a `ManualClock`, chains each produced message with the journal's FNV-1a hash and compares it with the recorded outbound journal. The factory is the unit of determinism: it configures the loop and registers the handlers, and the same factory must build the live loop and the replay loop. Handlers read time only from `loop.clock()` and send only through `loop.publish`.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopFactory;
import io.ratesalgo.core.LoopReplay;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/** COOKBOOK.md recipe 12: run the event loop, replay its journal and compare outbound hashes. */
public final class Recipe12LoopReplay {

  private Recipe12LoopReplay() {}

  /** Quotes every request at a fixed half-spread around 100; the spread is the knob we change. */
  static LoopFactory quoter(final long halfSpread) {
    return builder -> {
      final EventLoop loop = builder.build();
      final MessageHeaderDecoder headerIn = new MessageHeaderDecoder();
      final QuoteRequestDecoder request = new QuoteRequestDecoder();
      final MessageHeaderEncoder headerOut = new MessageHeaderEncoder();
      final QuoteEncoder quote = new QuoteEncoder();
      final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
      loop.register(
          QuoteRequestDecoder.TEMPLATE_ID,
          (templateId, buffer, offset, length) -> {
            request.wrapAndApplyHeader(buffer, offset, headerIn);
            final long now = loop.clock().epochNanos();
            quote.wrapAndApplyHeader(out, 0, headerOut).rfqId(request.rfqId()).quoteId(request.rfqId());
            quote.bidPrice().mantissa(100_000_000_000L - halfSpread);
            quote.askPrice().mantissa(100_000_000_000L + halfSpread);
            quote.sentNanos(now).validUntilNanos(now + 1_000_000_000L);
            loop.publish(0, out, 0, quote.limit());
          });
      return loop;
    };
  }

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe12");
    final ManualClock source = new ManualClock(1_700_000_000_000_000_000L);
    final EventClock clock = new EventClock(source);
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(1 << 16);

    // live: journals named "in" and "out", 4 KiB segments so the journal rolls
    try (SegmentedJournal in = SegmentedJournal.create(dir, "in", 4096, clock);
        SegmentedJournal out = SegmentedJournal.create(dir, "out", 4096, clock)) {
      final EventLoop loop =
          quoter(2_000_000L)
              .create(
                  EventLoop.builder(clock)
                      .inbound(inbound)
                      .outbound(outbound)
                      .inboundJournal(in::append)
                      .outboundJournal(out::append));
      final InboundPublisher gateway = new InboundPublisher(inbound);
      final UnsafeBuffer msg = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
      final MessageHeaderEncoder header = new MessageHeaderEncoder();
      final QuoteRequestEncoder rfq = new QuoteRequestEncoder();
      for (long id = 1; id <= 100; id++) {
        rfq.wrapAndApplyHeader(msg, 0, header)
            .venueId(7)
            .rfqId(id)
            .instrumentId(1001)
            .side(Side.Buy)
            .quantity(10_000_000_000L)
            .currency("USD");
        gateway.offer(msg, 0, rfq.limit());
        if (id % 10 == 0) {
          source.advance(1_000_000L); // 1 ms between bursts of ten
          loop.doWork();
          outbound.read((t, b, i, l) -> {});
        }
      }
      System.out.println("live: inbound segments=" + SegmentedJournal.segmentIndices(dir, "in").length
          + " outbound hash=" + Long.toHexString(out.chainHash()));
    }

    final ReplayResult same = LoopReplay.run(dir, "in", "out", quoter(2_000_000L));
    System.out.println("replay, same handler: matches=" + same.matches()
        + " frames=" + same.inboundFrames()
        + " produced=" + same.outboundProduced()
        + " hash=" + Long.toHexString(same.replayHash()));

    final ReplayResult changed = LoopReplay.run(dir, "in", "out", quoter(2_500_000L));
    System.out.println("replay, wider spread: matches=" + changed.matches()
        + " firstMismatch=" + changed.firstMismatch());

    try (Stream<Path> files = Files.list(dir)) {
      for (final Path p : files.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(dir);
  }
}
```

Expected output:

```
live: inbound segments=3 outbound hash=346d4ca853a38c4f
replay, same handler: matches=true frames=100 produced=100 hash=346d4ca853a38c4f
replay, wider spread: matches=false firstMismatch=1
```

One hundred requests at 57 bytes each fill three 4 KiB inbound segments, and the chain continues across them (API_SPEC.md Section 1.5). Replaying with the same quoter reproduces the outbound hash exactly. Widening the half-spread from 0.002 to 0.0025 changes the bytes of the very first Quote, so the first mismatch is outbound sequence 1. Recorded on 2026-10-31 with JDK 25.0.5 on Windows 11; the output does not depend on the machine, because time comes from the `ManualClock`.

## 13. How do I run the latency job and read the histogram?

The harness in `rates-bench` publishes QuoteRequests at a fixed rate and records two intervals on the loop thread in HdrHistograms (docs/BENCHMARKS.md, DECISIONS.md DR-P2C-08). Run it with:

```bash
mvn -B -ntp -pl rates-bench exec:exec@latency -Dlatency.warmup=60 -Dlatency.measure=300
```

It writes `rates-bench/target/latency/latency-<time>.json` and a `.md` file with rows for the results table. Each interval in the JSON carries its percentiles and the whole histogram as base64 of HdrHistogram's compressed encoding, which `LatencyReport.readHistogram` decodes. The recipe below shows why the decode-to-encode histogram is recorded with `recordValueWithExpectedInterval`: one 10 ms stall at 50,000 messages a second hides 499 messages that would have waited behind it, and only the corrected histogram shows them.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.bench.LatencyReport;
import java.util.Locale;
import org.HdrHistogram.Histogram;

/** COOKBOOK.md recipe 13: read a latency histogram the way the harness writes it. */
public final class Recipe13LatencyHistogram {

  private Recipe13LatencyHistogram() {}

  private static String row(final String name, final Histogram h) {
    return String.format(
        Locale.ROOT,
        "%-10s samples=%d p50=%.1fus p99=%.1fus p99.9=%.1fus max=%.1fus",
        name,
        h.getTotalCount(),
        h.getValueAtPercentile(50.0) / 1_000.0,
        h.getValueAtPercentile(99.0) / 1_000.0,
        h.getValueAtPercentile(99.9) / 1_000.0,
        h.getMaxValue() / 1_000.0);
  }

  public static void main(final String[] args) {
    // 50,000 messages a second: one every 20 us. 9,999 take 10 us; one stalls for 10 ms.
    final long interval = 20_000L;
    final Histogram raw = new Histogram(10_000_000_000L, 3);
    final Histogram corrected = new Histogram(10_000_000_000L, 3);
    for (int i = 0; i < 9_999; i++) {
      raw.recordValue(10_000L);
      corrected.recordValueWithExpectedInterval(10_000L, interval);
    }
    raw.recordValue(10_000_000L);
    corrected.recordValueWithExpectedInterval(10_000_000L, interval);
    System.out.println(row("raw", raw));
    System.out.println(row("corrected", corrected));

    // a harness report holds each interval as a JSON object with a base64 histogram
    final String report = "{\"decodeToEncode\": " + LatencyReport.intervalJson(corrected) + "}";
    final Histogram back = LatencyReport.readHistogram(report, "decodeToEncode");
    System.out.println(row("from JSON", back));
    System.out.println("p99.99 supported: " + report.contains("\"p9999Supported\": true"));
  }
}
```

Expected output:

```
raw        samples=10000 p50=10.0us p99=10.0us p99.9=10.0us max=10002.4us
corrected  samples=10499 p50=10.0us p99=7921.7us p99.9=9805.8us max=10002.4us
from JSON  samples=10499 p50=10.0us p99=7921.7us p99.9=9805.8us max=10002.4us
p99.99 supported: false
```

The raw histogram puts the stall at the maximum and nowhere else, so its p99 is 10 us. The corrected one adds the 499 samples the stall would have delayed (10 ms divided by the 20 us interval, less one), which moves p99 to 7.9 ms. The JSON round trip loses nothing. With 10,499 samples the report flags p99.99 as unsupported, since it needs at least 100,000 (docs/BENCHMARKS.md). P5 extends this recipe with the rate sweep. Recorded on 2026-10-31 with JDK 25.0.5 on Windows 11; the output does not depend on the machine.

## 14. How do I quote an RFQ through the engine and see a quoting parameter's effect on skew?

`Desk` wires the P3 components over the `rates-api` interfaces; here every input is an in-memory stub: one 10-year Treasury, a parametric PV01 of -8.2e-4 per unit of face, and a fair value of 98.25 stamped now. `QuotingEngine.onQuoteRequest` takes the venue, RFQ id, client, instrument, the client's side, quantity in minor units, dealer count, request expiry and journal sequence, and returns FIRM, SUBJECT or PASS; `outcome()` holds the price and the numbers behind it (API_SPEC.md Section 12). `kappa` and `maxSkewBp` in `QuotingConfig` set how strongly inventory moves the quote.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.RfqOutcome;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 14: quote an RFQ through the engine with a stub fair value; see the skew. */
public final class Recipe14QuoteRfq {

  private Recipe14QuoteRfq() {}

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L); // 2026-01-01T12:00Z
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, 2_500_000_000L));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0); // PV01 per unit of face: -820 USD per 1m per bp
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());

    final Desk desk =
        new Desk(
            new Desk.Inputs(
                instruments,
                pv01,
                fair,
                new InMemoryBookSource(4),
                new RiskLimits().bucketPv01(250_000.0),
                new HedgeUniverse(),
                new TableHedgeCostModel(1),
                new RecordingOutboundSink(),
                new RecordingOutboundSink(),
                new RecordingHedgeOrderSink(),
                clock,
                new QuotingConfig().kappa(2e-6).maxSkewBp(2.0),
                TierTable.single(1, new ClientTier(1, 1.0, 10_000_000_000L, true, false)),
                HitRatePreset.shippedDefault(),
                Hedger.config(),
                ClobQuoter.config()));
    final QuotingEngine engine = desk.quoting();

    // a client asks to buy 25m UST10 against four dealers; we quote an offer
    engine.onQuoteRequest(7, 1, 42, 103, Side.Buy, 2_500_000_000L, 4, 0L, 1L);
    print("flat book ", engine.outcome());

    // the same request when we are already long 150m: the offer improves
    desk.risk().onFill(103, 15_000_000_000L, fair.fairMid(103));
    engine.onQuoteRequest(7, 2, 43, 103, Side.Buy, 2_500_000_000L, 4, 0L, 2L);
    print("long 150m ", engine.outcome());

    // and the bid to a client who wants to sell us more is worse
    engine.onQuoteRequest(7, 3, 44, 103, Side.Sell, 2_500_000_000L, 4, 0L, 3L);
    print("long 150m ", engine.outcome());
  }

  private static void print(final String label, final RfqOutcome o) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "%s%s %s %.0fm at %.6f: half %.3fbp, skew %+.3fbp, size %.3fbp, distance %.3fbp, P(win) %.3f",
            label,
            o.decision(),
            o.ourSide(),
            o.quantity() / 1e8,
            Decimal9.toDouble(o.price()),
            o.halfSpreadBp(),
            o.skewBp(),
            o.sizeTermBp(),
            o.distanceBp(),
            o.winProbability()));
  }
}
```

Expected output:

```
flat book FIRM Sell 25m at 98.404645: half 0.938bp, skew +0.000bp, size 0.948bp, distance 1.886bp, P(win) 0.049
long 150m FIRM Sell 25m at 98.240645: half 0.938bp, skew +2.000bp, size 0.948bp, distance -0.114bp, P(win) 0.558
long 150m FIRM Buy 25m at 97.931355: half 0.938bp, skew +2.000bp, size 0.948bp, distance 3.886bp, P(win) 0.002
```

With a flat book the offer is the optimal half-spread plus the size term. After a 150 million fill the inventory skew, `kappa * u' Sigma p / |U|`, would be about 11.4 bp, so it is held at the 2 bp bound; the offer moves 2 bp lower and the bid 2 bp lower, which puts the offer 0.114 bp through fair (within the 0.25 bp improve limit) and the bid 3.886 bp below. The win probabilities come from the shipped default preset (DECISIONS.md DR-P3-02). The quantity is printed in millions of face.

## 15. How do I read the DV01 ladder and compute a cost-aware hedge?

`RiskEngine.ladder` copies the key-rate PV01 ladder of a currency into a `Dv01Vector` (PV01 is PV(rates + 1bp) minus PV(base), so a long bond is negative). `HedgePlanner.plan` chooses lots of each hedge instrument to minimise half of `kappa` times the residual variance plus the modelled cost from a `HedgeCostModel` (API_SPEC.md Section 14); `residualAfter`, `variance` and `cost` evaluate any candidate hedge, so the plan can be compared with the desk's reflex of selling the whole net DV01 in the 10-year future.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgePlanner;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.Locale;

/** COOKBOOK.md recipe 15: read the DV01 ladder and compute a cost-aware hedge. */
public final class Recipe15CostAwareHedge {

  private Recipe15CostAwareHedge() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final String[] names = {"UST2", "UST10", "UST30", "TU", "TY", "US", "SOFR2", "SOFR10", "SOFR30"};
    final double[] tenor = {2, 10, 30, 2, 10, 30, 2, 10, 30};
    final double[] pv01PerUnit = {-1.9e-4, -8.2e-4, -17e-4, -1.9e-4, -8.0e-4, -16e-4, 1.9e-4, 8.2e-4, 17e-4};
    for (int i = 0; i < names.length; i++) {
      final int id = i + 1;
      instruments.add(
          i < 3
              ? Instrument.bond(id, names[i], CurrencyCode.USD, tenor[i], 2_500_000_000L)
              : i < 6
                  ? Instrument.future(id, names[i], CurrencyCode.USD, tenor[i], 5_000_000_000L)
                  : Instrument.swap(id, names[i], CurrencyCode.USD, tenor[i], tenor[i] * 0.85, 0, 0.0, 5_000_000_000L));
      fair.set(id, Decimal9.round(i < 6 ? 100.0 : 3.9), clock.epochNanos());
    }
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    for (int i = 0; i < names.length; i++) {
      pv01.defineAtTenor(i + 1, pv01PerUnit[i], 0.0);
    }
    final RiskEngine risk =
        new RiskEngine(
            instruments, pv01, fair, new RiskLimits(),
            new KillSwitchController(new RecordingOutboundSink(), clock), new RecordingOutboundSink(), clock);

    // a curve position: long 200m 10s, short 400m 2s, long 50m 30s
    risk.onFill(2, 20_000_000_000L, fair.fairMid(2));
    risk.onFill(1, -40_000_000_000L, fair.fairMid(1));
    risk.onFill(3, 5_000_000_000L, fair.fairMid(3));
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    risk.ladder(CurrencyCode.USD, ladder);
    System.out.println("ladder " + ladder);
    System.out.printf(Locale.ROOT, "net %.0f, curve 2s10s %.0f%n", risk.netPv01(CurrencyCode.USD), risk.curveSpreadExposure(CurrencyCode.USD));

    // hedge instruments: futures in lots of 100k face, swaps in lots of 1m notional
    final HedgeUniverse universe = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(8);
    for (int id = 4; id <= 9; id++) {
      final boolean future = id <= 6;
      universe.add(id, future ? 10_000_000L : 100_000_000L, 5_000);
      final double lotPv01 = Math.abs(pv01PerUnit[id - 1]) * (future ? 100_000 : 1_000_000);
      // half spread: futures 0.1 USD per PV01 plus 0.006 basis; swaps 0.15 USD per PV01
      costs.set(id, (future ? 0.1 : 0.15) * lotPv01, future ? 0.006 * lotPv01 : 0.0, future ? 3e-6 * lotPv01 * lotPv01 : 3e-7 * lotPv01 * lotPv01);
    }
    final HedgePlanner planner = new HedgePlanner(universe, risk, KeyRateCovariance.assumedDefault(), costs, 1e-5, 1e-10, 50_000);
    planner.plan(ladder);
    final double[] plan = new double[universe.size()];
    planner.copyLots(plan);
    for (int j = 0; j < universe.size(); j++) {
      System.out.printf(Locale.ROOT, "  %-6s %+8.1f lots%n", names[universe.instrumentId(j) - 1], plan[j]);
    }

    // compare with selling the whole net DV01 in TY at once
    final double[] naive = new double[universe.size()];
    naive[1] = Math.round(-ladder.parallel() / (-8.0e-4 * 100_000));
    final Dv01Vector after = new Dv01Vector(CurrencyCode.USD);
    planner.residualAfter(ladder, plan, after);
    final double planSd = Math.sqrt(planner.variance(after));
    planner.residualAfter(ladder, naive, after);
    final double naiveSd = Math.sqrt(planner.variance(after));
    System.out.printf(Locale.ROOT, "unhedged daily sd %.0f%n", Math.sqrt(planner.variance(ladder)));
    System.out.printf(Locale.ROOT, "all in TY: %+.0f lots, sd %.0f, cost %.0f%n", naive[1], naiveSd, planner.cost(naive));
    System.out.printf(Locale.ROOT, "cost-aware: sd %.0f, cost %.0f, converged %s%n", planSd, planner.cost(plan), planner.converged());
  }
}
```

Expected output:

```
ladder USD [1Y=0.00, 2Y=76000.00, 3Y=0.00, 5Y=0.00, 7Y=0.00, 10Y=-164000.00, 20Y=0.00, 30Y=-85000.00]
net -173000, curve 2s10s -162500
  TU       +708.5 lots
  TY       -268.4 lots
  US        -89.7 lots
  SOFR2    -322.6 lots
  SOFR10   +172.4 lots
  SOFR30    +41.3 lots
unhedged daily sd 1135508
all in TY: -2162 lots, sd 647435, cost 108079
cost-aware: sd 7634, cost 57307, converged true
```

The futures hedge flattens net DV01 but leaves the 2s10s and 30-year risk, so its residual standard deviation is still 647,435; most of its 108,079 cost is impact from 2,162 contracts at once. The planner spreads the hedge across the curve and across futures and swaps, leaving 7,634 at about half the cost. The numbers depend only on the stated PV01s, the default covariance (API_SPEC.md Section 14.2) and the cost table in the recipe.

## 16. How do I set a risk limit and exercise the kill switch?

`RiskLimits` holds the limit set; here a 100,000 PV01 limit on every pillar and a 5 bp price collar. `RiskEngine.checkOrder` and `checkQuote` return 0 when the message may be sent, a `LimitKind` value when a limit refuses it (after sending a RiskLimitBreach to the control sink), or -1 when the kill switch is not ACTIVE (API_SPEC.md Section 13.2). `KillSwitchController.trigger` calls every registered listener and sends one KillSwitch message; resuming needs `requestReset` by one person and `approveReset` by another.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import org.agrona.DirectBuffer;

/** COOKBOOK.md recipe 16: set a risk limit, see a breach, and exercise the kill switch. */
public final class Recipe16LimitAndKillSwitch {

  private Recipe16LimitAndKillSwitch() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, 2_500_000_000L));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0);
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());

    final RecordingOutboundSink control = new RecordingOutboundSink();
    control.listener((templateId, buffer, offset, length) -> print(templateId, buffer, offset));
    final KillSwitchController killSwitch = new KillSwitchController(control, clock);
    killSwitch.register((reason, nanos) -> System.out.println("listener: pull quotes, cancel orders (" + reason + ")"));
    final RiskLimits limits = new RiskLimits().bucketPv01(100_000.0).priceCollarBp(5.0);
    final RiskEngine risk = new RiskEngine(instruments, pv01, fair, limits, killSwitch, control, clock);

    final long px = fair.fairMid(103);
    System.out.println("buy 100m: " + risk.checkOrder(103, Side.Buy, 10_000_000_000L, px, 41));
    System.out.println("buy 150m: " + risk.checkOrder(103, Side.Buy, 15_000_000_000L, px, 42));
    System.out.println("buy 1m 6bp through fair: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px + Decimal9.round(6 * 0.082), 43));

    killSwitch.trigger(KillReason.Manual, 0, 7);
    System.out.println("state " + killSwitch.state() + ", buy 1m: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px, 44));
    killSwitch.requestReset(7);
    System.out.println("same person approves: " + killSwitch.approveReset(7, true) + ", state " + killSwitch.state());
    System.out.println("second person approves: " + killSwitch.approveReset(8, true) + ", state " + killSwitch.state());
    System.out.println("buy 1m: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px, 45));
  }

  private static void print(final int templateId, final DirectBuffer buffer, final int offset) {
    final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    if (templateId == RiskLimitBreachDecoder.TEMPLATE_ID) {
      final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder().wrap(buffer, body, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
      System.out.println(
          "  RiskLimitBreach "
              + d.limitKind()
              + " scope="
              + d.scopeId()
              + " limit="
              + Decimal9.toDouble(d.limitValue().mantissa())
              + " observed="
              + Decimal9.toDouble(d.observedValue().mantissa())
              + " seq="
              + d.rejectedMessageSeqNo());
    } else if (templateId == KillSwitchDecoder.TEMPLATE_ID) {
      final KillSwitchDecoder d = new KillSwitchDecoder().wrap(buffer, body, KillSwitchDecoder.BLOCK_LENGTH, 0);
      System.out.println("  KillSwitch " + d.reason() + " requestedBy=" + d.requestedBy());
    }
  }
}
```

Expected output:

```
buy 100m: 0
  RiskLimitBreach BucketDv01 scope=5 limit=100000.0 observed=-123000.0 seq=42
buy 150m: 2
  RiskLimitBreach PriceCollar scope=103 limit=5.0 observed=6.0 seq=43
buy 1m 6bp through fair: 9
listener: pull quotes, cancel orders (Manual)
  KillSwitch Manual requestedBy=7
state KILLED, buy 1m: -1
same person approves: false, state RESET_PENDING
second person approves: true, state ACTIVE
buy 1m: 0
```

A 100 million purchase adds -82,000 PV01 at 10Y and passes. 150 million would reach -123,000 and is refused with BucketDv01 (value 2), scope 5 (the 10Y pillar), and the journal sequence of the refused message. A price 6 bp through fair is refused by the collar (value 9). While killed every check returns -1; the same operator cannot approve their own reset.

## 17. How do I route a hedge order and read the markout report?

Numbered 17 in the integrated cookbook: the P4 branch wrote it as 17, and when the phase branches were combined the recipes were renumbered to run contiguously by phase (P2 10 to 13, P3 14 to 16, P4 17, P5 18 to 20, P6 21; DECISIONS.md DR-P4-05, amended).

`SmartOrderRouter` takes a parent order through `ParentOrderSink.submit` (the P3 hedger's `HedgeOrderSink` is bound to it), cuts it into randomised slices, prices every venue with the cost model of API_SPEC.md Section 15.1, allocates each slice, and journals the cost table as RouteDecision (template 80) and RouteDecisionVenue (template 81) frames before any child order goes out. Here the router runs against two in-memory CLOBs (`SimConfig.ust10yTwoVenues()`), and a `DecisionCodec` behind the journal sink decodes each decision as it is written, so the table printed is the one in the journal. The last two lines are a small markout report: the cost against the arrival mid, and the mid's move 30 seconds after the last fill, both in basis points of price.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.CostTable;
import io.ratesalgo.router.DecisionCodec;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 17: route a hedge order across two venues and read the decisions. */
public final class Recipe17RouteHedge {

  private Recipe17RouteHedge() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final SimConfig config = SimConfig.ust10yTwoVenues();
    final SimMarket market = new SimMarket(config, 2026L, clock);

    // every RouteDecision and its venue rows are decoded as they are journaled
    final DecisionCodec codec = new DecisionCodec();
    final List<String> decisions = new ArrayList<>();
    final FrameSink journal =
        (templateId, version, buffer, offset, length) -> {
          if (codec.onFrame(templateId, buffer, offset, length)) {
            final CostTable t = codec.decoded();
            decisions.add(decisions.isEmpty() ? t.format() : t.reason() + " " + t.allocatedQuantity() / RouterPresets.ONE_MILLION);
          }
        };
    final SmartOrderRouter router =
        new SmartOrderRouter(
            RouterPresets.global(),
            config.routerVenues(0.25, 5),
            market,
            market,
            journal,
            clock,
            RandomSource.seeded(17L),
            4,
            64,
            1L);

    // a hedge of 20 million face, as the hedger would hand it over (urgency 0.5, no limit)
    final long parentId = 1;
    final double arrivalMid = market.referenceMid() / 1e9;
    router.submit(parentId, 1001, Side.Buy, 20 * RouterPresets.ONE_MILLION, 0.5, 0L);
    int steps = 0;
    while (router.isActive(parentId)) {
      clock.advance(config.stepNanos());
      market.step();
      market.drain(router);
      router.poll();
      steps++;
    }

    final double filled = market.parentFilled(parentId);
    final double average = market.parentNotional(parentId) / filled;
    final double fees = market.parentFees(parentId) / filled;
    System.out.print(decisions.get(0));
    System.out.println("later decisions (reason, millions allocated): " + decisions.subList(1, decisions.size()));
    System.out.println(
        String.format(
            Locale.ROOT,
            "filled %.0fm in %.1fs, average %.6f, arrival mid %.6f, cost %.3f bps incl. fees",
            filled / RouterPresets.ONE_MILLION,
            steps * config.stepNanos() / 1e9,
            average,
            arrivalMid,
            (average - arrivalMid + fees) / arrivalMid * 1e4));

    // markout: where the mid is 30 seconds after the last fill, against the average price
    for (int i = 0; i < 300; i++) {
      clock.advance(config.stepNanos());
      market.step();
      market.drain(router);
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "30s markout %.3f bps (positive: the mid rose after we bought)",
            (market.referenceMid() / 1e9 - average) / arrivalMid * 1e4));
  }
}
```

Expected output:

```
decision 1 parent 1 Slice Buy slice 0 qty 4.0 alloc 4.0 horizon 15.0s expected -0.001300
venue kind    bid        ask          same    opp    cap P(fill) impact    passive   aggress   chosen     alloc mode excluded
1     Clob    98.500000  98.515625    12.0  120.0   36.0 0.8161  0.002402  -0.001065 0.010766  -0.001065    3.0 pass None
2     Clob    98.500000  98.515625     6.0   60.0   18.0 0.8345  0.001961  -0.002003 0.010239  -0.002003    1.0 pass None
later decisions (reason, millions allocated): [Slice 4, Escalation 3, Slice 3, Slice 3, Slice 6]
filled 20m in 51.0s, average 98.502344, arrival mid 98.507813, cost -0.534 bps incl. fees
30s markout 0.555 bps (positive: the mid rose after we bought)
```

The first decision placed the 4 million slice passively on both venues, because the queue model gave a fill probability above 0.8 within the 15-second horizon and the passive cost (negative: half the spread is earned) beat crossing. One passive child reached its time limit and its 3 million escalated aggressively. The parent cost about half a basis point less than the arrival mid; the positive markout says the mid rose after we finished buying, which in this synthetic market is the random walk, not information.

## 18. How do I run the back-tester on one synthetic day?

`SyntheticDay` writes one session of QuoteRequests, BookUpdates and CurveInputs to a journal from a date and a seed; the presets in `data/presets.yaml` fix the market, the clients and the flow profile fitted from DTCC (API_SPEC.md Section 16.5). `BacktestConfig.fromPresets` builds the outcome models at one point of the declared win-probability band, and `BacktestRunner.run` replays the journal through a `Strategy` and returns fills, RFQ outcomes and the P&L attribution (Section 16.1). Running the same strategy at the three band points is how every result in this project is reported (DR-008).

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 18: run the back-tester on one synthetic day at every band point. */
public final class Recipe18Backtest {

  private Recipe18Backtest() {}

  public static void main(final String[] args) throws IOException {
    final Presets presets = Presets.load(RepoRoot.find().resolve("data/presets.yaml"));
    final Path dir = Files.createTempDirectory("recipe17");
    final Path journal = dir.resolve("2026-02-02.raj");
    final LocalDate date = LocalDate.of(2026, 2, 2);
    final SyntheticDay.Summary day =
        new SyntheticDay(SyntheticDay.Params.fromPresets(presets))
            .generate(date, 5_000_000L + date.toEpochDay(), journal, null);
    System.out.println(
        "journal: " + day.frames() + " frames, " + day.rfqs() + " rfqs, chain hash " + Long.toHexString(day.chainHash()));

    for (final BandPoint point : BandPoint.values()) {
      final BacktestConfig config = BacktestConfig.fromPresets(presets, List.of(journal), 7L, point, null);
      final BacktestResult r =
          new BacktestRunner(config).run(new FixedSpreadStrategy(0.30, 0.15, 100_000, 4));
      final long won = r.rfqs().stream().filter(RfqRecord::won).count();
      final Attribution a = r.total();
      System.out.println(
          String.format(
              Locale.ROOT,
              "%-7s won %d of %d, hedges %d, spread %.2f, inventory %.2f, hedge %.2f, fees %.2f,"
                  + " funding %.2f, P&L %.2f, residual below a cent: %b",
              point.label(), won, r.rfqs().size(), r.hedgeFills(), a.spreadCapture(), a.inventoryMtm(),
              a.hedgeCost(), a.fees(), a.funding(), a.positionPnl(), Math.abs(a.residual()) < 0.01));
    }
    Files.delete(journal);
    Files.delete(dir);
  }
}
```

Expected output:

```
journal: 94571 frames, 398 rfqs, chain hash f136c9dbcf88d7b1
low     won 180 of 398, hedges 11, spread 1601139.62, inventory 2204880.69, hedge -850804.69, fees -54451.50, funding -600.02, P&L 2900164.11, residual below a cent: true
central won 163 of 398, hedges 14, spread 1639687.50, inventory 1230784.67, hedge -963445.31, fees -61660.50, funding 1292.74, P&L 1846659.10, residual below a cent: true
high    won 129 of 398, hedges 15, spread 1748176.93, inventory 2386981.21, hedge -1116281.25, fees -71442.00, funding 2740.75, P&L 2950175.63, residual below a cent: true
```

The journal line is the same on every machine because the generator is seeded and the hash is the RAJ1 chain hash. The three band points see the same RFQs, competitors and uniform draws (common random numbers keyed by RFQ id), so the only difference between the rows is the slope of the win probability: the high point, where price matters most, wins the fewest RFQs. Inventory dominates the day's P&L, which is why one session says little and experiments use many (recipe 19).

## 19. How do I pre-register an experiment and record it in the ledger?

A pre-registration (API_SPEC.md Section 16.8) is a YAML file that names the hypothesis, metric, baseline, treatments, evaluation period, fitting period, holdout dates, success threshold, assumption band, author, date and the SHA-256 of the experiment config, written before the experiment runs. `ExperimentCli` refuses to run without one, refuses a holdout that overlaps the fitting or evaluation period, refuses a config whose hash has changed, and refuses to open a holdout twice. A run that passes is recorded in the ledger whatever its verdict, with the hashes of the pre-registration, config and journals and a hash chain over the ledger lines.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.prereg.ExperimentCli;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** COOKBOOK.md recipe 19: pre-register an experiment, see the refusals, record a run. */
public final class Recipe19Ledger {

  private Recipe19Ledger() {}

  public static void main(final String[] args) throws IOException {
    // a scratch repository with the real presets and a copy of the P5-001 experiment config
    final Path repo = Files.createTempDirectory("recipe18");
    Files.createDirectories(repo.resolve("data/experiments"));
    Files.createDirectories(repo.resolve("data/preregs"));
    Files.copy(RepoRoot.find().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
    Files.copy(
        RepoRoot.find().resolve("data/experiments/P5-001.config.yaml"),
        repo.resolve("data/experiments/demo.config.yaml"));
    final String configSha = Hashing.sha256TextFile(repo.resolve("data/experiments/demo.config.yaml"));

    final String prereg =
        """
        prereg_version: 1
        id: DEMO-001
        title: cookbook demonstration
        hypothesis: the model spread earns more than the fixed spread
        metric: total position P&L per session, USD
        baseline: fixed_spread
        treatments: [model_spread]
        period: {start: 2026-03-02, end: 2026-03-03}
        fitting_period: {start: 2026-01-05, end: 2026-01-16}
        holdout: [HOLDOUT]
        success_threshold: Holm-adjusted Welch p below 0.05 and bootstrap interval above zero at every band point
        assumption_band: {preset: usd_rfq_hit_rate, parameter: spread_to_cover_bp, points: [low, central, high]}
        author: cookbook
        date: 2026-10-31
        config: data/experiments/demo.config.yaml
        config_sha256: SHA
        """
            .replace("SHA", configSha);
    final Path file = repo.resolve("data/preregs/demo.yaml");

    System.out.println("1. no pre-registration:");
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("2. holdout overlapping the fitting period:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-01-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("3. valid pre-registration:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-03-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml", "--commit", "recipe18");

    final Ledger ledger = new Ledger(repo.resolve("data/ledger/ledger.jsonl"));
    final Map<String, Object> entry = ledger.entries().get(0);
    System.out.println(
        "ledger seq "
            + entry.get("seq")
            + ", prereg_sha256 matches file: "
            + Hashing.sha256TextFile(file).equals(entry.get("prereg_sha256"))
            + ", journals "
            + ((java.util.List<?>) entry.get("journals")).size()
            + ", verdict "
            + entry.get("verdict"));

    System.out.println("4. holdout opened twice:");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout", "--commit", "recipe18");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout");
    System.out.println("ledger lines: " + ledger.entries().size());
  }

  private static void run(final Path repo, final String... args) throws IOException {
    final Path work = Files.createDirectories(repo.resolve("work"));
    final String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--repo";
    all[args.length + 1] = repo.toString();
    all[args.length + 2] = "--work";
    all[args.length + 3] = work.toString();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final int code;
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      code = ExperimentCli.run(all, out);
    }
    final String text =
        bytes.toString(StandardCharsets.UTF_8).replace(repo.toString(), "<repo>").replace('\\', '/');
    for (final String line : text.strip().split("\\R", -1)) {
      System.out.println("   " + line.strip());
    }
    System.out.println("   exit code " + code);
  }
}
```

Expected output:

```
1. no pre-registration:
   refused:
   pre-registration not found: <repo>/data/preregs/demo.yaml
   exit code 2
2. holdout overlapping the fitting period:
   refused:
   holdout date 2026-01-09 overlaps the fitting period 2026-01-05..2026-01-16
   exit code 2
3. valid pre-registration:
   DEMO-001 evaluation look 1: not supported (2 days)
   recorded in data/ledger/ledger.jsonl
   exit code 0
ledger seq 1, prereg_sha256 matches file: true, journals 2, verdict not supported
4. holdout opened twice:
   DEMO-001 holdout look 1: not supported (2 days)
   recorded in data/ledger/ledger.jsonl
   exit code 0
   refused: the holdout of DEMO-001 has already been opened once
   exit code 2
ledger lines: 2
```

Refused runs read no data and leave the ledger untouched; the two recorded runs (evaluation, then the holdout once) are the two ledger lines. The real experiment of this phase, P5-001, went through the same command on 2026-10-31 and its record is the first line of `data/ledger/ledger.jsonl` (LEARN.md Section 10).

## 20. How do I reconcile attribution to position P&L?

The runner books every trade twice: as cash and position (position-based P&L is cash plus the final position at the final mid) and as attribution lines (spread capture, inventory mark-to-market, hedge cost, carry and roll, funding, fees). Because valuation is linear in price the two agree identically, and the residual must stay below one cent on every run (API_SPEC.md Section 16.4). This recipe is in Python: `ratesresearch.backtest_analysis.reconcile` checks a run's tables independently of the Java code, rebuilding spread capture, hedge cost and fees from the fills. The input is a fitting-period run committed as a fixture under `data/fixtures/backtest/fitting/`.

```python
"""COOKBOOK.md recipe 20: reconcile a run's attribution to its position-based P&L."""

from pathlib import Path

from ratesresearch import backtest_analysis as ba

root = Path(__file__).resolve().parents[2]
run = ba.load_run(root / "data/fixtures/backtest/fitting/2026-01-05")
total = run.pnl[run.pnl["instrument"] == "TOTAL"].iloc[0]

explained = 0.0
for line in ba.LINES:
    explained += float(total[line])
    print(f"{line:<15} {float(total[line]):>16,.2f}")
print(f"{'explained':<15} {explained:>16,.2f}")
print(f"{'position_pnl':<15} {float(total['position_pnl']):>16,.2f}")
print(f"{'residual':<15} {float(total['residual']):>16,.6f}")

client = run.fills[run.fills["kind"] == "client"]
hedge = run.fills[run.fills["kind"] == "hedge"]
print(f"client fills {len(client)}, sum of edges {client['edge'].sum():,.2f}")
print(f"hedge fills {len(hedge)}, sum of edges {hedge['edge'].sum():,.2f}")
failures = ba.reconcile(run)
print("reconciles to the cent:", not failures)
```

Expected output:

```
spread_capture      1,986,264.94
inventory_mtm      -1,547,896.30
hedge_cost         -1,633,546.88
carry_roll                  0.00
funding                -1,940.61
fees                 -104,547.00
explained          -1,301,665.84
position_pnl       -1,301,665.84
residual                0.000000
client fills 144, sum of edges 1,986,264.94
hedge fills 13, sum of edges -1,633,546.88
reconciles to the cent: True
```

The explained lines sum to the position P&L exactly at two decimals, and the sums of fill edges equal the spread-capture and hedge-cost lines. Inventory is the line the fills alone cannot rebuild, since it needs the mid path; it is the difference that the identity pins down. `tools/check_cookbook.py` runs this script in CI and compares its output with the block above.

## 21. How do I run the chaos test and read the break report?

`SyntheticDay.generate(journal, seed, params)` writes a trading day to a journal: two Treasuries traded by order on venue 1 and a USD SOFR swap traded by RFQ on venue 2, with faults injected into our side (duplicate fills, fills delivered out of order, fills delivered after the 17:00 reconciliation cut, busts, one bust that arrives before its fill, a bust after the cut) and duplicate lines in the venue's drop copy. The day depends only on the seed. The program books the journal twice with `PositionKeeper`, once up to the cut sequence and once to the end, submits the cleared swaps to the clearing simulator, and runs `DailyReconciliation`, which prints one line per reconciliation and one line per break. The acceptance test `ChaosDayTest` runs the same day and asserts that nothing is unexplained (API_SPEC.md Section 17.8).

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.clearing.ClearingHouseSimulator;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.BreakReport;
import io.ratesalgo.posttrade.recon.DailyReconciliation;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import io.ratesalgo.posttrade.trade.Trade;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** COOKBOOK.md recipe 21: run the chaos day and read the break report. */
public final class Recipe21ChaosBreakReport {

  private Recipe21ChaosBreakReport() {}

  static PositionKeeper keeper(final Path journal, final long lastSequence) {
    final InstrumentCatalogue catalogue = new InstrumentCatalogue()
        .add(new InstrumentReference(1001, "US91282CLF0X", "USD", "PERC", BigDecimal.ONE))
        .add(new InstrumentReference(1002, "US91282CLG0X", "USD", "PERC", BigDecimal.ONE))
        .add(new InstrumentReference(2001, "USD-SOFR-10Y", "USD", "PERC", new BigDecimal("8.5")));
    final FixedBookMapper books = new FixedBookMapper(new BookAssignment(1, 10, "ALG-UST-MM-01"))
        .with(2001, new BookAssignment(2, 20, "ALG-SWP-RFQ-01"));
    final PositionKeeper k = new PositionKeeper(books, catalogue);
    try (JournalReader r = JournalReader.open(journal)) {
      while (r.next((s, t, id, v, b, o, l) -> {
        if (s <= lastSequence) {
          k.onFrame(s, t, id, v, b, o, l);
        }
      })) {
        // reading
      }
    }
    return k;
  }

  static BreakReport reconcile(final SyntheticDay.Result day, final long cut) {
    final PositionKeeper atCut = keeper(day.journal(), cut);
    final PositionKeeper full = keeper(day.journal(), Long.MAX_VALUE);
    final ClearingHouseSimulator ccp = new ClearingHouseSimulator((k, why) -> { }).limit(20, Long.MAX_VALUE);
    for (final Trade t : atCut.trades()) {
      if (t.isLive() && t.instrumentId() == SyntheticDay.SWAP) {
        ccp.submit(t, SyntheticDay.CUT);
      }
    }
    ccp.endOfCycle(SyntheticDay.CUT);
    return DailyReconciliation.run(new DailyReconciliation.Inputs(day.date(), atCut, full, cut, day.dropCopy(),
        atCut.orderRecords(), ccp.clearedReport(), i -> i == SyntheticDay.SWAP));
  }

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe20");
    final Path journal = dir.resolve("day.raj");
    final SyntheticDay.Result day = SyntheticDay.generate(journal, 20261030L, SyntheticDay.Params.DEFAULT);
    System.out.println("day " + day.date() + ": frames=" + day.frames() + " fills=" + day.fills()
        + " cut-sequence=" + day.cutSequence());
    System.out.println("injected: " + day.injected());

    System.out.print(reconcile(day, day.cutSequence()).render());
    final BreakReport endOfDay = reconcile(day, Long.MAX_VALUE);
    System.out.println("after every late message: breaks=" + endOfDay.breaks().size()
        + " unexplained=" + endOfDay.unexplained());
    Files.delete(journal);
    Files.delete(dir);
  }
}
```

Expected output:

```
day 2026-10-30: frames=336 fills=105 cut-sequence=332
injected: duplicates=5 late-within-day=2 late-after-cut=3 busts=3 busts-after-cut=1 drop-copy-duplicates=2
break report 2026-10-30
  drop-copy          ours=99 theirs=101 matched=98 dup-suppressed=2 breaks=4 unexplained=0
  drop-copy-pos      ours=3 theirs=3 matched=3 dup-suppressed=0 breaks=0 unexplained=0
  books-records      ours=99 theirs=99 matched=99 dup-suppressed=0 breaks=0 unexplained=0
  clearing           ours=1 theirs=1 matched=1 dup-suppressed=0 breaks=0 unexplained=0
  pending-busts      ours=0 theirs=0 matched=0 dup-suppressed=0 breaks=0 unexplained=0
  drop-copy 1:E:800024 MISSING_THEIRS BUSTED_AFTER_CUT ours[Sell 400000000@100843750000 t=1793354580002000000] theirs[-]
  drop-copy 1:E:800050 MISSING_OURS LATE_ARRIVAL ours[-] theirs[Buy 600000000@100531250000 t=1793358360002000000]
  drop-copy 1:E:800071 MISSING_OURS LATE_ARRIVAL ours[-] theirs[Buy 400000000@100812500000 t=1793362140003000000]
  drop-copy 2:R:70024 MISSING_OURS LATE_ARRIVAL ours[-] theirs[Buy 41000000000@4252500000 t=1793366342000000000]
unexplained breaks: 0
after every late message: breaks=0 unexplained=0
```

How to read it. The first block has one line per reconciliation: `ours` and `theirs` are the items on each side after identical repeats are suppressed (`dup-suppressed=2` is the two duplicated drop-copy lines), `matched` agree on side, quantity, price to the mantissa and transaction time to one second, and `unexplained` counts breaks whose explanation does not clear them. The break lines give the reconciliation, the trade key (`venue:E:execId` for an order fill, `venue:R:quoteId` for an RFQ trade), the class and the explanation the journal supports. The three `MISSING_OURS LATE_ARRIVAL` breaks are the fills injected after the cut: the venue has them, our snapshot at the cut does not, and the full journal shows them arriving later. The `MISSING_THEIRS BUSTED_AFTER_CUT` break is the trade the venue busted after the cut. `drop-copy-pos` shows no break because the position reconciliation adjusts our side for exactly these explained trade breaks. The duplicate fills and the in-day busts produce no break at all: the keeper suppressed and applied them, and its audit trail records each one. The last line reconciles again after every late message has arrived and finds nothing. A break with explanation `NONE` or `DUPLICATE_CONFLICT` goes to operations under docs/runbooks/reconciliation-break.md.
