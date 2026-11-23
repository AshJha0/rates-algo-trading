package io.ratesalgo.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Secrets never print and compare in one way only; TLS settings fail before any connection. */
class SecurityTest {

  @TempDir Path dir;

  @Test
  void aSecretNeverPrintsAndMatchesOnlyItsOwnValue() {
    final Secret s = Secret.of("s3cret-value");
    assertEquals("Secret[****]", s.toString());
    assertFalse(("" + s + List.of(s) + Map.of("k", s)).contains("s3cret"));
    assertTrue(s.matches("s3cret-value"));
    assertTrue(s.matches("s3cret-value".getBytes(StandardCharsets.UTF_8)));
    assertTrue(s.matches(Secret.of("s3cret-value")));
    assertFalse(s.matches("s3cret-valuE"));
    assertFalse(s.matches("s3cret"));
    assertFalse(s.matches(""));
    assertFalse(s.matches((CharSequence) null));
    assertFalse(s.matches((byte[]) null));
    assertFalse(s.matches((Secret) null));
    assertArrayEquals("s3cret-value".toCharArray(), s.chars());
    assertArrayEquals("s3cret-value".getBytes(StandardCharsets.UTF_8), s.bytes());
    assertEquals(12, s.length());
    assertThrows(IllegalArgumentException.class, () -> Secret.of(""));
    assertThrows(IllegalArgumentException.class, () -> Secret.of(null));
  }

  @Test
  void aSecretComesFromTheEnvironmentOrAFileAndErrorsNameTheSourceOnly() throws IOException {
    assertTrue(Secret.fromEnv(Map.of("PW", "abc"), "PW").matches("abc"));
    final IllegalStateException unset = assertThrows(IllegalStateException.class, () -> Secret.fromEnv(Map.of(), "PW"));
    assertTrue(unset.getMessage().contains("PW"));
    assertThrows(IllegalStateException.class, () -> Secret.fromEnv(Map.of("PW", ""), "PW"));

    final Path file = dir.resolve("pw");
    Files.writeString(file, "from-file \r\n");
    assertTrue(Secret.fromFile(file).matches("from-file"));
    Files.writeString(file, "\n");
    final IllegalStateException empty = assertThrows(IllegalStateException.class, () -> Secret.fromFile(file));
    assertTrue(empty.getMessage().contains("empty"));
    assertThrows(IllegalStateException.class, () -> Secret.fromFile(dir.resolve("missing")));

    Files.writeString(file, "either");
    assertTrue(Secret.fromEnvOrFile(Map.of("PW", "abc"), "PW", null).matches("abc"));
    assertTrue(Secret.fromEnvOrFile(Map.of(), null, file.toString()).matches("either"));
    assertThrows(IllegalArgumentException.class, () -> Secret.fromEnvOrFile(Map.of(), null, null));
    assertThrows(IllegalArgumentException.class, () -> Secret.fromEnvOrFile(Map.of(), "PW", file.toString()));
  }

  @Test
  void tlsSettingsDefaultToTls13WithHostnameChecksAndBuildEngines() {
    final Secret password = Secret.of("store-pass-1");
    final KeytoolCertificates.Identity id = KeytoolCertificates.localhost(dir, "server", password, 30);
    final TlsConfig client = TlsConfig.builder().trustStore(id.trustStore(), password).build();
    assertEquals(List.of("TLSv1.3"), client.protocols());
    assertTrue(client.verifyHostname());
    assertEquals(TlsConfig.DEFAULT_HANDSHAKE_TIMEOUT_NANOS, client.handshakeTimeoutNanos());
    final SSLEngine c = client.clientEngine("localhost", 1);
    assertTrue(c.getUseClientMode());
    assertArrayEquals(new String[] {"TLSv1.3"}, c.getSSLParameters().getProtocols());
    assertEquals("HTTPS", c.getSSLParameters().getEndpointIdentificationAlgorithm());
    assertNotNull(client.context());

    final TlsConfig server =
        TlsConfig.builder().keyStore(id.keyStore(), password).trustStore(id.trustStore(), password)
            .requireClientCertificate(true).protocols(List.of("TLSv1.3", "TLSv1.2")).verifyHostname(false)
            .handshakeTimeoutNanos(1_000L).build();
    final SSLEngine s = server.serverEngine();
    assertFalse(s.getUseClientMode());
    assertTrue(s.getSSLParameters().getNeedClientAuth());
    assertEquals(2, s.getSSLParameters().getProtocols().length);
    assertFalse(server.verifyHostname());
    assertTrue(server.requireClientCertificate());
    assertEquals(id.keyStore(), server.keyStore());
    assertEquals(id.trustStore(), server.trustStore());
    assertTrue(server.keyStorePassword().matches(password) && server.trustStorePassword().matches(password));
    assertFalse(server.toString().contains("store-pass"));
    final String unchecked =
        server.clientEngine("localhost", 1).getSSLParameters().getEndpointIdentificationAlgorithm();
    assertTrue(unchecked == null || unchecked.isEmpty());
  }

  @Test
  void badTlsSettingsAreRefusedBeforeAnyConnection() {
    final Secret password = Secret.of("store-pass-2");
    final KeytoolCertificates.Identity id = KeytoolCertificates.expired(dir, "old", password);
    assertThrows(IllegalArgumentException.class, () -> TlsConfig.builder().build());
    assertThrows(IllegalArgumentException.class,
        () -> TlsConfig.builder().trustStore(id.trustStore(), password).protocols(List.of()).build());
    assertThrows(IllegalArgumentException.class,
        () -> TlsConfig.builder().trustStore(id.trustStore(), password).handshakeTimeoutNanos(0).build());
    assertThrows(IllegalArgumentException.class,
        () -> TlsConfig.builder().keyStore(id.keyStore(), password).requireClientCertificate(true).build());
    final TlsConfig wrongPassword = TlsConfig.builder().keyStore(id.keyStore(), Secret.of("not-the-password")).build();
    final IllegalStateException refused = assertThrows(IllegalStateException.class, wrongPassword::context);
    assertFalse(refused.getMessage().contains("not-the-password"));
    final TlsConfig missing = TlsConfig.builder().trustStore(dir.resolve("none.p12"), password).build();
    assertThrows(IllegalStateException.class, missing::context);
    final TlsConfig badProtocol =
        TlsConfig.builder().trustStore(id.trustStore(), password).protocols(List.of("TLSv9")).build();
    assertThrows(IllegalArgumentException.class, () -> badProtocol.clientEngine("localhost", 1));
  }
}
