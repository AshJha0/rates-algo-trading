package io.ratesalgo.hedge;

import io.ratesalgo.api.KeyRateTenors;

/**
 * Covariance of daily key-rate changes, in basis points squared. The default is a declared
 * assumption, not a fit (API_SPEC.md Section 14.2): daily volatilities of 6.0, 6.5, 6.8, 7.0, 7.0,
 * 6.8, 6.2 and 5.8 bp for 1Y to 30Y, and correlation {@code exp(-0.06 * |t_i - t_j|)} with t in
 * years. A fit from public par curves is planned with P5's research.
 */
public final class KeyRateCovariance {

  private static final int K = KeyRateTenors.COUNT;
  private static final double[] DEFAULT_VOL_BP = {6.0, 6.5, 6.8, 7.0, 7.0, 6.8, 6.2, 5.8};
  private static final double DEFAULT_DECAY = 0.06;

  private final double[][] sigma = new double[K][K];

  private KeyRateCovariance() {}

  /** The declared default. */
  public static KeyRateCovariance assumedDefault() {
    return of(DEFAULT_VOL_BP, DEFAULT_DECAY);
  }

  /** From per-pillar daily volatilities (bp) and an exponential correlation decay per year. */
  public static KeyRateCovariance of(final double[] volBp, final double decayPerYear) {
    if (volBp.length != K || decayPerYear < 0.0) {
      throw new IllegalArgumentException("vol length " + volBp.length + ", decay " + decayPerYear);
    }
    final KeyRateCovariance cov = new KeyRateCovariance();
    for (int i = 0; i < K; i++) {
      for (int j = 0; j < K; j++) {
        final double rho =
            Math.exp(-decayPerYear * Math.abs(KeyRateTenors.years(i) - KeyRateTenors.years(j)));
        cov.sigma[i][j] = volBp[i] * volBp[j] * rho;
      }
    }
    return cov;
  }

  /**
   * From a full matrix in bp squared per day (copied), for a fitted model such as {@link
   * FactorModel} (review Q2). The matrix must be symmetric with a non-negative diagonal.
   */
  public static KeyRateCovariance ofMatrix(final double[][] bp2) {
    if (bp2.length != K) {
      throw new IllegalArgumentException("need a " + K + " x " + K + " matrix");
    }
    for (int i = 0; i < K; i++) {
      if (bp2[i].length != K || !(bp2[i][i] >= 0.0)) {
        throw new IllegalArgumentException("row " + i);
      }
    }
    final KeyRateCovariance cov = new KeyRateCovariance();
    for (int i = 0; i < K; i++) {
      for (int j = 0; j < K; j++) {
        if (!Double.isFinite(bp2[i][j]) || Math.abs(bp2[i][j] - bp2[j][i]) > 1e-9 * (1.0 + Math.abs(bp2[i][j]))) {
          throw new IllegalArgumentException("not symmetric at (" + i + ", " + j + ")");
        }
        cov.sigma[i][j] = bp2[i][j];
      }
    }
    return cov;
  }

  /** Daily volatility of pillar {@code i}, bp. */
  public double volBp(final int i) {
    return Math.sqrt(sigma[i][i]);
  }

  /** Correlation of pillars {@code i} and {@code j}; 0 when either has no variance. */
  public double correlation(final int i, final int j) {
    final double d = Math.sqrt(sigma[i][i] * sigma[j][j]);
    return d == 0.0 ? 0.0 : sigma[i][j] / d;
  }

  /** Entry (i, j) in bp squared. */
  public double get(final int i, final int j) {
    return sigma[i][j];
  }

  /** {@code a' Sigma b} for two ladders held in arrays of length {@link KeyRateTenors#COUNT}. */
  public double quadratic(final double[] a, final double[] b) {
    double s = 0.0;
    for (int i = 0; i < K; i++) {
      double row = 0.0;
      for (int j = 0; j < K; j++) {
        row += sigma[i][j] * b[j];
      }
      s += a[i] * row;
    }
    return s;
  }
}
