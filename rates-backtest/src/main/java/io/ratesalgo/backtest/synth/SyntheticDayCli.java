package io.ratesalgo.backtest.synth;

import io.ratesalgo.backtest.model.Presets;
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
 *   SyntheticDayCli --date 2026-02-02 --out day.raj [--seed-base 5000000] [--truth truth.csv] [--repo .]
 * </pre>
 *
 * <p>The seed is {@code seed-base + epochDay(date)}, the rule the experiments use, so a journal
 * named in the ledger can be regenerated from its date alone.
 */
public final class SyntheticDayCli {

  private SyntheticDayCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != 0) {
      System.exit(code);
    }
  }

  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> o = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      o.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    if (!o.containsKey("date") || !o.containsKey("out")) {
      out.println("usage: SyntheticDayCli --date YYYY-MM-DD --out FILE [--seed-base N] [--truth FILE] [--repo DIR]");
      return 1;
    }
    final Path repo = Path.of(o.getOrDefault("repo", "."));
    final LocalDate date = LocalDate.parse(o.get("date"));
    final long seed = Long.parseLong(o.getOrDefault("seed-base", "5000000")) + date.toEpochDay();
    final Path journal = Path.of(o.get("out"));
    try {
      Files.deleteIfExists(journal);
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    final SyntheticDay day = new SyntheticDay(SyntheticDay.Params.fromPresets(Presets.load(repo.resolve("data/presets.yaml"))));
    final SyntheticDay.Summary s =
        day.generate(date, seed, journal, o.containsKey("truth") ? Path.of(o.get("truth")) : null);
    out.println(
        date + " seed " + seed + ": " + s.frames() + " frames, " + s.rfqs() + " rfqs (" + s.informedRfqs()
            + " informed), " + s.bookUpdates() + " book updates, " + s.curveInputs() + " curve inputs, chain hash "
            + Long.toHexString(s.chainHash()));
    return 0;
  }
}
