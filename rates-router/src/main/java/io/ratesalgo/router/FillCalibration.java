package io.ratesalgo.router;

/**
 * The passive fill probability calibrated online from the router's own outcomes, per venue and
 * per queue-position bucket (DECISIONS.md DR-R6-R02).
 *
 * <p>When a passive child placed with a model probability {@code p} ends at its time limit or
 * fills, the bucket of the queue ahead of it at placement adds {@code p} to its predicted count
 * and the filled share of the child to its observed count; both decay by {@code calibrationDecay}
 * at each new outcome. A child cancelled for another reason (the book moved, the parent stopped,
 * the venue went unhealthy) is censored and adds nothing. The calibrated probability is the model
 * probability times {@code (k + observed) / (k + predicted)}, capped at 1, where {@code k} is the
 * prior's weight in pseudo-fills: with no outcomes it is the model itself. Allocation-free.
 */
public final class FillCalibration {

  /** Queue buckets: nothing ahead, 1 to 4 lots, 5 to 19 lots, 20 lots or more. */
  public static final int BUCKETS = 4;

  private final double[][] observed;
  private final double[][] predicted;
  private final double decay;
  private long outcomes;

  /**
   * @param rows venue rows
   * @param decay weight kept by older outcomes at each new one
   */
  public FillCalibration(final int rows, final double decay) {
    observed = new double[rows][BUCKETS];
    predicted = new double[rows][BUCKETS];
    this.decay = decay;
  }

  /** Bucket of a queue of {@code aheadLots} lots. */
  public static int bucket(final long aheadLots) {
    if (aheadLots <= 0) {
      return 0;
    }
    if (aheadLots < 5) {
      return 1;
    }
    return aheadLots < 20 ? 2 : 3;
  }

  /** One uncensored outcome. */
  public void record(final int row, final int bucket, final double predictedP, final double filledShare) {
    observed[row][bucket] = observed[row][bucket] * decay + Math.min(1.0, Math.max(0.0, filledShare));
    predicted[row][bucket] = predicted[row][bucket] * decay + Math.min(1.0, Math.max(0.0, predictedP));
    outcomes++;
  }

  /** Decayed observed fills of a bucket. */
  public double observed(final int row, final int bucket) {
    return observed[row][bucket];
  }

  /** Decayed predicted fills of a bucket. */
  public double predicted(final int row, final int bucket) {
    return predicted[row][bucket];
  }

  /** Outcomes recorded. */
  public long outcomes() {
    return outcomes;
  }

  /** The calibrated probability from a model probability and a bucket's counts. */
  public static double calibrate(
      final double modelP, final double obs, final double pred, final double priorFills) {
    if (!(modelP > 0.0)) {
      return 0.0;
    }
    return Math.min(1.0, modelP * (priorFills + obs) / (priorFills + pred));
  }
}
