package io.ratesalgo.strategy;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.strategy.experiment.DeskExperiment;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Shared helpers: the repository root, the desk experiment and synthetic journals. */
public final class Support {

  public static final String CONFIG = "data/experiments/R2B.config.yaml";

  private Support() {}

  public static Path repoRoot() {
    final String prop = System.getProperty("ratesalgo.repoRoot");
    if (prop != null && Files.isRegularFile(Path.of(prop, "data", "presets.yaml"))) {
      return Path.of(prop).toAbsolutePath().normalize();
    }
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isRegularFile(p.resolve("data/presets.yaml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("repository root with data/presets.yaml not found");
    }
    return p;
  }

  public static DeskExperiment experiment() {
    return new DeskExperiment(repoRoot(), repoRoot().resolve(CONFIG));
  }

  public static Presets presets(final BandPoint point) {
    return experiment().presets(point);
  }

  public static DeskFactory factory(final BandPoint point) {
    return experiment().factory(presets(point));
  }

  /** The central-point journal of {@code date} in {@code dir}. */
  public static Path day(final Path dir, final LocalDate date) {
    final DeskExperiment e = experiment();
    final Path journal = dir.resolve("desk-" + date + ".raj");
    new SyntheticDay(SyntheticDay.Params.fromPresets(e.presets(BandPoint.CENTRAL)))
        .generate(date, e.daySeed(date), journal, null);
    return journal;
  }

  public static BacktestConfig config(final Path journal) {
    final DeskExperiment e = experiment();
    final Presets presets = e.presets(BandPoint.CENTRAL);
    return e.backtestConfig(presets, e.factory(presets), journal, BandPoint.CENTRAL);
  }

  public static RiskLadder ladder() {
    return new RiskLadder(InstrumentSet.usdDefault().all());
  }
}
