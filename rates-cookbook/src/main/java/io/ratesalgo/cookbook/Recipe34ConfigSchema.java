package io.ratesalgo.cookbook;

import io.ratesalgo.ops.config.JsonSchema;
import io.ratesalgo.ops.config.ProcessConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** COOKBOOK.md recipe 34: validate a deployment config against its schema and read the error. */
public final class Recipe34ConfigSchema {

  private Recipe34ConfigSchema() {}

  @SuppressWarnings("unchecked")
  public static void main(final String[] args) throws IOException {
    final Path root = RepoRoot.find();
    final JsonSchema schema = ProcessConfig.schema(root.resolve("deploy/config/schema/process-config.schema.json"));
    final Path blue = root.resolve("deploy/config/trading-blue.yaml");

    // the shipped file: valid, and the typed view the process starts from
    System.out.println("deploy/config/trading-blue.yaml errors: " + ProcessConfig.check(blue, schema));
    final ProcessConfig cfg = ProcessConfig.load(blue, schema, Map.of());
    System.out.println("  " + cfg.name() + " role " + cfg.role() + " colour " + cfg.colour() + " port " + cfg.port()
        + " instruments " + cfg.instruments().size() + " retention " + cfg.retentionYears() + "y");

    // the same document with four mistakes an operator could make by hand
    final Map<String, Object> doc;
    try (Reader r = Files.newBufferedReader(blue, StandardCharsets.UTF_8)) {
      doc = (Map<String, Object>) ProcessConfig.parse(r);
    }
    ((Map<String, Object>) doc.get("process")).put("colour", "red");
    ((Map<String, Object>) doc.get("http")).put("port", 80810);
    ((Map<String, Object>) doc.get("journal")).put("capacityBytes", "64MB");
    doc.remove("retention");
    final List<String> errors = schema.validate(doc);
    System.out.println("edited document: " + errors.size() + " errors");
    errors.forEach(e -> System.out.println("  " + e));

    // an environment override is validated too: the process refuses to start
    try {
      ProcessConfig.load(blue, schema, Map.of("RATES_HTTP_PORT", "70000"));
    } catch (final ProcessConfig.InvalidConfigException e) {
      System.out.println("RATES_HTTP_PORT=70000 refused: " + e.errors());
    }
  }
}
