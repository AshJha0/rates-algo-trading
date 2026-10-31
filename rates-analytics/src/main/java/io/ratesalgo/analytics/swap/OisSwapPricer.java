package io.ratesalgo.analytics.swap;

import io.ratesalgo.analytics.curve.DiscountCurve;

/**
 * Prices {@link OisSwap}s: present value, par rate, annuity, and the gradient of each with respect
 * to the curve's log discount factors at its pillars (algorithmic differentiation, adjoint mode).
 *
 * <p>Floating period p pays N (C_p - 1) a_p / r_p at its payment date, where C_p is the product
 * of the daily growth factors 1 + R_i d_i, a_p the period accrual and r_p the rate accrual (equal
 * to a_p without an observation shift). Fixings dated before {@code today} come from the history;
 * a fixing dated today is used if published and otherwise projected, which is the intraday case
 * (today's SOFR is published tomorrow morning). A projected fixing is R = (P(v)/P(m) - 1) / tau(v,
 * m) over the fixing's value and maturity dates. Runs of consecutive days whose accrual dates equal
 * the value and maturity dates telescope to one discount ratio, as QuantLib's pricer does.
 *
 * <p>Cash flows paying on or before {@code today} are excluded. Tolerance: exact up to rounding;
 * the golden test checks PV against QuantLib 1.44 to 1e-8 of notional and par rates to 1e-6.
 *
 * <p>The pricer allocates nothing per call; it is single-threaded.
 */
public final class OisSwapPricer {

  private double lastCompound;

  /** Creates a pricer. */
  public OisSwapPricer() {}

  /** Present value of the fixed leg per unit fixed rate and unit notional: sum of tau P(pay). */
  public double annuity(final OisSwap swap, final DiscountCurve discount, final int today) {
    double a = 0.0;
    for (int i = 0; i < swap.fixedPay.length; i++) {
      if (swap.fixedPay[i] > today) {
        a += swap.fixedTau[i] * discount.df(swap.fixedPay[i]);
      }
    }
    return a;
  }

  /** Present value of the floating leg (receiver view, positive for positive rates). */
  public double floatLegPv(
      final OisSwap swap,
      final DiscountCurve projection,
      final DiscountCurve discount,
      final FixingHistory fixings,
      final int today) {
    double pv = 0.0;
    for (int p = 0; p < swap.floatPay.length; p++) {
      if (swap.floatPay[p] <= today) {
        continue;
      }
      final double c = compound(swap, p, projection, fixings, today, null, 0.0);
      pv += swap.notional() * (c - 1.0) * swap.floatAccrual[p] / swap.floatRateTau[p]
          * discount.df(swap.floatPay[p]);
    }
    return pv;
  }

  /** Swap present value: float minus fixed for a payer, the negative for a receiver. */
  public double pv(
      final OisSwap swap,
      final DiscountCurve projection,
      final DiscountCurve discount,
      final FixingHistory fixings,
      final int today) {
    final double flt = floatLegPv(swap, projection, discount, fixings, today);
    final double fix = swap.notional() * swap.fixedRate() * annuity(swap, discount, today);
    return swap.isPayer() ? flt - fix : fix - flt;
  }

  /** Single-curve present value. */
  public double pv(
      final OisSwap swap, final DiscountCurve curve, final FixingHistory fixings, final int today) {
    return pv(swap, curve, curve, fixings, today);
  }

  /** Fixed rate that sets the present value to zero. */
  public double parRate(
      final OisSwap swap,
      final DiscountCurve projection,
      final DiscountCurve discount,
      final FixingHistory fixings,
      final int today) {
    final double a = annuity(swap, discount, today);
    if (!(a > 0.0)) {
      throw new IllegalStateException("no remaining fixed cash flows after today");
    }
    return floatLegPv(swap, projection, discount, fixings, today) / (swap.notional() * a);
  }

  /** Single-curve par rate. */
  public double parRate(
      final OisSwap swap, final DiscountCurve curve, final FixingHistory fixings, final int today) {
    return parRate(swap, curve, curve, fixings, today);
  }

  /**
   * Gradient of PV with respect to the log discount factors of the projection and discount
   * curves' pillars. Either output may be null to skip it; if both curves are the same object,
   * pass the same array for both to get the total.
   */
  public void pvGradient(
      final OisSwap swap,
      final DiscountCurve projection,
      final DiscountCurve discount,
      final FixingHistory fixings,
      final int today,
      final double[] dProjection,
      final double[] dDiscount) {
    final double sign = swap.isPayer() ? 1.0 : -1.0;
    for (int p = 0; p < swap.floatPay.length; p++) {
      if (swap.floatPay[p] <= today) {
        continue;
      }
      final double scale = swap.notional() * swap.floatAccrual[p] / swap.floatRateTau[p];
      final double dfPay = discount.df(swap.floatPay[p]);
      if (dProjection != null) {
        compound(swap, p, projection, fixings, today, dProjection, sign * scale * dfPay);
      }
      if (dDiscount != null) {
        final double c = compound(swap, p, projection, fixings, today, null, 0.0);
        discount.accumulateLogDfGradient(
            swap.floatPay[p], sign * scale * (c - 1.0) * dfPay, dDiscount);
      }
    }
    if (dDiscount != null) {
      final double k = -sign * swap.notional() * swap.fixedRate();
      for (int i = 0; i < swap.fixedPay.length; i++) {
        if (swap.fixedPay[i] > today) {
          discount.accumulateLogDfGradient(
              swap.fixedPay[i], k * swap.fixedTau[i] * discount.df(swap.fixedPay[i]), dDiscount);
        }
      }
    }
  }

  /**
   * Gradient of the par rate with respect to log discount factors, single curve. {@code work} must
   * have the curve's pillar count.
   */
  public void parRateGradient(
      final OisSwap swap,
      final DiscountCurve curve,
      final FixingHistory fixings,
      final int today,
      final double[] out,
      final double[] work) {
    final int n = curve.pillarCount();
    java.util.Arrays.fill(work, 0, n, 0.0);
    java.util.Arrays.fill(out, 0, n, 0.0);
    final double a = annuity(swap, curve, today);
    final double flt = floatLegPv(swap, curve, curve, fixings, today);
    // d float leg
    for (int p = 0; p < swap.floatPay.length; p++) {
      if (swap.floatPay[p] <= today) {
        continue;
      }
      final double scale = swap.notional() * swap.floatAccrual[p] / swap.floatRateTau[p];
      final double dfPay = curve.df(swap.floatPay[p]);
      final double c = compound(swap, p, curve, fixings, today, out, scale * dfPay);
      curve.accumulateLogDfGradient(swap.floatPay[p], scale * (c - 1.0) * dfPay, out);
    }
    // d annuity
    for (int i = 0; i < swap.fixedPay.length; i++) {
      if (swap.fixedPay[i] > today) {
        curve.accumulateLogDfGradient(
            swap.fixedPay[i], swap.fixedTau[i] * curve.df(swap.fixedPay[i]), work);
      }
    }
    final double na = swap.notional() * a;
    for (int i = 0; i < n; i++) {
      out[i] = out[i] / na - flt * swap.notional() * work[i] / (na * na);
    }
  }

  /** Compound factor of the last {@link #compound} call; for diagnostics. */
  double lastCompound() {
    return lastCompound;
  }

  /**
   * Compound factor C_p. If {@code grad} is non-null, adds weight * dC_p / d ln P(pillar) to it.
   */
  private double compound(
      final OisSwap s,
      final int p,
      final DiscountCurve curve,
      final FixingHistory fixings,
      final int today,
      final double[] grad,
      final double weight) {
    final int from = s.dayOffset[p];
    final int to = s.dayOffset[p + 1];
    double c = 1.0;
    // first pass: value
    int k = from;
    while (k < to) {
      final int fd = s.fixingDate[k];
      if (fd < today || (fd == today && fixings.has(fd))) {
        c *= 1.0 + fixings.rate(fd) * s.dayTau[k];
        k++;
        continue;
      }
      if (s.telescopic[k]) {
        int j = k;
        while (j + 1 < to && s.telescopic[j + 1] && s.valueStart[j + 1] == s.valueEnd[j]) {
          j++;
        }
        c *= curve.df(s.valueStart[k]) / curve.df(s.valueEnd[j]);
        k = j + 1;
      } else {
        final double ratio = curve.df(s.valueStart[k]) / curve.df(s.valueEnd[k]);
        c *= 1.0 + (ratio - 1.0) * s.dayTau[k] / s.fixingTau[k];
        k++;
      }
    }
    lastCompound = c;
    if (grad == null) {
      return c;
    }
    // second pass: d ln C = sum of d ln growth
    final double wc = weight * c;
    k = from;
    while (k < to) {
      final int fd = s.fixingDate[k];
      if (fd < today || (fd == today && fixings.has(fd))) {
        k++;
        continue;
      }
      if (s.telescopic[k]) {
        int j = k;
        while (j + 1 < to && s.telescopic[j + 1] && s.valueStart[j + 1] == s.valueEnd[j]) {
          j++;
        }
        curve.accumulateLogDfGradient(s.valueStart[k], wc, grad);
        curve.accumulateLogDfGradient(s.valueEnd[j], -wc, grad);
        k = j + 1;
      } else {
        final double ratio = curve.df(s.valueStart[k]) / curve.df(s.valueEnd[k]);
        final double cc = s.dayTau[k] / s.fixingTau[k];
        final double g = 1.0 + (ratio - 1.0) * cc;
        final double w = wc * cc * ratio / g;
        curve.accumulateLogDfGradient(s.valueStart[k], w, grad);
        curve.accumulateLogDfGradient(s.valueEnd[k], -w, grad);
        k++;
      }
    }
    return c;
  }
}
