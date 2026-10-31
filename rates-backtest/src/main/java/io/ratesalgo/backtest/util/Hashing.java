package io.ratesalgo.backtest.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of files and text, as lower-case hex.
 *
 * <p>Text files (pre-registrations, configs) are hashed after normalising CRLF to LF, so that a
 * Windows checkout and a Linux checkout give the same hash for the same committed file. Binary
 * files (journals) are hashed as bytes.
 */
public final class Hashing {

  private Hashing() {}

  public static String sha256Text(final String text) {
    return hex(digest().digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
  }

  public static String sha256TextFile(final Path path) {
    try {
      return sha256Text(Files.readString(path, StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  public static String sha256File(final Path path) {
    final MessageDigest md = digest();
    final byte[] chunk = new byte[1 << 16];
    try (InputStream in = Files.newInputStream(path)) {
      int n;
      while ((n = in.read(chunk)) > 0) {
        md.update(chunk, 0, n);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
    return hex(md.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JDK", e);
    }
  }

  private static String hex(final byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
