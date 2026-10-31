package io.ratesalgo.backtest.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;

/**
 * Base of the experiment strategies: quotes every RFQ symmetrically around mid at a half-spread
 * the subclass chooses, and hedges net DV01 into the 10-year note future whenever its absolute
 * value exceeds a threshold.
 *
 * <p>The hedge is a market order for {@code round(-netDv01 / dv01PerContract)} contracts. One
 * hedge is in flight at a time; when it fills the rule is checked again.
 */
public abstract class HedgingQuoter implements Strategy {

  private final double hedgeThresholdDv01;
  private final int hedgeInstrument;
  private boolean hedgeInFlight;

  protected HedgingQuoter(final double hedgeThresholdDv01) {
    if (!(hedgeThresholdDv01 > 0)) {
      throw new IllegalArgumentException("hedge threshold must be positive");
    }
    this.hedgeThresholdDv01 = hedgeThresholdDv01;
    this.hedgeInstrument = InstrumentSet.TY_FUTURE;
  }

  /** Half-spread in bp for this RFQ; negative or NaN means decline. */
  protected abstract double halfSpreadBp(
      Instrument instrument, double sizeMillions, int tier, int dealers, StrategyContext ctx);

  /** Client tier from the client id, matching the runner's rule. */
  protected int tier(final int clientId, final int clientsPerTier) {
    return 1 + (clientId - 1) / clientsPerTier;
  }

  /** The number of clients per tier the strategy assumes. */
  protected abstract int clientsPerTier();

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
    final int id = rfq.instrumentId();
    if (!ctx.hasMid(id)) {
      return; // no reference price yet: decline
    }
    final Instrument instrument = ctx.instruments().get(id);
    final double size = (double) rfq.quantity() / InstrumentSet.MILLION;
    final double h =
        halfSpreadBp(instrument, size, tier(rfq.clientId(), clientsPerTier()), rfq.dealerCount(), ctx);
    if (Double.isNaN(h) || h < 0) {
      return;
    }
    final double mid = ctx.mid(id);
    final double distance = instrument.bpToPrice(h);
    final long tick = instrument.tickMantissa();
    final long bid = Prices.roundToTick(Prices.toMantissa(mid - distance), tick, false);
    final long ask = Prices.roundToTick(Prices.toMantissa(mid + distance), tick, true);
    ctx.quote(rfq.rfqId(), bid, ask);
  }

  @Override
  public void onQuoteResponse(final QuoteResponseDecoder response, final StrategyContext ctx) {
    if (response.status() == QuoteStatus.Done) {
      maybeHedge(ctx);
    }
  }

  @Override
  public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
    final OrdStatus s = report.ordStatus();
    if (s == OrdStatus.Filled || s == OrdStatus.Rejected || s == OrdStatus.Cancelled) {
      hedgeInFlight = false;
      if (s == OrdStatus.Filled) {
        maybeHedge(ctx);
      }
    }
  }

  private void maybeHedge(final StrategyContext ctx) {
    if (hedgeInFlight || !ctx.hasMid(hedgeInstrument)) {
      return;
    }
    final double net = ctx.netDv01();
    if (Math.abs(net) <= hedgeThresholdDv01) {
      return;
    }
    final Instrument future = ctx.instruments().get(hedgeInstrument);
    final double perContract = future.dv01PerUnit() * future.lotSize();
    final long contracts = Math.round(-net / perContract);
    if (contracts == 0) {
      return;
    }
    hedgeInFlight = true;
    ctx.sendOrder(
        hedgeInstrument,
        contracts > 0 ? Side.Buy : Side.Sell,
        OrdType.Market,
        Math.abs(contracts) * future.lotSize(),
        0L,
        true);
  }

  public double hedgeThresholdDv01() {
    return hedgeThresholdDv01;
  }
}
