package io.ratesalgo.ops.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Health, readiness, metrics and control endpoints on the JDK's built-in HTTP server, one thread,
 * no framework (API_SPEC.md Section 17.12).
 *
 * <pre>
 * GET  /health/live      200 while the process runs
 * GET  /health/ready     200 when the readiness probe says so, 503 with the reason otherwise
 * GET  /metrics          Prometheus text format 0.0.4
 * GET  /control/status   JSON: quoting, kill switch, live quotes
 * POST /control/kill     engages the kill switch; JSON status after the quotes are pulled
 * </pre>
 *
 * <p>The control endpoints require the header {@code X-Control-Token} to equal the configured
 * token when one is configured, and should be bound to a management network only. The requester's
 * party id comes from {@code X-Requested-By} and is journaled with the KillSwitch message.
 */
public final class OpsHttpServer implements AutoCloseable {

  /** Says whether the process is ready, and if not, why. */
  @FunctionalInterface
  public interface ReadinessProbe {
    /** Null when ready, otherwise the reason. */
    String notReadyReason();
  }

  /** The process's control surface. */
  public interface Control {
    /** Engages the kill switch: pull every quote, block new ones. Idempotent. */
    void kill(int requestedBy);

    /** Status as a JSON object. */
    String statusJson();
  }

  private final HttpServer server;
  private final ExecutorService executor;

  /**
   * Starts the server.
   *
   * @param controlToken the required control token, or null for none
   */
  public OpsHttpServer(final String bindAddress, final int port, final MetricsRegistry metrics,
      final ReadinessProbe readiness, final Control control, final String controlToken) throws IOException {
    server = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
    executor = Executors.newSingleThreadExecutor(r -> {
      final Thread t = new Thread(r, "ops-http");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(executor);
    server.createContext("/health/live", ex -> respond(ex, "GET", 200, "text/plain", "live\n"));
    server.createContext("/health/ready", ex -> {
      final String reason = readiness.notReadyReason();
      respond(ex, "GET", reason == null ? 200 : 503, "text/plain", reason == null ? "ready\n" : reason + "\n");
    });
    server.createContext("/metrics", ex -> respond(ex, "GET", 200, "text/plain; version=0.0.4; charset=utf-8",
        metrics.render()));
    server.createContext("/control/status", ex -> respond(ex, "GET", 200, "application/json", control.statusJson()));
    server.createContext("/control/kill", ex -> {
      if (!"POST".equals(ex.getRequestMethod())) {
        respond(ex, "POST", 405, "text/plain", "");
        return;
      }
      if (!authorised(ex, controlToken)) {
        respond(ex, "POST", 403, "application/json", "{\"error\":\"control token required\"}");
        return;
      }
      int requestedBy = 0;
      final String who = ex.getRequestHeaders().getFirst("X-Requested-By");
      if (who != null) {
        try {
          requestedBy = Integer.parseInt(who.strip());
        } catch (final NumberFormatException e) {
          respond(ex, "POST", 400, "application/json", "{\"error\":\"X-Requested-By must be a party id\"}");
          return;
        }
      }
      control.kill(requestedBy);
      respond(ex, "POST", 200, "application/json", control.statusJson());
    });
    server.start();
  }

  /** The bound port (useful when started on port 0). */
  public int port() {
    return server.getAddress().getPort();
  }

  private static boolean authorised(final HttpExchange ex, final String token) {
    if (token == null || token.isEmpty()) {
      return true;
    }
    final String given = ex.getRequestHeaders().getFirst("X-Control-Token");
    return given != null && MessageDigest.isEqual(
        given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  private static void respond(final HttpExchange ex, final String method, final int status, final String type,
      final String body) throws IOException {
    try (ex) {
      if (!method.equals(ex.getRequestMethod())) {
        ex.getResponseHeaders().set("Allow", method);
        ex.sendResponseHeaders(405, -1);
        return;
      }
      final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", type);
      ex.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
