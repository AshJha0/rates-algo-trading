package io.ratesalgo.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The baseline is today's engine: with every feature at its default, the desk strategy sends the
 * same messages as {@link ModelSpreadStrategy} (P5-001's treatment) on the same journal, so the
 * output chain hashes agree, and P&amp;L agrees to the cent. Each treatment differs from it.
 */
class DeskQuoterBaselineTest {

  @TempDir Path dir;

  @Test
  @DisplayName("baseline: the desk strategy at its defaults reproduces ModelSpreadStrategy byte for byte")
  void baselineReproducesModelSpread() {
    final Path journal = Support.day(dir, LocalDate.of(2026, 2, 17));
    final BacktestConfig cfg = Support.config(journal);
    final Presets presets = Support.presets(BandPoint.CENTRAL);
    final ModelSpreadStrategy model =
        new ModelSpreadStrategy(
            HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL),
            CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET)),
            new double[] {0.0241, 0.0667, 0.1300},
            0.2459,
            0.2459,
            2.0,
            0.01,
            100_000,
            4);
    final BacktestResult expected = new BacktestRunner(cfg).run(model);
    final DeskQuoter baseline = Support.factory(BandPoint.CENTRAL).baseline();
    final BacktestResult actual = new BacktestRunner(cfg).run(baseline);
    assertTrue(expected.clientFills() > 50, "client fills " + expected.clientFills());
    assertEquals(expected.outputChainHash(), actual.outputChainHash());
    assertEquals(
        Math.round(expected.total().positionPnl() * 100), Math.round(actual.total().positionPnl() * 100));
    assertEquals(expected.hedgeFills(), baseline.hedgesSent());
    assertEquals(0, baseline.waits());
    assertEquals(0, baseline.sizeDeclines());
  }

  @Test
  @DisplayName("treatments: every feature changes what the strategy sends, and runs are deterministic")
  void treatmentsDifferAndAreDeterministic() {
    final Path journal = Support.day(dir, LocalDate.of(2026, 2, 18));
    final BacktestConfig cfg = Support.config(journal);
    final DeskFactory f = Support.factory(BandPoint.CENTRAL);
    final long base = new BacktestRunner(cfg).run(f.baseline()).outputChainHash();
    for (final String t : DeskFactory.TREATMENTS) {
      final long first = new BacktestRunner(cfg).run(f.treatment(t, f.newTiering())).outputChainHash();
      final long second = new BacktestRunner(cfg).run(f.treatment(t, f.newTiering())).outputChainHash();
      assertEquals(first, second, t);
      assertTrue(first != base, t + " sends the same messages as the baseline");
    }
    final DeskQuoter desk = f.deskDefault();
    assertEquals(DeskFactory.DESK_DEFAULT, desk.name());
    assertEquals(
        new BacktestRunner(cfg).run(f.treatment(DeskFactory.INTERNALISATION, null)).outputChainHash(),
        new BacktestRunner(cfg).run(desk).outputChainHash());
  }
}
