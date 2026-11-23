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
import java.util.Arrays;

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
 * <p>A kill cancels every parent, except a kill from the risk engine's daily-loss limit ({@link
 * RiskEngine#flattenOnly}): then the parents keep working and timers keep sending risk-reducing
 * tranches, so the book is flattened rather than frozen with a loss on it.
 *
 * <p>Protection (review 2026-11-23, Q10). A parent is never priced off a mid older than {@code
 * maxMidAgeNanos} on the injected clock, and none is sent while the {@link HedgeInputGate} says the
 * inputs are unhealthy. A parent still working {@code parentTimeoutNanos} after it was sent is
 * cancelled and stops counting toward the residual, so a venue that has stopped answering cannot
 * make open risk look hedged; its reservation is kept until the router reports it done, and a fill
 * that still arrives is booked as usual. Each of the three is counted, and a stalled parent is
 * reported to the {@link StallListener}.
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
    long maxMidAgeNanos = 5_000_000_000L;
    long parentTimeoutNanos = 60_000_000_000L;
    boolean carryFractionalLots;

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

    /**
     * Oldest mid a parent may be priced from, measured from the fair value's reference stamp to
     * the hedger's clock. The default equals the desk's quoting input age.
     */
    public Config maxMidAgeNanos(final long nanos) {
      if (nanos <= 0) {
        throw new IllegalArgumentException("mid age " + nanos);
      }
      maxMidAgeNanos = nanos;
      return this;
    }

    /**
     * Time after which a parent still working is cancelled and no longer counted as a hedge. It
     * should be several half-lives, so a parent on schedule never reaches it.
     */
    public Config parentTimeoutNanos(final long nanos) {
      if (nanos <= 0) {
        throw new IllegalArgumentException("parent timeout " + nanos);
      }
      parentTimeoutNanos = nanos;
      return this;
    }

    /**
     * Honour the half-life for small plans (review Q13). Off, a tranche that rounds to zero lots is
     * sent as one lot, so a small plan trades a lot per timer and finishes far ahead of its
     * half-life. On, the fraction is carried per instrument until it adds up to a whole lot. Off by
     * default: it changes when the back-tested desk hedges.
     */
    public Config carryFractionalLots(final boolean on) {
      carryFractionalLots = on;
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
  private final long[] parentSent;
  private final boolean[] parentStalled;
  private int parentCount;
  private long nextParentId;
  // parents dropped by the last kill: a fill that raced the cancel-all is still booked (DR-R2R-13)
  private final long[] killedId;
  private final int[] killedInstrument;
  private final long[] killedRemaining;
  private int killedCount;
  private long lateFills;
  private long flattenKills;

  private final Dv01Vector residual;
  private final double[] r = new double[K];
  private final double[] sr = new double[K];
  private final double[] u = new double[K];
  private double residualVariance;
  private boolean episode;
  private long lastHedgeNanos;
  private long parentsSent;
  private long parentsRefused;
  private final double[] carry;
  private HedgeInputGate gate = HedgeInputGate.ALWAYS;
  private StallListener stallListener = StallListener.NONE;
  private long staleMidRefusals;
  private long unhealthyTimers;
  private long stalledParents;

  /** Told when a working parent times out; called on the hedger's thread, must not allocate. */
  @FunctionalInterface
  public interface StallListener {
    /** No listener. */
    StallListener NONE = (parentId, instrumentId, remainingMinor, ageNanos) -> {};

    /** {@code remainingMinor} is signed from our side: positive is an unfilled buy. */
    void onParentStalled(long parentId, int instrumentId, long remainingMinor, long ageNanos);
  }

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
    parentSent = new long[config.maxParents];
    parentStalled = new boolean[config.maxParents];
    carry = new double[universe.size()];
    killedId = new long[config.maxParents];
    killedInstrument = new int[config.maxParents];
    killedRemaining = new long[config.maxParents];
    nextParentId = config.parentIdBase;
    residual = new Dv01Vector(currency);
  }

  /** Default configuration. */
  public static Config config() {
    return new Config();
  }

  /** Sets the input-health gate; {@code null} removes it. */
  public void inputGate(final HedgeInputGate inputGate) {
    gate = inputGate == null ? HedgeInputGate.ALWAYS : inputGate;
  }

  /** Sets the listener told of stalled parents; {@code null} removes it. */
  public void stallListener(final StallListener listener) {
    stallListener = listener == null ? StallListener.NONE : listener;
  }

  /** Measures the residual and sends the next tranche if one is due. Returns parents sent. */
  public int onTimer(final long epochNanos) {
    final long now = clock.epochNanos();
    expireStalled(now);
    measure();
    analytics.recordResidual(epochNanos, residualVariance, residual.parallel());
    final boolean allowed = risk.killSwitch().isActive() || risk.flattenOnly();
    if (!allowed || Math.sqrt(residualVariance) < config.triggerStdDev) {
      endEpisode();
      return 0;
    }
    if (!gate.healthy(now)) {
      // prices cannot be trusted: nothing new is sent, and the episode is left open so the
      // schedule resumes where it was when the inputs recover
      unhealthyTimers++;
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
      long lots;
      if (config.carryFractionalLots && !first) {
        // the part of a lot the schedule owes is carried until it adds up to a whole one
        final double due = planned * fraction + (carry[j] * planned > 0.0 ? carry[j] : 0.0);
        lots = (long) due;
        carry[j] = due - lots;
        if (lots == 0) {
          continue;
        }
      } else {
        lots = Math.round(planned * fraction);
        if (lots == 0) {
          lots = whole > 0 ? 1 : -1;
        }
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
      if (bookKilledFill(id, price, quantityMinor)) {
        return;
      }
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

  /**
   * A fill on a parent the last kill dropped: the router had already sent it when the cancel-all
   * went out, so the trade stands. It is booked (the reservation is already released) up to the
   * parent's remaining quantity; returns false if {@code id} is not such a parent.
   */
  private boolean bookKilledFill(final long id, final long price, final long quantityMinor) {
    for (int k = 0; k < killedCount; k++) {
      if (killedId[k] == id && killedRemaining[k] != 0) {
        final long remaining = killedRemaining[k];
        final long qty = Math.min(quantityMinor, Math.abs(remaining));
        final long signed = remaining > 0 ? qty : -qty;
        killedRemaining[k] = remaining - signed;
        lateFills++;
        risk.onFill(killedInstrument[k], signed, price);
        return true;
      }
    }
    return false;
  }

  /** Fills booked on parents after a kill had dropped them. */
  public long lateFills() {
    return lateFills;
  }

  /** Daily-loss kills under which the working parents were kept to keep flattening. */
  public long flattenKills() {
    return flattenKills;
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
    if (risk.flattenOnly()) {
      // the risk engine's own daily-loss kill: quotes are pulled, but the hedges that reduce the
      // loss keep working and new ones are still sent, each passing checkOrder (PNL_AUDIT item 14)
      flattenKills++;
      return;
    }
    // remembered before the cancel-all, since the router may report each parent done inside it
    killedCount = 0;
    for (int p = 0; p < parentCount; p++) {
      killedId[killedCount] = parentId[p];
      killedInstrument[killedCount] = parentInstrument[p];
      killedRemaining[killedCount] = parentRemaining[p];
      killedCount++;
    }
    sink.cancelAll();
    for (int p = 0; p < parentCount; p++) {
      releaseRemaining(p);
    }
    parentCount = 0;
    endEpisode();
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

  /** Parents refused by the risk engine or the router, or for want of a fresh mid. */
  public long parentsRefused() {
    return parentsRefused;
  }

  /** Parents not sent because the mid had no stamp or was older than the maximum age. */
  public long staleMidRefusals() {
    return staleMidRefusals;
  }

  /** Timers on which a tranche was due but the input gate was unhealthy. */
  public long unhealthyTimers() {
    return unhealthyTimers;
  }

  /** Parents that timed out while working, in total. */
  public long stalledParents() {
    return stalledParents;
  }

  /** Parents timed out and not yet reported done: risk reserved but not counted as hedged. */
  public int stalledWorking() {
    int n = 0;
    for (int p = 0; p < parentCount; p++) {
      if (parentStalled[p]) {
        n++;
      }
    }
    return n;
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
      if (parentStalled[p]) {
        continue; // timed out: its remainder is open risk until a fill says otherwise
      }
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
    final long now = clock.epochNanos();
    final long reference = fair.referenceNanos(instrumentId);
    if (reference == FairValueSource.NO_VALUE || now - reference > config.maxMidAgeNanos) {
      // a limit set off a stale mid can sit a long way through the market
      staleMidRefusals++;
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
    if (!sink.submit(id, instrumentId, side, qty, limit, urgency, now)) {
      risk.release(instrumentId, buy, sell);
      parentsRefused++;
      return false;
    }
    parentId[parentCount] = id;
    parentInstrument[parentCount] = instrumentId;
    parentRemaining[parentCount] = side == Side.Buy ? qty : -qty;
    parentArrival[parentCount] = arrival;
    parentSent[parentCount] = now;
    parentStalled[parentCount] = false;
    parentCount++;
    parentsSent++;
    return true;
  }

  /**
   * Cancels parents that have worked past the timeout and stops counting them as hedges. Backward,
   * because a router may report the parent done from inside the cancel, which removes it.
   */
  private void expireStalled(final long now) {
    for (int p = parentCount - 1; p >= 0; p--) {
      if (p >= parentCount || parentStalled[p] || now - parentSent[p] <= config.parentTimeoutNanos) {
        continue;
      }
      final long id = parentId[p];
      parentStalled[p] = true;
      stalledParents++;
      stallListener.onParentStalled(id, parentInstrument[p], parentRemaining[p], now - parentSent[p]);
      sink.cancel(id);
    }
  }

  private void endEpisode() {
    episode = false;
    Arrays.fill(carry, 0.0);
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
    parentSent[p] = parentSent[last];
    parentStalled[p] = parentStalled[last];
    parentCount = last;
  }
}
