package io.ratesalgo.venues.clob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClobBookTest {

  private final List<String> events = new ArrayList<>();
  private final ClobBook.Listener listener =
      new ClobBook.Listener() {
        @Override
        public void onFill(final ClobOrder a, final ClobOrder p, final long price, final long qty, final char w) {
          events.add("fill " + a.owner() + "/" + p.owner() + " " + qty + "@" + price + " " + w);
        }

        @Override
        public void onBookCancel(final ClobOrder o, final String reason) {
          events.add("cancel " + o.owner() + " " + reason);
        }
      };
  private long ids;

  private ClobOrder order(final String owner, final boolean buy, final long price, final long qty) {
    return new ClobOrder(++ids, owner, ids, buy, price, qty, 0, false);
  }

  @Test
  void priceTimePriorityAndPartialFills() {
    final ClobBook b = new ClobBook(1, ClobConfig.defaults().withoutWorkup(), listener);
    b.submit(order("A", false, 101, 2_000_000), 0);
    b.submit(order("B", false, 100, 1_000_000), 0);
    b.submit(order("C", false, 100, 1_000_000), 0);
    final ClobOrder buy = order("D", true, 101, 3_000_000);
    b.submit(buy, 0);
    assertEquals(List.of("fill D/B 1000000@100 N", "fill D/C 1000000@100 N", "fill D/A 1000000@101 N"), events);
    assertTrue(buy.isDone());
    assertEquals(3_000_000, buy.cumQty());
    assertEquals(List.of(101L, 1_000_000L, 1L), List.of(b.levels(false, 5).get(0)[0], b.levels(false, 5).get(0)[1], b.levels(false, 5).get(0)[2]));
    assertTrue(b.levels(true, 5).isEmpty());
  }

  @Test
  void validationRules() {
    final ClobBook b = new ClobBook(1, ClobConfig.defaults(), listener);
    assertNull(b.validate(98_000_000_000L, 2_000_000, 1_000_000));
    assertTrue(b.validate(98_000_000_000L, 1_500_000, 0).contains("minimum size"));
    assertTrue(b.validate(98_000_000_001L, 1_000_000, 0).contains("tick"));
    assertTrue(b.validate(98_000_000_000L, 2_000_000, 3_000_000).contains("display"));
    assertTrue(b.validate(0, 1_000_000, 0).contains("tick"));
    assertThrows(IllegalArgumentException.class, () -> new ClobConfig(0, 1, 0, 0, false, SelfMatch.CANCEL_RESTING, 5));
  }

  @Test
  void icebergRefreshLosesPriority() {
    final ClobBook b = new ClobBook(1, ClobConfig.defaults().withoutWorkup(), listener);
    final ClobOrder ice = new ClobOrder(++ids, "ICE", 1, false, 100, 4_000_000, 1_000_000, false);
    b.submit(ice, 0);
    b.submit(order("B", false, 100, 1_000_000), 0);
    assertEquals(2_000_000, b.levels(false, 1).get(0)[1]);
    b.submit(order("D", true, 100, 2_000_000), 0);
    assertEquals(List.of("fill D/ICE 1000000@100 N", "fill D/B 1000000@100 N"), events);
    assertEquals(1_000_000, ice.visible());
    assertEquals(3_000_000, ice.remaining());
    assertEquals(1_000_000, ice.display());
  }

  @Test
  void selfMatchPreventionModes() {
    final ClobBook resting = new ClobBook(1, ClobConfig.defaults().withoutWorkup(), listener);
    resting.submit(order("A", false, 100, 1_000_000), 0);
    resting.submit(order("B", false, 100, 1_000_000), 0);
    resting.submit(order("A", true, 100, 1_000_000), 0);
    assertEquals(List.of("cancel A self-match prevention", "fill A/B 1000000@100 N"), events);
    events.clear();
    final ClobConfig c = ClobConfig.defaults();
    final ClobBook aggressor =
        new ClobBook(1, new ClobConfig(c.tickSize(), c.minQty(), 0, 0, false, SelfMatch.CANCEL_AGGRESSOR, 5), listener);
    final ClobOrder rest = order("A", false, 100, 1_000_000);
    aggressor.submit(rest, 0);
    final ClobOrder in = order("A", true, 100, 1_000_000);
    aggressor.submit(in, 0);
    assertEquals(List.of("cancel A self-match prevention"), events);
    assertTrue(in.isDone());
    assertFalse(rest.isDone());
  }

  @Test
  void workupExclusiveWindowHoldsOutsiders() {
    final ClobBook b = new ClobBook(1, ClobConfig.defaults(), listener);
    final long s = 1_000_000_000L;
    b.submit(order("A", false, 100, 10_000_000), 0);
    b.submit(order("B", true, 100, 1_000_000), 0);
    assertTrue(b.inWorkup());
    assertEquals(100, b.workupPrice());
    final ClobOrder outsider = order("C", true, 100, 1_000_000);
    b.submit(outsider, s);
    assertEquals(1, b.held().size());
    final ClobOrder crossingOther = order("E", true, 101, 1_000_000);
    b.submit(crossingOther, s);
    b.submit(order("B", true, 100, 1_000_000), s);
    assertEquals("fill B/A 1000000@100 X", events.get(events.size() - 1));
    b.submit(order("F", false, 99, 1_000_000), s);
    b.onTime(3 * s);
    assertTrue(events.contains("fill C/A 1000000@100 W"), events.toString());
    b.onTime(10 * s);
    assertFalse(b.inWorkup());
    assertTrue(b.held().isEmpty());
    b.submit(order("G", true, 100, 1_000_000), 11 * s);
    assertTrue(events.get(events.size() - 1).endsWith(" N"), events.toString());
  }

  @Test
  void cancelReplaceAndIoc() {
    final ClobBook b = new ClobBook(1, ClobConfig.defaults().withoutWorkup(), listener);
    final ClobOrder a = order("A", true, 100, 3_000_000);
    final ClobOrder c = order("C", true, 100, 1_000_000);
    b.submit(a, 0);
    b.submit(c, 0);
    assertTrue(b.replace(a, 100, 2_000_000, 0));
    b.submit(order("S", false, 100, 1_000_000), 0);
    assertEquals("fill S/A 1000000@100 N", events.get(0));
    assertTrue(b.replace(a, 100, 5_000_000, 0));
    b.submit(order("S", false, 100, 1_000_000), 0);
    assertEquals("fill S/C 1000000@100 N", events.get(1));
    assertTrue(b.replace(a, 100, 1_000_000, 0));
    assertTrue(a.isDone());
    assertFalse(b.replace(a, 100, 3_000_000, 0));
    assertFalse(b.cancel(a));
    final ClobOrder ioc = new ClobOrder(++ids, "I", 9, false, 100, 2_000_000, 0, true);
    b.submit(ioc, 0);
    assertTrue(events.get(events.size() - 1).startsWith("cancel I immediate-or-cancel"));
    assertTrue(b.liveOrders().isEmpty());
  }
}
