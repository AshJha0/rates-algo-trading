package io.ratesalgo.posttrade.settlement;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settlement of one bond trade, FICC or CREST style delivery versus payment (API_SPEC.md
 * Section 17.7).
 *
 * <pre>
 *   PENDING --SETTLE--&gt; SETTLED
 *   PENDING --FAIL----&gt; FAILED --FAIL--&gt; FAILED (one more day)
 *                         FAILED --SETTLE--&gt; SETTLED (cured)
 *   PENDING or FAILED --CANCEL--&gt; CANCELLED
 * </pre>
 *
 * <p>SETTLE and FAIL are refused before the settlement date; nothing settles early. Refused events
 * leave the state unchanged and are kept in the history.
 */
public final class SettlementObligation {

  /** One applied or refused event. */
  public record Step(
      SettlementState from, SettlementEvent event, SettlementState to, boolean applied, LocalDate on) {}

  private final TradeKey trade;
  private final Side ourSide;
  private final LocalDate settlementDate;
  private final long proceedsMinor;
  private final List<Step> history = new ArrayList<>();
  private SettlementState state = SettlementState.PENDING;
  /** Date the obligation stopped being outstanding: settled or cancelled. */
  private LocalDate settledOn;

  /**
   * Creates a pending obligation.
   *
   * @param ourSide Buy means we receive bonds and pay cash; Sell means we deliver and are paid
   * @param proceedsMinor cash amount of the delivery versus payment, minor units
   */
  public SettlementObligation(
      final TradeKey trade, final Side ourSide, final LocalDate settlementDate, final long proceedsMinor) {
    if (proceedsMinor <= 0) {
      throw new IllegalArgumentException("proceeds must be positive for " + trade);
    }
    this.trade = trade;
    this.ourSide = ourSide;
    this.settlementDate = settlementDate;
    this.proceedsMinor = proceedsMinor;
  }

  /** The transition function, ignoring dates; empty means refused. */
  public static Optional<SettlementState> next(final SettlementState from, final SettlementEvent event) {
    final SettlementState to =
        switch (from) {
          case PENDING ->
              switch (event) {
                case SETTLE -> SettlementState.SETTLED;
                case FAIL -> SettlementState.FAILED;
                case CANCEL -> SettlementState.CANCELLED;
              };
          case FAILED ->
              switch (event) {
                case SETTLE -> SettlementState.SETTLED;
                case FAIL -> SettlementState.FAILED;
                case CANCEL -> SettlementState.CANCELLED;
              };
          case SETTLED, CANCELLED -> null;
        };
    return Optional.ofNullable(to);
  }

  /**
   * Applies one event on a business date.
   *
   * @return true if applied
   */
  public boolean apply(final SettlementEvent event, final LocalDate on) {
    Optional<SettlementState> to = next(state, event);
    if (event != SettlementEvent.CANCEL && on.isBefore(settlementDate)) {
      to = Optional.empty();
    }
    history.add(new Step(state, event, to.orElse(state), to.isPresent(), on));
    if (to.isPresent()) {
      state = to.get();
      if (state == SettlementState.SETTLED || state == SettlementState.CANCELLED) {
        settledOn = on;
      }
    }
    return to.isPresent();
  }

  /**
   * Calendar days the delivery has been outstanding past the settlement date as of {@code asOf}
   * (to the cure date once settled, or to the cancel date once cancelled). Zero if it never failed.
   */
  public long daysFailed(final LocalDate asOf) {
    final boolean everFailed = history.stream().anyMatch(s -> s.applied() && s.to() == SettlementState.FAILED);
    if (!everFailed) {
      return 0;
    }
    final LocalDate end = settledOn != null ? settledOn : asOf;
    return Math.max(0, ChronoUnit.DAYS.between(settlementDate, end));
  }

  public SettlementState state() {
    return state;
  }

  public TradeKey trade() {
    return trade;
  }

  public Side ourSide() {
    return ourSide;
  }

  public LocalDate settlementDate() {
    return settlementDate;
  }

  public long proceedsMinor() {
    return proceedsMinor;
  }

  /** Every event applied or refused, oldest first. */
  public List<Step> history() {
    return List.copyOf(history);
  }
}
