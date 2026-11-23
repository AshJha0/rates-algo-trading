package io.ratesalgo.fix.net;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.KeytoolCertificates.Identity;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Throwaway certificates for the TLS tests, made once per test JVM with keytool in a temporary
 * directory outside the source tree. Nothing here is key material anyone keeps.
 */
public final class TestCerts {

  /** Password of every generated store; the tests also check that it never shows in a message. */
  public static final String STORE_PASSWORD = "store-pass-for-tests-only";

  private static final Secret PASSWORD = Secret.of(STORE_PASSWORD);
  private static final Map<String, Identity> IDENTITIES = new HashMap<>();
  private static Path dir;

  private TestCerts() {}

  /** The password of the generated stores. */
  public static Secret password() {
    return PASSWORD;
  }

  /** A valid localhost identity with the given name, generated on first use. */
  public static synchronized Identity identity(final String name) {
    return IDENTITIES.computeIfAbsent(name, n -> KeytoolCertificates.localhost(dir(), n, PASSWORD, 30));
  }

  /** A localhost identity whose certificate has expired. */
  public static synchronized Identity expired() {
    return IDENTITIES.computeIfAbsent("expired", n -> KeytoolCertificates.expired(dir(), n, PASSWORD));
  }

  /** Server settings for the identity {@code name}. */
  public static TlsConfig server(final String name) {
    return TlsConfig.builder().keyStore(identity(name).keyStore(), PASSWORD).build();
  }

  /** Client settings that trust the identity {@code trusted} and present no certificate. */
  public static TlsConfig client(final String trusted) {
    return TlsConfig.builder().trustStore(identity(trusted).trustStore(), PASSWORD).build();
  }

  private static Path dir() {
    if (dir == null) {
      try {
        dir = Files.createTempDirectory("rates-fix-tls-test");
        final Path made = dir;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteAll(made)));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return dir;
  }

  private static void deleteAll(final Path root) {
    try (var files = Files.list(root)) {
      files.forEach(f -> f.toFile().delete());
    } catch (final IOException ignored) {
      // a temporary directory; the operating system clears it in time
    }
    root.toFile().delete();
  }
}
