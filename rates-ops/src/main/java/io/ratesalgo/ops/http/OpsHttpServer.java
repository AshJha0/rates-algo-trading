package io.ratesalgo.ops.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLParameters;

/**
 * Health, readiness, metrics and control endpoints on the JDK's built-in HTTP server, one thread,
 * no framework (API_SPEC.md Section 17.12).
 *
 * <pre>
 * GET  /health/live      200 while the process runs
 * GET  /health/ready     200 when the readiness probe says so, 503 with the reason otherwise
 * GET  /metrics          Prometheus text format 0.0.4
 * GET  /control/status   JSON: quoting, kill switch, live quotes
 * POST /control/kill     engages the kill switch; 200 with the JSON status once the withdrawal is
 *                        confirmed, 503 with the reason when it is not
 * </pre>
 *
 * <p>Every {@code /control/*} request must carry the header {@code X-Control-Token} equal to the
 * configured token. With no token configured the control endpoints answer 403 to everyone, and
 * the server refuses to start on an address that is not loopback: an unset variable must not
 * leave the kill switch open to the network. Health and metrics need no token. The requester's
 * party id comes from {@code X-Requested-By} and is journaled with the KillSwitch message.
 *
 * <p>Given a {@link TlsConfig} the server speaks HTTPS: TLS 1.3 only unless the configuration lists
 * other protocols, with a client certificate demanded if the configuration says so. Plain HTTP is
 * for loopback only. An address that is not loopback needs both the token and TLS, or the server
 * refuses to start: the token must not cross a network in clear text.
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
    /**
     * Engages the kill switch: pull every quote, block new ones. Idempotent.
     *
     * @return null when the withdrawal is confirmed, otherwise why it is not
     */
    String kill(int requestedBy);

    /** Status as a JSON object. */
    String statusJson();
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final boolean https;

  /**
   * Starts the server on plain HTTP, which only a loopback address may serve.
   *
   * @param controlToken the control token; null or empty disables the control endpoints
   * @throws IllegalArgumentException if the address is not loopback
   */
  public OpsHttpServer(final String bindAddress, final int port, final MetricsRegistry metrics,
      final ReadinessProbe readiness, final Control control, final String controlToken) throws IOException {
    this(bindAddress, port, metrics, readiness, control, controlToken, null);
  }

  /**
   * Starts the server.
   *
   * @param controlToken the control token; null or empty disables the control endpoints
   * @param tls the server's key store and protocols for HTTPS; null for plain HTTP
   * @throws IllegalArgumentException if the address is not loopback and the token or TLS is missing
   * @throws IllegalStateException if the key store cannot be opened
   */
  public OpsHttpServer(final String bindAddress, final int port, final MetricsRegistry metrics,
      final ReadinessProbe readiness, final Control control, final String controlToken, final TlsConfig tls)
      throws IOException {
    final boolean hasToken = controlToken != null && !controlToken.isEmpty();
    if (!hasToken && !isLoopback(bindAddress)) {
      throw new IllegalArgumentException(
          "refusing to serve control endpoints on " + bindAddress + " without a control token; "
              + "set the token or bind a loopback address");
    }
    if (tls == null && !isLoopback(bindAddress)) {
      throw new IllegalArgumentException(
          "refusing to serve plain HTTP on " + bindAddress + "; configure TLS (http.tls) or bind a loopback address");
    }
    https = tls != null;
    server = https ? secure(new InetSocketAddress(bindAddress, port), tls)
        : HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
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
    server.createContext("/control/status", ex -> {
      if ("GET".equals(ex.getRequestMethod()) && !authorised(ex, controlToken)) {
        respond(ex, "GET", 403, "application/json", "{\"error\":\"control token required\"}");
        return;
      }
      respond(ex, "GET", 200, "application/json", control.statusJson());
    });
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
      String unconfirmed;
      try {
        unconfirmed = control.kill(requestedBy);
      } catch (final RuntimeException e) {
        unconfirmed = "kill failed: " + e;
      }
      if (unconfirmed == null) {
        respond(ex, "POST", 200, "application/json", control.statusJson());
      } else {
        // the caller must not read this as done: blue/green and the runbook key on the status code
        respond(ex, "POST", 503, "application/json",
            "{\"confirmed\":false,\"reason\":\"" + escape(unconfirmed) + "\"}");
      }
    });
    server.start();
  }

  private static HttpServer secure(final InetSocketAddress address, final TlsConfig tls) throws IOException {
    // built before the socket is bound: a store that cannot be opened must not leave a port taken
    final HttpsConfigurator configurator = new HttpsConfigurator(tls.context()) {
      @Override
      public void configure(final HttpsParameters params) {
        final SSLParameters p = getSSLContext().getDefaultSSLParameters();
        p.setProtocols(tls.protocols().toArray(new String[0]));
        p.setNeedClientAuth(tls.requireClientCertificate());
        params.setSSLParameters(p);
      }
    };
    final HttpsServer secure = HttpsServer.create(address, 16);
    secure.setHttpsConfigurator(configurator);
    return secure;
  }

  /** True when the endpoints are served over HTTPS. */
  public boolean isHttps() {
    return https;
  }

  /** The bound port (useful when started on port 0). */
  public int port() {
    return server.getAddress().getPort();
  }

  /** True for a loopback literal or name ({@code 127.0.0.1}, {@code ::1}, {@code localhost}). */
  public static boolean isLoopback(final String bindAddress) {
    try {
      return InetAddress.getByName(bindAddress).isLoopbackAddress();
    } catch (final UnknownHostException e) {
      return false;
    }
  }

  private static String escape(final String text) {
    final StringBuilder b = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        b.append('\\').append(c);
      } else if (c < 0x20) {
        b.append(' ');
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }

  private static boolean authorised(final HttpExchange ex, final String token) {
    if (token == null || token.isEmpty()) {
      return false;
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
