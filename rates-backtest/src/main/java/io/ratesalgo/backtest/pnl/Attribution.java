package io.ratesalgo.backtest.pnl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P&amp;L attribution lines, in major currency units (API_SPEC.md Section 16.4).
 *
 * @param spreadCapture client trades: signed quantity times (mid at trade minus trade price)
 * @param inventoryMtm position held times the change in mid, summed over every mid change
 * @param hedgeCost hedge trades: signed quantity times (mid at trade minus fill price)
 * @param carryRoll coupon carry and roll-down; a hook that is zero until P1 analytics are wired
 * @param funding financing of bond positions at the funding rate, Act/360
 * @param fees venue and clearing fees, negative
 * @param residual position-based P&amp;L minus the sum of the lines above; zero to the cent
 * @param positionPnl cash plus final position valued at the final mid, computed independently
 */
public record Attribution(
    double spreadCapture,
    double inventoryMtm,
    double hedgeCost,
    double carryRoll,
    double funding,
    double fees,
    double residual,
    double positionPnl) {

  public static final Attribution ZERO = new Attribution(0, 0, 0, 0, 0, 0, 0, 0);

  /** Sum of the explained lines (everything but the residual). */
  public double explained() {
    return spreadCapture + inventoryMtm + hedgeCost + carryRoll + funding + fees;
  }

  public Attribution plus(final Attribution o) {
    return new Attribution(
        spreadCapture + o.spreadCapture,
        inventoryMtm + o.inventoryMtm,
        hedgeCost + o.hedgeCost,
        carryRoll + o.carryRoll,
        funding + o.funding,
        fees + o.fees,
        residual + o.residual,
        positionPnl + o.positionPnl);
  }

  /** The lines in report order, keyed by their schema names. */
  public Map<String, Object> asMap() {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("spread_capture", spreadCapture);
    m.put("inventory_mtm", inventoryMtm);
    m.put("hedge_cost", hedgeCost);
    m.put("carry_roll", carryRoll);
    m.put("funding", funding);
    m.put("fees", fees);
    m.put("residual", residual);
    m.put("position_pnl", positionPnl);
    return m;
  }
}
