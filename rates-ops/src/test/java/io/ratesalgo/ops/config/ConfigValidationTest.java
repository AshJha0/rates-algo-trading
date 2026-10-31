package io.ratesalgo.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The versioned configurations validate; broken ones are refused with every reason. */
class ConfigValidationTest {

  static final Path SCHEMA = Path.of("../deploy/config/schema/process-config.schema.json");
  static final Path BLUE = Path.of("../deploy/config/trading-blue.yaml");

  @TempDir Path dir;

  @Test
  void committedConfigurationsAreValid() throws Exception {
    final JsonSchema schema = ProcessConfig.schema(SCHEMA);
    for (final String f : List.of("trading-blue", "trading-green", "venue-sim")) {
      assertEquals(List.of(), ProcessConfig.check(Path.of("../deploy/config/" + f + ".yaml"), schema), f);
    }
    final ProcessConfig c = ProcessConfig.load(BLUE, schema, Map.of());
    assertEquals("trading-blue", c.name());
    assertEquals(8081, c.port());
    assertEquals(2, c.instruments().size());
    assertEquals(2001, c.instruments().get(0)[0]);
    assertEquals(5, c.retentionYears());
    assertEquals(200, c.maxMessagesPerSecond());
  }

  @Test
  void environmentOverridesApplyBeforeValidation() throws Exception {
    final JsonSchema schema = ProcessConfig.schema(SCHEMA);
    final ProcessConfig c = ProcessConfig.load(BLUE, schema,
        Map.of("RATES_HTTP_PORT", "9100", "RATES_JOURNAL_DIR", "/tmp/j"));
    assertEquals(9100, c.port());
    assertEquals("/tmp/j", c.journalDirectory());
    final ProcessConfig.InvalidConfigException e = assertThrows(ProcessConfig.InvalidConfigException.class,
        () -> ProcessConfig.load(BLUE, schema, Map.of("RATES_HTTP_PORT", "not-a-port")));
    assertTrue(e.errors().get(0).contains("$.http.port"), e.errors()::toString);
  }

  @Test
  void brokenConfigurationsReportEveryViolation() throws Exception {
    final String text = Files.readString(BLUE)
        .replace("role: trading", "role: router")
        .replace("years: 5", "years: 3")
        .replace("cutTimeUtc: \"17:00\"", "cutTimeUtc: \"5pm\"")
        .replace("  maxMessagesPerSecond: 200\n", "  maxMessagesPerSecond: 200\n  surprise: 1\n")
        .replace("    - instrumentId: 1001\n", "    - instrumentId: 0\n");
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, text);
    final List<String> errors = ProcessConfig.check(bad, ProcessConfig.schema(SCHEMA));
    assertEquals(5, errors.size(), errors::toString);
    assertTrue(errors.stream().anyMatch(x -> x.startsWith("$.process.role")));
    assertTrue(errors.stream().anyMatch(x -> x.startsWith("$.retention.years")));
    assertTrue(errors.stream().anyMatch(x -> x.startsWith("$.recon.cutTimeUtc")));
    assertTrue(errors.stream().anyMatch(x -> x.contains("'surprise' is not allowed")));
    assertTrue(errors.stream().anyMatch(x -> x.startsWith("$.quoting.instruments[1].instrumentId")));
  }

  @Test
  void schemaSubsetRulesAndRefusals() {
    final JsonSchema s = new JsonSchema(castMap(ProcessConfig.parse(new StringReader(
        "{type: object, required: [a], properties: {a: {type: array, minItems: 1, maxItems: 2, items: {type: number}},"
            + " b: {type: string, minLength: 2}, c: {type: boolean}, d: {const: 3}, e: {type: 'null'}}}"))));
    assertEquals(List.of(), s.validate(Map.of("a", List.of(1, 2.5))));
    assertEquals(1, s.validate(Map.of("a", List.of())).size());
    assertEquals(1, s.validate(Map.of("a", List.of(1, 2, 3))).size());
    assertEquals(1, s.validate(Map.of("a", List.of("x"))).size());
    assertEquals(2, s.validate(Map.of("b", "x")).size());
    assertEquals(1, s.validate(Map.of("a", List.of(1), "c", "yes")).size());
    assertEquals(1, s.validate(Map.of("a", List.of(1), "d", 4)).size());
    assertEquals(1, s.validate("not an object").size());
    assertThrows(IllegalArgumentException.class, () -> new JsonSchema(Map.of("oneOf", List.of())));
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchema(Map.of("additionalProperties", Map.of("type", "string"))));
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchema(Map.of("items", Map.of("anyOf", List.of()))));
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchema(Map.of("type", "tuple")).validate(1));
  }

  @Test
  void validatorMainReportsAndExits() throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
    assertEquals(0, ConfigValidatorMain.run(new String[] {SCHEMA.toString(), BLUE.toString()}, ps));
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, "schemaVersion: 2\n");
    assertEquals(1, ConfigValidatorMain.run(new String[] {SCHEMA.toString(), bad.toString(),
        dir.resolve("missing.yaml").toString()}, ps));
    assertEquals(2, ConfigValidatorMain.run(new String[] {SCHEMA.toString()}, ps));
    assertEquals(2, ConfigValidatorMain.run(new String[] {dir.resolve("none.json").toString(), BLUE.toString()}, ps));
    final String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("valid   ") && text.contains("INVALID "), text);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(final Object o) {
    return (Map<String, Object>) o;
  }
}
