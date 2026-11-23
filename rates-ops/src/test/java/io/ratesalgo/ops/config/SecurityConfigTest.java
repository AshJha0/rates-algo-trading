package io.ratesalgo.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.ops.TestConfigs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code http.tls} and {@code fix} sections: defaults when absent, passwords by reference
 * only, what transport {@code tls} needs, and what is checked before a process starts.
 */
class SecurityConfigTest {

  @TempDir static Path dir;

  private static KeytoolCertificates.Identity id;
  private static JsonSchema schema;
  private static String password;

  @BeforeAll
  static void generate() throws IOException {
    id = TestConfigs.identity(dir, "config");
    schema = ProcessConfig.schema(TestConfigs.SCHEMA);
    password = TestConfigs.text(id.password());
  }

  private static ProcessConfig load(final Consumer<Map<String, Object>> edit, final Map<String, String> env)
      throws IOException {
    return ProcessConfig.load(TestConfigs.copy("trading-blue", dir, edit), schema, env);
  }

  private static List<String> errors(final Consumer<Map<String, Object>> edit) throws IOException {
    return ProcessConfig.check(TestConfigs.copy("trading-blue", dir, edit), schema);
  }

  private static Map<String, Object> fix(final Map<String, Object> doc, final String section) {
    final Map<String, Object> fix = TestConfigs.section(doc, "fix");
    return section == null ? fix : TestConfigs.section(fix, section);
  }

  private static Map<String, Object> httpTls(final Map<String, Object> doc) {
    return TestConfigs.section(TestConfigs.section(doc, "http"), "tls");
  }

  private static void assertOneError(final String expected, final Consumer<Map<String, Object>> edit)
      throws IOException {
    final List<String> errors = errors(edit);
    assertEquals(1, errors.size(), errors::toString);
    assertTrue(errors.get(0).contains(expected), errors::toString);
  }

  @Test
  void withoutTheSectionsTheProcessRunsTheSimulatorPreset() throws Exception {
    final ProcessConfig c = load(TestConfigs::strip, Map.of());
    assertTrue(c.fixResetOnLogon());
    assertFalse(c.fixTls());
    assertNull(c.fixClientTls(Map.of()));
    assertNull(c.fixAcceptorTls(Map.of()));
    assertNull(c.fixUsername());
    assertNull(c.fixPassword(Map.of()));
    assertEquals(5, c.fixReconnectMaxAttempts());
    assertEquals(100_000_000L, c.fixReconnectInitialBackoffNanos());
    assertEquals(2_000_000_000L, c.fixReconnectMaxBackoffNanos());
    assertNull(c.httpTls(Map.of()));
    assertNull(c.securityProblem(Map.of()));
    // an empty fix section is the same preset
    assertEquals(c.fix(), load(doc -> {
      TestConfigs.strip(doc);
      doc.put("fix", new LinkedHashMap<String, Object>());
    }, Map.of()).fix());
  }

  @Test
  void theCommittedConfigurationResumesOverTlsAndNamesItsSecrets() throws Exception {
    final ProcessConfig c = ProcessConfig.load(TestConfigs.CONFIG_DIR.resolve("trading-blue.yaml"), schema, Map.of());
    assertFalse(c.fixResetOnLogon());
    assertTrue(c.fixTls());
    assertEquals("RATESMM", c.fixUsername());
    assertEquals(5, c.fixReconnectMaxAttempts());
    // on a machine that was not given the stores the process must not start
    final String problem = c.securityProblem(Map.of());
    assertTrue(problem.contains("http.tls.keyStorePath") && problem.contains("cannot be read"), problem);
  }

  @Test
  void storesAndPasswordsByEnvironmentVariable() throws Exception {
    final Map<String, String> env = TestConfigs.passwords(id.password());
    final ProcessConfig c = ProcessConfig.load(TestConfigs.secured("trading-blue", dir, id), schema, env);
    assertNull(c.securityProblem(env));
    final TlsConfig http = c.httpTls(env);
    assertEquals(id.keyStore(), http.keyStore());
    assertNotNull(http.context());
    final TlsConfig client = c.fixClientTls(env);
    assertEquals(id.trustStore(), client.trustStore());
    assertNull(client.keyStore());
    assertTrue(client.verifyHostname());
    assertEquals(5_000_000_000L, client.handshakeTimeoutNanos());
    assertEquals(TlsConfig.DEFAULT_PROTOCOLS, client.protocols());
    assertNotNull(client.context());
    final TlsConfig acceptor = c.fixAcceptorTls(env);
    assertEquals(id.keyStore(), acceptor.keyStore());
    assertNull(acceptor.trustStore());
    assertFalse(acceptor.requireClientCertificate());
    assertNotNull(acceptor.context());
    assertTrue(c.fixPassword(env).matches(password));

    // nothing the record prints is a secret: it holds names and paths
    final String printed = c.toString() + http + client + acceptor + c.fixPassword(env);
    assertFalse(printed.contains(password), "a password was printed");
    assertTrue(printed.contains("RATES_FIX_PASSWORD") && printed.contains(id.keyStore().toString()), printed);

    // each variable is needed, and the refusal names it without a value
    for (final String name : List.of("RATES_HTTP_KEYSTORE_PASSWORD", "RATES_FIX_TRUSTSTORE_PASSWORD",
        "RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD", "RATES_FIX_PASSWORD")) {
      final Map<String, String> missing = TestConfigs.passwords(id.password());
      missing.remove(name);
      final String problem = c.securityProblem(missing);
      assertTrue(problem.contains(name + ", which is not set"), problem);
      assertFalse(problem.contains(password), problem);
      missing.put(name, "");
      assertTrue(c.securityProblem(missing).contains(name), "an empty variable is an unset one");
    }
    assertThrows(IllegalStateException.class, () -> c.fixPassword(Map.of()));
  }

  @Test
  void storesAndPasswordsByFileWithMutualTls() throws Exception {
    final Path secret = Files.createTempFile(dir, "password-", ".txt");
    Files.writeString(secret, password + "\n");
    final Path gone = dir.resolve("no-such-secret.txt");
    final Consumer<Map<String, Object>> byFile = doc -> {
      final Map<String, Object> http = httpTls(doc);
      http.remove("keyStorePasswordEnv");
      http.put("keyStorePath", id.keyStore().toString());
      http.put("keyStorePasswordFile", secret.toString());
      final Map<String, Object> tls = fix(doc, "tls");
      tls.clear();
      tls.put("trustStorePath", id.trustStore().toString());
      tls.put("trustStorePasswordFile", secret.toString());
      tls.put("keyStorePath", id.keyStore().toString());
      tls.put("keyStorePasswordFile", secret.toString());
      tls.put("verifyHostname", false);
      tls.put("handshakeTimeoutMillis", 250);
      final Map<String, Object> acceptor = fix(doc, "acceptor");
      acceptor.clear();
      acceptor.put("keyStorePath", id.keyStore().toString());
      acceptor.put("keyStorePasswordFile", secret.toString());
      acceptor.put("requireClientCertificate", true);
      acceptor.put("trustStorePath", id.trustStore().toString());
      acceptor.put("trustStorePasswordFile", secret.toString());
      final Map<String, Object> credentials = fix(doc, "credentials");
      credentials.remove("passwordEnv");
      credentials.put("passwordFile", secret.toString());
      final Map<String, Object> reconnect = fix(doc, "reconnect");
      reconnect.put("maxAttempts", 9);
      reconnect.put("initialBackoffMillis", 7);
      reconnect.put("maxBackoffMillis", 70);
    };
    final ProcessConfig c = load(byFile, Map.of());
    assertNull(c.securityProblem(Map.of()));
    assertNotNull(c.httpTls(Map.of()).context());
    final TlsConfig client = c.fixClientTls(Map.of());
    assertEquals(id.keyStore(), client.keyStore());
    assertFalse(client.verifyHostname());
    assertEquals(250_000_000L, client.handshakeTimeoutNanos());
    assertNotNull(client.context());
    final TlsConfig acceptor = c.fixAcceptorTls(Map.of());
    assertTrue(acceptor.requireClientCertificate());
    assertEquals(id.trustStore(), acceptor.trustStore());
    assertNotNull(acceptor.context());
    assertTrue(c.fixPassword(Map.of()).matches(password));
    assertEquals(9, c.fixReconnectMaxAttempts());
    assertEquals(7_000_000L, c.fixReconnectInitialBackoffNanos());
    assertEquals(70_000_000L, c.fixReconnectMaxBackoffNanos());
    assertFalse(c.toString().contains(password));

    // a secret file or a store that is not there is named in the refusal
    final String noSecret = load(byFile.andThen(doc -> fix(doc, "credentials").put("passwordFile", gone.toString())),
        Map.of()).securityProblem(Map.of());
    assertEquals("fix.credentials.passwordFile " + gone + " cannot be read", noSecret);
    final String noStorePassword =
        load(byFile.andThen(doc -> fix(doc, "tls").put("keyStorePasswordFile", gone.toString())), Map.of())
            .securityProblem(Map.of());
    assertEquals("fix.tls.keyStorePasswordFile " + gone + " cannot be read", noStorePassword);
    for (final String[] where : new String[][] {{"tls", "trustStorePath"}, {"tls", "keyStorePath"},
        {"acceptor", "keyStorePath"}, {"acceptor", "trustStorePath"}}) {
      final String problem = load(byFile.andThen(doc -> fix(doc, where[0]).put(where[1], gone.toString())), Map.of())
          .securityProblem(Map.of());
      assertEquals("fix." + where[0] + "." + where[1] + " " + gone + " cannot be read", problem);
    }
    // a directory is not a store
    final String directory = load(byFile.andThen(doc -> httpTls(doc).put("keyStorePath", dir.toString())), Map.of())
        .securityProblem(Map.of());
    assertEquals("http.tls.keyStorePath " + dir + " cannot be read", directory);
  }

  @Test
  void aPasswordIsNamedByExactlyOneOfEnvAndFileAndNeverWritten() throws Exception {
    assertOneError("$.http.tls: give exactly one of keyStorePasswordEnv and keyStorePasswordFile",
        doc -> httpTls(doc).put("keyStorePasswordFile", "/run/secrets/p"));
    assertOneError("$.http.tls: give exactly one of keyStorePasswordEnv and keyStorePasswordFile",
        doc -> httpTls(doc).remove("keyStorePasswordEnv"));
    assertOneError("$.fix.tls: give exactly one of trustStorePasswordEnv and trustStorePasswordFile",
        doc -> fix(doc, "tls").remove("trustStorePasswordEnv"));
    assertOneError("$.fix.acceptor: give exactly one of keyStorePasswordEnv and keyStorePasswordFile",
        doc -> fix(doc, "acceptor").put("keyStorePasswordFile", "/run/secrets/p"));
    assertOneError("$.fix.credentials: give exactly one of passwordEnv and passwordFile",
        doc -> fix(doc, "credentials").put("passwordFile", "/run/secrets/p"));
    assertOneError("$.fix.credentials: give exactly one of passwordEnv and passwordFile",
        doc -> fix(doc, "credentials").remove("passwordEnv"));
    assertOneError("$.fix.tls: a keyStore password is named but keyStorePath is missing",
        doc -> fix(doc, "tls").put("keyStorePasswordEnv", "RATES_X"));
    assertOneError("$.fix.acceptor: a trustStore password is named but trustStorePath is missing",
        doc -> fix(doc, "acceptor").put("trustStorePasswordFile", "/run/secrets/p"));

    // an inline password has no key to live under
    assertOneError("$.fix.credentials: property 'password' is not allowed",
        doc -> fix(doc, "credentials").put("password", "written-down"));
    assertOneError("$.http.tls: property 'keyStorePassword' is not allowed",
        doc -> httpTls(doc).put("keyStorePassword", "written-down"));
    assertOneError("$.fix.acceptor: property 'keyStorePassword' is not allowed",
        doc -> fix(doc, "acceptor").put("keyStorePassword", "written-down"));
    assertOneError("$.fix.tls: property 'trustStorePassword' is not allowed",
        doc -> fix(doc, "tls").put("trustStorePassword", "written-down"));
    assertOneError("does not match", doc -> fix(doc, "credentials").put("passwordEnv", "lower case"));
    assertOneError("$.fix.transport", doc -> fix(doc, null).put("transport", "ssl3"));

    final ProcessConfig.InvalidConfigException refused = assertThrows(ProcessConfig.InvalidConfigException.class,
        () -> load(doc -> fix(doc, "credentials").remove("passwordEnv"), Map.of()));
    assertTrue(refused.getMessage().contains("exactly one of passwordEnv and passwordFile"), refused.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new ProcessConfig.SecretRef(null, null));
    assertThrows(IllegalArgumentException.class, () -> new ProcessConfig.SecretRef("RATES_X", "/run/secrets/p"));
  }

  @Test
  void transportTlsNeedsItsParts() throws Exception {
    assertOneError("$.fix: transport tls needs tls.trustStorePath", doc -> fix(doc, null).remove("tls"));
    assertOneError("$.fix: transport tls needs acceptor.keyStorePath", doc -> fix(doc, null).remove("acceptor"));
    assertOneError("requireClientCertificate needs acceptor.trustStorePath and tls.keyStorePath",
        doc -> fix(doc, "acceptor").put("requireClientCertificate", true));
    assertOneError("requireClientCertificate needs acceptor.trustStorePath and tls.keyStorePath", doc -> {
      fix(doc, "acceptor").put("requireClientCertificate", true);
      fix(doc, "acceptor").put("trustStorePath", "/run/rates/certs/truststore.p12");
      fix(doc, "acceptor").put("trustStorePasswordEnv", "RATES_X");
    });
    assertOneError("$.fix.reconnect: initialBackoffMillis is above maxBackoffMillis",
        doc -> fix(doc, "reconnect").put("initialBackoffMillis", 5000));

    // plain loopback with credentials and resume is a whole configuration
    final ProcessConfig plain = load(doc -> {
      httpTls(doc).clear();
      TestConfigs.section(doc, "http").remove("tls");
      fix(doc, null).put("transport", "plain-loopback");
      fix(doc, null).remove("tls");
      fix(doc, null).remove("acceptor");
    }, Map.of());
    assertFalse(plain.fixTls());
    assertFalse(plain.fixResetOnLogon());
    assertNull(plain.fixClientTls(Map.of()));
    assertNull(plain.fixAcceptorTls(Map.of()));
    assertEquals("RATESMM", plain.fixUsername());
    assertTrue(plain.securityProblem(Map.of()).contains("RATES_FIX_PASSWORD, which is not set"));
    assertNull(plain.securityProblem(Map.of("RATES_FIX_PASSWORD", "x")));

    // the same rules hold for a record built by hand, past the loader
    final ProcessConfig.StoreRef store =
        new ProcessConfig.StoreRef(id.keyStore().toString(), new ProcessConfig.SecretRef("RATES_X", null));
    final Map<String, String> env = Map.of("RATES_X", password);
    assertTrue(withFix(plain, fixSettings(null, null, store, null, false)).securityProblem(env)
        .contains("fix.tls.trustStorePath is missing"));
    assertTrue(withFix(plain, fixSettings(store, null, null, null, false)).securityProblem(env)
        .contains("fix.acceptor.keyStorePath is missing"));
    assertTrue(withFix(plain, fixSettings(store, null, store, null, true)).securityProblem(env)
        .contains("requireClientCertificate needs"));
    assertTrue(withFix(plain, fixSettings(store, null, store, store, true)).securityProblem(env)
        .contains("requireClientCertificate needs"));
    assertNull(withFix(plain, fixSettings(store, store, store, store, true)).securityProblem(env));
  }

  @Test
  void aBindBeyondLoopbackNeedsTheTokenAndHttpTls() throws Exception {
    final Map<String, String> open = Map.of("RATES_HTTP_BIND", "0.0.0.0");
    final Map<String, String> token = Map.of("RATES_CONTROL_TOKEN", "t");
    final ProcessConfig plain = load(TestConfigs::strip, open);
    assertTrue(plain.controlBindProblem(Map.of()).contains("RATES_CONTROL_TOKEN is not set"));
    assertTrue(plain.controlBindProblem(token).contains("http.tls is not configured"));
    assertTrue(plain.securityProblem(token).contains("http.tls is not configured"));

    final ProcessConfig secured = ProcessConfig.load(TestConfigs.secured("trading-blue", dir, id), schema, open);
    assertTrue(secured.controlBindProblem(Map.of()).contains("RATES_CONTROL_TOKEN is not set"));
    assertNull(secured.controlBindProblem(token));
    assertNull(secured.securityProblem(TestConfigs.passwords(id.password())));
  }

  private static ProcessConfig.FixSettings fixSettings(final ProcessConfig.StoreRef clientTrust,
      final ProcessConfig.StoreRef clientKey, final ProcessConfig.StoreRef acceptorKey,
      final ProcessConfig.StoreRef acceptorTrust, final boolean requireClientCertificate) {
    return new ProcessConfig.FixSettings(false, true, clientTrust, clientKey, true, 5000, acceptorKey, acceptorTrust,
        requireClientCertificate, null, null, 5, 100, 2000);
  }

  private static ProcessConfig withFix(final ProcessConfig c, final ProcessConfig.FixSettings fix) {
    return new ProcessConfig(c.name(), c.role(), c.colour(), c.bindAddress(), c.port(), c.controlTokenEnv(),
        c.journalDirectory(), c.journalCapacityBytes(), c.quotingEnabledAtStart(), c.instruments(),
        c.maxMessagesPerSecond(), c.retentionYears(), c.loopIdleStrategy(), c.loopCpu(), null, fix);
  }
}
