package io.ratesalgo.posttrade.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each break class, and duplicate handling inside one side. */
class ReconcilerTest {

  private static ReconItem item(final String key, final long qty, final long px, final long t) {
    return new ReconItem(key, 1001, Side.Buy, qty, px, t);
  }

  @Test
  void everyBreakClassIsFound() {
    final List<ReconItem> ours = List.of(item("1:E:1", 10, 5, 0), item("1:E:2", 10, 5, 0),
        item("1:E:3", 10, 5, 0), item("1:E:4", 10, 5, 0), item("1:E:6", 10, 5, 0));
    final List<ReconItem> theirs = List.of(item("1:E:1", 10, 5, 0), item("1:E:2", 11, 5, 0),
        item("1:E:3", 10, 6, 0), item("1:E:4", 10, 5, 5_000_000_000L), item("1:E:5", 10, 5, 0));
    final ReconResult r = Reconciler.trades("t", ours, theirs, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
    assertEquals(1, r.matched());
    assertEquals(1, r.count(BreakClass.QUANTITY));
    assertEquals(1, r.count(BreakClass.PRICE));
    assertEquals(1, r.count(BreakClass.TIMING));
    assertEquals(1, r.count(BreakClass.MISSING_OURS));
    assertEquals(1, r.count(BreakClass.MISSING_THEIRS));
    assertEquals(5, r.unexplained());
  }

  @Test
  void identicalRepeatsAreSuppressedAndDifferentRepeatsAreConflicts() {
    final List<ReconItem> theirs = List.of(item("1:E:1", 10, 5, 0), item("1:E:1", 10, 5, 0),
        item("1:E:2", 10, 5, 0), item("1:E:2", 10, 7, 0));
    final ReconResult r = Reconciler.trades("t", List.of(item("1:E:1", 10, 5, 0), item("1:E:2", 10, 5, 0)),
        theirs, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
    assertEquals(1, r.duplicatesSuppressed());
    assertEquals(1, r.breaks().size());
    assertEquals(Explanation.DUPLICATE_CONFLICT, r.breaks().get(0).explanation());
    assertFalse(r.breaks().get(0).explained());
  }

  @Test
  void positionBreaks() {
    final ReconResult r = Reconciler.positions("p", Map.of("a", 1L, "b", 2L, "c", 3L),
        Map.of("a", 1L, "b", 5L, "d", 4L), BreakExplainer.NONE);
    assertEquals(1, r.matched());
    assertEquals(1, r.count(BreakClass.QUANTITY));
    assertEquals(1, r.count(BreakClass.MISSING_THEIRS));
    assertEquals(1, r.count(BreakClass.MISSING_OURS));
  }

  @Test
  void tradeKeysParseAndOrder() {
    final TradeKey k = TradeKey.parse("2:R:71");
    assertEquals(new TradeKey(2, TradeSource.Rfq, 71), k);
    assertEquals("2:R:71", k.toString());
    assertTrue(TradeKey.parse("1:E:5").compareTo(k) < 0);
    assertThrows(IllegalArgumentException.class, () -> TradeKey.parse("1:X:5"));
    assertThrows(IllegalArgumentException.class, () -> TradeKey.parse("15"));
    assertThrows(IllegalArgumentException.class, () -> new TradeKey(1, TradeSource.NULL_VAL, 1));
    assertEquals(Explanation.NONE, BreakExplainer.NONE.explain("x", BreakClass.PRICE));
    assertEquals(k, new ReconItem("2:R:71", 1, Side.Sell, 1, 1, 1).tradeKey());
  }
}
