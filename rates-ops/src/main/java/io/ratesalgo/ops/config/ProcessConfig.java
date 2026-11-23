package io.ratesalgo.ops.config;

import io.ratesalgo.ops.http.OpsHttpServer;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A process's configuration: versioned YAML under deploy/config, validated against
 * deploy/config/schema/process-config.schema.json at start (API_SPEC.md Section 17.11). A process
 * whose configuration does not validate does not start.
 *
 * <p>Three values may be overridden from the environment so that one file serves Docker and
 * process-mode runs: {@code RATES_HTTP_PORT}, {@code RATES_HTTP_BIND} and {@code
 * RATES_JOURNAL_DIR}. Overrides are applied before validation. The files bind loopback; a
 * container, whose published port reaches only a non-loopback address, sets {@code
 * RATES_HTTP_BIND=0.0.0.0}, and the server then insists on a control token.
 *
 * @param name process name
 * @param role {@code trading} or {@code venue-sim}
 * @param colour {@code blue} or {@code green}
 * @param bindAddress HTTP bind address
 * @param port HTTP port
 * @param controlTokenEnv environment variable holding the control-endpoint token
 * @param journalDirectory where the journal is written
 * @param journalCapacityBytes journal segment size
 * @param quotingEnabledAtStart whether the simulated quoter starts enabled
 * @param instruments instruments quoted, each an instrument id and a venue id
 * @param maxMessagesPerSecond message-rate limit
 * @param retentionYears record retention
 * @param loopIdleStrategy idle strategy of the loop threads: {@code busy-spin}, {@code yield} or
 *     {@code backoff} (the default when the {@code runtime} section is absent)
 * @param loopCpu CPU the loop thread is pinned to on Linux, or -1 for none (the default)
 */
public record ProcessConfig(
    String name,
    String role,
    String colour,
    String bindAddress,
    int port,
    String controlTokenEnv,
    String journalDirectory,
    int journalCapacityBytes,
    boolean quotingEnabledAtStart,
    List<int[]> instruments,
    int maxMessagesPerSecond,
    int retentionYears,
    String loopIdleStrategy,
    int loopCpu) {

  /** Idle strategy when the configuration names none: what the process always used. */
  public static final String DEFAULT_LOOP_IDLE_STRATEGY = "backoff";

  public ProcessConfig {
    final List<int[]> copy = new ArrayList<>();
    for (final int[] i : instruments) {
      copy.add(i.clone());
    }
    instruments = List.copyOf(copy);
  }

  /** Instruments quoted, as fresh {instrumentId, venueId} pairs. */
  @Override
  public List<int[]> instruments() {
    final List<int[]> copy = new ArrayList<>();
    for (final int[] i : instruments) {
      copy.add(i.clone());
    }
    return copy;
  }

  /** The control token named by {@code controlTokenEnv}, or null when the variable is unset or empty. */
  public String controlToken(final Map<String, String> env) {
    final String token = controlTokenEnv == null ? null : env.get(controlTokenEnv);
    return token == null || token.isEmpty() ? null : token;
  }

  /**
   * Why this process may not serve its control endpoints, or null if it may: a bind address that is
   * not loopback needs a control token. Checked before anything is started, so the refusal reads
   * like any other configuration error.
   */
  public String controlBindProblem(final Map<String, String> env) {
    if (controlToken(env) != null || OpsHttpServer.isLoopback(bindAddress)) {
      return null;
    }
    return "http.bindAddress " + bindAddress + " is not loopback and " + controlTokenEnv
        + " is not set: the control endpoints would be open to the network";
  }

  /** Raised when a configuration does not validate; carries every violation. */
  public static final class InvalidConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final List<String> errors;

    InvalidConfigException(final Path file, final List<String> errors) {
      super(file + " is invalid: " + String.join("; ", errors));
      this.errors = List.copyOf(errors);
    }

    /** Every violation. */
    public List<String> errors() {
      return errors;
    }
  }

  /** Parses YAML (or JSON) safely: no custom tags, no object construction. */
  public static Object parse(final Reader reader) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
  }

  /** Reads a schema file. */
  @SuppressWarnings("unchecked")
  public static JsonSchema schema(final Path schemaFile) throws IOException {
    try (Reader r = Files.newBufferedReader(schemaFile, StandardCharsets.UTF_8)) {
      return new JsonSchema((Map<String, Object>) parse(r));
    }
  }

  /** Validates a configuration file without overrides; the list is empty when valid. */
  public static List<String> check(final Path configFile, final JsonSchema schema) throws IOException {
    try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
      return schema.validate(parse(r));
    }
  }

  /** Loads, applies environment overrides, validates and returns the typed view. */
  public static ProcessConfig load(final Path configFile, final JsonSchema schema, final Map<String, String> env)
      throws IOException {
    final Object doc;
    try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
      doc = parse(r);
    }
    if (doc instanceof Map<?, ?> m) {
      override(m, "http", "port", env.get("RATES_HTTP_PORT"), true);
      override(m, "http", "bindAddress", env.get("RATES_HTTP_BIND"), false);
      override(m, "journal", "directory", env.get("RATES_JOURNAL_DIR"), false);
    }
    final List<String> errors = schema.validate(doc);
    if (!errors.isEmpty()) {
      throw new InvalidConfigException(configFile, errors);
    }
    final Map<?, ?> root = (Map<?, ?>) doc;
    final Map<?, ?> process = (Map<?, ?>) root.get("process");
    final Map<?, ?> http = (Map<?, ?>) root.get("http");
    final Map<?, ?> journal = (Map<?, ?>) root.get("journal");
    final Map<?, ?> quoting = (Map<?, ?>) root.get("quoting");
    final Map<?, ?> limits = (Map<?, ?>) root.get("limits");
    final Map<?, ?> retention = (Map<?, ?>) root.get("retention");
    final Map<?, ?> runtime = root.get("runtime") instanceof Map<?, ?> r ? r : Map.of();
    final List<int[]> instruments = new ArrayList<>();
    for (final Object o : (List<?>) quoting.get("instruments")) {
      final Map<?, ?> i = (Map<?, ?>) o;
      instruments.add(new int[] {((Number) i.get("instrumentId")).intValue(), ((Number) i.get("venueId")).intValue()});
    }
    return new ProcessConfig(
        (String) process.get("name"),
        (String) process.get("role"),
        (String) process.get("colour"),
        (String) http.get("bindAddress"),
        ((Number) http.get("port")).intValue(),
        (String) http.get("controlTokenEnv"),
        (String) journal.get("directory"),
        ((Number) journal.get("capacityBytes")).intValue(),
        (Boolean) quoting.get("enabledAtStart"),
        instruments,
        ((Number) limits.get("maxMessagesPerSecond")).intValue(),
        ((Number) retention.get("years")).intValue(),
        runtime.get("loopIdleStrategy") instanceof String s ? s : DEFAULT_LOOP_IDLE_STRATEGY,
        runtime.get("loopCpu") instanceof Number n ? n.intValue() : -1);
  }

  @SuppressWarnings("unchecked")
  private static void override(final Map<?, ?> root, final String section, final String key, final String value,
      final boolean integer) {
    if (value == null || !(root.get(section) instanceof Map<?, ?> m)) {
      return;
    }
    final Map<Object, Object> target = (Map<Object, Object>) m;
    if (integer) {
      try {
        target.put(key, Integer.parseInt(value));
      } catch (final NumberFormatException e) {
        target.put(key, value);
      }
    } else {
      target.put(key, value);
    }
  }
}
