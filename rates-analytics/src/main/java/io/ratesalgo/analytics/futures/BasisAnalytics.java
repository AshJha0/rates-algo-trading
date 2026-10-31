package io.ratesalgo.analytics.futures;

import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import java.util.List;

/**
 * Basis, implied repo and cheapest-to-deliver for a bond futures contract.
 *
 * <p>For a deliverable bond with spot clean price P, futures price F, conversion factor CF, spot
 * settlement s, delivery date D (d = D - s days), coupons C_i paid at dates t_i whose record date
 * ({@link FixedRateBond#entitlementDate}) is in (s, D], and repo rate r (Act/360 simple). For a
 * gilt delivered inside an ex-dividend period t_i is after D and the coupon enters at its value
 * at D to first order in r, consistent with the negative AI(D) in the invoice:
 *
 * <pre>
 *   gross basis  = P - F CF
 *   invoice      = F CF + AI(D)
 *   implied repo = (invoice + sum C_i - P_dirty) / (P_dirty d / 360 - sum C_i (D - t_i) / 360)
 *   carry        = AI(D) + sum C_i - AI(s) - (P_dirty r d / 360 - sum C_i r (D - t_i) / 360)
 *   net basis    = gross basis - carry
 * </pre>
 *
 * The cheapest to deliver has the highest implied repo (equivalently, near enough, the lowest net
 * basis). Futures DV01 is the CTD's DV01 divided by its conversion factor (Burghardt and Belton,
 * The Treasury Bond Basis, 3rd ed., Chapters 1 to 3). Exact given the inputs.
 */
public final class BasisAnalytics {

  /**
   * Result for one deliverable bond.
   *
   * @param bond bond name
   * @param conversionFactor CF
   * @param grossBasis per 100
   * @param carry per 100 to delivery
   * @param netBasis per 100
   * @param impliedRepo decimal, Act/360
   */
  public record Result(
      String bond,
      double conversionFactor,
      double grossBasis,
      double carry,
      double netBasis,
      double impliedRepo) {}

  private final BondPricer pricer = new BondPricer();

  /** Creates the calculator. */
  public BasisAnalytics() {}

  /** Analyses one bond. */
  public Result analyse(
      final FixedRateBond bond,
      final double cleanPrice,
      final double futuresPrice,
      final double conversionFactor,
      final int settlement,
      final int delivery,
      final double repoRate) {
    final double aiSpot = pricer.accrued(bond, settlement);
    final double aiDel = pricer.accrued(bond, delivery);
    final double dirty = cleanPrice + aiSpot;
    final int d = delivery - settlement;
    double coupons = 0.0;
    double couponTime = 0.0;
    for (int i = 0; i < bond.couponCount(); i++) {
      final int t = bond.couponDate(i);
      // owned from s to D: paid every coupon whose record date falls in (s, D], including a gilt
      // coupon paid after D when D is ex-dividend (D - t is then negative: discounted back to D)
      final int record = bond.entitlementDate(i);
      if (record > settlement && record <= delivery) {
        coupons += bond.couponAmount(i);
        couponTime += bond.couponAmount(i) * (delivery - t) / 360.0;
      }
    }
    final double invoice = futuresPrice * conversionFactor + aiDel;
    final double impliedRepo =
        (invoice + coupons - dirty) / (dirty * d / 360.0 - couponTime);
    final double gross = cleanPrice - futuresPrice * conversionFactor;
    final double carry =
        aiDel + coupons - aiSpot - (dirty * repoRate * d / 360.0 - couponTime * repoRate);
    return new Result(bond.name(), conversionFactor, gross, carry, gross - carry, impliedRepo);
  }

  /** Index of the cheapest to deliver: the highest implied repo. */
  public static int cheapestToDeliver(final List<Result> results) {
    if (results.isEmpty()) {
      throw new IllegalArgumentException("empty deliverable basket");
    }
    int best = 0;
    for (int i = 1; i < results.size(); i++) {
      if (results.get(i).impliedRepo() > results.get(best).impliedRepo()) {
        best = i;
      }
    }
    return best;
  }

  /**
   * Futures DV01 per contract: the CTD's yield DV01 per 100 at its street yield, divided by its
   * conversion factor, scaled to the contract face. Positive for a long contract, the sign of
   * {@link BondPricer#dv01}, not of the risk ladder.
   */
  public double futuresDv01(
      final FixedRateBond ctd,
      final double ctdYield,
      final int settlement,
      final double conversionFactor,
      final double face) {
    return pricer.dv01(ctd, ctdYield, settlement, false) / conversionFactor * face / 100.0;
  }
}
