package io.ratesalgo.backtest.prereg;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Verifies an experiment ledger and prints its looks per data set.
 *
 * <pre>
 *   LedgerCli [--ledger data/ledger/ledger.jsonl] [--bh FAMILY_FILE | --bh family:ID | --bh data_set:KEY]
 * </pre>
 *
 * <p>{@code --bh} prints the Benjamini-Hochberg adjusted p and the verdict of every primary test
 * of a registered family ({@link FamilyAdjustment}); the adjustment is computed here, on request,
 * and never stored in a row.
 *
 * <p>Exit status 0 when the hash chain holds, 1 when it is broken or the file is missing. The
 * looks report ({@link DataSets#report}) is information: repeated looks at one data set are
 * printed as warnings and do not change the exit status, because the rows are history and are
 * never rewritten. {@code python -m ratesresearch.prereg} prints the same report.
 */
public final class LedgerCli {

  private LedgerCli() {}

  public static void main(final String[] args) {
    final int code = run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8));
    if (code != 0) {
      System.exit(code);
    }
  }

  /** Runs the command; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = ExperimentCli.parse(args);
    final Path file = Path.of(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    if (!Files.isRegularFile(file)) {
      out.println("ledger not found: " + file);
      return 1;
    }
    final List<Map<String, Object>> entries;
    try {
      entries = new Ledger(file).entries();
    } catch (final IllegalStateException e) {
      out.println(e.getMessage());
      return 1;
    }
    out.println("ledger chain verified: " + entries.size() + " lines");
    out.print(DataSets.report(entries));
    if (opts.containsKey("bh")) {
      final FamilyAdjustment.Family family;
      try {
        family = FamilyAdjustment.Family.of(opts.get("bh"));
      } catch (final IllegalArgumentException e) {
        out.println(e.getMessage());
        return 1;
      }
      out.print(FamilyAdjustment.report(entries, family));
    }
    return 0;
  }
}
