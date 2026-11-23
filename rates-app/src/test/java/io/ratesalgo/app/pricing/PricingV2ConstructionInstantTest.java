package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.common.time.ManualClock;
import org.junit.jupiter.api.Test;

/**
 * Pricing v2 is a function of its timed inputs and not of the instant it was built at
 * (docs/research/DESK_V2_WIRING.md Section 8, fourth row). A live loop builds its binding before
 * the first input and its replay builds one on the first journaled frame, so two bindings built at
 * different instants and given the same inputs and the same {@code advanceTo} calls must hold the
 * same state to the bit, including when one is built just before a New York cut and the other
 * just after it.
 */
class PricingV2ConstructionInstantTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final long MILLI = 1_000_000L;
  private static final long SECOND = V2Feed.SECOND;
  private static final double BP = 1e-4;
  /** Monday 2 November 2026 17:00 New York, the first cut after the session start. */
  private static final long MONDAY_CUT = BusinessDate.nextCut(V2Feed.T0, BusinessDate.DEFAULT_CUT_HOUR);

  @Test
  void bindingsBuiltSecondsApartPriceAlike() {
    final Pair p = new Pair(V2Feed.T0, V2Feed.T0 + 7 * SECOND);
    p.script(V2Feed.T0 + 10 * SECOND);
  }

  @Test
  void bindingsBuiltEitherSideOfACutPriceAlike() {
    final Pair p = new Pair(MONDAY_CUT - 100 * MILLI, MONDAY_CUT + 100 * MILLI);
    p.script(MONDAY_CUT + 5 * SECOND);
  }

  @Test
  void theDeskFactoryReadsNoClock() {
    // the desk's construction: one binding built on Sunday night, one on Thursday, given Monday
    final ManualClock sunday = new ManualClock(V2Feed.T0 - 14 * 3_600 * SECOND);
    final ManualClock thursday = new ManualClock(V2Feed.T0 + 72 * 3_600 * SECOND);
    final Pair p =
        new Pair(
            (PricingV2) PricingBindings.create(PricingBindings.Version.V2, UNIVERSE, sunday),
            (PricingV2) PricingBindings.create(PricingBindings.Version.V2, UNIVERSE, thursday));
    p.script(V2Feed.T0 + SECOND);
  }

  /** Two bindings on one book, driven by one script, compared after every step. */
  private static final class Pair {
    final PricingV2 a;
    final PricingV2 b;
    final InMemoryBookSource book = new InMemoryBookSource(8);
    int step;

    Pair(final long builtA, final long builtB) {
      this(new PricingV2(UNIVERSE, new ManualClock(builtA), PricingV2.Options.defaults()),
          new PricingV2(UNIVERSE, new ManualClock(builtB), PricingV2.Options.defaults()));
    }

    Pair(final PricingV2 a, final PricingV2 b) {
      this.a = a;
      this.b = b;
      books(0L, V2Feed.T0);
      a.book(book);
      b.book(book);
    }

    void books(final long offset, final long nanos) {
      final long tick = UNIVERSE.bondTick();
      for (final UsdUniverse.BondSpec s : UNIVERSE.bonds()) {
        final long mid = s.initialMid() + offset;
        book.set(s.instrumentId(), mid - tick, 100_000_000L, mid + tick, 100_000_000L, nanos);
      }
    }

    void script(final long first) {
      long now = first;
      // the feed of today: the four swap templates and nothing else, so no input is fed for the
      // first time and the residuals are not re-marked; this is where the start-of-day stamp
      // showed
      for (int r = 0; r < 4; r++) {
        books((r % 3 - 1) * UNIVERSE.bondTick(), now);
        for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
          final long m = s.initialMid() + (r - 1) * 200_000L + s.tenorMonths() * 500L;
          a.onCurveInput(s.instrumentId(), m, now);
          b.onCurveInput(s.instrumentId(), m, now);
        }
        same();
        now += V2Feed.ROUND;
      }
      // full rounds of the build set, one of them left incomplete for a timer to apply
      for (int r = 0; r < 6; r++) {
        advance(now);
        books((r % 2) * UNIVERSE.bondTick(), now);
        round(now, 0.3 * BP * (r - 2), r == 3 ? 5 : a.inputCount() + 1);
        if (r == 3) {
          advance(now + 50 * MILLI);
          advance(now + 150 * MILLI);
        }
        now += V2Feed.ROUND;
      }
      // across the next cut, by a timer, then rounds on the new date
      advance(BusinessDate.nextCut(now, BusinessDate.DEFAULT_CUT_HOUR) + MILLI);
      now = BusinessDate.nextCut(now, BusinessDate.DEFAULT_CUT_HOUR) + SECOND;
      for (int r = 0; r < 3; r++) {
        advance(now);
        round(now, 0.5 * BP, a.inputCount() + 1);
        now += V2Feed.ROUND;
      }
      assertEquals(a.businessDate(), b.businessDate());
    }

    void advance(final long t) {
      a.advanceTo(t);
      b.advanceTo(t);
      same();
    }

    /** Sends the first {@code count} inputs of a round (the 12-year mark is the last). */
    void round(final long now, final double shift, final int count) {
      final SofrCurve c = a.curve();
      final int n = a.inputCount();
      assertEquals(n, b.inputCount());
      for (int i = 0; i < n && i < count; i++) {
        assertEquals(a.inputId(i), b.inputId(i));
        final double rate = V2Feed.rate(c, i) + shift;
        final long m =
            c.quotePerRate(i) == 1.0 ? Math.round(rate * 1e11) : Math.round(c.quoteForRate(i, rate) * 1e9);
        a.onCurveInput(a.inputId(i), m, now);
        b.onCurveInput(b.inputId(i), m, now);
        same();
      }
      if (count > n) {
        final long mark = Math.round((V2Feed.swapRate(144) + shift) * 1e11);
        a.onCurveInput(V2Feed.SWAP_12Y, mark, now);
        b.onCurveInput(V2Feed.SWAP_12Y, mark, now);
        same();
      }
    }

    void same() {
      step++;
      final long[] x = V2Feed.bits(a);
      final long[] y = V2Feed.bits(b);
      assertEquals(x.length, y.length, "step " + step);
      for (int i = 0; i < x.length; i++) {
        assertEquals(x[i], y[i], "step " + step + ", value " + i);
      }
    }
  }
}
