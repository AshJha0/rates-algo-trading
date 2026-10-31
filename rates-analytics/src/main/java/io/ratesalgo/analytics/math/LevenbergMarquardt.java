package io.ratesalgo.analytics.math;

/**
 * Levenberg-Marquardt for small dense nonlinear least squares, min over x of the sum of r_i(x)^2,
 * with a user-supplied Jacobian (Marquardt 1963; damping updated as in Nielsen 1999). Storage is
 * allocated once; {@link #minimise} does not allocate.
 *
 * <p>Used for the global curve bootstrap (square systems, where it reduces to damped Newton and
 * converges quadratically) and for fitting Nelson-Siegel-Svensson curves (overdetermined).
 */
public final class LevenbergMarquardt {

  /** The residuals and their Jacobian. */
  public interface Problem {
    /** Number of residuals. */
    int residualCount();

    /** Number of parameters. */
    int parameterCount();

    /** Writes r(x) into {@code residuals}. */
    void residuals(double[] x, double[] residuals);

    /** Writes dr_i/dx_j into {@code jacobian}, row-major, residuals by parameters. */
    void jacobian(double[] x, double[] jacobian);
  }

  private final int maxResiduals;
  private final int maxParameters;
  private final double[] r;
  private final double[] rTrial;
  private final double[] j;
  private final double[] jtj;
  private final double[] jtr;
  private final double[] a;
  private final double[] step;
  private final double[] xTrial;
  private final LuSolver lu;
  private int iterations;

  /** Allocates storage for problems up to the given sizes. */
  public LevenbergMarquardt(final int maxResiduals, final int maxParameters) {
    this.maxResiduals = maxResiduals;
    this.maxParameters = maxParameters;
    this.r = new double[maxResiduals];
    this.rTrial = new double[maxResiduals];
    this.j = new double[maxResiduals * maxParameters];
    this.jtj = new double[maxParameters * maxParameters];
    this.jtr = new double[maxParameters];
    this.a = new double[maxParameters * maxParameters];
    this.step = new double[maxParameters];
    this.xTrial = new double[maxParameters];
    this.lu = new LuSolver(maxParameters);
  }

  /**
   * Minimises in place from the starting point {@code x}.
   *
   * @param problem residuals and Jacobian
   * @param x start, overwritten with the solution
   * @param residualTolerance stop when every |r_i| is at or below this
   * @param stepTolerance stop when every |step_j| is at or below this
   * @param maxIterations iteration limit
   * @return the largest |r_i| at the solution
   * @throws IllegalStateException if the iteration limit is reached without meeting either
   *     tolerance
   */
  public double minimise(
      final Problem problem,
      final double[] x,
      final double residualTolerance,
      final double stepTolerance,
      final int maxIterations) {
    final int m = problem.residualCount();
    final int n = problem.parameterCount();
    if (m > maxResiduals || n > maxParameters || m < n) {
      throw new IllegalArgumentException(
          "problem size " + m + " by " + n + " outside capacity " + maxResiduals + " by "
              + maxParameters + " or underdetermined");
    }
    problem.residuals(x, r);
    double cost = sumSquares(r, m);
    double lambda = 1e-3;
    double nu = 2.0;
    for (iterations = 0; iterations < maxIterations; iterations++) {
      if (maxAbs(r, m) <= residualTolerance) {
        return maxAbs(r, m);
      }
      problem.jacobian(x, j);
      double maxDiag = 0.0;
      for (int p = 0; p < n; p++) {
        double g = 0.0;
        for (int i = 0; i < m; i++) {
          g += j[i * n + p] * r[i];
        }
        jtr[p] = g;
        for (int q = 0; q < n; q++) {
          double s = 0.0;
          for (int i = 0; i < m; i++) {
            s += j[i * n + p] * j[i * n + q];
          }
          jtj[p * n + q] = s;
        }
        maxDiag = Math.max(maxDiag, jtj[p * n + p]);
      }
      if (iterations == 0) {
        lambda = 1e-12 * maxDiag;
      }
      boolean accepted = false;
      while (!accepted) {
        System.arraycopy(jtj, 0, a, 0, n * n);
        for (int p = 0; p < n; p++) {
          a[p * n + p] += lambda * Math.max(jtj[p * n + p], 1e-300);
          step[p] = -jtr[p];
        }
        lu.factor(a, n);
        lu.solve(step);
        double maxStep = 0.0;
        for (int p = 0; p < n; p++) {
          xTrial[p] = x[p] + step[p];
          maxStep = Math.max(maxStep, Math.abs(step[p]));
        }
        problem.residuals(xTrial, rTrial);
        final double trialCost = sumSquares(rTrial, m);
        if (Double.isFinite(trialCost) && trialCost <= cost) {
          System.arraycopy(xTrial, 0, x, 0, n);
          System.arraycopy(rTrial, 0, r, 0, m);
          cost = trialCost;
          lambda = Math.max(lambda / 3.0, 1e-300);
          nu = 2.0;
          accepted = true;
          if (maxStep <= stepTolerance) {
            return maxAbs(r, m);
          }
        } else {
          lambda *= nu;
          nu *= 2.0;
          if (lambda > 1e100 || maxStep <= stepTolerance) {
            return maxAbs(r, m);
          }
        }
      }
    }
    if (maxAbs(r, m) <= residualTolerance) {
      return maxAbs(r, m);
    }
    throw new IllegalStateException(
        "Levenberg-Marquardt: no convergence in " + maxIterations + " iterations; max residual "
            + maxAbs(r, m));
  }

  /** Iterations used by the last call. */
  public int iterations() {
    return iterations;
  }

  private static double sumSquares(final double[] v, final int m) {
    double s = 0.0;
    for (int i = 0; i < m; i++) {
      s += v[i] * v[i];
    }
    return s;
  }

  private static double maxAbs(final double[] v, final int m) {
    double s = 0.0;
    for (int i = 0; i < m; i++) {
      s = Math.max(s, Math.abs(v[i]));
    }
    return s;
  }
}
