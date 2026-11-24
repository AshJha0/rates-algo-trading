package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Execution-id dedupe against an unbounded reference (a HashSet of every execution booked), with
 * children opening and leaving the book, plain duplicates, whole ranges resent as after a
 * reconnect, reordered and late fills, and cancels whose fills arrive after the confirmation.
 *
 * <p>Properties: a fill the reference has booked is never booked again; a fill the reference has
 * not booked is booked unless every entry is in use, when it is refused and nothing else; a
 * report for a child that has left the book is a fill already booked; with enough entries every
 * fill the venue made is booked exactly once; and a second book fed the same calls makes the same
 * decisions, as a book rebuilt by replay must.
 */
class ChildOrderBookDedupePropertiesTest {

  private static final long LOT = 100L;
  private static final long PX = 99_000_000_000L;
  private static final int SLOTS = 12;
  private static final int MAX_LOTS = 10;
  private static final int VENUES = 3;
  private static final int MAX_ORDERS = 400;

  /** One report as the venue sent it. */
  private record Report(int venue, long id, long execId, OrdStatus status, long last, long cum) {}

  /** One call on the book, kept so that a second book can be fed the same calls. */
  private sealed interface Op permits Open, Cancel, Deliver, Release {}

  private record Open(long id, int venue, long qty) implements Op {}

  private record Cancel(long id, boolean replace) implements Op {}

  private record Deliver(Report report) implements Op {}

  private record Release(long id) implements Op {}

  private static long key(final Report r) {
    return ((long) r.venue() << 43) | r.execId();
  }

  @Property(tries = 300)
  void aFillIsBookedOnceAndNeverDroppedAsADuplicate(
      @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) final long seed,
      @ForAll @IntRange(min = 1, max = 2 * SLOTS * MAX_LOTS) final int entries,
      @ForAll @IntRange(min = 50, max = 600) final int steps) {
    final Sim sim = new Sim(seed, entries);
    sim.run(steps);
    final boolean ample = entries >= SLOTS * MAX_LOTS;
    if (ample) {
      assertEquals(0, sim.book.refusedFills(), "refused with an entry for every possible fill");
    }
    for (int o = 0; o < sim.orders; o++) {
      assertTrue(sim.bookedQty[o] <= sim.venueCum[o], "booked more than the venue filled");
      if (ample) {
        assertEquals(sim.venueCum[o], sim.bookedQty[o], "a fill the venue made was never booked");
      }
    }

    // a book rebuilt from the same calls makes the same decisions
    final ChildOrderBook again = new ChildOrderBook(SLOTS, entries, new ManualClock(), FrameSink.NONE);
    final List<Integer> replayed = new ArrayList<>();
    for (final Op op : sim.ops) {
      replayed.add(apply(again, op));
    }
    assertEquals(sim.results, replayed);
    assertEquals(sim.book.heldExecutions(), again.heldExecutions());
    assertEquals(sim.book.duplicates(), again.duplicates());
    assertEquals(sim.book.refusedFills(), again.refusedFills());
  }

  /**
   * The id table on its own, against a set per slot, over a small key space so that probe runs
   * collide, wrap round the table and are cut by removals at every position.
   */
  @Property(tries = 300)
  void theIdTableAgreesWithASetPerSlot(
      @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) final long seed,
      @ForAll @IntRange(min = 1, max = 40) final int entries) {
    final Random rnd = new Random(seed);
    final int slots = 6;
    final ChildExecIds ids = new ChildExecIds(slots, entries);
    final List<Set<Long>> ref = new ArrayList<>();
    for (int s = 0; s < slots; s++) {
      ref.add(new HashSet<>());
    }
    final long[] universe = new long[96];
    for (int i = 0; i < universe.length; i++) {
      universe[i] = i < 64 ? i : ((long) (i - 64) << 43) | 5L;
    }
    int total = 0;
    for (int step = 0; step < 400; step++) {
      final int slot = rnd.nextInt(slots);
      if (rnd.nextInt(4) == 0) {
        total -= ref.get(slot).size();
        ref.get(slot).clear();
        ids.clear(slot);
      } else {
        final long k = universe[rnd.nextInt(universe.length)];
        boolean held = false;
        for (final Set<Long> set : ref) {
          held |= set.contains(k);
        }
        assertEquals(held, ids.contains(k));
        if (!held) {
          final boolean added = ids.add(slot, k);
          assertEquals(total < entries, added);
          if (added) {
            ref.get(slot).add(k);
            total++;
          }
        }
      }
      assertEquals(total, ids.size());
      for (final long k : universe) {
        boolean held = false;
        for (final Set<Long> set : ref) {
          held |= set.contains(k);
        }
        assertEquals(held, ids.contains(k), "key " + k);
      }
    }
  }

  private static int apply(final ChildOrderBook book, final Op op) {
    return switch (op) {
      case Open o -> book.open(o.id(), 7, o.venue(), Side.Buy, PX, o.qty(), false);
      case Cancel c -> book.requestCancel(c.id(), c.replace()) ? 1 : 0;
      case Deliver d -> {
        final Report r = d.report();
        yield book.onExecutionReport(r.venue(), r.id(), r.execId(), r.status(), PX, r.last(), r.cum());
      }
      case Release r -> book.release(book.slotOf(r.id())) ? 1 : 0;
    };
  }

  /** A venue and the router's book, with the reference model beside them. */
  private static final class Sim {

    final Random rnd;
    final ChildOrderBook book;
    final List<Op> ops = new ArrayList<>();
    final List<Integer> results = new ArrayList<>();
    final Set<Long> booked = new HashSet<>();
    final List<List<Report>> sent = new ArrayList<>();
    final List<Report> pending = new ArrayList<>();
    final long[] execIds = new long[VENUES + 1];
    final long[] ordered = new long[MAX_ORDERS];
    final long[] venueCum = new long[MAX_ORDERS];
    final long[] bookedQty = new long[MAX_ORDERS];
    final int[] venueOf = new int[MAX_ORDERS];
    final boolean[] venueDone = new boolean[MAX_ORDERS];
    int orders;
    int held;

    Sim(final long seed, final int entries) {
      rnd = new Random(seed);
      book = new ChildOrderBook(SLOTS, entries, new ManualClock(), FrameSink.NONE);
      for (int v = 0; v <= VENUES; v++) {
        sent.add(new ArrayList<>());
      }
    }

    void run(final int steps) {
      for (int i = 0; i < steps; i++) {
        final int a = rnd.nextInt(100);
        if (a < 12) {
          open();
        } else if (a < 45) {
          venueFill();
        } else if (a < 55) {
          cancel();
        } else if (a < 72) {
          if (!pending.isEmpty()) {
            // any pending report may overtake the others
            deliver(pending.remove(rnd.nextInt(pending.size())));
          }
        } else if (a < 85) {
          duplicate();
        } else if (a < 90) {
          resend(1 + rnd.nextInt(VENUES));
        } else if (!pending.isEmpty()) {
          deliver(pending.remove(0));
        }
      }
      while (!pending.isEmpty()) {
        deliver(pending.remove(0));
      }
      // a full resend from every venue, twice: a fill refused at the bound gets its chance once
      // other children have left the book
      for (int pass = 0; pass < 2; pass++) {
        for (int v = 1; v <= VENUES; v++) {
          resend(v);
        }
      }
    }

    private int liveVenueOrder() {
      if (orders == 0) {
        return -1;
      }
      final int start = rnd.nextInt(orders);
      for (int k = 0; k < orders; k++) {
        final int o = (start + k) % orders;
        if (!venueDone[o]) {
          return o;
        }
      }
      return -1;
    }

    private void open() {
      if (held == SLOTS || orders == MAX_ORDERS) {
        return;
      }
      final int o = orders++;
      venueOf[o] = 1 + rnd.nextInt(VENUES);
      ordered[o] = LOT * (1 + rnd.nextInt(MAX_LOTS));
      record(new Open(id(o), venueOf[o], ordered[o]));
      held++;
      if (rnd.nextInt(10) == 0) {
        venueDone[o] = true;
        send(o, OrdStatus.Rejected, 0);
      } else if (rnd.nextBoolean()) {
        send(o, OrdStatus.New, 0);
      }
    }

    private void venueFill() {
      final int o = liveVenueOrder();
      if (o < 0) {
        return;
      }
      final long q = Math.min(ordered[o] - venueCum[o], LOT * (1 + rnd.nextInt(3)));
      venueCum[o] += q;
      if (venueCum[o] == ordered[o]) {
        venueDone[o] = true;
        send(o, OrdStatus.Filled, q);
      } else {
        send(o, OrdStatus.PartiallyFilled, q);
      }
    }

    private void cancel() {
      final int o = liveVenueOrder();
      if (o < 0) {
        return;
      }
      record(new Cancel(id(o), rnd.nextBoolean()));
      if (rnd.nextInt(4) != 0) {
        // the venue confirms with what it had filled; those fills may still be on the way
        venueDone[o] = true;
        send(o, OrdStatus.Cancelled, 0);
      }
    }

    private void duplicate() {
      final List<Report> list = sent.get(1 + rnd.nextInt(VENUES));
      if (!list.isEmpty()) {
        deliver(list.get(rnd.nextInt(list.size())));
      }
    }

    private void resend(final int venue) {
      final List<Report> list = sent.get(venue);
      if (list.isEmpty()) {
        return;
      }
      // everything from some point on, as a venue does after a logon resuming the sequence
      final int from = rnd.nextInt(list.size());
      final int to = list.size();
      for (int i = from; i < to; i++) {
        deliver(list.get(i));
      }
    }

    private void send(final int o, final OrdStatus status, final long last) {
      final int v = venueOf[o];
      final Report r = new Report(v, id(o), ++execIds[v], status, last, venueCum[o]);
      sent.get(v).add(r);
      if (rnd.nextBoolean()) {
        deliver(r);
      } else {
        pending.add(r);
      }
    }

    private void deliver(final Report r) {
      final int o = (int) (r.id() - 1_000L);
      final boolean known = book.slotOf(r.id()) >= 0;
      final boolean full = book.heldExecutions() == book.executionCapacity();
      final long k = key(r);
      final int f = record(new Deliver(r));
      assertTrue(book.heldExecutions() <= book.executionCapacity());
      if (!known) {
        assertEquals(ChildOrderBook.UNKNOWN, f);
        if (r.last() > 0) {
          assertTrue(booked.contains(k), "a child left the book with a fill unbooked");
        }
        return;
      }
      if (r.last() > 0) {
        if (booked.contains(k)) {
          assertEquals(ChildOrderBook.DUPLICATE, f & (ChildOrderBook.DUPLICATE | ChildOrderBook.FILL));
        } else if ((f & ChildOrderBook.FILL) != 0) {
          assertEquals(r.last(), book.lastFill());
          booked.add(k);
          bookedQty[o] += r.last();
        } else {
          // the only reason a new fill may go unbooked, and never as a duplicate
          assertEquals(ChildOrderBook.REFUSED | ChildOrderBook.ANOMALY, f & 0x64);
          assertTrue(full, "refused below the bound");
        }
      }
      final int s = book.slotOf(r.id());
      if (ChildOrderBook.isTerminal(book.state(s)) && book.leaves(s) == 0) {
        assertEquals(1, record(new Release(r.id())));
        held--;
      }
    }

    private int record(final Op op) {
      final int result = apply(book, op);
      ops.add(op);
      results.add(result);
      return result;
    }

    private static long id(final int o) {
      return 1_000L + o;
    }
  }
}
