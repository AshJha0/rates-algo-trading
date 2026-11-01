package io.ratesalgo.strategy.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.strategy.CostAwareHedgeBudget;
import io.ratesalgo.strategy.HedgeSlicing;
import io.ratesalgo.strategy.RiskLadder;
import io.ratesalgo.strategy.Support;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hedge slicing, the cost-aware trigger and the V1H harness (DR-V1H-01 to DR-V1H-03). */
class HedgeExperimentTest {

  private static final String CONFIG = "data/experiments/V1H.config.yaml";

  @TempDir Path dir;

  @Test
  @DisplayName("slicing: children follow displayed depth, at least the minimum, at most what is left")
  void childSizes() {
    final HedgeSlicing s = new HedgeSlicing(0.5, 10, 5_000_000_000L, 400_000);
    assertEquals(250, s.childLots(1_400, 500));
    assertEquals(10, s.childLots(1_400, 4));
    assertEquals(7, s.childLots(7, 500));
    assertThrows(IllegalArgumentException.class, () -> new HedgeSlicing(0, 10, 0, 1));
  }

  @Test
  @DisplayName("cost-aware trigger: depth-walk cost, the hold-versus-hedge rule and the cap")
  void costAwareTrigger() {
    // gamma 1e-6, sigma 0.1 bp/sqrt(s), horizon 100 s: hold(a) = 5e-7 * a^2
    final CostAwareHedgeBudget b = new CostAwareHedgeBudget(1e-6, 0.1, 100, 0.02, 1, 800_000, 0.1, 0.2, 40_000);
    assertEquals(0.12 * 30_000, b.hedgeCost(30_000), 1e-9);
    assertEquals(0.12 * 100_000 + 0.2 * 60_000, b.hedgeCost(100_000), 1e-6);
    assertEquals(5e-7 * 1e10, b.holdCost(100_000), 1e-6);
    b.observeMarket(0.05, 0.2, 40_000);
    assertEquals(0.07 * 30_000, b.hedgeCost(30_000), 1e-9);
    final RiskLadder ladder = Support.ladder();
    assertEquals(0.0, b.excessDv01(ladder, 0L), 0.0, "a flat book is not hedged");
    assertThrows(
        IllegalArgumentException.class, () -> new CostAwareHedgeBudget(1e-6, 0.1, 100, 0.02, 0, 1, 0.1, 0.2, 1));
  }

  @Test
  @DisplayName("harness: two days at every band point; slicing works passively and escalates; refusals")
  void twoDays() {
    final HedgeExperiment e = new HedgeExperiment(Support.repoRoot(), Support.repoRoot().resolve(CONFIG));
    final List<LocalDate> days = List.of(LocalDate.of(2026, 4, 13), LocalDate.of(2026, 4, 14));
    final HedgeExperiment.Outcome o = e.run(days, dir.resolve("work"));
    assertEquals(List.of(HedgeExperiment.SLICED, HedgeExperiment.ADAPTIVE_TRIGGER), List.copyOf(o.resultsByTreatment().keySet()));
    assertEquals(6, o.journals().size());
    @SuppressWarnings("unchecked")
    final Map<String, Object> central =
        (Map<String, Object>) ((Map<String, Object>) o.resultsByTreatment().get(HedgeExperiment.SLICED).get("band"))
            .get(BandPoint.CENTRAL.label());
    @SuppressWarnings("unchecked")
    final Map<String, Map<String, Double>> sec = (Map<String, Map<String, Double>>) central.get("secondary_means");
    assertTrue(sec.get("hedges").get("treatment") > sec.get("hedges").get("baseline"), "slicing sends more orders");
    assertTrue(sec.get("passive_fills").get("treatment") + sec.get("escalations").get("treatment") > 0);
    assertEquals(0.0, sec.get("passive_fills").get("baseline"), 0.0);

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final int code =
        HedgeExperimentCli.run(
            new String[] {"--preregs", "data/preregs/V1H-001.yaml", "--repo", Support.repoRoot().toString(),
              "--config", CONFIG},
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    assertEquals(HedgeExperimentCli.REFUSED, code, "--config is only for the fitting period");
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("--config"));
  }
}
