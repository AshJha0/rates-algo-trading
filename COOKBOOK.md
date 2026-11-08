# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine. Recipes 4 to 8 (P1) print values rounded well above double-precision noise so that they are the same on every platform. Recipes 14 to 16 (P3) were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11. Recipes 23 to 34 (round 2) were recorded on 2026-10-31 with JDK 25.0.5 and Python 3.12 on Windows 11; the analytics recipes among them (23 to 26) round their output in the same way as recipes 4 to 8.

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
22. How do I run the trading process on a synthetic day and read the daily report? (round 2, written below; the brief for round 2 assigned this number)
23. How do I fit a Nelson-Siegel-Svensson curve to Treasury yields and read rich and cheap bonds? (round 2, written below)
24. How do I compute z-spread and asset-swap spread of a bond to the OIS curve? (round 2, written below)
25. How do I compute carry and roll-down and a repo-implied forward price for a Treasury? (round 2, written below)
26. How do I price a compounded-in-arrears SOFR leg with lookback and lockout? (round 2, written below)
27. How do I price a curve or butterfly RFQ as one package with a net DV01 ladder? (round 2, written below)
28. How do I see the quote lifecycle controls pull stale quotes (time to live, stale reference, feed gap)? (round 2, written below)
29. How do I replay a journal and rebuild the router's decision table for one parent order? (round 2, written below)
30. How do I run the CLOB simulator through a workup and read the book feed? (round 2, written below)
31. How do I compute key-rate hedge ratios with PCA from the public Treasury curve fixture? (round 2, written below; a Python recipe)
32. How do I fit the hit-rate model and read its calibration across the assumption band? (round 2, written below; a Python recipe)
33. How do I compute markout curves by client tier from a back-test run? (round 2, written below; a Python recipe)
34. How do I validate a deployment config against its schema and read the error? (round 2, written below)
35. How do I verify a Java journal's hash chain from Python? (written below; a Python recipe; was 23)
36. How do I check that a hot-path package allocates nothing? (written below; was 22)
37. How do I check a forward-log ledger from Python? (written below; a Python recipe)

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

## 22. How do I run the trading process on a synthetic day and read the daily report?

The trading process is `TradingProcess` in `rates-app`: P2's D2C RFQ and CLOB simulators started in this JVM, our FIX gateways connected to them over TCP, the CLOB's SBE book feed, a curve feed and one event loop for the USD instrument group with its journals (API_SPEC.md Section 18). `step(nanos, stepNanos)` moves simulated time and pumps everything on the calling thread until the FIX sessions are quiet, so a seeded day repeats exactly.

The program runs ten simulated minutes from 08:25 New York on seed 7, across the 08:30 peak of the D2C arrival model, then pulls the kill switch and runs one more second. It then does what the desk does after the close: it books the journals into the position keeper (`JournalFeed`), reconciles the keeper with both venues' trade registers (`VenueReconciliation`), replays the journals through a freshly built desk (`TradingProcessMain.replayGroup`, the same check as `--replay`), and runs P5's back-tester over the recorded day with the desk as its strategy (`DeskBacktest`), which writes the daily report. The program prints the report's title and its P&L total row.

Positions print as book/account/instrument: book 1 holds the RFQ trades in the five SOFR swaps, book 2 the Treasury hedges the routers bought and sold on the CLOB. The back-test's numbers differ from the live day's because the back-tester settles RFQs with P5's outcome model and fills hedges with its hedge cost model (DECISIONS.md DR-R2W-11); its P&L columns are those of API_SPEC.md Section 16.4.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.app.TradingProcessMain;
import io.ratesalgo.app.backtest.DeskBacktest;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.posttrade.VenueReconciliation;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.posttrade.position.Position;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** COOKBOOK.md recipe 22: run the trading process on a synthetic day and read the daily report. */
public final class Recipe22TradingDay {

  private Recipe22TradingDay() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe22");
    // the D2C and CLOB simulators, both gateways and the USD loop, from 08:25 New York
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 7L, DeskSettings.defaults()));
    final DeskGroup desk = p.group(0).desk();
    try {
      p.step(10 * 60_000_000_000L, 10_000_000L);
      System.out.println(String.format(Locale.ROOT,
          "10 simulated minutes: %d RFQs, %d quotes, %d RFQ fills, %d hedge parents, %d hedge fills",
          desk.desk().quoting().rfqs(), desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(),
          desk.hedgesRouted(), desk.hedgeFills()));
      p.kill(9001);
      p.step(1_000_000_000L, 10_000_000L);
      System.out.println("after the kill switch: " + p.statusJson());
    } finally {
      p.close();
    }

    // post-trade: positions from the journals, reconciled with the venues' trade registers
    final PositionKeeper keeper =
        JournalFeed.keeper(p.group(0).dir(), "in", "out", JournalFeed.books(p.universe()),
            JournalFeed.catalogue(desk.pricing()));
    for (final Position pos : keeper.positions()) {
      System.out.println(String.format(Locale.ROOT, "  %s net %,d", pos.key().text(), pos.netQuantity()));
    }
    final ReconResult recon = VenueReconciliation.run(keeper, p.venues().d2c(), p.venues().clob());
    System.out.println(String.format(Locale.ROOT, "reconciliation: %d ours, %d theirs, %d matched, %d breaks",
        recon.oursCount(), recon.theirsCount(), recon.matched(), recon.breaks().size()));

    // the same journals through a fresh desk: the outbound stream must repeat exactly
    final ReplayResult replay = TradingProcessMain.replayGroup(p.group(0).dir());
    System.out.println(String.format(Locale.ROOT, "replay: %d inbound frames, %d of %d outbound messages, %s",
        replay.inboundFrames(), replay.outboundProduced(), replay.outboundRecorded(),
        replay.matches() ? "hash matches" : "MISMATCH"));

    // the daily report: the back-tester replays the day with the desk as its strategy
    final DeskBacktest.Run run = DeskBacktest.run(p.group(0).dir(), DeskBacktest.presets(), dir.resolve("report"), 1L);
    System.out.println(String.format(Locale.ROOT, "back-test: %d RFQs, %d client fills, %d hedge fills",
        run.result().rfqs().size(), run.result().clientFills(), run.result().hedgeFills()));
    final String report = Files.readString(dir.resolve("report").resolve("report.md"));
    report.lines().filter(l -> l.startsWith("| TOTAL") || l.startsWith("# ")).forEach(System.out::println);
  }
}
```

Expected output (recorded 2026-10-31 after the round 2 review merge, JDK 25.0.5, Windows 11; `SmokeTest` asserts the same day's counts; DR-INT-05):

```
10 simulated minutes: 36 RFQs, 27 quotes, 16 RFQ fills, 210 hedge parents, 213 hedge fills
after the kill switch: {"quoting":false,"killSwitch":true,"liveQuotes":0,"quotesSent":27,"fills":16,"hedgesRouted":210,"liveChildOrders":0,"groups":1,"simulatedNanos":601000000000}
  1/1/2001 net 1,000,000,000
  1/1/2002 net 7,500,000,000
  1/1/2003 net 10,000,000,000
  1/1/2004 net -14,500,000,000
  1/1/2005 net -4,000,000,000
  2/1/1003 net 500,000,000
  2/1/1006 net 7,400,000,000
  2/1/1007 net 7,300,000,000
  2/1/1009 net -14,000,000,000
reconciliation: 229 ours, 229 theirs, 229 matched, 0 breaks
replay: 96864 inbound frames, 240 of 240 outbound messages, hash matches
back-test: 36 RFQs, 14 client fills, 171 hedge fills
# Daily back-test report, 2026-11-02
| TOTAL | 54049.48 | -19345.88 | -45781.25 | 0 | 10.45 | -439.5 | 0 | -11506.7 |
```

## 23. How do I fit a Nelson-Siegel-Svensson curve to Treasury yields and read rich and cheap bonds?

`NelsonSiegelSvensson.fit(maturities, yields, start, rms)` fits the six Svensson parameters by Levenberg-Marquardt. The program fits them to the Treasury par curve of 2025-01-15 from `data/fixtures/ust_par_curve_2015_2025.csv` (13 tenors, in percent), then reads ten coupon Treasuries from the 2024 auction fixture against the fitted curve: the residual is the bond's street yield minus the curve at its remaining maturity, positive for a bond that is cheap (its yield is above the curve), and `px vs fit` turns it into price per 100 with the bond's yield DV01. The street yields are declared marks for the day, because no free source publishes end-of-day prices for individual Treasuries.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.bond.NelsonSiegelSvensson;
import io.ratesalgo.analytics.date.Dates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 23: fit a Nelson-Siegel-Svensson curve to Treasury yields; rich and cheap. */
public final class Recipe23NssRichCheap {

  private Recipe23NssRichCheap() {}

  static final int TODAY = Dates.of(2025, 1, 15);
  static final double[] TENORS = {1 / 12.0, 2 / 12.0, 0.25, 4 / 12.0, 0.5, 1, 2, 3, 5, 7, 10, 20, 30};

  /** Coupon Treasuries from data/fixtures/ust_auctions_2024.csv with declared street yields. */
  static final Object[][] BASKET = {
      {"91282CME8", 0.04250, "2024-12-31", "2026-12-31", 4.270},
      {"91282CMB4", 0.04000, "2024-12-16", "2027-12-15", 4.335},
      {"91282CMA6", 0.04125, "2024-12-02", "2029-11-30", 4.440},
      {"91282CMD0", 0.04375, "2024-12-31", "2029-12-31", 4.452},
      {"91282CLZ2", 0.04125, "2024-12-02", "2031-11-30", 4.552},
      {"91282CMC2", 0.04500, "2024-12-31", "2031-12-31", 4.561},
      {"91282CLF6", 0.03875, "2024-08-15", "2034-08-15", 4.652},
      {"91282CLW9", 0.04250, "2024-11-15", "2034-11-15", 4.660},
      {"912810UF3", 0.04625, "2024-12-02", "2044-11-15", 4.951},
      {"912810UE6", 0.04500, "2024-11-15", "2054-11-15", 4.882},
  };

  static FixedRateBond bond(final Object[] row) {
    return new FixedRateBond((String) row[0], BondConvention.UST, (double) row[1],
        Dates.parse((String) row[2]), Dates.parse((String) row[3]));
  }

  /** The Treasury par curve of 2025-01-15 (percent) from data/fixtures/ust_par_curve_2015_2025.csv. */
  static double[] parCurve() {
    try {
      final List<String> lines = Files.readAllLines(
          RepoRoot.find().resolve("data/fixtures/ust_par_curve_2015_2025.csv"), StandardCharsets.UTF_8);
      for (final String line : lines) {
        if (line.startsWith(Dates.format(TODAY))) {
          final String[] f = line.split(",", -1);
          final double[] y = new double[TENORS.length];
          for (int i = 0; i < y.length; i++) {
            y[i] = Double.parseDouble(f[i + 1]);
          }
          return y;
        }
      }
      throw new IllegalStateException("no par curve row for " + Dates.format(TODAY));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The fitted curve, shared with recipe 25. */
  static NelsonSiegelSvensson fit(final double[] rms) {
    final double[] start = {4.5, -0.3, -1.0, 1.0, 1.5, 10.0};
    return NelsonSiegelSvensson.fit(TENORS, parCurve(), start, rms);
  }

  public static void main(final String[] args) {
    final double[] rms = new double[1];
    final NelsonSiegelSvensson nss = fit(rms);
    final double[] p = nss.parameters();
    System.out.println(String.format(Locale.ROOT,
        "NSS b0=%.3f b1=%.3f b2=%.3f b3=%.3f t1=%.3f t2=%.3f  rms %.2f bp",
        p[0], p[1], p[2], p[3], p[4], p[5], 100 * rms[0]));

    final BondPricer pricer = new BondPricer();
    System.out.println("cusip      maturity    years  yield%  fit%    resid bp  px vs fit  verdict");
    for (final Object[] row : BASKET) {
      final FixedRateBond b = bond(row);
      final int settle = b.settlementDate(TODAY);
      final double t = (b.maturity() - settle) / 365.25;
      final double y = (double) row[4];
      final double fitted = nss.zero(t);
      final double residBp = 100 * (y - fitted);
      final double dv01 = pricer.dv01(b, y / 100, settle, false);
      final String verdict = Math.abs(residBp) < 0.5 ? "fair" : residBp > 0 ? "cheap" : "rich";
      System.out.println(String.format(Locale.ROOT, "%s  %s  %5.2f  %6.3f  %6.3f  %+7.2f   %+8.4f  %s",
          b.name(), Dates.format(b.maturity()), t, y, fitted, residBp, -residBp * dv01, verdict));
    }
  }
}
```

Expected output:

```
NSS b0=3.994 b1=0.457 b2=-0.160 b3=3.076 t1=0.405 t2=14.156  rms 2.43 bp
cusip      maturity    years  yield%  fit%    resid bp  px vs fit  verdict
91282CME8  2026-12-31   1.95   4.270   4.250    +1.97    -0.0366  cheap
91282CMB4  2027-12-15   2.91   4.335   4.312    +2.34    -0.0630  cheap
91282CMA6  2029-11-30   4.87   4.440   4.441    -0.10    +0.0042  fair
91282CMD0  2029-12-31   4.96   4.452   4.446    +0.57    -0.0253  cheap
91282CLZ2  2031-11-30   6.87   4.552   4.555    -0.33    +0.0193  fair
91282CMC2  2031-12-31   6.95   4.561   4.560    +0.12    -0.0074  fair
91282CLF6  2034-08-15   9.58   4.652   4.678    -2.62    +0.1939  rich
91282CLW9  2034-11-15   9.83   4.660   4.688    -2.79    +0.2142  rich
912810UF3  2044-11-15  19.83   4.951   4.897    +5.38    -0.6568  cheap
912810UE6  2054-11-15  29.83   4.882   4.907    -2.45    +0.3665  rich
```

The fit leaves 2.4 bp root-mean-square on the par curve itself: a four-factor shape cannot follow the 20-year point, which sits above both the 10-year and the 30-year. That is why the 20-year bond `912810UF3` reads 5.4 bp cheap (0.66 per 100 below the curve) while the 30-year reads rich, and it is the well-known 20-year anomaly of this curve, not a pricing error. The 0.5 bp band for `fair` is a choice of the program, not a market convention. `BondAnalyticsTest.svenssonParametersFromTheBundesbankFixtureAndRefit` checks the fitter by refitting the Bundesbank's published parameters to below 1e-6, and LEARN.md Section 4 covers yields and relative value.

## 24. How do I compute z-spread and asset-swap spread of a bond to the OIS curve?

`BondPricer.zSpread(bond, curve, dirty, settlement)` finds the continuously compounded spread that, added to the curve's zero rates, reprices the bond's dirty price; `assetSwapSpread(bond, curve, dirty, settlement, floatDayCount)` is the par-par asset-swap spread, the bond's value on the curve minus its market price divided by the floating annuity. The program prices recipe 23's basket at the same marks on the SOFR OIS curve of recipe 4 (2025-01-15) and prints both spreads, then checks that a bond priced at exactly its curve value has zero of each.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.DayCount;
import java.util.Locale;

/** COOKBOOK.md recipe 24: z-spread and par-par asset-swap spread of Treasuries to the OIS curve. */
public final class Recipe24ZSpreadAsw {

  private Recipe24ZSpreadAsw() {}

  public static void main(final String[] args) {
    // the SOFR OIS curve of recipe 4 and the Treasury basket of recipe 23, both on 2025-01-15
    final DiscountCurve ois = Recipe04SofrCurve.build().curve();
    final BondPricer pricer = new BondPricer();
    System.out.println("cusip      yield%   dirty      on OIS     z-spread bp  ASW bp");
    for (final Object[] row : Recipe23NssRichCheap.BASKET) {
      final FixedRateBond b = Recipe23NssRichCheap.bond(row);
      final int settle = b.settlementDate(Recipe23NssRichCheap.TODAY);
      final double y = (double) row[4];
      final double dirty = pricer.dirtyPrice(b, y / 100, settle, false);
      final double onCurve = pricer.dirtyFromCurve(b, ois, settle);
      final double z = pricer.zSpread(b, ois, dirty, settle);
      final double asw = pricer.assetSwapSpread(b, ois, dirty, settle, DayCount.ACT_360);
      System.out.println(String.format(Locale.ROOT, "%s  %6.3f  %9.5f  %9.5f  %9.2f  %8.2f",
          b.name(), y, dirty, onCurve, 1e4 * z, 1e4 * asw));
    }
    // a check: priced at its own OIS value, a bond has zero z-spread and zero asset-swap spread
    final FixedRateBond tens = Recipe23NssRichCheap.bond(Recipe23NssRichCheap.BASKET[7]);
    final int settle = tens.settlementDate(Recipe23NssRichCheap.TODAY);
    final double fair = pricer.dirtyFromCurve(tens, ois, settle);
    final double z = pricer.zSpread(tens, ois, fair, settle);
    final double asw = pricer.assetSwapSpread(tens, ois, fair, settle, DayCount.ACT_360);
    System.out.println(tens.name() + " at its OIS value: |z| and |ASW| below 1e-10 bp: "
        + (Math.abs(1e4 * z) < 1e-10 && Math.abs(1e4 * asw) < 1e-10));
  }
}
```

Expected output:

```
cusip      yield%   dirty      on OIS     z-spread bp  ASW bp
91282CME8   4.270  100.14889  100.97790      43.51     43.83
91282CMB4   4.335   99.43046  100.79361      49.17     49.26
91282CMA6   4.440   99.13977  101.72118      57.84     57.80
91282CMD0   4.452   99.85236  102.54396      59.03     59.33
91282CLZ2   4.552   98.01062  101.63181      60.23     59.69
91282CMC2   4.561   99.83675  103.59605      61.13     61.31
91282CLF6   4.652   95.66633  100.63042      63.82     61.80
91282CLW9   4.660   97.51866  102.69795      64.11     63.13
912810UF3   4.951   96.48417  109.17265      93.72     91.82
912810UE6   4.882   94.79615  109.64097      87.40     84.31
91282CLW9 at its OIS value: |z| and |ASW| below 1e-10 bp: true
```

Both spreads are positive and grow with maturity: the Treasuries yield more than the declared OIS quotes of recipe 4, by 44 bp at two years and about 90 bp at twenty and thirty. They agree to within about half a basis point for bonds priced near par and drift apart for the discount bonds (`91282CLF6` at 95.67, the long bonds), because the asset swap is struck at par while the z-spread discounts the actual cash flows. The levels reflect recipe 4's declared quotes, not an observed swap spread. `BondAnalyticsTest.zSpreadAndAssetSwapSpreadAreConsistent` asserts the same sign and size relation and the zero-at-curve-value identity.

## 25. How do I compute carry and roll-down and a repo-implied forward price for a Treasury?

`BondCarry.forwardCleanPrice` finances the dirty price in repo to the horizon and subtracts coupons received on the way (reinvested at the repo rate) and the accrued interest at the horizon; carry is spot clean minus forward clean. `rollDown` reprices the bond at the horizon with its yield moved down the fitted curve's slope, so that its spread to the curve is unchanged. The program uses the 10-year note at 4.66%, a GC repo curve whose overnight point is the TGCR of 2025-01-14 (4.27%, `data/fixtures/repo_gc_2024.csv`) with declared term points, and recipe 23's fitted curve for the roll; it prints one, three and six month horizons, the breakeven yield at which the forward price is reached, and the effect of financing 50 bp special.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondCarry;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.bond.NelsonSiegelSvensson;
import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.funding.RepoCurve;
import java.util.Locale;

/** COOKBOOK.md recipe 25: carry, roll-down and the repo-implied forward price of a Treasury. */
public final class Recipe25CarryRoll {

  private Recipe25CarryRoll() {}

  public static void main(final String[] args) {
    // the 4 1/4% note of November 2034 at 4.66%, the 10-year line of recipe 23's basket
    final FixedRateBond note = Recipe23NssRichCheap.bond(Recipe23NssRichCheap.BASKET[7]);
    final int settle = note.settlementDate(Recipe23NssRichCheap.TODAY);
    final double y = 0.0466;
    final BondPricer pricer = new BondPricer();
    final double clean = pricer.cleanPrice(note, y, settle);
    // overnight point: TGCR of 2025-01-14 (4.27%, data/fixtures/repo_gc_2024.csv); term points declared
    final RepoCurve gc = new RepoCurve(new int[] {1, 30, 90, 180},
        new double[] {0.0427, 0.0425, 0.0421, 0.0415});
    final NelsonSiegelSvensson nss = Recipe23NssRichCheap.fit(new double[1]);
    final BondCarry carry = new BondCarry();
    System.out.println(String.format(Locale.ROOT, "%s settle %s clean %.6f yield %.3f%%",
        note.name(), Dates.format(settle), clean, 100 * y));
    System.out.println("horizon     days  repo%   fwd clean   carry    roll    total   be yield%  be chg bp");
    for (final int months : new int[] {1, 3, 6}) {
      final int horizon = note.convention().calendar().adjust(Dates.addMonths(settle, months),
          BusinessDayConvention.FOLLOWING);
      final int days = horizon - settle;
      final double repo = gc.rate(days);
      final double fwd = carry.forwardCleanPrice(note, clean, settle, horizon, repo);
      final double c = carry.carry(note, clean, settle, horizon, repo);
      final double today = nss.zero((note.maturity() - settle) / 365.25) / 100;
      final double later = nss.zero((note.maturity() - horizon) / 365.25) / 100;
      final double roll = carry.rollDown(note, y, horizon, today, later);
      final double breakeven = pricer.yieldFromClean(note, fwd, horizon, false);
      System.out.println(String.format(Locale.ROOT,
          "%s  %4d  %5.3f  %10.6f  %+7.4f  %+7.4f  %+7.4f  %8.4f  %+8.2f",
          Dates.format(horizon), days, 100 * repo, fwd, c, roll, c + roll, 100 * breakeven,
          1e4 * (breakeven - y)));
    }
    // trading special: 50 bp below GC for three months raises the carry by the funding saved
    final int horizon = Dates.addMonths(settle, 3);
    final double gcCarry = carry.carry(note, clean, settle, horizon, gc.rate(horizon - settle));
    final double special = clean - carry.forwardCleanPrice(note, clean, settle, horizon, gc, 0.0050);
    System.out.println(String.format(Locale.ROOT,
        "3m carry at GC %+.4f, 50 bp special %+.4f, difference %.4f per 100", gcCarry, special,
        special - gcCarry));
    System.out.println(String.format(Locale.ROOT, "funding saved on the dirty price %.4f per 100",
        RepoCurve.fundingCharge(clean + pricer.accrued(note, settle), horizon - settle, 0.0050)));
  }
}
```

Expected output:

```
91282CLW9 settle 2025-01-16 clean 96.790759 yield 4.660%
horizon     days  repo%   fwd clean   carry    roll    total   be yield%  be chg bp
2025-02-18    33  4.248   96.783065  +0.0077  +0.0262  +0.0339    4.6640     +0.40
2025-04-16    90  4.210   96.760513  +0.0302  +0.0713  +0.1015    4.6729     +1.29
2025-07-16   181  4.150   96.697193  +0.0936  +0.1425  +0.2361    4.6904     +3.04
3m carry at GC +0.0302, 50 bp special +0.1521, difference 0.1219 per 100
funding saved on the dirty price 0.1219 per 100
```

Carry is small and positive because the 4.25% coupon on a price of 96.79 (a current yield near 4.39%) only just exceeds repo at 4.15 to 4.25%; over six months it is 0.094 per 100 and the May coupon is inside the horizon. Roll-down is larger, 0.143 per 100 over six months, because the fitted curve slopes up at ten years. The breakeven column says the position earns its carry unless the yield rises more than 3.0 bp in six months. Trading 50 bp special adds exactly the funding saved on the dirty price, 0.1219 per 100 over three months, which the last two lines show agree. `BondAnalyticsTest.repoForwardCarryAndRollDown` checks the forward formula term by term; LEARN.md Section 4 explains carry and repo.

## 26. How do I price a compounded-in-arrears SOFR leg with lookback and lockout?

An `OisSwap` holds, for each floating period, the daily compounding grid the ISDA 2021 definitions describe, and `SwapConvention.withObservation(lookback, lockout, shift)` changes how that grid is observed. The program values one seasoned quarterly period (2024-12-16 to 2025-03-17) on the morning of 2025-01-15: `FixingHistory` holds the published SOFR fixings up to 2025-01-14 from `data/fixtures/sofr_2024.csv`, and `OisSwapPricer` compounds those and projects the rest from recipe 4's curve. It prints the observation window, how many observations are already fixed, the period's compounded rate and the leg's value for five conventions, then checks the realised part of the plain leg against the ratio of the published SOFR Index values.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 26: a seasoned compounded-in-arrears SOFR leg with lookback and lockout. */
public final class Recipe26SofrInArrears {

  private Recipe26SofrInArrears() {}

  /** Rows of data/fixtures/sofr_2024.csv: date, SOFR in percent, SOFR Index. */
  static List<String[]> sofr() {
    try {
      final List<String> lines = Files.readAllLines(
          RepoRoot.find().resolve("data/fixtures/sofr_2024.csv"), StandardCharsets.UTF_8);
      final List<String[]> rows = new ArrayList<>();
      for (final String line : lines.subList(1, lines.size())) {
        rows.add(line.split(",", -1));
      }
      return rows;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static double index(final List<String[]> rows, final int date) {
    for (final String[] r : rows) {
      if (Dates.parse(r[0]) == date) {
        return Double.parseDouble(r[2]);
      }
    }
    throw new IllegalArgumentException("no SOFR Index on " + Dates.format(date));
  }

  public static void main(final String[] args) {
    final DiscountCurve curve = Recipe04SofrCurve.build().curve();
    final int today = curve.valuationDate();
    // what was published by the morning of 2025-01-15: fixings up to and including 2025-01-14
    final List<String[]> rows = sofr();
    int n = 0;
    while (n < rows.size() && Dates.parse(rows.get(n)[0]) < today) {
      n++;
    }
    final int[] dates = new int[n];
    final double[] values = new double[n];
    for (int i = 0; i < n; i++) {
      dates[i] = Dates.parse(rows.get(i)[0]);
      values[i] = Double.parseDouble(rows.get(i)[1]) / 100.0;
    }
    final FixingHistory fixings = new FixingHistory(dates, values);

    // one quarterly period, 2024-12-16 to 2025-03-17, receiving SOFR on 100 million
    final int start = Dates.of(2024, 12, 16);
    final int end = Dates.of(2025, 3, 17);
    final OisSwapPricer pricer = new OisSwapPricer();
    final SwapConvention base = SwapConvention.USD_SOFR;
    final SwapConvention[] variants = {
        base.withObservation(0, 0, false),
        base.withObservation(2, 0, false),
        base.withObservation(2, 0, true),
        base.withObservation(0, 2, false),
        base.withObservation(5, 2, true),
    };
    System.out.println("convention               days  first obs   last obs    known  rate%     float PV");
    for (final SwapConvention c : variants) {
      final OisSwap leg = OisSwap.of(c, start, end, 1e8, 0.0, false);
      final int[] obs = leg.fixingDates(0);
      int known = 0;
      for (final int d : obs) {
        known += fixings.has(d) ? 1 : 0;
      }
      final double rate = pricer.parRate(leg, curve, fixings, today);
      System.out.println(String.format(Locale.ROOT, "%-23s  %4d  %s  %s  %5d  %7.5f  %11.2f",
          c.name(), obs.length, Dates.format(obs[0]), Dates.format(obs[obs.length - 1]), known,
          100 * rate, pricer.floatLegPv(leg, curve, curve, fixings, today)));
    }

    // the realised part of the plain leg, compounded from fixings, against the SOFR Index ratio
    final OisSwap plain = OisSwap.of(variants[0], start, end, 1e8, 0.0, false);
    final int[] obs = plain.fixingDates(0);
    final double[] w = plain.compoundingWeights(0);
    double growth = 1.0;
    for (int i = 0; i < obs.length && obs[i] < today; i++) {
      growth *= 1.0 + fixings.rate(obs[i]) * w[i];
    }
    final double ratio = index(rows, today) / index(rows, start);
    System.out.println(String.format(Locale.ROOT,
        "realised growth %s to %s: fixings %.8f, SOFR Index ratio %.8f", Dates.format(start),
        Dates.format(today), growth, ratio));
  }
}
```

Expected output:

```
convention               days  first obs   last obs    known  rate%     float PV
USD-SOFR-OIS               61  2024-12-16  2025-03-14     20  4.33715   1088151.02
USD-SOFR-OIS-LB2           61  2024-12-12  2025-03-12     22  4.35193   1091858.38
USD-SOFR-OIS-LB2-OS        61  2024-12-12  2025-03-12     22  4.35167   1091793.95
USD-SOFR-OIS-LO2           61  2024-12-16  2025-03-12     20  4.33713   1088146.77
USD-SOFR-OIS-LB5-LO2-OS    61  2024-12-09  2025-03-05     25  4.36339   1094734.70
realised growth 2024-12-16 to 2025-01-15: fixings 1.00365457, SOFR Index ratio 1.00365456
```

A two-day lookback moves the whole window back two business days, so 22 observations are known instead of 20 and the rate rises 1.5 bp, since mid-December SOFR fixed above the rates the curve projects for March. The observation shift weights each day by the shifted period instead, a 0.03 bp difference here. A two-day lockout repeats the fixing of 2025-03-12 for the last two days and changes the rate by 0.002 bp, because the curve is flat across those days. The fixings compound to 1.00365457 against an index ratio of 1.00365456: the index is published to eight decimals. `AnalyticsGoldenTest.swapsMatchQuantLibOnBothCurves` checks lookback, lockout and shift grids against QuantLib 1.44 (LEARN.md Section 3).

## 27. How do I price a curve or butterfly RFQ as one package with a net DV01 ladder?

`PackageQuoter` prices a package RFQ as one trade (API_SPEC.md Section 12.7): each leg's optimal half-spread is computed alone, then the hedge is charged once on the dealer's net ladder instead of leg by leg, and the inventory charge is taken on the net ladder against the book. `neutralPair` and `butterfly` size the other legs so that the package is DV01-neutral. The program builds a small USD SOFR swap market on the `rates-api` stubs (2, 5, 10 and 30 years, each with its Treasury future as hedge), wires a `Desk` and prices a 2s10s steepener and a 2s5s10s butterfly.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.PackageQuoter;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 27: price a curve and a butterfly RFQ as one package, with the net ladder. */
public final class Recipe27PackageRfq {

  private Recipe27PackageRfq() {}

  static final long FACE_1M = 100_000_000L;
  static final int SW2 = 301;
  static final int SW5 = 302;
  static final int SW10 = 303;
  static final int SW30 = 304;

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L); // 2026-01-01T12:00Z
    final InMemoryInstrumentDirectory dir = new InMemoryInstrumentDirectory();
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    // USD SOFR swaps quoted in rate percent, each with its futures hedge and a duration
    final int[] futures = {201, 202, 203, 204};
    final String[] futureNames = {"TU", "FV", "TY", "US"};
    final double[] futureDuration = {1.9, 4.3, 6.5, 13.0};
    final double[] futurePrice = {103.20, 108.40, 111.10, 117.30};
    final int[] swaps = {SW2, SW5, SW10, SW30};
    final double[] tenor = {2.0, 5.0, 10.0, 30.0};
    final double[] swapDuration = {1.9, 4.5, 8.2, 17.0};
    final double[] swapRate = {3.80, 3.70, 3.85, 3.95};
    for (int i = 0; i < 4; i++) {
      dir.add(Instrument.future(futures[i], futureNames[i], CurrencyCode.USD, tenor[i] * 0.7, 50 * FACE_1M));
      dir.add(Instrument.swap(swaps[i], "SOFR" + (int) tenor[i], CurrencyCode.USD, tenor[i], swapDuration[i],
          futures[i], 0.9, 50 * FACE_1M));
    }
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(dir);
    final HedgeUniverse universe = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(16);
    final long now = clock.epochNanos();
    for (int i = 0; i < 4; i++) {
      pv01.defineAtTenor(futures[i], -futureDuration[i] * 1e-4, 0.002);
      pv01.defineAtTenor(swaps[i], swapDuration[i] * 1e-4, 0.002);
      fair.set(futures[i], Decimal9.round(futurePrice[i]), now);
      fair.set(swaps[i], Decimal9.round(swapRate[i]), now);
      universe.add(futures[i], 10_000_000L, 5_000).add(swaps[i], FACE_1M, 2_000);
      costs.set(futures[i], 4.0 * (i + 1), 1.0, 0.002).set(swaps[i], swapDuration[i] * 10, 0.0, 0.01 * (i + 1));
    }
    final Desk desk = new Desk(new Desk.Inputs(dir, pv01, fair, new InMemoryBookSource(16),
        new RiskLimits().bucketPv01(250_000.0).netPv01(400_000.0).grossPv01(1_500_000.0).priceCollarBp(15.0),
        universe, costs, new RecordingOutboundSink(), new RecordingOutboundSink(), new RecordingHedgeOrderSink(),
        clock, new QuotingConfig().requoteThrottleNanos(0L), TierTable.single(1, new ClientTier(1, 1.0, 500 * FACE_1M, true, false)),
        HitRatePreset.shippedDefault(), Hedger.config(), ClobQuoter.config()));
    final PackageQuoter quoter = desk.packages();

    // 2s10s steepener: the client pays fixed on 100m 2y and receives on a DV01-matched 10y
    show(quoter, quoter.neutralPair(PackageQuoter.Type.CURVE, SW2, 100 * FACE_1M, SW10), dir);
    // 2s5s10s butterfly: the client receives on 50m 5y, each wing carries half the body's DV01
    show(quoter, quoter.butterfly(SW2, SW5, -50 * FACE_1M, SW10), dir);
  }

  private static void show(final PackageQuoter quoter, final PackageQuoter.PackageRequest pkg,
      final InMemoryInstrumentDirectory dir) {
    final PackageQuoter.Result r = quoter.quote(pkg, 1, 4, new PackageQuoter.Result());
    System.out.println(pkg.type() + " quoted " + r.quoted());
    for (int i = 0; i < pkg.legs(); i++) {
      final long q = pkg.clientQuantity(i);
      System.out.println(String.format(Locale.ROOT, "  %-7s client %-7s %6.1fm at %.6f%%",
          dir.byId(pkg.instrumentId(i)).symbol(), q > 0 ? "pays" : "receives", Math.abs(q) / 1e8,
          Decimal9.toDouble(r.legPrice(i))));
    }
    System.out.println(String.format(Locale.ROOT, "  legs one by one %.0f USD; net-risk PV01 %.0f USD per bp",
        r.legChargeSum(), r.netRiskPv01()));
    System.out.println(String.format(Locale.ROOT,
        "  package %.0f USD: spread %.0f - hedge saving %.0f + inventory on the net ladder %.0f",
        r.charge(), r.spreadChargeSum(), r.hedgeSaving(), r.inventoryCharge()));
    System.out.println(String.format(Locale.ROOT, "  distance %.4f bp on every leg", r.distanceBp()));
    final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
    r.netLadder(net);
    final StringBuilder ladder = new StringBuilder("  dealer net DV01");
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      ladder.append(String.format(Locale.ROOT, " %s %.0f", KeyRateTenors.label(k), net.get(k) + 0.0));
    }
    System.out.println(ladder);
    System.out.println(String.format(Locale.ROOT, "  parallel %.0f, gross %.0f USD per bp", net.parallel() + 0.0,
        net.gross()));
  }
}
```

Expected output:

```
CURVE quoted true
  SOFR2   client pays     100.0m at 3.806750%
  SOFR10  client receives   23.0m at 3.843250%
  legs one by one 66265 USD; net-risk PV01 16542 USD per bp
  package 25556 USD: spread 17239 - hedge saving 3782 + inventory on the net ladder 12098
  distance 0.6750 bp on every leg
  dealer net DV01 1Y 0 2Y -19000 3Y 0 5Y 0 7Y 0 10Y 18860 20Y 0 30Y 0
  parallel -140, gross 37860 USD per bp
BUTTERFLY quoted true
  SOFR2   client pays      59.0m at 3.803333%
  SOFR5   client receives   50.0m at 3.696667%
  SOFR10  client pays      14.0m at 3.853333%
  legs one by one 77734 USD; net-risk PV01 11217 USD per bp
  package 15062 USD: spread 13712 - hedge saving 4511 + inventory on the net ladder 5861
  distance 0.3333 bp on every leg
  dealer net DV01 1Y 0 2Y -11210 3Y 0 5Y 22500 7Y 0 10Y -11480 20Y 0 30Y 0
  parallel -190, gross 45190 USD per bp
```

For the curve trade the client pays fixed on 100m 2-year and receives on 23m 10-year, which matches the 2-year's DV01; the dealer's net ladder is -19,000 at 2Y and +18,860 at 10Y, 140 USD per bp parallel after rounding the 10-year to whole millions. Priced leg by leg the two trades cost the client 66,265 USD. As a package it costs 25,556: the spread is charged on the net ladder's risk-equivalent PV01 (16,542 USD per bp, against 37,860 gross), the hedge is netted (3,782 saved) and the inventory charge on the offsetting ladder is 12,098. That charge is spread as 0.675 bp on every leg, in the client-worse direction. The package passes the same kill, health, release, throttle, fair-value and risk checks as a single RFQ; the program turns the client re-quote throttle off because it prices two packages for one client at the same instant. `PackageAndClobTest.curvePackageCostsLessThanItsLegsByTheNettedHedgeSaving` checks the identity charge = spread - saving + inventory and the DV01 neutrality; LEARN.md Section 8 covers the ladder.

## 28. How do I see the quote lifecycle controls pull stale quotes (time to live, stale reference, feed gap)?

A firm quote leaves the engine three ways besides a client answer (API_SPEC.md Section 12.6): it lapses at its time to live, it is withdrawn when the oldest input it was priced from grows older than `maxInputAgeNanos`, and every quote is withdrawn when `InputHealth` reports a heartbeat loss, a session loss or a book-feed gap. `QuotingEngine.poll` runs these checks; a withdrawal is a QuoteWithdraw message to the gateway with a `CancelReason`. The program sets a one-second time to live and a two-second input-age limit, drives a `ManualClock`, and prints each RFQ decision and each QuoteWithdraw the gateway receives.

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
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.InputHealth;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.RfqOutcome;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 28: the quote lifecycle controls that pull stale quotes. */
public final class Recipe28QuotePulls {

  private Recipe28QuotePulls() {}

  static final long T0 = 1_767_268_800_000_000_000L; // 2026-01-01T12:00Z
  static final long MS = 1_000_000L;
  static final long FACE_25M = 2_500_000_000L;

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(T0);
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, FACE_25M));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0);
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final RecordingOutboundSink gateway = new RecordingOutboundSink();
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final QuoteWithdrawDecoder withdraw = new QuoteWithdrawDecoder();
    gateway.listener((templateId, buffer, offset, length) -> {
      if (templateId == QuoteWithdrawDecoder.TEMPLATE_ID) {
        withdraw.wrapAndApplyHeader(buffer, offset, header);
        System.out.println(String.format(Locale.ROOT, "  t=%5d ms  QuoteWithdraw quote %d reason %s",
            (withdraw.sentNanos() - T0) / MS, withdraw.quoteId(), withdraw.reason()));
      }
    });
    // a one-second time to live and a two-second limit on the age of the inputs a quote used
    final QuotingConfig config = new QuotingConfig().firmTtlNanos(1_000 * MS).maxInputAgeNanos(2_000 * MS);
    final Desk desk = new Desk(new Desk.Inputs(instruments, pv01, fair, new InMemoryBookSource(4),
        new RiskLimits().bucketPv01(250_000.0), new HedgeUniverse(), new TableHedgeCostModel(1), gateway,
        new RecordingOutboundSink(), new RecordingHedgeOrderSink(), clock, config,
        TierTable.single(1, new ClientTier(1, 1.0, 10_000_000_000L, true, false)),
        HitRatePreset.shippedDefault(), Hedger.config(), ClobQuoter.config()));
    final QuotingEngine engine = desk.quoting();
    final InputHealth health = desk.health();
    health.trackVenue(7);

    System.out.println("1. time to live: the quote lapses at its validUntil, no message is sent");
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());
    rfq(engine, clock, 1);
    clock.advance(1_000 * MS);
    engine.poll(clock.epochNanos());
    System.out.println("  t= 1000 ms  live " + engine.liveQuotes() + ", expiries " + engine.expiries());

    System.out.println("2. stale reference: quoted from a 1.5 s old fair value, pulled when it passes 2 s");
    clock.advance(500 * MS);
    rfq(engine, clock, 2);
    clock.advance(400 * MS);
    engine.poll(clock.epochNanos());
    System.out.println("  t= 1900 ms  inputs 1.9 s old, live " + engine.liveQuotes());
    clock.advance(200 * MS);
    engine.poll(clock.epochNanos());

    System.out.println("3. feed gap: book sequence 101, 102, 105 on venue 7 pulls every quote");
    fair.set(103, Decimal9.round(98.26), clock.epochNanos());
    clock.advance(300 * MS);
    rfq(engine, clock, 3);
    health.onBookSequence(7, 101);
    health.onBookSequence(7, 102);
    health.onBookSequence(7, 105);
    clock.advance(1 * MS);
    engine.poll(clock.epochNanos());
    rfq(engine, clock, 4);
    // the book is rebuilt from a snapshot; the re-quote throttle (250 ms) then lets the client in again
    health.onFeedRecovered(7);
    clock.advance(300 * MS);
    fair.set(103, Decimal9.round(98.26), clock.epochNanos());
    rfq(engine, clock, 5);
    System.out.println(String.format(Locale.ROOT, "rfqs %d quotes %d passes %d expiries %d pulls %d",
        engine.rfqs(), engine.quotesSent(), engine.passes(), engine.expiries(), engine.pulls()));
  }

  private static void rfq(final QuotingEngine engine, final ManualClock clock, final long rfqId) {
    engine.onQuoteRequest(7, rfqId, 42, 103, Side.Buy, FACE_25M, 4, 0L, rfqId);
    final RfqOutcome o = engine.outcome();
    System.out.println(String.format(Locale.ROOT, "  t=%5d ms  rfq %d: %s %s%s", (clock.epochNanos() - T0) / MS,
        rfqId, o.decision(), o.reason(), o.quoteId() == 0 ? ""
            : String.format(Locale.ROOT, " quote %d valid to t=%d ms", o.quoteId(), (o.validUntilNanos() - T0) / MS)));
  }
}
```

Expected output:

```
1. time to live: the quote lapses at its validUntil, no message is sent
  t=    0 ms  rfq 1: FIRM NONE quote 1 valid to t=1000 ms
  t= 1000 ms  live 0, expiries 1
2. stale reference: quoted from a 1.5 s old fair value, pulled when it passes 2 s
  t= 1500 ms  rfq 2: FIRM NONE quote 2 valid to t=2500 ms
  t= 1900 ms  inputs 1.9 s old, live 1
  t= 2100 ms  QuoteWithdraw quote 2 reason StaleInput
3. feed gap: book sequence 101, 102, 105 on venue 7 pulls every quote
  t= 2400 ms  rfq 3: FIRM NONE quote 3 valid to t=3400 ms
  t= 2401 ms  QuoteWithdraw quote 3 reason FeedGap
  t= 2401 ms  rfq 4: PASS UNHEALTHY
  t= 2701 ms  rfq 5: FIRM NONE quote 4 valid to t=3701 ms
rfqs 5 quotes 4 passes 1 expiries 2 pulls 2
```

Quote 1 lapses at 1,000 ms without a message: the venue knows the quote's validUntil. Quote 2 is priced from a fair value already 1.5 s old, so it is withdrawn at 2,100 ms with `StaleInput`, before its time to live. Quote 3 is withdrawn with `FeedGap` as soon as venue 7's book sequence jumps from 102 to 105, the next RFQ is passed `UNHEALTHY`, and quoting resumes after `onFeedRecovered`. The final `expiries 2` counts quote 2 a second time, because a withdrawn quote keeps its reservation until its time to live passes. `QuotingEngineTest.quotesArePulledWhenTheirInputsGoStaleOrHealthFails` and `feedGapAndSessionLossPullEverything` assert the same behaviour.

## 29. How do I replay a journal and rebuild the router's decision table for one parent order?

The router writes every routing decision as one RouteDecision frame (template 80) followed by one RouteDecisionVenue frame (81) per venue, and every child order state change as a ChildOrderTransition (82), to a `FrameSink` (API_SPEC.md Section 15.5). The program binds that sink to `JournalWriter.append`, routes two parent orders on recipe 17's two-venue simulator, closes the journal, then reads it back with `JournalReader`, feeds every frame to `DecisionCodec` and keeps what belongs to parent 2, an urgent 8m sell. Nothing from the live run is kept in memory: the tables and the child history come from the file.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.ChildOrderTransitionDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.CostTable;
import io.ratesalgo.router.DecisionCodec;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** COOKBOOK.md recipe 29: replay a journal and rebuild the router's decisions for one parent. */
public final class Recipe29RouterReplay {

  private Recipe29RouterReplay() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe29");
    final Path file = dir.resolve("router.raj");
    final ManualClock clock = new ManualClock();
    final SimConfig config = SimConfig.ust10yTwoVenues();
    final SimMarket market = new SimMarket(config, 2026L, clock);

    // live run: the router journals every RouteDecision, its venue rows and every child transition
    try (JournalWriter journal = JournalWriter.create(file, 1 << 22, clock)) {
      final SmartOrderRouter router = new SmartOrderRouter(RouterPresets.global(), config.routerVenues(0.25, 5),
          market, market, (t, v, b, o, l) -> journal.append(t, v, b, o, l), clock, RandomSource.seeded(17L),
          4, 64, 1L);
      router.submit(1, 1001, Side.Buy, 20 * RouterPresets.ONE_MILLION, 0.5, 0L);
      router.submit(2, 1001, Side.Sell, 8 * RouterPresets.ONE_MILLION, 0.9, 0L);
      while (router.isActive(1) || router.isActive(2)) {
        clock.advance(config.stepNanos());
        market.step();
        market.drain(router);
        router.poll();
      }
      System.out.println("journal frames " + journal.nextSequence());
    }

    // replay: read the file back and keep what belongs to parent 2
    final DecisionCodec codec = new DecisionCodec();
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final ChildOrderTransitionDecoder transition = new ChildOrderTransitionDecoder();
    final Map<Integer, Integer> byTemplate = new TreeMap<>();
    final StringBuilder decisions = new StringBuilder();
    final StringBuilder children = new StringBuilder();
    try (JournalReader reader = JournalReader.open(file)) {
      while (reader.next((seq, nanos, templateId, version, buffer, offset, length) -> {
        byTemplate.merge(templateId, 1, Integer::sum);
        if (codec.onFrame(templateId, buffer, offset, length) && codec.decoded().parentId() == 2) {
          final CostTable t = codec.decoded();
          decisions.append(decisions.isEmpty() ? t.format() : String.format(Locale.ROOT,
              "decision %d %s slice %.1fm alloc %.1fm%n", t.decisionId(), t.reason(),
              t.sliceQuantity() / (double) RouterPresets.ONE_MILLION,
              t.allocatedQuantity() / (double) RouterPresets.ONE_MILLION));
        }
        if (templateId == ChildOrderTransitionDecoder.TEMPLATE_ID) {
          transition.wrapAndApplyHeader(buffer, offset, header);
          if (transition.parentId() == 2) {
            children.append(String.format(Locale.ROOT, "  seq %3d child %d venue %d %s -> %s (%s) cum %.1fm%n",
                seq, transition.clOrdId(), transition.venueId(), transition.fromState(), transition.toState(),
                transition.cause(), transition.cumQuantity() / (double) RouterPresets.ONE_MILLION));
          }
        }
      })) {
        // reading
      }
    }
    System.out.println("frames by template (80 decision, 81 venue row, 82 child transition): " + byTemplate);
    System.out.print(decisions);
    System.out.println("child orders of parent 2:");
    System.out.print(children);
    Files.delete(file);
    Files.delete(dir);
  }
}
```

Expected output:

```
journal frames 92
frames by template (80 decision, 81 venue row, 82 child transition): {80=11, 81=22, 82=58}
decision 2 parent 2 Slice Sell slice 0 qty 4.0 alloc 4.0 horizon 3.0s expected 0.013448
venue kind    bid        ask          same    opp    cap P(fill) impact    passive   aggress   chosen     alloc mode excluded
1     Clob    98.500000  98.515625    12.0  120.0   33.0 0.0000  0.002402  0.013597  0.010766  0.013597     3.0 pass None
2     Clob    98.500000  98.515625     6.0   60.0   16.0 0.0037  0.001961  0.013001  0.010239  0.013001     1.0 pass None
decision 3 Escalation slice 3.0m alloc 0.0m
decision 4 Escalation slice 1.0m alloc 0.0m
decision 5 Retry slice 4.0m alloc 4.0m
decision 6 Slice slice 4.0m alloc 4.0m
child orders of parent 2:
  seq   9 child 3 venue 1 New -> New (Sent) cum 0.0m
  seq  10 child 4 venue 2 New -> New (Sent) cum 0.0m
  seq  13 child 3 venue 1 New -> Acknowledged (Ack) cum 0.0m
  seq  14 child 4 venue 2 New -> Acknowledged (Ack) cum 0.0m
  seq  15 child 3 venue 1 Acknowledged -> CancelRequested (CancelSent) cum 0.0m
  seq  16 child 4 venue 2 Acknowledged -> CancelRequested (CancelSent) cum 0.0m
  seq  17 child 3 venue 1 CancelRequested -> Replaced (CancelConfirmed) cum 0.0m
  seq  21 child 4 venue 2 CancelRequested -> Replaced (CancelConfirmed) cum 0.0m
  seq  28 child 5 venue 1 New -> New (Sent) cum 0.0m
  seq  29 child 6 venue 2 New -> New (Sent) cum 0.0m
  seq  30 child 6 venue 2 New -> Filled (Fill) cum 1.0m
  seq  31 child 5 venue 1 New -> Filled (Fill) cum 3.0m
  seq  35 child 7 venue 1 New -> New (Sent) cum 0.0m
  seq  36 child 8 venue 2 New -> New (Sent) cum 0.0m
  seq  37 child 8 venue 2 New -> Filled (Fill) cum 1.0m
  seq  38 child 7 venue 1 New -> Filled (Fill) cum 3.0m
```

The first table is the decision as the router saw it: both CLOB venues, their queue and capacity, the fill probability, impact, the passive and aggressive costs, and the allocation of 3m and 1m passive. The two Escalation decisions allocate nothing new; they replace the resting children, which the child history shows as Acknowledged to CancelRequested to Replaced, and the Retry and the next Slice each fill 4m across the two venues. The frame counts (11 decisions, 22 venue rows, 58 transitions) cover both parents. `DecisionReplayTest.journalRebuildsAndReproducesEveryDecision` asserts that every decision rebuilt from a journal equals the live one, and LEARN.md Section 9 covers the routing logic.

## 30. How do I run the CLOB simulator through a workup and read the book feed?

The CLOB simulator runs a workup like the interdealer Treasury venues: a trade at price P opens an exclusive window in which only the two counterparties may trade more at P, then an open window in which anyone may, and the timer restarts on each trade (the states are in the clob_workup_states diagram). Orders that arrive from outsiders during the exclusive window are held and processed when it ends. The program enters participant orders with `submitInternal`, moves the `ManualClock`, runs the due workup timers with `onTime`, and after each step prints the workup state, the trades from the venue's event log with their FIX tag 9504 flag, and the book rebuilt from the SBE feed by `BookFeedConsumer`.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobBook;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.Px;
import java.util.List;

/** COOKBOOK.md recipe 30: run the CLOB simulator through a workup and read the book feed. */
public final class Recipe30ClobWorkup {

  private Recipe30ClobWorkup() {}

  static final int TEN_YEAR = 1007; // US91282CZG94

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock(Venues.T0);
    final InProcessFeedChannel feed = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 1, feed);
    final BookFeedConsumer book = new BookFeedConsumer(clob.feed());
    final ClobConfig cfg = clob.config();
    System.out.println("workup " + cfg.workupNanos() / 1_000_000 + " ms, exclusive " + cfg.exclusiveNanos() / 1_000_000
        + " ms, extend on trade " + cfg.extendOnTrade());
    final long p = Px.price("98.015625");
    final int[] cursor = {0};
    final long ms = 1_000_000L;

    clob.submitInternal("DLR-A", TEN_YEAR, false, p, 10_000_000, 0);
    clob.submitInternal("DLR-A", TEN_YEAR, false, Px.price("98.03125"), 5_000_000, 0);
    clob.submitInternal("DLR-Z", TEN_YEAR, true, Px.price("97.984375"), 4_000_000, 0);
    step("resting book", clob, clock, feed, book, 0, cursor);

    clob.submitInternal("DLR-B", TEN_YEAR, true, p, 2_000_000, 0); // lifts the offer: a workup opens at p
    step("B lifts 2m at 98.015625", clob, clock, feed, book, 0, cursor);

    clob.submitInternal("DLR-C", TEN_YEAR, true, p, 3_000_000, 0); // an outsider: held in the exclusive window
    clob.submitInternal("DLR-B", TEN_YEAR, true, p, 1_000_000, 0); // a participant: trades at once
    step("C bids 3m (held), B adds 1m", clob, clock, feed, book, 100 * ms, cursor);

    clob.submitInternal("DLR-D", TEN_YEAR, true, Px.price("98.03125"), 1_000_000, 0); // an outsider bidding above p: held too
    step("exclusive window over", clob, clock, feed, book, cfg.exclusiveNanos(), cursor);

    step("workup timer expired", clob, clock, feed, book, cfg.workupNanos(), cursor);
    System.out.println("trades " + clob.trades() + ", feed sequence " + clob.feed().lastSequence()
        + ", consumer sequence " + book.lastSequence() + ", gaps " + book.gaps());
  }

  private static void step(final String label, final ClobSimulator clob, final ManualClock clock,
      final InProcessFeedChannel feed, final BookFeedConsumer book, final long advanceNanos, final int[] logCursor) {
    if (advanceNanos > 0) {
      clock.advance(advanceNanos);
      clob.onTime(); // runs the workup timers that fall due
    }
    feed.poll(book, 100);
    final ClobBook b = clob.book(TEN_YEAR);
    System.out.println(label + ": " + (b.inWorkup() ? "workup at " + Px.text(b.workupPrice()) : "normal")
        + ", held " + b.held().size());
    final List<String> log = clob.eventLog();
    for (; logCursor[0] < log.size(); logCursor[0]++) {
      final String line = log.get(logCursor[0]);
      final String text = line.substring(line.indexOf(' ') + 1); // drop the timestamp
      if (text.startsWith("trade")) {
        System.out.println("  " + text);
      }
    }
    System.out.println("  feed bids " + levels(book, Side.Buy) + " offers " + levels(book, Side.Sell));
  }

  private static String levels(final BookFeedConsumer book, final Side side) {
    final StringBuilder sb = new StringBuilder("[");
    book.levels(TEN_YEAR, side).forEach((price, qty) ->
        sb.append(sb.length() == 1 ? "" : ", ").append(Px.text(price)).append('x').append(qty));
    return sb.append(']').toString();
  }
}
```

Expected output:

```
workup 5000 ms, exclusive 2000 ms, extend on trade true
resting book: normal, held 0
  feed bids [97.984375x400000000] offers [98.015625x1000000000, 98.03125x500000000]
B lifts 2m at 98.015625: workup at 98.015625, held 0
  trade 2000000 @ 98.015625 DLR-B/DLR-A N
  feed bids [97.984375x400000000] offers [98.015625x800000000, 98.03125x500000000]
C bids 3m (held), B adds 1m: workup at 98.015625, held 1
  trade 1000000 @ 98.015625 DLR-B/DLR-A X
  feed bids [97.984375x400000000] offers [98.015625x700000000, 98.03125x500000000]
exclusive window over: workup at 98.015625, held 0
  trade 3000000 @ 98.015625 DLR-C/DLR-A W
  trade 1000000 @ 98.015625 DLR-D/DLR-A W
  feed bids [97.984375x400000000] offers [98.015625x300000000, 98.03125x500000000]
workup timer expired: normal, held 0
  feed bids [97.984375x400000000] offers [98.015625x300000000, 98.03125x500000000]
trades 4, feed sequence 6, consumer sequence 6, gaps 0
```

The first trade is a normal one (flag N) and opens the workup at 98.015625. DLR-C's 3m bid is held because C is not a counterparty, while DLR-B's further 1m trades at once with flag X. When the exclusive window ends, 2 s after the last trade, the held orders trade at P in arrival order with flag W, including DLR-D's bid at 98.03125, which fills at the workup price and not at its limit. The feed shows the offer at P falling from 10m to 3m and never shows a crossed book; the workup then times out with no further trades. `ClobBookTest.workupExclusiveWindowHoldsOutsiders` asserts the same sequence, and LEARN.md Section 6, "Worked example: a workup", explains why venues run it.

## 31. How do I compute key-rate hedge ratios with PCA from the public Treasury curve fixture?

`ratesresearch.curves.load_par_curve` reads the public Treasury par curve fixture `data/fixtures/ust_par_curve_2015_2025.csv` at seven tenors, `daily_changes` turns it into daily changes in basis points, and `pca` takes the eigenvectors of their sample covariance (level, slope and curvature, sign-normalised). `pca_hedge_ratio` gives the hedge of the 10-year with the 5-year alone, keeping one, two or three factors; the last step solves a three-by-three system with numpy so that a 2y/5y/30y hedge of one unit of 10-year nets PC1, PC2 and PC3 to zero. All ratios are in yield-change space, units of hedge yield per unit of target yield; a desk multiplies them by the ratio of DV01s to get notionals.

```python
"""COOKBOOK.md recipe 31: PCA hedge ratios from the Treasury par curve fixture."""

from pathlib import Path

import numpy as np

from ratesresearch import curves

root = Path(__file__).resolve().parents[2]
dates, tenors, rates = curves.load_par_curve(root / "data/fixtures/ust_par_curve_2015_2025.csv")
changes = curves.daily_changes(rates)
result = curves.pca(changes, n_components=3)
print(f"{len(changes)} daily changes, {dates[0]} to {dates[-1]}")

print(f"{'':<6}" + "".join(f"{t:>8}" for t in tenors) + f"{'explained':>11}")
for k, name in enumerate(("PC1", "PC2", "PC3")):
    row = "".join(f"{v:>8.3f}" for v in result.loadings[k])
    print(f"{name:<6}{row}{result.explained_variance_ratio[k]:>11.4f}")

i10, i5 = tenors.index("10 Yr"), tenors.index("5 Yr")
for n in (1, 2, 3):
    h = curves.pca_hedge_ratio(result, i10, i5, n_factors=n)
    print(f"10y hedged with 5y alone, {n} factor(s): {h:.4f}")

# Hedge one unit of 10y with 2y, 5y and 30y so that PC1, PC2 and PC3 all net to zero.
legs = [tenors.index(t) for t in ("2 Yr", "5 Yr", "30 Yr")]
weights = np.linalg.solve(result.loadings[:, legs], result.loadings[:, i10])
for t, w in zip(("2y", "5y", "30y"), weights, strict=True):
    print(f"hedge leg {t:<4} {w:>8.4f}")
residual = changes[:, i10] - changes[:, legs] @ weights
print(f"10y daily sd {changes[:, i10].std(ddof=1):.3f} bp, hedged {residual.std(ddof=1):.3f} bp")
```

Expected output:

```
2749 daily changes, 2015-01-02 to 2025-12-31
          2 Yr    3 Yr    5 Yr    7 Yr   10 Yr   20 Yr   30 Yr  explained
PC1      0.338   0.378   0.410   0.418   0.394   0.358   0.341     0.8782
PC2     -0.538  -0.418  -0.190   0.013   0.199   0.445   0.513     0.0995
PC3      0.653  -0.050  -0.402  -0.413  -0.196   0.225   0.386     0.0114
10y hedged with 5y alone, 1 factor(s): 0.9629
10y hedged with 5y alone, 2 factor(s): 0.9153
10y hedged with 5y alone, 3 factor(s): 0.9101
hedge leg 2y    -0.1565
hedge leg 5y     0.6933
hedge leg 30y    0.4798
10y daily sd 5.351 bp, hedged 0.907 bp
```

The first three components explain 87.8, 10.0 and 1.1 percent of the variance of daily changes, the same values `data/fixtures/ust_pca_summary.json` records, and `test_summarise_matches_committed_summary` and `test_first_component_explains_most_of_real_daily_changes` in `research/tests/test_curves.py` check them on the fixture. The one-factor 5y hedge is the loading ratio 0.394 / 0.410 = 0.963 and moves toward the regression slope as factors are added (`test_pca_hedge_with_all_factors_equals_regression_slope` shows they coincide with all seven). The three-leg hedge sells 2y against 5y and 30y, its weights sum to 1.017 because it must offset the level, and it cuts the 10-year's daily standard deviation from 5.35 bp to 0.91 bp; what remains is the variance in components four to seven.

## 32. How do I fit the hit-rate model and read its calibration across the assumption band?

`ratesresearch.hitrate.simulate_labels` draws labelled RFQs from the declared competition model, preset `usd_rfq_competition` in `data/presets.yaml`, and `fit` estimates the logistic win probability by Newton-Raphson on the first 70 percent of them, with standard errors from the inverse observed information. No public tape shows losing quotes (DR-008), so the spread-to-cover slope is a declared band (low -1.25, central -2.5, high -5.0 per bp, `ratesresearch.assumptions.SLOPE_BAND`); the program generates 20,000 labels at each point with the default seed, fits them, and runs `calibration_table` on the held-out 30 percent in quintiles of predicted probability.

```python
"""COOKBOOK.md recipe 32: fit the hit-rate model at each point of the slope band."""

import copy
from pathlib import Path

from ratesresearch import hitrate, presets

root = Path(__file__).resolve().parents[2]
competition = presets.get_preset(root / "data/presets.yaml", "usd_rfq_competition")
band = competition["assumptions"]["spread_to_cover_bp"]["band"]

fits, tables = {}, {}
for point in ("low", "central", "high"):
    # simulate_labels draws with the band's central slope, so move that slot to this point.
    comp = copy.deepcopy(competition)
    comp["assumptions"]["spread_to_cover_bp"]["band"]["central"] = band[point]
    x, y = hitrate.simulate_labels(comp, 20_000)
    fits[point] = f = hitrate.fit(x, y)
    tables[point] = hitrate.calibration_table(x[f.n_fit :], y[f.n_fit :], f.beta, bins=5)

print(f"{'band point':<20}" + "".join(f"{p:>20}" for p in fits))
print(f"{'declared slope':<20}" + "".join(f"{band[p]:>20.2f}" for p in fits))
for j, name in enumerate(hitrate.FEATURES):
    cells = "".join(f"{f.beta[j]:>10.3f} +/- {f.se[j]:.3f}" for f in fits.values())
    print(f"{name:<20}{cells}")
for label, attr in (("oos log-loss model", "log_loss_model"), ("constant", "log_loss_constant")):
    print(f"{label:<20}" + "".join(f"{getattr(f, attr):>20.4f}" for f in fits.values()))

print("test-set calibration by quintile of predicted win probability (predicted / observed)")
for b in range(5):
    cells = "".join(f"{tables[p][b][0]:>12.3f} / {tables[p][b][1]:.3f}" for p in fits)
    print(f"{f'quintile {b + 1}':<20}{cells}")
```

Expected output:

```
band point                           low             central                high
declared slope                     -1.25               -2.50               -5.00
intercept                1.228 +/- 0.077     1.185 +/- 0.081     1.087 +/- 0.092
spread_to_cover_bp      -1.268 +/- 0.053    -2.490 +/- 0.061    -4.998 +/- 0.089
log_size_mm             -0.145 +/- 0.015    -0.145 +/- 0.015    -0.141 +/- 0.018
tenor_years             -0.012 +/- 0.002    -0.012 +/- 0.002    -0.011 +/- 0.002
fixing_window            0.324 +/- 0.041     0.281 +/- 0.043     0.337 +/- 0.049
dealer_count            -0.255 +/- 0.016    -0.239 +/- 0.017    -0.223 +/- 0.019
oos log-loss model                0.6526              0.6014              0.4950
constant                          0.6888              0.6905              0.6923
test-set calibration by quintile of predicted win probability (predicted / observed)
quintile 1                 0.271 / 0.268       0.190 / 0.176       0.078 / 0.067
quintile 2                 0.383 / 0.367       0.343 / 0.352       0.253 / 0.296
quintile 3                 0.459 / 0.468       0.462 / 0.443       0.461 / 0.451
quintile 4                 0.536 / 0.524       0.585 / 0.587       0.684 / 0.703
quintile 5                 0.657 / 0.638       0.756 / 0.759       0.895 / 0.877
```

At each band point the fit recovers the declared slope within one standard error (-1.268, -2.490, -4.998) and the other coefficients near the simulator's true values (intercept 1.2, log size -0.15, tenor -0.01, fixing window 0.3, dealer count -0.25), so the pipeline returns what it was given; it measures no market. The held-out log-loss beats the constant predictor at every point, by more as the slope steepens, and observed rates sit within 0.05 of predicted ones in every quintile, inside the 0.06 bound that `test_hitrate_fit_beats_constant_and_recovers_slope` in `research/tests/test_p5_research.py` asserts. The committed preset `usd_rfq_hit_rate` (-2.57 +/- 0.04) is fitted to 40,000 labels, which is why it differs from the central column here; LEARN.md Section 10, "Why the band, and not one number", explains why results are reported at all three points.

## 33. How do I compute markout curves by client tier from a back-test run?

`ratesresearch.backtest_analysis.load_run` reads the two fitting-period runs committed under `data/fixtures/backtest/fitting/`, and `ratesresearch.markouts.curves` converts each client fill's dealer markout into a mid-to-mid move in basis points of yield (dealer markout less the edge earned, divided by the fill's DV01) and averages it by tier at 1 s, 10 s, 1 min and 5 min. A negative number means mid moved with the client, against us. `informed_share` estimates the informed fraction of each tier's flow as twice the share of fills where the client's direction matched the one-minute move, less one, with a binomial standard error.

```python
"""COOKBOOK.md recipe 33: markout curves by client tier from the fitting-period runs."""

from pathlib import Path

import pandas as pd

from ratesresearch import backtest_analysis as ba
from ratesresearch import markouts

root = Path(__file__).resolve().parents[2]
days = sorted((root / "data/fixtures/backtest/fitting").iterdir())
fills = pd.concat([ba.load_run(d).fills for d in days], ignore_index=True)
print("runs:", ", ".join(d.name for d in days))

by_tier = markouts.curves(fills, "tier")
print(f"{'tier':<6}{'fills':>6}" + "".join(f"{h:>9}" for h in markouts.HORIZONS))
for _, r in by_tier.iterrows():
    cells = "".join(f"{r[f'bp_{h}']:>9.4f}" for h in markouts.HORIZONS)
    print(f"{int(r['tier']):<6}{int(r['fills']):>6}{cells}")

share = markouts.informed_share(fills, "1min", by="tier")
print("informed share at 1min (2 * agreement - 1)")
for _, r in share.iterrows():
    tier, n = int(r["group"]), int(r["fills"])
    print(f"tier {tier}: {r['informed_share']:.3f} +/- {r['se']:.3f} ({n} fills)")
```

Expected output:

```
runs: 2026-01-05, 2026-01-06
tier   fills       1s      10s     1min     5min
1        145  -0.0057  -0.0186  -0.0792  -0.0815
2         82   0.0083   0.0060  -0.0537   0.2437
3         56   0.0066  -0.0287  -0.2444  -0.2621
informed share at 1min (2 * agreement - 1)
tier 1: 0.220 +/- 0.082 (141 fills)
tier 2: 0.190 +/- 0.110 (79 fills)
tier 3: 0.519 +/- 0.116 (54 fills)
```

Tier 3 loses 0.24 bp at one minute against 0.08 bp for tier 1 and 0.05 bp for tier 2, and its informed-share estimate, 0.52 +/- 0.12, is the highest; `test_markouts_rank_tiers_and_estimate_informed_share` in `research/tests/test_p5_research.py` checks on these same two runs that tier 3 is more adverse than tier 1 and that each estimate lies within three standard errors (plus 0.05) of the generator's truth in `truth.csv`. With 56 to 145 fills per tier the curves are noisy (tier 2 turns positive at five minutes), so LEARN.md Section 10, worked example P5-001, quotes the tier figures from all ten fitting sessions rather than from these two.

## 34. How do I validate a deployment config against its schema and read the error?

Every process reads a YAML configuration that must validate against `deploy/config/schema/process-config.schema.json` before it starts (API_SPEC.md Section 17.11); `ProcessConfig.check` returns every violation as a JSON-path message, `ProcessConfig.load` applies the environment overrides `RATES_HTTP_PORT` and `RATES_JOURNAL_DIR` first and throws `InvalidConfigException` if the result does not validate, and `ConfigValidatorMain` wraps the same check for CI. The program validates the shipped `deploy/config/trading-blue.yaml`, then the same document with four hand-made mistakes, then the shipped file with a bad port in the environment. `rates-cookbook` depends on `rates-ops` for this recipe.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.ops.config.JsonSchema;
import io.ratesalgo.ops.config.ProcessConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** COOKBOOK.md recipe 34: validate a deployment config against its schema and read the error. */
public final class Recipe34ConfigSchema {

  private Recipe34ConfigSchema() {}

  @SuppressWarnings("unchecked")
  public static void main(final String[] args) throws IOException {
    final Path root = RepoRoot.find();
    final JsonSchema schema = ProcessConfig.schema(root.resolve("deploy/config/schema/process-config.schema.json"));
    final Path blue = root.resolve("deploy/config/trading-blue.yaml");

    // the shipped file: valid, and the typed view the process starts from
    System.out.println("deploy/config/trading-blue.yaml errors: " + ProcessConfig.check(blue, schema));
    final ProcessConfig cfg = ProcessConfig.load(blue, schema, Map.of());
    System.out.println("  " + cfg.name() + " role " + cfg.role() + " colour " + cfg.colour() + " port " + cfg.port()
        + " instruments " + cfg.instruments().size() + " retention " + cfg.retentionYears() + "y");

    // the same document with four mistakes an operator could make by hand
    final Map<String, Object> doc;
    try (Reader r = Files.newBufferedReader(blue, StandardCharsets.UTF_8)) {
      doc = (Map<String, Object>) ProcessConfig.parse(r);
    }
    ((Map<String, Object>) doc.get("process")).put("colour", "red");
    ((Map<String, Object>) doc.get("http")).put("port", 80810);
    ((Map<String, Object>) doc.get("journal")).put("capacityBytes", "64MB");
    doc.remove("retention");
    final List<String> errors = schema.validate(doc);
    System.out.println("edited document: " + errors.size() + " errors");
    errors.forEach(e -> System.out.println("  " + e));

    // an environment override is validated too: the process refuses to start
    try {
      ProcessConfig.load(blue, schema, Map.of("RATES_HTTP_PORT", "70000"));
    } catch (final ProcessConfig.InvalidConfigException e) {
      System.out.println("RATES_HTTP_PORT=70000 refused: " + e.errors());
    }
  }
}
```

Expected output:

```
deploy/config/trading-blue.yaml errors: []
  trading-blue role trading colour blue port 8081 instruments 2 retention 5y
edited document: 4 errors
  $: missing required property 'retention'
  $.process.colour: red is not one of [blue, green]
  $.http.port: 80810 is above the maximum 65535
  $.journal.capacityBytes: expected integer, found String 64MB
RATES_HTTP_PORT=70000 refused: [$.http.port: 70000 is above the maximum 65535]
```

The shipped file has no errors and loads to its typed view. The edited document reports all four mistakes at once, each with the path to the offending value and what the schema expected, so an operator fixes them in one pass; a missing section is reported at `$`, the root. The override is checked like the file, so a process started with `RATES_HTTP_PORT=70000` refuses to start instead of binding an unexpected port. `ConfigValidationTest.brokenConfigurationsReportEveryViolation` and `environmentOverridesApplyBeforeValidation` assert this, and the ops workflow runs the validator on every committed configuration.

## 35. How do I verify a Java journal's hash chain from Python?

Every journal frame stores a chain hash: FNV-1a 64 over the previous frame's hash, as eight little-endian bytes, followed by this frame's payload (API_SPEC.md Section 1). `ratesresearch.fnv.fnv1a64` is the same function in Python, so a research script can check that a journal written by Java's `JournalWriter` is complete and untouched without starting a JVM. The program first checks the shared known answers in `data/golden/chain_hash_vectors.json`, which `rates-journal` `ChainHashTest` also checks, then walks `data/fixtures/journal/recipe02.raj`: the three frames of recipe 2, written by `JournalWriter` into a 256-byte file. `JournalFixtureTest` in `rates-cookbook` rewrites that file with the current `JournalWriter` on every build and fails if a byte differs, so the fixture cannot fall behind the Java format. Java stores the hash in a signed `long`; `to_java_long` reinterprets the Python value before comparing.

```python
"""COOKBOOK.md recipe 35: verify a journal written by Java's JournalWriter from Python."""

import json
import struct
from pathlib import Path

from ratesresearch.fnv import fnv1a64, to_java_long

root = Path(__file__).resolve().parents[2]

# 1. the shared known answers that rates-journal ChainHashTest also checks
vectors = json.loads((root / "data/golden/chain_hash_vectors.json").read_text(encoding="utf-8"))
for v in vectors["vectors"]:
    got = fnv1a64(v["payload_ascii"].encode("ascii"), int(v["previous"], 16))
    verdict = "ok" if got == int(v["hash"], 16) else "MISMATCH"
    print(f"vector previous={v['previous']} payload={v['payload_ascii']!r}: {got:#018x} {verdict}")


# 2. walk the frames of a file JournalWriter wrote (pinned by rates-cookbook JournalFixtureTest)
def walk(data: bytes) -> tuple[int, int]:
    """Return (frames verified, final hash); stop at the first broken link."""
    pos, prev, count = 32, 0, 0
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            break
        seq, ts, template, _version, stored = struct.unpack_from("<qqHHq", data, pos + 4)
        payload = data[pos + 32 : pos + length]
        h = fnv1a64(payload, prev)
        if to_java_long(h) != stored:
            stored_hex = f"{stored & (2**64 - 1):#018x}"
            print(f"  chain broken at sequence {seq}: stored {stored_hex}, computed {h:#018x}")
            return count, prev
        (value,) = struct.unpack_from("<q", payload)
        print(f"  seq={seq} ts={ts} template={template} value={value} hash={h:016x}")
        prev, count = h, count + 1
        pos += (length + 7) & ~7
    return count, prev


data = (root / "data/fixtures/journal/recipe02.raj").read_bytes()
magic, version, header, capacity = struct.unpack_from("<4sHHi", data, 0)
print(f"journal {magic.decode()} version {version} header {header} bytes capacity {capacity}")
frames, final = walk(data)

# 3. the final hash Java printed in recipe 2's recorded output
java_out = root / "rates-cookbook/src/test/resources/expected/recipe02.txt"
words = java_out.read_text(encoding="utf-8").split()
java_final = next(w for w in words if w.startswith("finalHash=")).split("=")[1]
match = f"{final:x}" == java_final
print(f"frames={frames} finalHash={final:x} java finalHash={java_final} match={match}")

# 4. flip one payload byte of the second frame, as recipe 2 does
tampered = bytearray(data)
tampered[32 + 40 + 32] ^= 0x01
print("after flipping one byte of frame 2:")
frames, _ = walk(bytes(tampered))
print(f"frames verified before the break: {frames}")
```

Expected output:

```
vector previous=0x0 payload='': 0xa8c7f832281a39c5 ok
vector previous=0x0 payload='hello': 0x8fe9535b8a55c92b ok
vector previous=0x8fe9535b8a55c92b payload='world': 0xb71f85c4c4115848 ok
journal RAJ1 version 1 header 32 bytes capacity 256
  seq=1 ts=1000 template=1 value=100 hash=2cdc2273d758f7c1
  seq=2 ts=2000 template=1 value=200 hash=c88bd5b4bd2668cd
  seq=3 ts=3000 template=1 value=300 hash=c6d0336109f10ed2
frames=3 finalHash=c6d0336109f10ed2 java finalHash=c6d0336109f10ed2 match=True
after flipping one byte of frame 2:
  seq=1 ts=1000 template=1 value=100 hash=2cdc2273d758f7c1
  chain broken at sequence 2: stored 0xc88bd5b4bd2668cd, computed 0xa9910eabb2371eac
frames verified before the break: 1
```

The three vectors agree with the values computed independently on 2026-10-31. The final hash Python computes, `c6d0336109f10ed2`, is the one Java printed in recipe 2's recorded output, read from `rates-cookbook/src/test/resources/expected/recipe02.txt` rather than copied in, so the two languages are compared on every run of `tools/check_cookbook.py`. Flipping one bit of frame 2's payload breaks the link at sequence 2, the same sequence Java's `Replayer` reports in recipe 2. `tools/journal_query.py` uses the same walk to answer incident questions from the command line.

## 36. How do I check that a hot-path package allocates nothing?

The hot path must not allocate per message, because allocation is what eventually causes a garbage collection pause. `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` counts the bytes the current thread has allocated, so a check is: warm the path up until the JIT has compiled it, read the counter, run a window of messages, read it again. The checks in this repository use windows (DR-INT-02): after one million warm-up calls, three windows of one million are measured; the last must allocate exactly zero bytes and no window after the first more than 1,024, because a JIT recompilation just after warm-up can allocate a few hundred bytes once. The first call to the bean stays outside the windows because some JDKs allocate on first use. The program measures an SBE `Quote` encoded into a reused buffer and decoded back with flyweights, then the same quote formatted as a `String`, the convenient way that allocates.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import java.lang.management.ManagementFactory;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 36: check that a hot path allocates nothing, with the thread's byte counter. */
public final class Recipe36AllocationCheck {

  private static final int WINDOW = 1_000_000;
  private static final int WINDOWS = 3;
  private static final long LATER_WINDOW_BOUND = 1024L;

  private static final UnsafeBuffer BUFFER = new UnsafeBuffer(new byte[128]);
  private static final MessageHeaderEncoder HEADER_OUT = new MessageHeaderEncoder();
  private static final MessageHeaderDecoder HEADER_IN = new MessageHeaderDecoder();
  private static final QuoteEncoder ENCODER = new QuoteEncoder();
  private static final QuoteDecoder DECODER = new QuoteDecoder();
  private static long checksum;
  private static String lastLine = "";

  private Recipe36AllocationCheck() {}

  /** The hot path: encode a Quote into a reused buffer and decode it back, flyweights only. */
  static void flyweightQuote(final long i) {
    ENCODER.wrapAndApplyHeader(BUFFER, 0, HEADER_OUT).venueId(101).instrumentId(1008)
        .bidQuantity(1_000_000_000L).askQuantity(1_000_000_000L).validUntilNanos(i).sentNanos(i);
    ENCODER.bidPrice().mantissa(99_500_000_000L + i);
    ENCODER.askPrice().mantissa(99_515_625_000L + i);
    HEADER_IN.wrap(BUFFER, 0);
    DECODER.wrapAndApplyHeader(BUFFER, 0, HEADER_IN);
    checksum += DECODER.askPrice().mantissa() - DECODER.bidPrice().mantissa();
  }

  /** The same quote logged the convenient way: a String per message. */
  static void stringQuote(final long i) {
    final String line = "quote venue=101 instrument=1008 bid=" + (99_500_000_000L + i) + " ask=" + (99_515_625_000L + i);
    lastLine = line; // escapes, so the JIT cannot remove the allocation
  }

  interface Path {
    void run(long i);
  }

  /** Warm up, then measure consecutive windows; returns the bytes this thread allocated in each. */
  static long[] measure(final Path path) {
    final com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    mx.getThreadAllocatedBytes(thread); // the first call may allocate; keep it outside the windows
    long i = 0;
    for (; i < WINDOW; i++) {
      path.run(i);
    }
    final long[] bytes = new long[WINDOWS];
    for (int w = 0; w < WINDOWS; w++) {
      final long before = mx.getThreadAllocatedBytes(thread);
      for (final long end = i + WINDOW; i < end; i++) {
        path.run(i);
      }
      bytes[w] = mx.getThreadAllocatedBytes(thread) - before;
    }
    return bytes;
  }

  public static void main(final String[] args) {
    final long[] hot = measure(Recipe36AllocationCheck::flyweightQuote);
    System.out.println("flyweight encode and decode, " + WINDOWS + " windows of " + WINDOW + " quotes after "
        + WINDOW + " warm-up");
    System.out.println("  last window allocated " + hot[WINDOWS - 1] + " bytes");
    boolean bounded = true;
    for (int w = 1; w < WINDOWS; w++) {
      bounded &= hot[w] <= LATER_WINDOW_BOUND;
    }
    System.out.println("  every window after the first within " + LATER_WINDOW_BOUND + " bytes: " + bounded);
    System.out.println("  quoted spread checksum " + (checksum == (long) (WINDOWS + 1) * WINDOW * 15_625_000L));

    final long[] cold = measure(Recipe36AllocationCheck::stringQuote);
    System.out.println("String per quote, same windows");
    System.out.println("  last window allocated more than one byte per quote: " + (cold[WINDOWS - 1] > WINDOW));
    System.out.println("  last line: " + lastLine);
  }
}
```

Expected output:

```
flyweight encode and decode, 3 windows of 1000000 quotes after 1000000 warm-up
  last window allocated 0 bytes
  every window after the first within 1024 bytes: true
  quoted spread checksum true
String per quote, same windows
  last window allocated more than one byte per quote: true
  last line: quote venue=101 instrument=1008 bid=99503999999 ask=99519624999
```

The flyweight path allocates nothing in the last window and the spread checksum confirms every quote was encoded and decoded. The `String` path allocates more than a byte per quote; the program prints a comparison, not the exact count, because the count depends on the JDK's string layout. The `String` is kept in a static field so it escapes: a value that does not escape can be removed by the JIT's escape analysis, which would make a wasteful path look free. The gating checks follow this pattern on the real loops: `LoopAllocationTest` in `rates-core`, `WiredLoopAllocationTest` in `rates-app`, `AllocationTest` in `rates-fix`, `QuotingAllocationTest`, `RouterAllocationTest` and `DeskQuoterAllocationTest`.

## 37. How do I check a forward-log ledger from Python?

The forward log (docs/research/PAPER_TRADING_PLAN.md) appends one JSON line per decision and per outcome to `forward/ledger.jsonl` on the `forward-log` branch. Each line carries `seq` and `prev_sha256`, the SHA-256 of the previous line's text, as the experiment ledger does (API_SPEC.md Section 16.8). `ratesresearch.forward.check` re-derives everything a reader should not have to take on trust, without the Java code: the chain, that each outcome closes the decision just before it, that each book line's close mark is position times mark change times value per unit, and that the total is the session P&L plus the close mark. The program builds a two-line ledger with made-up numbers, checks it, then rewrites the committed mid in the decision line, the kind of edit the chain exists to catch.

```python
"""COOKBOOK.md recipe 37: check a forward-log ledger from Python, then tamper with it."""

import hashlib
import json
import tempfile
from pathlib import Path

from ratesresearch.forward import check

# A two-line ledger in the forward layout: a decision, then the outcome that closes it.
# Made-up numbers: one 10-year swap position of 10 million (1e9 cents) marked 2 bp higher.
vpu = check.VALUE_PER_UNIT["103"]
mark = round(1_000_000_000 * (4.47 - 4.45) * vpu, 2)
decision = {"kind": "decision", "session": "2026-11-09", "mids": {"103": 4.45}}
outcome = {
    "kind": "outcome",
    "closes_seq": 1,
    "session": "2026-11-09",
    "book": {
        "103": {
            "position": 1_000_000_000,
            "mid_committed": 4.45,
            "mid_marked": 4.47,
            "close_mark": mark,
        }
    },
    "close_mark": mark,
    "session_result": {"position_pnl": 1250.0},
    "pnl_total": round(mark + 1250.0, 2),
}
lines, prev = [], check.GENESIS
for seq, entry in enumerate((decision, outcome), start=1):
    line = json.dumps(
        {**entry, "seq": seq, "prev_sha256": prev}, sort_keys=True, separators=(",", ":")
    )
    prev = hashlib.sha256(line.encode()).hexdigest()
    lines.append(line)
print(f"close mark of 10mm 10Y on +2 bp: {mark:,.2f} USD")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "ledger.jsonl"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("sound ledger problems:", check.check(path))
    # rewrite history: pretend the decision had committed a higher mid
    path.write_text(
        path.read_text(encoding="utf-8").replace('"mids":{"103":4.45}', '"mids":{"103":4.46}'),
        encoding="utf-8",
    )
    for problem in check.check(path):
        print("tampered:", problem)
```

Expected output:

```
close mark of 10mm 10Y on +2 bp: 16,220.00 USD
sound ledger problems: []
tampered: line 2: hash chain broken
tampered: seq 2 103: committed mid differs from the decision
```

A 10-year swap's value per unit is its annuity times 1e-4 (`InstrumentSet.usdDefault`, annuity 8.11), so 10 million of notional is worth 8,110 USD per bp and a 2 bp move is 16,220 USD, the first line. The edit to line 1 is caught twice: line 2's `prev_sha256` no longer matches, and the outcome's committed mid no longer agrees with the decision it claims to close. The scheduled job runs the same check on the real ledger after every cycle (`.github/workflows/forward.yml`).

