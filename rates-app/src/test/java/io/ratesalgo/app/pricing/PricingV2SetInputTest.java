package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.common.time.ManualClock;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Setting an input and repricing are two steps (docs/research/DESK_V2_WIRING.md Section 8, third
 * row). {@link PricingV2#setInput} records; {@link PricingV2#repriceIfDirty} applies everything
 * recorded with one solve; {@link PricingV2#advanceTo} applies a held round once and whole when
 * its conflation time has passed; {@link PricingV2#flush}, the loop's older call, never splits a
 * round.
 */
class PricingV2SetInputTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final double BP = 1e-4;
  private static final long MILLI = 1_000_000L;

  private static V2Feed settled() {
    final PricingV2 p = new PricingV2(UNIVERSE, PricingV2.Options.defaults());
    final V2Feed f = new V2Feed(p, V2Feed.T0);
    f.books(0L);
    p.book(f.book);
    for (int k = 0; k < 10; k++) {
      f.round(0.0);
    }
    return f;
  }

  /** The ids and mantissas of a full round at a shift: the build set, then the 12-year mark. */
  private static long[][] round(final PricingV2 p, final double shift) {
    final SofrCurve c = p.curve();
    final int n = p.inputCount();
    final long[][] r = new long[2][n + 1];
    for (int i = 0; i < n; i++) {
      final double rate = V2Feed.rate(c, i) + shift;
      r[0][i] = p.inputId(i);
      r[1][i] = c.quotePerRate(i) == 1.0 ? Math.round(rate * 1e11) : Math.round(c.quoteForRate(i, rate) * 1e9);
    }
    r[0][n] = V2Feed.SWAP_12Y;
    r[1][n] = Math.round((V2Feed.swapRate(144) + shift) * 1e11);
    return r;
  }

  @Test
  void aBurstOfInputsThenOneRepriceCostsOneSolveAndMatchesACompleteRound() {
    final V2Feed fx = settled();
    final V2Feed fy = settled();
    final PricingV2 x = fx.pricing;
    final PricingV2 y = fy.pricing;
    final long t = fx.now + V2Feed.ROUND;
    final long[][] older = round(x, 0.5 * BP);
    final long[][] newer = round(x, 1.0 * BP);
    final long reprices = x.reprices();
    final long solves = x.rebuilds();
    // every input twice, the older value first: the burst a conflating loop would collapse
    for (int i = 0; i < older[0].length; i++) {
      assertTrue(x.setInput((int) older[0][i], older[1][i], t));
      assertTrue(x.setInput((int) newer[0][i], newer[1][i], t));
      assertEquals(reprices, x.reprices(), "setting an input never reprices");
    }
    assertFalse(x.setInput(999, 1L, t), "not an input of the binding");
    assertEquals(older[0].length, x.pendingInputs());
    assertTrue(x.repriceIfDirty());
    assertEquals(reprices + 1, x.reprices());
    assertEquals(solves + 1, x.rebuilds(), "one bootstrap for the burst");
    assertFalse(x.repriceIfDirty(), "nothing held: nothing done");
    assertEquals(reprices + 1, x.reprices());

    // the same round through the loop's entry point, newest values only: complete, one reprice
    for (int i = 0; i < newer[0].length; i++) {
      y.onCurveInput((int) newer[0][i], newer[1][i], t);
    }
    assertEquals(reprices + 1, y.reprices());
    assertArrayEquals(V2Feed.bits(y), V2Feed.bits(x));
  }

  @Test
  void settingAnInputAloneChangesNoFairValue() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final long[] before = V2Feed.prices(p);
    final long[][] moved = round(p, 2.0 * BP);
    f.now += V2Feed.ROUND;
    f.books(UNIVERSE.bondTick());
    for (int i = 0; i < moved[0].length; i++) {
      p.setInput((int) moved[0][i], moved[1][i], f.now);
      assertArrayEquals(before, V2Feed.prices(p), "after input " + i);
    }
    // the timer before the round's conflation time has passed changes nothing either
    p.advanceTo(f.now + 99 * MILLI);
    assertArrayEquals(before, V2Feed.prices(p));
    assertTrue(p.repriceIfDirty());
    assertFalse(java.util.Arrays.equals(before, V2Feed.prices(p)));
  }

  @Test
  void aRoundThatStraddlesATimerTickIsAppliedOnceAndWhole() {
    final V2Feed fx = settled();
    final V2Feed fy = settled();
    final PricingV2 x = fx.pricing;
    final PricingV2 y = fy.pricing;
    final long t = fx.now + V2Feed.ROUND;
    final long[][] r = round(x, 0.7 * BP);
    final long reprices = x.reprices();
    final int half = r[0].length / 2;
    for (int i = 0; i < half; i++) {
      x.onCurveInput((int) r[0][i], r[1][i], t);
    }
    // the loop's timer, 10 ms apart, as DeskGroup calls it: advanceTo, then flush
    for (long tick = 10 * MILLI; tick <= 60 * MILLI; tick += 10 * MILLI) {
      x.advanceTo(t + tick);
      x.flush();
      assertEquals(reprices, x.reprices(), "a tick inside the conflation time applies nothing");
      assertEquals(half, x.pendingInputs());
    }
    for (int i = half; i < r[0].length; i++) {
      x.onCurveInput((int) r[0][i], r[1][i], t);
    }
    assertEquals(reprices + 1, x.reprices(), "the round is applied once, when it is complete");
    x.advanceTo(t + 200 * MILLI);
    x.flush();
    assertEquals(reprices + 1, x.reprices(), "and never again");

    for (int i = 0; i < r[0].length; i++) {
      y.onCurveInput((int) r[0][i], r[1][i], t);
    }
    assertArrayEquals(V2Feed.prices(y), V2Feed.prices(x));
  }

  @Test
  void aHeldRoundIsAppliedByTheTimerOnceItsConflationTimeHasPassed() {
    final V2Feed fx = settled();
    final V2Feed fy = settled();
    final PricingV2 x = fx.pricing;
    final PricingV2 y = fy.pricing;
    final long t = fx.now + V2Feed.ROUND;
    final long[][] r = round(x, -0.4 * BP);
    final long reprices = x.reprices();
    // the feed leaves the round incomplete
    for (int i = 0; i < 5; i++) {
      x.onCurveInput((int) r[0][i], r[1][i], t);
      y.onCurveInput((int) r[0][i], r[1][i], t);
    }
    x.advanceTo(t + 99 * MILLI);
    x.flush();
    assertEquals(reprices, x.reprices());
    assertEquals(5, x.pendingInputs());
    x.advanceTo(t + 100 * MILLI);
    assertEquals(reprices + 1, x.reprices(), "applied when 100 ms have passed on the timer");
    assertEquals(0, x.pendingInputs());
    x.flush();
    x.advanceTo(t + 110 * MILLI);
    assertEquals(reprices + 1, x.reprices(), "once");
    // the same five applied by hand give the same state
    assertTrue(y.repriceIfDirty());
    assertArrayEquals(V2Feed.prices(y), V2Feed.prices(x));

    // flush alone applies a held round only once the binding has been told its time has passed
    x.onCurveInput((int) r[0][0], r[1][0] + 1_000L, t + 300 * MILLI);
    x.flush();
    assertEquals(1, x.pendingInputs(), "a flush inside the conflation time does nothing");
    x.onCurveInput(999, 0L, t + 400 * MILLI);
    assertEquals(1, x.pendingInputs(), "an input that is not the binding's applies nothing");
    x.flush();
    assertEquals(0, x.pendingInputs(), "its timestamp told the binding the round's time had passed");
    assertEquals(reprices + 2, x.reprices());
  }

  @Test
  void anInputPastTheCutMovesTheDateBeforeItIsRecorded() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final long cut = BusinessDate.nextCut(f.now, BusinessDate.DEFAULT_CUT_HOUR);
    final long[][] r = round(p, 0.0);
    p.setInput((int) r[0][9], r[1][9] + 1_000L, cut - MILLI);
    assertEquals(1, p.pendingInputs());
    final long reprices = p.reprices();
    assertTrue(p.setInput(V2InputIds.OVERNIGHT, r[1][0], cut));
    // what was held belonged to the day that ended; the date change reprices on the new one
    assertEquals(1, p.dateChanges());
    assertEquals(reprices + 2, p.reprices());
    assertEquals(1, p.pendingInputs(), "the new input is held on the new date");
  }

  @Test
  void setInputAndRepriceDoNotAllocate() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final int states = 240;
    final long[][][] rounds = new long[states][][];
    for (int s = 0; s < states; s++) {
      rounds[s] = round(p, (s < states / 2 ? s : states - s) * 0.1 * BP);
    }
    final int[] bondIds = UNIVERSE.bondIds();
    final long[] mids = new long[bondIds.length];
    for (int j = 0; j < mids.length; j++) {
      mids[j] = UNIVERSE.bond(bondIds[j]).initialMid();
    }
    final long tick = UNIVERSE.bondTick();
    final com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    long now = f.now;
    long before = 0L;
    long reprices = 0L;
    final int warmUp = 6_000;
    final int measured = 4_000;
    for (int k = 0; k < warmUp + measured; k++) {
      if (k == warmUp) {
        reprices = p.reprices();
        before = threads.getThreadAllocatedBytes(thread);
      }
      now += V2Feed.ROUND / 100;
      final long offset = (k % 5 - 2) * tick;
      for (int j = 0; j < bondIds.length; j++) {
        f.book.set(bondIds[j], mids[j] + offset - tick, 100_000_000L, mids[j] + offset + tick, 100_000_000L, now);
      }
      final long[][] r = rounds[k % states];
      for (int i = 0; i < r[0].length; i++) {
        p.setInput((int) r[0][i], r[1][i], now);
      }
      p.advanceTo(now);
      p.repriceIfDirty();
    }
    final long bytes = threads.getThreadAllocatedBytes(thread) - before;
    assertEquals(measured, p.reprices() - reprices);
    assertEquals(0, p.rejectedInputs());
    assertEquals(0L, bytes, "bytes allocated over " + measured + " rounds of setInput and repriceIfDirty");
  }

  @Test
  void theClockOfTheOlderConstructorIsReadForTheDateShownBeforeTheFirstInputOnly() {
    // a probe on Thursday lists Thursday's inputs; given Monday's first input it is Monday's
    // binding to the bit
    final long thursday = V2Feed.T0 + 3 * 86_400L * V2Feed.SECOND;
    final PricingV2 probe = new PricingV2(UNIVERSE, new ManualClock(thursday), PricingV2.Options.defaults());
    final PricingV2 desk = new PricingV2(UNIVERSE, PricingV2.Options.defaults());
    assertEquals(BusinessDate.tradeDate(thursday, 17, io.ratesalgo.analytics.date.Calendars.US_SOFR),
        probe.businessDate());
    assertEquals(UNIVERSE.valuationDate(), desk.businessDate());
    assertEquals(io.ratesalgo.api.FairValueSource.NO_VALUE, desk.referenceNanos(2003), "no time yet");
    final V2Feed a = new V2Feed(probe, V2Feed.T0);
    final V2Feed b = new V2Feed(desk, V2Feed.T0);
    a.books(0L);
    b.books(0L);
    probe.book(a.book);
    desk.book(b.book);
    probe.advanceTo(V2Feed.T0);
    desk.advanceTo(V2Feed.T0);
    assertEquals(BusinessDate.lastCut(V2Feed.T0, 17), desk.referenceNanos(2003),
        "the start-of-day marks are dated at the start of the business day");
    assertArrayEquals(V2Feed.bits(desk), V2Feed.bits(probe));
    for (int k = 0; k < 3; k++) {
      a.round(0.2 * BP * k);
      b.round(0.2 * BP * k);
      assertArrayEquals(V2Feed.bits(desk), V2Feed.bits(probe));
    }
  }
}
