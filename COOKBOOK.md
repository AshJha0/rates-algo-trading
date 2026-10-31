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
12. How do I run the event loop against a journal and check determinism? (P2, written below)
13. How do I check that a hot-path package allocates nothing? (P2)
14. How do I configure a quoting parameter and see its effect on skew? (P3)
15. How do I read the DV01 ladder and ask the hedger what it would do? (P3)
16. How do I set a risk limit and trigger the kill switch in a test? (P3)
17. How do I route a hedge order and read the markout report? (P4)
18. How do I pre-register a back-test and open the holdout once? (P5)
19. How do I run the latency job and read the histogram? (P2, written below; P5 adds the rate sweep)
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

## 19. How do I run the latency job and read the histogram?

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

/** COOKBOOK.md recipe 19: read a latency histogram the way the harness writes it. */
public final class Recipe19LatencyHistogram {

  private Recipe19LatencyHistogram() {}

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
