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
17. How do I route a hedge order and read the markout report? (P4)
18. How do I pre-register a back-test and open the holdout once? (P5)
19. How do I run the latency job and read the histogram? (P5)
20. How do I run the chaos test and read the break report? (P6, written below; the Python hash-chain recipe planned here for P5 is renumbered at integration)

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

## 20. How do I run the chaos test and read the break report?

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

/** COOKBOOK.md recipe 20: run the chaos day and read the break report. */
public final class Recipe20ChaosBreakReport {

  private Recipe20ChaosBreakReport() {}

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
