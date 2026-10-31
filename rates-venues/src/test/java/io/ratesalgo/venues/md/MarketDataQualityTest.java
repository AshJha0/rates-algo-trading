package io.ratesalgo.venues.md;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.MarketDataRejectDecoder;
import io.ratesalgo.messages.sbe.MdRejectReason;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketDataQualityTest {

  static final long MID = 98_000_000_000L;
  static final long BAND = 250_000_000L;
  static final long SECOND = 1_000_000_000L;

  private final List<MdRejectReason> reasons = new ArrayList<>();
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final MarketDataRejectDecoder reject = new MarketDataRejectDecoder();

  private RejectJournal journal() {
    return new RejectJournal((b, o, l) -> {
      reject.wrapAndApplyHeader(b, o, header);
      reasons.add(reject.reason());
      return true;
    });
  }

  @Test
  void eachCheckRejectsWithItsReason() {
    final ManualClock clock = new ManualClock();
    final RejectJournal j = journal();
    final CompositeBuilder c = new CompositeBuilder(clock, new BandReference().set(1, MID), j, BAND, 5 * SECOND);
    assertTrue(c.onQuote(10, 1, 1, MID - 10_000_000, MID + 10_000_000, 0));
    assertFalse(c.onQuote(10, 1, 2, 0, MID, 0));
    assertFalse(c.onQuote(10, 1, 3, MID + 1, MID, 0));
    assertFalse(c.onQuote(10, 1, 4, MID, MID, 0));
    assertFalse(c.onQuote(10, 1, 1, MID - 1, MID + 1, 0));
    assertFalse(c.onQuote(10, 1, 5, MID + BAND * 2, MID + BAND * 2 + 10, 0));
    assertTrue(c.onQuote(10, 1, 9, MID - 5_000_000, MID + 5_000_000, 0));
    assertEquals(
        List.of(MdRejectReason.NonPositivePrice, MdRejectReason.CrossedQuote, MdRejectReason.LockedQuote,
            MdRejectReason.OutOfOrder, MdRejectReason.Outlier, MdRejectReason.SequenceGap),
        reasons);
    assertEquals(5, c.metrics(10).rejects());
    assertEquals(2, c.metrics(10).updates());
    assertEquals(1, j.count(MdRejectReason.Outlier));
    assertEquals(6, j.total());
  }

  @Test
  void compositeTakesBestOfFreshSourcesAndRejectsCrossedComposite() {
    final ManualClock clock = new ManualClock();
    final CompositeBuilder c = new CompositeBuilder(clock, id -> 0, journal(), BAND, 5 * SECOND);
    assertNull(c.composite(1));
    c.onQuote(1, 1, 1, MID - 20, MID + 20, 0);
    c.onQuote(2, 1, 1, MID - 10, MID + 30, 0);
    Composite comp = c.composite(1);
    assertEquals(MID - 10, comp.bid());
    assertEquals(MID + 20, comp.ask());
    assertEquals(2, comp.sources());
    c.onQuote(3, 1, 1, MID + 25, MID + 40, 0);
    assertEquals(MdRejectReason.CrossedComposite, reasons.get(reasons.size() - 1));
    assertEquals(MID - 10, c.composite(1).bid());
    c.onQuote(4, 1, 1, MID + 20, MID + 50, 0);
    assertTrue(reasons.contains(MdRejectReason.CrossedComposite));
    clock.advance(6 * SECOND);
    c.checkStaleness();
    assertTrue(c.metrics(1).isStale());
    assertEquals(1, c.metrics(1).staleTransitions());
    assertTrue(c.metrics(1).maxAgeNanos() >= 6 * SECOND);
    assertNull(c.composite(1));
    c.checkStaleness();
    assertEquals(1, c.metrics(1).staleTransitions());
    c.onQuote(1, 1, 2, MID - 20, MID + 20, 0);
    assertFalse(c.metrics(1).isStale());
    assertNotNull(c.composite(1));
    assertTrue(c.metrics(1).lastUpdateNanos() > 0);
    final CompositeBuilder locked = new CompositeBuilder(clock, id -> 0, journal(), BAND, 5 * SECOND);
    locked.onQuote(1, 2, 1, MID - 20, MID, 0);
    locked.onQuote(2, 2, 1, MID, MID + 20, 0);
    assertEquals(MdRejectReason.LockedComposite, reasons.get(reasons.size() - 1));
  }

  @Property(tries = 200)
  void injectedBadTicksNeverReachTheComposite(
      @ForAll @LongRange(min = 1, max = Long.MAX_VALUE) final long seed,
      @ForAll @IntRange(min = 50, max = 400) final int ticks) {
    final ManualClock clock = new ManualClock();
    final CompositeBuilder c = new CompositeBuilder(clock, new BandReference().set(1, MID), journal(), BAND, 5 * SECOND);
    final java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
    final Set<Long> goodBids = new HashSet<>();
    final Set<Long> goodAsks = new HashSet<>();
    final long[] seq = new long[4];
    for (int t = 0; t < ticks; t++) {
      clock.advance(r.nextLong(SECOND));
      final int source = r.nextInt(4);
      final long mid = MID + r.nextLong(-BAND / 2, BAND / 2);
      final long half = 1 + r.nextLong(50_000_000L);
      long bid = mid - half;
      long ask = mid + half;
      final int kind = r.nextInt(10);
      switch (kind) {
        case 0 -> { final long x = bid; bid = ask; ask = x; }
        case 1 -> ask = bid;
        case 2 -> bid = -bid;
        case 3 -> { bid += 10 * BAND; ask += 10 * BAND; }
        default -> { }
      }
      final boolean good = kind > 3;
      final boolean accepted = c.onQuote(source, 1, ++seq[source], bid, ask, 0);
      assertEquals(good, accepted, "kind " + kind);
      if (accepted) {
        goodBids.add(bid);
        goodAsks.add(ask);
      }
      if (r.nextInt(20) == 0) {
        c.checkStaleness();
      }
      final Composite comp = c.composite(1);
      if (comp != null) {
        assertTrue(goodBids.contains(comp.bid()), "composite bid from a bad tick");
        assertTrue(goodAsks.contains(comp.ask()), "composite ask from a bad tick");
        assertTrue(comp.bid() < comp.ask(), "composite crossed or locked");
      }
    }
  }

  @Test
  void fixingRepublication() {
    final ManualClock clock = new ManualClock(1_793_664_000_000_000_000L);
    final FixingBook f = new FixingBook(clock, journal());
    final long cutoff = clock.epochNanos() + 2 * 3_600 * SECOND;
    assertTrue(f.publish(1, 20261102, 3_650_000_000L, cutoff));
    assertFalse(f.publish(1, 20261102, 3_650_000_000L, cutoff));
    assertEquals(MdRejectReason.FixingRepublicationUnchanged, reasons.get(0));
    clock.advance(3_600 * SECOND);
    assertTrue(f.publish(1, 20261102, 3_660_000_000L, cutoff));
    assertEquals(3_660_000_000L, f.fixing(1, 20261102));
    assertEquals(1, f.republications());
    clock.advance(2 * 3_600 * SECOND);
    assertFalse(f.publish(1, 20261102, 3_670_000_000L, cutoff));
    assertEquals(MdRejectReason.FixingRepublicationLate, reasons.get(1));
    assertEquals(0, f.fixing(1, 20261103));
  }

  @Test
  void rejectionsAreJournaled(@TempDir final Path dir) {
    final ManualClock clock = new ManualClock();
    final Path file = dir.resolve("md.raj");
    try (JournalWriter w = JournalWriter.create(file, 1 << 16, clock)) {
      final CompositeBuilder c = new CompositeBuilder(clock, id -> 0, new RejectJournal(new JournalSink(w)), BAND, SECOND);
      c.onQuote(1, 7, 1, MID + 1, MID, 0);
      c.onQuote(1, 7, 2, MID, MID, 0);
    }
    final List<MdRejectReason> journaled = new ArrayList<>();
    try (JournalReader reader = JournalReader.open(file)) {
      while (reader.next((seq, ts, templateId, version, buffer, offset, length) -> {
        assertEquals(MarketDataRejectDecoder.TEMPLATE_ID, templateId);
        reject.wrapAndApplyHeader(new UnsafeBuffer(buffer, offset, length), 0, header);
        journaled.add(reject.reason());
      })) {
        // read every frame
      }
    }
    assertEquals(List.of(MdRejectReason.CrossedQuote, MdRejectReason.LockedQuote), journaled);
  }
}
