package io.ratesalgo.analytics.curve;

/**
 * Fair value of a quoted instrument as a model value plus a slowly moving residual.
 *
 * <p>Plain idea. The model value (a leave-one-out rate, a fitted spread) does not use the
 * instrument's own quote, but it is not the market level either: the real curve has shape between
 * pillars that no interpolation knows. The gap between quote and model is mostly persistent, so
 * it is carried as a residual that follows the observed gap with a half-life. A quote that jumps
 * away from the model for one update moves the fair value by a small, stated share of the jump; a
 * gap that persists is taken in over a few half-lives, because a persistent gap is the market.
 *
 * <p>Formal statement. For slot k with residual r_k, on an update at time t with quote q and
 * model m, after the previous update at time s:
 *
 * <pre>
 *   g   = 1 - 2^(-(t - s) / h)          h the half-life
 *   r_k = r_k + g (q - m - r_k)
 *   fair = m + r_k
 * </pre>
 *
 * so d fair / d q = g for one update. With h = 300 s and updates 0.5 s apart g = 0.00115: a
 * quote skewed by 1 bp for one update moves its own fair value by 0.0012 bp. Held for a full
 * half-life the skew is half absorbed. Time is taken from the timestamps of the inputs, so the
 * result is a function of the journaled inputs alone.
 *
 * <p>No allocation after construction. Single-threaded.
 */
public final class FairValueSmoother {

  private static final double LN2 = Math.log(2.0);

  private final double halfLifeNanos;
  private final double[] residual;
  private final long[] updated;
  private final boolean[] set;

  /**
   * Creates the smoother.
   *
   * @param slots number of instruments
   * @param halfLifeSeconds half-life of the residual, positive
   */
  public FairValueSmoother(final int slots, final double halfLifeSeconds) {
    if (!(halfLifeSeconds > 0.0)) {
      throw new IllegalArgumentException("half-life must be positive: " + halfLifeSeconds);
    }
    this.halfLifeNanos = halfLifeSeconds * 1e9;
    this.residual = new double[slots];
    this.updated = new long[slots];
    this.set = new boolean[slots];
  }

  /** Share of a quote's gap to the model taken in by one update {@code elapsedNanos} later. */
  public double gain(final long elapsedNanos) {
    return elapsedNanos <= 0 ? 0.0 : -Math.expm1(-LN2 * elapsedNanos / halfLifeNanos);
  }

  /** Sets the residual of slot k to the whole gap: the start-of-day mark. */
  public void reset(final int k, final double quote, final double model, final long nanos) {
    residual[k] = quote - model;
    updated[k] = nanos;
    set[k] = true;
  }

  /**
   * Takes one observation of slot k and returns the fair value. The first observation of a slot
   * is a {@link #reset}. A quote or model that is not finite changes nothing.
   */
  public double update(final int k, final double quote, final double model, final long nanos) {
    if (!Double.isFinite(quote) || !Double.isFinite(model)) {
      return model + residual[k];
    }
    if (!set[k]) {
      reset(k, quote, model, nanos);
      return quote;
    }
    residual[k] += gain(nanos - updated[k]) * (quote - model - residual[k]);
    updated[k] = Math.max(updated[k], nanos);
    return model + residual[k];
  }

  /** Fair value of slot k for a model value, without an observation. */
  public double fair(final int k, final double model) {
    return model + residual[k];
  }

  /** The residual of slot k, zero before its first observation. */
  public double residual(final int k) {
    return residual[k];
  }

  /** True once slot k has had an observation. */
  public boolean isSet(final int k) {
    return set[k];
  }
}
