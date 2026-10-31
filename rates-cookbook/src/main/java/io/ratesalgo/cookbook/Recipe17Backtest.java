package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 17: run the back-tester on one synthetic day at every band point. */
public final class Recipe17Backtest {

  private Recipe17Backtest() {}

  public static void main(final String[] args) throws IOException {
    final Presets presets = Presets.load(RepoRoot.find().resolve("data/presets.yaml"));
    final Path dir = Files.createTempDirectory("recipe17");
    final Path journal = dir.resolve("2026-02-02.raj");
    final LocalDate date = LocalDate.of(2026, 2, 2);
    final SyntheticDay.Summary day =
        new SyntheticDay(SyntheticDay.Params.fromPresets(presets))
            .generate(date, 5_000_000L + date.toEpochDay(), journal, null);
    System.out.println(
        "journal: " + day.frames() + " frames, " + day.rfqs() + " rfqs, chain hash " + Long.toHexString(day.chainHash()));

    for (final BandPoint point : BandPoint.values()) {
      final BacktestConfig config = BacktestConfig.fromPresets(presets, List.of(journal), 7L, point, null);
      final BacktestResult r =
          new BacktestRunner(config).run(new FixedSpreadStrategy(0.30, 0.15, 100_000, 4));
      final long won = r.rfqs().stream().filter(RfqRecord::won).count();
      final Attribution a = r.total();
      System.out.println(
          String.format(
              Locale.ROOT,
              "%-7s won %d of %d, hedges %d, spread %.2f, inventory %.2f, hedge %.2f, fees %.2f,"
                  + " funding %.2f, P&L %.2f, residual below a cent: %b",
              point.label(), won, r.rfqs().size(), r.hedgeFills(), a.spreadCapture(), a.inventoryMtm(),
              a.hedgeCost(), a.fees(), a.funding(), a.positionPnl(), Math.abs(a.residual()) < 0.01));
    }
    Files.delete(journal);
    Files.delete(dir);
  }
}
