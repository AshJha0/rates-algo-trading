package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.report.BandRisk;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.common.time.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line for pre-registered experiments (API_SPEC.md Section 16.8).
 *
 * <pre>
 *   ExperimentCli --prereg data/preregs/P5-001.yaml [--ledger data/ledger/ledger.jsonl]
 *                 [--repo .] [--work target/experiment] [--scope evaluation|holdout]
 *                 [--commit SHA] [--dirty true|false]
 * </pre>
 *
 * <p>Refuses (exit code 2, nothing run, nothing recorded) when the pre-registration is missing or
 * invalid, when a holdout date overlaps the fitting period or the evaluation period, when the
 * config hash does not match, or when the holdout has already been opened once. Otherwise runs
 * the experiment on the evaluation period's business days (or on the holdout dates), appends one
 * record to the ledger whatever the verdict, writes {@code <id>-<scope>-band_risk.json} and {@code
 * .md} (daily-P&amp;L volatility, Sharpe ratio and maximum drawdown per strategy and band point, a
 * simulator consistency check derived from the recorded daily P&amp;L) to the work directory, and
 * exits 0.
 */
public final class ExperimentCli {

  public static final int OK = 0;
  public static final int ERROR = 1;
  public static final int REFUSED = 2;

  private ExperimentCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != OK) {
      System.exit(code);
    }
  }

  /** Runs the command; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = parse(args);
    if (!opts.containsKey("prereg")) {
      out.println("refused: --prereg is required; an experiment without a pre-registration is not run");
      return REFUSED;
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final Path work = Path.of(opts.getOrDefault("work", "target/experiment")).toAbsolutePath();
    final String scope = opts.getOrDefault("scope", "evaluation");
    if (!"evaluation".equals(scope) && !"holdout".equals(scope)) {
      out.println("refused: --scope must be evaluation or holdout");
      return REFUSED;
    }
    final PreRegistration prereg;
    try {
      prereg = PreRegistration.load(repo.resolve(opts.get("prereg"))).requireValid(repo);
    } catch (final PreRegistrationException e) {
      out.println("refused:");
      for (final String p : e.problems()) {
        out.println("  " + p);
      }
      return REFUSED;
    }
    final Ledger ledger = new Ledger(ledgerFile);
    final long previous = ledger.looks(prereg.id(), scope);
    if ("holdout".equals(scope) && previous > 0) {
      out.println("refused: the holdout of " + prereg.id() + " has already been opened once");
      return REFUSED;
    }
    final List<LocalDate> days = "holdout".equals(scope) ? prereg.holdout() : prereg.period().businessDays();
    final Experiment experiment = new Experiment(repo, repo.resolve(prereg.config()));
    final Experiment.Outcome outcome = experiment.run(prereg.baseline(), prereg.treatments(), days, work);

    final StringBuilder concat = new StringBuilder();
    for (final Map<String, Object> j : outcome.journals()) {
      concat.append(j.get("sha256")).append('\n');
    }
    final Map<String, Object> record = new LinkedHashMap<>();
    record.put("prereg_id", prereg.id());
    record.put("prereg_sha256", prereg.sha256());
    record.put("config_sha256", prereg.configSha256());
    record.put("journal_sha256", Hashing.sha256Text(concat.toString()));
    record.put("journals", outcome.journals());
    record.put("code_commit", opts.getOrDefault("commit", gitHead(repo)));
    record.put("code_dirty", Boolean.parseBoolean(opts.getOrDefault("dirty", "false")));
    record.put("scope", scope);
    record.put("look", previous + 1);
    record.put(
        "recorded_utc", Instant.ofEpochSecond(SystemClock.INSTANCE.epochNanos() / 1_000_000_000L).toString());
    record.put("results", outcome.results());
    record.put("verdict", outcome.verdict());
    ledger.append(record);
    final List<Map<String, Object>> risk = BandRisk.fromExperimentResults(outcome.results());
    try {
      Files.createDirectories(work);
      Files.writeString(work.resolve(prereg.id() + "-" + scope + "-band_risk.json"), BandRisk.toJson(risk), StandardCharsets.UTF_8);
      Files.writeString(work.resolve(prereg.id() + "-" + scope + "-band_risk.md"), BandRisk.toMarkdown(risk), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    out.println(
        prereg.id() + " " + scope + " look " + (previous + 1) + ": " + outcome.verdict() + " (" + days.size() + " days)");
    out.println("recorded in " + repo.relativize(ledgerFile).toString().replace('\\', '/'));
    return OK;
  }

  static Map<String, String> parse(final String[] args) {
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--") || i + 1 >= args.length) {
        throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
      }
      opts.put(args[i].substring(2), args[++i]);
    }
    return opts;
  }

  private static String gitHead(final Path repo) {
    try {
      final Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo.toFile()).start();
      try (InputStream in = p.getInputStream()) {
        final String sha = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        return p.waitFor() == 0 && !sha.isEmpty() ? sha : "unknown";
      }
    } catch (final IOException e) {
      return "unknown";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }
}
