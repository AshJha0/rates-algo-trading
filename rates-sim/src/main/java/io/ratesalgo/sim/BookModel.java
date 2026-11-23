package io.ratesalgo.sim;

import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.List;

/**
 * The order book of one Treasury or future (design section 8): liquidity providers quoting around
 * a displaced mid, other takers, impact and resilience. Nothing here is calibrated; every number
 * is an assumption with a band in the parameter file.
 *
 * <p>Plain idea. Providers quote one tick either side of the displaced mid D = latent mid + J and
 * four more levels behind. What takers consume at a price comes back with a half-life. Every
 * marketable order pushes J in its direction by a square-root law; J decays with the same
 * half-life. The desk's own orders also move the latent level factor for good (X1). Other takers
 * arrive at random with a small skill at trading before the next move, which is what makes a
 * resting order's fills adversely selected. The book is never re-centred on the latent mid.
 *
 * <pre>
 *   best bid = tick (ceil(D / tick) - 1),  best ask = tick (floor(D / tick) + 1), 5 levels a tick apart
 *   displayed(price) = target(level of price) - consumed(price),  consumed decays by 2^(-1s / half-life) per step
 *   another taker's trade of Q:  J += side (1 - pi) Y sigma sqrt(Q / V)
 *   the desk's order of Q:       I = Y sigma (f(W + side Q) - f(W)),  f(w) = sign(w) sqrt(|w| / V)
 *                                J += (1 - pi) I,  X1 += pi I / (pcpb L[T, level]),  W += side Q
 *   J and W decay by 2^(-1s / half-life) per step
 * </pre>
 *
 * <p>W is the desk's recent signed marketable volume. For a single order W is zero and I is the
 * design's Y sigma sqrt(Q / V) exactly. For a sliced order the slices add up to the impact of
 * their total, less what has decayed, which is what a square-root law of metaorder size means;
 * applying the law to each slice on its own would charge twenty slices 4.5 times the block
 * (DECISIONS.md DR-R3-04, design document "Deviations and clarifications").
 *
 * <p>Queue rule for the desk's resting orders: an order waits behind the provider size displayed
 * at its price when it was entered. Takers at that price consume that size first, then the order,
 * then whatever providers added behind it. When the price leaves the providers' five levels the
 * size ahead is gone. A resting order that the providers' opposite best reaches is filled at its
 * own price.
 *
 * <p>State advances step by step whatever the polling interval; time must not go backwards. Not
 * thread-safe.
 */
public final class BookModel {

  /**
   * Result of one of the desk's orders.
   *
   * @param filledUnits quantity traded at once
   * @param notional sum of quantity times price over those fills, quoted units times quantity units
   * @param restingId id of the remainder resting on the book, 0 if nothing rests
   */
  public record Execution(double filledUnits, double notional, int restingId) {
    /** Average price of the immediate fills, NaN if there were none. */
    public double averagePrice() {
      return filledUnits > 0.0 ? notional / filledUnits : Double.NaN;
    }
  }

  /** A fill of one of the desk's resting orders. */
  public record PassiveFill(int orderId, long nanos, boolean buy, double quantityUnits, double price) {}

  private static final class Resting {
    final int id;
    final boolean buy;
    final long tick;
    double remaining;
    double ahead;

    Resting(final int id, final boolean buy, final long tick, final double remaining, final double ahead) {
      this.id = id;
      this.buy = buy;
      this.tick = tick;
      this.remaining = remaining;
      this.ahead = ahead;
    }
  }

  private final MarketModel model;
  private final MarketSpec spec;
  private final int book;
  private final int instrument;
  private final double tick;
  private final double decay;
  private final double permanentShare;
  private final double permanentPerPrice;
  private final int levels;
  private final double[] target;
  private final int[] takerStart;
  private final boolean[] takerBuy;
  private final double[] takerUnits;

  private final double[] bidConsumed;
  private final double[] askConsumed;
  private final List<Resting> resting = new ArrayList<>();
  private final List<PassiveFill> fills = new ArrayList<>();
  private long bidTop;
  private long askTop;
  private int processed = -1;
  private long clock;
  private long now;
  private double transientJ;
  private double ownVolume;
  private double takerFilledUnits;
  private int nextId = 1;

  // result of the last take(): quantity and notional
  private double lastFilled;
  private double lastNotional;

  BookModel(final MarketModel model, final int book) {
    this.model = model;
    this.spec = model.spec();
    this.book = book;
    this.instrument = spec.bookInstrument(book);
    final SimParams p = spec.params();
    final SimInstrument inst = spec.instrument(instrument);
    this.tick = inst.tick();
    this.decay = spec.impactDecayPerStep();
    this.permanentShare = p.get(Scalar.IMPACT_PERMANENT_SHARE);
    this.permanentPerPrice = 1.0 / (inst.priceChangePerBp() * spec.loading(instrument, 0));
    this.levels = p.bookLevels();
    this.target = new double[levels];
    for (int l = 0; l < levels; l++) {
      target[l] = Math.max(1.0, Math.floor(spec.touchTargetUnits(book) * p.depthMultiple(l) / inst.minQuantity()))
          * inst.minQuantity();
    }
    this.bidConsumed = new double[levels];
    this.askConsumed = new double[levels];

    // other takers (stream 200 + book): per step a count, then per trade a sign and a size
    final int steps = spec.steps();
    final SplitMix64 s = Streams.of(model.seed(), Streams.TAKERS + book);
    final double perBin = p.get(Scalar.RFQ_BIN_MINUTES) * 60.0 / p.get(Scalar.STEP_SECONDS);
    final double tradesPerDay = spec.dailyVolumeUnits(book) / spec.takerMeanUnits(book);
    final int look = (int) Math.round(p.get(Scalar.TAKER_LOOK_AHEAD_S) / p.get(Scalar.STEP_SECONDS));
    final double skill = p.get(Scalar.TAKER_SIGN_SKILL);
    final double logSd = p.get(Scalar.TAKER_SIZE_LOG_SD);
    this.takerStart = new int[steps + 1];
    boolean[] buys = new boolean[1024];
    double[] units = new double[1024];
    int n = 0;
    for (int k = 0; k < steps; k++) {
      takerStart[k] = n;
      final int bin = Math.min(p.bins() - 1, (int) (k / perBin));
      final int count = Draws.poisson(tradesPerDay * p.timeOfDayShare(bin) / perBin, s);
      if (n + count > buys.length) {
        buys = java.util.Arrays.copyOf(buys, Math.max(n + count, buys.length * 2));
        units = java.util.Arrays.copyOf(units, buys.length);
      }
      final double move = model.exogenousMid(instrument, Math.min(steps, k + look)) - model.exogenousMid(instrument, k);
      for (int i = 0; i < count; i++) {
        buys[n] = Draws.uniform(s) < 0.5 + skill * Math.signum(move);
        final double size = spec.takerMedianUnits(book) * Math.exp(logSd * Draws.normal(s));
        units[n] = Math.ceil(size / inst.minQuantity()) * inst.minQuantity();
        n++;
      }
    }
    takerStart[steps] = n;
    this.takerBuy = java.util.Arrays.copyOf(buys, n);
    this.takerUnits = java.util.Arrays.copyOf(units, n);

    final double d = model.latentMid(instrument, 0L) / tick;
    this.bidTop = (long) Math.ceil(d) - 1;
    this.askTop = (long) Math.floor(d) + 1;
  }

  // ------------------------------------------------------------------ the exogenous flow

  /** Index of the first other-taker trade of a step; the trades of step k are [start(k), start(k+1)). */
  public int takerStart(final int step) {
    return takerStart[step];
  }

  /** True if other-taker trade {@code n} buys. */
  public boolean takerBuys(final int n) {
    return takerBuy[n];
  }

  /** Size of other-taker trade {@code n}, quantity units (a whole number of lots). */
  public double takerUnits(final int n) {
    return takerUnits[n];
  }

  long flowHash(final long seed) {
    long h = seed;
    for (final int start : takerStart) {
      h = SimRfq.fold(h, start);
    }
    for (int n = 0; n < takerUnits.length; n++) {
      h = SimRfq.fold(h, Double.doubleToRawLongBits(takerBuy[n] ? takerUnits[n] : -takerUnits[n]));
    }
    return h;
  }

  // ------------------------------------------------------------------ state

  /** Index of the instrument in the universe. */
  public int instrument() {
    return instrument;
  }

  /** D: the latent mid plus the transient displacement, at the book's current time. */
  public double displacedMid() {
    return model.latentMid(instrument, clock) + transientJ;
  }

  /** J: the transient displacement of the book, quoted units. */
  public double transientDisplacement() {
    return transientJ;
  }

  /** W: the desk's recent signed marketable volume, quantity units, positive for buying. */
  public double ownRecentVolume() {
    return ownVolume;
  }

  /** The providers' best bid. */
  public double bestBid() {
    return bidTop * tick;
  }

  /** The providers' best ask. */
  public double bestAsk() {
    return askTop * tick;
  }

  /** Price of provider level {@code level} (0 is the best) on a side. */
  public double levelPrice(final boolean bidSide, final int level) {
    return (bidSide ? bidTop - level : askTop + level) * tick;
  }

  /** The providers' target size at a level, quantity units. */
  public double targetUnits(final int level) {
    return target[level];
  }

  /** The providers' displayed size at a level of a side, quantity units. */
  public double displayedUnits(final boolean bidSide, final int level) {
    return Math.max(0.0, target[level] - (bidSide ? bidConsumed : askConsumed)[level]);
  }

  /** What takers consumed at a level of a side and providers have not yet put back, quantity units. */
  public double consumedUnits(final boolean bidSide, final int level) {
    return (bidSide ? bidConsumed : askConsumed)[level];
  }

  /** Volume other takers have traded so far, quantity units. */
  public double takerFilledUnits() {
    return takerFilledUnits;
  }

  /** Quantity still ahead of a resting order of the desk, or NaN if it is not on the book. */
  public double queueAhead(final int orderId) {
    for (final Resting o : resting) {
      if (o.id == orderId) {
        return o.ahead;
      }
    }
    return Double.NaN;
  }

  /** Fills of the desk's resting orders since the last call, in time order. */
  public List<PassiveFill> drainPassiveFills() {
    final List<PassiveFill> out = List.copyOf(fills);
    fills.clear();
    return out;
  }

  // ------------------------------------------------------------------ time

  /**
   * Brings the book to a time: for every step up to the one that contains it, decay, re-key the
   * provider levels on the new displaced mid, then let that step's other takers trade.
   */
  public void advanceTo(final long nanos) {
    if (nanos < now) {
      throw new IllegalArgumentException("book time must not go backwards");
    }
    now = nanos;
    final int to = spec.stepAt(nanos);
    while (processed < to) {
      processed++;
      clock = processed * MarketSpec.STEP_NANOS;
      if (processed > 0) {
        transientJ *= decay;
        ownVolume *= decay;
        for (int l = 0; l < levels; l++) {
          bidConsumed[l] *= decay;
          askConsumed[l] *= decay;
        }
      }
      rekey();
      if (processed < spec.steps()) {
        for (int n = takerStart[processed]; n < takerStart[processed + 1]; n++) {
          take(takerBuy[n], takerUnits[n], takerBuy[n] ? Long.MAX_VALUE : Long.MIN_VALUE, false);
          takerFilledUnits += lastFilled;
          otherMarketable(takerBuy[n], lastFilled);
        }
      }
    }
    clock = nanos;
  }

  /** Moves the provider levels to the current displaced mid and settles what that does to resting orders. */
  private void rekey() {
    final double d = displacedMid() / tick;
    final long bid = (long) Math.ceil(d) - 1;
    final long ask = (long) Math.floor(d) + 1;
    if (bid != bidTop) {
      shift(bidConsumed, bidTop - bid);
      bidTop = bid;
    }
    if (ask != askTop) {
      shift(askConsumed, ask - askTop);
      askTop = ask;
    }
    for (int i = 0; i < resting.size(); i++) {
      final Resting o = resting.get(i);
      if (o.buy ? o.tick >= askTop : o.tick <= bidTop) {
        // the providers' opposite best has reached the order: it trades at its own price
        fills.add(new PassiveFill(o.id, clock, o.buy, o.remaining, o.tick * tick));
        resting.remove(i--);
        continue;
      }
      final long level = o.buy ? bidTop - o.tick : o.tick - askTop;
      o.ahead = level < 0 || level >= levels ? 0.0 : Math.min(o.ahead, displayedUnits(o.buy, (int) level));
    }
  }

  /** A price that enters the five levels starts at its target: consumption is kept only for prices that stay. */
  private void shift(final double[] consumed, final long by) {
    final double[] old = consumed.clone();
    for (int l = 0; l < levels; l++) {
      final long from = l + by;
      consumed[l] = from >= 0 && from < levels ? old[(int) from] : 0.0;
    }
  }

  // ------------------------------------------------------------------ matching

  /**
   * Matches a marketable quantity against the book in price order, and at one price in queue
   * order. The desk's own order does not trade with the desk's resting orders.
   */
  private void take(final boolean buy, final double units, final long limitTick, final boolean own) {
    double remaining = units;
    double notional = 0.0;
    long first = buy ? askTop : bidTop;
    long last = buy ? askTop + levels - 1 : bidTop - levels + 1;
    if (!own) {
      for (final Resting o : resting) {
        if (o.buy != buy) {
          first = buy ? Math.min(first, o.tick) : Math.max(first, o.tick);
          last = buy ? Math.max(last, o.tick) : Math.min(last, o.tick);
        }
      }
    }
    final double[] consumed = buy ? askConsumed : bidConsumed;
    for (long t = first; remaining > 0.0 && (buy ? t <= last && t <= limitTick : t >= last && t >= limitTick);
        t += buy ? 1 : -1) {
      final long level = buy ? t - askTop : bidTop - t;
      final boolean provided = level >= 0 && level < levels;
      final double displayed = provided ? displayedUnits(!buy, (int) level) : 0.0;
      double fromProviders = 0.0;
      if (!own) {
        for (final Resting o : resting) {
          if (o.buy == buy || o.tick != t || remaining <= 0.0) {
            continue;
          }
          final double before = Math.min(remaining, Math.max(0.0, Math.min(o.ahead, displayed) - fromProviders));
          fromProviders += before;
          remaining -= before;
          final double fill = Math.min(remaining, o.remaining);
          if (fill > 0.0) {
            o.remaining -= fill;
            remaining -= fill;
            notional += fill * t * tick;
            fills.add(new PassiveFill(o.id, clock, o.buy, fill, t * tick));
          }
        }
      }
      final double rest = Math.min(remaining, displayed - fromProviders);
      fromProviders += rest;
      remaining -= rest;
      if (fromProviders > 0.0) {
        consumed[(int) level] += fromProviders;
        notional += fromProviders * t * tick;
        for (final Resting o : resting) {
          if (o.buy != buy && o.tick == t) {
            o.ahead = Math.max(0.0, o.ahead - fromProviders);
          }
        }
      }
    }
    resting.removeIf(o -> o.remaining <= 0.0);
    lastFilled = units - remaining;
    lastNotional = notional;
  }

  /**
   * One of the desk's orders. The marketable part trades at the displayed prices up to the limit,
   * then moves the book and the level factor; a remainder rests unless the order is
   * immediate-or-cancel or has no limit.
   *
   * @param limitPrice limit in quoted units, NaN for a market order
   */
  public Execution submit(
      final long nanos, final boolean buy, final double units, final double limitPrice, final boolean immediateOrCancel) {
    if (!(units > 0.0)) {
      throw new IllegalArgumentException("order quantity must be positive: " + units);
    }
    advanceTo(nanos);
    final boolean market = Double.isNaN(limitPrice);
    // a limit between ticks is tightened to the tick inside it
    final long limitTick =
        market
            ? (buy ? Long.MAX_VALUE : Long.MIN_VALUE)
            : (buy ? (long) Math.floor(limitPrice / tick + 1e-9) : (long) Math.ceil(limitPrice / tick - 1e-9));
    take(buy, units, limitTick, true);
    final double filled = lastFilled;
    final double notional = lastNotional;
    if (filled > 0.0) {
      ownMarketable(nanos, buy, filled);
    }
    int id = 0;
    if (filled < units && !market && !immediateOrCancel) {
      final long level = buy ? bidTop - limitTick : limitTick - askTop;
      final double ahead = level >= 0 && level < levels ? displayedUnits(buy, (int) level) : 0.0;
      id = nextId++;
      resting.add(new Resting(id, buy, limitTick, units - filled, ahead));
    }
    return new Execution(filled, notional, id);
  }

  /** Cancels a resting order of the desk; false if it is not on the book. */
  public boolean cancel(final int orderId) {
    return resting.removeIf(o -> o.id == orderId);
  }

  // ------------------------------------------------------------------ impact and venue hooks

  /**
   * Impact of a marketable quantity of the desk that has just traded: transient to the book,
   * permanent to the level factor (see the class comment for the law). The venue simulator calls
   * this after its own matching engine has filled the order.
   */
  public void ownMarketable(final long nanos, final boolean buy, final double filledUnits) {
    final double before = signedRoot(ownVolume);
    ownVolume += buy ? filledUnits : -filledUnits;
    final double impact = signedRoot(ownVolume) - before;
    transientJ += (1.0 - permanentShare) * impact;
    model.shiftLevel(nanos, permanentShare * impact * permanentPerPrice);
    rekey();
  }

  /** Transient impact of another taker's trade that has just been matched. */
  public void otherMarketable(final boolean buy, final double filledUnits) {
    if (filledUnits > 0.0) {
      final double impact = spec.peakImpact(book, filledUnits);
      transientJ += (1.0 - permanentShare) * (buy ? impact : -impact);
      rekey();
    }
  }

  /**
   * Records that takers consumed provider size at a price (venue simulator, whose own matching
   * engine holds the orders). Nothing happens if the price is not one of the five levels.
   */
  public void consume(final boolean bidSide, final double price, final double units) {
    final long t = Math.round(price / tick);
    final long level = bidSide ? bidTop - t : t - askTop;
    if (level >= 0 && level < levels) {
      (bidSide ? bidConsumed : askConsumed)[(int) level] += units;
    }
  }

  private double signedRoot(final double volume) {
    return Math.signum(volume) * spec.peakImpact(book, volume);
  }
}
