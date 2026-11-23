package io.ratesalgo.quoting;

import io.ratesalgo.hedge.FactorModel;
import io.ratesalgo.hedge.HedgeAvailability;
import java.nio.file.Path;

/**
 * The Round 5 strategy capabilities (docs/research/STRATEGY_V2.md), each behind its own switch so
 * it can be tested and run alone (ablation); every switch is off by default, so a {@link
 * QuotingConfig} that does not set them quotes and hedges exactly as before. Each capability has
 * a pre-registration draft (research/prereg/R5-*.yaml); none is on in any recorded run.
 *
 * <ul>
 *   <li>{@link #coverPricing} (R5-001; review Q1, Q3): win probability from the distance to the
 *       predicted cover ({@link CoverModel}), learned from wins, losses and covers; the quote
 *       maximises expected P&amp;L above break-even, with no fixed half-spread clamp.
 *   <li>{@link #reservationPrice} (R5-002; Q4): one inventory term from the DV01 ladder with the
 *       working hedges, the covariance and a risk aversion, capped at the cost of hedging; no
 *       separate skew, size term or axe.
 *   <li>{@link #factorCovariance}, {@link #factorHedging}, {@link #tickHedgeCost} (R5-003; Q2,
 *       Q5): the fitted covariance; level-then-slope hedging, whose cost is the quoted hedge cost;
 *       hedge cost in each instrument's ticks.
 *   <li>Internalisation (R5-004) is a hedger setting: {@code Hedger.Config.internalise}.
 *   <li>{@link #markoutStore} (R5-005; Q6): multi-horizon client markouts, persisted, shrunk to
 *       the tier prior, as the adverse cost.
 *   <li>{@link #tickRounding}, {@link #volatilityTtl} (R5-006; Q7, Q8): RFQ prices on the venue
 *       tick in the dealer's favour; time to live shortened as volatility rises. The pull at a
 *       fraction of the quoted distance and the rejection of a mismatched Done are the fix
 *       round's {@code QuotingConfig} switches.
 * </ul>
 */
public final class StrategyConfig {

  boolean coverPricing;
  boolean reservationPrice;
  boolean factorCovariance;
  boolean factorHedging;
  boolean tickHedgeCost;
  boolean markoutStore;
  boolean tickRounding;
  boolean volatilityTtl;
  double riskAversionPerUsd = 4e-7;
  double hedgeKappaPerUsd = 1e-5;
  double hedgeHalfSpreadTicks = 0.5;
  long minTtlNanos = 1_000_000_000L;
  int adverseHorizon = 2;
  double markoutShrinkage = 20.0;
  double markoutWindow = 500.0;
  int markoutClients = 4096;
  Path markoutFile;
  VenueTickTable ticks = VenueTickTable.simulatorDefaults();
  FactorModel factorModel;
  CoverModel coverModel;
  TierPriors tierPriors;
  HedgeAvailability hedgeAvailability = HedgeAvailability.ALL;
  double volatilityTenorYears = Double.NaN;

  /** Every capability off. */
  public StrategyConfig() {}

  /** Every capability of this class on, with the calibrated models: the Round 5 desk. */
  public static StrategyConfig allOn() {
    return new StrategyConfig()
        .coverPricing(true)
        .reservationPrice(true)
        .factorCovariance(true)
        .factorHedging(true)
        .tickHedgeCost(true)
        .markoutStore(true)
        .tickRounding(true)
        .volatilityTtl(true);
  }

  public StrategyConfig coverPricing(final boolean on) {
    coverPricing = on;
    return this;
  }

  public StrategyConfig reservationPrice(final boolean on) {
    reservationPrice = on;
    return this;
  }

  public StrategyConfig factorCovariance(final boolean on) {
    factorCovariance = on;
    return this;
  }

  /** Factor hedging; it plans with the factor model's covariance whatever {@link #factorCovariance} says. */
  public StrategyConfig factorHedging(final boolean on) {
    factorHedging = on;
    return this;
  }

  public StrategyConfig tickHedgeCost(final boolean on) {
    tickHedgeCost = on;
    return this;
  }

  public StrategyConfig markoutStore(final boolean on) {
    markoutStore = on;
    return this;
  }

  public StrategyConfig tickRounding(final boolean on) {
    tickRounding = on;
    return this;
  }

  public StrategyConfig volatilityTtl(final boolean on) {
    volatilityTtl = on;
    return this;
  }

  /**
   * Risk aversion of the reservation price, per USD: the inventory penalty is {@code (kappa / 2)
   * x' Sigma x} USD for a ladder x (USD per bp) and Sigma in bp squared per day. The default 4e-7
   * makes the penalty on the hedger's 20k USD trigger residual about equal to the cost of
   * hedging it (STRATEGY_V2.md Section 3).
   */
  public StrategyConfig riskAversionPerUsd(final double v) {
    riskAversionPerUsd = nonNegative(v, "riskAversionPerUsd");
    return this;
  }

  /** Risk aversion of the factor hedge planner, per USD (the key-rate planner's default 1e-5). */
  public StrategyConfig hedgeKappaPerUsd(final double v) {
    if (!(v > 0.0)) {
      throw new IllegalArgumentException("hedgeKappaPerUsd must be positive: " + v);
    }
    hedgeKappaPerUsd = v;
    return this;
  }

  /** Half-spread paid to cross a hedge instrument's book, in its ticks (0.5 for one tick wide). */
  public StrategyConfig hedgeHalfSpreadTicks(final double v) {
    hedgeHalfSpreadTicks = nonNegative(v, "hedgeHalfSpreadTicks");
    return this;
  }

  /** Shortest time to live {@link #volatilityTtl} may give a firm quote. */
  public StrategyConfig minTtlNanos(final long v) {
    if (v <= 0) {
      throw new IllegalArgumentException("minTtlNanos must be positive: " + v);
    }
    minTtlNanos = v;
    return this;
  }

  /** Which of {@link MarkoutStore#horizonNanos} is the adverse cost (default 30 s, three drift time constants). */
  public StrategyConfig adverseHorizonNanos(final long horizonNanos) {
    adverseHorizon = MarkoutStore.horizonIndex(horizonNanos);
    return this;
  }

  /** Markout store: shrinkage weight n0, memory in fills, client capacity. */
  public StrategyConfig markoutLearning(final double shrinkage, final double window, final int clients) {
    if (!(shrinkage >= 0.0) || !(window >= 1.0) || clients <= 0) {
      throw new IllegalArgumentException("markout learning " + shrinkage + ", " + window + ", " + clients);
    }
    markoutShrinkage = shrinkage;
    markoutWindow = window;
    markoutClients = clients;
    return this;
  }

  /** File the markout store is loaded from at start and saved to by {@code Desk.saveMarkouts}. */
  public StrategyConfig markoutFile(final Path file) {
    markoutFile = file;
    return this;
  }

  public StrategyConfig ticks(final VenueTickTable table) {
    ticks = table;
    return this;
  }

  public StrategyConfig factorModel(final FactorModel model) {
    factorModel = model;
    return this;
  }

  public StrategyConfig coverModel(final CoverModel model) {
    coverModel = model;
    return this;
  }

  public StrategyConfig tierPriors(final TierPriors priors) {
    tierPriors = priors;
    return this;
  }

  /** Which hedge instruments may be planned now; futures wait for a futures book. */
  public StrategyConfig hedgeAvailability(final HedgeAvailability availability) {
    hedgeAvailability = availability == null ? HedgeAvailability.ALL : availability;
    return this;
  }

  /**
   * Estimate each currency's realised volatility from the swap (or, without one, the bond) whose
   * tenor is nearest {@code years}, unless {@code QuotingConfig.volatilityReference} already names
   * an instrument. The desk resolves it from its instrument directory at start, so a preset need
   * not know instrument ids. NaN, the default, leaves volatility at the baseline.
   */
  public StrategyConfig volatilityReferenceTenor(final double years) {
    if (!(years > 0.0) && !Double.isNaN(years)) {
      throw new IllegalArgumentException("volatility reference tenor " + years);
    }
    volatilityTenorYears = years;
    return this;
  }

  public double volatilityReferenceTenor() {
    return volatilityTenorYears;
  }

  /** Whether any switch that changes the RFQ price is on. */
  public boolean pricingActive() {
    return coverPricing || reservationPrice || markoutStore || tickRounding || factorHedging || volatilityTtl;
  }

  public boolean coverPricing() {
    return coverPricing;
  }

  public boolean reservationPrice() {
    return reservationPrice;
  }

  public boolean factorCovariance() {
    return factorCovariance;
  }

  public boolean factorHedging() {
    return factorHedging;
  }

  public boolean tickHedgeCost() {
    return tickHedgeCost;
  }

  public boolean markoutStore() {
    return markoutStore;
  }

  public boolean tickRounding() {
    return tickRounding;
  }

  public boolean volatilityTtl() {
    return volatilityTtl;
  }

  public double riskAversionPerUsd() {
    return riskAversionPerUsd;
  }

  public double hedgeKappaPerUsd() {
    return hedgeKappaPerUsd;
  }

  public double hedgeHalfSpreadTicks() {
    return hedgeHalfSpreadTicks;
  }

  public VenueTickTable ticks() {
    return ticks;
  }

  public Path markoutFile() {
    return markoutFile;
  }

  public HedgeAvailability hedgeAvailability() {
    return hedgeAvailability;
  }

  /** The factor model in use: the one set, or the shipped calibration. */
  public FactorModel factorModelOrCalibrated() {
    if (factorModel == null) {
      factorModel = FactorModel.calibrated();
    }
    return factorModel;
  }

  /** A fresh cover model: the one set is used once; otherwise the calibrated prior. */
  CoverModel coverModelOrCalibrated() {
    return coverModel == null ? CoverModel.calibrated() : coverModel;
  }

  /** The tier priors in use: the ones set, or the calibrated ones. */
  public TierPriors tierPriorsOrCalibrated() {
    if (tierPriors == null) {
      tierPriors = TierPriors.calibrated();
    }
    return tierPriors;
  }

  private static double nonNegative(final double v, final String name) {
    if (!(v >= 0.0) || Double.isInfinite(v)) {
      throw new IllegalArgumentException(name + " must be non-negative: " + v);
    }
    return v;
  }
}
