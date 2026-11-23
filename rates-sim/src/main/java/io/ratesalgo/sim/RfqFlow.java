package io.ratesalgo.sim;

import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates a session's RFQs (design section 5): arrival counts and times from stream 100, then
 * every attribute of RFQ r from its own stream, in a fixed order with a fixed number of draws.
 *
 * <p>Draw order of an RFQ's stream: asset class (uniform), tenor or Treasury (uniform), side
 * (uniform), size (1 + redraws draws, all made), tier (uniform), informed (uniform), dealers asked
 * (uniform), then for each of the most competitors there can be: answers (uniform), delay
 * (normal), half-spread (normal); last the reserve (normal) and done-away (uniform). Draws for
 * competitors that were not asked are made and discarded, so nothing moves in the stream when a
 * parameter changes who is asked.
 */
public final class RfqFlow {

  private static final long MILLI = 1_000_000L;

  private RfqFlow() {}

  /** The RFQs of the session with this seed, in arrival order. */
  public static List<SimRfq> generate(final MarketSpec spec, final long seed) {
    final SimParams p = spec.params();
    final double swaps = p.get(Scalar.SWAP_RFQS_PER_DAY);
    final double total = swaps * (1.0 + p.get(Scalar.TREASURY_RFQS_PER_SWAP_RFQ));
    final long binMillis = Math.round(p.get(Scalar.RFQ_BIN_MINUTES) * 60_000.0);

    final SplitMix64 arrivals = Streams.of(seed, Streams.ARRIVALS);
    final List<long[]> times = new ArrayList<>();
    for (int b = 0; b < p.bins(); b++) {
      final int count = Draws.poisson(total * p.timeOfDayShare(b), arrivals);
      for (int i = 0; i < count; i++) {
        final long millis = b * binMillis + (long) (Draws.uniform(arrivals) * binMillis);
        times.add(new long[] {millis, times.size()});
      }
    }
    // by time; ties keep generation order
    times.sort(Comparator.<long[]>comparingLong(t -> t[0]).thenComparingLong(t -> t[1]));

    final List<SimRfq> out = new ArrayList<>(times.size());
    for (int r = 0; r < times.size(); r++) {
      out.add(one(spec, seed, r, times.get(r)[0] * MILLI, swaps / total));
    }
    return out;
  }

  private static SimRfq one(
      final MarketSpec spec, final long seed, final int r, final long arrivalNanos, final double swapShare) {
    final SimParams p = spec.params();
    final SplitMix64 s = Streams.of(seed, Streams.RFQ + r);

    final boolean swap = Draws.uniform(s) < swapShare;
    final double uInstrument = Draws.uniform(s);
    final int node = swap ? pickNode(p, uInstrument) : -1;
    final int instrument =
        swap ? spec.swapOfNode(node) : spec.treasury((int) (uInstrument * spec.treasuryCount()));
    final boolean buy = Draws.uniform(s) < 0.5;

    final int sizeDraws = 1 + (int) p.get(Scalar.SIZE_REDRAWS);
    final double minMm = p.get(Scalar.MIN_SIZE_MM);
    double sizeMm = Double.NaN;
    for (int k = 0; k < sizeDraws; k++) {
      final double u = Draws.uniform(s);
      if (!Double.isNaN(sizeMm)) {
        continue;
      }
      if (swap) {
        final double mm = Math.exp(p.sizeLogMeanMm(node) + p.sizeLogSd(node) * Gaussian.inverse(u));
        if (mm <= p.sizeCapMm(node)) {
          sizeMm = mm;
        } else if (k == sizeDraws - 1) {
          sizeMm = p.sizeCapMm(node);
        }
      } else {
        sizeMm = ladder(p, u);
      }
    }
    final double roundedMm = Math.max(minMm, Math.rint(sizeMm / minMm) * minMm);

    final int tier = pickTier(p, Draws.uniform(s));
    final boolean informed = Draws.uniform(s) < p.tierInformedProbability(tier);
    final int dealersMin = (int) p.get(Scalar.DEALERS_MIN);
    final int dealersMax = (int) p.get(Scalar.DEALERS_MAX);
    final int asked = Draws.between(dealersMin, dealersMax, s);

    final double median = spec.medianHalfSpreadBp(instrument);
    final double window = p.get(Scalar.RESPONSE_WINDOW_MS);
    final int slots = dealersMax - 1;
    final boolean[] answers = new boolean[slots];
    final long[] delay = new long[slots];
    final double[] half = new double[slots];
    for (int d = 0; d < slots; d++) {
      final boolean responds = Draws.uniform(s) < p.get(Scalar.RESPONSE_PROBABILITY);
      final double delayMs =
          p.get(Scalar.DEALER_DELAY_MEDIAN_MS) * Math.exp(p.get(Scalar.DEALER_DELAY_LOG_SD) * Draws.normal(s));
      half[d] = median * Math.exp(p.get(Scalar.COMPETITOR_LOG_SD) * Draws.normal(s));
      delay[d] = (long) (delayMs * MILLI);
      answers[d] = d < asked - 1 && responds && delayMs < window;
    }
    final double reserve =
        p.get(Scalar.RESERVE_MULTIPLE_MEDIAN) * Math.exp(p.get(Scalar.RESERVE_LOG_SD) * Draws.normal(s)) * median;
    final boolean doneAway = Draws.uniform(s) < p.get(Scalar.DONE_AWAY_PROBABILITY);

    return new SimRfq(
        r, arrivalNanos, instrument, buy, sizeMm, roundedMm * 1e6, tier, informed, asked, answers, delay, half,
        reserve, doneAway);
  }

  private static int pickNode(final SimParams p, final double u) {
    double acc = 0.0;
    for (int n = 0; n < p.nodes() - 1; n++) {
      acc += p.tenorMix(n);
      if (u < acc) {
        return n;
      }
    }
    return p.nodes() - 1;
  }

  private static int pickTier(final SimParams p, final double u) {
    double acc = 0.0;
    for (int t = 0; t < SimParams.TIERS - 1; t++) {
      acc += p.tierWeight(t);
      if (u < acc) {
        return t;
      }
    }
    return SimParams.TIERS - 1;
  }

  private static double ladder(final SimParams p, final double u) {
    double acc = 0.0;
    for (int i = 0; i < p.ladderSteps() - 1; i++) {
      acc += p.ladderWeight(i);
      if (u < acc) {
        return p.ladderSizeMm(i);
      }
    }
    return p.ladderSizeMm(p.ladderSteps() - 1);
  }
}
