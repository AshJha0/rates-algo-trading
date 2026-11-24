package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.ExecutionListener;
import io.ratesalgo.router.ExecutionModel;
import io.ratesalgo.router.RouterParams;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.VenueParams;
import io.ratesalgo.router.sim.ImpactConfig;
import io.ratesalgo.router.sim.ImpactMarket;
import io.ratesalgo.router.sim.SimulatedMarket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The router benchmark (docs/research/ROUTER_BENCHMARK.md, DECISIONS.md DR-R6-R05): the router
 * and real alternatives worked over the same seeded days of an {@link ImpactMarket}, with the
 * implementation shortfall of every parent against the consolidated mid at its arrival.
 *
 * <p>Every strategy sees the same days: the same market seed, so the same efficient-price path
 * and other participants' orders, and the same parent sides, sizes and gaps; they differ only by
 * what their own orders did. Each strategy's orders pass through a check that every fill is
 * within its order's limit and records how far past the far touch each aggressive order was
 * priced when sent.
 */
public final class RouterBenchmark {

  private static final long START_EPOCH = 1_767_225_600_000_000_000L;
  private static final long DAY_NANOS = 86_400_000_000_000L;

  private RouterBenchmark() {}

  /** The strategies of the benchmark, router first, for a scenario. */
  public static List<ExecutionStrategy> strategies(final RouterBenchmarkScenario sc) {
    final ImpactConfig m = sc.market();
    final int nv = m.venueCount();
    final int[] ids = new int[nv];
    final double[] fees = new double[nv];
    for (int i = 0; i < nv; i++) {
      ids[i] = m.venue(i).venueId();
      fees[i] = m.venue(i).takerFee();
    }
    final RouterParams ladder = RouterPresets.ladder();
    final VenueParams[] declared = m.declaredVenues(0.25, 5);
    // the router's nominal schedule at this urgency, which baselines (b) and (c) follow
    final double patience = 1.0 - sc.urgency();
    final int slices = 1 + (int) Math.round((ladder.maxSlices() - 1) * patience);
    final long horizon = Math.round(ladder.scheduleHorizonNanos() * patience);
    final long passive = Math.round(ladder.passiveHorizonMaxNanos() * patience);
    final long retry = ladder.retryDelayNanos();
    final long lot = m.lotSize();
    final int levels = m.levels();
    final List<ExecutionStrategy> out = new ArrayList<>();
    out.add(new RouterStrategy("router (ladder model)", ladder, declared, sc.urgency(), 10, ExecutionModel.defaults()));
    out.add(SweepBaseline.onArrival("(a) sweep on arrival", ids, fees, retry, lot, levels));
    out.add(
        SweepBaseline.schedule(
            "(b) schedule, best venue", new int[] {ids[0]}, new double[] {fees[0]}, slices,
            horizon / slices, retry, lot, levels));
    out.add(SweepBaseline.schedule("(c) TWAP across venues", ids, fees, slices, horizon / slices, retry, lot, levels));
    out.add(new PassiveDeadlineBaseline("(d) passive, deadline", ids, fees, horizon + passive, retry, lot, levels));
    out.add(AggressiveBaseline.singleVenue(ids[0], 8, retry, lot, m.tickMantissa()));
    out.add(AggressiveBaseline.evenSplit(ids, 8, retry, lot, m.tickMantissa()));
    out.add(new RouterStrategy("router (touch model v1)", RouterPresets.global(), declared, sc.urgency(), 10, null));
    return out;
  }

  /** Number of real baselines after the router in {@link #strategies}: (a) to (d). */
  public static final int REAL_BASELINES = 4;

  /**
   * Runs the scenario.
   *
   * @throws IllegalStateException if a parent does not complete in time
   */
  public static RouterBenchmarkResult run(
      final RouterBenchmarkScenario sc, final List<ExecutionStrategy> strategies) {
    final int s = strategies.size();
    final int parents = sc.days() * sc.parentsPerDay();
    final String[] names = new String[s];
    final double[][] ticks = new double[s][parents];
    final double[][] bps = new double[s][parents];
    final double[][] seconds = new double[s][parents];
    final double[][] fill = new double[s][parents];
    final int[] day = new int[parents];
    final long[] violations = new long[s];
    final long[] maxPast = new long[s];
    final long[] selfCrosses = new long[s];
    final long[] orders = new long[s];
    for (int k = 0; k < s; k++) {
      final ExecutionStrategy strategy = strategies.get(k);
      names[k] = strategy.name();
      int p = 0;
      for (int d = 0; d < sc.days(); d++) {
        final long seed = sc.seed() + d;
        final ManualClock clock = new ManualClock(START_EPOCH + d * DAY_NANOS);
        final ImpactMarket market = new ImpactMarket(sc.market(), seed, clock);
        final CheckedMarket checked = new CheckedMarket(market, sc.market().tickMantissa());
        strategy.startDay(checked, clock, seed);
        final SplittableRandom parentStream = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        final long step = sc.market().stepNanos();
        final long lot = sc.market().lotSize();
        final double tickPoints = sc.market().tickMantissa() / 1e9;
        for (int q = 0; q < sc.parentsPerDay(); q++, p++) {
          final Side side = parentStream.nextBoolean() ? Side.Buy : Side.Sell;
          final long lots = sc.minLots() + parentStream.nextLong(sc.maxLots() - sc.minLots() + 1);
          for (long t = 0; t < sc.gapNanos(); t += step) {
            advance(clock, checked, strategy, step);
          }
          final long parentId = d * 1_000L + q + 1;
          final double arrival = market.referenceMid() / 1e9;
          final long qty = lots * lot;
          strategy.submit(parentId, side, qty);
          long elapsed = 0;
          while (!strategy.isDone(parentId)) {
            if (elapsed > sc.maxParentNanos()) {
              throw new IllegalStateException(
                  names[k] + ": parent " + parentId + " incomplete after " + elapsed + " ns, filled "
                      + market.parentFilled(parentId) + " of " + qty);
            }
            advance(clock, checked, strategy, step);
            elapsed += step;
          }
          final long filled = market.parentFilled(parentId);
          final double average = market.parentNotional(parentId) / filled;
          final double fees = market.parentFees(parentId) / filled;
          final double sign = side == Side.Buy ? 1.0 : -1.0;
          final double shortfall = sign * (average - arrival) + fees;
          ticks[k][p] = shortfall / tickPoints;
          bps[k][p] = shortfall / arrival * 1e4;
          seconds[k][p] = elapsed / 1e9;
          fill[k][p] = filled / (double) qty;
          day[p] = d;
        }
        // let the day's last reports arrive before counting
        for (int t = 0; t < 20; t++) {
          advance(clock, checked, strategy, step);
        }
        violations[k] += checked.violations;
        maxPast[k] = Math.max(maxPast[k], checked.maxTicksPastTouch);
        selfCrosses[k] += market.selfCrosses();
        orders[k] += checked.orders;
      }
    }
    return new RouterBenchmarkResult(
        sc, names, ticks, bps, seconds, fill, day, violations, maxPast, selfCrosses, orders);
  }

  private static void advance(
      final ManualClock clock, final SimulatedMarket market, final ExecutionStrategy strategy,
      final long step) {
    clock.advance(step);
    market.step();
    market.drain(strategy);
    strategy.poll();
  }

  /**
   * A pass-through market that checks every fill against its order's limit and records, for
   * aggressive orders, how many ticks past the far touch their limit was when sent.
   */
  static final class CheckedMarket implements SimulatedMarket, ExecutionListener {
    private final ImpactMarket inner;
    private final long tick;
    private final Map<Long, long[]> limits = new HashMap<>();
    private ExecutionListener target;
    long violations;
    long maxTicksPastTouch;
    long orders;

    CheckedMarket(final ImpactMarket inner, final long tick) {
      this.inner = inner;
      this.tick = tick;
    }

    @Override
    public void newOrder(
        final int venueId, final long clOrdId, final long parentId, final int instrumentId,
        final Side side, final OrdType ordType, final TimeInForce timeInForce, final long price,
        final long quantity) {
      orders++;
      final boolean buy = side == Side.Buy;
      limits.put(clOrdId, new long[] {buy ? 1 : -1, price});
      if (timeInForce != TimeInForce.Day) {
        final long far = buy ? inner.bestAsk(venueId) : inner.bestBid(venueId);
        if (far > 0) {
          maxTicksPastTouch = Math.max(maxTicksPastTouch, (buy ? price - far : far - price) / tick);
        }
      }
      inner.newOrder(venueId, clOrdId, parentId, instrumentId, side, ordType, timeInForce, price, quantity);
    }

    @Override
    public void onExecutionReport(
        final int venueId, final long clOrdId, final long execId, final OrdStatus ordStatus,
        final long lastPrice, final long lastQuantity, final long leavesQuantity,
        final long cumQuantity, final long transactNanos) {
      final long[] l = limits.get(clOrdId);
      if (l != null && lastQuantity > 0 && (l[0] > 0 ? lastPrice > l[1] : lastPrice < l[1])) {
        violations++;
      }
      target.onExecutionReport(
          venueId, clOrdId, execId, ordStatus, lastPrice, lastQuantity, leavesQuantity, cumQuantity,
          transactNanos);
    }

    @Override
    public void onCancelReject(final int venueId, final long clOrdId) {
      target.onCancelReject(venueId, clOrdId);
    }

    @Override
    public void onBookUpdate(final int venueId) {
      target.onBookUpdate(venueId);
    }

    @Override
    public void onTrade(final int venueId, final Side aggressorSide, final long price, final long quantity) {
      target.onTrade(venueId, aggressorSide, price, quantity);
    }

    @Override
    public void drain(final ExecutionListener listener) {
      target = listener;
      inner.drain(this);
    }

    @Override
    public void cancel(final int venueId, final long clOrdId) {
      inner.cancel(venueId, clOrdId);
    }

    @Override
    public void step() {
      inner.step();
    }

    @Override
    public long referenceMid() {
      return inner.referenceMid();
    }

    @Override
    public long parentFilled(final long parentId) {
      return inner.parentFilled(parentId);
    }

    @Override
    public double parentNotional(final long parentId) {
      return inner.parentNotional(parentId);
    }

    @Override
    public double parentFees(final long parentId) {
      return inner.parentFees(parentId);
    }

    @Override
    public long selfCrosses() {
      return inner.selfCrosses();
    }

    @Override
    public int liveOrders() {
      return inner.liveOrders();
    }

    @Override
    public int[] venueIds() {
      return inner.venueIds();
    }

    @Override
    public long bestBid(final int venueId) {
      return inner.bestBid(venueId);
    }

    @Override
    public long bestAsk(final int venueId) {
      return inner.bestAsk(venueId);
    }

    @Override
    public long depth(final int venueId, final Side side, final int level) {
      return inner.depth(venueId, side, level);
    }

    @Override
    public long levelPrice(final int venueId, final Side side, final int level) {
      return inner.levelPrice(venueId, side, level);
    }

    @Override
    public double tradeRatePerSecond(final int venueId) {
      return inner.tradeRatePerSecond(venueId);
    }
  }

  /** Formats a number with one decimal, the precision the report uses. */
  static String one(final double x) {
    return String.format(Locale.ROOT, "%.1f", x);
  }
}
