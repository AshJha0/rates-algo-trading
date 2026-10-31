package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.math.Brent;

/**
 * Price, yield and risk of a {@link FixedRateBond}, per 100 face.
 *
 * <p>Yield to price follows QuantLib 1.44 {@code CashFlows::npv(leg, InterestRate, ...)} line for
 * line: cash flows paying after settlement are discounted with compounding at the coupon
 * frequency, over times accumulated period by period on Act/Act ICMA with each coupon's reference
 * period, and a coupon whose ex-dividend date has passed contributes nothing. With nominal coupon
 * dates this is the street convention (the Treasury's 31 CFR 356 Appendix B formula and the DMO's
 * gilt formula for regular periods); with dates moved to business days it is the true yield.
 *
 * <ul>
 *   <li>Dirty price: exact given the yield. Clean = dirty - accrued.
 *   <li>Yield from price: Brent on [-50%, 100%] to 1e-15 in yield, so the price reproduces to
 *       about 1e-13 per 100.
 *   <li>Modified duration -P'/P and convexity P''/P from the analytic derivatives; DV01 = -P'
 *       times 1e-4, per 100 face per basis point of yield.
 * </ul>
 *
 * <p>Single-threaded; the scratch array grows to the largest bond priced and is then reused.
 */
public final class BondPricer {

  private double[] times = new double[64];
  private double[] amounts = new double[64];
  private int flows;
  private final YieldFunction yieldFunction = new YieldFunction();
  private final SpreadFunction spreadFunction = new SpreadFunction();

  /** Creates a pricer. */
  public BondPricer() {}

  /**
   * Accrued interest per 100 at settlement, by the market rule: zero on a coupon date, negative
   * inside an ex-dividend period (the buyer does not receive the next coupon). Exact.
   */
  public double accrued(final FixedRateBond b, final int settlement, final boolean adjusted) {
    final int[] pay = adjusted ? b.payAdjusted : b.payNominal;
    final DayCount dc = b.convention().dayCount();
    for (int i = 0; i < pay.length; i++) {
      if (pay[i] > settlement) {
        if (settlement <= b.accrualStart[i]) {
          return 0.0;
        }
        final double c = 100.0 * b.coupon();
        if (settlement >= b.exDate[i]) {
          return -c * dc.yearFraction(settlement, Math.max(settlement, b.accrualEnd[i]),
              b.refStart[i], b.refEnd[i]);
        }
        return c * dc.yearFraction(b.accrualStart[i], Math.min(settlement, b.accrualEnd[i]),
            b.refStart[i], b.refEnd[i]);
      }
    }
    return 0.0;
  }

  /** Street-convention accrued interest (nominal coupon dates). */
  public double accrued(final FixedRateBond b, final int settlement) {
    return accrued(b, settlement, false);
  }

  private void prepare(final FixedRateBond b, final int settlement, final boolean adjusted) {
    final int n = b.couponCount();
    if (times.length < n) {
      times = new double[n];
      amounts = new double[n];
    }
    final int[] pay = adjusted ? b.payAdjusted : b.payNominal;
    final DayCount dc = b.convention().dayCount();
    flows = 0;
    double t = 0.0;
    int lastDate = settlement;
    for (int i = 0; i < n; i++) {
      if (pay[i] <= settlement) {
        continue;
      }
      final double dt;
      if (lastDate != b.accrualStart[i]) {
        dt = dc.yearFraction(b.accrualStart[i], pay[i], b.refStart[i], b.refEnd[i])
            - dc.yearFraction(b.accrualStart[i], lastDate, b.refStart[i], b.refEnd[i]);
      } else {
        dt = dc.yearFraction(lastDate, pay[i], b.refStart[i], b.refEnd[i]);
      }
      t += dt;
      double a = settlement >= b.exDate[i] ? 0.0 : b.amount[i];
      if (i == n - 1) {
        a += 100.0;
      }
      times[flows] = t;
      amounts[flows] = a;
      flows++;
      lastDate = pay[i];
    }
    if (flows == 0) {
      throw new IllegalStateException(b.name() + " has no cash flows after settlement");
    }
  }

  private double npv(final double y, final int f) {
    final double base = 1.0 + y / f;
    double pv = 0.0;
    for (int k = 0; k < flows; k++) {
      pv += amounts[k] * Math.pow(base, -f * times[k]);
    }
    return pv;
  }

  /** Dirty price per 100 from a yield compounded at the coupon frequency. */
  public double dirtyPrice(
      final FixedRateBond b, final double yield, final int settlement, final boolean adjusted) {
    prepare(b, settlement, adjusted);
    return npv(yield, b.convention().frequency());
  }

  /** Clean price per 100 from a yield. */
  public double cleanPrice(
      final FixedRateBond b, final double yield, final int settlement, final boolean adjusted) {
    return dirtyPrice(b, yield, settlement, adjusted) - accrued(b, settlement, adjusted);
  }

  /** Street-convention clean price. */
  public double cleanPrice(final FixedRateBond b, final double yield, final int settlement) {
    return cleanPrice(b, yield, settlement, false);
  }

  /** Yield from a clean price; street convention if {@code adjusted} is false, true yield if true. */
  public double yieldFromClean(
      final FixedRateBond b, final double cleanPrice, final int settlement, final boolean adjusted) {
    final double dirty = cleanPrice + accrued(b, settlement, adjusted);
    prepare(b, settlement, adjusted);
    yieldFunction.target = dirty;
    yieldFunction.f = b.convention().frequency();
    return Brent.solve(yieldFunction, -0.5, 1.0, 1e-15, 200);
  }

  private final class YieldFunction implements Brent.Function {
    double target;
    int f;

    @Override
    public double value(final double y) {
      return npv(y, f) - target;
    }
  }

  /**
   * Clean price per 100 by the Treasury's auction formula (31 CFR Part 356, Appendix B, Section
   * I.A): the value at the next coupon date of the remaining cash flows at the semi-annual yield,
   * discounted to settlement with simple interest over the fraction r/s of the current period,
   * less accrued interest. It differs from the compounded street convention only through that
   * first fraction, and it reproduces every 2024 note and bond auction price to the published six
   * decimals ({@code PublishedExamplesTest}). Exact.
   */
  public double treasuryAuctionPrice(
      final FixedRateBond b, final double yield, final int settlement) {
    return treasuryAuctionDirtyPrice(b, yield, settlement) - accrued(b, settlement, false);
  }

  /**
   * Dirty price per 100 by the Treasury auction formula. The Treasury publishes the clean price
   * as this value and the accrued interest each rounded to six decimals, then subtracted, which
   * reproduces all 84 note and bond prices of 2024.
   */
  public double treasuryAuctionDirtyPrice(
      final FixedRateBond b, final double yield, final int settlement) {
    int k = 0;
    while (b.payNominal[k] <= settlement) {
      k++;
    }
    final int f = b.convention().frequency();
    final double v = 1.0 / (1.0 + yield / f);
    double atNext = b.amount[k];
    double vj = 1.0;
    final int n = b.couponCount();
    for (int j = k + 1; j < n; j++) {
      vj *= v;
      atNext += b.amount[j] * vj;
    }
    atNext += 100.0 * (k == n - 1 ? 1.0 : vj);
    final double s = b.refEnd[k] - b.refStart[k];
    final double r = b.payNominal[k] - settlement;
    return atNext / (1.0 + r / s * yield / f);
  }

  /** Yield from a clean price by the Treasury auction formula; to 1e-15. */
  public double treasuryAuctionYield(
      final FixedRateBond b, final double cleanPrice, final int settlement) {
    auctionFunction.bond = b;
    auctionFunction.settlement = settlement;
    auctionFunction.target = cleanPrice;
    return Brent.solve(auctionFunction, -0.5, 1.0, 1e-15, 200);
  }

  private final AuctionFunction auctionFunction = new AuctionFunction();

  private final class AuctionFunction implements Brent.Function {
    FixedRateBond bond;
    int settlement;
    double target;

    @Override
    public double value(final double y) {
      return treasuryAuctionPrice(bond, y, settlement) - target;
    }
  }

  /** Modified duration -(dP/dy)/P on the dirty price. */
  public double modifiedDuration(
      final FixedRateBond b, final double yield, final int settlement, final boolean adjusted) {
    prepare(b, settlement, adjusted);
    final int f = b.convention().frequency();
    final double base = 1.0 + yield / f;
    double pv = 0.0;
    double d1 = 0.0;
    for (int k = 0; k < flows; k++) {
      final double t = times[k];
      pv += amounts[k] * Math.pow(base, -f * t);
      d1 -= amounts[k] * t * Math.pow(base, -f * t - 1.0);
    }
    return -d1 / pv;
  }

  /** Convexity (d2P/dy2)/P on the dirty price. */
  public double convexity(
      final FixedRateBond b, final double yield, final int settlement, final boolean adjusted) {
    prepare(b, settlement, adjusted);
    final int f = b.convention().frequency();
    final double base = 1.0 + yield / f;
    double pv = 0.0;
    double d2 = 0.0;
    for (int k = 0; k < flows; k++) {
      final double t = times[k];
      pv += amounts[k] * Math.pow(base, -f * t);
      d2 += amounts[k] * t * (f * t + 1.0) / f * Math.pow(base, -f * t - 2.0);
    }
    return d2 / pv;
  }

  /** Yield DV01: price change per 100 face for a one basis point fall in yield. */
  public double dv01(
      final FixedRateBond b, final double yield, final int settlement, final boolean adjusted) {
    return modifiedDuration(b, yield, settlement, adjusted)
        * dirtyPrice(b, yield, settlement, adjusted) * 1e-4;
  }

  /**
   * Dirty price per 100 from a discount curve, as QuantLib's {@code DiscountingBondEngine}
   * settlement value: sum of cash flows paying after settlement (ex-dividend coupons excluded) at
   * P(pay) / P(settlement), with business-day payment dates. Exact.
   */
  public double dirtyFromCurve(
      final FixedRateBond b, final DiscountCurve curve, final int settlement) {
    return dirtyFromCurve(b, curve, settlement, 0.0);
  }

  /**
   * Dirty price with a continuously compounded z-spread s added to the curve's zero rates:
   * discount P(pay) exp(-s t_pay) / (P(settle) exp(-s t_settle)), times Act/365F.
   */
  public double dirtyFromCurve(
      final FixedRateBond b, final DiscountCurve curve, final int settlement, final double spread) {
    final double dfs = curve.df(settlement);
    final double ts = curve.time(settlement);
    double pv = 0.0;
    final int n = b.couponCount();
    for (int i = 0; i < n; i++) {
      final int pay = b.payAdjusted[i];
      if (pay <= settlement) {
        continue;
      }
      double a = settlement >= b.exDate[i] ? 0.0 : b.amount[i];
      if (i == n - 1) {
        a += 100.0;
      }
      pv += a * curve.df(pay) / dfs * Math.exp(-spread * (curve.time(pay) - ts));
    }
    return pv;
  }

  /**
   * Adds {@code weight * d dirtyFromCurve / d ln P(pillar)} into {@code out} (algorithmic
   * differentiation; used for key-rate DV01 of a bond against the OIS curve).
   */
  public void dirtyFromCurveGradient(
      final FixedRateBond b,
      final DiscountCurve curve,
      final int settlement,
      final double weight,
      final double[] out) {
    final double dfs = curve.df(settlement);
    double pv = 0.0;
    final int n = b.couponCount();
    for (int i = 0; i < n; i++) {
      final int pay = b.payAdjusted[i];
      if (pay <= settlement) {
        continue;
      }
      double a = settlement >= b.exDate[i] ? 0.0 : b.amount[i];
      if (i == n - 1) {
        a += 100.0;
      }
      final double v = a * curve.df(pay) / dfs;
      pv += v;
      curve.accumulateLogDfGradient(pay, weight * v, out);
    }
    curve.accumulateLogDfGradient(settlement, -weight * pv, out);
  }

  /** Z-spread (continuous, decimal) that reprices the dirty price on the curve; to 1e-15. */
  public double zSpread(
      final FixedRateBond b, final DiscountCurve curve, final double dirty, final int settlement) {
    spreadFunction.bond = b;
    spreadFunction.curve = curve;
    spreadFunction.settlement = settlement;
    spreadFunction.target = dirty;
    return Brent.solve(spreadFunction, -0.05, 0.05, 1e-15, 200);
  }

  private final class SpreadFunction implements Brent.Function {
    FixedRateBond bond;
    DiscountCurve curve;
    int settlement;
    double target;

    @Override
    public double value(final double s) {
      return dirtyFromCurve(bond, curve, settlement, s) - target;
    }
  }

  /**
   * Par-par asset-swap spread (decimal) to a discount curve: the spread over the floating index
   * that a buyer paying par and swapping the bond's coupons receives, s = (B - P) / (100 A), with
   * B the bond's value on the curve, P the dirty market price and A the floating leg annuity over
   * the remaining coupon periods (the first starting at settlement) on {@code floatDayCount},
   * discounted to settlement. Exact.
   */
  public double assetSwapSpread(
      final FixedRateBond b,
      final DiscountCurve curve,
      final double dirty,
      final int settlement,
      final DayCount floatDayCount) {
    final double bValue = dirtyFromCurve(b, curve, settlement);
    final double dfs = curve.df(settlement);
    double annuity = 0.0;
    for (int i = 0; i < b.couponCount(); i++) {
      final int pay = b.payAdjusted[i];
      if (pay <= settlement) {
        continue;
      }
      final int start = Math.max(b.accrualStart[i], settlement);
      annuity += floatDayCount.yearFraction(start, b.accrualEnd[i]) * curve.df(pay) / dfs;
    }
    return (bValue - dirty) / (100.0 * annuity);
  }
}
