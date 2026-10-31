# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine. Recipes 4 to 8 (P1) print values rounded well above double-precision noise so that they are the same on every platform.

## The planned recipes

1. How do I use the ManualClock and TimerWheel in a test? (P0, written below)
2. How do I write frames to a journal and replay them? (P0, written below)
3. How do I encode and decode a QuoteRequest with the SBE codecs? (P0, written below)
4. How do I bootstrap a USD SOFR discount curve from OIS quotes? (P1)
5. How do I price a swap and get its par rate and DV01? (P1)
6. How do I price a Treasury from yield and get accrued interest? (P1)
7. How do I compute a gilt's accrued interest inside the ex-dividend period? (P1)
8. How do I find the CTD and net basis for a Treasury futures contract? (P1)
9. How do I compare a result to QuantLib within a stated tolerance? (P1)
10. How do I start a venue simulator and connect a FIX 4.4 session? (P2)
11. How do I subscribe to the SBE book feed and rebuild a book? (P2)
12. How do I run the event loop against a journal and check determinism? (P2)
13. How do I check that a hot-path package allocates nothing? (P2)
14. How do I configure a quoting parameter and see its effect on skew? (P3)
15. How do I read the DV01 ladder and ask the hedger what it would do? (P3)
16. How do I set a risk limit and trigger the kill switch in a test? (P3)
17. How do I route a hedge order and read the markout report? (P4, written below)
18. How do I pre-register a back-test and open the holdout once? (P5)
19. How do I run the latency job and read the histogram? (P5)
20. How do I verify a Java journal's hash chain from Python? (P5)

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

## 17. How do I route a hedge order and read the markout report?

Numbered 17, not 16 as the P4 brief said: the planned list above already gives 16 to the P3 kill-switch recipe and 17 to routing (DECISIONS.md DR-P4-05).

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
