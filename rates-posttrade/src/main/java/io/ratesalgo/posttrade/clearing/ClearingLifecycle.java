package io.ratesalgo.posttrade.clearing;

import io.ratesalgo.posttrade.trade.TradeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The clearing lifecycle of one swap, LCH SwapClear or CME style (API_SPEC.md Section 17.6).
 *
 * <pre>
 *   NEW --SUBMIT--&gt; SUBMITTED --ACCEPT--&gt; ACCEPTED --CLEAR--&gt; CLEARED
 *                     |                     |
 *                     +------REJECT---------+-----&gt; REJECTED
 * </pre>
 *
 * <p>Every other (state, event) pair is refused: the state does not change and the refusal is
 * kept in the history, because an out-of-order message from a clearing house is itself something
 * operations must see. A rejection voids the trade; the caller turns it into a {@code
 * PostTradeBust} with reason {@code ClearingRejected}, which is the CFTC treatment of a SEF trade
 * rejected for clearing (void ab initio).
 */
public final class ClearingLifecycle {

  /** One applied or refused event. */
  public record Step(ClearingState from, ClearingEvent event, ClearingState to, boolean applied, long nanos) {}

  private final TradeKey trade;
  private final List<Step> history = new ArrayList<>();
  private ClearingState state = ClearingState.NEW;

  public ClearingLifecycle(final TradeKey trade) {
    this.trade = trade;
  }

  /** The transition function; empty means the event is refused in that state. */
  public static Optional<ClearingState> next(final ClearingState from, final ClearingEvent event) {
    final ClearingState to =
        switch (from) {
          case NEW -> event == ClearingEvent.SUBMIT ? ClearingState.SUBMITTED : null;
          case SUBMITTED ->
              switch (event) {
                case ACCEPT -> ClearingState.ACCEPTED;
                case REJECT -> ClearingState.REJECTED;
                case SUBMIT, CLEAR -> null;
              };
          case ACCEPTED ->
              switch (event) {
                case CLEAR -> ClearingState.CLEARED;
                case REJECT -> ClearingState.REJECTED;
                case SUBMIT, ACCEPT -> null;
              };
          case CLEARED, REJECTED -> null;
        };
    return Optional.ofNullable(to);
  }

  /**
   * Applies one event.
   *
   * @return true if the state changed
   */
  public boolean apply(final ClearingEvent event, final long nanos) {
    final Optional<ClearingState> to = next(state, event);
    history.add(new Step(state, event, to.orElse(state), to.isPresent(), nanos));
    to.ifPresent(s -> state = s);
    return to.isPresent();
  }

  /** The current state. */
  public ClearingState state() {
    return state;
  }

  /** The trade this lifecycle belongs to. */
  public TradeKey trade() {
    return trade;
  }

  /** Every event applied or refused, oldest first. */
  public List<Step> history() {
    return List.copyOf(history);
  }
}
