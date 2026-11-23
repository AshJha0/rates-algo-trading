package io.ratesalgo.sim;

import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.List;

/**
 * One simulated session of the market: design sections 2 to 8 in one object, built from the
 * parameters, a session seed and a day type. The venue simulators and the back-test day both read
 * this class, so they cannot disagree about the market.
 *
 * <p>Plain idea. Rates move by three curve factors (level, slope, curvature), swaps add a spread
 * factor, each future adds a mean-reverting basis, each tenor a small term of its own. The state
 * lives on a one-second grid and is never rounded; a read at any time returns the state of the
 * step that contains it, so the path does not depend on how often it is polled. Informed clients
 * do not move the price by trading: their arrival starts a drift of the level factor, whoever
 * wins the RFQ.
 *
 * <p>Formal statement, per step of dt = 1/60 minute (m is 1, or 1 plus the event multiple inside
 * the day type's window):
 *
 * <pre>
 *   F_j[k+1] = F_j[k] + s_j sqrt(dt m[k]) Z_j[k] + release_j[k]         j = level, slope, curvature
 *   S[k+1]   = S[k]   + s_S sqrt(dt m[k]) Z_S[k]                        swap-spread factor
 *   e_T[k+1] = e_T[k] + s_eT sqrt(dt) Z_T[k]                            per tenor node
 *   B[k+1]   = B[k] exp(-kappa/60) + sd_B sqrt(1 - exp(-2 kappa/60)) Z_B[k]
 *   release_level[k] = G[k] (1 - exp(-1 s / tau)),  G[k+1] = G[k] - release_level[k] + arrivals[k]
 *   dy_T = L[T,level] (F_level + X1) + L[T,slope] F_slope + L[T,curv] F_curv + e_T
 * </pre>
 *
 * <p>Everything except X1 (the desk's own permanent impact, section 8) is a function of the seed
 * and the parameters and is computed here once. X1 changes only when {@link BookModel} applies the
 * permanent part of one of the desk's marketable orders.
 *
 * <p>Not thread-safe. Times are nanoseconds after the session start (07:00 New York).
 */
public final class MarketModel {

  private final MarketSpec spec;
  private final SimParams p;
  private final long seed;
  private final DayType dayType;
  private final int steps;
  private final double[][] factor;
  private final double[] spread;
  private final double[][] idio;
  private final double[][] basis;
  private final double[] pending;
  private final List<SimRfq> rfqs;
  private final BookModel[] books;

  // X1 history: the offset is offsetValue[n] from offsetNanos[n] on
  private long[] offsetNanos = new long[8];
  private double[] offsetValue = new double[8];
  private int offsets;

  public MarketModel(final MarketSpec spec, final long seed, final DayType dayType) {
    this.spec = spec;
    this.p = spec.params();
    this.seed = seed;
    this.dayType = dayType;
    this.steps = spec.steps();
    this.rfqs = List.copyOf(RfqFlow.generate(spec, seed));
    this.factor = new double[SimParams.FACTORS][steps + 1];
    this.spread = new double[steps + 1];
    this.idio = new double[p.nodes()][steps + 1];
    this.basis = new double[spec.futureCount()][steps + 1];
    this.pending = new double[steps + 1];
    this.books = new BookModel[spec.bookCount()];
    generate();
  }

  private void generate() {
    final double stepMinutes = p.get(Scalar.STEP_SECONDS) / 60.0;
    final double rootDt = Math.sqrt(stepMinutes);

    // sqrt(m[k]): 1 outside the day type's window
    final double[] rootM = new double[steps];
    java.util.Arrays.fill(rootM, 1.0);
    if (dayType != DayType.NONE) {
      final double perMinute = 60.0 / p.get(Scalar.STEP_SECONDS);
      final int start = (int) ((p.eventStartMinuteNy(dayType) - p.get(Scalar.SESSION_START_MINUTE_NY)) * perMinute);
      final int end = (int) ((p.eventEndMinuteNy(dayType) - p.get(Scalar.SESSION_START_MINUTE_NY)) * perMinute);
      final double inside = Math.sqrt(1.0 + p.eventExtraVariance(dayType));
      for (int k = Math.max(0, start); k < Math.min(steps, end); k++) {
        rootM[k] = inside;
      }
    }

    // informed arrivals per step: d x A / L[T_r, level] (section 6)
    final double[] arrivals = new double[steps];
    final double move = p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP);
    for (final SimRfq r : rfqs) {
      if (r.informed()) {
        final int k = spec.stepAt(r.arrivalNanos());
        arrivals[Math.min(k, steps - 1)] += yieldDirection(r) * move / spec.loading(r.instrument(), 0);
      }
    }

    final double[] sd = {
      spec.levelDiffusiveSdPerSqrtMinute(), p.factorSdPerSqrtMinute(1), p.factorSdPerSqrtMinute(2)
    };
    final double release = 1.0 - Math.exp(-p.get(Scalar.STEP_SECONDS) / p.get(Scalar.DRIFT_TIME_CONSTANT_S));
    for (int j = 0; j < SimParams.FACTORS; j++) {
      final SplitMix64 z = Streams.of(seed, Streams.FACTOR + j);
      final double[] f = factor[j];
      final double scale = sd[j] * rootDt;
      for (int k = 0; k < steps; k++) {
        f[k + 1] = f[k] + scale * rootM[k] * Draws.normal(z);
        if (j == 0) {
          final double released = pending[k] * release;
          f[k + 1] += released;
          pending[k + 1] = pending[k] - released + arrivals[k];
        }
      }
    }
    final SplitMix64 zs = Streams.of(seed, Streams.SPREAD);
    final double spreadScale = p.get(Scalar.SPREAD_SD_PER_SQRT_MINUTE) * rootDt;
    for (int k = 0; k < steps; k++) {
      spread[k + 1] = spread[k] + spreadScale * rootM[k] * Draws.normal(zs);
    }
    for (int n = 0; n < p.nodes(); n++) {
      final SplitMix64 z = Streams.of(seed, Streams.IDIOSYNCRATIC + n);
      final double scale = p.idiosyncraticSdPerSqrtMinute(n) * rootDt;
      final double[] e = idio[n];
      for (int k = 0; k < steps; k++) {
        e[k + 1] = e[k] + scale * Draws.normal(z);
      }
    }
    // Ornstein-Uhlenbeck basis, exact over one step
    final double kappaStep = Math.log(2.0) / p.get(Scalar.BASIS_HALF_LIFE_MINUTES) * stepMinutes;
    final double keep = Math.exp(-kappaStep);
    final double shock = p.get(Scalar.BASIS_STATIONARY_SD_BP) * Math.sqrt(1.0 - Math.exp(-2.0 * kappaStep));
    for (int c = 0; c < basis.length; c++) {
      final SplitMix64 z = Streams.of(seed, Streams.BASIS + c);
      final double[] b = basis[c];
      for (int k = 0; k < steps; k++) {
        b[k + 1] = b[k] * keep + shock * Draws.normal(z);
      }
    }
  }

  // ------------------------------------------------------------------ identity

  /** The specification this session was built from. */
  public MarketSpec spec() {
    return spec;
  }

  /** The session seed. */
  public long seed() {
    return seed;
  }

  /** The session's day type. */
  public DayType dayType() {
    return dayType;
  }

  // ------------------------------------------------------------------ latent state

  /** Curve factor {@code j} (0 level, 1 slope, 2 curvature) at a step, bp in factor units, without X1. */
  public double factor(final int j, final int step) {
    return factor[j][step];
  }

  /** Swap-spread factor S at a step, bp. */
  public double spreadFactor(final int step) {
    return spread[step];
  }

  /** Idiosyncratic term of tenor node {@code n} at a step, bp. */
  public double idiosyncratic(final int n, final int step) {
    return idio[n][step];
  }

  /** Basis of futures contract {@code c} at a step, bp of yield. */
  public double basis(final int c, final int step) {
    return basis[c][step];
  }

  /** G: level-factor drift not yet released at a step, bp in factor units. */
  public double pendingDrift(final int step) {
    return pending[step];
  }

  /** X1: the level-factor offset from the desk's own permanent impact at a time, bp in factor units. */
  public double levelOffset(final long nanos) {
    int lo = 0;
    int hi = offsets;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (offsetNanos[mid] <= nanos) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo == 0 ? 0.0 : offsetValue[lo - 1];
  }

  /** Adds to X1 from {@code nanos} on. Called by {@link BookModel} for the desk's orders only. */
  void shiftLevel(final long nanos, final double deltaBp) {
    if (offsets > 0 && nanos < offsetNanos[offsets - 1]) {
      throw new IllegalArgumentException("permanent impact must be applied in time order");
    }
    if (offsets == offsetNanos.length) {
      offsetNanos = java.util.Arrays.copyOf(offsetNanos, offsets * 2);
      offsetValue = java.util.Arrays.copyOf(offsetValue, offsets * 2);
    }
    offsetNanos[offsets] = nanos;
    offsetValue[offsets] = (offsets == 0 ? 0.0 : offsetValue[offsets - 1]) + deltaBp;
    offsets++;
  }

  /** Change of instrument {@code i}'s yield since the session start at a step, bp, without X1. */
  public double yieldChangeBp(final int i, final int step) {
    final int low = spec.nodeLow(i);
    final double w = spec.nodeWeightHigh(i);
    final double e = w == 0.0 ? idio[low][step] : (1.0 - w) * idio[low][step] + w * idio[low + 1][step];
    return spec.loading(i, 0) * factor[0][step]
        + spec.loading(i, 1) * factor[1][step]
        + spec.loading(i, 2) * factor[2][step]
        + e;
  }

  /** Latent mid of instrument {@code i} at a step without the desk's permanent impact, quoted units. */
  public double exogenousMid(final int i, final int step) {
    final SimInstrument inst = spec.instrument(i);
    double bp = yieldChangeBp(i, step);
    if (inst.kind() == SimInstrument.Kind.SWAP) {
      bp += p.get(Scalar.SPREAD_LOADING) * spread[step];
    } else if (inst.kind() == SimInstrument.Kind.FUTURE) {
      bp += basis[spec.futureOf(i)][step];
    }
    return inst.initialMid() + inst.priceChangePerBp() * bp;
  }

  /**
   * The unrounded latent mid of instrument {@code i} at a time (design section 4): the state of
   * the step that contains the time, plus the desk's permanent impact up to that time. Marking in
   * research reports uses this.
   */
  public double latentMid(final int i, final long nanos) {
    final double offset = offsets == 0 ? 0.0 : levelOffset(nanos);
    return exogenousMid(i, spec.stepAt(nanos))
        + spec.instrument(i).priceChangePerBp() * spec.loading(i, 0) * offset;
  }

  /** The latent mid rounded to the instrument's tick, half away from zero: what a venue publishes. */
  public double referencePrice(final int i, final long nanos) {
    return spec.instrument(i).roundToTick(latentMid(i, nanos));
  }

  // ------------------------------------------------------------------ RFQs

  /** The session's RFQs in arrival order. */
  public List<SimRfq> rfqs() {
    return rfqs;
  }

  /** +1 if the client's side gains from higher yields (pays fixed, sells a bond), else -1. */
  public int yieldDirection(final SimRfq r) {
    final boolean swap = spec.instrument(r.instrument()).kind() == SimInstrument.Kind.SWAP;
    return r.clientBuys() == swap ? 1 : -1;
  }

  /** Latent mid of the RFQ's instrument at its arrival: the reference of every quote and of the reserve. */
  public double midAtArrival(final SimRfq r) {
    return latentMid(r.instrument(), r.arrivalNanos());
  }

  /**
   * Competitor {@code d}'s quote in quoted units: the mid at arrival plus or minus its half-spread,
   * rounded to the tick away from the mid, so a competitor never quotes better than its unrounded
   * price. This conversion and rounding is the only place a tick enters an RFQ.
   */
  public double competitorQuote(final SimRfq r, final int d) {
    final SimInstrument inst = spec.instrument(r.instrument());
    final double distance = r.halfSpreadBp(d) * Math.abs(inst.priceChangePerBp());
    final double mid = midAtArrival(r);
    return r.clientBuys()
        ? Math.ceil((mid + distance) / inst.tick()) * inst.tick()
        : Math.floor((mid - distance) / inst.tick()) * inst.tick();
  }

  /**
   * The client's decision under the best-price rule (design section 7).
   *
   * <p>Live quotes are the answering competitors' and the desk's, if it quoted inside the window.
   * The client decides when every dealer asked has answered, or at the end of the window. It
   * trades with the best live quote (earliest on a tie) if that quote's distance from the mid at
   * arrival is at most its reserve; otherwise it passes. A done-away client trades elsewhere.
   *
   * @param deskPrice the desk's price on the client's side in quoted units, NaN if it did not quote
   * @param deskDelayNanos when the desk's quote reached the client, nanoseconds after arrival
   */
  public RfqDecision decide(final SimRfq r, final double deskPrice, final long deskDelayNanos) {
    final SimInstrument inst = spec.instrument(r.instrument());
    final long window = (long) (p.get(Scalar.RESPONSE_WINDOW_MS) * 1e6);
    final double mid = midAtArrival(r);
    final double sign = r.clientBuys() ? 1.0 : -1.0;
    final boolean desk = !Double.isNaN(deskPrice) && deskDelayNanos < window;

    int best = RfqDecision.NOBODY;
    double bestPrice = Double.NaN;
    long bestDelay = 0;
    double cover = Double.NaN;
    boolean allIn = desk;
    long lastAnswer = desk ? deskDelayNanos : 0;
    for (int d = RfqDecision.DESK; d < r.competitors(); d++) {
      final boolean live = d == RfqDecision.DESK ? desk : r.answers(d);
      if (!live) {
        allIn &= d == RfqDecision.DESK;
        continue;
      }
      final double price = d == RfqDecision.DESK ? deskPrice : competitorQuote(r, d);
      final long delay = d == RfqDecision.DESK ? deskDelayNanos : r.delayNanos(d);
      lastAnswer = Math.max(lastAnswer, delay);
      // better for the client: lower when buying, higher when selling; earlier on a tie
      final boolean first = best == RfqDecision.NOBODY;
      final double edge = first ? 1.0 : sign * (bestPrice - price);
      if (edge > 0.0 || (edge == 0.0 && delay < bestDelay)) {
        if (!first) {
          cover = betterOf(cover, bestPrice, sign);
        }
        best = d;
        bestPrice = price;
        bestDelay = delay;
      } else {
        cover = betterOf(cover, price, sign);
      }
    }
    final long decided = allIn ? lastAnswer : window;
    if (best == RfqDecision.NOBODY) {
      return new RfqDecision(RfqDecision.Outcome.EXPIRED, RfqDecision.NOBODY, Double.NaN, Double.NaN, Double.NaN, decided);
    }
    if (r.doneAway()) {
      return new RfqDecision(RfqDecision.Outcome.DONE_AWAY, RfqDecision.NOBODY, Double.NaN, Double.NaN, Double.NaN, decided);
    }
    if (inst.toBp(sign * (bestPrice - mid)) > r.reserveBp()) {
      return new RfqDecision(RfqDecision.Outcome.PASSED, RfqDecision.NOBODY, Double.NaN, Double.NaN, Double.NaN, decided);
    }
    final double unrounded =
        best == RfqDecision.DESK ? inst.toBp(sign * (bestPrice - mid)) : r.halfSpreadBp(best);
    return new RfqDecision(RfqDecision.Outcome.DONE, best, bestPrice, cover, unrounded, decided);
  }

  private static double betterOf(final double a, final double b, final double sign) {
    if (Double.isNaN(a)) {
      return b;
    }
    return sign * (a - b) <= 0.0 ? a : b;
  }

  /**
   * Move of the RFQ's instrument against a dealer who took the other side, over {@code horizonNanos}
   * from arrival, bp of yield: positive when the mid moved the way the client traded.
   */
  public double adverseMoveBp(final SimRfq r, final long horizonNanos) {
    return adverseMoveBp(r, r.instrument(), horizonNanos);
  }

  /**
   * The same move measured on another instrument's latent mid, in bp of that instrument's yield
   * and in the direction of the RFQ's yield view: for an informed RFQ its expectation is
   * A x L[T, level] / L[T_r, level] (acceptance test A8). The desk's own impact is left out.
   */
  public double adverseMoveBp(final SimRfq r, final int instrument, final long horizonNanos) {
    final int from = spec.stepAt(r.arrivalNanos());
    final int to = spec.stepAt(r.arrivalNanos() + horizonNanos);
    final double move = exogenousMid(instrument, to) - exogenousMid(instrument, from);
    return yieldDirection(r) * move / spec.instrument(instrument).priceChangePerBp();
  }

  // ------------------------------------------------------------------ order books

  /** The order book of instrument {@code i} (design section 8); created on first use. */
  public BookModel book(final int i) {
    final int b = spec.bookOf(i);
    if (b < 0) {
      throw new IllegalArgumentException(spec.instrument(i).key() + " has no order book");
    }
    if (books[b] == null) {
      books[b] = new BookModel(this, b);
    }
    return books[b];
  }

  // ------------------------------------------------------------------ replay

  /**
   * A hash of the whole exogenous session: every latent value at every step, every RFQ with its
   * competitors, and every other taker's trade. Two sessions with equal hashes are the same
   * session for test A1; the comparison there is also made value by value.
   */
  public long replayHash() {
    long h = SimRfq.fold(seed, dayType.name().hashCode());
    for (final double[] f : factor) {
      h = foldAll(h, f);
    }
    h = foldAll(h, spread);
    for (final double[] e : idio) {
      h = foldAll(h, e);
    }
    for (final double[] b : basis) {
      h = foldAll(h, b);
    }
    h = foldAll(h, pending);
    for (final SimRfq r : rfqs) {
      h = r.hash(h);
    }
    for (int b = 0; b < books.length; b++) {
      h = book(spec.bookInstrument(b)).flowHash(h);
    }
    return h;
  }

  private static long foldAll(final long seed, final double[] values) {
    long h = seed;
    for (final double v : values) {
      h = SimRfq.fold(h, Double.doubleToRawLongBits(v));
    }
    return h;
  }
}
