package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process main as the deployment rehearsal runs it: configuration checked first, the trading
 * role started on its own threads with the control endpoints, the kill switch over HTTP, and the
 * replay mode over the journals the run left (API_SPEC.md Section 18.1).
 *
 * <p>The shipped configurations name key stores under a deploy-time directory and passwords by
 * environment variable. The test does what the rehearsal does: it generates throwaway certificates
 * with keytool, points a copy of each configuration at them and passes the passwords in the
 * environment. So the trading role here runs FIX over TLS with logon credentials and resumed
 * sequence numbers, serves its endpoints over HTTPS, and is restarted on its own journals.
 */
class TradingProcessMainTest {

  private static final Path ROOT = root();
  private static final String CONFIG = ROOT.resolve("deploy/config/trading-blue.yaml").toString();
  private static final String VENUE = ROOT.resolve("deploy/config/venue-sim.yaml").toString();
  private static final String SCHEMA = ROOT.resolve("deploy/config/schema/process-config.schema.json").toString();

  private static final String STORE_PASSWORD = "test-store-" + System.nanoTime();
  private static final String FIX_PASSWORD = "test-fix-" + System.nanoTime();

  @TempDir Path dir;

  private String config;
  private String venue;
  private SSLContext trust;

  @BeforeEach
  void generateTestMaterial() throws Exception {
    final KeytoolCertificates.Identity id =
        KeytoolCertificates.localhost(dir.resolve("certs"), "server", Secret.of(STORE_PASSWORD), 30);
    config = pointAtTestMaterial(CONFIG, id);
    venue = pointAtTestMaterial(VENUE, id);
    trust = TlsConfig.builder().trustStore(id.trustStore(), id.password()).build().context();
  }

  private String pointAtTestMaterial(final String shipped, final KeytoolCertificates.Identity id) throws Exception {
    final Path copy = dir.resolve(Path.of(shipped).getFileName().toString());
    Files.writeString(copy, Files.readString(Path.of(shipped))
        .replace("/run/rates/certs/server-keystore.p12", id.keyStore().toString().replace(java.io.File.separatorChar, '/'))
        .replace("/run/rates/certs/truststore.p12", id.trustStore().toString().replace(java.io.File.separatorChar, '/')));
    return copy.toString();
  }

  /** The environment of a deployment: the passwords the configuration names, plus {@code more}. */
  private static Map<String, String> env(final String... more) {
    final Map<String, String> env = new HashMap<>();
    env.put("RATES_HTTP_KEYSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_TRUSTSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_PASSWORD", FIX_PASSWORD);
    for (int i = 0; i + 1 < more.length; i += 2) {
      env.put(more[i], more[i + 1]);
    }
    return env;
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("deploy/config"))) {
      p = p.getParent();
    }
    return p;
  }

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private TradingProcessMain.Started start(final String config, final Map<String, String> env, final String... extra)
      throws Exception {
    final String[] args = new String[4 + extra.length];
    args[0] = "--config";
    args[1] = config;
    args[2] = "--schema";
    args[3] = SCHEMA;
    System.arraycopy(extra, 0, args, 4, extra.length);
    return TradingProcessMain.start(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private HttpResponse<String> http(final String method, final int port, final String path,
      final String... headers) throws Exception {
    final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path));
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    b.method(method, HttpRequest.BodyPublishers.noBody());
    return HttpClient.newBuilder().sslContext(trust).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void refusesBadArgumentsAndAnInvalidConfiguration() throws Exception {
    assertNull(TradingProcessMain.start(new String[] {"--nope", "x"}, Map.of(), System.out, new PrintStream(err)));
    assertNull(TradingProcessMain.start(new String[0], Map.of(), System.out, new PrintStream(err)));
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, Files.readString(Path.of(config)).replace("years: 5", "years: 1"));
    assertNull(start(bad.toString(), env()));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("configuration refused"));
    // a password the configuration names and the environment does not hold: refused, by name only
    err.reset();
    final Map<String, String> noFixPassword = env("RATES_HTTP_PORT", "0");
    noFixPassword.remove("RATES_FIX_PASSWORD");
    assertNull(start(config, noFixPassword));
    final String refusal = err.toString(StandardCharsets.UTF_8);
    assertTrue(refusal.contains("RATES_FIX_PASSWORD"), refusal);
    assertTrue(!refusal.contains(FIX_PASSWORD) && !refusal.contains(STORE_PASSWORD), "no secret in the refusal");
    // the shipped file itself names stores that exist only in a deployment
    err.reset();
    assertNull(start(CONFIG, env("RATES_HTTP_PORT", "0")));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("configuration refused"));
  }

  @Test
  void theVenueRoleServesHealthOnly() throws Exception {
    try (TradingProcessMain.Started s = start(venue, env("RATES_HTTP_PORT", "0"))) {
      assertTrue(s.server().isHttps());
      assertNotNull(s);
      assertNull(s.process());
      assertEquals(200, http("GET", s.server().port(), "/health/ready").statusCode());
      // no token in the environment: the control endpoints are closed, health is not
      assertEquals(403, http("GET", s.server().port(), "/control/status").statusCode());
      assertEquals(403, http("POST", s.server().port(), "/control/kill").statusCode());
    }
    try (TradingProcessMain.Started s = start(venue, env("RATES_HTTP_PORT", "0", "RATES_CONTROL_TOKEN", "v"))) {
      assertEquals("{\"role\":\"venue-sim\"}",
          http("GET", s.server().port(), "/control/status", "X-Control-Token", "v").body());
      assertEquals(200, http("POST", s.server().port(), "/control/kill", "X-Control-Token", "v").statusCode());
    }
    // a bind beyond loopback without a token is refused like any invalid configuration
    assertNull(start(venue, env("RATES_HTTP_PORT", "0", "RATES_HTTP_BIND", "0.0.0.0")));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("is not loopback"), err.toString(StandardCharsets.UTF_8));
    try (TradingProcessMain.Started s = start(venue, env("RATES_HTTP_PORT", "0", "RATES_HTTP_BIND", "0.0.0.0",
        "RATES_CONTROL_TOKEN", "v"))) {
      assertEquals(200, http("GET", s.server().port(), "/health/live").statusCode());
    }
  }

  @Test
  void theTradingRoleQuotesTakesTheKillAndItsJournalsReplay() throws Exception {
    final Path journals = dir.resolve("journal");
    try (TradingProcessMain.Started s =
        start(config, env("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", journals.toString(), "RATES_CONTROL_TOKEN",
            "t0k3n"), "--seed", "4", "--time-scale", "60")) {
      assertNotNull(s);
      final int port = s.server().port();
      // the deployment configuration: FIX over TLS with credentials, sequence numbers resumed
      assertTrue(s.server().isHttps());
      assertTrue(s.process().options().fix().clientTls() != null && s.process().options().fix().acceptorTls() != null);
      assertEquals("RATESMM", s.process().options().fix().username());
      assertTrue(!s.process().options().fix().resetOnLogon(), "the deployment resumes sequence numbers");
      assertTrue(!s.process().recovered());
      assertEquals(200, http("GET", port, "/health/ready").statusCode());
      // the process runs in wall-clock time (time scale 60), so a loaded machine delays the first
      // quote; wait on the status itself with a generous ceiling (DR-INT-06)
      final long deadline = System.nanoTime() + 180_000_000_000L;
      String status = http("GET", port, "/control/status", "X-Control-Token", "t0k3n").body();
      while (!status.matches(".*\"quotesSent\":[1-9].*")) {
        assertTrue(System.nanoTime() < deadline, "no quote within 180 s; last status " + status);
        Thread.sleep(50);
        status = http("GET", port, "/control/status", "X-Control-Token", "t0k3n").body();
      }
      assertEquals(403, http("GET", port, "/control/status").statusCode(), "status needs the token");
      assertEquals(403, http("POST", port, "/control/kill").statusCode());
      final HttpResponse<String> kill =
          http("POST", port, "/control/kill", "X-Control-Token", "t0k3n", "X-Requested-By", "9001");
      assertEquals(200, kill.statusCode());
      assertTrue(kill.body().contains("\"killSwitch\":true") && kill.body().contains("\"liveQuotes\":0"), kill.body());
      assertTrue(kill.body().contains("\"withdrawn\":true"), kill.body());
      assertTrue(s.process().withdrawn() && s.process().killMessagePublished());
      assertEquals(503, http("GET", port, "/health/ready").statusCode(), "a halted process is not ready");
      final String metrics = http("GET", port, "/metrics").body();
      assertTrue(metrics.contains("\nrates_kill_switch_engaged 1\n") && metrics.contains("\nrates_quotes_live 0\n"),
          metrics);
      assertNull(s.process().failure());
    }
    final String firstRun = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    assertTrue(!firstRun.contains(FIX_PASSWORD) && !firstRun.contains(STORE_PASSWORD), "no secret in the output");
    assertNoSecretOnDisk(journals);

    // started again on the journals it left: rebuilt by replay, and the kill is part of that state
    try (TradingProcessMain.Started s =
        start(config, env("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", journals.toString(), "RATES_CONTROL_TOKEN",
            "t0k3n"), "--seed", "4", "--time-scale", "60")) {
      assertNotNull(s, err.toString(StandardCharsets.UTF_8));
      assertTrue(s.process().recovered());
      assertTrue(out.toString(StandardCharsets.UTF_8).contains("recovery: usd replayed"), out.toString(StandardCharsets.UTF_8));
      final String status = http("GET", s.server().port(), "/control/status", "X-Control-Token", "t0k3n").body();
      assertTrue(status.contains("\"recovered\":true"), status);
      assertTrue(status.contains("\"killSwitch\":true") && status.contains("\"quoting\":false"),
          "the kill switch pulled before the stop is still pulled: " + status);
      assertTrue(status.contains("\"halted\":false"), status);
      assertTrue(s.process().d2cSession().session().nextOutSeq() > 2, "sequence numbers resumed, not reset");
      assertNull(s.process().failure());
    }
    final ByteArrayOutputStream replayed = new ByteArrayOutputStream();
    assertEquals(0, TradingProcessMain.replay(journals, new PrintStream(replayed, true, StandardCharsets.UTF_8)));
    assertTrue(replayed.toString(StandardCharsets.UTF_8).contains("usd:") && replayed.toString(StandardCharsets.UTF_8)
        .contains("match"), replayed.toString(StandardCharsets.UTF_8));
    assertEquals(1, TradingProcessMain.replay(dir, new PrintStream(new ByteArrayOutputStream())), "no group journals");

    // a journal damaged in the middle: the process says so and stays down
    final Path segment = journals.resolve("usd").resolve("in.000001.raj");
    final byte[] bytes = Files.readAllBytes(segment);
    bytes[32 + 32 + 4] ^= 0x20;
    Files.write(segment, bytes);
    err.reset();
    assertNull(start(config, env("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", journals.toString(), "RATES_CONTROL_TOKEN",
        "t0k3n")));
    final String refused = err.toString(StandardCharsets.UTF_8);
    assertTrue(refused.contains("restart refused") && refused.contains("refusing to start"), refused);
  }

  /** Neither password is in any file the process wrote: journals, sequence stores, venue stores. */
  private static void assertNoSecretOnDisk(final Path journals) throws Exception {
    final byte[][] secrets = {
      FIX_PASSWORD.getBytes(StandardCharsets.US_ASCII), STORE_PASSWORD.getBytes(StandardCharsets.US_ASCII)
    };
    try (java.util.stream.Stream<Path> files = Files.walk(journals)) {
      for (final Path f : files.filter(Files::isRegularFile).toList()) {
        final byte[] bytes = Files.readAllBytes(f);
        for (final byte[] secret : secrets) {
          assertTrue(indexOf(bytes, secret) < 0, "a secret was written to " + f);
        }
      }
    }
  }

  private static int indexOf(final byte[] haystack, final byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
