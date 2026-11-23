package io.ratesalgo.posttrade.surveillance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.chaos.JournalScript;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The window edges, re-arming and message details of the two surveillance detectors. */
class DetectorEdgesTest {

  private static final long T0 = 1_800_000_000_000_000_000L;
  private static final long W = 1_000L;

  @TempDir Path dir;

  private <D extends SurveillanceDetector> D feed(final D detector, final Consumer<JournalScript> script) {
    final Path j = dir.resolve("j" + System.nanoTime() + ".raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, T0)) {
      script.accept(s);
    }
    new Replayer(new ManualClock(0)).replay(j, detector);
    return detector;
  }

  private static void fill(final JournalScript s, final long execId, final Side side, final long qty,
      final OrdStatus status, final long transact) {
    s.executionReport(T0 + execId, 1, execId, 500, execId, 1001, side, status, 100_000_000_000L, qty, 0, qty, transact);
  }

  @Test
  void selfTradesAreMatchedUpToExactlyTheWindowAndReportTheGap() {
    final SelfTradeDetector d = feed(new SelfTradeDetector(W), s -> {
      fill(s, 1, Side.Buy, 100, OrdStatus.Filled, T0);
      fill(s, 2, Side.Sell, 100, OrdStatus.PartiallyFilled, T0 + W);
    });
    assertEquals(1, d.alerts().size());
    assertEquals("venue 1 exec 1 Buy and exec 2 Sell at 100000000000 within 1000 ns", d.alerts().get(0).detail());
    assertEquals(1001, d.alerts().get(0).instrumentId());
    assertEquals("self-trade", d.name());
  }

  @Test
  void reportsThatAreNotFillsAreNeverMatched() {
    final SelfTradeDetector d = feed(new SelfTradeDetector(W), s -> {
      fill(s, 1, Side.Buy, 100, OrdStatus.Filled, T0);
      fill(s, 2, Side.Sell, 0, OrdStatus.Filled, T0 + 1);
      fill(s, 3, Side.Sell, 100, OrdStatus.New, T0 + 2);
      fill(s, 4, Side.Sell, 100, OrdStatus.Cancelled, T0 + 3);
      fill(s, 5, Side.Buy, 100, OrdStatus.Filled, T0 + 4);
    });
    assertEquals(0, d.alerts().size());
  }

  @Test
  void aFillDroppedFromTheWindowDoesNotReturnForALateReport() {
    final SelfTradeDetector d = feed(new SelfTradeDetector(W), s -> {
      fill(s, 1, Side.Buy, 100, OrdStatus.Filled, T0);
      fill(s, 2, Side.Buy, 100, OrdStatus.Filled, T0 + W + 1); // the first buy leaves the window
      fill(s, 3, Side.Sell, 100, OrdStatus.Filled, T0 + W / 2); // late: within W of the first only
    });
    assertEquals(1, d.alerts().size(), "matched with the second buy only");
    assertEquals("venue 1 exec 2 Buy and exec 3 Sell at 100000000000 within -501 ns", d.alerts().get(0).detail());
  }

  @Test
  void stuffingCountsMessagesInsideAWindowThatExcludesItsOldEdgeAndReArmsAtHalfTheThreshold() {
    final QuoteStuffingDetector d = feed(new QuoteStuffingDetector(W, 4), s -> {
      for (long t = 0; t <= 4; t++) {
        s.quote(T0 + t, 2, 61, 70 + t, 2001, 1, 2, 1_000, T0 + 100);
      }
      s.quote(T0 + W + 3, 2, 61, 80, 2001, 1, 2, 1_000, T0 + 100); // 0..3 leave: 2 left, re-armed
      s.quote(T0 + W + 4, 2, 61, 81, 2001, 1, 2, 1_000, T0 + 100);
      for (long t = 5; t <= 7; t++) {
        s.quote(T0 + W + t, 2, 61, 82 + t, 2001, 1, 2, 1_000, T0 + 100);
      }
    });
    assertEquals(2, d.alerts().size());
    assertEquals("5 quotes, orders and cancels in 1000 ns, threshold 4", d.alerts().get(0).detail());
    assertEquals(10L, d.messages(2001));
    assertEquals(0L, d.cancels(2001));

    final QuoteStuffingDetector edge = feed(new QuoteStuffingDetector(W, 1), s -> {
      s.quote(T0, 2, 61, 70, 2001, 1, 2, 1_000, T0 + 100);
      s.quote(T0 + W, 2, 61, 71, 2001, 1, 2, 1_000, T0 + 100); // the first is exactly one window old
    });
    assertEquals(0, edge.alerts().size());
  }

  @Test
  void detectorsRefuseAnEmptyWindowOrThreshold() {
    assertThrows(IllegalArgumentException.class, () -> new QuoteStuffingDetector(0, 5));
    assertThrows(IllegalArgumentException.class, () -> new QuoteStuffingDetector(5, 0));
    assertThrows(IllegalArgumentException.class, () -> new SelfTradeDetector(0));
  }
}
