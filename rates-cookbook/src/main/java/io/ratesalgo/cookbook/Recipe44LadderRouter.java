package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.VenueKind;
import io.ratesalgo.router.BookView;
import io.ratesalgo.router.ExecutionModel;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.OrderGateway;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterParams;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.RouterTimeouts;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 44: the ladder router and the touch router on one book. */
public final class Recipe44LadderRouter {

  private static final long MILLION = RouterPresets.ONE_MILLION;
  private static final long TICK = RouterPresets.TICK_64TH;
  private static final long BID = 98_500_000_000L;

  /** Millions offered at the touch, one tick and two ticks above it, per venue. */
  private static final int[][] OFFERED = {{10, 20, 50}, {5, 20, 50}};

  private static final double[] TAKER_FEE = {0.0005, 0.0002};

  private Recipe44LadderRouter() {}

  /** A still book: the same ladder is shown to both routers. */
  private static final class StillBook implements BookView {

    @Override
    public long bestBid(final int venueId) {
      return BID;
    }

    @Override
    public long bestAsk(final int venueId) {
      return BID + TICK;
    }

    @Override
    public long depth(final int venueId, final Side side, final int level) {
      return level >= 0 && level < 3 ? OFFERED[venueId - 1][level] * MILLION : 0L;
    }

    @Override
    public long levelPrice(final int venueId, final Side side, final int level) {
      if (level < 0 || level >= 3) {
        return 0L;
      }
      return side == Side.Buy ? BID - level * TICK : BID + (1 + level) * TICK;
    }

    @Override
    public double tradeRatePerSecond(final int venueId) {
      return 0.0;
    }
  }

  /** One child order. */
  private record Child(int venue, TimeInForce tif, long price, long quantity) {}

  /** Keeps the child orders; nothing is filled. */
  private static final class Recorder implements OrderGateway {

    private final List<Child> children = new ArrayList<>();

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
      children.add(new Child(venueId, timeInForce, price, quantity));
    }

    @Override
    public void cancel(final int venueId, final long clOrdId) {
      throw new IllegalStateException("nothing is cancelled in this recipe");
    }
  }

  public static void main(final String[] args) {
    for (int v = 1; v <= 2; v++) {
      System.out.printf(
          Locale.ROOT, "venue %d offers %dm at the touch %.6f, %dm one tick up, %dm two ticks up; taker fee %.4f%n",
          v, OFFERED[v - 1][0], (BID + TICK) / 1e9, OFFERED[v - 1][1], OFFERED[v - 1][2], TAKER_FEE[v - 1]);
    }
    System.out.println("buy 30m now (urgency 1)");
    route("touch", RouterPresets.global(), null);
    route("ladder", RouterPresets.ladder(), ExecutionModel.defaults());
  }

  private static void route(final String name, final RouterParams params, final ExecutionModel model) {
    final VenueParams[] venues = new VenueParams[2];
    for (int v = 1; v <= 2; v++) {
      venues[v - 1] =
          new VenueParams(
              v, VenueKind.Clob, TICK, MILLION, MILLION, TAKER_FEE[v - 1], 0.0002, 250_000L, 0.2,
              30_000L * MILLION, 1.0, 5);
    }
    final Recorder gateway = new Recorder();
    final SmartOrderRouter router =
        new SmartOrderRouter(
            params, venues, new StillBook(), gateway, FrameSink.NONE, new ManualClock(), RandomSource.seeded(44L),
            4, 64, 1L, RouterTimeouts.DEFAULT, model);
    router.submit(1, 1001, Side.Buy, 30 * MILLION, 1.0, 0L);

    System.out.println(name + " router: " + gateway.children.size() + " children");
    double filled = 0.0;
    double cost = 0.0;
    for (final Child c : gateway.children) {
      // what the shown ladder gives this child, level by level, up to its limit price
      long left = c.quantity();
      for (int level = 0; level < 3; level++) {
        final long price = BID + (1 + level) * TICK;
        final long take = price <= c.price() ? Math.min(left, OFFERED[c.venue() - 1][level] * MILLION) : 0L;
        left -= take;
        filled += take;
        cost += take * ((price - BID) / (double) TICK - 0.5 + TAKER_FEE[c.venue() - 1] * 1e9 / TICK);
      }
      System.out.printf(
          Locale.ROOT, "  venue %d buy %.0fm %s, limit %d ticks above the touch, finds %.0fm%n",
          c.venue(), (double) c.quantity() / MILLION, c.tif(), (c.price() - BID - TICK) / TICK,
          (double) (c.quantity() - left) / MILLION);
    }
    System.out.printf(
        Locale.ROOT, "  walk of the shown book: %.0fm at %.3f ticks from the mid, fees included%n",
        filled / MILLION, cost / filled);
  }
}
