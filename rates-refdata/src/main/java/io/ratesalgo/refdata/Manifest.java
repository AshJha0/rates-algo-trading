package io.ratesalgo.refdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code MANIFEST.sha256} file of a fixture version: one {@code <sha256>  <file>} line per
 * fixture. {@link #verify(Path)} recomputes every digest and refuses any mismatch or missing file,
 * so a hand edit to a fixture without regenerating the manifest fails at load time.
 */
public final class Manifest {

  /** Name of the manifest file inside a fixture directory. */
  public static final String FILE_NAME = "MANIFEST.sha256";

  private Manifest() {}

  /**
   * Verifies every file listed in the directory's manifest.
   *
   * @return file name to sha256, in manifest order
   * @throws RefDataException on a missing manifest, missing file or digest mismatch
   */
  public static Map<String, String> verify(final Path directory) {
    final Path manifest = directory.resolve(FILE_NAME);
    final List<String> lines;
    try {
      lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RefDataException("cannot read manifest " + manifest, e);
    }
    final Map<String, String> digests = new LinkedHashMap<>();
    for (final String raw : lines) {
      final String line = raw.strip();
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      final int split = line.indexOf("  ");
      if (split != 64) {
        throw new RefDataException(FILE_NAME + ": malformed line '" + line + "'");
      }
      final String expected = line.substring(0, split);
      final String name = line.substring(split + 2);
      final String actual = sha256(directory.resolve(name));
      if (!expected.equals(actual)) {
        throw new RefDataException(
            "checksum mismatch for " + name + ": manifest " + expected + ", file " + actual);
      }
      digests.put(name, actual);
    }
    return digests;
  }

  /** Hex sha256 of a file. */
  public static String sha256(final Path file) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new RefDataException("cannot read " + file, e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JDK", e);
    }
  }
}
