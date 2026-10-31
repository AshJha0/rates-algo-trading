package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process main as the deployment rehearsal runs it: configuration checked first, the trading
 * role started on its own threads with the control endpoints, the kill switch over HTTP, and the
 * replay mode over the journals the run left (API_SPEC.md Section 18.1).
 */
class TradingProcessMainTest {

  private static final Path ROOT = root();
  private static final String CONFIG = ROOT.resolve("deploy/config/trading-blue.yaml").toString();
  private static final String VENUE = ROOT.resolve("deploy/config/venue-sim.yaml").toString();
  private static final String SCHEMA = ROOT.resolve("deploy/config/schema/process-config.schema.json").toString();

  @TempDir Path dir;

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

  private static HttpResponse<String> http(final String method, final int port, final String path,
      final String... headers) throws Exception {
    final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    b.method(method, HttpRequest.BodyPublishers.noBody());
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void refusesBadArgumentsAndAnInvalidConfiguration() throws Exception {
    assertNull(TradingProcessMain.start(new String[] {"--nope", "x"}, Map.of(), System.out, new PrintStream(err)));
    assertNull(TradingProcessMain.start(new String[0], Map.of(), System.out, new PrintStream(err)));
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, Files.readString(Path.of(CONFIG)).replace("years: 5", "years: 1"));
    assertNull(start(bad.toString(), Map.of()));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("configuration refused"));
  }

  @Test
  void theVenueRoleServesHealthOnly() throws Exception {
    try (TradingProcessMain.Started s = start(VENUE, Map.of("RATES_HTTP_PORT", "0"))) {
      assertNotNull(s);
      assertNull(s.process());
      assertEquals(200, http("GET", s.server().port(), "/health/ready").statusCode());
      assertEquals("{\"role\":\"venue-sim\"}", http("GET", s.server().port(), "/control/status").body());
      assertEquals(200, http("POST", s.server().port(), "/control/kill").statusCode());
    }
  }

  @Test
  void theTradingRoleQuotesTakesTheKillAndItsJournalsReplay() throws Exception {
    final Path journals = dir.resolve("journal");
    try (TradingProcessMain.Started s =
        start(CONFIG, Map.of("RATES_HTTP_PORT", "0", "RATES_JOURNAL_DIR", journals.toString(), "RATES_CONTROL_TOKEN",
            "t0k3n"), "--seed", "4", "--time-scale", "60")) {
      assertNotNull(s);
      final int port = s.server().port();
      assertEquals(200, http("GET", port, "/health/ready").statusCode());
      final long deadline = System.nanoTime() + 30_000_000_000L;
      while (!http("GET", port, "/control/status").body().matches(".*\"quotesSent\":[1-9].*")) {
        assertTrue(System.nanoTime() < deadline, "no quote within 30 s");
        Thread.sleep(50);
      }
      assertEquals(403, http("POST", port, "/control/kill").statusCode());
      final HttpResponse<String> kill =
          http("POST", port, "/control/kill", "X-Control-Token", "t0k3n", "X-Requested-By", "9001");
      assertEquals(200, kill.statusCode());
      assertTrue(kill.body().contains("\"killSwitch\":true") && kill.body().contains("\"liveQuotes\":0"), kill.body());
      final String metrics = http("GET", port, "/metrics").body();
      assertTrue(metrics.contains("\nrates_kill_switch_engaged 1\n") && metrics.contains("\nrates_quotes_live 0\n"),
          metrics);
      assertNull(s.process().failure());
    }
    final ByteArrayOutputStream replayed = new ByteArrayOutputStream();
    assertEquals(0, TradingProcessMain.replay(journals, new PrintStream(replayed, true, StandardCharsets.UTF_8)));
    assertTrue(replayed.toString(StandardCharsets.UTF_8).contains("usd:") && replayed.toString(StandardCharsets.UTF_8)
        .contains("match"), replayed.toString(StandardCharsets.UTF_8));
    assertEquals(1, TradingProcessMain.replay(dir, new PrintStream(new ByteArrayOutputStream())), "no group journals");
  }
}
