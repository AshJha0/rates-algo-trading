package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.math.LevenbergMarquardt;
import java.util.Arrays;

/**
 * The Nelson-Siegel-Svensson zero curve (Svensson 1994, NBER Working Paper 4871), the form the
 * Bundesbank publishes daily for Bunds:
 *
 * <pre>
 *   z(T) = b0 + b1 (1 - e^{-T/t1}) / (T/t1)
 *            + b2 [(1 - e^{-T/t1}) / (T/t1) - e^{-T/t1}]
 *            + b3 [(1 - e^{-T/t2}) / (T/t2) - e^{-T/t2}]
 * </pre>
 *
 * with T in years and z in the units of the b's (the Bundesbank uses percent). Parameters are
 * {b0, b1, b2, b3, t1, t2}. {@link #fit} fits them to observed zero or par yields by
 * Levenberg-Marquardt for relative-value screens: rich or cheap is the observed yield minus z(T).
 */
public final class NelsonSiegelSvensson {

  private final double[] p;

  /** Creates the curve from {b0, b1, b2, b3, t1, t2}; the taus must be positive. */
  public NelsonSiegelSvensson(final double[] parameters) {
    if (parameters.length != 6 || !(parameters[4] > 0) || !(parameters[5] > 0)) {
      throw new IllegalArgumentException("need six parameters with positive taus: "
          + Arrays.toString(parameters));
    }
    this.p = Arrays.copyOf(parameters, 6);
  }

  /** Copy of the parameters. */
  public double[] parameters() {
    return Arrays.copyOf(p, 6);
  }

  /** Zero rate at maturity T years; the limit b0 + b1 at T = 0. */
  public double zero(final double t) {
    return value(p, t);
  }

  static double value(final double[] p, final double t) {
    if (t <= 0.0) {
      return p[0] + p[1];
    }
    final double x1 = t / p[4];
    final double x2 = t / p[5];
    final double e1 = Math.exp(-x1);
    final double e2 = Math.exp(-x2);
    final double f1 = (1.0 - e1) / x1;
    final double f2 = (1.0 - e2) / x2;
    return p[0] + p[1] * f1 + p[2] * (f1 - e1) + p[3] * (f2 - e2);
  }

  /**
   * Least-squares fit to (maturity, yield) points from a starting guess. Converges when the step
   * is below 1e-12 or the residuals below 1e-12; throws if not within 500 iterations.
   *
   * @return the fitted curve and, through {@code rms}, the root-mean-square residual
   */
  public static NelsonSiegelSvensson fit(
      final double[] maturities, final double[] yields, final double[] start, final double[] rms) {
    final int m = maturities.length;
    final double[] x = Arrays.copyOf(start, 6);
    final LevenbergMarquardt lm = new LevenbergMarquardt(Math.max(m, 6), 6);
    final LevenbergMarquardt.Problem problem =
        new LevenbergMarquardt.Problem() {
          @Override
          public int residualCount() {
            return m;
          }

          @Override
          public int parameterCount() {
            return 6;
          }

          @Override
          public void residuals(final double[] q, final double[] r) {
            for (int i = 0; i < m; i++) {
              r[i] = q[4] > 0 && q[5] > 0 ? value(q, maturities[i]) - yields[i] : 1e6;
            }
          }

          @Override
          public void jacobian(final double[] q, final double[] j) {
            final double[] w = Arrays.copyOf(q, 6);
            for (int k = 0; k < 6; k++) {
              final double h = 1e-7 * Math.max(1.0, Math.abs(q[k]));
              w[k] = q[k] + h;
              final double[] up = new double[m];
              residuals(w, up);
              w[k] = q[k] - h;
              final double[] dn = new double[m];
              residuals(w, dn);
              w[k] = q[k];
              for (int i = 0; i < m; i++) {
                j[i * 6 + k] = (up[i] - dn[i]) / (2.0 * h);
              }
            }
          }
        };
    lm.minimise(problem, x, 1e-12, 1e-12, 500);
    if (rms != null && rms.length > 0) {
      double s = 0.0;
      for (int i = 0; i < m; i++) {
        final double r = value(x, maturities[i]) - yields[i];
        s += r * r;
      }
      rms[0] = Math.sqrt(s / m);
    }
    return new NelsonSiegelSvensson(x);
  }
}
