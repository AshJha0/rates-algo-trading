package io.ratesalgo.ops.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.ops.TestConfigs;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trading process in-JVM: readiness, metrics, and the kill switch pulling every quote. */
class OpsProcessTest {

  private static final String[] ARGS = {"--config", "../deploy/config/trading-blue.yaml", "--schema",
      "../deploy/config/schema/process-config.schema.json"};

  @TempDir Path dir;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private HttpResponse<String> get(final int port, final String path, final String... headers) throws Exception {
    final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(final int port, final String path, final String... headers) throws Exception {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.noBody());
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Arguments for a copy of a committed configuration without its TLS sections: plain loopback. */
  private String[] plain(final String name) throws Exception {
    return new String[] {"--config", TestConfigs.plain(name, dir).toString(), "--schema", ARGS[3]};
  }

  private OpsProcessMain.Started start(final String[] args, final Map<String, String> env) throws Exception {
    return OpsProcessMain.start(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void killSwitchPullsEveryQuoteAndIsJournaled() throws Exception {
    final Map<String, String> env = Map.of("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", dir.toString(),
        "RATES_CONTROL_TOKEN", "s3cret");
    try (OpsProcessMain.Started s = start(plain("trading-blue"), env)) {
      final int port = s.server().port();
      assertEquals(200, get(port, "/health/live").statusCode());
      assertEquals(200, get(port, "/health/ready").statusCode());
      assertTrue(get(port, "/metrics").body().contains("rates_quotes_live 2"));
      assertEquals(403, get(port, "/control/status").statusCode(), "status needs the token too");
      assertTrue(get(port, "/control/status", "X-Control-Token", "s3cret").body().contains("\"liveQuotes\":2"));
      assertEquals(405, get(port, "/control/kill").statusCode());
      assertEquals(403, post(port, "/control/kill").statusCode());
      assertEquals(400, post(port, "/control/kill", "X-Control-Token", "s3cret", "X-Requested-By", "x").statusCode());
      final HttpResponse<String> killed =
          post(port, "/control/kill", "X-Control-Token", "s3cret", "X-Requested-By", "42");
      assertEquals(200, killed.statusCode());
      assertEquals("{\"quoting\":false,\"killSwitch\":true,\"liveQuotes\":0,\"instruments\":2}", killed.body());
      post(port, "/control/kill", "X-Control-Token", "s3cret");
      final String metrics = get(port, "/metrics").body();
      assertTrue(metrics.contains("rates_quotes_live 0"), metrics);
      assertTrue(metrics.contains("rates_kill_switch_engaged 1"), metrics);
      assertTrue(metrics.contains("rates_journal_frames_total 1"), metrics);
      assertEquals(405, post(port, "/metrics").statusCode());
    }
    final Path journal;
    try (Stream<Path> files = Files.list(dir)) {
      journal = files.filter(p -> p.toString().endsWith(".raj")).findFirst().orElseThrow();
    }
    final int[] requestedBy = {-1};
    new Replayer(new ManualClock(0)).replay(journal, (s, t, id, v, b, o, l) -> {
      if (id == KillSwitchDecoder.TEMPLATE_ID) {
        final KillSwitchDecoder d = new KillSwitchDecoder();
        d.wrapAndApplyHeader(b, o, new io.ratesalgo.messages.sbe.MessageHeaderDecoder());
        requestedBy[0] = d.requestedBy();
      }
    });
    assertEquals(42, requestedBy[0]);
  }

  @Test
  void invalidConfigurationRefusesToStart() throws Exception {
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, Files.readString(Path.of("../deploy/config/trading-blue.yaml")).replace("years: 5", "years: 1"));
    assertNull(start(new String[] {"--config", bad.toString(), "--schema", ARGS[3]}, Map.of()));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("configuration refused"));
    assertNull(start(new String[] {"--config", bad.toString()}, Map.of()));
    assertNull(start(new String[] {"--bogus", "x"}, Map.of()));
  }

  @Test
  void venueSimulatorRoleServesHealthOnly() throws Exception {
    try (OpsProcessMain.Started s = start(plain("venue-sim"), Map.of("RATES_HTTP_PORT", "0"))) {
      assertNull(s.quoter());
      final int port = s.server().port();
      assertEquals(200, get(port, "/health/ready").statusCode());
      assertEquals(403, post(port, "/control/kill").statusCode(), "no token configured: control is closed");
      assertTrue(get(port, "/metrics").body().contains("rates_process_info{name=\"venue-sim\""));
    }
    assertTrue(List.of(out.toString(StandardCharsets.UTF_8).split("\n")).get(0).contains("listening"));
    try (OpsProcessMain.Started s =
        start(plain("venue-sim"), Map.of("RATES_HTTP_PORT", "0", "RATES_CONTROL_TOKEN", "v"))) {
      assertEquals("{\"role\":\"venue-sim\"}",
          post(s.server().port(), "/control/kill", "X-Control-Token", "v").body());
    }
  }

  @Test
  void aNonLoopbackBindNeedsTheTokenAndTls() throws Exception {
    final Map<String, String> open = Map.of("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", dir.toString(),
        "RATES_HTTP_BIND", "0.0.0.0");
    assertNull(start(plain("trading-blue"), open));
    final String refused = err.toString(StandardCharsets.UTF_8);
    assertTrue(refused.contains("configuration refused") && refused.contains("RATES_CONTROL_TOKEN is not set"),
        refused);
    // a token alone used to be enough; the token would cross the network in clear text
    err.reset();
    final Map<String, String> closed = Map.of("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", dir.toString(),
        "RATES_HTTP_BIND", "0.0.0.0", "RATES_CONTROL_TOKEN", "s3cret");
    assertNull(start(plain("trading-blue"), closed));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("http.tls is not configured"), err::toString);
  }

  @Test
  void withHttpTlsTheProcessServesHttpsBeyondLoopback() throws Exception {
    final KeytoolCertificates.Identity id = TestConfigs.identity(dir, "process");
    final String[] args =
        {"--config", TestConfigs.secured("trading-blue", dir, id).toString(), "--schema", ARGS[3]};
    final Map<String, String> env = TestConfigs.passwords(id.password(), "RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR",
        dir.toString(), "RATES_HTTP_BIND", "0.0.0.0", "RATES_CONTROL_TOKEN", "s3cret");
    final HttpClient https = TestConfigs.client(id.trustStore(), id.password());
    try (OpsProcessMain.Started s = start(args, env)) {
      assertTrue(s.server().isHttps());
      final URI status = URI.create("https://127.0.0.1:" + s.server().port() + "/control/status");
      final HttpResponse<String> r = https.send(
          HttpRequest.newBuilder(status).header("X-Control-Token", "s3cret").GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertTrue(r.body().contains("\"liveQuotes\":2"), r.body());
    }
    // a password variable that is not set stops the start, and the refusal names it
    err.reset();
    final Map<String, String> missing = new java.util.HashMap<>(env);
    missing.remove("RATES_HTTP_KEYSTORE_PASSWORD");
    assertNull(start(args, missing));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("RATES_HTTP_KEYSTORE_PASSWORD, which is not set"),
        err::toString);
  }

  @Test
  void theCommittedConfigurationDoesNotStartWithoutItsStores() throws Exception {
    assertNull(start(ARGS, Map.of("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", dir.toString())));
    final String refused = err.toString(StandardCharsets.UTF_8);
    assertTrue(refused.contains("http.tls.keyStorePath") && refused.contains("cannot be read"), refused);
  }
}
