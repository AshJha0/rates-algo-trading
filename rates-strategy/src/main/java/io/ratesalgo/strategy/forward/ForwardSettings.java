package io.ratesalgo.strategy.forward;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Path;
import java.util.Map;

/**
 * The frozen forward-log settings, {@code config/forward/desk.yaml}.
 *
 * @param file the settings file
 * @param experimentConfig the desk experiment config whose adopted desk default is run
 * @param presetsFile the presets file
 * @param bandPoint the assumption band point the synthetic outcomes are settled at
 * @param flatIntraday true to switch the synthetic intraday market moves off
 * @param futureRefYieldPct the 10-year par yield at which the note future's model price is {@code future_price_0}
 * @param maxMarkGapDays the longest gap from session to mark before a decision is skipped
 */
public record ForwardSettings(
    Path file,
    Path experimentConfig,
    Path presetsFile,
    BandPoint bandPoint,
    boolean flatIntraday,
    double futureRefYieldPct,
    long maxMarkGapDays) {

  /** Reads the settings; relative paths in the file resolve against {@code repoRoot}. */
  public static ForwardSettings load(final Path repoRoot, final Path file) {
    final String where = file.toString();
    final Map<String, Object> m = Yaml.loadMap(file);
    if (Yaml.integer(m, "version", where) != 1) {
      throw new IllegalArgumentException(where + ": unsupported version");
    }
    final Object flat = Yaml.require(m, "flat_intraday", where);
    if (!(flat instanceof Boolean b)) {
      throw new IllegalArgumentException(where + ": flat_intraday must be true or false");
    }
    return new ForwardSettings(
        file,
        repoRoot.resolve(Yaml.string(m, "experiment_config", where)),
        repoRoot.resolve(Yaml.string(m, "presets", where)),
        BandPoint.parse(Yaml.string(m, "band_point", where)),
        b,
        Yaml.number(m, "future_ref_yield_pct", where),
        Yaml.integer(m, "max_mark_gap_days", where));
  }

  /** SHA-256 of the settings, experiment config and presets files, for the ledger. */
  public Map<String, Object> hashes() {
    return Map.of(
        "settings_sha256", Hashing.sha256TextFile(file),
        "experiment_config_sha256", Hashing.sha256TextFile(experimentConfig),
        "presets_sha256", Hashing.sha256TextFile(presetsFile));
  }
}
