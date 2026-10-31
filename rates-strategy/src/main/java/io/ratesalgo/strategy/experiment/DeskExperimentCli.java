package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.common.time.SystemClock;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line for the desk-feature experiments (API_SPEC.md Section 19.9).
 *
 * <pre>
 *   DeskExperimentCli --preregs data/preregs/R2B-001.yaml,...,data/preregs/R2B-007.yaml
 *                     [--ledger data/ledger/ledger.jsonl] [--repo .] [--work target/desk-experiment]
 *                     [--scope evaluation|holdout] [--commit SHA] [--dirty true|false]
 * </pre>
 *
 * <p>Refuses (exit code 2, nothing run, nothing recorded) when a pre-registration is missing or
 * invalid, when they do not share one config, period and holdout, when one names a treatment the
 * experiment does not know, or when the holdout has already been opened. Otherwise runs all seven
 * treatments once and appends one ledger record per pre-registration, whatever the verdict.
 */
public final class DeskExperimentCli {

  public static final int OK = 0;
  public static final int REFUSED = 2;

  private DeskExperimentCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != OK) {
      throw new IllegalStateException("desk experiment exited with code " + code);
    }
  }

  /** Runs the command; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = parse(args);
    if (!opts.containsKey("preregs")) {
      out.println("refused: --preregs is required; an experiment without a pre-registration is not run");
      return REFUSED;
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final Path work = Path.of(opts.getOrDefault("work", "target/desk-experiment")).toAbsolutePath();
    final String scope = opts.getOrDefault("scope", "evaluation");
    if (!"evaluation".equals(scope) && !"holdout".equals(scope)) {
      out.println("refused: --scope must be evaluation or holdout");
      return REFUSED;
    }
    final List<PreRegistration> preregs = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    for (final String p : opts.get("preregs").split(",", -1)) {
      try {
        preregs.add(PreRegistration.load(repo.resolve(p.trim())).requireValid(repo));
      } catch (final PreRegistrationException e) {
        problems.addAll(e.problems());
      }
    }
    if (problems.isEmpty()) {
      problems.addAll(consistency(preregs));
    }
    final Ledger ledger = new Ledger(ledgerFile);
    if (problems.isEmpty() && "holdout".equals(scope)) {
      for (final PreRegistration p : preregs) {
        if (ledger.looks(p.id(), scope) > 0) {
          problems.add("the holdout of " + p.id() + " has already been opened once");
        }
      }
    }
    if (!problems.isEmpty()) {
      out.println("refused:");
      for (final String p : problems) {
        out.println("  " + p);
      }
      return REFUSED;
    }
    final PreRegistration first = preregs.get(0);
    final List<LocalDate> days = "holdout".equals(scope) ? first.holdout() : first.period().businessDays();
    final DeskExperiment experiment = new DeskExperiment(repo, repo.resolve(first.config()));
    final DeskExperiment.Outcome outcome = experiment.run(days, work);

    final StringBuilder concat = new StringBuilder();
    for (final Map<String, Object> j : outcome.journals()) {
      concat.append(j.get("sha256")).append('\n');
    }
    final String journalSha = Hashing.sha256Text(concat.toString());
    final String commit = opts.getOrDefault("commit", "unknown");
    final String recorded =
        Instant.ofEpochSecond(SystemClock.INSTANCE.epochNanos() / 1_000_000_000L).toString();
    for (final PreRegistration p : preregs) {
      final String treatment = p.treatments().get(0);
      final Map<String, Object> record = new LinkedHashMap<>();
      record.put("prereg_id", p.id());
      record.put("prereg_sha256", p.sha256());
      record.put("config_sha256", p.configSha256());
      record.put("journal_sha256", journalSha);
      record.put("journals", outcome.journals());
      record.put("code_commit", commit);
      record.put("code_dirty", Boolean.parseBoolean(opts.getOrDefault("dirty", "false")));
      record.put("scope", scope);
      record.put("look", ledger.looks(p.id(), scope) + 1);
      record.put("recorded_utc", recorded);
      record.put("results", outcome.resultsByTreatment().get(treatment));
      record.put("verdict", outcome.verdictByTreatment().get(treatment));
      ledger.append(record);
      out.println(
          p.id() + " " + treatment + " " + scope + ": " + outcome.verdictByTreatment().get(treatment)
              + (outcome.adoptedByTreatment().get(treatment) ? ", adopted" : ", not adopted"));
    }
    out.println("recorded in " + repo.relativize(ledgerFile).toString().replace('\\', '/'));
    return OK;
  }

  /** The pre-registrations must share a config, period and holdout, and name one known treatment each. */
  static List<String> consistency(final List<PreRegistration> preregs) {
    final List<String> problems = new ArrayList<>();
    final PreRegistration first = preregs.get(0);
    for (final PreRegistration p : preregs) {
      if (!p.config().equals(first.config()) || !p.period().equals(first.period())
          || !p.holdout().equals(first.holdout())) {
        problems.add(p.id() + ": config, period and holdout must match " + first.id());
      }
      if (!DeskFactory.BASELINE.equals(p.baseline())) {
        problems.add(p.id() + ": baseline must be " + DeskFactory.BASELINE);
      }
      if (p.treatments().size() != 1 || !DeskFactory.TREATMENTS.contains(p.treatments().get(0))) {
        problems.add(p.id() + ": exactly one treatment out of " + DeskFactory.TREATMENTS);
      }
    }
    return problems;
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
}
