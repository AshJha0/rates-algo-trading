package io.ratesalgo.ops.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.ops.config.JsonSchema;
import io.ratesalgo.ops.config.ProcessConfig;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control endpoint's access rules (review finding L12) and the kill's answer (L2): no token
 * means no control and no bind beyond loopback; a kill whose withdrawal was not confirmed answers
 * 503 with the reason, never 200.
 */
class ControlEndpointTest {

  private final HttpClient http = HttpClient.newHttpClient();

  /** A control surface whose kill answers what the test sets. */
  private static final class Scripted implements OpsHttpServer.Control {
    String unconfirmed;
    boolean fail;
    int kills;

    @Override
    public String kill(final int requestedBy) {
      kills++;
      if (fail) {
        throw new IllegalStateException("boom");
      }
      return unconfirmed;
    }

    @Override
    public String statusJson() {
      return "{\"liveQuotes\":0}";
    }
  }

  private HttpResponse<String> send(final String method, final int port, final String path, final String... headers)
      throws Exception {
    final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void anUnconfirmedKillAnswers503WithTheReason() throws Exception {
    final Scripted control = new Scripted();
    try (OpsHttpServer server = new OpsHttpServer("127.0.0.1", 0, new MetricsRegistry(), () -> null, control, "tok")) {
      final int port = server.port();
      assertEquals(200, send("POST", port, "/control/kill", "X-Control-Token", "tok").statusCode());

      control.unconfirmed = "D2C: 2 quotes \"live\" after 2000 ms";
      final HttpResponse<String> r = send("POST", port, "/control/kill", "X-Control-Token", "tok");
      assertEquals(503, r.statusCode());
      assertEquals("{\"confirmed\":false,\"reason\":\"D2C: 2 quotes \\\"live\\\" after 2000 ms\"}", r.body());
      assertFalse(r.body().contains("liveQuotes"), "an unconfirmed kill must not look like a status");

      control.fail = true;
      final HttpResponse<String> failed = send("POST", port, "/control/kill", "X-Control-Token", "tok");
      assertEquals(503, failed.statusCode());
      assertTrue(failed.body().contains("kill failed") && failed.body().contains("boom"), failed.body());
      assertEquals(3, control.kills);
    }
  }

  @Test
  void everyControlPathNeedsTheTokenAndHealthNeedsNone() throws Exception {
    final Scripted control = new Scripted();
    try (OpsHttpServer server = new OpsHttpServer("127.0.0.1", 0, new MetricsRegistry(), () -> null, control, "tok")) {
      final int port = server.port();
      assertEquals(403, send("GET", port, "/control/status").statusCode());
      assertEquals(403, send("GET", port, "/control/status", "X-Control-Token", "nope").statusCode());
      assertEquals(200, send("GET", port, "/control/status", "X-Control-Token", "tok").statusCode());
      assertEquals(403, send("POST", port, "/control/kill").statusCode());
      assertEquals(405, send("POST", port, "/control/status").statusCode());
      assertEquals(200, send("GET", port, "/health/live").statusCode());
      assertEquals(200, send("GET", port, "/health/ready").statusCode());
      assertEquals(200, send("GET", port, "/metrics").statusCode());
      assertEquals(0, control.kills);
    }
  }

  @Test
  void withoutATokenControlIsClosedAndOnlyLoopbackBinds() throws Exception {
    final Scripted control = new Scripted();
    for (final String none : new String[] {null, ""}) {
      try (OpsHttpServer server =
          new OpsHttpServer("127.0.0.1", 0, new MetricsRegistry(), () -> null, control, none)) {
        final int port = server.port();
        // an unset token used to authorise everyone
        assertEquals(403, send("POST", port, "/control/kill").statusCode());
        assertEquals(403, send("POST", port, "/control/kill", "X-Control-Token", "").statusCode());
        assertEquals(403, send("GET", port, "/control/status").statusCode());
        assertEquals(200, send("GET", port, "/health/live").statusCode());
      }
      final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
          () -> new OpsHttpServer("0.0.0.0", 0, new MetricsRegistry(), () -> null, control, none));
      assertTrue(refused.getMessage().contains("without a control token"), refused.getMessage());
    }
    assertEquals(0, control.kills);
    assertTrue(OpsHttpServer.isLoopback("127.0.0.1"));
    assertTrue(OpsHttpServer.isLoopback("::1"));
    assertTrue(OpsHttpServer.isLoopback("localhost"));
    assertFalse(OpsHttpServer.isLoopback("0.0.0.0"));
    assertFalse(OpsHttpServer.isLoopback("no such host.invalid"));
  }

  @Test
  void theRuntimeSectionSetsTheLoopThreadsAndIsValidated(@TempDir final Path dir) throws Exception {
    final JsonSchema schema = ProcessConfig.schema(Path.of("../deploy/config/schema/process-config.schema.json"));
    final String base = Files.readString(Path.of("../deploy/config/trading-blue.yaml"));
    final Path tuned = dir.resolve("tuned.yaml");
    Files.writeString(tuned, base + "runtime:\n  loopIdleStrategy: busy-spin\n  loopCpu: 3\n");
    final ProcessConfig c = ProcessConfig.load(tuned, schema, Map.of());
    assertEquals("busy-spin", c.loopIdleStrategy());
    assertEquals(3, c.loopCpu());
    assertEquals(ProcessConfig.RuntimeSettings.DEFAULT.withLoopThread("busy-spin", 3), c.runtime(), "the rest stays default");
    assertEquals(ProcessConfig.RuntimeSettings.DEFAULT, ProcessConfig.load(Path.of("../deploy/config/trading-blue.yaml"),
        schema, Map.of()).runtime(), "no runtime section: what the process always ran");

    // the low-latency profile, with the host's CPUs and one key overridden
    final Path low = dir.resolve("low.yaml");
    Files.writeString(low, base + "runtime:\n  profile: lowLatency\n  loopCpu: 2\n  gatewayCpu: 3\n  journalHash: fnv1a\n");
    final ProcessConfig.RuntimeSettings r = ProcessConfig.load(low, schema, Map.of()).runtime();
    assertEquals("lowLatency", r.profile());
    assertEquals("busy-spin", r.loopIdleStrategy());
    assertEquals(2, r.loopCpu());
    assertEquals("busy-spin", r.gatewayIdleStrategy());
    assertEquals(3, r.gatewayCpu());
    assertEquals("ring", r.journalMode());
    assertEquals("fnv1a", r.journalHash(), "a key beside the profile wins");
    assertEquals("busy-spin", r.journalIdleStrategy());
    assertTrue(r.lockJournalPages() && r.conflateCurveInputs());
    assertThrows(IllegalArgumentException.class, () -> ProcessConfig.RuntimeSettings.profile("fast"));
    final ProcessConfig shipped =
        ProcessConfig.load(Path.of("../deploy/config/trading-lowlatency.yaml"), schema, Map.of());
    assertEquals("lowLatency", shipped.runtime().profile());
    assertTrue(shipped.loopCpu() >= 0 && shipped.runtime().gatewayCpu() >= 0, "the shipped profile pins");
    final Path badProfile = dir.resolve("badprofile.yaml");
    Files.writeString(badProfile, base + "runtime:\n  profile: fast\n  journalMode: mmap\n");
    assertEquals(2, assertThrows(ProcessConfig.InvalidConfigException.class,
        () -> ProcessConfig.load(badProfile, schema, Map.of())).errors().size());
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, base + "runtime:\n  loopIdleStrategy: sleep\n  loopCpu: -2\n");
    final ProcessConfig.InvalidConfigException e =
        assertThrows(ProcessConfig.InvalidConfigException.class, () -> ProcessConfig.load(bad, schema, Map.of()));
    assertEquals(2, e.errors().size(), e.errors()::toString);
  }
}
