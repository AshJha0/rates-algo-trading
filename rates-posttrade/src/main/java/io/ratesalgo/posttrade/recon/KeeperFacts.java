package io.ratesalgo.posttrade.recon;

import io.ratesalgo.posttrade.position.AuditEntry;
import io.ratesalgo.posttrade.position.AuditKind;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Explains breaks from a keeper that has read the whole journal, given the journal sequence at
 * which the reconciled snapshot was cut (API_SPEC.md Section 17.8):
 *
 * <ul>
 *   <li>MISSING_OURS on a live trade booked after the cut: {@link Explanation#LATE_ARRIVAL}.
 *   <li>MISSING_OURS on a trade we busted for a clearing rejection: {@link
 *       Explanation#CLEARING_REJECTED}.
 *   <li>MISSING_THEIRS on a trade busted after the cut: {@link Explanation#BUSTED_AFTER_CUT}.
 *   <li>PRICE or QUANTITY on an amended trade: {@link Explanation#AMENDED}.
 *   <li>Any break on a key with a conflicting duplicate: {@link Explanation#DUPLICATE_CONFLICT},
 *       which does not clear.
 * </ul>
 */
public final class KeeperFacts implements BreakExplainer {

  private final PositionKeeper full;
  private final long cutSequence;
  private final Map<TradeKey, AuditEntry> busts = new HashMap<>();
  private final Set<TradeKey> conflicts = new HashSet<>();

  /**
   * Creates an explainer.
   *
   * @param full a keeper that has read every frame up to now
   * @param cutSequence the last journal sequence included in the reconciled snapshot
   */
  public KeeperFacts(final PositionKeeper full, final long cutSequence) {
    this.full = full;
    this.cutSequence = cutSequence;
    for (final AuditEntry a : full.audit(AuditKind.BUSTED)) {
      busts.put(a.key(), a);
    }
    for (final AuditEntry a : full.audit(AuditKind.DUPLICATE_CONFLICT)) {
      conflicts.add(a.key());
    }
  }

  @Override
  public Explanation explain(final String matchKey, final BreakClass breakClass) {
    final TradeKey key;
    try {
      key = TradeKey.parse(matchKey);
    } catch (final IllegalArgumentException notATrade) {
      return Explanation.NONE;
    }
    if (conflicts.contains(key)) {
      return Explanation.DUPLICATE_CONFLICT;
    }
    final Trade t = full.trade(key);
    final AuditEntry bust = busts.get(key);
    return switch (breakClass) {
      case MISSING_OURS -> {
        if (t != null && t.isLive() && full.versions(key).get(0).journalSequence() > cutSequence) {
          yield Explanation.LATE_ARRIVAL;
        }
        if (bust != null && bust.detail().contains("ClearingRejected")) {
          yield Explanation.CLEARING_REJECTED;
        }
        yield Explanation.NONE;
      }
      case MISSING_THEIRS ->
          bust != null && bust.journalSequence() > cutSequence
              ? Explanation.BUSTED_AFTER_CUT
              : Explanation.NONE;
      case PRICE, QUANTITY -> t != null && t.version() > 1 ? Explanation.AMENDED : Explanation.NONE;
      case TIMING -> Explanation.NONE;
    };
  }
}
