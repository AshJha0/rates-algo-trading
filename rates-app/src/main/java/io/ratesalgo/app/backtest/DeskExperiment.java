package io.ratesalgo.app.backtest;

import io.ratesalgo.backtest.experiment.ExperimentRunner;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The simulator v2 experiment runner with the desk's arms ({@link DeskArms}): the same commands
 * as {@link ExperimentRunner}, with {@code --factory} set to the desk unless given.
 *
 * <pre>
 *   DeskExperiment run    --prereg research/prereg/R5-007.yaml --out D:/runs/R5-007 [--workers 8]
 *   DeskExperiment pilot  --prereg research/prereg/SIM3-BASE-001.yaml --pilot 8 --out D:/runs/pilot
 *   DeskExperiment status --out D:/runs/R5-007
 *   DeskExperiment validate --prereg research/prereg/R5-001.yaml
 * </pre>
 */
public final class DeskExperiment {

  private DeskExperiment() {}

  public static void main(final String[] args) {
    final int code = run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8));
    if (code != ExperimentRunner.OK) {
      System.exit(code);
    }
  }

  /** Runs a command with the desk's arms; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    final List<String> all = new ArrayList<>(Arrays.asList(args));
    if (!all.contains("--factory") && !all.isEmpty() && !"status".equals(all.get(0))) {
      all.add("--factory");
      all.add(DeskArms.class.getName());
    }
    return ExperimentRunner.run(all.toArray(new String[0]), out);
  }
}
