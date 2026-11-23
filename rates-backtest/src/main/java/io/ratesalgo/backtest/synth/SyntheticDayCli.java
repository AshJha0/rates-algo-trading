package io.ratesalgo.backtest.synth;

import io.ratesalgo.backtest.experiment.SpentData;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes one synthetic session to a journal.
 *
 * <pre>
 *   SyntheticDayCli --sim-v2 --seed 30000000 --out day.raj [--truth truth.csv] [--repo .]
 *   SyntheticDayCli --date 2026-02-02 --out day.raj [--seed-base 5000000] [--truth truth.csv] [--repo .]   (version 1)
 * </pre>
 *
 * <p>Simulator v2 ({@code --sim-v2}) writes the full-day session of a seed from the shared market
 * model (docs/research/SIM_V2_DESIGN.md section 10); the seed must be a v2 seed (base + n, base a
 * whole million of at least 28,000,000) and not a spent one.
 *
 * <p>Version 1 is superseded for new experiments: it is kept so that the recorded ledger rows
 * stay reproducible, and it prints a notice saying so. Its seed is {@code seed-base +
 * epochDay(date)}, the rule the version 1 experiments used.
 */
public final class SyntheticDayCli {

  /** Printed before every version 1 day. */
  public static final String SUPERSEDED =
      "notice: the version 1 synthetic day is superseded for new experiments by simulator v2 (--sim-v2);"
          + " it is kept only to reproduce recorded ledger rows";

  private SyntheticDayCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != 0) {
      System.exit(code);
    }
  }

  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> o = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if ("--sim-v2".equals(args[i])) {
        o.put("sim-v2", "true");
      } else if (i + 1 < args.length) {
        o.put(args[i].replaceFirst("^--", ""), args[++i]);
      }
    }
    if (!o.containsKey("out") || !(o.containsKey("date") || (o.containsKey("sim-v2") && o.containsKey("seed")))) {
      out.println("usage: SyntheticDayCli --sim-v2 --seed N --out FILE [--truth FILE] [--repo DIR]");
      out.println("       SyntheticDayCli --date YYYY-MM-DD --out FILE [--seed-base N] [--truth FILE] [--repo DIR]  (version 1)");
      return 1;
    }
    final Path repo = Path.of(o.getOrDefault("repo", "."));
    final Path journal = Path.of(o.get("out"));
    try {
      Files.deleteIfExists(journal);
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    final Path truth = o.containsKey("truth") ? Path.of(o.get("truth")) : null;
    final Presets presets = Presets.load(repo.resolve("data/presets.yaml"));
    if (o.containsKey("sim-v2")) {
      final long seed = Long.parseLong(o.get("seed"));
      final String refusal = SpentData.spentRefusal(seed - seed % SessionSeeds.SPAN);
      if (refusal != null) {
        out.println("refused: " + refusal);
        return 2;
      }
      final SimDay day = SimDay.of(new MarketSpec(SimParams.loadDefault()), seed, PairedSessions.labelDate(seed));
      final SyntheticDay.Summary s = SyntheticDay.generateSimV2(day, SyntheticDay.Publication.fromPresets(presets), journal, truth);
      out.println(
          "simulator v2 seed " + seed + " (" + day.model().dayType() + ", label " + day.date() + "): " + s.frames()
              + " frames, " + s.rfqs() + " rfqs (" + s.informedRfqs() + " informed), " + s.bookUpdates()
              + " book updates, " + s.curveInputs() + " curve inputs, chain hash " + Long.toHexString(s.chainHash()));
      return 0;
    }
    out.println(SUPERSEDED);
    final LocalDate date = LocalDate.parse(o.get("date"));
    final long seed = Long.parseLong(o.getOrDefault("seed-base", "5000000")) + date.toEpochDay();
    final SyntheticDay day = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
    final SyntheticDay.Summary s = day.generate(date, seed, journal, truth);
    out.println(
        date + " seed " + seed + ": " + s.frames() + " frames, " + s.rfqs() + " rfqs (" + s.informedRfqs()
            + " informed), " + s.bookUpdates() + " book updates, " + s.curveInputs() + " curve inputs, chain hash "
            + Long.toHexString(s.chainHash()));
    return 0;
  }
}
