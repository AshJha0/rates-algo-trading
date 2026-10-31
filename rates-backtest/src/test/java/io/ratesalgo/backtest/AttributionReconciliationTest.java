package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.pnl.CarryModel;
import io.ratesalgo.backtest.pnl.PositionBook;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import java.nio.file.Path;
import java.time.LocalDate;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Attribution reconciles to position-based P&amp;L to the cent on every run (P5 gate): spread
 * capture, inventory, hedge cost, carry, funding and fees sum to cash plus the final position at
 * the final mid, with a residual below one cent.
 */
class AttributionReconciliationTest {

  private static final double CENT = 0.01;

  @TempDir Path dir;

  @Test
  void everyRunReconcilesToTheCent() {
    final Path journal = TestSupport.day(dir, LocalDate.of(2026, 2, 9));
    final ModelSpreadStrategy model =
        new ModelSpreadStrategy(
            HitRateModel.fromPreset(TestSupport.presets().get(HitRateModel.PRESET), BandPoint.CENTRAL),
            CompetitorModel.fromPreset(TestSupport.presets().get(CompetitorModel.PRESET)),
            new double[] {0.02, 0.06, 0.15},
            0.15,
            0.15,
            2.0,
            0.01,
            100_000,
            4);
    for (final BandPoint p : BandPoint.values()) {
      for (final Strategy s : new Strategy[] {TestSupport.fixed(), model}) {
        final BacktestResult r = new BacktestRunner(TestSupport.config(journal, p)).run(s);
        assertTrue(Math.abs(r.total().residual()) < CENT, s.name() + " " + p + " residual " + r.total().residual());
        for (final Attribution a : r.attributionByInstrument().values()) {
          assertTrue(Math.abs(a.residual()) < CENT);
          assertEquals(a.positionPnl(), a.explained() + a.residual(), 1e-9);
        }
        double clientEdge = 0;
        double hedgeEdge = 0;
        for (final FillRecord f : r.fills()) {
          final double e = f.edge(InstrumentSet.usdDefault().get(f.instrumentId()));
          if (f.hedge()) {
            hedgeEdge += e;
          } else {
            clientEdge += e;
          }
        }
        assertEquals(r.total().spreadCapture(), clientEdge, CENT);
        assertEquals(r.total().hedgeCost(), hedgeEdge, CENT);
        assertTrue(r.total().hedgeCost() <= 0, "hedges cross the spread");
        assertEquals(0.0, r.total().carryRoll(), 0.0, "carry hook is zero until P1");
      }
    }
  }

  @Test
  void handWorkedExample() {
    final InstrumentSet set = InstrumentSet.usdDefault();
    final PositionBook book = new PositionBook(set, CarryModel.NONE);
    final long t0 = 1_000_000_000_000L;
    // 100mm 10Y payer swap bought at 3.95 + 0.0030 above mid 3.95? We receive at 3.9530 (sell payer).
    book.mark(InstrumentSet.SOFR_10Y, 3.9500, t0);
    book.trade(InstrumentSet.SOFR_10Y, -100 * InstrumentSet.MILLION, 3.9530, 0.0, false, t0);
    // spread capture: -1e10 * (3.95 - 3.953) * 8.11e-4 = 24,330 USD (0.3 bp on 81,100 USD per bp)
    assertEquals(24_330.0, book.attribution(InstrumentSet.SOFR_10Y).spreadCapture(), 1e-6);
    // rates rise 1 bp: short payer loses 81,100
    book.mark(InstrumentSet.SOFR_10Y, 3.9600, t0 + 1_000_000_000L);
    final Attribution a = book.attribution(InstrumentSet.SOFR_10Y);
    assertEquals(-81_100.0, a.inventoryMtm(), 1e-6);
    assertEquals(24_330.0 - 81_100.0, a.positionPnl(), 1e-6);
    assertEquals(0.0, a.residual(), 1e-9);
    assertEquals(-100 * InstrumentSet.MILLION * set.get(InstrumentSet.SOFR_10Y).dv01PerUnit(), book.netDv01(), 1e-6);
  }

  @Test
  void fundingAccruesOnBonds() {
    final PositionBook book = new PositionBook(InstrumentSet.usdDefault(), CarryModel.NONE);
    book.mark(InstrumentSet.UST_10Y, 100.0, 0L);
    book.trade(InstrumentSet.UST_10Y, 100 * InstrumentSet.MILLION, 100.0, 10.0, false, 0L);
    book.close(86_400_000_000_000L); // one day
    final Attribution a = book.attribution(InstrumentSet.UST_10Y);
    // 100mm at 4.25 percent Act/360 for one day
    assertEquals(-100e6 * 0.0425 / 360, a.funding(), 1e-6);
    assertEquals(-10.0, a.fees(), 1e-12);
    assertTrue(Math.abs(a.residual()) < 1e-6);
  }

  @Test
  void tradeBeforeMidIsRefused() {
    final PositionBook book = new PositionBook(InstrumentSet.usdDefault(), CarryModel.NONE);
    assertThrows(IllegalStateException.class, () -> book.trade(InstrumentSet.UST_2Y, 1, 100.0, 0, false, 0));
    assertThrows(IllegalArgumentException.class, () -> book.mark(InstrumentSet.UST_2Y, Double.NaN, 0));
    book.mark(InstrumentSet.UST_2Y, 100.0, 0);
    assertThrows(IllegalArgumentException.class, () -> book.trade(InstrumentSet.UST_2Y, 1, 100.0, -1, false, 0));
    assertEquals(Attribution.ZERO, book.attribution(InstrumentSet.TY_FUTURE));
  }

  @Property(tries = 200)
  void identityHoldsForRandomTradesAndMarks(
      @ForAll @LongRange(min = 1, max = Long.MAX_VALUE / 4) final long seed,
      @ForAll @IntRange(min = 1, max = 60) final int steps) {
    final io.ratesalgo.backtest.util.SplitMix64 rng = new io.ratesalgo.backtest.util.SplitMix64(seed);
    final InstrumentSet set = InstrumentSet.usdDefault();
    final PositionBook book = new PositionBook(set, CarryModel.NONE);
    final int[] ids = {InstrumentSet.SOFR_5Y, InstrumentSet.UST_10Y, InstrumentSet.TY_FUTURE};
    final double[] mids = {3.6, 100.0, 112.0};
    long t = 0;
    for (final int i : new int[] {0, 1, 2}) {
      book.mark(ids[i], mids[i], t);
    }
    for (int s = 0; s < steps; s++) {
      t += 1_000_000_000L + rng.nextInt(1_000_000);
      final int i = rng.nextInt(3);
      if (rng.nextDouble() < 0.5) {
        mids[i] += (rng.nextDouble() - 0.5) * 0.1;
        book.mark(ids[i], mids[i], t);
      } else {
        final long qty = (rng.nextInt(400) - 200) * set.get(ids[i]).lotSize();
        book.trade(ids[i], qty, mids[i] + (rng.nextDouble() - 0.5) * 0.02, rng.nextDouble() * 50, i == 2, t);
      }
    }
    book.close(t + 3_600_000_000_000L);
    assertTrue(Math.abs(book.total().residual()) < CENT, "residual " + book.total().residual());
  }
}
