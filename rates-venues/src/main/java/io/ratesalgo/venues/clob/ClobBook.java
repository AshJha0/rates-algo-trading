package io.ratesalgo.venues.clob;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One instrument's order book: price-time priority, minimum size, icebergs, workup, self-match
 * prevention (API_SPEC.md Section 11.2 states the rules; this class implements them).
 *
 * <p>Workup: a trade at price P opens a workup at P for {@code workupNanos}. During the first
 * {@code exclusiveNanos} only the participants of the opening trade may trade; marketable orders
 * from others are held until the exclusive window ends. During workup every marketable order
 * trades only at P and any remainder rests at P; non-marketable orders rest normally. Each workup
 * trade restarts the timer when {@code extendOnTrade} is set. When the workup ends, held orders
 * are processed in arrival order.
 *
 * <p>Icebergs show {@code min(display, remaining)}; when the shown slice is filled, a new slice is
 * shown with new time priority at the back of its level.
 */
public final class ClobBook {

  /** Receives the book's events. */
  public interface Listener {
    /** A trade between an incoming and a resting order; {@code workup} is N, W or X. */
    void onFill(ClobOrder aggressor, ClobOrder passive, long price, long quantity, char workup);

    /** An order was cancelled by the book (self-match prevention, IOC remainder). */
    void onBookCancel(ClobOrder order, String reason);
  }

  private final int instrumentId;
  private final ClobConfig config;
  private final Listener listener;
  private final TreeMap<Long, ArrayDeque<ClobOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
  private final TreeMap<Long, ArrayDeque<ClobOrder>> asks = new TreeMap<>();
  private final List<ClobOrder> held = new ArrayList<>();
  private final Set<String> participants = new HashSet<>();
  private boolean workup;
  private long workupPrice;
  private long workupEnd;
  private long exclusiveEnd;

  /** Creates an empty book. */
  public ClobBook(final int instrumentId, final ClobConfig config, final Listener listener) {
    this.instrumentId = instrumentId;
    this.config = config;
    this.listener = listener;
  }

  /** Instrument id. */
  public int instrumentId() {
    return instrumentId;
  }

  /** Reason the order breaks a size or tick rule, or null if it is valid. */
  public String validate(final long price, final long quantity, final long display) {
    if (quantity < config.minQty() || quantity % config.minQty() != 0) {
      return "quantity must be a positive multiple of the minimum size " + config.minQty();
    }
    if (price <= 0 || price % config.tickSize() != 0) {
      return "price not on the tick grid";
    }
    if (display != 0 && (display < config.minQty() || display % config.minQty() != 0 || display > quantity)) {
      return "display quantity must be a multiple of the minimum size and at most the order quantity";
    }
    return null;
  }

  /** True while a workup is running. */
  public boolean inWorkup() {
    return workup;
  }

  /** Workup price, meaningful while {@link #inWorkup()}. */
  public long workupPrice() {
    return workupPrice;
  }

  /** Monotonic time the workup ends. */
  public long workupEndNanos() {
    return workupEnd;
  }

  /** Orders waiting for the exclusive window or the workup to end. */
  public List<ClobOrder> held() {
    return List.copyOf(held);
  }

  private char state(final long now) {
    if (!workup) {
      return 'N';
    }
    return now < exclusiveEnd ? 'X' : 'W';
  }

  private static boolean marketable(final ClobOrder o, final long levelPrice) {
    return o.buy ? o.price >= levelPrice : o.price <= levelPrice;
  }

  /** Adds an incoming order and matches it. The order must have passed {@link #validate}. */
  public void submit(final ClobOrder o, final long now) {
    onTime(now);
    if (workup) {
      if (marketable(o, workupPrice)) {
        if (now < exclusiveEnd && !participants.contains(o.owner)) {
          held.add(o);
          return;
        }
        o.price = workupPrice;
        match(o, now, true);
        if (!o.done) {
          rest(o);
        }
        return;
      }
      if (wouldCross(o)) {
        held.add(o);
        return;
      }
      rest(o);
      return;
    }
    participants.clear();
    final boolean traded = match(o, now, false);
    if (!o.done) {
      if (o.immediateOrCancel) {
        o.done = true;
        o.remaining = 0;
        listener.onBookCancel(o, "immediate-or-cancel remainder");
      } else {
        rest(o);
      }
    }
    if (traded && config.workupNanos() > 0 && !workup) {
      workup = true;
      exclusiveEnd = now + config.exclusiveNanos();
      workupEnd = now + config.workupNanos();
    }
  }

  private boolean wouldCross(final ClobOrder o) {
    final TreeMap<Long, ArrayDeque<ClobOrder>> opp = o.buy ? asks : bids;
    return !opp.isEmpty() && marketable(o, opp.firstKey());
  }

  private boolean match(final ClobOrder o, final long now, final boolean workupOnly) {
    final TreeMap<Long, ArrayDeque<ClobOrder>> opp = o.buy ? asks : bids;
    boolean traded = false;
    long lastPrice = 0;
    while (o.remaining > 0 && !opp.isEmpty()) {
      final long levelPrice = opp.firstKey();
      if (!marketable(o, levelPrice) || (workupOnly && levelPrice != workupPrice)) {
        break;
      }
      final ArrayDeque<ClobOrder> queue = opp.get(levelPrice);
      final ClobOrder p = queue.peekFirst();
      if (p.owner.equals(o.owner)) {
        if (config.selfMatch() == SelfMatch.CANCEL_RESTING) {
          queue.pollFirst();
          p.done = true;
          p.remaining = 0;
          p.visible = 0;
          if (queue.isEmpty()) {
            opp.remove(levelPrice);
          }
          listener.onBookCancel(p, "self-match prevention");
          continue;
        }
        o.done = true;
        o.remaining = 0;
        listener.onBookCancel(o, "self-match prevention");
        break;
      }
      final long qty = Math.min(o.remaining, p.visible);
      p.visible -= qty;
      p.remaining -= qty;
      p.cumQty += qty;
      o.remaining -= qty;
      o.cumQty += qty;
      if (p.remaining == 0) {
        p.done = true;
      }
      if (o.remaining == 0) {
        o.done = true;
      }
      final char st = state(now);
      participants.add(o.owner);
      participants.add(p.owner);
      listener.onFill(o, p, levelPrice, qty, st);
      traded = true;
      lastPrice = levelPrice;
      if (p.visible == 0) {
        queue.pollFirst();
        if (p.remaining > 0) {
          p.visible = Math.min(p.display, p.remaining);
          queue.addLast(p);
        }
      }
      if (queue.isEmpty()) {
        opp.remove(levelPrice);
      }
    }
    if (traded) {
      if (workup) {
        if (config.extendOnTrade()) {
          workupEnd = Math.max(workupEnd, now + config.workupNanos());
        }
      } else {
        workupPrice = lastPrice;
      }
    }
    return traded;
  }

  private void rest(final ClobOrder o) {
    o.visible = o.display > 0 ? Math.min(o.display, o.remaining) : o.remaining;
    (o.buy ? bids : asks).computeIfAbsent(o.price, k -> new ArrayDeque<>()).addLast(o);
  }

  /** Ends the workup if its time has come and processes held orders. */
  public void onTime(final long now) {
    if (workup && now >= exclusiveEnd && !held.isEmpty()) {
      releaseHeld(now, true);
    }
    if (workup && now >= workupEnd) {
      workup = false;
      participants.clear();
      releaseHeld(now, false);
    }
  }

  private void releaseHeld(final long now, final boolean exclusiveOnly) {
    final List<ClobOrder> waiting = new ArrayList<>(held);
    held.clear();
    for (final ClobOrder o : waiting) {
      if (exclusiveOnly && workup && !marketable(o, workupPrice) && wouldCross(o)) {
        held.add(o);
        continue;
      }
      submit(o, now);
    }
  }

  /** Removes an order; returns false if it was not on the book or held. */
  public boolean cancel(final ClobOrder o) {
    if (held.remove(o)) {
      o.done = true;
      o.remaining = 0;
      return true;
    }
    final TreeMap<Long, ArrayDeque<ClobOrder>> side = o.buy ? bids : asks;
    final ArrayDeque<ClobOrder> queue = side.get(o.price);
    if (queue == null || !queue.remove(o)) {
      return false;
    }
    if (queue.isEmpty()) {
      side.remove(o.price);
    }
    o.done = true;
    o.remaining = 0;
    o.visible = 0;
    return true;
  }

  /**
   * Changes price or total quantity. A quantity reduction at the same price keeps priority;
   * anything else loses it and the order is matched again. Returns false if the order is not
   * live.
   */
  public boolean replace(final ClobOrder o, final long newPrice, final long newQuantity, final long now) {
    if (o.done) {
      return false;
    }
    final long newRemaining = newQuantity - o.cumQty;
    if (newRemaining <= 0) {
      return cancel(o);
    }
    if (newPrice == o.price && newRemaining <= o.remaining && !held.contains(o)) {
      o.quantity = newQuantity;
      o.remaining = newRemaining;
      o.visible = o.display > 0 ? Math.min(o.visible, o.remaining) : o.remaining;
      return true;
    }
    if (!cancel(o)) {
      return false;
    }
    o.done = false;
    o.price = newPrice;
    o.quantity = newQuantity;
    o.remaining = newRemaining;
    submit(o, now);
    return true;
  }

  /** Visible quantity and order count per price, best first, up to {@code depth} levels. */
  public List<long[]> levels(final boolean bidSide, final int depth) {
    final List<long[]> out = new ArrayList<>();
    for (final Map.Entry<Long, ArrayDeque<ClobOrder>> e : (bidSide ? bids : asks).entrySet()) {
      if (out.size() == depth) {
        break;
      }
      long qty = 0;
      for (final ClobOrder o : e.getValue()) {
        qty += o.visible;
      }
      out.add(new long[] {e.getKey(), qty, e.getValue().size()});
    }
    return out;
  }

  /** Every live order on the book and held, in no particular order. */
  public List<ClobOrder> liveOrders() {
    final List<ClobOrder> out = new ArrayList<>(held);
    for (final ArrayDeque<ClobOrder> q : bids.values()) {
      out.addAll(q);
    }
    for (final ArrayDeque<ClobOrder> q : asks.values()) {
      out.addAll(q);
    }
    return out;
  }
}
