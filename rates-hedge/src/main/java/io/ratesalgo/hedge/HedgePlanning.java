package io.ratesalgo.hedge;

import io.ratesalgo.api.Dv01Vector;

/**
 * What the {@link Hedger} needs from a planner: a plan of signed lots per hedge instrument for a
 * residual ladder. {@link HedgePlanner} (key-rate variance against cost, one quadratic programme)
 * and {@link FactorHedgePlanner} (level fully, then slope when it pays; review Q2) are the two.
 * Every call is allocation-free.
 */
public interface HedgePlanning {

  /** Plans a hedge of {@code residual}; false when the plan is only approximate. */
  boolean plan(Dv01Vector residual);

  /** Planned lots of hedge instrument {@code j} of the universe (signed: positive buys). */
  double lots(int j);

  /** Modelled cost of the last plan traded at once, currency units. */
  double plannedCost();

  /** The instruments planned over. */
  HedgeUniverse universe();

  /** The covariance the plan and the hedger's residual are measured with, bp squared per day. */
  KeyRateCovariance covariance();
}
