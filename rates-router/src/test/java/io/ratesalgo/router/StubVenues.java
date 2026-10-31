package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import java.util.ArrayList;
import java.util.List;

/** A hand-driven book and a recording gateway for deterministic router tests. */
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
  final List<Order> orders = new ArrayList<>();
  final List<long[]> cancels = new ArrayList<>();

  StubVenues(final int... venueIds) {
    ids = venueIds.clone();
    final int n = ids.length;
    bid = new long[n];
    ask = new long[n];
    bidDepth = new long[n];
    askDepth = new long[n];
    rate = new double[n];
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
    if (level != 0) {
      return 0;
    }
    final int i = index(venueId);
    return side == Side.Buy ? bidDepth[i] : askDepth[i];
  }

  @Override
  public double tradeRatePerSecond(final int venueId) {
    return rate[index(venueId)];
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
