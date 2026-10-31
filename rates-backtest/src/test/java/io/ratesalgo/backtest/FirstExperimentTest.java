package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.prereg.Experiment;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first pre-registered experiment (P5-001) is recorded in the committed ledger, and running
 * it again from its pre-registration reproduces the recorded results exactly (P5 gate).
 */
class FirstExperimentTest {

  @TempDir Path work;

  @Test
  @DisplayName("P5-001 is in the ledger and re-running it reproduces the recorded results")
  void ledgerRecordReproduces() {
    final Path repo = TestSupport.repoRoot();
    final PreRegistration prereg = PreRegistration.load(repo.resolve("data/preregs/P5-001.yaml")).requireValid(repo);
    final List<Map<String, Object>> entries = new Ledger(repo.resolve("data/ledger/ledger.jsonl")).entries();
    final Map<String, Object> record =
        entries.stream()
            .filter(e -> "P5-001".equals(e.get("prereg_id")) && "evaluation".equals(e.get("scope")))
            .findFirst()
            .orElseThrow();
    assertEquals(prereg.sha256(), record.get("prereg_sha256"));
    assertEquals(prereg.configSha256(), record.get("config_sha256"));
    assertFalse(String.valueOf(record.get("verdict")).isBlank());

    final Experiment.Outcome again =
        new Experiment(repo, repo.resolve(prereg.config()))
            .run(prereg.baseline(), prereg.treatments(), prereg.period().businessDays(), work);
    assertEquals(record.get("verdict"), again.verdict());
    // compare through the canonical JSON writer so number types do not matter
    assertEquals(
        Json.write(Yaml.parse(Json.write(record.get("results")))),
        Json.write(Yaml.parse(Json.write(again.results()))));
    final List<Object> recorded = Yaml.asList(record.get("journals"), "journals");
    for (int i = 0; i < recorded.size(); i++) {
      assertEquals(
          Yaml.asMap(recorded.get(i), "journal").get("sha256"), again.journals().get(i).get("sha256"));
    }
    final Map<String, Object> band = Yaml.asMap(Yaml.asMap(record.get("results"), "r").get("band"), "band");
    assertTrue(band.keySet().containsAll(List.of("low", "central", "high")), "reported across the band");
  }
}
