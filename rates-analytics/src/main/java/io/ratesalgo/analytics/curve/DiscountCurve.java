package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.Dates;
import java.util.Arrays;

/**
 * A discount curve P(0, t) stored as log discount factors at pillar dates.
 *
 * <p>Time is measured Act/365F from the valuation date: t = (date - valuationDate) / 365. Node 0
 * is the valuation date with ln P = 0; nodes 1..N are the pillars. Between nodes the chosen {@link
 * Interpolation} applies; beyond the last pillar the last segment's average forward is extended.
 * Optional turn adjustments add a known extra overnight spread over short windows (year end,
 * quarter end) on top of the interpolated curve.
 *
 * <p>The curve is mutable and single-writer: the bootstrap sets node values and calls {@link
 * #update()}. Discount factors, gradients and zero rates do not allocate. Tolerance: values are
 * exact to double precision given the node values; there is no internal approximation other than
 * the tension spline series noted in its class.
 */
public final class DiscountCurve {

  private final int valuationDate;
  private final int[] pillarDates;
  private final double[] times;
  private final double[] logDf;
  private final Interpolation interpolation;
  private final double tension;
  private final Interpolator interpolator;
  private final double[] gradientWork;
  private int activeCount;
  private int[] turnStart = new int[0];
  private int[] turnEnd = new int[0];
  private double[] turnSpread = new double[0];

  /**
   * Builds a curve with all node values zero (flat zero rate), to be filled by a bootstrap.
   *
   * @param valuationDate curve reference date
   * @param pillarDates strictly increasing pillar dates after the valuation date
   * @param interpolation scheme between pillars
   * @param tension tension per year for {@link Interpolation#TENSION_SPLINE}; ignored otherwise
   */
  public DiscountCurve(
      final int valuationDate,
      final int[] pillarDates,
      final Interpolation interpolation,
      final double tension) {
    if (pillarDates.length == 0) {
      throw new IllegalArgumentException("a curve needs at least one pillar");
    }
    for (int i = 0; i < pillarDates.length; i++) {
      final int prev = i == 0 ? valuationDate : pillarDates[i - 1];
      if (pillarDates[i] <= prev) {
        throw new IllegalArgumentException(
            "pillar " + i + " (" + Dates.format(pillarDates[i]) + ") not after "
                + Dates.format(prev));
      }
    }
    this.valuationDate = valuationDate;
    this.pillarDates = Arrays.copyOf(pillarDates, pillarDates.length);
    final int n = pillarDates.length + 1;
    this.times = new double[n];
    this.logDf = new double[n];
    for (int i = 1; i < n; i++) {
      times[i] = (pillarDates[i - 1] - valuationDate) / 365.0;
    }
    this.interpolation = interpolation;
    this.tension = tension;
    this.interpolator =
        switch (interpolation) {
          case LINEAR_LOG_DF -> new LinearInterpolator(n);
          case MONOTONE_CONVEX -> new MonotoneConvexInterpolator(n);
          case TENSION_SPLINE -> new TensionSplineInterpolator(n, tension);
        };
    this.gradientWork = new double[n];
    this.activeCount = n;
    update();
  }

  /** Builds a curve from pillar dates and discount factors (not logs), and updates it. */
  public static DiscountCurve fromDiscountFactors(
      final int valuationDate,
      final int[] pillarDates,
      final double[] discountFactors,
      final Interpolation interpolation,
      final double tension) {
    final DiscountCurve c = new DiscountCurve(valuationDate, pillarDates, interpolation, tension);
    for (int i = 0; i < pillarDates.length; i++) {
      if (!(discountFactors[i] > 0.0)) {
        throw new IllegalArgumentException(
            "discount factor at " + Dates.format(pillarDates[i]) + " not positive: "
                + discountFactors[i]);
      }
      c.logDf[i + 1] = Math.log(discountFactors[i]);
    }
    c.update();
    return c;
  }

  /** A copy with the same pillars, node values, turns and interpolation. */
  public DiscountCurve copy() {
    final DiscountCurve c = new DiscountCurve(valuationDate, pillarDates, interpolation, tension);
    System.arraycopy(logDf, 0, c.logDf, 0, logDf.length);
    c.turnStart = Arrays.copyOf(turnStart, turnStart.length);
    c.turnEnd = Arrays.copyOf(turnEnd, turnEnd.length);
    c.turnSpread = Arrays.copyOf(turnSpread, turnSpread.length);
    c.activeCount = activeCount;
    c.update();
    return c;
  }

  /**
   * Adds a turn: an extra simple overnight spread (decimal, Act/360) over [start, end). Applied as
   * ln P(t) minus spread times the overlap of [start, end) with [valuation, t] in Act/360 years.
   * Must be added before bootstrapping.
   */
  public void addTurn(final int start, final int end, final double spread) {
    if (end <= start || start < valuationDate) {
      throw new IllegalArgumentException(
          "bad turn window " + Dates.format(start) + " to " + Dates.format(end));
    }
    final int k = turnStart.length;
    turnStart = Arrays.copyOf(turnStart, k + 1);
    turnEnd = Arrays.copyOf(turnEnd, k + 1);
    turnSpread = Arrays.copyOf(turnSpread, k + 1);
    turnStart[k] = start;
    turnEnd[k] = end;
    turnSpread[k] = spread;
  }

  /** Recomputes interpolation state after node values change. */
  public void update() {
    interpolator.update(times, logDf, activeCount);
  }

  /**
   * Restricts interpolation to the first {@code pillars} pillars (used by the sequential
   * bootstrap); later pillars are ignored and the curve extrapolates from the last active one.
   */
  public void setActivePillars(final int pillars) {
    if (pillars < 1 || pillars > pillarDates.length) {
      throw new IllegalArgumentException("active pillars out of range: " + pillars);
    }
    activeCount = pillars + 1;
    update();
  }

  /** Number of pillars (nodes excluding the valuation date). */
  public int pillarCount() {
    return pillarDates.length;
  }

  /** Pillar date i, 0-based. */
  public int pillarDate(final int i) {
    return pillarDates[i];
  }

  /** Copy of the pillar dates. */
  public int[] pillarDates() {
    return Arrays.copyOf(pillarDates, pillarDates.length);
  }

  /** Log discount factor at pillar i, 0-based. */
  public double pillarLogDf(final int i) {
    return logDf[i + 1];
  }

  /** Sets the log discount factor at pillar i; call {@link #update()} afterwards. */
  public void setPillarLogDf(final int i, final double value) {
    logDf[i + 1] = value;
  }

  /** The valuation (reference) date. */
  public int valuationDate() {
    return valuationDate;
  }

  /** The interpolation scheme. */
  public Interpolation interpolation() {
    return interpolation;
  }

  /** Act/365F time from the valuation date. */
  public double time(final int date) {
    return (date - valuationDate) / 365.0;
  }

  /** Discount factor to the date. */
  public double df(final int date) {
    return Math.exp(logDf(time(date)) + turnLog(date));
  }

  /** Discount factor at Act/365F time t (turns not applied). */
  public double dfAtTime(final double t) {
    return Math.exp(logDf(t));
  }

  /** Continuously compounded zero rate to the date, Act/365F. */
  public double zeroRate(final int date) {
    final double t = time(date);
    if (t <= 0.0) {
      throw new IllegalArgumentException("zero rate needs a date after the valuation date");
    }
    return -(logDf(t) + turnLog(date)) / t;
  }

  /** Simple forward rate between two dates on the given year fraction. */
  public double simpleForward(final int start, final int end, final double yearFraction) {
    return (df(start) / df(end) - 1.0) / yearFraction;
  }

  private double logDf(final double t) {
    if (t < 0.0) {
      throw new IllegalArgumentException("time before valuation date: " + t);
    }
    final int last = activeCount - 1;
    if (t <= times[last]) {
      return interpolator.value(t);
    }
    final double slope = (logDf[last] - logDf[last - 1]) / (times[last] - times[last - 1]);
    return logDf[last] + slope * (t - times[last]);
  }

  private double turnLog(final int date) {
    double s = 0.0;
    for (int k = 0; k < turnStart.length; k++) {
      final int overlap = Math.min(date, turnEnd[k]) - turnStart[k];
      if (overlap > 0) {
        s -= turnSpread[k] * overlap / 360.0;
      }
    }
    return s;
  }

  /**
   * Writes d ln P(date) / d ln P(pillar i) for i = 0..pillarCount-1 into {@code out}
   * (algorithmic differentiation through the interpolation; turns do not depend on pillars).
   */
  public void logDfGradient(final int date, final double[] out) {
    final double t = time(date);
    final int n = pillarDates.length;
    Arrays.fill(out, 0, n, 0.0);
    final int last = activeCount - 1;
    if (t <= times[last]) {
      interpolator.gradient(t, gradientWork);
      for (int i = 1; i <= last; i++) {
        out[i - 1] = gradientWork[i];
      }
    } else {
      final double u = (t - times[last]) / (times[last] - times[last - 1]);
      out[last - 1] = 1.0 + u;
      if (last >= 2) {
        out[last - 2] = -u;
      }
    }
  }

  /**
   * Accumulates {@code weight * d ln P(date) / d ln P(pillar i)} into {@code out}. Dates on or
   * before the valuation date contribute nothing.
   */
  public void accumulateLogDfGradient(final int date, final double weight, final double[] out) {
    final double t = time(date);
    if (t <= 0.0 || weight == 0.0) {
      return;
    }
    final int last = activeCount - 1;
    if (t <= times[last]) {
      interpolator.gradient(t, gradientWork);
      for (int i = 1; i <= last; i++) {
        out[i - 1] += weight * gradientWork[i];
      }
    } else {
      final double u = (t - times[last]) / (times[last] - times[last - 1]);
      out[last - 1] += weight * (1.0 + u);
      if (last >= 2) {
        out[last - 2] -= weight * u;
      }
    }
  }

  /**
   * Accumulates {@code weight * d P(date) / d ln P(pillar i)} into {@code out}; the adjoint step
   * used by every pricer's gradient.
   */
  public void accumulateDfGradient(final int date, final double weight, final double[] out) {
    final double t = time(date);
    if (t == 0.0 || weight == 0.0) {
      return;
    }
    final double w = weight * df(date);
    final int last = activeCount - 1;
    if (t <= times[last]) {
      interpolator.gradient(t, gradientWork);
      for (int i = 1; i <= last; i++) {
        out[i - 1] += w * gradientWork[i];
      }
    } else {
      final double u = (t - times[last]) / (times[last] - times[last - 1]);
      out[last - 1] += w * (1.0 + u);
      if (last >= 2) {
        out[last - 2] -= w * u;
      }
    }
  }
}
