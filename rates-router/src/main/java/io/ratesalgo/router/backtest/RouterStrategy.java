package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.ExecutionModel;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterParams;
import io.ratesalgo.router.RouterTimeouts;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueParams;
import io.ratesalgo.router.sim.SimulatedMarket;

/**
 * The smart order router as a back-test strategy: the touch model without an {@link
 * ExecutionModel}, the ladder model with one. A fresh router each day, so nothing it learned
 * carries from one synthetic day to the next.
 */
public final class RouterStrategy implements ExecutionStrategy {

  private final String name;
  private final RouterParams params;
  private final VenueParams[] venues;
  private final double urgency;
  private final int instrumentId;
  private final ExecutionModel model;
  private SmartOrderRouter router;

  /**
   * A touch-model router.
   *
   * @param params router parameters
   * @param venues venue parameters
   * @param urgency urgency given to every parent
   * @param instrumentId instrument routed
   */
  public RouterStrategy(
      final RouterParams params,
      final VenueParams[] venues,
      final double urgency,
      final int instrumentId) {
    this("router", params, venues, urgency, instrumentId, null);
  }

  /**
   * @param name name in reports
   * @param params router parameters
   * @param venues venue parameters, as declared (never the simulator's own)
   * @param urgency urgency given to every parent
   * @param instrumentId instrument routed
   * @param model the ladder model, or null for the touch model
   */
  public RouterStrategy(
      final String name,
      final RouterParams params,
      final VenueParams[] venues,
      final double urgency,
      final int instrumentId,
      final ExecutionModel model) {
    this.name = name;
    this.params = params;
    this.venues = venues.clone();
    this.urgency = urgency;
    this.instrumentId = instrumentId;
    this.model = model;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void startDay(final SimulatedMarket market, final Clock clock, final long seed) {
    router =
        new SmartOrderRouter(
            params,
            venues,
            market,
            market,
            FrameSink.NONE,
            clock,
            RandomSource.seeded(seed ^ 0x2545F4914F6CDD1DL),
            16,
            512,
            1L,
            RouterTimeouts.DEFAULT,
            model);
  }

  @Override
  public void submit(final long parentId, final Side side, final long quantity) {
    router.submit(parentId, instrumentId, side, quantity, urgency, 0L);
  }

  @Override
  public void poll() {
    router.poll();
  }

  @Override
  public boolean isDone(final long parentId) {
    return !router.isActive(parentId);
  }

  /** The router of the current day. */
  SmartOrderRouter router() {
    return router;
  }

  @Override
  public void onExecutionReport(
      final int venueId,
      final long clOrdId,
      final long execId,
      final OrdStatus ordStatus,
      final long lastPrice,
      final long lastQuantity,
      final long leavesQuantity,
      final long cumQuantity,
      final long transactNanos) {
    router.onExecutionReport(
        venueId, clOrdId, execId, ordStatus, lastPrice, lastQuantity, leavesQuantity, cumQuantity,
        transactNanos);
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    router.onCancelReject(venueId, clOrdId);
  }

  @Override
  public void onBookUpdate(final int venueId) {
    router.onBookUpdate(venueId);
  }

  @Override
  public void onTrade(
      final int venueId, final Side aggressorSide, final long price, final long quantity) {
    router.onTrade(venueId, aggressorSide, price, quantity);
  }
}
