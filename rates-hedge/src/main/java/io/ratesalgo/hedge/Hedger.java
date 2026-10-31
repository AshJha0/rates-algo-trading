package io.ratesalgo.hedge;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchListener;
import io.ratesalgo.risk.RiskEngine;

/**
 * Staged, cost-aware hedging of one currency's residual key-rate risk (API_SPEC.md Section 14).
 *
 * <p>On each {@link #onTimer} the hedger measures the residual: the risk engine's ladder plus the
 * PV01 of parent orders already working. If its standard deviation (one-day, currency units) is
 * below the trigger, nothing happens and the episode ends. Otherwise {@link HedgePlanner} plans the
 * hedge and the hedger sends part of it to the router through {@link HedgeOrderSink}: the first
 * tranche fraction at the start of an episode, then {@code 1 - 2^(-dt / halfLife)} of the
 * remaining plan per timer, so the residual decays toward the plan with the configured half-life.
 * Every parent passes {@link RiskEngine#checkOrder} first and reserves its quantity until filled
 * or cancelled.
 *
 * <p>Also an {@link AxeSource}: while an episode is active, a client trade that moves the book
 * toward the hedge earns a price improvement proportional to its correlation with the needed
 * hedge, capped at {@code axeMaxBp}. Allocation-free after construction.
 */
public final class Hedger implements AxeSource, KillSwitchListener {

  private static final int K = KeyRateTenors.COUNT;

  /** Tunable settings; every value is a declared assumption (API_SPEC.md Section 14.2). */
  public static final class Config {
    double firstTranche = 0.5;
    long halfLifeNanos = 30_000_000_000L;
    double triggerStdDev = 10_000.0;
    double axeMaxBp = 0.25;
    double limitBp = 2.0;
    int maxParents = 256;
    long parentIdBase = 1L << 40;

    /** Fraction of the plan sent at the start of an episode. */
    public Config firstTranche(final double f) {
      if (!(f > 0.0 && f <= 1.0)) {
        throw new IllegalArgumentException("first tranche " + f);
      }
      firstTranche = f;
      return this;
    }

    /** Half-life of the remaining plan after the first tranche. */
    public Config halfLifeNanos(final long nanos) {
      if (nanos <= 0) {
        throw new IllegalArgumentException("half-life " + nanos);
      }
      halfLifeNanos = nanos;
      return this;
    }

    /** One-day residual standard deviation, in currency units, below which nothing is hedged. */
    public Config triggerStdDev(final double v) {
      if (!(v >= 0.0)) {
        throw new IllegalArgumentException("trigger " + v);
      }
      triggerStdDev = v;
      return this;
    }

    /** Largest axe improvement in basis points. */
    public Config axeMaxBp(final double bp) {
      if (!(bp >= 0.0)) {
        throw new IllegalArgumentException("axe " + bp);
      }
      axeMaxBp = bp;
      return this;
    }

    /** Parent limit price distance from arrival mid, in basis points of value. */
    public Config limitBp(final double bp) {
      if (!(bp > 0.0)) {
        throw new IllegalArgumentException("limit " + bp);
      }
      limitBp = bp;
      return this;
    }

    /** Parent order ids start above this value. */
    public Config parentIdBase(final long base) {
      parentIdBase = base;
      return this;
    }
  }

  private final CurrencyCode currency;
  private final HedgePlanner planner;
  private final RiskEngine risk;
  private final HedgeOrderSink sink;
  private final FairValueSource fair;
  private final HedgeAnalytics analytics;
  private final Clock clock;
  private final Config config;
  private final HedgeUniverse universe;

  private final long[] parentId;
  private final int[] parentInstrument;
  private final long[] parentRemaining;
  private final long[] parentArrival;
  private int parentCount;
  private long nextParentId;

  private final Dv01Vector residual;
  private final double[] r = new double[K];
  private final double[] sr = new double[K];
  private final double[] u = new double[K];
  private double residualVariance;
  private boolean episode;
  private long lastHedgeNanos;
  private long parentsSent;
  private long parentsRefused;

  /** A hedger for {@code currency}. */
  public Hedger(
      final CurrencyCode currency,
      final HedgePlanner planner,
      final RiskEngine risk,
      final HedgeOrderSink sink,
      final FairValueSource fair,
      final HedgeAnalytics analytics,
      final Clock clock,
      final Config config) {
    this.currency = currency;
    this.planner = planner;
    this.risk = risk;
    this.sink = sink;
    this.fair = fair;
    this.analytics = analytics;
    this.clock = clock;
    this.config = config;
    this.universe = planner.universe();
    parentId = new long[config.maxParents];
    parentInstrument = new int[config.maxParents];
    parentRemaining = new long[config.maxParents];
    parentArrival = new long[config.maxParents];
    nextParentId = config.parentIdBase;
    residual = new Dv01Vector(currency);
  }

  /** Default configuration. */
  public static Config config() {
    return new Config();
  }

  /** Measures the residual and sends the next tranche if one is due. Returns parents sent. */
  public int onTimer(final long epochNanos) {
    measure();
    analytics.recordResidual(epochNanos, residualVariance, residual.parallel());
    if (!risk.killSwitch().isActive() || Math.sqrt(residualVariance) < config.triggerStdDev) {
      episode = false;
      return 0;
    }
    planner.plan(residual);
    final boolean first = !episode;
    final double fraction;
    if (episode) {
      final double dt = Math.max(0L, epochNanos - lastHedgeNanos);
      fraction = 1.0 - Math.pow(2.0, -dt / config.halfLifeNanos);
    } else {
      fraction = config.firstTranche;
    }
    episode = true;
    lastHedgeNanos = epochNanos;
    int sent = 0;
    for (int j = 0; j < universe.size(); j++) {
      final double planned = planner.lots(j);
      final long whole = Math.round(planned);
      if (whole == 0) {
        continue;
      }
      long lots = Math.round(planned * fraction);
      if (lots == 0) {
        lots = whole > 0 ? 1 : -1;
      }
      if (Math.abs(lots) > Math.abs(whole)) {
        lots = whole;
      }
      if (submit(j, lots, first ? 0L : epochNanos + config.halfLifeNanos)) {
        sent++;
      }
    }
    return sent;
  }

  /** A fill on one of our parents; {@code quantityMinor} is unsigned. */
  public void onHedgeFill(final long id, final long price, final long quantityMinor) {
    final int p = find(id);
    if (p < 0) {
      throw new IllegalArgumentException("fill for unknown hedge parent " + id);
    }
    final long remaining = parentRemaining[p];
    final long qty = Math.min(quantityMinor, Math.abs(remaining));
    final long signed = remaining > 0 ? qty : -qty;
    final int instrumentId = parentInstrument[p];
    final long arrival = parentArrival[p];
    risk.release(instrumentId, signed > 0 ? qty : 0L, signed > 0 ? 0L : qty);
    // the parent's book entry is brought up to date before the fill reaches the risk engine: a
    // fill can trip a post-trade limit, and the kill switch then calls onKill, which releases
    // and drops every parent, so the entry must already match the reservation it describes
    parentRemaining[p] = remaining - signed;
    if (parentRemaining[p] == 0) {
      remove(p);
    }
    risk.onFill(instrumentId, signed, price);
    final Instrument inst = risk.directory().byId(instrumentId);
    analytics.onFill(
        inst, signed, price, arrival, risk.quotePerBp(instrumentId), risk.unitParallelPv01(instrumentId));
  }

  /** The router finished a parent without filling the rest (cancelled, expired, rejected). */
  public void onParentDone(final long id) {
    final int p = find(id);
    if (p >= 0) {
      releaseRemaining(p);
      remove(p);
    }
  }

  @Override
  public void onKill(final KillReason reason, final long epochNanos) {
    sink.cancelAll();
    for (int p = 0; p < parentCount; p++) {
      releaseRemaining(p);
    }
    parentCount = 0;
    episode = false;
  }

  @Override
  public double axeBp(final int instrumentId, final Side ourSide) {
    if (!episode || residualVariance <= 0.0) {
      return 0.0;
    }
    final Instrument inst = risk.directory().byId(instrumentId);
    if (inst == null || inst.currency() != currency) {
      return 0.0;
    }
    final double sign = ourSide == Side.Buy ? 1.0 : -1.0;
    double dot = 0.0;
    for (int k = 0; k < K; k++) {
      u[k] = sign * risk.unitPv01(instrumentId, k);
      dot += u[k] * sr[k];
    }
    if (dot >= 0.0) {
      return 0.0;
    }
    final double uu = planner.covariance().quadratic(u, u);
    if (uu <= 0.0) {
      return 0.0;
    }
    final double corr = -dot / (Math.sqrt(residualVariance) * Math.sqrt(uu));
    return config.axeMaxBp * Math.min(1.0, corr);
  }

  /** Residual including working parents, as of the last timer. */
  public void residual(final Dv01Vector out) {
    out.copyFrom(residual);
  }

  /** One-day residual variance, currency squared, as of the last timer. */
  public double residualVariance() {
    return residualVariance;
  }

  /** Whether a hedging episode is in progress. */
  public boolean inEpisode() {
    return episode;
  }

  /** Parents currently working. */
  public int workingParents() {
    return parentCount;
  }

  /** Signed remaining quantity of the working parent with this id, or 0. */
  public long remaining(final long id) {
    final int p = find(id);
    return p < 0 ? 0L : parentRemaining[p];
  }

  /** Id of the {@code i}th working parent. */
  public long workingParentId(final int i) {
    return parentId[i];
  }

  public long parentsSent() {
    return parentsSent;
  }

  /** Parents refused by the risk engine or the router. */
  public long parentsRefused() {
    return parentsRefused;
  }

  public HedgeAnalytics analytics() {
    return analytics;
  }

  public CurrencyCode currency() {
    return currency;
  }

  private void measure() {
    risk.ladder(currency, residual);
    for (int p = 0; p < parentCount; p++) {
      final int id = parentInstrument[p];
      final double qty = risk.directory().byId(id).major(parentRemaining[p]);
      for (int k = 0; k < K; k++) {
        residual.add(k, risk.unitPv01(id, k) * qty);
      }
    }
    residual.copyTo(r);
    for (int a = 0; a < K; a++) {
      double s = 0.0;
      for (int b = 0; b < K; b++) {
        s += planner.covariance().get(a, b) * r[b];
      }
      sr[a] = s;
    }
    double v = 0.0;
    for (int k = 0; k < K; k++) {
      v += r[k] * sr[k];
    }
    residualVariance = v;
  }

  private boolean submit(final int j, final long lots, final long urgency) {
    if (parentCount == parentId.length) {
      parentsRefused++;
      return false;
    }
    final int instrumentId = universe.instrumentId(j);
    final long arrival = fair.fairMid(instrumentId);
    if (arrival == FairValueSource.NO_VALUE) {
      parentsRefused++;
      return false;
    }
    final Side side = lots > 0 ? Side.Buy : Side.Sell;
    final long qty = Math.abs(lots) * universe.lotMinor(j);
    final double offset = config.limitBp * risk.quotePerBp(instrumentId);
    final double mid = Decimal9.toDouble(arrival);
    final long limit = side == Side.Buy ? Decimal9.ceil(mid + offset) : Decimal9.floor(mid - offset);
    if (risk.checkOrder(instrumentId, side, qty, limit, 0L) != RiskEngine.OK) {
      parentsRefused++;
      return false;
    }
    final long buy = side == Side.Buy ? qty : 0L;
    final long sell = side == Side.Buy ? 0L : qty;
    risk.reserve(instrumentId, buy, sell);
    final long id = ++nextParentId;
    if (!sink.submit(id, instrumentId, side, qty, limit, urgency, clock.epochNanos())) {
      risk.release(instrumentId, buy, sell);
      parentsRefused++;
      return false;
    }
    parentId[parentCount] = id;
    parentInstrument[parentCount] = instrumentId;
    parentRemaining[parentCount] = side == Side.Buy ? qty : -qty;
    parentArrival[parentCount] = arrival;
    parentCount++;
    parentsSent++;
    return true;
  }

  private void releaseRemaining(final int p) {
    final long rem = parentRemaining[p];
    risk.release(parentInstrument[p], rem > 0 ? rem : 0L, rem > 0 ? 0L : -rem);
    parentRemaining[p] = 0L;
  }

  private int find(final long id) {
    for (int p = 0; p < parentCount; p++) {
      if (parentId[p] == id) {
        return p;
      }
    }
    return -1;
  }

  private void remove(final int p) {
    final int last = parentCount - 1;
    parentId[p] = parentId[last];
    parentInstrument[p] = parentInstrument[last];
    parentRemaining[p] = parentRemaining[last];
    parentArrival[p] = parentArrival[last];
    parentCount = last;
  }
}
