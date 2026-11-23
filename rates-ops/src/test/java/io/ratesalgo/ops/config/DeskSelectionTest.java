package io.ratesalgo.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desk section: a profile names four components and each can be switched on top of it. */
class DeskSelectionTest {

  private static final Path CONFIGS = Path.of("../deploy/config");

  @TempDir Path dir;

  private ProcessConfig withDesk(final String section) throws Exception {
    final Path file = dir.resolve("c.yaml");
    Files.writeString(file, Files.readString(CONFIGS.resolve("trading-blue.yaml")) + section, StandardCharsets.UTF_8);
    return ProcessConfig.load(file, ProcessConfig.schema(ConfigValidationTest.SCHEMA), Map.of());
  }

  @Test
  void theCommittedFilesSelectTheRecordedDeskExceptTheV2Example() throws Exception {
    final JsonSchema schema = ProcessConfig.schema(ConfigValidationTest.SCHEMA);
    for (final String f : List.of("trading-blue", "trading-green", "trading-lowlatency")) {
      final ProcessConfig c = ProcessConfig.load(CONFIGS.resolve(f + ".yaml"), schema, Map.of());
      assertEquals(ProcessConfig.DeskSelection.DEFAULT, c.desk(), f);
      assertTrue(c.desk().isRecorded());
    }
    assertEquals(List.of(), ProcessConfig.check(CONFIGS.resolve("trading-v2.yaml"), schema));
    final ProcessConfig v2 = ProcessConfig.load(CONFIGS.resolve("trading-v2.yaml"), schema, Map.of());
    assertEquals(ProcessConfig.DeskSelection.V2, v2.desk());
    assertEquals("market=simV2 pricing=v2 strategy=v2 router=ladder", v2.desk().components());
    assertFalse(v2.desk().isRecorded());
    assertEquals("trading-v2", v2.name());
  }

  @Test
  void eachComponentIsSwitchedOnTopOfTheProfile() throws Exception {
    final ProcessConfig.DeskSelection pricing = withDesk("desk:\n  pricing: v2\n").desk();
    assertEquals(new ProcessConfig.DeskSelection("default", "standard", "v2", "v1", "touch"), pricing);
    assertFalse(pricing.isRecorded());
    final ProcessConfig.DeskSelection back =
        withDesk("desk:\n  profile: v2\n  market: standard\n  router: touch\n  strategy: v1\n").desk();
    assertEquals(new ProcessConfig.DeskSelection("v2", "standard", "v2", "v1", "touch"), back);
    // every component switched back is the recorded desk, whatever the profile key says
    assertTrue(withDesk("desk:\n  profile: v2\n  market: standard\n  pricing: v1\n  router: touch\n  strategy: v1\n")
        .desk().isRecorded());
    assertTrue(withDesk("desk: {}\n").desk().isRecorded());
  }

  @Test
  void unknownValuesAndKeysAreRefused() {
    for (final String bad : List.of("desk:\n  profile: v3\n", "desk:\n  market: moon\n", "desk:\n  hedger: v2\n")) {
      final ProcessConfig.InvalidConfigException e =
          assertThrows(ProcessConfig.InvalidConfigException.class, () -> withDesk(bad), bad);
      assertTrue(e.errors().stream().anyMatch(x -> x.contains("desk")), e.errors().toString());
    }
    assertThrows(IllegalArgumentException.class, () -> ProcessConfig.DeskSelection.profile("v3"));
  }

  @Test
  void theRecordedLineRoundTripsAndNothingElseParses() {
    for (final ProcessConfig.DeskSelection d :
        List.of(ProcessConfig.DeskSelection.DEFAULT, ProcessConfig.DeskSelection.V2,
            new ProcessConfig.DeskSelection("default", "simV2", "v1", "v2", "touch"))) {
      assertEquals(d.components(), ProcessConfig.DeskSelection.parse(d.components() + "\n").components());
    }
    for (final String bad : List.of("", "market=simV2", "market=moon pricing=v2 strategy=v2 router=ladder",
        "pricing=v2 market=simV2 strategy=v2 router=ladder")) {
      assertThrows(IllegalArgumentException.class, () -> ProcessConfig.DeskSelection.parse(bad), bad);
    }
  }
}
