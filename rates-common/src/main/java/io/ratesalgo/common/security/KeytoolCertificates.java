package io.ratesalgo.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Makes throwaway self-signed certificates with the JDK's {@code keytool}, for tests and for the
 * deployment rehearsal. Nothing made here is for production: a real deployment is given key stores
 * issued by its own certificate authority (runbook, certificate rotation). No private key made
 * here is committed; each test run generates its own.
 *
 * <p>The password reaches keytool through an environment variable ({@code -storepass:env}), not
 * the command line, so it does not show in a process listing.
 */
public final class KeytoolCertificates {

  private static final String PASS_ENV = "RATES_KEYTOOL_STOREPASS";

  private KeytoolCertificates() {}

  /**
   * One generated identity.
   *
   * @param keyStore PKCS#12 store with the private key and certificate
   * @param trustStore PKCS#12 store with the certificate alone, for the other end to trust
   * @param password password of both stores
   */
  public record Identity(Path keyStore, Path trustStore, Secret password) {}

  /**
   * A certificate for {@code localhost} and {@code 127.0.0.1}, valid from yesterday for {@code
   * validityDays}.
   */
  public static Identity localhost(final Path dir, final String name, final Secret password, final int validityDays) {
    return generate(dir, name, password, "-1d", validityDays);
  }

  /** A certificate for {@code localhost} that expired days ago. */
  public static Identity expired(final Path dir, final String name, final Secret password) {
    return generate(dir, name, password, "-10d", 2);
  }

  private static Identity generate(
      final Path dir, final String name, final Secret password, final String startDate, final int validityDays) {
    try {
      Files.createDirectories(dir);
      final Path keyStore = dir.resolve(name + "-keystore.p12");
      final Path trustStore = dir.resolve(name + "-truststore.p12");
      final Path certificate = dir.resolve(name + ".cer");
      Files.deleteIfExists(keyStore);
      Files.deleteIfExists(trustStore);
      Files.deleteIfExists(certificate);
      keytool(password, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname",
          "CN=localhost, OU=test material, O=rates-algo-trading", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
          "-startdate", startDate, "-validity", Integer.toString(validityDays), "-keystore", keyStore.toString(),
          "-storetype", "PKCS12", "-storepass:env", PASS_ENV);
      keytool(password, "-exportcert", "-alias", name, "-keystore", keyStore.toString(), "-storetype", "PKCS12",
          "-storepass:env", PASS_ENV, "-file", certificate.toString());
      keytool(password, "-importcert", "-noprompt", "-alias", name, "-file", certificate.toString(), "-keystore",
          trustStore.toString(), "-storetype", "PKCS12", "-storepass:env", PASS_ENV);
      return new Identity(keyStore, trustStore, password);
    } catch (final IOException e) {
      throw new IllegalStateException("cannot generate test certificate " + name + " in " + dir, e);
    }
  }

  private static void keytool(final Secret password, final String... args) throws IOException {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put(PASS_ENV, new String(password.bytes(), StandardCharsets.UTF_8));
    final Process process = builder.start();
    final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool " + args[0] + " did not finish within 120 s");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while keytool " + args[0] + " ran", e);
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          "keytool " + args[0] + " failed (exit " + process.exitValue() + "): " + output.strip());
    }
  }
}
