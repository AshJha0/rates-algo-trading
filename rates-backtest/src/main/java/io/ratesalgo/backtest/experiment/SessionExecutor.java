package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
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

  /** One row of a session and the arms to run on it. */
  public record RowRun(BandParameters.Row row, List<String> arms) {
    public RowRun {
      arms = List.copyOf(arms);
    }
  }

  /**
   * Where a session's finished parts are kept while it runs: the row's journal facts and each
   * arm's numbers. A part present when the session is resumed is not run again.
   */
  public interface Parts {
    /** The row's journal facts, or null. */
    Map<String, Object> row(String row);

    void putRow(String row, Map<String, Object> value);

    /** An arm's numbers on a row, or null. */
    Map<String, Object> arm(String row, String arm);

    void putArm(String row, String arm, Map<String, Object> value);
  }

  /** Parts kept in memory only: a session runs whole or not at all. */
  static Parts inMemory() {
    final Map<String, Map<String, Object>> m = new java.util.HashMap<>();
    return new Parts() {
      @Override
      public Map<String, Object> row(final String row) {
        return m.get(row);
      }

      @Override
      public void putRow(final String row, final Map<String, Object> value) {
        m.put(row, value);
      }

      @Override
      public Map<String, Object> arm(final String row, final String arm) {
        return m.get(row + "\u0000" + arm);
      }

      @Override
      public void putArm(final String row, final String arm, final Map<String, Object> value) {
        m.put(row + "\u0000" + arm, value);
      }
    };
  }

  /**
   * What a timing-only run keeps of an arm: structure, counts, guard metrics and wall-clock. No
   * P&amp;L line, no attribution, no output hash; a pilot that must not show P&amp;L never writes it.
   */
  static final java.util.Set<String> TIMING_KEYS =
      java.util.Set.of(
          "rfqs", "quoted", "withdrawn", "won", "hedge_fills", "client_dv01_usd", "hedge_dv01_usd", "max_abs_net_dv01",
          "input_chain", "wall_ms", "config_hash", "rfqs_seen", "structural_declines", "desk_quotes", "child_orders",
          "withdrawals", "cancels", "curve_rounds", "futures_dust_fills");

  /** Runs every row and arm of one seed and returns the session's record. */
  public Map<String, Object> run(
      final long seed, final List<BandParameters.Row> rows, final List<String> arms, final ArmFactory factory) {
    final List<RowRun> runs = new java.util.ArrayList<>();
    for (final BandParameters.Row r : rows) {
      runs.add(new RowRun(r, arms));
    }
    return run(seed, runs, factory, inMemory(), false, Long.MAX_VALUE);
  }

  /**
   * Runs one seed: per row one journal written from the seed, then each arm of that row against it.
   * Finished parts are kept in {@code parts}; a part already there is not run again, and a row's
   * journal written again must hash as it did the first time or the session fails.
   *
   * @param timingOnly keep only {@link #TIMING_KEYS} of each arm (pilot {@code --timing-only})
   * @param windowNanos replay only this much of each session after the open ({@link Long#MAX_VALUE}
   *     for the full day, which every registered run uses); for tests
   */
  public Map<String, Object> run(
      final long seed, final List<RowRun> runs, final ArmFactory factory, final Parts parts, final boolean timingOnly,
      final long windowNanos) {
    final String spent = SpentData.seedRefusal(seed);
    if (spent != null) {
      throw new IllegalArgumentException(spent);
    }
    final long started = System.nanoTime();
    final LocalDate date = PairedSessions.labelDate(seed);
    final SyntheticDay.Publication publication = SyntheticDay.Publication.simV2(presets);
    final Map<String, Object> rowsOut = new LinkedHashMap<>();
    try {
      Files.createDirectories(workDir);
      for (final RowRun run : runs) {
        final BandParameters.Row row = run.row();
        final MarketSpec spec = new MarketSpec(row.params());
        final InstrumentSet instruments = InstrumentSet.simV2(spec.params());
        final List<String> missing = new java.util.ArrayList<>();
        for (final String arm : run.arms()) {
          if (parts.arm(row.label(), arm) == null) {
            missing.add(arm);
          }
        }
        Map<String, Object> r = parts.row(row.label());
        if (!missing.isEmpty() || r == null) {
          final Path journal = workDir.resolve("session-" + seed + "-" + row.label().replace(':', '_') + ".raj");
          Files.deleteIfExists(journal);
          final SyntheticDay.Summary summary =
              SyntheticDay.generateSimV2(SimDay.of(spec, seed, date), publication, journal, null, windowNanos);
          final Map<String, Object> fresh = new LinkedHashMap<>();
          fresh.put("journal_sha256", Hashing.sha256File(journal));
          fresh.put("journal_chain", Long.toHexString(summary.chainHash()));
          fresh.put("market_hash", Long.toHexString(SessionSeeds.session(spec, seed).replayHash()));
          fresh.put("frames", summary.frames());
          fresh.put("rfqs", (long) summary.rfqs());
          if (r != null && !String.valueOf(r.get("journal_sha256")).equals(fresh.get("journal_sha256"))) {
            throw new IllegalStateException("seed " + seed + " row " + row.label()
                + ": the journal written again differs from the first attempt's; the session is not deterministic");
          }
          r = fresh;
          parts.putRow(row.label(), r);
          for (final String arm : missing) {
            final long t0 = System.nanoTime();
            final SimDay day = SimDay.of(spec, seed, date);
            final Strategy strategy = factory.create(arm, day);
            final BacktestResult result =
                new BacktestRunner(BacktestConfig.forSimV2(presets, day, List.of(journal), null)).run(strategy);
            final Map<String, Object> m = metrics(result, instruments, (System.nanoTime() - t0) / 1_000_000L);
            for (final Map.Entry<String, Long> c : strategy.counters().entrySet()) {
              m.put(c.getKey(), c.getValue());
            }
            final String hash = factory.configurationHash(arm);
            if (hash != null) {
              m.put("config_hash", hash);
            }
            if (timingOnly) {
              m.keySet().retainAll(TIMING_KEYS);
            }
            parts.putArm(row.label(), arm, m);
          }
          Files.deleteIfExists(journal);
        }
        final Map<String, Object> out = new LinkedHashMap<>(r);
        final Map<String, Object> armsOut = new LinkedHashMap<>();
        for (final String arm : run.arms()) {
          armsOut.put(arm, parts.arm(row.label(), arm));
        }
        out.put("arms", armsOut);
        rowsOut.put(row.label(), out);
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
