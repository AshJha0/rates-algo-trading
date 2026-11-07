package io.ratesalgo.strategy.forward;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line of the forward log.
 *
 * <pre>
 *   ForwardCli --forward-dir forward --as-of 2026-11-06 --commit SHA [--repo .] [--config config/forward/desk.yaml]
 *   ForwardCli --forward-dir forward --verify
 * </pre>
 *
 * <p>The first form runs one {@link ForwardCycle} for the day; the input file {@code
 * forward/inputs/<as-of>.json} must already exist ({@code python -m ratesresearch.forward.fetch}).
 * The second only verifies the ledger's hash chain. Exit status 0 on success, 2 on bad arguments,
 * 1 on any failure (a broken chain included).
 */
public final class ForwardCli {

  private ForwardCli() {}

  public static void main(final String[] args) {
    System.exit(run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8)));
  }

  static int run(final String[] args, final PrintStream out) {
    final Map<String, String> a = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if ("--verify".equals(args[i])) {
        a.put("verify", "true");
      } else if (args[i].startsWith("--") && i + 1 < args.length) {
        a.put(args[i].substring(2), args[++i]);
      } else {
        out.println("unknown argument " + args[i]);
        return 2;
      }
    }
    if (!a.containsKey("forward-dir")) {
      out.println("--forward-dir is required");
      return 2;
    }
    final Path repo = Path.of(a.getOrDefault("repo", "."));
    final Path forward = Path.of(a.get("forward-dir"));
    try {
      final ForwardSettings settings =
          ForwardSettings.load(repo, repo.resolve(a.getOrDefault("config", "config/forward/desk.yaml")));
      if (a.containsKey("verify")) {
        final int n = new io.ratesalgo.backtest.prereg.Ledger(forward.resolve("ledger.jsonl")).entries().size();
        out.println("ledger chain verified: " + n + " lines");
        return 0;
      }
      if (!a.containsKey("as-of") || !a.containsKey("commit")) {
        out.println("--as-of and --commit are required");
        return 2;
      }
      final Path work = Files.createTempDirectory("forward-work");
      final ForwardCycle.Step step =
          new ForwardCycle(settings, forward, a.get("commit")).run(LocalDate.parse(a.get("as-of")), work);
      for (final String line : step.appended()) {
        out.println("appended " + line.substring(0, Math.min(160, line.length())));
      }
      if (step.appended().isEmpty()) {
        out.println("nothing to do: already decided from " + a.get("as-of"));
      }
      return 0;
    } catch (final RuntimeException | java.io.IOException e) {
      out.println("forward cycle failed: " + e);
      return 1;
    }
  }
}
