package io.ratesalgo.hedge;

import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.risk.RiskEngine;

/**
 * Factor hedging (review 2026-11-23, Q2): hedge the level factor fully, then the slope factor when
 * that pays for its cost, with the instruments available now.
 *
 * <p>Plain idea. Under the fitted model one factor, level, carries about 89 % of the daily
 * variance summed over the seven tenors (13.3 bp against 4.5 bp for slope and 1.5 bp for
 * curvature), slope most of the rest. A hedge that removes the level exposure
 * with one instrument removes most of the risk whatever the tenor; a second instrument can then
 * remove slope too, at the cost of a second crossing. Curvature and the idiosyncratic terms are
 * left and reported.
 *
 * <p>Formally, with r the residual ladder (currency per bp at each pillar), h_j the ladder of one
 * lot of instrument j, e = L'r and g_j = L'h_j its factor exposures (currency per bp of factor),
 * the planner evaluates every candidate that makes the level exposure exactly zero:
 *
 * <pre>
 *   level only, one instrument j:       x_j = -e_level / g_j,level
 *   level and slope, a pair (j, k):     [g_j,level g_k,level; g_j,slope g_k,slope] (x_j, x_k)' = -(e_level, e_slope)'
 *   J(x) = 0.5 kappa ( (r + Hx)' Sigma (r + Hx) + sum_j (x_j dv01_j sd_basis,j)^2 ) + sum_j cost_j(x_j)
 * </pre>
 *
 * and keeps the one with the lowest J within each instrument's lot limit. Sigma is the factor
 * model's covariance (idiosyncratic terms included) and sd_basis is the futures-basis sd for a
 * future and 0 for cash. Because every candidate zeroes level, level is always hedged fully; slope
 * is hedged only when the variance it removes, valued at kappa, exceeds the extra cost. If no
 * candidate fits the lot limits, the cheapest instrument per unit of level is traded up to its
 * limit and {@link #levelHedged()} is false.
 *
 * <p>Instruments in another currency, without a finite cost, or not {@link HedgeAvailability
 * available} (a future with no book yet) are not used. With m instruments the planner evaluates m
 * + m(m-1)/2 candidates, each an 8-by-8 quadratic form. Allocation-free after construction.
 */
public final class FactorHedgePlanner implements HedgePlanning {

  private static final int K = KeyRateTenors.COUNT;
  private static final int F = FactorModel.FACTORS;

  private final HedgeUniverse universe;
  private final RiskEngine risk;
  private final FactorModel model;
  private final HedgeCostModel costs;
  private final double kappa;
  private final HedgeAvailability availability;
  private final int m;

  private final double[][] h;
  private final double[][] g;
  private final double[] basisVarPerLot2;
  private final boolean[] eligible;
  private final double[] lots;
  private final double[] r = new double[K];
  private final double[] rr = new double[K];
  private final double[] before = new double[F];
  private final double[] after = new double[F];
  private boolean converged;
  private boolean levelHedged;
  private boolean slopeHedged;
  private double plannedCost;
  private double objective;

  /** A planner over {@code universe} with risk aversion {@code kappa} per unit of currency. */
  public FactorHedgePlanner(
      final HedgeUniverse universe,
      final RiskEngine risk,
      final FactorModel model,
      final HedgeCostModel costs,
      final double kappa,
      final HedgeAvailability availability) {
    if (!(kappa > 0.0)) {
      throw new IllegalArgumentException("kappa must be positive: " + kappa);
    }
    this.universe = universe;
    this.risk = risk;
    this.model = model;
    this.costs = costs;
    this.kappa = kappa;
    this.availability = availability == null ? HedgeAvailability.ALL : availability;
    m = universe.size();
    h = new double[m][K];
    g = new double[m][F];
    basisVarPerLot2 = new double[m];
    eligible = new boolean[m];
    lots = new double[m];
  }

  @Override
  public boolean plan(final Dv01Vector residual) {
    residual.copyTo(r);
    model.exposures(r, before);
    for (int j = 0; j < m; j++) {
      final int id = universe.instrumentId(j);
      final Instrument inst = risk.directory().byId(id);
      lots[j] = 0.0;
      eligible[j] =
          inst != null
              && inst.currency() == residual.currency()
              && Double.isFinite(costs.linearPerLot(id))
              && availability.available(id);
      final double lotMajor = inst == null ? 0.0 : inst.major(universe.lotMinor(j));
      double parallel = 0.0;
      for (int k = 0; k < K; k++) {
        h[j][k] = eligible[j] ? risk.unitPv01(id, k) * lotMajor : 0.0;
        parallel += h[j][k];
      }
      model.exposures(h[j], g[j]);
      final double basisSd = inst != null && inst.type() == ProductType.FUTURE ? model.basisSdBp() : 0.0;
      basisVarPerLot2[j] = parallel * parallel * basisSd * basisSd;
    }
    double best = Double.POSITIVE_INFINITY;
    int bestA = -1;
    int bestB = -1;
    double bestXa = 0.0;
    double bestXb = 0.0;
    for (int j = 0; j < m; j++) {
      if (!eligible[j] || g[j][FactorModel.LEVEL] == 0.0) {
        continue;
      }
      final double x = -before[FactorModel.LEVEL] / g[j][FactorModel.LEVEL];
      if (Math.abs(x) > universe.maxLots(j)) {
        continue;
      }
      final double v = evaluate(j, x, -1, 0.0);
      if (v < best) {
        best = v;
        bestA = j;
        bestB = -1;
        bestXa = x;
        bestXb = 0.0;
      }
    }
    for (int j = 0; j < m; j++) {
      if (!eligible[j]) {
        continue;
      }
      for (int k = j + 1; k < m; k++) {
        if (!eligible[k]) {
          continue;
        }
        final double a = g[j][FactorModel.LEVEL];
        final double b = g[k][FactorModel.LEVEL];
        final double c = g[j][FactorModel.SLOPE];
        final double d = g[k][FactorModel.SLOPE];
        final double det = a * d - b * c;
        // two instruments with nearly the same level-to-slope mix cannot separate the factors
        if (Math.abs(det) <= 1e-6 * (Math.abs(a * d) + Math.abs(b * c))) {
          continue;
        }
        final double e0 = before[FactorModel.LEVEL];
        final double e1 = before[FactorModel.SLOPE];
        final double xj = (-e0 * d + e1 * b) / det;
        final double xk = (-e1 * a + e0 * c) / det;
        if (Math.abs(xj) > universe.maxLots(j) || Math.abs(xk) > universe.maxLots(k)) {
          continue;
        }
        final double v = evaluate(j, xj, k, xk);
        if (v < best) {
          best = v;
          bestA = j;
          bestB = k;
          bestXa = xj;
          bestXb = xk;
        }
      }
    }
    converged = bestA >= 0;
    if (!converged) {
      // nothing zeroes level inside the limits: trade the cheapest level hedge as far as allowed
      double cheapest = Double.POSITIVE_INFINITY;
      for (int j = 0; j < m; j++) {
        final double gl = Math.abs(g[j][FactorModel.LEVEL]);
        if (!eligible[j] || gl == 0.0) {
          continue;
        }
        final double perLevel = costs.linearPerLot(universe.instrumentId(j)) / gl;
        if (perLevel < cheapest) {
          cheapest = perLevel;
          bestA = j;
        }
      }
      if (bestA >= 0) {
        final double x = -before[FactorModel.LEVEL] / g[bestA][FactorModel.LEVEL];
        bestXa = Math.max(-universe.maxLots(bestA), Math.min(universe.maxLots(bestA), x));
        best = evaluate(bestA, bestXa, -1, 0.0);
      }
    }
    if (bestA >= 0) {
      lots[bestA] = bestXa;
    }
    if (bestB >= 0) {
      lots[bestB] = bestXb;
    }
    objective = bestA >= 0 ? best : 0.5 * kappa * model.covariance().quadratic(r, r);
    plannedCost = 0.0;
    for (int j = 0; j < m; j++) {
      if (lots[j] != 0.0) {
        plannedCost += costs.cost(universe.instrumentId(j), lots[j]);
      }
    }
    residualAfterPlan();
    levelHedged = converged;
    slopeHedged = converged && bestB >= 0;
    return converged;
  }

  @Override
  public double lots(final int j) {
    return lots[j];
  }

  @Override
  public double plannedCost() {
    return plannedCost;
  }

  @Override
  public HedgeUniverse universe() {
    return universe;
  }

  @Override
  public KeyRateCovariance covariance() {
    return model.covariance();
  }

  /** The factor model planned with. */
  public FactorModel model() {
    return model;
  }

  /** Whether the last plan met its target: level exposure zero within the lot limits. */
  public boolean converged() {
    return converged;
  }

  /** Whether the last plan zeroed the level exposure. */
  public boolean levelHedged() {
    return levelHedged;
  }

  /** Whether the last plan also zeroed the slope exposure. */
  public boolean slopeHedged() {
    return slopeHedged;
  }

  /** The last plan's objective J, currency units. */
  public double objective() {
    return objective;
  }

  /** Exposure of the residual to factor {@code f} before the last plan, currency per bp of factor. */
  public double exposureBefore(final int f) {
    return before[f];
  }

  /** Exposure to factor {@code f} after the last plan, currency per bp of factor. */
  public double exposureAfter(final int f) {
    return after[f];
  }

  /** Daily sd left in factor {@code f} after the last plan, currency. */
  public double residualSd(final int f) {
    return Math.abs(after[f]) * model.factorSdBp(f);
  }

  /** Daily sd of the idiosyncratic terms left after the last plan, currency. */
  public double residualIdioSd() {
    return Math.sqrt(model.idioVariance(rr));
  }

  private void residualAfterPlan() {
    System.arraycopy(r, 0, rr, 0, K);
    for (int j = 0; j < m; j++) {
      if (lots[j] != 0.0) {
        for (int k = 0; k < K; k++) {
          rr[k] += lots[j] * h[j][k];
        }
      }
    }
    model.exposures(rr, after);
  }

  private double evaluate(final int a, final double xa, final int b, final double xb) {
    for (int k = 0; k < K; k++) {
      rr[k] = r[k] + xa * h[a][k] + (b >= 0 ? xb * h[b][k] : 0.0);
    }
    double variance = model.covariance().quadratic(rr, rr) + xa * xa * basisVarPerLot2[a];
    double cost = costs.cost(universe.instrumentId(a), xa);
    if (b >= 0) {
      variance += xb * xb * basisVarPerLot2[b];
      cost += costs.cost(universe.instrumentId(b), xb);
    }
    return 0.5 * kappa * variance + cost;
  }
}
