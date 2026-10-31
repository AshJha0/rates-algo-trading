package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.prereg.ExperimentCli;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** COOKBOOK.md recipe 18: pre-register an experiment, see the refusals, record a run. */
public final class Recipe18Ledger {

  private Recipe18Ledger() {}

  public static void main(final String[] args) throws IOException {
    // a scratch repository with the real presets and a copy of the P5-001 experiment config
    final Path repo = Files.createTempDirectory("recipe18");
    Files.createDirectories(repo.resolve("data/experiments"));
    Files.createDirectories(repo.resolve("data/preregs"));
    Files.copy(RepoRoot.find().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
    Files.copy(
        RepoRoot.find().resolve("data/experiments/P5-001.config.yaml"),
        repo.resolve("data/experiments/demo.config.yaml"));
    final String configSha = Hashing.sha256TextFile(repo.resolve("data/experiments/demo.config.yaml"));

    final String prereg =
        """
        prereg_version: 1
        id: DEMO-001
        title: cookbook demonstration
        hypothesis: the model spread earns more than the fixed spread
        metric: total position P&L per session, USD
        baseline: fixed_spread
        treatments: [model_spread]
        period: {start: 2026-03-02, end: 2026-03-03}
        fitting_period: {start: 2026-01-05, end: 2026-01-16}
        holdout: [HOLDOUT]
        success_threshold: Holm-adjusted Welch p below 0.05 and bootstrap interval above zero at every band point
        assumption_band: {preset: usd_rfq_hit_rate, parameter: spread_to_cover_bp, points: [low, central, high]}
        author: cookbook
        date: 2026-10-31
        config: data/experiments/demo.config.yaml
        config_sha256: SHA
        """
            .replace("SHA", configSha);
    final Path file = repo.resolve("data/preregs/demo.yaml");

    System.out.println("1. no pre-registration:");
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("2. holdout overlapping the fitting period:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-01-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("3. valid pre-registration:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-03-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml", "--commit", "recipe18");

    final Ledger ledger = new Ledger(repo.resolve("data/ledger/ledger.jsonl"));
    final Map<String, Object> entry = ledger.entries().get(0);
    System.out.println(
        "ledger seq "
            + entry.get("seq")
            + ", prereg_sha256 matches file: "
            + Hashing.sha256TextFile(file).equals(entry.get("prereg_sha256"))
            + ", journals "
            + ((java.util.List<?>) entry.get("journals")).size()
            + ", verdict "
            + entry.get("verdict"));

    System.out.println("4. holdout opened twice:");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout", "--commit", "recipe18");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout");
    System.out.println("ledger lines: " + ledger.entries().size());
  }

  private static void run(final Path repo, final String... args) throws IOException {
    final Path work = Files.createDirectories(repo.resolve("work"));
    final String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--repo";
    all[args.length + 1] = repo.toString();
    all[args.length + 2] = "--work";
    all[args.length + 3] = work.toString();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final int code;
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      code = ExperimentCli.run(all, out);
    }
    final String text =
        bytes.toString(StandardCharsets.UTF_8).replace(repo.toString(), "<repo>").replace('\\', '/');
    for (final String line : text.strip().split("\\R", -1)) {
      System.out.println("   " + line.strip());
    }
    System.out.println("   exit code " + code);
  }
}
