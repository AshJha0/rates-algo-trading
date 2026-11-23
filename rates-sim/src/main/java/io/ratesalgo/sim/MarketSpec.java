package io.ratesalgo.sim;

import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that follows from the parameters alone and is the same for every session: the grid,
 * each instrument's loadings, the diffusive level volatility, decay factors and the order-book
 * scales. Building it runs the start-up checks of acceptance test A12; a configuration that fails
 * one cannot produce a session.
 *
 * <p>Immutable; one instance can be shared by any number of {@link MarketModel}s.
 */
public final class MarketSpec {

  /** One grid step in nanoseconds. */
  public static final long STEP_NANOS = 1_000_000_000L;

  private final SimParams p;
  private final int steps;
  private final int nInstruments;
  private final double informedVarianceBp2;
  private final double levelDiffusiveSd;
  private final double expectedBestPerMedian;
  private final int[] nodeLow;
  private final double[] nodeWeightHigh;
  private final double[][] loading;
  private final int[] swapOfNode;
  private final int[] treasuries;
  private final int[] books;
  private final int[] bookOf;
  private final int[] futureOf;
  private final int futures;
  private final double[] dailyVolumeUnits;
  private final double[] dailyPriceSd;
  private final double[] touchTargetUnits;
  private final double[] takerMedianUnits;
  private final double[] takerMeanUnits;

  public MarketSpec(final SimParams params) {
    this.p = params;
    this.steps = (int) Math.round(p.get(Scalar.SESSION_MINUTES) * 60.0 / p.get(Scalar.STEP_SECONDS));
    final List<SimInstrument> universe = p.instruments();
    this.nInstruments = universe.size();
    this.nodeLow = new int[nInstruments];
    this.nodeWeightHigh = new double[nInstruments];
    this.loading = new double[nInstruments][SimParams.FACTORS];
    this.swapOfNode = new int[p.nodes()];
    java.util.Arrays.fill(swapOfNode, -1);
    this.bookOf = new int[nInstruments];
    this.futureOf = new int[nInstruments];
    final List<Integer> tsy = new ArrayList<>();
    final List<Integer> bk = new ArrayList<>();
    int fut = 0;
    for (final SimInstrument i : universe) {
      final int x = i.index();
      interpolate(x, i.nodeYears());
      bookOf[x] = -1;
      futureOf[x] = -1;
      if (i.kind() == SimInstrument.Kind.SWAP) {
        if (nodeWeightHigh[x] == 0.0 && swapOfNode[nodeLow[x]] < 0) {
          swapOfNode[nodeLow[x]] = x;
        }
      } else {
        bookOf[x] = bk.size();
        bk.add(x);
        if (i.kind() == SimInstrument.Kind.TREASURY) {
          tsy.add(x);
        } else {
          futureOf[x] = fut++;
        }
      }
    }
    this.futures = fut;
    this.treasuries = tsy.stream().mapToInt(Integer::intValue).toArray();
    this.books = bk.stream().mapToInt(Integer::intValue).toArray();
    for (int n = 0; n < p.nodes(); n++) {
      if (p.tenorMix(n) > 0.0 && swapOfNode[n] < 0) {
        throw new IllegalArgumentException("no swap instrument at the " + p.tenorYears(n) + "-year node of rfq.tenor_mix");
      }
    }
    if (treasuries.length == 0 && p.get(Scalar.TREASURY_RFQS_PER_SWAP_RFQ) > 0.0) {
      throw new IllegalArgumentException("Treasury RFQs need at least one Treasury in the universe");
    }
    if (futures > Streams.MAX_FUTURES) {
      throw new IllegalArgumentException("the stream table has room for " + Streams.MAX_FUTURES + " futures");
    }

    // zero-profit identity (section 7): median x E[min] = adverse move + all-in cost
    this.expectedBestPerMedian = Competition.expectedBestPerMedian(p);
    final double winner = p.get(Scalar.MEDIAN_HALF_SPREAD_BP) * expectedBestPerMedian;
    final double needed = p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP) + p.get(Scalar.ALL_IN_COST_BP);
    if (Math.abs(winner - needed) > p.get(Scalar.ZERO_PROFIT_TOLERANCE_BP)) {
      throw new IllegalArgumentException(
          "zero-profit identity fails: expected winning half-spread " + winner + " bp against adverse move plus all-in cost "
              + needed + " bp");
    }

    // informed variance (section 6) and the diffusive level sd that keeps the total calibrated
    final double total = p.factorSdPerSqrtMinute(0) * p.factorSdPerSqrtMinute(0) * p.get(Scalar.SESSION_MINUTES);
    this.informedVarianceBp2 = informedVariance();
    if (informedVarianceBp2 > p.get(Scalar.MAX_INFORMED_VARIANCE_SHARE) * total) {
      throw new IllegalArgumentException(
          "informed flow would supply " + informedVarianceBp2 + " bp^2 of the level factor's " + total
              + " bp^2 per session, above the allowed share");
    }
    this.levelDiffusiveSd = Math.sqrt((total - informedVarianceBp2) / p.get(Scalar.SESSION_MINUTES));

    // order-book scales (section 8)
    this.dailyVolumeUnits = new double[books.length];
    this.dailyPriceSd = new double[books.length];
    this.touchTargetUnits = new double[books.length];
    this.takerMedianUnits = new double[books.length];
    this.takerMeanUnits = new double[books.length];
    final double treasuryVolumeUsd = p.get(Scalar.TREASURY_DAILY_VOLUME_USD_BN) * 1e9;
    for (int b = 0; b < books.length; b++) {
      final SimInstrument i = universe.get(books[b]);
      dailyVolumeUnits[b] =
          i.kind() == SimInstrument.Kind.FUTURE
              ? p.get(Scalar.FUTURES_DAILY_VOLUME_CONTRACTS)
              : treasuryVolumeUsd / i.unitFaceUsd();
      double variance = 0.0;
      for (int j = 0; j < SimParams.FACTORS; j++) {
        final double v = loading[books[b]][j] * p.factorDailySdBp(j);
        variance += v * v;
      }
      dailyPriceSd[b] = Math.abs(i.priceChangePerBp()) * Math.sqrt(variance);
      // q x V_i / V_reference, both volumes in USD face; whole lots, at least one
      final double shareOfReference = dailyVolumeUnits[b] * i.unitFaceUsd() / treasuryVolumeUsd;
      final double units = p.get(Scalar.TOUCH_DEPTH_USD_MM) * 1e6 * shareOfReference / i.unitFaceUsd();
      touchTargetUnits[b] = Math.max(1.0, Math.floor(units / i.minQuantity())) * i.minQuantity();
      takerMedianUnits[b] = p.get(Scalar.TAKER_TRADE_SIZE_USD_MM) * 1e6 / i.unitFaceUsd();
      takerMeanUnits[b] = meanRoundedUp(takerMedianUnits[b], p.get(Scalar.TAKER_SIZE_LOG_SD), i.minQuantity());
    }
  }

  private void interpolate(final int x, final double years) {
    final int last = p.nodes() - 1;
    int low = 0;
    double w = 0.0;
    if (years >= p.tenorYears(last)) {
      low = last;
    } else if (years > p.tenorYears(0)) {
      while (p.tenorYears(low + 1) <= years) {
        low++;
      }
      w = (years - p.tenorYears(low)) / (p.tenorYears(low + 1) - p.tenorYears(low));
    }
    nodeLow[x] = low;
    nodeWeightHigh[x] = w;
    for (int j = 0; j < SimParams.FACTORS; j++) {
      loading[x][j] = w == 0.0 ? p.loading(low, j) : (1.0 - w) * p.loading(low, j) + w * p.loading(low + 1, j);
    }
  }

  /** V_inf of section 6 with the universe's own RFQ mix: swaps by tenor_mix, Treasuries uniform. */
  private double informedVariance() {
    final double move = p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP);
    final double swaps = p.get(Scalar.SWAP_RFQS_PER_DAY);
    final double tsys = swaps * p.get(Scalar.TREASURY_RFQS_PER_SWAP_RFQ);
    double swapTerm = 0.0;
    for (int n = 0; n < p.nodes(); n++) {
      if (p.tenorMix(n) > 0.0) {
        final double l = loading[swapOfNode[n]][0];
        swapTerm += p.tenorMix(n) / (l * l);
      }
    }
    double tsyTerm = 0.0;
    for (final int t : treasuries) {
      tsyTerm += 1.0 / (loading[t][0] * loading[t][0] * treasuries.length);
    }
    return p.get(Scalar.INFORMED_FRACTION) * move * move * (swaps * swapTerm + tsys * tsyTerm);
  }

  /**
   * Mean of a lognormal size rounded up to a whole number of lots: lot x sum over n >= 0 of
   * P(size > n lots). The taker rate uses it so that the flow's volume, after rounding, is the
   * declared daily volume.
   */
  static double meanRoundedUp(final double median, final double logSd, final double lot) {
    double sum = 1.0;
    for (int n = 1; n < 50_000_000; n++) {
      final double tail = 1.0 - Gaussian.cdf(Math.log(n * lot / median) / logSd);
      sum += tail;
      if (tail < 1e-13) {
        break;
      }
    }
    return sum * lot;
  }

  // ------------------------------------------------------------------ access

  /** The parameters this was built from. */
  public SimParams params() {
    return p;
  }

  /** Steps in a session (36,000 at the committed parameters). */
  public int steps() {
    return steps;
  }

  /** Session length in nanoseconds. */
  public long sessionNanos() {
    return steps * STEP_NANOS;
  }

  /** Step whose state a read at {@code nanos} after the session start returns: floor, clamped to [0, steps]. */
  public int stepAt(final long nanos) {
    if (nanos <= 0) {
      return 0;
    }
    final long k = nanos / STEP_NANOS;
    return k >= steps ? steps : (int) k;
  }

  /** Number of instruments. */
  public int instruments() {
    return nInstruments;
  }

  /** Instrument by index. */
  public SimInstrument instrument(final int index) {
    return p.instruments().get(index);
  }

  /** Index of the instrument with this key; throws if there is none. */
  public int indexOf(final String key) {
    for (final SimInstrument i : p.instruments()) {
      if (i.key().equals(key)) {
        return i.index();
      }
    }
    throw new IllegalArgumentException("no instrument " + key);
  }

  /** Loading of instrument {@code i} on factor {@code j}, interpolated in maturity, bp per factor unit. */
  public double loading(final int i, final int j) {
    return loading[i][j];
  }

  /** Lower tenor node of instrument {@code i}. */
  public int nodeLow(final int i) {
    return nodeLow[i];
  }

  /** Weight of the next node above {@link #nodeLow} in instrument {@code i}'s yield; 0 on a node. */
  public double nodeWeightHigh(final int i) {
    return nodeWeightHigh[i];
  }

  /** Swap instrument at tenor node {@code n}. */
  public int swapOfNode(final int n) {
    return swapOfNode[n];
  }

  /** Number of Treasuries; RFQs are uniform over them. */
  public int treasuryCount() {
    return treasuries.length;
  }

  /** Instrument index of Treasury {@code t}. */
  public int treasury(final int t) {
    return treasuries[t];
  }

  /** Number of order-book instruments. */
  public int bookCount() {
    return books.length;
  }

  /** Instrument index of order book {@code b}. */
  public int bookInstrument(final int b) {
    return books[b];
  }

  /** Order-book number of instrument {@code i}, or -1 for a swap. */
  public int bookOf(final int i) {
    return bookOf[i];
  }

  /** Futures contract number of instrument {@code i}, or -1. */
  public int futureOf(final int i) {
    return futureOf[i];
  }

  /** Number of futures contracts. */
  public int futureCount() {
    return futures;
  }

  /** V_inf: variance informed flow adds to the level factor per session, bp^2 in factor units. */
  public double informedVarianceBp2() {
    return informedVarianceBp2;
  }

  /** s_1: the level factor's diffusive sd outside event windows, bp per sqrt-minute. */
  public double levelDiffusiveSdPerSqrtMinute() {
    return levelDiffusiveSd;
  }

  /** E[min]: mean lowest answering competitor half-spread per unit median. */
  public double expectedBestPerMedian() {
    return expectedBestPerMedian;
  }

  /** Competitor median half-spread for instrument {@code i}, bp of yield. */
  public double medianHalfSpreadBp(final int i) {
    final double m = p.get(Scalar.MEDIAN_HALF_SPREAD_BP);
    return instrument(i).kind() == SimInstrument.Kind.SWAP ? m : m * p.get(Scalar.TREASURY_HALF_SPREAD_MULTIPLE);
  }

  /** V_i: daily volume of order book {@code b}, quantity units. */
  public double dailyVolumeUnits(final int b) {
    return dailyVolumeUnits[b];
  }

  /** sigma_i: daily sd of order book {@code b}'s price, quoted units. */
  public double dailyPriceSd(final int b) {
    return dailyPriceSd[b];
  }

  /** q_i: provider target size at the touch of order book {@code b}, quantity units. */
  public double touchTargetUnits(final int b) {
    return touchTargetUnits[b];
  }

  /** Median size of another taker's trade on order book {@code b}, quantity units. */
  public double takerMedianUnits(final int b) {
    return takerMedianUnits[b];
  }

  /** Mean size of another taker's trade on order book {@code b} after rounding up to lots, quantity units. */
  public double takerMeanUnits(final int b) {
    return takerMeanUnits[b];
  }

  /** Peak impact of a quantity on order book {@code b}: Y x sigma x sqrt(|Q| / V), quoted units. */
  public double peakImpact(final int b, final double quantityUnits) {
    return p.get(Scalar.IMPACT_Y) * dailyPriceSd[b] * Math.sqrt(Math.abs(quantityUnits) / dailyVolumeUnits[b]);
  }

  /** Factor by which transient impact and consumed depth shrink over one step. */
  public double impactDecayPerStep() {
    return Math.exp(-Math.log(2.0) * p.get(Scalar.STEP_SECONDS) / p.get(Scalar.IMPACT_HALF_LIFE_S));
  }
}
