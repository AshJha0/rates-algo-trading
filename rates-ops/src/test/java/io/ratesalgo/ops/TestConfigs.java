package io.ratesalgo.ops;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.ops.config.ProcessConfig;
import java.io.IOException;
import java.io.Reader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.yaml.snakeyaml.Yaml;

/**
 * Test copies of the committed configurations. The committed files name key stores that exist
 * only on a deployed host, so a test either drops the TLS sections (plain loopback, the simulator
 * preset) or points them at a throwaway certificate generated for the run.
 */
public final class TestConfigs {

  /** The committed configuration directory, seen from the module. */
  public static final Path CONFIG_DIR = Path.of("../deploy/config");

  /** The schema, seen from the module. */
  public static final Path SCHEMA = CONFIG_DIR.resolve("schema/process-config.schema.json");

  private TestConfigs() {}

  /** A random password for a throwaway store: nothing fixed is committed. */
  public static Secret randomPassword() {
    return Secret.of(UUID.randomUUID().toString());
  }

  /** A fresh certificate for localhost and 127.0.0.1 with a random password. */
  public static KeytoolCertificates.Identity identity(final Path dir, final String name) {
    return KeytoolCertificates.localhost(dir, name, randomPassword(), 2);
  }

  /** The password of an identity as text, for an environment map. */
  public static String text(final Secret password) {
    return new String(password.bytes(), StandardCharsets.UTF_8);
  }

  /** Reads committed configuration {@code name}, applies {@code edit} and writes the copy into {@code dir}. */
  @SuppressWarnings("unchecked")
  public static Path copy(final String name, final Path dir, final Consumer<Map<String, Object>> edit)
      throws IOException {
    final Map<String, Object> doc;
    try (Reader r = Files.newBufferedReader(CONFIG_DIR.resolve(name + ".yaml"), StandardCharsets.UTF_8)) {
      doc = (Map<String, Object>) ProcessConfig.parse(r);
    }
    edit.accept(doc);
    final Path out = Files.createTempFile(dir, name + "-", ".yaml");
    Files.writeString(out, new Yaml().dump(doc));
    return out;
  }

  /** A copy with no {@code fix} section and no {@code http.tls}: plain loopback, as before TLS. */
  public static Path plain(final String name, final Path dir) throws IOException {
    return copy(name, dir, TestConfigs::strip);
  }

  /** Removes {@code fix} and {@code http.tls}. */
  public static void strip(final Map<String, Object> doc) {
    doc.remove("fix");
    section(doc, "http").remove("tls");
  }

  /** A copy whose every store path points at {@code id}; the password variables keep their names. */
  public static Path secured(final String name, final Path dir, final KeytoolCertificates.Identity id)
      throws IOException {
    return copy(name, dir, doc -> {
      point(section(section(doc, "http"), "tls"), id);
      final Map<String, Object> fix = section(doc, "fix");
      point(section(fix, "tls"), id);
      point(section(fix, "acceptor"), id);
    });
  }

  private static void point(final Map<String, Object> section, final KeytoolCertificates.Identity id) {
    section.computeIfPresent("keyStorePath", (k, v) -> id.keyStore().toString());
    section.computeIfPresent("trustStorePath", (k, v) -> id.trustStore().toString());
  }

  /** The named section of a parsed document, or a detached empty map when it is absent. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> section(final Map<String, Object> parent, final String key) {
    return parent.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
  }

  /** An environment that sets every password variable the committed configurations name. */
  public static Map<String, String> passwords(final Secret password, final String... more) {
    final Map<String, String> env = new HashMap<>();
    final String text = text(password);
    env.put("RATES_HTTP_KEYSTORE_PASSWORD", text);
    env.put("RATES_FIX_TRUSTSTORE_PASSWORD", text);
    env.put("RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD", text);
    env.put("RATES_FIX_PASSWORD", text);
    for (int i = 0; i < more.length; i += 2) {
      env.put(more[i], more[i + 1]);
    }
    return env;
  }

  /** An HTTP/1.1 client that believes the given trust store (and nothing else). */
  public static HttpClient client(final Path trustStore, final Secret password) {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(TlsConfig.builder().trustStore(trustStore, password).build().context())
        .build();
  }
}
