package io.ratesalgo.app.backtest;

import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.AnalyticsPricing;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.ResultsWriter;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.report.DailyReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily report on a recorded day (API_SPEC.md Section 18.7): the loop's inbound journal is
 * exported ({@link JournalExport}), the P5 back-tester replays it with the desk as its strategy
 * ({@link DeskStrategy}) under the standard presets of {@code data/presets.yaml} at the central
 * band point, and P5's daily report is written next to the results.
 */
public final class DeskBacktest {

  /** A finished run. */
  public record Run(BacktestResult result, DailyReport report, DeskStrategy strategy, long exportedFrames) {}

  private DeskBacktest() {}

  /** The back-tester's instruments for the universe: swaps per annuity, bonds per their curve DV01. */
  public static InstrumentSet instruments(final UsdUniverse universe) {
    final AnalyticsPricing pricing = new AnalyticsPricing(universe, FairValueSource.NO_VALUE);
    final List<Instrument> list = new ArrayList<>();
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      final double annuity = pricing.directory().byId(s.instrumentId()).quoteValue() * 100.0;
      list.add(new Instrument(s.instrumentId(), s.securityId(), Instrument.Kind.SWAP, s.tenorMonths() / 12.0,
          annuity * 1e-4, 0.01, 100_000L, InstrumentSet.MILLION, 0.0));
    }
    final Dv01Vector v = new Dv01Vector(io.ratesalgo.api.CurrencyCode.USD);
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      pricing.pv01(b.instrumentId(), 10_000L, v);
      // price points per basis point: the PV01 of 100 of face
      final double perBp = v.parallel();
      list.add(new Instrument(b.instrumentId(), b.securityId(), Instrument.Kind.BOND,
          pricing.directory().byId(b.instrumentId()).tenorYears(), 1e-4, perBp, b.tick(), InstrumentSet.MILLION,
          0.0425));
    }
    return new InstrumentSet(list);
  }

  /**
   * Exports {@code groupDir}'s inbound journal, replays it through the desk and writes {@code
   * results.raj}, the CSV tables, {@code report.md} and {@code report.json} under {@code outDir}.
   */
  public static Run run(final Path groupDir, final Path presetsYaml, final Path outDir, final long seed) {
    try {
      Files.createDirectories(outDir);
      final Path journal = outDir.resolve("export.raj");
      Files.deleteIfExists(journal);
      final long frames = JournalExport.export(groupDir, "in", journal, 256 << 20);
      final UsdUniverse universe = UsdUniverse.standard();
      final InstrumentSet instruments = instruments(universe);
      final BacktestConfig standard =
          BacktestConfig.fromPresets(Presets.load(presetsYaml), List.of(journal), seed, BandPoint.CENTRAL, outDir);
      final BacktestConfig config =
          new BacktestConfig(standard.journals(), instruments, standard.seed(), standard.bandPoint(), standard.outcomes(),
              standard.strategyHitRate(), standard.hedgeCost(), standard.clob(), standard.carry(), standard.calendar(),
              standard.clientsPerTier(), standard.quoteLatencyNanos(), standard.clientDecisionNanos(),
              standard.orderLatencyNanos(), standard.markoutHorizonsNanos(), outDir);
      final DeskStrategy strategy = new DeskStrategy(universe, DeskSettings.defaults());
      final BacktestResult result = new BacktestRunner(config).run(strategy);
      ResultsWriter.write(result, instruments, outDir);
      final DailyReport report =
          new DailyReport(result, instruments, new DailyReport.Settings(5_000_000.0, 5_000.0, null),
              DailyReport.RouterFillQuality.NONE);
      Files.writeString(outDir.resolve("report.md"), report.toMarkdown(), StandardCharsets.UTF_8);
      Files.writeString(outDir.resolve("report.json"), report.toJson(), StandardCharsets.UTF_8);
      return new Run(result, report, strategy, frames);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code data/presets.yaml} of the repository the working directory is in. */
  public static Path presets() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null) {
      final Path candidate = p.resolve("data").resolve("presets.yaml");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("data/presets.yaml not found above " + Path.of("").toAbsolutePath());
  }
}
