package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one session (one seed) for every band row and every arm, and returns what the ledger needs
 * from it. Nothing here compares arms or reads across sessions; that happens once, at the end of a
 * run ({@link ExperimentSummary}).
 *
 * <p>Common random numbers. For each band row the session's journal is written once, from the
 * seed, and every arm replays that same file against its own fresh model of the same seed. So the
 * arms share, bit for bit: the latent rate path (factors, swap spread, basis, idiosyncratic
 * terms, the informed drift), the RFQ list with every attribute (time, instrument, side, size,
 * tier, informed flag, dealers asked), every competitor's answer, delay and half-spread, every
 * client's reserve and done-away draw, the other takers' trades on each order book, and the
 * published reference prices and book tops the desk sees. What differs is only what the arm does
 * and what its own orders cause: its fills, its permanent impact on the level factor (X1), its
 * transient book displacement, and therefore its later marks and passive fills. Each row records
 * the journal's chain hash and the arms' input chain hashes, which must all be equal.
 *
 * <p>Wall-clock time is recorded per arm and per session; it is measurement, not a result.
 */
public final class SessionExecutor {

  private final Presets presets;
  private final Path workDir;

  /**
   * @param presets desk-side and transport settings (latencies, venue fee, publication interval)
   * @param workDir scratch directory for the session's journals, each deleted after its arms ran
   */
  public SessionExecutor(final Presets presets, final Path workDir) {
    this.presets = presets;
    this.workDir = workDir;
  }

  /** Runs every row and arm of one seed and returns the session's record. */
  public Map<String, Object> run(
      final long seed, final List<BandParameters.Row> rows, final List<String> arms, final ArmFactory factory) {
    final String spent = SpentData.seedRefusal(seed);
    if (spent != null) {
      throw new IllegalArgumentException(spent);
    }
    final long started = System.nanoTime();
    final LocalDate date = PairedSessions.labelDate(seed);
    final SyntheticDay.Publication publication = SyntheticDay.Publication.fromPresets(presets);
    final Map<String, Object> rowsOut = new LinkedHashMap<>();
    try {
      Files.createDirectories(workDir);
      for (final BandParameters.Row row : rows) {
        final MarketSpec spec = new MarketSpec(row.params());
        final InstrumentSet instruments = InstrumentSet.simV2(spec.params());
        final Path journal = workDir.resolve("session-" + seed + "-" + row.label().replace(':', '_') + ".raj");
        Files.deleteIfExists(journal);
        final SyntheticDay.Summary summary =
            SyntheticDay.generateSimV2(SimDay.of(spec, seed, date), publication, journal, null);
        final Map<String, Object> r = new LinkedHashMap<>();
        r.put("journal_sha256", Hashing.sha256File(journal));
        r.put("journal_chain", Long.toHexString(summary.chainHash()));
        r.put("market_hash", Long.toHexString(SessionSeeds.session(spec, seed).replayHash()));
        r.put("frames", summary.frames());
        r.put("rfqs", (long) summary.rfqs());
        final Map<String, Object> armsOut = new LinkedHashMap<>();
        for (final String arm : arms) {
          final long t0 = System.nanoTime();
          final SimDay day = SimDay.of(spec, seed, date);
          final BacktestResult result =
              new BacktestRunner(BacktestConfig.forSimV2(presets, day, List.of(journal), null)).run(factory.create(arm, day));
          armsOut.put(arm, metrics(result, instruments, (System.nanoTime() - t0) / 1_000_000L));
        }
        r.put("arms", armsOut);
        rowsOut.put(row.label(), r);
        Files.deleteIfExists(journal);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("seed", seed);
    out.put("day_type", SessionSeeds.dayType(seed).name());
    out.put("label_date", date.toString());
    out.put("rows", rowsOut);
    out.put("wall_ms", (System.nanoTime() - started) / 1_000_000L);
    return out;
  }

  /** One arm's numbers on one session. */
  static Map<String, Object> metrics(final BacktestResult r, final InstrumentSet instruments, final long wallMs) {
    double clientDv01 = 0.0;
    double hedgeDv01 = 0.0;
    for (final FillRecord f : r.fills()) {
      final double dv01 = Math.abs(f.signedQuantity() * instruments.get(f.instrumentId()).dv01PerUnit());
      if (f.hedge()) {
        hedgeDv01 += dv01;
      } else {
        clientDv01 += dv01;
      }
    }
    long quoted = 0;
    long withdrawn = 0;
    long won = 0;
    for (final RfqRecord q : r.rfqs()) {
      quoted += q.quoted() ? 1 : 0;
      withdrawn += q.withdrawn() ? 1 : 0;
      won += q.won() ? 1 : 0;
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    final Attribution a = r.total();
    m.putAll(a.asMap());
    m.put("client_dv01_usd", clientDv01);
    m.put("hedge_dv01_usd", hedgeDv01);
    m.put("rfqs", (long) r.rfqs().size());
    m.put("quoted", quoted);
    m.put("withdrawn", withdrawn);
    m.put("won", won);
    m.put("hedge_fills", r.hedgeFills());
    m.put("max_abs_net_dv01", r.maxAbsNetDv01());
    m.put("input_chain", Long.toHexString(r.inputChainHash()));
    m.put("output_chain", Long.toHexString(r.outputChainHash()));
    m.put("wall_ms", wallMs);
    return m;
  }
}
