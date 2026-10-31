package io.ratesalgo.posttrade.surveillance;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurveillanceTest {

  @TempDir Path dir;

  @Test
  void quoteStuffingAlertsOncePerEpisode() {
    final Path j = dir.resolve("s.raj");
    try (JournalScript s = new JournalScript(j, 1 << 20, SyntheticDay.MIDNIGHT)) {
      for (int i = 0; i < 30; i++) {
        s.quote(NINE + i * 1_000_000L, 2, 1, i, 2001, px("4.25"), px("4.26"), 1, NINE + 1);
      }
      for (int i = 0; i < 5; i++) {
        s.newOrder(NINE + 5_000_000_000L + i * 1_000_000_000L, 1, i, 1001, Side.Buy, px("99"), 1);
      }
      for (int i = 0; i < 30; i++) {
        s.newOrder(NINE + 20_000_000_000L + i * 1_000_000L, 1, 100 + i, 1001, Side.Buy, px("99"), 1);
      }
    }
    final QuoteStuffingDetector d = new QuoteStuffingDetector(1_000_000_000L, 20);
    new Replayer(new ManualClock(0)).replay(j, d);
    assertEquals(2, d.alerts().size(), d.alerts()::toString);
    assertEquals(2001, d.alerts().get(0).instrumentId());
    assertEquals(1001, d.alerts().get(1).instrumentId());
    assertEquals("quote-stuffing", d.name());
    assertThrows(IllegalArgumentException.class, () -> new QuoteStuffingDetector(0, 1));
  }

  @Test
  void selfTradePatternIsFlagged() {
    final Path j = dir.resolve("w.raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      s.executionReport(NINE, 1, 1, 501, 11, 1001, Side.Buy, OrdStatus.Filled, px("99.5"), 100, 0, 100, NINE);
      s.executionReport(NINE + 1, 1, 2, 502, 12, 1001, Side.Sell, OrdStatus.Filled, px("99.5"), 100, 0, 100,
          NINE + 1_000_000_000L);
      // different price, and a sell outside the window: no alert
      s.executionReport(NINE + 2, 1, 3, 503, 13, 1001, Side.Sell, OrdStatus.Filled, px("99.6"), 100, 0, 100,
          NINE + 2_000_000_000L);
      s.executionReport(NINE + 3, 1, 4, 504, 14, 1001, Side.Sell, OrdStatus.Filled, px("99.5"), 100, 0, 100,
          NINE + 100_000_000_000L);
      s.executionReport(NINE + 4, 1, 5, 505, 15, 1001, Side.Buy, OrdStatus.New, 0, 0, 100, 0, NINE + 4);
    }
    final SelfTradeDetector d = new SelfTradeDetector(60_000_000_000L);
    new Replayer(new ManualClock(0)).replay(j, d);
    assertEquals(1, d.alerts().size(), d.alerts()::toString);
    assertTrue(d.alerts().get(0).detail().contains("exec 11 Buy and exec 12 Sell"));
    assertEquals("self-trade", d.name());
    assertThrows(IllegalArgumentException.class, () -> new SelfTradeDetector(0));
  }
}
