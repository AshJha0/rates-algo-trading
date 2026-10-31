package io.ratesalgo.hedge;

import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.risk.RiskEngine;

/**
 * Chooses hedge quantities that trade residual key-rate risk against transaction cost
 * (API_SPEC.md Section 14.1). With r the residual PV01 ladder, H the per-lot PV01 ladders of the
 * hedge instruments, Sigma the key-rate covariance (bp squared) and x the lots traded:
 *
 * <pre>
 *   minimise  0.5 kappa (r + Hx)' Sigma (r + Hx) + sum_j ( a_j |x_j| + b_j x_j^2 )
 *   subject to |x_j| &lt;= maxLots_j, and x_j = 0 for instruments in another currency
 * </pre>
 *
 * The absolute value is removed by writing x = p - m with p, m &gt;= 0, which gives a convex
 * quadratic programme with box constraints in 2n variables, solved by {@link BoxQpSolver}. At the
 * optimum at most one of p_j, m_j is non-zero when a_j &gt; 0. Kappa is risk aversion per unit of
 * currency: the planner is indifferent between one unit of cost and 1/kappa units of half-variance.
 *
 * <p>Allocation-free after construction.
 */
public final class HedgePlanner {

  private static final int K = KeyRateTenors.COUNT;

  private final HedgeUniverse universe;
  private final RiskEngine risk;
  private final KeyRateCovariance covariance;
  private final HedgeCostModel costs;
  private final double kappa;
  private final int m;
  private final BoxQpSolver solver;

  private final double[][] h;
  private final double[][] sh;
  private final double[][] q;
  private final double[] c;
  private final double[] lo;
  private final double[] hi;
  private final double[] x;
  private final double[] lots;
  private final double[] r = new double[K];
  private final double[] work = new double[K];
  private boolean converged;

  /** A planner over {@code universe} with risk aversion {@code kappa}. */
  public HedgePlanner(
      final HedgeUniverse universe,
      final RiskEngine risk,
      final KeyRateCovariance covariance,
      final HedgeCostModel costs,
      final double kappa,
      final double tolerance,
      final int maxIterations) {
    if (!(kappa > 0.0)) {
      throw new IllegalArgumentException("kappa must be positive: " + kappa);
    }
    this.universe = universe;
    this.risk = risk;
    this.covariance = covariance;
    this.costs = costs;
    this.kappa = kappa;
    this.m = universe.size();
    solver = new BoxQpSolver(2 * m, tolerance, maxIterations);
    h = new double[m][K];
    sh = new double[m][K];
    q = new double[2 * m][2 * m];
    c = new double[2 * m];
    lo = new double[2 * m];
    hi = new double[2 * m];
    x = new double[2 * m];
    lots = new double[m];
  }

  /** Plans a hedge of {@code residual}; read the result with {@link #lots(int)}. */
  public boolean plan(final Dv01Vector residual) {
    residual.copyTo(r);
    for (int j = 0; j < m; j++) {
      final int id = universe.instrumentId(j);
      final Instrument inst = risk.directory().byId(id);
      final double lotMajor = inst.major(universe.lotMinor(j));
      // an instrument without a finite cost is not traded (and kept out of the solver's scale)
      final boolean eligible =
          inst.currency() == residual.currency() && Double.isFinite(costs.linearPerLot(id));
      for (int k = 0; k < K; k++) {
        h[j][k] = eligible ? risk.unitPv01(id, k) * lotMajor : 0.0;
      }
      lo[j] = 0.0;
      lo[j + m] = 0.0;
      hi[j] = eligible ? universe.maxLots(j) : 0.0;
      hi[j + m] = hi[j];
    }
    // sh[j] = Sigma h_j
    for (int j = 0; j < m; j++) {
      for (int a = 0; a < K; a++) {
        double s = 0.0;
        for (int b = 0; b < K; b++) {
          s += covariance.get(a, b) * h[j][b];
        }
        sh[j][a] = s;
      }
    }
    for (int i = 0; i < m; i++) {
      double g = 0.0;
      for (int k = 0; k < K; k++) {
        g += sh[i][k] * r[k];
      }
      final int id = universe.instrumentId(i);
      final double a = hi[i] > 0.0 ? costs.linearPerLot(id) : 0.0;
      final double b = costs.impactPerLot2(id);
      c[i] = kappa * g + a;
      c[i + m] = -kappa * g + a;
      for (int j = 0; j < m; j++) {
        double gij = 0.0;
        for (int k = 0; k < K; k++) {
          gij += h[i][k] * sh[j][k];
        }
        final double v = kappa * gij;
        q[i][j] = v;
        q[i + m][j + m] = v;
        q[i][j + m] = -v;
        q[i + m][j] = -v;
      }
      q[i][i] += 2.0 * b;
      q[i + m][i + m] += 2.0 * b;
      x[i] = 0.0;
      x[i + m] = 0.0;
    }
    converged = solver.solve(q, c, lo, hi, x);
    for (int j = 0; j < m; j++) {
      lots[j] = x[j] - x[j + m];
    }
    return converged;
  }

  /** Planned lots of hedge instrument {@code j} (signed: positive buys). */
  public double lots(final int j) {
    return lots[j];
  }

  /** Whether the last plan met the solver tolerance. */
  public boolean converged() {
    return converged;
  }

  /** Solver iterations in the last plan. */
  public int iterations() {
    return solver.iterations();
  }

  /** {@code r' Sigma r} of a ladder, in currency squared. */
  public double variance(final Dv01Vector ladder) {
    ladder.copyTo(work);
    return covariance.quadratic(work, work);
  }

  /** Residual ladder after trading {@code planLots} (one entry per hedge instrument). */
  public void residualAfter(final Dv01Vector residual, final double[] planLots, final Dv01Vector out) {
    out.copyFrom(residual);
    for (int j = 0; j < m; j++) {
      final int id = universe.instrumentId(j);
      final Instrument inst = risk.directory().byId(id);
      if (inst.currency() != residual.currency()) {
        continue;
      }
      final double lotMajor = inst.major(universe.lotMinor(j));
      for (int k = 0; k < K; k++) {
        out.add(k, risk.unitPv01(id, k) * lotMajor * planLots[j]);
      }
    }
  }

  /** Modelled cost of trading {@code planLots} at once. */
  public double cost(final double[] planLots) {
    double s = 0.0;
    for (int j = 0; j < m; j++) {
      s += costs.cost(universe.instrumentId(j), planLots[j]);
    }
    return s;
  }

  /** Copies the last plan into {@code out}. */
  public void copyLots(final double[] out) {
    System.arraycopy(lots, 0, out, 0, m);
  }

  public HedgeUniverse universe() {
    return universe;
  }

  public KeyRateCovariance covariance() {
    return covariance;
  }

  public double kappa() {
    return kappa;
  }
}
