# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine.

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
