package io.ratesalgo.risk;

/**
 * Rate limiter: a bucket of {@code rate * burstSeconds} tokens refilled continuously at {@code
 * rate} per second of monotonic time. In any interval of length T at most {@code rate * (T +
 * burstSeconds)} tokens are taken. Allocation-free.
 */
public final class TokenBucket {

  private double ratePerNano;
  private double capacity;
  private double tokens;
  private long lastNanos;

  /** A full bucket at {@code startNanos}. */
  public TokenBucket(final double ratePerSecond, final double burstSeconds, final long startNanos) {
    reset(ratePerSecond, burstSeconds, startNanos);
  }

  /** Reconfigures and refills. */
  public void reset(final double ratePerSecond, final double burstSeconds, final long startNanos) {
    ratePerNano = ratePerSecond / 1e9;
    capacity = Math.max(1.0, ratePerSecond * burstSeconds);
    tokens = capacity;
    lastNanos = startNanos;
  }

  /** True if a token is available at {@code nowNanos}; does not take it. */
  public boolean available(final long nowNanos) {
    refill(nowNanos);
    return tokens >= 1.0;
  }

  /** Takes a token; call only after {@link #available(long)} returned true. */
  public void take() {
    tokens -= 1.0;
  }

  /** Takes a token even if none is available (for exempt messages such as cancels). */
  public void takeExempt(final long nowNanos) {
    refill(nowNanos);
    tokens = Math.max(tokens - 1.0, -capacity);
  }

  /** Tokens currently in the bucket. */
  public double tokens(final long nowNanos) {
    refill(nowNanos);
    return tokens;
  }

  /** Bucket depth. */
  public double capacity() {
    return capacity;
  }

  private void refill(final long nowNanos) {
    if (nowNanos > lastNanos) {
      tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) * ratePerNano);
      lastNanos = nowNanos;
    }
  }
}
