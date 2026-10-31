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
10. How do I start a venue simulator and connect a FIX 4.4 session? (P2, written below)
11. How do I subscribe to the SBE book feed and rebuild a book? (P2, written below)
12. How do I run the event loop against a journal and check determinism? (P2)
13. How do I check that a hot-path package allocates nothing? (P2)
14. How do I configure a quoting parameter and see its effect on skew? (P3)
15. How do I read the DV01 ladder and ask the hedger what it would do? (P3)
16. How do I set a risk limit and trigger the kill switch in a test? (P3)
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
