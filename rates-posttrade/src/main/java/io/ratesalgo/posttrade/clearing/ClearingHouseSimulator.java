package io.ratesalgo.posttrade.clearing;

import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stub clearing house for the simulated swap flow. Each account has a clearing-member credit
 * limit on gross notional; a submission that would take the account past its limit is rejected
 * (the CFTC Rule 1.73 pre-execution screen should have stopped it; this is the backstop). Accepted
 * trades clear at the end of the cycle. The clearing house's own record of cleared trades is the
 * counterparty side of the clearing reconciliation.
 */
public final class ClearingHouseSimulator {

  /** Callback for trades the clearing house rejects; the caller busts them. */
  @FunctionalInterface
  public interface RejectionListener {
    /** Called once per rejected trade. */
    void rejected(TradeKey trade, String reason);
  }

  private final Map<Integer, Long> limitByAccount = new HashMap<>();
  private final Map<Integer, Long> usedByAccount = new HashMap<>();
  private final Map<TradeKey, ClearingLifecycle> lifecycles = new TreeMap<>();
  private final Map<TradeKey, Trade> submitted = new HashMap<>();
  private final RejectionListener listener;

  public ClearingHouseSimulator(final RejectionListener listener) {
    this.listener = listener;
  }

  /** Sets an account's gross notional limit in minor units. */
  public ClearingHouseSimulator limit(final int account, final long grossNotionalMinor) {
    limitByAccount.put(account, grossNotionalMinor);
    return this;
  }

  /** Submits a booked trade and answers at once with accept or reject. */
  public ClearingState submit(final Trade trade, final long nanos) {
    final ClearingLifecycle lc = lifecycles.computeIfAbsent(trade.key(), ClearingLifecycle::new);
    lc.apply(ClearingEvent.SUBMIT, nanos);
    if (lc.state() != ClearingState.SUBMITTED) {
      return lc.state();
    }
    submitted.put(trade.key(), trade);
    final int account = trade.booking().account();
    final long limit = limitByAccount.getOrDefault(account, 0L);
    final long used = usedByAccount.getOrDefault(account, 0L);
    if (used + trade.quantity() > limit) {
      lc.apply(ClearingEvent.REJECT, nanos);
      listener.rejected(trade.key(), "account " + account + " limit " + limit + " used " + used
          + " trade " + trade.quantity());
    } else {
      usedByAccount.put(account, used + trade.quantity());
      lc.apply(ClearingEvent.ACCEPT, nanos);
    }
    return lc.state();
  }

  /** Novates every accepted trade. */
  public int endOfCycle(final long nanos) {
    int cleared = 0;
    for (final ClearingLifecycle lc : lifecycles.values()) {
      if (lc.state() == ClearingState.ACCEPTED && lc.apply(ClearingEvent.CLEAR, nanos)) {
        cleared++;
      }
    }
    return cleared;
  }

  /** The lifecycle of one trade, or null if never submitted. */
  public ClearingLifecycle lifecycle(final TradeKey key) {
    return lifecycles.get(key);
  }

  /** The clearing house's report of cleared trades, in key order. */
  public List<Trade> clearedReport() {
    final List<Trade> out = new ArrayList<>();
    for (final ClearingLifecycle lc : lifecycles.values()) {
      if (lc.state() == ClearingState.CLEARED) {
        out.add(submitted.get(lc.trade()));
      }
    }
    return out;
  }
}
