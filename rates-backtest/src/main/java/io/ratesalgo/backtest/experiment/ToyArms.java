package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;

/**
 * Arms with no desk behind them, for the runner's own tests and smoke runs: {@code idle} declines
 * every RFQ; {@code fixed} quotes 0.12 bp on swaps and never hedges; {@code fixed_hedged} quotes
 * the same and hedges in the future above 20,000 USD of daily sd. They are not strategies anyone
 * should study; they exercise the harness with known relations between arms.
 */
public final class ToyArms implements ArmFactory {

  @Override
  public String refusal(final String arm) {
    return switch (arm) {
      case "idle", "fixed", "fixed_hedged" -> null;
      default -> "unknown toy arm '" + arm + "' (idle, fixed, fixed_hedged)";
    };
  }

  @Override
  public Strategy create(final String arm, final SimDay day) {
    return switch (arm) {
      case "idle" -> new Idle();
      case "fixed" -> new FixedSpreadStrategy(0.12, 0.07, 1e15, 1);
      case "fixed_hedged" -> new FixedSpreadStrategy(0.12, 0.07, 20_000, 1);
      default -> throw new IllegalArgumentException(refusal(arm));
    };
  }

  /** Declines every RFQ and sends nothing. */
  static final class Idle implements Strategy {
    @Override
    public String name() {
      return "idle";
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      // declines
    }
  }
}
