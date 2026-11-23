package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.math.LuSolver;

/**
 * A fitted Treasury curve, expressed as the z-spread of Treasuries to the SOFR curve by maturity.
 *
 * <p>Plain idea. The desk sees a handful of Treasury books: the on-the-run notes and whatever
 * off-the-run issues the venue lists. A free-standing yield curve fitted to four to ten prices is
 * either an interpolation (a spline through every point has no opinion about any of them) or
 * under-determined (Nelson-Siegel-Svensson has six parameters, four of them tangled). The SOFR
 * curve already carries the shape of rates at every maturity; what Treasuries add is the swap
 * spread, which is smooth in maturity. So the fit is of the spread: each bond's z-spread to the
 * SOFR curve, regressed on a level, a slope and a curvature term, plus one number for the
 * on-the-run premium. Bond fair value is then the SOFR curve plus the fitted spread, and a bond's
 * richness is its own spread minus the fit.
 *
 * <p>Formal statement. For bond i with years to maturity T_i, market z-spread s_i, weight w_i and
 * on-the-run flag o_i:
 *
 * <pre>
 *   s_i = b0 + b1 f1(T_i) + b2 f2(T_i) + c o_i + e_i
 *   f1(T) = (1 - exp(-T/tau)) / (T/tau)        f2(T) = f1(T) - exp(-T/tau)
 * </pre>
 *
 * the Nelson-Siegel basis with a fixed decay tau, so the model is linear in its parameters and
 * the fit is a weighted least squares solved in closed form. One curve term (a level) is fitted
 * to fewer than four usable bonds, two to four to seven, three to eight or more, so that a bond is
 * never the only evidence for a term. The premium term c enters when both on-the-run and
 * off-the-run bonds are present and a degree of freedom remains for it. The reported on-the-run
 * premium is -c: positive when on-the-run issues trade at a lower spread (richer) than the
 * off-the-run curve.
 *
 * <p>Robustness, in two steps with fixed iteration counts, so the result is a deterministic
 * function of the inputs. First, at most two bonds are dropped as bad books: the bond with the
 * largest standardised residual is dropped when the fit made without it misses it by more than
 * the rejection limit. Second, the fit of the remaining bonds is repeated with Huber weights
 * min(1, k / |e_i|), so a residual beyond k pulls the fit as if it were k. What this cannot do:
 * with four bonds and two terms there are two degrees of freedom, so one bad book can be found
 * and two cannot; a book wrong by less than the rejection limit is bounded, not removed; and a
 * long end that bends away from the two-term form by more than the limit is read as a bad book,
 * in which case that bond's fair value rests on its own residual (see {@code
 * FairValueSmoother}) and {@link #rejectedCount} says so.
 *
 * <p>A bond's own influence. {@link #leaveOneOut} is the fit at bond i's maturity with bond i
 * taken out, from the leverage h_i of the final weighted fit: s_i - e_i / (1 - h_i). This is
 * exact for the weights held fixed, so a bond's own book moves its own model value only through
 * the change of the robust weights.
 *
 * <p>{@link #fit} and the accessors do not allocate. Single-threaded.
 */
public final class TreasurySpreadCurve {

  /** Largest number of curve terms. */
  public static final int MAX_TERMS = 3;

  private static final int MAX_COLUMNS = MAX_TERMS + 1;
  private static final int HUBER_ROUNDS = 8;
  private static final int REJECT_ROUNDS = 2;

  /** Below this share of its own fit left to the others, a bond's leave-one-out value is unusable. */
  private static final double MIN_FREE_SHARE = 0.05;

  private final int capacity;
  private final double tau;
  private final double huber;
  private final double reject;
  private final double[] design;
  private final double[] spread;
  private final double[] weight;
  private final double[] robust;
  private final double[] fitted;
  private final double[] leverage;
  private final boolean[] onTheRun;
  private final double[] normal = new double[MAX_COLUMNS * MAX_COLUMNS];
  private final double[] beta = new double[MAX_COLUMNS];
  private final double[] work = new double[MAX_COLUMNS];
  private final LuSolver lu = new LuSolver(MAX_COLUMNS);
  private int count;
  private int terms;
  private int columns;
  private boolean premium;
  private int rejected;

  /**
   * Creates the curve.
   *
   * @param capacity largest number of bonds
   * @param tau decay of the basis in years, positive
   * @param huber residual beyond which a bond's pull is capped, decimal spread
   * @param reject residual beyond which a bond is dropped, decimal spread, above {@code huber}
   */
  public TreasurySpreadCurve(
      final int capacity, final double tau, final double huber, final double reject) {
    if (capacity < 1 || !(tau > 0.0) || !(huber > 0.0) || !(reject > huber)) {
      throw new IllegalArgumentException(
          "need capacity >= 1, tau > 0 and reject > huber > 0: " + capacity + ", " + tau + ", "
              + huber + ", " + reject);
    }
    this.capacity = capacity;
    this.tau = tau;
    this.huber = huber;
    this.reject = reject;
    this.design = new double[capacity * MAX_COLUMNS];
    this.spread = new double[capacity];
    this.weight = new double[capacity];
    this.robust = new double[capacity];
    this.fitted = new double[capacity];
    this.leverage = new double[capacity];
    this.onTheRun = new boolean[capacity];
  }

  /** The desk settings: decay 5 years, Huber limit 1.5 bp, rejection at 5 bp. */
  public static TreasurySpreadCurve standard(final int capacity) {
    return new TreasurySpreadCurve(capacity, 5.0, 1.5e-4, 5e-4);
  }

  private double basis(final int term, final double years) {
    if (term == 0) {
      return 1.0;
    }
    final double x = Math.max(years, 1e-6) / tau;
    final double e = Math.exp(-x);
    final double f1 = (1.0 - e) / x;
    return term == 1 ? f1 : f1 - e;
  }

  /**
   * Fits the curve to the first {@code n} bonds. A bond with a weight that is not positive or a
   * spread that is not finite takes no part and counts as rejected.
   *
   * @param n number of bonds, at least one
   * @param years years to maturity
   * @param spreads market z-spreads to the SOFR curve, decimal
   * @param weights inverse variances of the spreads; only their ratios matter
   * @param otr true for an on-the-run issue
   */
  public void fit(
      final int n, final double[] years, final double[] spreads, final double[] weights,
      final boolean[] otr) {
    if (n < 1 || n > capacity) {
      throw new IllegalArgumentException("bond count " + n + " outside 1.." + capacity);
    }
    count = n;
    int usable = 0;
    for (int i = 0; i < n; i++) {
      spread[i] = spreads[i];
      onTheRun[i] = otr[i];
      final boolean ok = weights[i] > 0.0 && Double.isFinite(spreads[i]);
      weight[i] = ok ? weights[i] : 0.0;
      robust[i] = ok ? 1.0 : 0.0;
      usable += ok ? 1 : 0;
      for (int t = 0; t < MAX_TERMS; t++) {
        design[i * MAX_COLUMNS + t] = basis(t, years[i]);
      }
    }
    rejected = n - usable;
    terms = usable >= 8 ? MAX_TERMS : usable >= 4 ? 2 : 1;
    for (int round = 0; ; round++) {
      layout();
      solve();
      leverages();
      if (round == REJECT_ROUNDS || !dropWorst()) {
        break;
      }
    }
    for (int k = 0; k < HUBER_ROUNDS; k++) {
      for (int i = 0; i < n; i++) {
        if (robust[i] > 0.0) {
          final double r = Math.abs(spread[i] - fitted[i]);
          robust[i] = r <= huber ? 1.0 : huber / r;
        }
      }
      solve();
    }
    leverages();
  }

  private void leverages() {
    for (int i = 0; i < count; i++) {
      leverage[i] = robust[i] > 0.0 ? robust[i] * weight[i] * quadratic(i) : 0.0;
    }
  }

  /** Decides the premium column from the bonds still in the fit. */
  private void layout() {
    int on = 0;
    int off = 0;
    for (int i = 0; i < count; i++) {
      if (robust[i] > 0.0) {
        on += onTheRun[i] ? 1 : 0;
        off += onTheRun[i] ? 0 : 1;
      }
    }
    premium = on >= 1 && off >= 1 && on + off - terms - 1 >= 1;
    columns = terms + (premium ? 1 : 0);
    for (int i = 0; i < count; i++) {
      design[i * MAX_COLUMNS + terms] = premium && onTheRun[i] ? 1.0 : 0.0;
    }
  }

  /**
   * Drops one bond if it stands out. The candidate is the bond with the largest standardised
   * residual |e_i| / sqrt(1 - h_i): for a single bad bond that is the bad one, since the residual
   * it forces on any other bond is smaller by the Cauchy-Schwarz bound on the hat matrix. It is
   * dropped if its deleted residual |e_i| / (1 - h_i), the miss of the fit made without it, is
   * beyond the rejection limit, and at least four bonds are in the fit with a degree of freedom
   * to spare after the drop.
   */
  private boolean dropWorst() {
    int worst = -1;
    int active = 0;
    double max = 0.0;
    for (int i = 0; i < count; i++) {
      if (robust[i] > 0.0) {
        active++;
        final double free = 1.0 - leverage[i];
        if (free >= MIN_FREE_SHARE) {
          final double standardised = Math.abs(spread[i] - fitted[i]) / Math.sqrt(free);
          if (standardised > max) {
            max = standardised;
            worst = i;
          }
        }
      }
    }
    if (worst < 0 || active < Math.max(4, columns + 2)) {
      return false;
    }
    final double deleted = Math.abs(spread[worst] - fitted[worst]) / (1.0 - leverage[worst]);
    if (!(deleted > reject)) {
      return false;
    }
    robust[worst] = 0.0;
    rejected++;
    return true;
  }

  private void solve() {
    java.util.Arrays.fill(normal, 0.0);
    java.util.Arrays.fill(beta, 0.0);
    double trace = 0.0;
    for (int i = 0; i < count; i++) {
      final double w = robust[i] * weight[i];
      if (w == 0.0) {
        continue;
      }
      for (int a = 0; a < columns; a++) {
        final double da = design[i * MAX_COLUMNS + a];
        beta[a] += w * da * spread[i];
        for (int b = 0; b < columns; b++) {
          normal[a * columns + b] += w * da * design[i * MAX_COLUMNS + b];
        }
      }
    }
    for (int a = 0; a < columns; a++) {
      trace += normal[a * columns + a];
    }
    // a ridge far below the data's scale: it only keeps a degenerate design solvable
    final double ridge = 1e-12 * trace / columns;
    for (int a = 0; a < columns; a++) {
      normal[a * columns + a] += ridge;
    }
    lu.factor(normal, columns);
    lu.solve(beta);
    for (int i = 0; i < count; i++) {
      double f = 0.0;
      for (int a = 0; a < columns; a++) {
        f += beta[a] * design[i * MAX_COLUMNS + a];
      }
      fitted[i] = f;
    }
  }

  /** a_i^T N^-1 a_i for bond i against the factored normal matrix. */
  private double quadratic(final int i) {
    for (int a = 0; a < columns; a++) {
      work[a] = design[i * MAX_COLUMNS + a];
    }
    lu.solve(work);
    double q = 0.0;
    for (int a = 0; a < columns; a++) {
      q += work[a] * design[i * MAX_COLUMNS + a];
    }
    return q;
  }

  /** Bonds in the last fit. */
  public int count() {
    return count;
  }

  /** Curve terms used by the last fit, 1 to 3. */
  public int terms() {
    return terms;
  }

  /** True if the last fit estimated an on-the-run premium. */
  public boolean premiumIdentified() {
    return premium;
  }

  /** Bonds left out of the last fit: unusable inputs and rejected outliers. */
  public int rejectedCount() {
    return rejected;
  }

  /** Off-the-run curve spread at a maturity, decimal. */
  public double spread(final double years) {
    double s = 0.0;
    for (int t = 0; t < terms; t++) {
      s += beta[t] * basis(t, years);
    }
    return s;
  }

  /**
   * The on-the-run premium, decimal: how far below the off-the-run curve on-the-run issues trade.
   * Zero when it is not identified, in which case the curve is the curve of the bonds given.
   */
  public double onTheRunPremium() {
    return premium ? -beta[terms] : 0.0;
  }

  /** Fitted spread of bond i, its premium term included. */
  public double fitted(final int i) {
    return fitted[i];
  }

  /** Market spread minus fitted spread of bond i: positive is cheap. */
  public double residual(final int i) {
    return spread[i] - fitted[i];
  }

  /** Robust weight of bond i in the last fit: 1 inside the Huber limit, 0 if left out. */
  public double robustWeight(final int i) {
    return robust[i];
  }

  /** Leverage of bond i: the share of its own fitted value that comes from its own spread. */
  public double leverage(final int i) {
    return leverage[i];
  }

  /**
   * The fitted spread of bond i with bond i left out of the fit. For a bond the fit already left
   * out, or one whose leverage leaves under 5 percent of its fit to the others, the fitted value.
   */
  public double leaveOneOut(final int i) {
    final double free = 1.0 - leverage[i];
    if (robust[i] == 0.0 || free < MIN_FREE_SHARE) {
      return fitted[i];
    }
    return spread[i] - (spread[i] - fitted[i]) / free;
  }

  /**
   * Par yield (semi-annual, decimal) of a bond of the given maturity on a discount curve with a
   * continuous spread added: coupons every half year back from maturity, the first period
   * possibly short, price 100 clean. Used for roll-down: the curve yield at a maturity.
   *
   * @param curve the SOFR curve
   * @param settlement settlement date
   * @param years years from settlement to maturity, positive
   * @param spread z-spread, decimal
   */
  public static double parYield(
      final io.ratesalgo.analytics.curve.DiscountCurve curve, final int settlement,
      final double years, final double spread) {
    final double dfs = curve.df(settlement);
    double annuity = 0.0;
    double t = years;
    double last = 0.0;
    while (t > 1e-9) {
      final double d = curve.df(settlement + (int) Math.round(t * 365.0)) / dfs
          * Math.exp(-spread * t);
      if (t == years) {
        last = d;
      }
      annuity += d;
      t -= 0.5;
    }
    // the first coupon period is short by -t half-years; its accrued is not paid by the buyer
    final double shortfall = -t / 0.5;
    return 2.0 * (1.0 - last) / (annuity - shortfall);
  }
}
