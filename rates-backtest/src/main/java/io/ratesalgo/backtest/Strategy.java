package io.ratesalgo.backtest;

import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;

/**
 * A strategy under back-test (API_SPEC.md Section 16.1).
 *
 * <p>Callbacks receive the same SBE flyweights the live event loop will deliver (P2), positioned
 * on the message; they are valid only during the call. Every callback runs on the runner's single
 * thread with the {@link StrategyContext} clock already at the event's time. A strategy acts only
 * through the context: {@link StrategyContext#quote}, {@link StrategyContext#sendOrder} and
 * {@link StrategyContext#scheduleTimer}. Not quoting an RFQ is a decline and is counted.
 *
 * <p>Implementations must be deterministic given their inputs: no wall clock, no unseeded random
 * numbers, no iteration over hash-ordered collections that affects output.
 */
public interface Strategy {

  /** A short stable name used in results and the ledger. */
  String name();

  /** Called once before the first event. */
  default void onStart(final StrategyContext ctx) {}

  void onQuoteRequest(QuoteRequestDecoder rfq, StrategyContext ctx);

  default void onBookUpdate(final BookUpdateDecoder update, final StrategyContext ctx) {}

  default void onCurveInput(final CurveInputDecoder input, final StrategyContext ctx) {}

  default void onQuoteResponse(final QuoteResponseDecoder response, final StrategyContext ctx) {}

  default void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {}

  default void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {}
}
