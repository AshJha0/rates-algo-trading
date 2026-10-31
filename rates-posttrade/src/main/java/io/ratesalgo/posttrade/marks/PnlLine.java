package io.ratesalgo.posttrade.marks;

import io.ratesalgo.posttrade.trade.PositionKey;
import java.time.LocalDate;

/**
 * Official P&amp;L of one position for one business date, each component rounded half-even to
 * the minor unit and the total the sum of the rounded components (marking policy, API_SPEC.md
 * Section 17.3).
 *
 * @param date business date
 * @param key book, account, instrument
 * @param netQuantity closing quantity
 * @param mark official close used, Decimal9 mantissa
 * @param realisedMinor realised P&amp;L
 * @param unrealisedMinor unrealised P&amp;L against the close
 * @param carryMinor carry
 */
public record PnlLine(
    LocalDate date,
    PositionKey key,
    long netQuantity,
    long mark,
    long realisedMinor,
    long unrealisedMinor,
    long carryMinor) {

  /** Sum of the rounded components. */
  public long totalMinor() {
    return Math.addExact(Math.addExact(realisedMinor, unrealisedMinor), carryMinor);
  }

  /** One component's amount. */
  public long amount(final PnlComponent component) {
    return switch (component) {
      case REALISED -> realisedMinor;
      case UNREALISED -> unrealisedMinor;
      case CARRY -> carryMinor;
    };
  }
}
