package io.ratesalgo.ops.config;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
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
 * RATES_HTTP_BIND=0.0.0.0}, and the server then insists on a control token and on {@code http.tls}.
 *
 * <p>No password is ever part of a configuration file. A key store password, a trust store
 * password and the FIX logon password are each named by an environment variable ({@code ...Env})
 * or a file ({@code ...File}), exactly one of the two, and read when the process starts ({@link
 * #securityProblem}). This record therefore holds names and paths only, and so does its {@code
 * toString}.
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
 * @param loopIdleStrategy idle strategy of the loop threads: {@code busy-spin}, {@code yield},
 *     {@code backoff} or {@code park}; the profile's when the key is absent ({@code backoff} by default)
 * @param loopCpu CPU the loop thread is pinned to on Linux, or -1 for none (the default)
 * @param httpKeyStore key store of the HTTPS endpoints ({@code http.tls}), or null for plain HTTP
 * @param fix the {@code fix} section; {@link FixSettings#SIMULATOR} when it is absent
 * @param runtime the whole {@code runtime} section with its profile applied; {@link
 *     RuntimeSettings#DEFAULT} when it is absent
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
    int loopCpu,
    StoreRef httpKeyStore,
    FixSettings fix,
    RuntimeSettings runtime) {

  /** Idle strategy when the configuration names none: what the process always used. */
  public static final String DEFAULT_LOOP_IDLE_STRATEGY = "backoff";

  /** A configuration without the {@code runtime} section's later keys: their defaults. */
  public ProcessConfig(
      final String name, final String role, final String colour, final String bindAddress, final int port,
      final String controlTokenEnv, final String journalDirectory, final int journalCapacityBytes,
      final boolean quotingEnabledAtStart, final List<int[]> instruments, final int maxMessagesPerSecond,
      final int retentionYears, final String loopIdleStrategy, final int loopCpu, final StoreRef httpKeyStore,
      final FixSettings fix) {
    this(name, role, colour, bindAddress, port, controlTokenEnv, journalDirectory, journalCapacityBytes,
        quotingEnabledAtStart, instruments, maxMessagesPerSecond, retentionYears, loopIdleStrategy, loopCpu,
        httpKeyStore, fix, RuntimeSettings.DEFAULT.withLoopThread(loopIdleStrategy, loopCpu));
  }

  /**
   * The {@code runtime} section: the trading process's threads and journals (API_SPEC.md Section
   * 17.11, DECISIONS.md DR-R6-L02). A profile sets every key at once; a key given beside it
   * overrides the profile's value.
   *
   * <ul>
   *   <li>{@code default}: what the process always ran. Loop on backoff, session and feed threads
   *       parking 200 us when idle, nothing pinned, direct journals with FNV-1a, no conflation.
   *   <li>{@code lowLatency}: loop, session, feed and journal threads busy-spin, ring journals with
   *       the WORD64 hash and locked segments, curve inputs conflated. CPUs are still the host's to
   *       give ({@code loopCpu}, {@code gatewayCpu}); a spinning thread belongs on a reserved core.
   * </ul>
   *
   * @param profile {@code default} or {@code lowLatency}
   * @param loopIdleStrategy the loop threads' idle strategy
   * @param loopCpu the first loop's CPU, or -1
   * @param gatewayIdleStrategy the D2C, CLOB and feed threads' idle strategy
   * @param gatewayCpu the D2C thread's CPU (CLOB +1, feed +2), or -1
   * @param journalMode {@code direct} or {@code ring}
   * @param journalHash chain hash of new journals: {@code fnv1a} or {@code word64}
   * @param journalIdleStrategy the journal threads' idle strategy in ring mode
   * @param lockJournalPages mlock the journal segments (Linux, within RLIMIT_MEMLOCK)
   * @param conflateCurveInputs conflate curve inputs on the loop
   */
  public record RuntimeSettings(
      String profile,
      String loopIdleStrategy,
      int loopCpu,
      String gatewayIdleStrategy,
      int gatewayCpu,
      String journalMode,
      String journalHash,
      String journalIdleStrategy,
      boolean lockJournalPages,
      boolean conflateCurveInputs) {

    /** The {@code default} profile. */
    public static final RuntimeSettings DEFAULT =
        new RuntimeSettings("default", DEFAULT_LOOP_IDLE_STRATEGY, -1, "park", -1, "direct", "fnv1a", "backoff", false,
            false);

    /** The {@code lowLatency} profile. */
    public static final RuntimeSettings LOW_LATENCY =
        new RuntimeSettings("lowLatency", "busy-spin", -1, "busy-spin", -1, "ring", "word64", "busy-spin", true, true);

    /** The profile called {@code name}. */
    public static RuntimeSettings profile(final String name) {
      return switch (name) {
        case "default" -> DEFAULT;
        case "lowLatency" -> LOW_LATENCY;
        default -> throw new IllegalArgumentException("unknown runtime profile '" + name + "'; use default or lowLatency");
      };
    }

    /** These settings with another loop idle strategy and CPU. */
    public RuntimeSettings withLoopThread(final String idle, final int cpu) {
      return new RuntimeSettings(profile, idle, cpu, gatewayIdleStrategy, gatewayCpu, journalMode, journalHash,
          journalIdleStrategy, lockJournalPages, conflateCurveInputs);
    }

    /** The section's profile with each key the section gives applied over it. */
    static RuntimeSettings of(final Map<?, ?> section) {
      final RuntimeSettings p = profile(section.get("profile") instanceof String s ? s : "default");
      return new RuntimeSettings(
          p.profile(),
          text(section, "loopIdleStrategy", p.loopIdleStrategy()),
          number(section, "loopCpu", p.loopCpu()),
          text(section, "gatewayIdleStrategy", p.gatewayIdleStrategy()),
          number(section, "gatewayCpu", p.gatewayCpu()),
          text(section, "journalMode", p.journalMode()),
          text(section, "journalHash", p.journalHash()),
          text(section, "journalIdleStrategy", p.journalIdleStrategy()),
          section.get("lockJournalPages") instanceof Boolean b ? b : p.lockJournalPages(),
          section.get("conflateCurveInputs") instanceof Boolean b ? b : p.conflateCurveInputs());
    }

    private static String text(final Map<?, ?> section, final String key, final String fallback) {
      return section.get(key) instanceof String s ? s : fallback;
    }

    private static int number(final Map<?, ?> section, final String key, final int fallback) {
      return section.get(key) instanceof Number n ? n.intValue() : fallback;
    }
  }

  /**
   * Where a password comes from: an environment variable or a file, exactly one of the two.
   *
   * @param envName the environment variable, or null
   * @param file the file, or null
   */
  public record SecretRef(String envName, String file) {

    public SecretRef {
      if ((envName == null) == (file == null)) {
        throw new IllegalArgumentException("a password is named by an environment variable or a file, exactly one");
      }
    }

    Secret read(final Map<String, String> env) {
      return Secret.fromEnvOrFile(env, envName, file);
    }

    /** Why the password cannot be read, or null. Names the variable or the file, never a value. */
    String problem(final String key, final Map<String, String> env) {
      if (envName != null) {
        final String value = env.get(envName);
        return value == null || value.isEmpty() ? key + "Env names " + envName + ", which is not set" : null;
      }
      return readable(file) ? null : key + "File " + file + " cannot be read";
    }
  }

  /**
   * A PKCS#12 store and where its password comes from.
   *
   * @param path the store file
   * @param password its password
   */
  public record StoreRef(String path, SecretRef password) {

    String problem(final String key, final Map<String, String> env) {
      if (!readable(path)) {
        return key + "Path " + path + " cannot be read";
      }
      return password.problem(key + "Password", env);
    }
  }

  /**
   * The {@code fix} section: how the FIX sessions between the trading process and its in-process
   * venue simulators run.
   *
   * @param resetOnLogon true to restart sequence numbers at every logon, false to resume them
   * @param tls true for transport {@code tls}, false for {@code plain-loopback}
   * @param clientTrustStore initiator: whom it believes ({@code fix.tls.trustStore*})
   * @param clientKeyStore initiator: its client certificate, or null
   * @param verifyHostname initiator: whether the certificate must name the host dialled
   * @param handshakeTimeoutMillis initiator: time allowed for a handshake
   * @param acceptorKeyStore acceptors: their certificate ({@code fix.acceptor.keyStore*})
   * @param acceptorTrustStore acceptors: the client certificates they accept, or null
   * @param requireClientCertificate acceptors: refuse a client with no accepted certificate
   * @param username Logon Username(553), or null for no credentials
   * @param password where Logon Password(554) comes from, or null
   * @param reconnectMaxAttempts reconnection attempts before the session is given up
   * @param reconnectInitialBackoffMillis wait before the first reconnection attempt
   * @param reconnectMaxBackoffMillis cap of the doubling wait
   */
  public record FixSettings(
      boolean resetOnLogon,
      boolean tls,
      StoreRef clientTrustStore,
      StoreRef clientKeyStore,
      boolean verifyHostname,
      long handshakeTimeoutMillis,
      StoreRef acceptorKeyStore,
      StoreRef acceptorTrustStore,
      boolean requireClientCertificate,
      String username,
      SecretRef password,
      int reconnectMaxAttempts,
      long reconnectInitialBackoffMillis,
      long reconnectMaxBackoffMillis) {

    /** What a configuration with no {@code fix} section runs: the simulator preset. */
    public static final FixSettings SIMULATOR =
        new FixSettings(true, false, null, null, true, 5000, null, null, false, null, null, 5, 100, 2000);

    /** What transport {@code tls} still lacks, or null. The loader refuses the same cases. */
    String incomplete() {
      if (!tls) {
        return null;
      }
      if (clientTrustStore == null) {
        return "fix.transport is tls but fix.tls.trustStorePath is missing";
      }
      if (acceptorKeyStore == null) {
        return "fix.transport is tls but fix.acceptor.keyStorePath is missing";
      }
      if (requireClientCertificate && (acceptorTrustStore == null || clientKeyStore == null)) {
        return "fix.acceptor.requireClientCertificate needs fix.acceptor.trustStorePath and fix.tls.keyStorePath";
      }
      return null;
    }
  }

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
   * not loopback needs a control token and {@code http.tls}. Checked before anything is started, so
   * the refusal reads like any other configuration error.
   */
  public String controlBindProblem(final Map<String, String> env) {
    if (OpsHttpServer.isLoopback(bindAddress)) {
      return null;
    }
    if (controlToken(env) == null) {
      return "http.bindAddress " + bindAddress + " is not loopback and " + controlTokenEnv
          + " is not set: the control endpoints would be open to the network";
    }
    return httpKeyStore == null ? noHttpTls() : null;
  }

  private String noHttpTls() {
    return "http.bindAddress " + bindAddress + " is not loopback and http.tls is not configured: "
        + "the control token would cross the network in clear text";
  }

  /**
   * Why this process may not start with its TLS and credential settings, or null if it may. One
   * message for the first of: a bind address that is not loopback without {@code http.tls}; TLS
   * transport with a part missing; a store or password file that cannot be read; a password
   * variable that is not set. The message names the key and the variable or file, never a value.
   */
  public String securityProblem(final Map<String, String> env) {
    if (httpKeyStore == null && !OpsHttpServer.isLoopback(bindAddress)) {
      return noHttpTls();
    }
    String problem = fix.incomplete();
    if (problem == null && httpKeyStore != null) {
      problem = httpKeyStore.problem("http.tls.keyStore", env);
    }
    if (problem == null && fix.tls()) {
      problem = firstProblem(env, "fix.tls.trustStore", fix.clientTrustStore(), "fix.tls.keyStore",
          fix.clientKeyStore(), "fix.acceptor.keyStore", fix.acceptorKeyStore(), "fix.acceptor.trustStore",
          fix.acceptorTrustStore());
    }
    if (problem == null && fix.password() != null) {
      problem = fix.password().problem("fix.credentials.password", env);
    }
    return problem;
  }

  private static String firstProblem(final Map<String, String> env, final Object... keysAndStores) {
    for (int i = 0; i < keysAndStores.length; i += 2) {
      if (keysAndStores[i + 1] instanceof StoreRef store) {
        final String problem = store.problem((String) keysAndStores[i], env);
        if (problem != null) {
          return problem;
        }
      }
    }
    return null;
  }

  private static boolean readable(final String file) {
    final Path path = Path.of(file);
    return Files.isRegularFile(path) && Files.isReadable(path);
  }

  /** TLS for the HTTP endpoints, or null when {@code http.tls} is absent (plain HTTP, loopback only). */
  public TlsConfig httpTls(final Map<String, String> env) {
    if (httpKeyStore == null) {
      return null;
    }
    return TlsConfig.builder().keyStore(Path.of(httpKeyStore.path()), httpKeyStore.password().read(env)).build();
  }

  /** True to restart FIX sequence numbers at every logon; false to resume them after a restart. */
  public boolean fixResetOnLogon() {
    return fix.resetOnLogon();
  }

  /** True when the FIX transport is TLS; false for plain loopback. */
  public boolean fixTls() {
    return fix.tls();
  }

  /** TLS for the FIX initiators, or null when the transport is plain loopback. */
  public TlsConfig fixClientTls(final Map<String, String> env) {
    if (!fix.tls()) {
      return null;
    }
    final TlsConfig.Builder b = TlsConfig.builder()
        .trustStore(Path.of(fix.clientTrustStore().path()), fix.clientTrustStore().password().read(env))
        .verifyHostname(fix.verifyHostname())
        .handshakeTimeoutNanos(fix.handshakeTimeoutMillis() * 1_000_000L);
    if (fix.clientKeyStore() != null) {
      b.keyStore(Path.of(fix.clientKeyStore().path()), fix.clientKeyStore().password().read(env));
    }
    return b.build();
  }

  /** TLS for the in-process venue simulators' acceptors, or null when the transport is plain loopback. */
  public TlsConfig fixAcceptorTls(final Map<String, String> env) {
    if (!fix.tls()) {
      return null;
    }
    final TlsConfig.Builder b = TlsConfig.builder()
        .keyStore(Path.of(fix.acceptorKeyStore().path()), fix.acceptorKeyStore().password().read(env))
        .requireClientCertificate(fix.requireClientCertificate());
    if (fix.acceptorTrustStore() != null) {
      b.trustStore(Path.of(fix.acceptorTrustStore().path()), fix.acceptorTrustStore().password().read(env));
    }
    return b.build();
  }

  /** FIX Logon Username(553), or null when no credentials are configured. */
  public String fixUsername() {
    return fix.username();
  }

  /** FIX Logon Password(554), or null when no credentials are configured. */
  public Secret fixPassword(final Map<String, String> env) {
    return fix.password() == null ? null : fix.password().read(env);
  }

  /** Reconnection attempts before a FIX session is given up. */
  public int fixReconnectMaxAttempts() {
    return fix.reconnectMaxAttempts();
  }

  /** Wait before the first FIX reconnection attempt. */
  public long fixReconnectInitialBackoffNanos() {
    return fix.reconnectInitialBackoffMillis() * 1_000_000L;
  }

  /** Cap of the doubling wait between FIX reconnection attempts. */
  public long fixReconnectMaxBackoffNanos() {
    return fix.reconnectMaxBackoffMillis() * 1_000_000L;
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
      return validate(parse(r), schema);
    }
  }

  /** The schema's violations, or, for a document of the right shape, the rules a schema cannot state. */
  private static List<String> validate(final Object doc, final JsonSchema schema) {
    final List<String> errors = schema.validate(doc);
    return errors.isEmpty() ? structuralErrors((Map<?, ?>) doc) : errors;
  }

  /**
   * The rules the schema subset cannot express: each password by exactly one of {@code ...Env} and
   * {@code ...File}, and the parts transport {@code tls} needs. An inline password never gets this
   * far: the schema allows no such key.
   */
  private static List<String> structuralErrors(final Map<?, ?> root) {
    final List<String> errors = new ArrayList<>();
    checkStore(section(section(root, "http"), "tls"), "$.http.tls", "keyStore", errors);
    final Map<?, ?> fix = section(root, "fix");
    final Map<?, ?> tls = section(fix, "tls");
    final Map<?, ?> acceptor = section(fix, "acceptor");
    checkStore(tls, "$.fix.tls", "trustStore", errors);
    checkStore(tls, "$.fix.tls", "keyStore", errors);
    checkStore(acceptor, "$.fix.acceptor", "keyStore", errors);
    checkStore(acceptor, "$.fix.acceptor", "trustStore", errors);
    if ("tls".equals(fix.get("transport"))) {
      if (!tls.containsKey("trustStorePath")) {
        errors.add("$.fix: transport tls needs tls.trustStorePath");
      }
      if (!acceptor.containsKey("keyStorePath")) {
        errors.add("$.fix: transport tls needs acceptor.keyStorePath");
      }
    }
    if (Boolean.TRUE.equals(acceptor.get("requireClientCertificate"))
        && !(acceptor.containsKey("trustStorePath") && tls.containsKey("keyStorePath"))) {
      errors.add("$.fix.acceptor: requireClientCertificate needs acceptor.trustStorePath and tls.keyStorePath");
    }
    if (fix.get("credentials") instanceof Map<?, ?> c && c.containsKey("passwordEnv") == c.containsKey("passwordFile")) {
      errors.add("$.fix.credentials: give exactly one of passwordEnv and passwordFile");
    }
    final Map<?, ?> reconnect = section(fix, "reconnect");
    if (number(reconnect, "initialBackoffMillis", 100) > number(reconnect, "maxBackoffMillis", 2000)) {
      errors.add("$.fix.reconnect: initialBackoffMillis is above maxBackoffMillis");
    }
    return errors;
  }

  private static void checkStore(final Map<?, ?> section, final String where, final String kind,
      final List<String> errors) {
    final boolean byEnv = section.containsKey(kind + "PasswordEnv");
    final boolean byFile = section.containsKey(kind + "PasswordFile");
    if (section.containsKey(kind + "Path")) {
      if (byEnv == byFile) {
        errors.add(where + ": give exactly one of " + kind + "PasswordEnv and " + kind + "PasswordFile");
      }
    } else if (byEnv || byFile) {
      errors.add(where + ": a " + kind + " password is named but " + kind + "Path is missing");
    }
  }

  private static Map<?, ?> section(final Map<?, ?> parent, final String key) {
    return parent.get(key) instanceof Map<?, ?> m ? m : Map.of();
  }

  private static long number(final Map<?, ?> section, final String key, final long fallback) {
    return section.get(key) instanceof Number n ? n.longValue() : fallback;
  }

  private static boolean flag(final Map<?, ?> section, final String key, final boolean fallback) {
    return section.get(key) instanceof Boolean b ? b : fallback;
  }

  private static SecretRef secretRef(final Map<?, ?> section, final String key) {
    return new SecretRef((String) section.get(key + "Env"), (String) section.get(key + "File"));
  }

  private static StoreRef store(final Map<?, ?> section, final String kind) {
    return section.get(kind + "Path") instanceof String path
        ? new StoreRef(path, secretRef(section, kind + "Password"))
        : null;
  }

  private static FixSettings fixSettings(final Map<?, ?> fix) {
    final FixSettings d = FixSettings.SIMULATOR;
    final Map<?, ?> tls = section(fix, "tls");
    final Map<?, ?> acceptor = section(fix, "acceptor");
    final Map<?, ?> credentials = section(fix, "credentials");
    final Map<?, ?> reconnect = section(fix, "reconnect");
    return new FixSettings(
        flag(fix, "resetOnLogon", d.resetOnLogon()),
        "tls".equals(fix.get("transport")),
        store(tls, "trustStore"),
        store(tls, "keyStore"),
        flag(tls, "verifyHostname", d.verifyHostname()),
        number(tls, "handshakeTimeoutMillis", d.handshakeTimeoutMillis()),
        store(acceptor, "keyStore"),
        store(acceptor, "trustStore"),
        flag(acceptor, "requireClientCertificate", false),
        (String) credentials.get("username"),
        credentials.isEmpty() ? null : secretRef(credentials, "password"),
        (int) number(reconnect, "maxAttempts", d.reconnectMaxAttempts()),
        number(reconnect, "initialBackoffMillis", d.reconnectInitialBackoffMillis()),
        number(reconnect, "maxBackoffMillis", d.reconnectMaxBackoffMillis()));
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
    final List<String> errors = validate(doc, schema);
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
    final RuntimeSettings runtime = RuntimeSettings.of(root.get("runtime") instanceof Map<?, ?> r ? r : Map.of());
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
        runtime.loopIdleStrategy(),
        runtime.loopCpu(),
        store(section(http, "tls"), "keyStore"),
        fixSettings(section(root, "fix")),
        runtime);
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
