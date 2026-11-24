package io.ratesalgo.posttrade.position;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * The keeper's books are a function of the set of events, not of the order they arrive in. For any
 * set of fills with distinct transaction times, any subset of them busted (the bust arriving before
 * or after its fill), identical repeats mixed in and the whole shuffled: the net position is the
 * sum of the signed quantities of the fills not busted, average cost and realised P&amp;L equal those
 * of the same events delivered in transaction order, nothing is left pending, every repeat is
 * ignored once and every fill that arrives after a later one is flagged late.
 */
class PositionKeeperPropertiesTest {

  private static final PositionKey BOND = new PositionKey(1, 10, 1001);

  /** One input event: a fill, a repeat of it, or a bust of it. */
  private record Event(int kind, Fill fill) {}

  private record Fill(long execId, Side side, long qty, long price, long transact) {}

  @Property(tries = 60, seed = "1123")
  void positionIsTheSumOfLiveFillsWhateverTheArrivalOrder(@ForAll final long seed) throws IOException {
    final Random rnd = new Random(seed);
    final int n = 3 + rnd.nextInt(14);
    final List<Fill> fills = new ArrayList<>();
    final List<Integer> times = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      times.add(i);
    }
    Collections.shuffle(times, rnd);
    for (int i = 0; i < n; i++) {
      fills.add(new Fill(i + 1L, rnd.nextBoolean() ? Side.Buy : Side.Sell, (1 + rnd.nextInt(9)) * 1_000_000L,
          Fixtures.px("99.5") + rnd.nextInt(2_000) * 1_000_000L, NINE + times.get(i) * 1_000_000L));
    }
    final List<Event> shuffled = new ArrayList<>();
    final List<Event> canonical = new ArrayList<>();
    long expectedNet = 0;
    int busted = 0;
    int repeats = 0;
    for (final Fill f : fills) {
      shuffled.add(new Event(0, f));
      if (rnd.nextInt(4) == 0) {
        shuffled.add(new Event(1, f));
        repeats++;
      }
      if (rnd.nextInt(4) == 0) {
        shuffled.add(new Event(2, f));
        busted++;
      } else {
        expectedNet += f.side() == Side.Buy ? f.qty() : -f.qty();
        canonical.add(new Event(0, f));
      }
    }
    Collections.shuffle(shuffled, rnd);
    canonical.sort(Comparator.comparingLong(e -> e.fill().transact()));

    final Path dir = Files.createTempDirectory("keeper-props");
    try {
      final PositionKeeper any = replay(dir.resolve("any.raj"), shuffled);
      final PositionKeeper ordered = replay(dir.resolve("ordered.raj"), canonical);
      final Position p = any.position(BOND);
      final Position q = ordered.position(BOND);
      assertEquals(expectedNet, p.netQuantity(), "net is the sum of live fills");
      assertEquals(n - busted, p.liveTrades());
      assertEquals(0, p.averagePrice().compareTo(q.averagePrice()), p.averagePrice() + " vs " + q.averagePrice());
      assertEquals(0, p.realisedPnl().compareTo(q.realisedPnl()), p.realisedPnl() + " vs " + q.realisedPnl());
      assertTrue(any.pendingBusts().isEmpty(), "every bust found its fill");
      assertEquals(busted, any.trades().stream().filter(t -> !t.isLive()).count());
      assertEquals(n, any.trades().size());
      assertEquals(repeats, any.audit(AuditKind.DUPLICATE_IGNORED).size());
      assertEquals(lateFills(shuffled), any.audit(AuditKind.LATE_FILL).size());
      assertEquals(0, ordered.audit(AuditKind.LATE_FILL).size());
      final List<Trade> trades = any.trades();
      for (int i = 1; i < trades.size(); i++) {
        assertTrue(trades.get(i - 1).transactNanos() < trades.get(i).transactNanos(), "transaction order");
      }
    } finally {
      try (var files = Files.list(dir)) {
        for (final Path f : files.toList()) {
          Files.delete(f);
        }
      }
      Files.delete(dir);
    }
  }

  /** Fills booked after a fill with a later transaction time; the first copy of a fill books. */
  private static int lateFills(final List<Event> arrivals) {
    long latest = Long.MIN_VALUE;
    int late = 0;
    final Set<Long> booked = new HashSet<>();
    for (final Event e : arrivals) {
      if (e.kind() == 2 || !booked.add(e.fill().execId())) {
        continue;
      }
      if (e.fill().transact() < latest) {
        late++;
      }
      latest = Math.max(latest, e.fill().transact());
    }
    return late;
  }

  private static PositionKeeper replay(final Path journal, final List<Event> events) {
    try (JournalScript s = new JournalScript(journal, 1 << 16, SyntheticDay.MIDNIGHT)) {
      long at = NINE;
      for (final Event e : events) {
        at += 1_000L;
        final Fill f = e.fill();
        if (e.kind() == 2) {
          s.bust(at, 1, TradeSource.Execution, f.execId(), 500 + f.execId(), 1001, BustReason.VenueError);
        } else {
          s.executionReport(at, 1, f.execId(), 500 + f.execId(), f.execId(), 1001, f.side(), OrdStatus.Filled,
              f.price(), f.qty(), 0, f.qty(), f.transact());
        }
      }
    }
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    new Replayer(new ManualClock(0)).replay(journal, keeper);
    return keeper;
  }
}
