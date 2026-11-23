package io.ratesalgo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Decimal9 against BigDecimal as the independent oracle. The oracle for a double {@code v} is
 * {@code new BigDecimal(v)} moved nine places: the exact value of the double, not of its decimal
 * spelling. Decimal9 multiplies in double; {@code slack(v)} is the error that may leave. Decimal9
 * has no parse, format, multiply or divide entry points; the conversions and the range checks are
 * its whole surface.
 */
class Decimal9PropertiesTest {

  private static final double MAX = 9.2e9;
  // the double Decimal9 adds before floor and subtracts before ceil, at its exact binary value
  private static final double ALLOWANCE = 1e-6;
  private static final double WHOLE = 0x1p52;
  private static final double TINY = 1e-9;

  private static BigDecimal exact(final double v) {
    return new BigDecimal(v).movePointRight(9);
  }

  /**
   * The error Decimal9 may carry from its double product: a full ulp below 2^52, where the product
   * still has a fraction; from 2^52 on Decimal9 recovers the product's exact error, so only the
   * rounding of that small remainder is left.
   */
  private static BigDecimal slack(final double v) {
    final double p = v * 1e9;
    return new BigDecimal(Math.abs(p) < WHOLE ? Math.ulp(p) : TINY);
  }

  private static boolean within(final BigDecimal a, final BigDecimal b, final BigDecimal tol) {
    return a.subtract(b).abs().compareTo(tol) <= 0;
  }

  // ---------------------------------------------------------------- toDouble

  /** Every mantissa a double can hold exactly converts to the nearest double of m / 10^9. */
  @Property(tries = 2000)
  void toDoubleIsTheNearestDouble(@ForAll @LongRange(min = -(1L << 53), max = 1L << 53) final long m) {
    assertEquals(new BigDecimal(m).movePointLeft(9).doubleValue(), Decimal9.toDouble(m));
  }

  /** Past 2^53 the mantissa itself is rounded first; still within two ulps, never a sign flip. */
  @Property(tries = 1000)
  void toDoubleOfAnyMantissaIsCloseAndKeepsItsSign(@ForAll final long m) {
    final double d = Decimal9.toDouble(m);
    final double want = new BigDecimal(m).movePointLeft(9).doubleValue();
    assertTrue(Math.abs(d - want) <= 2 * Math.ulp(want), m + " -> " + d);
    assertEquals(Long.signum(m), (int) Math.signum(d));
  }

  // ---------------------------------------------------------------- round trip

  /**
   * Mantissa to double and back is the identity while the double's error stays under half a unit
   * (|m| below 2^51, about 2.25 million in major units, far past any price or rate).
   */
  @Property(tries = 3000)
  void roundTripsEveryMantissaBelow2To51(@ForAll @LongRange(min = -(1L << 51), max = 1L << 51) final long m) {
    assertEquals(m, Decimal9.round(Decimal9.toDouble(m)));
  }

  /** floor and ceil give the mantissa back when the double is the nearest one to m / 10^9. */
  @Property(tries = 3000)
  void floorAndCeilRoundTripPrices(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) final long m) {
    // up to 1000 in major units the product error (ulp(1e12) is about 1.2e-4) can pass the 1e-6
    // allowance, so the oracle is the exact value of the double, not m itself
    final double v = Decimal9.toDouble(m);
    final BigDecimal x = exact(v);
    assertEquals(floorOracle(v, x), Decimal9.floor(v), "floor " + m);
    assertEquals(ceilOracle(v, x), Decimal9.ceil(v), "ceil " + m);
  }

  // ---------------------------------------------------------------- rounding direction

  /** round is within half a unit (plus the product error) of the exact value, and exact off ties. */
  @Property(tries = 3000)
  void roundIsNearest(@ForAll @DoubleRange(min = -MAX, max = MAX, minIncluded = false, maxIncluded = false) final double v) {
    checkRound(v);
  }

  /** The same over the range prices and rates live in, where every digit counts. */
  @Property(tries = 3000)
  void roundIsNearestForPrices(@ForAll @DoubleRange(min = -1e4, max = 1e4) final double v) {
    checkRound(v);
  }

  private static void checkRound(final double v) {
    final BigDecimal x = exact(v);
    final long r = Decimal9.round(v);
    final BigDecimal tol = new BigDecimal("0.5").add(slack(v));
    assertTrue(within(BigDecimal.valueOf(r), x, tol), v + " -> " + r);
    // away from a tie by more than the product error, the answer is the exact nearest
    final BigDecimal frac = x.subtract(new BigDecimal(x.toBigInteger())).abs();
    if (!within(frac, new BigDecimal("0.5"), slack(v))) {
      assertEquals(x.setScale(0, RoundingMode.HALF_UP).longValueExact(), r, "nearest of " + v);
    }
  }

  /**
   * Rounding is symmetric: -v rounds to minus what v rounds to. The class documents ties as half
   * away from zero; Math.round alone takes them toward positive infinity.
   */
  @Property(tries = 3000)
  void roundIsSymmetricAboutZero(@ForAll @DoubleRange(min = -MAX, max = MAX, minIncluded = false, maxIncluded = false) final double v) {
    assertEquals(-Decimal9.round(v), Decimal9.round(-v), "round(" + v + ")");
  }

  /** Exact half-unit ties round away from zero on both sides. */
  @Property(tries = 2000)
  void tiesRoundAwayFromZero(@ForAll @LongRange(min = -(1L << 40), max = 1L << 40) final long k) {
    // k + 0.5 is exact in double, but (k + 0.5) / 1e9 times 1e9 lands back on it only sometimes;
    // the other k test nothing here (the nearest-value property covers them)
    final double v = (k + 0.5) / 1e9;
    if (v * 1e9 != k + 0.5) {
      return;
    }
    final long away = k >= 0 ? k + 1 : k;
    assertEquals(away, Decimal9.round(v), "tie " + (k + 0.5));
  }

  /** floor never above the exact value by more than the 1e-6 allowance plus the product error. */
  @Property(tries = 3000)
  void floorRoundsDownAndCeilUp(@ForAll @DoubleRange(min = -MAX, max = MAX, minIncluded = false, maxIncluded = false) final double v) {
    final BigDecimal x = exact(v);
    assertEquals(floorOracle(v, x), Decimal9.floor(v), "floor " + v);
    assertEquals(ceilOracle(v, x), Decimal9.ceil(v), "ceil " + v);
  }

  /** A bid is never above the rounded price, an ask never below, and they are at most a unit apart. */
  @Property(tries = 3000)
  void bidAtOrBelowRoundAtOrBelowAsk(@ForAll @DoubleRange(min = -MAX, max = MAX, minIncluded = false, maxIncluded = false) final double v) {
    final long f = Decimal9.floor(v);
    final long r = Decimal9.round(v);
    final long c = Decimal9.ceil(v);
    assertTrue(f <= r && r <= c && c - f <= 1, v + ": " + f + " " + r + " " + c);
    assertEquals(-Decimal9.ceil(v), Decimal9.floor(-v), "floor(-v) is -ceil(v)");
  }

  /**
   * floor(v) is the floor of the exact value, or one more when the exact value is within the
   * 1e-6 allowance (plus the product error) under the next integer; ceil mirrors it.
   */
  private static long floorOracle(final double v, final BigDecimal x) {
    final long down = x.setScale(0, RoundingMode.FLOOR).longValueExact();
    final BigDecimal gap = BigDecimal.valueOf(down + 1).subtract(x);
    final BigDecimal allowance = new BigDecimal(ALLOWANCE).add(slack(v));
    final long f = Decimal9.floor(v);
    if (f == down + 1) {
      assertTrue(gap.compareTo(allowance) <= 0, "floor rounded up by more than the allowance: " + v);
      return f;
    }
    return down;
  }

  private static long ceilOracle(final double v, final BigDecimal x) {
    final long up = x.setScale(0, RoundingMode.CEILING).longValueExact();
    final BigDecimal gap = x.subtract(BigDecimal.valueOf(up - 1));
    final BigDecimal allowance = new BigDecimal(ALLOWANCE).add(slack(v));
    final long c = Decimal9.ceil(v);
    if (c == up - 1) {
      assertTrue(gap.compareTo(allowance) <= 0, "ceil rounded down by more than the allowance: " + v);
      return c;
    }
    return up;
  }

  // ---------------------------------------------------------------- the long bounds

  /** At and past +-9.2e9 every rounding entry point throws; nothing wraps through the long range. */
  @Property(tries = 1000)
  void outOfRangeThrows(@ForAll @DoubleRange(min = MAX, max = Double.MAX_VALUE) final double big) {
    for (final double v : new double[] {big, -big}) {
      assertThrows(IllegalArgumentException.class, () -> Decimal9.round(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.floor(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.ceil(v));
    }
  }

  /** The bound itself is out of range: 9.2e9 throws and saturates, the next double down converts. */
  @Property(tries = 1)
  void theBoundIsExclusive() {
    final double below = Math.nextDown(MAX);
    for (final double v : new double[] {MAX, -MAX}) {
      assertThrows(IllegalArgumentException.class, () -> Decimal9.round(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.floor(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.ceil(v));
    }
    assertEquals(Long.MAX_VALUE, Decimal9.saturating(MAX));
    assertEquals(Long.MIN_VALUE + 1, Decimal9.saturating(-MAX));
    assertEquals(exact(below).setScale(0, RoundingMode.HALF_UP).longValueExact(), Decimal9.round(below));
    assertEquals(Decimal9.round(below), Decimal9.saturating(below));
    assertEquals(-Decimal9.round(below), Decimal9.saturating(-below));
  }

  @Property(tries = 1)
  void nanAndInfinitiesThrow() {
    for (final double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> Decimal9.round(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.floor(v));
      assertThrows(IllegalArgumentException.class, () -> Decimal9.ceil(v));
    }
    assertEquals(Long.MIN_VALUE, Decimal9.saturating(Double.NaN));
    assertEquals(Long.MAX_VALUE, Decimal9.saturating(Double.POSITIVE_INFINITY));
    assertEquals(Long.MIN_VALUE + 1, Decimal9.saturating(Double.NEGATIVE_INFINITY));
  }

  /** Just inside the bound the result keeps its sign and magnitude: no silent wrap. */
  @Property(tries = 2000)
  void justInsideTheBoundNothingWraps(@ForAll @DoubleRange(min = 9.0e9, max = MAX, maxIncluded = false) final double big) {
    for (final double v : new double[] {big, -big}) {
      for (final long m : new long[] {Decimal9.round(v), Decimal9.floor(v), Decimal9.ceil(v), Decimal9.saturating(v)}) {
        assertEquals((int) Math.signum(v), Long.signum(m), v + " -> " + m);
        assertTrue(within(BigDecimal.valueOf(m), exact(v), BigDecimal.ONE.add(slack(v))), v + " -> " + m);
      }
    }
  }

  /** saturating clamps past the bound and otherwise rounds exactly as round does. */
  @Property(tries = 3000)
  void saturatingClampsOrRounds(@ForAll final double v) {
    final long s = Decimal9.saturating(v);
    if (Double.isNaN(v)) {
      assertEquals(Long.MIN_VALUE, s);
    } else if (v >= MAX) {
      assertEquals(Long.MAX_VALUE, s);
    } else if (v <= -MAX) {
      assertEquals(Long.MIN_VALUE + 1, s);
    } else {
      assertEquals(Decimal9.round(v), s, "saturating(" + v + ")");
    }
  }

  @Property(tries = 1)
  void oneIsTenToTheNine() {
    assertEquals(BigDecimal.ONE.movePointRight(9).longValueExact(), Decimal9.ONE);
    assertEquals(1.0, Decimal9.toDouble(Decimal9.ONE));
  }
}
