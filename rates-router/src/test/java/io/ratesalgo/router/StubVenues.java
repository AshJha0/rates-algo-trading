package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-driven book and a recording gateway for deterministic router tests. Level 0 is the
 * touch arrays; deeper levels, for the ladder model, are set with {@link #level}.
 */
final class StubVenues implements BookView, OrderGateway {

  /** A child order the router sent. */
  record Order(
      int venue, long id, long parent, Side side, TimeInForce tif, long price, long qty) {}

  final int[] ids;
  final long[] bid;
  final long[] ask;
  final long[] bidDepth;
  final long[] askDepth;
  final double[] rate;
  final long[] updated;
  final List<Order> orders = new ArrayList<>();
  final List<long[]> cancels = new ArrayList<>();
  // deeper levels: [venue][side 0 bid, 1 ask][level - 1]
  final long[][][] deepPrice;
  final long[][][] deepQty;

  StubVenues(final int... venueIds) {
    ids = venueIds.clone();
    final int n = ids.length;
    bid = new long[n];
    ask = new long[n];
    bidDepth = new long[n];
    askDepth = new long[n];
    rate = new double[n];
    updated = new long[n];
    java.util.Arrays.fill(updated, BookView.AGE_UNKNOWN);
    deepPrice = new long[n][2][CostTable.MAX_LEVELS];
    deepQty = new long[n][2][CostTable.MAX_LEVELS];
  }

  /** Sets level {@code k >= 1} of a side. */
  void level(final int venue, final Side side, final int k, final long price, final long qty) {
    final int i = index(venue);
    deepPrice[i][side == Side.Buy ? 0 : 1][k - 1] = price;
    deepQty[i][side == Side.Buy ? 0 : 1][k - 1] = qty;
  }

  int index(final int venue) {
    for (int i = 0; i < ids.length; i++) {
      if (ids[i] == venue) {
        return i;
      }
    }
    throw new IllegalArgumentException("venue " + venue);
  }

  @Override
  public long bestBid(final int venueId) {
    return bid[index(venueId)];
  }

  @Override
  public long bestAsk(final int venueId) {
    return ask[index(venueId)];
  }

  @Override
  public long depth(final int venueId, final Side side, final int level) {
    final int i = index(venueId);
    if (level != 0) {
      return level < 0 || level > CostTable.MAX_LEVELS ? 0 : deepQty[i][side == Side.Buy ? 0 : 1][level - 1];
    }
    return side == Side.Buy ? bidDepth[i] : askDepth[i];
  }

  @Override
  public long levelPrice(final int venueId, final Side side, final int level) {
    final int i = index(venueId);
    if (level == 0) {
      return side == Side.Buy ? bid[i] : ask[i];
    }
    return level < 0 || level > CostTable.MAX_LEVELS ? 0 : deepPrice[i][side == Side.Buy ? 0 : 1][level - 1];
  }

  @Override
  public double tradeRatePerSecond(final int venueId) {
    return rate[index(venueId)];
  }

  @Override
  public long lastUpdateNanos(final int venueId) {
    return updated[index(venueId)];
  }

  @Override
  public void newOrder(
      final int venueId,
      final long clOrdId,
      final long parentId,
      final int instrumentId,
      final Side side,
      final OrdType ordType,
      final TimeInForce timeInForce,
      final long price,
      final long quantity) {
    orders.add(new Order(venueId, clOrdId, parentId, side, timeInForce, price, quantity));
  }

  @Override
  public void cancel(final int venueId, final long clOrdId) {
    cancels.add(new long[] {venueId, clOrdId});
  }

  Order last() {
    return orders.get(orders.size() - 1);
  }
}
