package io.ratesalgo.bench.jmh;

import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.app.pricing.AnalyticsPricing;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.ManualClock;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Microbenchmark of the pricing v2 reprice: what one round of curve market data costs the loop
 * thread. A round is the fourteen curve inputs and the 12-year mark; the last message of the
 * round triggers one curve solve, the leave-one-out values, nine swap-rate ladders, four spread
 * ladders, the Treasury fit and carry. Three cases:
 *
 * <ul>
 *   <li>{@link #roundThatMovesTheCurve}: every quote moves by a fraction of a basis point each
 *       round, in a cycle that stays within 3 bp, so the leave-one-out reference is not retaken;
 *   <li>{@link #roundWithTheSameQuotes}: no quote changes, so there is no solve and the cost is
 *       the bond stage and the ladders;
 *   <li>{@link #versionOneRound}: the version 1 binding on its five pillars, for scale.
 * </ul>
 *
 * <p>Input timestamps advance one microsecond a round, so that a run of any length stays inside
 * one business date; a date change is a once-a-day event with its own cost and is not measured
 * here. Run with {@code -prof gc} to see the allocation rate, which must be zero.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PricingV2RepriceBenchmark {

  /** 2026-11-02 09:30 New York, the simulators' session start. */
  private static final long T0 = 1_793_629_800_000_000_000L;

  private static final int STATES = 64;
  private static final int MARK_12Y = 2005;

  private PricingV2 pricing;
  private AnalyticsPricing versionOne;
  private int[] ids;
  private long[][] mantissas;
  private long[] mark;
  private int[] versionOneIds;
  private long[][] versionOneMantissas;
  private long now;
  private int state;

  @Setup
  public void setUp() {
    final UsdUniverse universe = UsdUniverse.standard();
    pricing = new PricingV2(universe, new ManualClock(T0), PricingV2.Options.defaults());
    final InMemoryBookSource book = new InMemoryBookSource(8);
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      book.set(b.instrumentId(), b.initialMid() - b.tick(), 100_000_000L, b.initialMid() + b.tick(),
          100_000_000L, T0);
    }
    pricing.book(book);
    final SofrCurve curve = pricing.curve();
    final int n = pricing.inputCount();
    ids = new int[n];
    mantissas = new long[STATES][n];
    mark = new long[STATES];
    for (int s = 0; s < STATES; s++) {
      // a triangle wave of 3 bp peak to peak
      final double shift = 3e-4 * Math.abs(s - STATES / 2) / (STATES / 2.0);
      for (int i = 0; i < n; i++) {
        ids[i] = pricing.inputId(i);
        final double rate = rate(curve, i) + shift;
        mantissas[s][i] =
            curve.quotePerRate(i) == 1.0
                ? Math.round(rate * 1e11)
                : Math.round(curve.quoteForRate(i, rate) * 1e9);
      }
      mark[s] = Math.round((0.0380 + shift) * 1e11);
    }
    versionOne = new AnalyticsPricing(universe, T0);
    versionOne.book(book);
    versionOneIds = new int[universe.swaps().size()];
    versionOneMantissas = new long[STATES][versionOneIds.length];
    for (int j = 0; j < versionOneIds.length; j++) {
      versionOneIds[j] = universe.swaps().get(j).instrumentId();
      for (int s = 0; s < STATES; s++) {
        versionOneMantissas[s][j] =
            universe.swaps().get(j).initialMid()
                + Math.round(3e9 * Math.abs(s - STATES / 2) / (STATES / 2.0)) / 100L;
      }
    }
    now = T0;
    // every input fed once, so that the measured rounds are complete rounds
    roundThatMovesTheCurve();
  }

  private static double rate(final SofrCurve c, final int i) {
    if (c.kind(i) == SofrCurve.Kind.OVERNIGHT) {
      return 0.0370;
    }
    if (c.kind(i) == SofrCurve.Kind.SWAP) {
      return 0.0358 + 0.0017 * (1.0 - Math.exp(-c.tenorMonths(i) / 120.0));
    }
    return 0.0362;
  }

  private long send(final int s) {
    now += 1_000L;
    for (int i = 0; i < ids.length; i++) {
      pricing.onCurveInput(ids[i], mantissas[s][i], now);
    }
    pricing.onCurveInput(MARK_12Y, mark[s], now);
    return pricing.fairMid(MARK_12Y);
  }

  /** One complete round in which every quote has moved: one solve and one full reprice. */
  @Benchmark
  public long roundThatMovesTheCurve() {
    state = (state + 1) % STATES;
    return send(state);
  }

  /** One complete round that repeats the previous quotes: no solve, bonds and ladders only. */
  @Benchmark
  public long roundWithTheSameQuotes() {
    return send(state);
  }

  /** Version 1 for scale: five swap quotes, each an incremental rebuild and a full recompute. */
  @Benchmark
  public long versionOneRound() {
    state = (state + 1) % STATES;
    now += 1_000L;
    for (int j = 0; j < versionOneIds.length; j++) {
      versionOne.onCurveInput(versionOneIds[j], versionOneMantissas[state][j], now);
    }
    return versionOne.fairMid(MARK_12Y);
  }

  /** Reprices done by the version 2 binding so far (for the smoke test). */
  public long reprices() {
    return pricing.reprices();
  }
}
