package io.ratesalgo.ops.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.ops.TestConfigs;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The endpoints over HTTPS, on real loopback sockets with certificates generated for the run: who
 * gets in, who is refused, and where plain HTTP is still allowed.
 */
class HttpsEndpointTest {

  @TempDir static Path dir;

  private static KeytoolCertificates.Identity server;
  private static KeytoolCertificates.Identity caller;

  @BeforeAll
  static void generate() {
    server = TestConfigs.identity(dir, "ops");
    caller = TestConfigs.identity(dir, "caller");
  }

  /** A control surface that counts its kills. */
  private static final class Counting implements OpsHttpServer.Control {
    int kills;

    @Override
    public String kill(final int requestedBy) {
      kills++;
      return null;
    }

    @Override
    public String statusJson() {
      return "{\"liveQuotes\":0}";
    }
  }

  private static TlsConfig serverTls() {
    return TlsConfig.builder().keyStore(server.keyStore(), server.password()).build();
  }

  private static OpsHttpServer start(final String bind, final OpsHttpServer.Control control, final TlsConfig tls)
      throws IOException {
    return new OpsHttpServer(bind, 0, new MetricsRegistry(), () -> null, control, "tok", tls);
  }

  private static HttpResponse<String> send(final HttpClient client, final String method, final String scheme,
      final int port, final String path, final String... headers) throws Exception {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return client.send(b.method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthMetricsStatusAndKillRoundTripOverHttps() throws Exception {
    final Counting control = new Counting();
    final HttpClient client = TestConfigs.client(server.trustStore(), server.password());
    try (OpsHttpServer s = start("127.0.0.1", control, serverTls())) {
      assertTrue(s.isHttps());
      final int port = s.port();
      assertEquals("live\n", send(client, "GET", "https", port, "/health/live").body());
      assertEquals(200, send(client, "GET", "https", port, "/health/ready").statusCode());
      assertEquals(200, send(client, "GET", "https", port, "/metrics").statusCode());
      // the token rule is the same over TLS
      assertEquals(403, send(client, "GET", "https", port, "/control/status").statusCode());
      assertEquals(403, send(client, "POST", "https", port, "/control/kill", "X-Control-Token", "no").statusCode());
      assertEquals("{\"liveQuotes\":0}",
          send(client, "GET", "https", port, "/control/status", "X-Control-Token", "tok").body());
      assertEquals(200, send(client, "POST", "https", port, "/control/kill", "X-Control-Token", "tok").statusCode());
      assertEquals(1, control.kills);
    }
  }

  @Test
  void callersThatDoNotTrustTheCertificateOrSpeakOldTlsOrPlainHttpAreRefused() throws Exception {
    final Counting control = new Counting();
    try (OpsHttpServer s = start("127.0.0.1", control, serverTls())) {
      final int port = s.port();
      // the JDK's default trust store has never heard of a certificate generated a moment ago
      final HttpClient stranger = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      assertThrows(IOException.class, () -> send(stranger, "GET", "https", port, "/health/live"));
      // nor does a caller that believes some other certificate
      final HttpClient other = TestConfigs.client(caller.trustStore(), caller.password());
      assertThrows(IOException.class, () -> send(other, "GET", "https", port, "/health/live"));

      final HttpClient old = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
          .sslContext(TlsConfig.builder().trustStore(server.trustStore(), server.password()).build().context())
          .sslParameters(new SSLParameters(null, new String[] {"TLSv1.2"}))
          .build();
      assertThrows(IOException.class, () -> send(old, "GET", "https", port, "/health/live"));

      final HttpClient plain = HttpClient.newHttpClient();
      assertThrows(IOException.class,
          () -> send(plain, "POST", "http", port, "/control/kill", "X-Control-Token", "tok"));
      assertEquals(0, control.kills);

      // the one server thread survived all of that
      final HttpClient client = TestConfigs.client(server.trustStore(), server.password());
      assertEquals(200, send(client, "GET", "https", port, "/health/live").statusCode());
    }
  }

  @Test
  void aConfigurationThatListsTls12AdmitsATls12Caller() throws Exception {
    final TlsConfig both = TlsConfig.builder().keyStore(server.keyStore(), server.password())
        .protocols(List.of("TLSv1.3", "TLSv1.2")).build();
    final HttpClient old = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .sslContext(TlsConfig.builder().trustStore(server.trustStore(), server.password()).build().context())
        .sslParameters(new SSLParameters(null, new String[] {"TLSv1.2"}))
        .build();
    try (OpsHttpServer s = start("127.0.0.1", new Counting(), both)) {
      assertEquals(200, send(old, "GET", "https", s.port(), "/health/live").statusCode());
    }
  }

  @Test
  void aRequiredClientCertificateIsDemanded() throws Exception {
    final TlsConfig mutual = TlsConfig.builder().keyStore(server.keyStore(), server.password())
        .trustStore(caller.trustStore(), caller.password()).requireClientCertificate(true).build();
    final HttpClient known = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .sslContext(TlsConfig.builder().trustStore(server.trustStore(), server.password())
            .keyStore(caller.keyStore(), caller.password()).build().context())
        .build();
    final HttpClient anonymous = TestConfigs.client(server.trustStore(), server.password());
    try (OpsHttpServer s = start("127.0.0.1", new Counting(), mutual)) {
      final int port = s.port();
      assertEquals(200, send(known, "GET", "https", port, "/health/live").statusCode());
      assertThrows(IOException.class, () -> send(anonymous, "GET", "https", port, "/health/live"));
    }
  }

  @Test
  void beyondLoopbackNeedsBothTheTokenAndTls() throws Exception {
    final Counting control = new Counting();
    // a token alone used to be enough
    final IllegalArgumentException plain = assertThrows(IllegalArgumentException.class,
        () -> new OpsHttpServer("0.0.0.0", 0, new MetricsRegistry(), () -> null, control, "tok"));
    assertTrue(plain.getMessage().contains("plain HTTP") && plain.getMessage().contains("http.tls"),
        plain.getMessage());
    assertThrows(IllegalArgumentException.class, () -> start("0.0.0.0", control, null));
    // TLS alone is not enough either
    final IllegalArgumentException open = assertThrows(IllegalArgumentException.class,
        () -> new OpsHttpServer("0.0.0.0", 0, new MetricsRegistry(), () -> null, control, null, serverTls()));
    assertTrue(open.getMessage().contains("without a control token"), open.getMessage());

    final HttpClient client = TestConfigs.client(server.trustStore(), server.password());
    try (OpsHttpServer s = start("0.0.0.0", control, serverTls())) {
      assertEquals(200, send(client, "GET", "https", s.port(), "/health/live").statusCode());
    }
  }

  @Test
  void loopbackStillServesPlainHttp() throws Exception {
    try (OpsHttpServer s = start("127.0.0.1", new Counting(), null)) {
      assertFalse(s.isHttps());
      final HttpClient plain = HttpClient.newHttpClient();
      assertEquals(200, send(plain, "GET", "http", s.port(), "/health/live").statusCode());
      assertEquals(200, send(plain, "GET", "http", s.port(), "/control/status", "X-Control-Token", "tok").statusCode());
    }
  }

  @Test
  void aKeyStoreThatCannotBeOpenedStopsTheServerBeforeItBinds() {
    final TlsConfig wrongPassword =
        TlsConfig.builder().keyStore(server.keyStore(), TestConfigs.randomPassword()).build();
    final IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> start("127.0.0.1", new Counting(), wrongPassword));
    assertTrue(e.getMessage().contains(server.keyStore().toString()), e.getMessage());
  }
}
