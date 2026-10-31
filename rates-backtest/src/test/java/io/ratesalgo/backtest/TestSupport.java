package io.ratesalgo.backtest;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Shared helpers: the repository root, the presets and synthetic journals. */
public final class TestSupport {

  private TestSupport() {}

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

  public static Presets presets() {
    return Presets.load(repoRoot().resolve("data/presets.yaml"));
  }

  /** Generates the synthetic day of {@code date} with the experiment seed rule into {@code dir}. */
  public static Path day(final Path dir, final LocalDate date) {
    final Path journal = dir.resolve("day-" + date + ".raj");
    new SyntheticDay(SyntheticDay.Params.fromPresets(presets()))
        .generate(date, 5_000_000L + date.toEpochDay(), journal, null);
    return journal;
  }

  public static BacktestConfig config(final Path journal, final BandPoint point) {
    return BacktestConfig.fromPresets(presets(), List.of(journal), 7L, point, null);
  }

  public static FixedSpreadStrategy fixed() {
    return new FixedSpreadStrategy(0.30, 0.15, 100_000, 4);
  }
}
