package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
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
 * Command line for the hedge execution experiments V1H-001 and V1H-002 ({@link HedgeExperiment}).
 *
 * <pre>
 *   HedgeExperimentCli --preregs data/preregs/V1H-001.yaml,data/preregs/V1H-002.yaml
 *                      [--ledger data/ledger/ledger.jsonl] [--repo .] [--work target/hedge-experiment]
 *                      [--scope evaluation|holdout|fitting] [--config FILE] [--commit SHA] [--dirty true|false]
 * </pre>
 *
 * <p>{@code evaluation} and {@code holdout} run once and append one ledger record per
 * pre-registration whatever the verdict; the holdout is refused once opened. {@code fitting} runs
 * the pre-registrations' fitting period, prints the results and records nothing; with {@code
 * --config} it runs another config, which is how parameters were chosen before the
 * pre-registrations were written. Exit code 2 (nothing run) on any refusal.
 */
public final class HedgeExperimentCli {

  public static final int OK = 0;
  public static final int REFUSED = 2;

  private HedgeExperimentCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != OK) {
      throw new IllegalStateException("hedge experiment exited with code " + code);
    }
  }

  /** Runs the command; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = DeskExperimentCli.parse(args);
    if (!opts.containsKey("preregs")) {
      out.println("refused: --preregs is required; an experiment without a pre-registration is not run");
      return REFUSED;
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final Path work = Path.of(opts.getOrDefault("work", "target/hedge-experiment")).toAbsolutePath();
    final String scope = opts.getOrDefault("scope", "evaluation");
    if (!List.of("evaluation", "holdout", "fitting").contains(scope)) {
      out.println("refused: --scope must be evaluation, holdout or fitting");
      return REFUSED;
    }
    if (opts.containsKey("config") && !"fitting".equals(scope)) {
      out.println("refused: --config is only allowed with --scope fitting");
      return REFUSED;
    }
    final List<PreRegistration> preregs = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    for (final String p : opts.get("preregs").split(",", -1)) {
      try {
        final PreRegistration r = PreRegistration.load(repo.resolve(p.trim()));
        preregs.add("fitting".equals(scope) ? r : r.requireValid(repo));
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
    final List<LocalDate> days =
        switch (scope) {
          case "holdout" -> first.holdout();
          case "fitting" -> first.fittingPeriod().businessDays();
          default -> first.period().businessDays();
        };
    final Path config = repo.resolve(opts.getOrDefault("config", first.config()));
    final HedgeExperiment.Outcome outcome = new HedgeExperiment(repo, config).run(days, work);
    if ("fitting".equals(scope)) {
      for (final String t : HedgeExperiment.TREATMENTS) {
        out.println(Json.write(outcome.resultsByTreatment().get(t)));
      }
      return OK;
    }
    final StringBuilder concat = new StringBuilder();
    for (final Map<String, Object> j : outcome.journals()) {
      concat.append(j.get("sha256")).append('\n');
    }
    final String journalSha = Hashing.sha256Text(concat.toString());
    final String recorded = Instant.ofEpochSecond(SystemClock.INSTANCE.epochNanos() / 1_000_000_000L).toString();
    for (final PreRegistration p : preregs) {
      final String treatment = p.treatments().get(0);
      final Map<String, Object> record = new LinkedHashMap<>();
      record.put("prereg_id", p.id());
      record.put("prereg_sha256", p.sha256());
      record.put("config_sha256", p.configSha256());
      record.put("journal_sha256", journalSha);
      record.put("journals", outcome.journals());
      record.put("code_commit", opts.getOrDefault("commit", "unknown"));
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

  /** The pre-registrations must share a config, period and holdout, and each pair one treatment with its baseline. */
  static List<String> consistency(final List<PreRegistration> preregs) {
    final List<String> problems = new ArrayList<>();
    final PreRegistration first = preregs.get(0);
    for (final PreRegistration p : preregs) {
      if (!p.config().equals(first.config()) || !p.period().equals(first.period())
          || !p.holdout().equals(first.holdout())) {
        problems.add(p.id() + ": config, period and holdout must match " + first.id());
      }
      final HedgeExperiment.Spec spec =
          p.treatments().size() == 1
              ? HedgeExperiment.SPECS.stream().filter(s -> s.treatment().equals(p.treatments().get(0))).findFirst()
                  .orElse(null)
              : null;
      if (spec == null) {
        problems.add(p.id() + ": exactly one treatment out of " + HedgeExperiment.TREATMENTS);
      } else if (!spec.baseline().equals(p.baseline())) {
        problems.add(p.id() + ": the baseline of " + spec.treatment() + " is " + spec.baseline());
      }
    }
    return problems;
  }
}
