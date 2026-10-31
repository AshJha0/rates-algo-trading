package io.ratesalgo.app.market;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.BookView;

/**
 * Price ladders per venue and instrument, rebuilt on the loop from BookUpdate messages (template
 * 2), with the composite top of book P3 reads ({@link BookSource}) and a per-instrument venue view
 * for each router ({@link #view}).
 *
 * <p>Each (instrument, venue, side) keeps up to {@link #MAX_LEVELS} price levels in fixed arrays,
 * best first. Add and Change set a level's quantity, Delete removes it, Clear empties both sides
 * of that venue's book (the first frame of a snapshot). A level beyond the capacity is ignored and
 * counted. The composite best bid is the highest bid over venues, its size the sum over venues at
 * that price. Allocation-free after construction; the loop thread owns it.
 */
@HotPath
public final class LoopBook implements BookSource {

  /** Levels kept per side per venue. */
  public static final int MAX_LEVELS = 16;

  private final InstrumentDirectory directory;
  private final int[] venueIds;
  private final long[][] price;
  private final long[][] quantity;
  private final int[] count;
  private final long[] updateNanos;
  private long overflow;

  /** A book for every instrument in {@code directory} on each of {@code venueIds}. */
  public LoopBook(final InstrumentDirectory directory, final int[] venueIds) {
    this.directory = directory;
    this.venueIds = venueIds.clone();
    final int ladders = directory.size() * venueIds.length * 2;
    this.price = new long[ladders][MAX_LEVELS];
    this.quantity = new long[ladders][MAX_LEVELS];
    this.count = new int[ladders];
    this.updateNanos = new long[directory.size()];
    java.util.Arrays.fill(updateNanos, FairValueSource.NO_VALUE);
  }

  private int venueSlot(final int venueId) {
    for (int v = 0; v < venueIds.length; v++) {
      if (venueIds[v] == venueId) {
        return v;
      }
    }
    return -1;
  }

  private int ladder(final int index, final int venueSlot, final boolean bid) {
    return ((index * venueIds.length) + venueSlot) * 2 + (bid ? 0 : 1);
  }

  /**
   * Applies one BookUpdate. Returns false (and changes nothing) for an unknown instrument or venue.
   */
  public boolean apply(final BookUpdateDecoder d) {
    final int index = directory.indexOf(d.instrumentId());
    final int v = venueSlot(d.venueId());
    if (index < 0 || v < 0) {
      return false;
    }
    updateNanos[index] = d.timestampNanos();
    final BookAction action = d.action();
    if (action == BookAction.Clear) {
      count[ladder(index, v, true)] = 0;
      count[ladder(index, v, false)] = 0;
      return true;
    }
    final boolean bid = d.side() == Side.Buy;
    final int l = ladder(index, v, bid);
    final long px = d.price().mantissa();
    if (action == BookAction.Delete || d.quantity() <= 0) {
      remove(l, px);
    } else {
      set(l, bid, px, d.quantity());
    }
    return true;
  }

  private void remove(final int l, final long px) {
    final long[] p = price[l];
    final long[] q = quantity[l];
    final int n = count[l];
    for (int i = 0; i < n; i++) {
      if (p[i] == px) {
        System.arraycopy(p, i + 1, p, i, n - i - 1);
        System.arraycopy(q, i + 1, q, i, n - i - 1);
        count[l] = n - 1;
        return;
      }
    }
  }

  private void set(final int l, final boolean bid, final long px, final long qty) {
    final long[] p = price[l];
    final long[] q = quantity[l];
    final int n = count[l];
    int i = 0;
    while (i < n && (bid ? p[i] > px : p[i] < px)) {
      i++;
    }
    if (i < n && p[i] == px) {
      q[i] = qty;
      return;
    }
    if (n == MAX_LEVELS) {
      if (i == MAX_LEVELS) {
        overflow++;
        return;
      }
      overflow++;
      System.arraycopy(p, i, p, i + 1, n - i - 1);
      System.arraycopy(q, i, q, i + 1, n - i - 1);
    } else {
      System.arraycopy(p, i, p, i + 1, n - i);
      System.arraycopy(q, i, q, i + 1, n - i);
      count[l] = n + 1;
    }
    p[i] = px;
    q[i] = qty;
  }

  /** Levels dropped because a side was full. */
  public long overflow() {
    return overflow;
  }

  private long best(final int instrumentId, final boolean bid) {
    final int index = directory.indexOf(instrumentId);
    if (index < 0) {
      return FairValueSource.NO_VALUE;
    }
    long best = FairValueSource.NO_VALUE;
    for (int v = 0; v < venueIds.length; v++) {
      final int l = ladder(index, v, bid);
      if (count[l] > 0) {
        final long px = price[l][0];
        if (best == FairValueSource.NO_VALUE || (bid ? px > best : px < best)) {
          best = px;
        }
      }
    }
    return best;
  }

  private long sizeAt(final int instrumentId, final boolean bid, final long px) {
    if (px == FairValueSource.NO_VALUE) {
      return 0L;
    }
    final int index = directory.indexOf(instrumentId);
    long size = 0L;
    for (int v = 0; v < venueIds.length; v++) {
      final int l = ladder(index, v, bid);
      if (count[l] > 0 && price[l][0] == px) {
        size += quantity[l][0];
      }
    }
    return size;
  }

  @Override
  public long bestBid(final int instrumentId) {
    return best(instrumentId, true);
  }

  @Override
  public long bestAsk(final int instrumentId) {
    return best(instrumentId, false);
  }

  @Override
  public long bidSize(final int instrumentId) {
    return sizeAt(instrumentId, true, bestBid(instrumentId));
  }

  @Override
  public long askSize(final int instrumentId) {
    return sizeAt(instrumentId, false, bestAsk(instrumentId));
  }

  @Override
  public long updateNanos(final int instrumentId) {
    final int index = directory.indexOf(instrumentId);
    return index < 0 ? FairValueSource.NO_VALUE : updateNanos[index];
  }

  /**
   * The router's view of one instrument across this book's venues. {@code tradeRatePerSecond} is
   * the configured rate: the book feed carries no trades (DECISIONS.md DR-R2W-08).
   */
  public BookView view(final int instrumentId, final double tradeRatePerSecond) {
    final int index = directory.indexOf(instrumentId);
    if (index < 0) {
      throw new IllegalArgumentException("unknown instrument " + instrumentId);
    }
    return new BookView() {
      @Override
      public long bestBid(final int venueId) {
        return top(venueId, true);
      }

      @Override
      public long bestAsk(final int venueId) {
        return top(venueId, false);
      }

      private long top(final int venueId, final boolean bid) {
        final int v = venueSlot(venueId);
        if (v < 0) {
          return 0L;
        }
        final int l = ladder(index, v, bid);
        return count[l] > 0 ? price[l][0] : 0L;
      }

      @Override
      public long depth(final int venueId, final Side side, final int level) {
        final int v = venueSlot(venueId);
        if (v < 0 || level < 0) {
          return 0L;
        }
        final int l = ladder(index, v, side == Side.Buy);
        return level < count[l] ? quantity[l][level] : 0L;
      }

      @Override
      public double tradeRatePerSecond(final int venueId) {
        return venueSlot(venueId) < 0 ? 0.0 : tradeRatePerSecond;
      }
    };
  }
}
