package io.ratesalgo.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * The token bucket against its documented bound: over an interval of length T a greedy taker gets
 * the burst {@code max(1, rate * burst)} plus {@code rate * T} tokens, give or take the one token
 * still filling at the end.
 */
class TokenBucketPropertiesTest {

  @Property(tries = 300, seed = "7")
  void aGreedyTakerGetsTheBurstPlusTheRateTimesTheElapsedTime(
      @ForAll @DoubleRange(min = 1.0, max = 2_000.0) final double rate,
      @ForAll @DoubleRange(min = 2.0, max = 4.0) final double burst,
      @ForAll @IntRange(min = 1, max = 400) final int polls,
      @ForAll @LongRange(min = 0L, max = 1_000_000L) final long start) {
    final TokenBucket bucket = new TokenBucket(rate, burst, start);
    final double capacity = Math.max(1.0, rate * burst);
    assertEquals(capacity, bucket.capacity(), 1e-12);
    assertEquals(capacity, bucket.tokens(start), 1e-12, "starts full");
    // poll at most half a token apart, so no refill is lost to the cap once the burst is spent
    final long step = Math.max(1L, (long) (0.5e9 / rate));
    long now = start;
    long taken = 0;
    for (int p = 0; p < polls; p++) {
      while (bucket.available(now)) {
        bucket.take();
        taken++;
      }
      now += step;
    }
    final long end = now - step;
    final double allowed = capacity + rate * (end - start) / 1e9;
    assertTrue(taken <= allowed + 1e-6, "taken " + taken + " allowed " + allowed);
    assertTrue(taken >= allowed - 1.0 - 1e-6, "taken " + taken + " allowed " + allowed);
    assertEquals(allowed - taken, bucket.tokens(end), 1e-6, "what is left is exactly the rest");
  }

  @Property(tries = 200, seed = "11")
  void exemptTakesDriveTheBucketNegativeButNoLowerThanMinusItsCapacity(
      @ForAll @DoubleRange(min = 1.0, max = 50.0) final double rate,
      @ForAll @IntRange(min = 0, max = 200) final int exempt) {
    final TokenBucket bucket = new TokenBucket(rate, 1.0, 0L);
    final double capacity = Math.max(1.0, rate);
    for (int i = 0; i < exempt; i++) {
      bucket.takeExempt(0L);
    }
    assertEquals(Math.max(-capacity, capacity - exempt), bucket.tokens(0L), 1e-9);
    assertEquals(capacity - exempt >= 1.0, bucket.available(0L));
  }

  @Test
  void aBucketSlowerThanOnePerBurstStillHoldsOneToken() {
    final TokenBucket bucket = new TokenBucket(0.5, 1.0, 0L);
    assertEquals(1.0, bucket.capacity(), 0.0);
    assertTrue(bucket.available(0L));
    bucket.take();
    assertFalse(bucket.available(1_999_999_999L), "half a token a second: two seconds per token");
    assertTrue(bucket.available(2_000_000_000L));
  }

  @Test
  void timeGoingBackwardsNeitherRefillsNorMovesTheClockBack() {
    final TokenBucket bucket = new TokenBucket(1.0, 1.0, 10_000_000_000L);
    bucket.take();
    assertEquals(0.0, bucket.tokens(5_000_000_000L), 0.0);
    // had the backwards read moved the clock to 5 s, 10.5 s would refill 5.5 tokens, capped to 1
    assertEquals(0.5, bucket.tokens(10_500_000_000L), 1e-12);
  }

  @Test
  void resetRefillsAndReconfigures() {
    final TokenBucket bucket = new TokenBucket(2.0, 1.0, 0L);
    bucket.take();
    bucket.take();
    assertFalse(bucket.available(0L));
    bucket.reset(10.0, 2.0, 1_000L);
    assertEquals(20.0, bucket.capacity(), 0.0);
    assertEquals(20.0, bucket.tokens(1_000L), 0.0);
    bucket.take();
    assertEquals(19.5, bucket.tokens(1_000L + 50_000_000L), 1e-9, "refills at the new rate");
  }
}
