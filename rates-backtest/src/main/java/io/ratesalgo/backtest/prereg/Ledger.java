package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The append-only experiment ledger, {@code data/ledger/ledger.jsonl} (API_SPEC.md Section 16.8).
 *
 * <p>One JSON object per line, canonical (sorted keys, no whitespace). Each line carries {@code
 * seq} (1-based) and {@code prev_sha256}, the SHA-256 of the previous line's text (64 zeros for
 * the first), so that editing or deleting a past line breaks the chain at that line. {@link
 * #append} verifies the whole chain before writing and refuses to extend a broken one. Every run
 * of a pre-registered experiment is recorded whatever its verdict, so the count of looks at the
 * data is public. A run that is refused before it starts reads no data and is not recorded.
 */
public final class Ledger {

  public static final int VERSION = 1;
  public static final String GENESIS = "0".repeat(64);

  private final Path file;

  public Ledger(final Path file) {
    this.file = file;
  }

  /** Parsed lines, in order. Verifies the chain. */
  public List<Map<String, Object>> entries() {
    final List<Map<String, Object>> out = new ArrayList<>();
    String prev = GENESIS;
    long seq = 1;
    for (final String line : lines()) {
      final Map<String, Object> entry = Yaml.parseMap(line, file + " line " + seq);
      if (!prev.equals(entry.get("prev_sha256"))) {
        throw new IllegalStateException(file + ": hash chain broken at line " + seq);
      }
      final Object s = entry.get("seq");
      if (!(s instanceof Number n) || n.longValue() != seq) {
        throw new IllegalStateException(file + ": seq " + s + " at line " + seq);
      }
      out.add(entry);
      prev = Hashing.sha256Text(line);
      seq++;
    }
    return out;
  }

  private List<String> lines() {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      final List<String> out = new ArrayList<>();
      for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          out.add(line);
        }
      }
      return out;
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read ledger " + file, e);
    }
  }

  /**
   * Appends a record, filling in {@code ledger_version}, {@code seq} and {@code prev_sha256}.
   *
   * @return the line written
   */
  public String append(final Map<String, Object> record) {
    final List<String> existing = lines();
    entries(); // verifies the chain
    final Map<String, Object> full = new LinkedHashMap<>(record);
    full.put("ledger_version", VERSION);
    full.put("seq", existing.size() + 1L);
    full.put(
        "prev_sha256", existing.isEmpty() ? GENESIS : Hashing.sha256Text(existing.get(existing.size() - 1)));
    final String line = Json.write(full);
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(
          file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot append to ledger " + file, e);
    }
    return line;
  }

  /** Number of earlier records for a pre-registration and scope. */
  public long looks(final String preregId, final String scope) {
    return entries().stream()
        .filter(e -> preregId.equals(e.get("prereg_id")) && scope.equals(e.get("scope")))
        .count();
  }

  public Path file() {
    return file;
  }
}
