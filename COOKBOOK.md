# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine. Recipes 14 to 16 (P3) were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11.

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
14. How do I quote an RFQ through the engine and see a quoting parameter's effect on skew? (P3, written below)
15. How do I read the DV01 ladder and compute a cost-aware hedge? (P3, written below)
16. How do I set a risk limit and exercise the kill switch? (P3, written below)
17. How do I route a hedge order and read the markout report? (P4)
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
