package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.math.Brent;
import io.ratesalgo.analytics.math.LevenbergMarquardt;
import io.ratesalgo.analytics.math.LuSolver;
import io.ratesalgo.analytics.swap.FixingHistory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bootstraps a {@link DiscountCurve} so that every instrument reprices to its quote.
 *
 * <p>Two solvers (DECISIONS.md DR-P1-03):
 *
 * <ul>
 *   <li>Sequential: pillars are solved in date order, each by Brent's method on its log discount
 *       factor with later pillars switched off. Valid when the interpolation is local and no
 *       instrument depends on a date beyond its own pillar.
 *   <li>Global: Levenberg-Marquardt on all pillar values at once with the analytic Jacobian
 *       (instrument gradients by algorithmic differentiation), started from a sequential sweep.
 *       Used when instruments overlap (an instrument depends on dates past its pillar) or the
 *       interpolation is non-local (monotone convex, tension spline), where each residual depends
 *       on later pillars.
 * </ul>
 *
 * <p>Tolerances: the solve stops when every |implied - quote| is at or below {@link
 * #SOLVER_TOLERANCE} (1e-12 in quote units: decimal rate, or price for futures); the build throws
 * if it is not met. {@code CurveBuilderTest} asserts that every instrument reprices within 1e-10
 * and that an incremental rebuild after one quote change equals a full rebuild to 1e-10 on
 * discount factors.
 *
 * <p>After a build, {@link #quoteSensitivities} maps a gradient with respect to log discount
 * factors to sensitivities with respect to the quotes, by solving J^T x = g with J the Jacobian of
 * implied quotes in log discount factors. This is how key-rate DV01 by pillar is computed.
 */
public final class CurveBuilder {

  /** Stopping tolerance on |implied - quote| in quote units. */
  public static final double SOLVER_TOLERANCE = 1e-12;

  /** Solver choice. */
  public enum Method {
    /** Sequential if valid, otherwise global. */
    AUTO,
    SEQUENTIAL,
    GLOBAL
  }

  private final List<CurveInstrument> instruments;
  private final DiscountCurve curve;
  private final CurveContext context;
  private final int n;
  private final double[] jacobian;
  private final double[] row;
  private final LuSolver lu;
  private final LevenbergMarquardt lm;
  private final double[] lmX;
  private final PillarFunction pillarFunction = new PillarFunction();
  private Method lastMethod = Method.AUTO;
  private boolean jacobianValid;

  /**
   * Builds the curve object (all pillars at zero) for the instruments, sorted by pillar date.
   *
   * @param valuationDate today
   * @param instruments one per pillar; pillar dates must be distinct
   * @param interpolation interpolation scheme
   * @param tension tension for the tension spline
   * @param discount discount curve for a projection-curve bootstrap, or null for a single OIS curve
   * @param fixings published fixings of the index
   */
  public CurveBuilder(
      final int valuationDate,
      final List<? extends CurveInstrument> instruments,
      final Interpolation interpolation,
      final double tension,
      final DiscountCurve discount,
      final FixingHistory fixings) {
    final List<CurveInstrument> sorted = new ArrayList<>(instruments);
    sorted.sort(Comparator.comparingInt(CurveInstrument::pillarDate));
    this.instruments = List.copyOf(sorted);
    this.n = sorted.size();
    final int[] pillars = new int[n];
    for (int i = 0; i < n; i++) {
      pillars[i] = sorted.get(i).pillarDate();
      if (i > 0 && pillars[i] == pillars[i - 1]) {
        throw new IllegalArgumentException(
            "two instruments share pillar " + Dates.format(pillars[i]) + ": "
                + sorted.get(i - 1).label() + " and " + sorted.get(i).label());
      }
    }
    this.curve = new DiscountCurve(valuationDate, pillars, interpolation, tension);
    this.context =
        new CurveContext(curve, discount == null ? curve : discount, fixings, valuationDate);
    this.jacobian = new double[n * n];
    this.row = new double[n];
    this.lu = new LuSolver(n);
    this.lm = new LevenbergMarquardt(n, n);
    this.lmX = new double[n];
  }

  /** The curve being built. */
  public DiscountCurve curve() {
    return curve;
  }

  /** The context instruments are evaluated in. */
  public CurveContext context() {
    return context;
  }

  /** Instruments in pillar order. */
  public List<CurveInstrument> instruments() {
    return instruments;
  }

  /** Adds a turn to the curve; call before building. */
  public void addTurn(final int start, final int end, final double spread) {
    curve.addTurn(start, end, spread);
  }

  /** True if some instrument depends on dates after its pillar. */
  public boolean instrumentsOverlap() {
    for (final CurveInstrument inst : instruments) {
      if (inst.latestRelevantDate() > inst.pillarDate()) {
        return true;
      }
    }
    return false;
  }

  /** The method {@link Method#AUTO} resolves to. */
  public Method resolve(final Method method) {
    if (method != Method.AUTO) {
      return method;
    }
    return curve.interpolation().isLocal() && !instrumentsOverlap()
        ? Method.SEQUENTIAL
        : Method.GLOBAL;
  }

  /** Full bootstrap from a flat start. Returns the resolved method. */
  public Method build(final Method method) {
    for (int i = 0; i < n; i++) {
      curve.setPillarLogDf(i, 0.0);
    }
    final Method m = resolve(method);
    sequentialFrom(0);
    if (m == Method.GLOBAL) {
      global();
    }
    lastMethod = m;
    jacobianValid = false;
    checkResiduals();
    return m;
  }

  /**
   * Incremental rebuild after instrument {@code index} (in pillar order) changes its quote:
   * sequential re-solves only that pillar and those after it, from the current values; global
   * restarts Levenberg-Marquardt from the current solution.
   */
  public void rebuild(final int index, final double newQuote) {
    instruments.get(index).setQuote(newQuote);
    if (lastMethod == Method.SEQUENTIAL) {
      sequentialFrom(index);
    } else {
      curve.setActivePillars(n);
      global();
    }
    jacobianValid = false;
    checkResiduals();
  }

  /** Largest |implied - quote| over the instruments. */
  public double maxRepricingError() {
    curve.setActivePillars(n);
    double max = 0.0;
    // indexed, not for-each: an incremental rebuild runs on the event loop, which must not allocate
    for (int i = 0; i < n; i++) {
      final CurveInstrument inst = instruments.get(i);
      max = Math.max(max, Math.abs(inst.impliedQuote(context) - inst.quote()));
    }
    return max;
  }

  private void checkResiduals() {
    final double err = maxRepricingError();
    if (!(err <= SOLVER_TOLERANCE)) {
      throw new IllegalStateException(
          "bootstrap did not reprice within " + SOLVER_TOLERANCE + ": max error " + err);
    }
  }

  private void sequentialFrom(final int from) {
    for (int i = from; i < n; i++) {
      curve.setActivePillars(i + 1);
      final double prevY = i == 0 ? 0.0 : curve.pillarLogDf(i - 1);
      final double prevT = i == 0 ? 0.0 : curve.time(curve.pillarDate(i - 1));
      final double dt = curve.time(curve.pillarDate(i)) - prevT;
      pillarFunction.index = i;
      final double current = curve.pillarLogDf(i);
      double lo = prevY - 0.15 * dt;
      double hi = prevY + 0.05 * dt;
      if (current != 0.0 && current > lo && current < hi) {
        // warm start: a narrow bracket around the previous solution
        lo = current - 1e-4 * dt - 1e-12;
        hi = current + 1e-4 * dt + 1e-12;
      }
      final double y = Brent.solve(pillarFunction, lo, hi, 1e-16, 200);
      curve.setPillarLogDf(i, y);
      curve.update();
    }
    curve.setActivePillars(n);
  }

  private final class PillarFunction implements Brent.Function {
    int index;

    @Override
    public double value(final double x) {
      curve.setPillarLogDf(index, x);
      curve.update();
      final CurveInstrument inst = instruments.get(index);
      return inst.impliedQuote(context) - inst.quote();
    }
  }

  private void global() {
    final double[] x = lmX;
    for (int i = 0; i < n; i++) {
      x[i] = curve.pillarLogDf(i);
    }
    lm.minimise(globalProblem, x, SOLVER_TOLERANCE * 0.1, 1e-15, 100);
    for (int i = 0; i < n; i++) {
      curve.setPillarLogDf(i, x[i]);
    }
    curve.update();
  }

  private final LevenbergMarquardt.Problem globalProblem =
      new LevenbergMarquardt.Problem() {
        @Override
        public int residualCount() {
          return n;
        }

        @Override
        public int parameterCount() {
          return n;
        }

        @Override
        public void residuals(final double[] x, final double[] r) {
          setAll(x);
          for (int i = 0; i < n; i++) {
            final CurveInstrument inst = instruments.get(i);
            r[i] = inst.impliedQuote(context) - inst.quote();
          }
        }

        @Override
        public void jacobian(final double[] x, final double[] j) {
          setAll(x);
          for (int i = 0; i < n; i++) {
            instruments.get(i).impliedQuoteGradient(context, row);
            System.arraycopy(row, 0, j, i * n, n);
          }
        }
      };

  private void setAll(final double[] x) {
    for (int i = 0; i < n; i++) {
      curve.setPillarLogDf(i, x[i]);
    }
    curve.update();
  }

  /** Fills and factors the Jacobian d implied_i / d ln P(pillar j) at the current solution. */
  private void ensureJacobian() {
    if (jacobianValid) {
      return;
    }
    curve.setActivePillars(n);
    for (int i = 0; i < n; i++) {
      instruments.get(i).impliedQuoteGradient(context, row);
      System.arraycopy(row, 0, jacobian, i * n, n);
    }
    lu.factor(jacobian, n);
    jacobianValid = true;
  }

  /** Copy of the Jacobian d implied_i / d ln P(pillar j), row-major. */
  public double[] jacobian() {
    ensureJacobian();
    return jacobian.clone();
  }

  /**
   * Converts {@code dValue/d ln P(pillar)} in {@code gradient} into {@code dValue/d quote_i} in
   * place, in pillar (instrument) order.
   */
  public void quoteSensitivities(final double[] gradient) {
    ensureJacobian();
    lu.solveTransposed(gradient);
  }
}
