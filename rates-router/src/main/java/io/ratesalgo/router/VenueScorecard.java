package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.Side;

/**
 * What the router has seen of each venue, for the ladder model (DECISIONS.md DR-R6-R03): reject
 * and cancel-reject rates, the fill ratio of immediate-or-cancel children, the mid move 1, 5 and
 * 30 seconds after our fills, acknowledgement latency, the rate of market orders against each
 * side from trade prints, and the cancellation rate of resting orders learned from touch
 * decrements that no trade print explains. It also holds the per-venue throttles (a token bucket
 * on new orders and an order-to-trade ratio) and the outage state (a venue that rejects or
 * ignores {@code outageFailures} orders in a row is excluded for a cool-down that doubles on
 * each repeat, and readmitted after it).
 *
 * <p>Every average decays exponentially in time, applied lazily at each event from the event's
 * own clock reading, so the same events at the same times give the same values: the state is a
 * deterministic function of what the router was told. The values a decision used are journaled
 * with it (RouteDecisionModel, template 84). Nothing here allocates; one thread.
 */
public final class VenueScorecard {

  /** Markout horizons, nanoseconds. */
  static final long[] MARKOUT_NANOS = {1_000_000_000L, 5_000_000_000L, 30_000_000_000L};

  private static final double LN2 = StrictMath.log(2.0);
  private static final int RING = 512;
  private static final double SCALE = 1e9;

  private final int n;
  private final long[] lot;
  private final double[] priorCancelRate;
  private final ExecutionModel model;
  private final VenueCapabilities[] caps;

  // decayed counts, decayed together at lastNanos
  private final long[] lastNanos;
  private final double[] sent;
  private final double[] rejects;
  private final double[] cancelsSent;
  private final double[] cancelRejects;
  private final double[] iocOrdered;
  private final double[] iocFilled;
  private final double[] ackSum;
  private final double[] ackCount;
  private final double[] fills;
  private final double[][] markSum;
  private final double[][] markCount;
  private final double[] cancelLots;
  private final double[] exposure;

  // market orders against each side (index 0 resting bids, 1 resting asks), own half-life
  private final double[][] printSum;
  private final long[][] printNanos;
  private final long[] firstPrint;

  // touch tracking per side for the cancellation rate
  private final long[][] touchPrice;
  private final long[][] touchQty;
  private final long[][] touchNanos;
  private final long[][] pendingPrint;

  // throttles and outage
  private final double[] tokens;
  private final long[] tokenNanos;
  private final int[] failures;
  private final long[] outageUntil;
  private final int[] outages;
  private long outageEpisodes;
  private long throttled;

  // markout rings, one per horizon
  private final int[][] mRow = new int[3][RING];
  private final double[][] mSign = new double[3][RING];
  private final long[][] mMid = new long[3][RING];
  private final long[][] mDue = new long[3][RING];
  private final int[] mHead = new int[3];
  private final int[] mCount = new int[3];
  private long markoutsDropped;

  /**
   * @param venues the router's venues in row order
   * @param model the ladder model's parameters
   * @param startNanos clock reading at construction (the throttles start full)
   */
  public VenueScorecard(final VenueParams[] venues, final ExecutionModel model, final long startNanos) {
    this.n = venues.length;
    this.model = model;
    lot = new long[n];
    priorCancelRate = new double[n];
    caps = new VenueCapabilities[n];
    lastNanos = new long[n];
    sent = new double[n];
    rejects = new double[n];
    cancelsSent = new double[n];
    cancelRejects = new double[n];
    iocOrdered = new double[n];
    iocFilled = new double[n];
    ackSum = new double[n];
    ackCount = new double[n];
    fills = new double[n];
    markSum = new double[n][3];
    markCount = new double[n][3];
    cancelLots = new double[n];
    exposure = new double[n];
    printSum = new double[n][2];
    printNanos = new long[n][2];
    firstPrint = new long[n];
    touchPrice = new long[n][2];
    touchQty = new long[n][2];
    touchNanos = new long[n][2];
    pendingPrint = new long[n][2];
    tokens = new double[n];
    tokenNanos = new long[n];
    failures = new int[n];
    outageUntil = new long[n];
    outages = new int[n];
    for (int r = 0; r < n; r++) {
      lot[r] = venues[r].lotSize();
      priorCancelRate[r] = venues[r].cancelRatePerSecond();
      caps[r] = model.capabilities(venues[r].venueId());
      lastNanos[r] = startNanos;
      tokens[r] = caps[r].maxBurst();
      tokenNanos[r] = startNanos;
      firstPrint[r] = Long.MIN_VALUE;
      outageUntil[r] = Long.MIN_VALUE;
    }
  }

  private void decay(final int r, final long now) {
    final long dt = now - lastNanos[r];
    if (dt <= 0) {
      return;
    }
    lastNanos[r] = now;
    final double f = StrictMath.exp(-LN2 * dt / model.scorecardHalfLifeNanos());
    sent[r] *= f;
    rejects[r] *= f;
    cancelsSent[r] *= f;
    cancelRejects[r] *= f;
    iocOrdered[r] *= f;
    iocFilled[r] *= f;
    ackSum[r] *= f;
    ackCount[r] *= f;
    fills[r] *= f;
    cancelLots[r] *= f;
    exposure[r] *= f;
    for (int h = 0; h < 3; h++) {
      markSum[r][h] *= f;
      markCount[r][h] *= f;
    }
  }

  // ------------------------------------------------------------------ order events

  /** A child was sent: one token and one order. */
  void onSent(final int r, final long now) {
    decay(r, now);
    refill(r, now);
    tokens[r] -= 1.0;
    sent[r] += 1.0;
  }

  /** The venue acknowledged a child {@code latencyNanos} after it was sent. */
  void onAck(final int r, final long now, final long latencyNanos) {
    decay(r, now);
    ackSum[r] += Math.max(0L, latencyNanos);
    ackCount[r] += 1.0;
    failures[r] = 0;
    outages[r] = Math.max(0, outages[r] - 1);
  }

  /** The venue rejected a child. */
  void onReject(final int r, final long now) {
    decay(r, now);
    rejects[r] += 1.0;
    fail(r, now);
  }

  /** A child went unacknowledged past the acknowledgement bound. */
  void onAckTimeout(final int r, final long now) {
    decay(r, now);
    fail(r, now);
  }

  private void fail(final int r, final long now) {
    if (++failures[r] >= model.outageFailures() && now >= outageUntil[r]) {
      final int doublings = Math.min(outages[r], 4);
      outageUntil[r] = now + (model.outageCooldownNanos() << doublings);
      outages[r]++;
      outageEpisodes++;
      // on probation after the cool-down: one more failure excludes again
      failures[r] = model.outageFailures() - 1;
    }
  }

  /** A cancel was sent. */
  void onCancelSent(final int r, final long now) {
    decay(r, now);
    cancelsSent[r] += 1.0;
  }

  /** The venue refused a cancel. */
  void onCancelReject(final int r, final long now) {
    decay(r, now);
    cancelRejects[r] += 1.0;
  }

  /** An immediate-or-cancel child finished with {@code filled} of {@code ordered}. */
  void onIocDone(final int r, final long now, final long ordered, final long filled) {
    decay(r, now);
    iocOrdered[r] += ordered / (double) lot[r];
    iocFilled[r] += filled / (double) lot[r];
  }

  /**
   * One of our fills. {@code mid} is the venue's mid when the report arrived, or 0; the markouts
   * measure its move at the three horizons, our side positive.
   */
  void onFill(final int r, final long now, final Side side, final long mid) {
    decay(r, now);
    fills[r] += 1.0;
    if (mid <= 0) {
      return;
    }
    final double sign = side == Side.Buy ? 1.0 : -1.0;
    for (int h = 0; h < 3; h++) {
      if (mCount[h] == RING) {
        // the oldest sample is dropped rather than growing; counted
        mHead[h] = (mHead[h] + 1) % RING;
        mCount[h]--;
        markoutsDropped++;
      }
      final int i = (mHead[h] + mCount[h]) % RING;
      mCount[h]++;
      mRow[h][i] = r;
      mSign[h][i] = sign;
      mMid[h][i] = mid;
      mDue[h][i] = now + MARKOUT_NANOS[h];
    }
  }

  /** Takes the markout samples that are due, reading mids from the book. */
  void poll(final long now, final BookView book, final VenueParams[] venues) {
    for (int h = 0; h < 3; h++) {
      while (mCount[h] > 0 && mDue[h][mHead[h]] <= now) {
        final int i = mHead[h];
        mHead[h] = (i + 1) % RING;
        mCount[h]--;
        final int r = mRow[h][i];
        final int v = venues[r].venueId();
        final long bid = book.bestBid(v);
        final long ask = book.bestAsk(v);
        if (bid > 0 && ask > bid) {
          decay(r, now);
          final long mid2 = bid + ask;
          markSum[r][h] += mSign[h][i] * (mid2 - 2 * mMid[h][i]) * 0.5 / SCALE;
          markCount[r][h] += 1.0;
        }
      }
    }
  }

  // ------------------------------------------------------------------ market data

  /**
   * A trade print: {@code aggressor} removed liquidity, so the resting side is the other one.
   * Counts towards the market order rate against that side, and explains a touch decrement.
   */
  void onTrade(final int r, final long now, final Side aggressor, final long price, final long qty) {
    if (qty <= 0) {
      return;
    }
    final int s = aggressor == Side.Sell ? 0 : 1;
    final double tau = model.tradeRateHalfLifeNanos() / LN2;
    if (firstPrint[r] == Long.MIN_VALUE) {
      firstPrint[r] = now;
      printNanos[r][0] = now;
      printNanos[r][1] = now;
    }
    final long dt = now - printNanos[r][s];
    if (dt > 0) {
      printSum[r][s] *= StrictMath.exp(-dt / tau);
      printNanos[r][s] = now;
    }
    printSum[r][s] += qty;
    if (price == touchPrice[r][s]) {
      pendingPrint[r][s] += qty;
    }
  }

  /**
   * The touch on one side after a book change, with our own resting size taken out: a decrement
   * at an unchanged price that trade prints do not explain is cancellation.
   */
  void onTouch(final int r, final long now, final Side side, final long price, final long others) {
    final int s = side == Side.Buy ? 0 : 1;
    decay(r, now);
    final long dt = now - touchNanos[r][s];
    if (touchPrice[r][s] > 0 && dt > 0) {
      exposure[r] += touchQty[r][s] / (double) lot[r] * (dt / SCALE);
    }
    if (price == touchPrice[r][s] && price > 0) {
      final long d = touchQty[r][s] - others;
      if (d > 0) {
        final long explained = Math.min(d, pendingPrint[r][s]);
        pendingPrint[r][s] -= explained;
        cancelLots[r] += (d - explained) / (double) lot[r];
      }
    } else {
      pendingPrint[r][s] = 0;
    }
    touchPrice[r][s] = price;
    touchQty[r][s] = Math.max(0L, others);
    touchNanos[r][s] = now;
  }

  // ------------------------------------------------------------------ reads

  /** Rate of market orders against resting orders on {@code side}, quantity per second; -1 if no print yet. */
  double marketOrderRate(final int r, final long now, final Side side) {
    if (firstPrint[r] == Long.MIN_VALUE) {
      return -1.0;
    }
    final int s = side == Side.Buy ? 0 : 1;
    final double tau = model.tradeRateHalfLifeNanos() / LN2;
    final double sum = printSum[r][s] * StrictMath.exp(-Math.max(0L, now - printNanos[r][s]) / tau);
    final double seen = Math.max(1L, now - firstPrint[r]);
    return sum / (tau * (1.0 - StrictMath.exp(-seen / tau))) * SCALE;
  }

  /** Learned cancellation rate per unit queued per second, shrunk to the declared prior. */
  double cancelRate(final int r, final long now) {
    decay(r, now);
    final double e0 = model.priorExposureLotSeconds();
    return (priorCancelRate[r] * e0 + cancelLots[r]) / (e0 + exposure[r]);
  }

  /** Rejects per order sent, with one pseudo-order of prior at zero. */
  double rejectRate(final int r, final long now) {
    decay(r, now);
    return rejects[r] / (sent[r] + 1.0);
  }

  /** Cancel rejects per cancel sent, with one pseudo-cancel at zero. */
  double cancelRejectRate(final int r, final long now) {
    decay(r, now);
    return cancelRejects[r] / (cancelsSent[r] + 1.0);
  }

  /** Filled over ordered lots of IOC children, with one pseudo-lot filled. */
  double fillRatio(final int r, final long now) {
    decay(r, now);
    return (iocFilled[r] + 1.0) / (iocOrdered[r] + 1.0);
  }

  /** Mean mid move after our fills at horizon {@code h} (0, 1, 2), our side positive; 0 if none. */
  double markout(final int r, final long now, final int h) {
    decay(r, now);
    return markCount[r][h] > 1e-9 ? markSum[r][h] / markCount[r][h] : 0.0;
  }

  /** Mean acknowledgement latency, or {@code fallback} before any acknowledgement. */
  long ackLatency(final int r, final long now, final long fallback) {
    decay(r, now);
    return ackCount[r] > 1e-9 ? Math.round(ackSum[r] / ackCount[r]) : fallback;
  }

  /** True while the venue's order bucket is empty or its order-to-trade ratio is over the limit. */
  boolean throttled(final int r, final long now) {
    decay(r, now);
    refill(r, now);
    final VenueCapabilities c = caps[r];
    final boolean ratio = sent[r] >= c.minOrdersForRatio() && sent[r] / (fills[r] + 1.0) > c.maxOrderToTrade();
    if (tokens[r] < 1.0 || ratio) {
      throttled++;
      return true;
    }
    return false;
  }

  private void refill(final int r, final long now) {
    final long dt = now - tokenNanos[r];
    if (dt > 0) {
      tokens[r] = Math.min(caps[r].maxBurst(), tokens[r] + caps[r].maxOrdersPerSecond() * dt / SCALE);
      tokenNanos[r] = now;
    }
  }

  /** True while the venue is excluded after an outage. */
  boolean inOutage(final int r, final long now) {
    return now < outageUntil[r];
  }

  /** Capabilities of row {@code r}. */
  VenueCapabilities capabilities(final int r) {
    return caps[r];
  }

  /** Outage episodes since construction. */
  public long outageEpisodes() {
    return outageEpisodes;
  }

  /** Decisions in which a venue was throttled. */
  public long throttledRows() {
    return throttled;
  }

  /** Markout samples dropped because a ring was full. */
  public long markoutsDropped() {
    return markoutsDropped;
  }

  /** Number of venue rows. */
  public int rows() {
    return n;
  }
}
