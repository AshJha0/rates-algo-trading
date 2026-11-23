package io.ratesalgo.analytics.curve;

import java.util.Arrays;

/**
 * What the SOFR curve of pricing v2 is built from and what it is reported on. Immutable; the
 * {@code with} methods return changed copies.
 *
 * <p>Build set, in maturity order:
 *
 * <ul>
 *   <li>the overnight fixing estimate, which sets the level until the first policy date;
 *   <li>one one-month future (SR1) per modelled FOMC meeting, which sets the level after that
 *       meeting ({@link #meetings()} of them);
 *   <li>{@link #sr3Contracts()} consecutive three-month futures (SR3) after the last step;
 *   <li>par swaps at {@link #swapTenorMonths()}.
 * </ul>
 *
 * <p>Risk set: par swaps at {@link #riskTenorMonths()}, the tenors every ladder is reported on.
 * The two sets are declared separately. Where a risk tenor is also a build swap the mapping is
 * the identity for it; where it is not, the mapping is the Jacobian of {@link SofrCurve}.
 *
 * <p>Futures convexity: Hull-White one factor with {@link #hwA()} and {@link #hwSigma()}. These
 * are assumptions (DECISIONS.md DR-P1-05), not calibrated: the repository holds no option prices.
 * {@code SofrCurveTest.convexityAssumptionSensitivity} states what a 20 percent error in sigma
 * moves.
 */
public final class SofrCurveSpec {

  /** Tension per year of the spline schemes unless another is given. */
  public static final double DEFAULT_TENSION = 0.5;

  private final int meetings;
  private final int sr3Contracts;
  private final int[] swapTenorMonths;
  private final int[] riskTenorMonths;
  private final double hwA;
  private final double hwSigma;
  private final Interpolation interpolation;
  private final double tension;
  private final int[] turnStart;
  private final int[] turnEnd;
  private final double[] turnSpread;

  private SofrCurveSpec(
      final int meetings,
      final int sr3Contracts,
      final int[] swapTenorMonths,
      final int[] riskTenorMonths,
      final double hwA,
      final double hwSigma,
      final Interpolation interpolation,
      final double tension,
      final int[] turnStart,
      final int[] turnEnd,
      final double[] turnSpread) {
    if (meetings < 0 || sr3Contracts < 0) {
      throw new IllegalArgumentException("negative instrument count");
    }
    requireIncreasing(swapTenorMonths, "swap tenors");
    requireIncreasing(riskTenorMonths, "risk tenors");
    final boolean spline =
        interpolation == Interpolation.TENSION_SPLINE
            || interpolation == Interpolation.STEP_THEN_TENSION_SPLINE;
    if (spline && !(tension > 0.0)) {
      throw new IllegalArgumentException("a tension spline needs a positive tension: " + tension);
    }
    this.meetings = meetings;
    this.sr3Contracts = sr3Contracts;
    this.swapTenorMonths = swapTenorMonths.clone();
    this.riskTenorMonths = riskTenorMonths.clone();
    this.hwA = hwA;
    this.hwSigma = hwSigma;
    this.interpolation = interpolation;
    this.tension = tension;
    this.turnStart = turnStart.clone();
    this.turnEnd = turnEnd.clone();
    this.turnSpread = turnSpread.clone();
  }

  private static void requireIncreasing(final int[] months, final String what) {
    if (months.length == 0) {
      throw new IllegalArgumentException(what + " are empty");
    }
    for (int i = 0; i < months.length; i++) {
      if (months[i] <= (i == 0 ? 0 : months[i - 1])) {
        throw new IllegalArgumentException(what + " not increasing: " + Arrays.toString(months));
      }
    }
  }

  /**
   * The USD desk curve: two meeting steps, three SR3 contracts, swaps at the eight risk tenors
   * (1, 2, 3, 5, 7, 10, 20 and 30 years), step-then-tension-spline interpolation at {@link
   * #DEFAULT_TENSION}, the DR-P1-05 Hull-White parameters, no turns. The interpolation is chosen
   * on the evidence of {@code SofrCurveTest.interpolationEvidence}.
   */
  public static SofrCurveSpec usd() {
    final int[] tenors = {12, 24, 36, 60, 84, 120, 240, 360};
    return new SofrCurveSpec(
        2, 3, tenors, tenors, HullWhite.DEFAULT_A, HullWhite.DEFAULT_SIGMA,
        Interpolation.STEP_THEN_TENSION_SPLINE, DEFAULT_TENSION, new int[0], new int[0],
        new double[0]);
  }

  /** A copy with other counts of meeting steps and SR3 contracts. */
  public SofrCurveSpec withFutures(final int newMeetings, final int newSr3Contracts) {
    return new SofrCurveSpec(newMeetings, newSr3Contracts, swapTenorMonths, riskTenorMonths, hwA,
        hwSigma, interpolation, tension, turnStart, turnEnd, turnSpread);
  }

  /** A copy with other build swaps. */
  public SofrCurveSpec withSwapTenors(final int... months) {
    return new SofrCurveSpec(meetings, sr3Contracts, months, riskTenorMonths, hwA, hwSigma,
        interpolation, tension, turnStart, turnEnd, turnSpread);
  }

  /** A copy with other Hull-White parameters. */
  public SofrCurveSpec withHullWhite(final double a, final double sigma) {
    return new SofrCurveSpec(meetings, sr3Contracts, swapTenorMonths, riskTenorMonths, a, sigma,
        interpolation, tension, turnStart, turnEnd, turnSpread);
  }

  /** A copy with another interpolation and the same tension. */
  public SofrCurveSpec withInterpolation(final Interpolation scheme) {
    return withInterpolation(scheme, tension);
  }

  /** A copy with another interpolation and tension (per year; used by the spline schemes). */
  public SofrCurveSpec withInterpolation(final Interpolation scheme, final double newTension) {
    return new SofrCurveSpec(meetings, sr3Contracts, swapTenorMonths, riskTenorMonths, hwA,
        hwSigma, scheme, newTension, turnStart, turnEnd, turnSpread);
  }

  /**
   * A copy with one more turn: an extra simple overnight spread (decimal, Act/360) over [start,
   * end), for a year end or quarter end. See {@link DiscountCurve#addTurn}.
   */
  public SofrCurveSpec withTurn(final int start, final int end, final double spread) {
    final int k = turnStart.length;
    final int[] s = Arrays.copyOf(turnStart, k + 1);
    final int[] e = Arrays.copyOf(turnEnd, k + 1);
    final double[] x = Arrays.copyOf(turnSpread, k + 1);
    s[k] = start;
    e[k] = end;
    x[k] = spread;
    return new SofrCurveSpec(meetings, sr3Contracts, swapTenorMonths, riskTenorMonths, hwA,
        hwSigma, interpolation, tension, s, e, x);
  }

  /** FOMC meetings with a dated step. */
  public int meetings() {
    return meetings;
  }

  /** Three-month futures after the step section. */
  public int sr3Contracts() {
    return sr3Contracts;
  }

  /** Tenors of the build swaps in months (copy). */
  public int[] swapTenorMonths() {
    return swapTenorMonths.clone();
  }

  /** Tenors of the risk swaps in months (copy). */
  public int[] riskTenorMonths() {
    return riskTenorMonths.clone();
  }

  /** Hull-White mean reversion per year. */
  public double hwA() {
    return hwA;
  }

  /** Hull-White short-rate volatility per year. */
  public double hwSigma() {
    return hwSigma;
  }

  /** Tension per year of the spline schemes. */
  public double tension() {
    return tension;
  }

  /** Interpolation of the curve. */
  public Interpolation interpolation() {
    return interpolation;
  }

  /** Number of turns. */
  public int turnCount() {
    return turnStart.length;
  }

  /** First day of turn k. */
  public int turnStart(final int k) {
    return turnStart[k];
  }

  /** Day after the last day of turn k. */
  public int turnEnd(final int k) {
    return turnEnd[k];
  }

  /** Spread of turn k, decimal. */
  public double turnSpread(final int k) {
    return turnSpread[k];
  }
}
