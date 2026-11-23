package io.ratesalgo.common.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

/**
 * A password or token held in memory. It comes from an environment variable or a file, never from
 * a configuration file, and it does not print: {@link #toString} shows no part of the value, so a
 * secret that ends up in a log line, an exception message or a record's {@code toString} is still
 * not disclosed. The class has no {@code equals}: comparisons go through {@link #matches}, which
 * takes the same time whatever the candidate is.
 */
public final class Secret {

  private static final String MASK = "Secret[****]";

  private final byte[] value;

  private Secret(final byte[] value) {
    this.value = value;
  }

  /** A secret with the given text (UTF-8). For values that are already in memory, and for tests. */
  public static Secret of(final String text) {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("a secret must not be empty");
    }
    return new Secret(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads the secret from environment variable {@code name}.
   *
   * @throws IllegalStateException if the variable is unset or empty; the message names the
   *     variable, never a value
   */
  public static Secret fromEnv(final Map<String, String> env, final String name) {
    final String text = env.get(name);
    if (text == null || text.isEmpty()) {
      throw new IllegalStateException("environment variable " + name + " is not set");
    }
    return new Secret(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads the secret from a file, less trailing line ends and spaces (a Docker or Kubernetes
   * secret mount).
   *
   * @throws IllegalStateException if the file cannot be read or holds nothing; the message names
   *     the file, never its content
   */
  public static Secret fromFile(final Path file) {
    final byte[] raw;
    try {
      raw = Files.readAllBytes(file);
    } catch (final IOException e) {
      throw new IllegalStateException("cannot read the secret file " + file, e);
    }
    int end = raw.length;
    while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r' || raw[end - 1] == ' ')) {
      end--;
    }
    final byte[] trimmed = Arrays.copyOf(raw, end);
    Arrays.fill(raw, (byte) 0);
    if (end == 0) {
      throw new IllegalStateException("the secret file " + file + " is empty");
    }
    return new Secret(trimmed);
  }

  /**
   * The secret named by an environment variable or a file, whichever is given. Exactly one of the
   * two names must be non-null.
   */
  public static Secret fromEnvOrFile(final Map<String, String> env, final String envName, final String file) {
    if ((envName == null) == (file == null)) {
      throw new IllegalArgumentException("name an environment variable or a file for the secret, not both or neither");
    }
    return envName != null ? fromEnv(env, envName) : fromFile(Path.of(file));
  }

  /** True if {@code candidate} is this secret. Constant time in the candidate's content and length. */
  public boolean matches(final byte[] candidate) {
    // digests of both sides: equal-length inputs to the comparison whatever the candidate's length
    final MessageDigest sha = sha256();
    final byte[] mine = sha.digest(value);
    final byte[] theirs = sha.digest(candidate == null ? new byte[0] : candidate);
    return MessageDigest.isEqual(mine, theirs) && candidate != null;
  }

  /** True if {@code candidate} (as UTF-8) is this secret. Constant time. */
  public boolean matches(final CharSequence candidate) {
    return matches(candidate == null ? null : candidate.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** True if both hold the same value. Constant time. */
  public boolean matches(final Secret other) {
    return other != null && matches(other.value);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }

  /** A copy of the value as characters, for key store APIs; the caller clears it after use. */
  public char[] chars() {
    final CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(value));
    final char[] out = new char[decoded.remaining()];
    decoded.get(out);
    return out;
  }

  /** A copy of the value as bytes (UTF-8); the caller clears it after use. */
  public byte[] bytes() {
    return value.clone();
  }

  /** Length of the value in bytes. */
  public int length() {
    return value.length;
  }

  /** Never the value. */
  @Override
  public String toString() {
    return MASK;
  }
}
