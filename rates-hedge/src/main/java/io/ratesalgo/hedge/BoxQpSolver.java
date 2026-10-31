package io.ratesalgo.hedge;

/**
 * Solves the box-constrained convex quadratic programme
 *
 * <pre>
 *   minimise  0.5 x'Qx + c'x   subject to  lo &lt;= x &lt;= hi
 * </pre>
 *
 * by accelerated projected gradient (FISTA with gradient-based restart). Q must be symmetric
 * positive semi-definite. The step is 1/L with L the Gershgorin bound on Q's largest eigenvalue.
 * Iteration stops when the projected-gradient residual {@code max_i |x_i - P(x_i - g_i / L)| * L}
 * falls below {@code tolerance} times {@code max(1, max_i |c_i|)}, or after {@code maxIterations}
 * (API_SPEC.md Section 14.3 gives the tolerances in use).
 *
 * <p>Sized once for {@code n} variables; {@link #solve} does not allocate.
 */
public final class BoxQpSolver {

  private final int n;
  private final double[] y;
  private final double[] xPrev;
  private final double[] grad;
  private final double tolerance;
  private final int maxIterations;
  private int iterations;
  private double residual;

  /** A solver for {@code n} variables. */
  public BoxQpSolver(final int n, final double tolerance, final int maxIterations) {
    if (n <= 0 || !(tolerance > 0.0) || maxIterations <= 0) {
      throw new IllegalArgumentException(
          "n " + n + ", tolerance " + tolerance + ", maxIterations " + maxIterations);
    }
    this.n = n;
    this.tolerance = tolerance;
    this.maxIterations = maxIterations;
    y = new double[n];
    xPrev = new double[n];
    grad = new double[n];
  }

  /**
   * Solves in place: {@code x} holds the starting point on entry (it is projected first) and the
   * solution on exit. Returns true if the tolerance was met.
   */
  public boolean solve(
      final double[][] q, final double[] c, final double[] lo, final double[] hi, final double[] x) {
    double lip = 0.0;
    double cScale = 1.0;
    for (int i = 0; i < n; i++) {
      double row = 0.0;
      for (int j = 0; j < n; j++) {
        row += Math.abs(q[i][j]);
      }
      lip = Math.max(lip, row);
      cScale = Math.max(cScale, Math.abs(c[i]));
      if (lo[i] > hi[i]) {
        throw new IllegalArgumentException("lo " + lo[i] + " > hi " + hi[i] + " at " + i);
      }
      x[i] = clamp(x[i], lo[i], hi[i]);
    }
    iterations = 0;
    if (lip == 0.0) {
      // linear objective: the optimum is at a bound (or anywhere when c_i is zero)
      for (int i = 0; i < n; i++) {
        x[i] = c[i] > 0.0 ? lo[i] : (c[i] < 0.0 ? hi[i] : x[i]);
      }
      residual = 0.0;
      return true;
    }
    final double step = 1.0 / lip;
    final double stop = tolerance * cScale;
    System.arraycopy(x, 0, y, 0, n);
    System.arraycopy(x, 0, xPrev, 0, n);
    double t = 1.0;
    residual = Double.MAX_VALUE;
    while (iterations < maxIterations) {
      iterations++;
      gradient(q, c, y, grad);
      double restartDot = 0.0;
      for (int i = 0; i < n; i++) {
        xPrev[i] = x[i];
        x[i] = clamp(y[i] - step * grad[i], lo[i], hi[i]);
        restartDot += grad[i] * (x[i] - xPrev[i]);
      }
      residual = projectedResidual(q, c, lo, hi, x, lip);
      if (residual <= stop) {
        return true;
      }
      final double tNext = restartDot > 0.0 ? 1.0 : 0.5 * (1.0 + Math.sqrt(1.0 + 4.0 * t * t));
      final double momentum = restartDot > 0.0 ? 0.0 : (t - 1.0) / tNext;
      for (int i = 0; i < n; i++) {
        y[i] = x[i] + momentum * (x[i] - xPrev[i]);
      }
      t = tNext;
    }
    return false;
  }

  /** Iterations used by the last solve. */
  public int iterations() {
    return iterations;
  }

  /** Projected-gradient residual at the last iterate. */
  public double residual() {
    return residual;
  }

  /** Objective {@code 0.5 x'Qx + c'x}. */
  public static double objective(final double[][] q, final double[] c, final double[] x) {
    double f = 0.0;
    for (int i = 0; i < x.length; i++) {
      double qx = 0.0;
      for (int j = 0; j < x.length; j++) {
        qx += q[i][j] * x[j];
      }
      f += 0.5 * x[i] * qx + c[i] * x[i];
    }
    return f;
  }

  private double projectedResidual(
      final double[][] q,
      final double[] c,
      final double[] lo,
      final double[] hi,
      final double[] x,
      final double lip) {
    gradient(q, c, x, grad);
    double r = 0.0;
    for (int i = 0; i < n; i++) {
      final double p = clamp(x[i] - grad[i] / lip, lo[i], hi[i]);
      r = Math.max(r, Math.abs(x[i] - p) * lip);
    }
    return r;
  }

  private void gradient(final double[][] q, final double[] c, final double[] at, final double[] out) {
    for (int i = 0; i < n; i++) {
      double g = c[i];
      for (int j = 0; j < n; j++) {
        g += q[i][j] * at[j];
      }
      out[i] = g;
    }
  }

  private static double clamp(final double v, final double lo, final double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
