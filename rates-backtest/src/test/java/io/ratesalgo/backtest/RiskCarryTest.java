package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskCarryTest {

  private static FillRecord fill(final long seconds, final boolean hedge) {
    return new FillRecord(seconds, seconds * 1_000_000_000L, 1, 1, 100, 100, 0, hedge, 0, 1, 1, List.of());
  }

  @Test
  @DisplayName("carry is the time from each client fill to the next hedge fill; later fills are unhedged")
  void measuresCarry() {
    // clients at 0, 10, 50 and 200; hedges at 30 and 50; the client at 200 has no hedge after it
    final RiskCarry rc =
        RiskCarry.of(List.of(fill(50, true), fill(0, false), fill(10, false), fill(30, true), fill(50, false), fill(200, false)));
    assertEquals(4, rc.clientFills());
    assertEquals(1, rc.unhedgedClientFills());
    assertArrayEquals(new double[] {0, 20, 30}, rc.carrySeconds(), 1e-12);
    assertEquals(20, rc.quantile(0.5), 1e-12);
    assertEquals(30, rc.quantile(1.0), 1e-12);
    assertEquals(50.0 / 3, rc.mean(), 1e-12);
  }

  @Test
  @DisplayName("no hedges gives no carry times and NaN summaries")
  void noHedges() {
    final RiskCarry rc = RiskCarry.of(List.of(fill(1, false)));
    assertEquals(1, rc.unhedgedClientFills());
    assertTrue(Double.isNaN(rc.quantile(0.5)));
  }
}
