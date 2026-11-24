package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The output directory of a long run: one file per finished session, written atomically, so a
 * killed run resumes without recomputing or double-counting.
 *
 * <pre>
 *   manifest.json          what the run is: pre-registration and config hashes, seeds, arms, band
 *                          rows, factory, presets hash, code commit; a resume must match it exactly
 *   sessions/&lt;seed&gt;.json   one session's per-arm outputs; present only once complete
 *   parts/&lt;seed&gt;/         a session in progress: one file per row journal and per (row, arm),
 *                          so a killed worker resumes at the next arm; removed once the
 *                          session file is written
 *   timing/&lt;seed&gt;.txt      that session's wall-clock milliseconds, read by the status command
 *   logs/&lt;seed&gt;.log        the worker's output
 *   heartbeat.txt          rewritten at least every minute while the coordinator runs
 *   FAILED                 written when a session fails twice; the run stops launching
 *   summary.json           written once, after every registered session is present
 * </pre>
 *
 * <p>No peeking. The interim files hold only per-session outputs. The status command reads file
 * names, timing files, the heartbeat and the failure marker, never a session file; the summary is
 * computed by {@link ExperimentSummary} only when {@link #missing} is empty for the registered
 * seeds, and it is the first code that reads a session's P&amp;L. A session file is keyed by its
 * seed, so a session run twice replaces itself and is counted once.
 */
public final class Checkpoints {

  private final Path dir;

  public Checkpoints(final Path dir) {
    this.dir = dir.toAbsolutePath().normalize();
  }

  public Path dir() {
    return dir;
  }

  public Path manifestFile() {
    return dir.resolve("manifest.json");
  }

  Path sessionFile(final long seed) {
    return dir.resolve("sessions").resolve(seed + ".json");
  }

  Path timingFile(final long seed) {
    return dir.resolve("timing").resolve(seed + ".txt");
  }

  /** The worker log of a session. */
  public Path logFile(final long seed) {
    return dir.resolve("logs").resolve(seed + ".log");
  }

  public Path heartbeatFile() {
    return dir.resolve("heartbeat.txt");
  }

  public Path failureFile() {
    return dir.resolve("FAILED");
  }

  public Path summaryFile() {
    return dir.resolve("summary.json");
  }

  /** Creates the directories and removes temporary files a killed writer left behind. */
  public void prepare() {
    try {
      for (final String sub : List.of("sessions", "timing", "logs", "parts")) {
        Files.createDirectories(dir.resolve(sub));
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir.resolve(sub), "*.tmp")) {
          for (final Path p : s) {
            Files.delete(p);
          }
        }
      }
      try (DirectoryStream<Path> seeds = Files.newDirectoryStream(dir.resolve("parts"))) {
        for (final Path seed : seeds) {
          if (Files.isDirectory(seed)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(seed, "*.tmp")) {
              for (final Path p : s) {
                Files.delete(p);
              }
            }
          }
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ------------------------------------------------------------------ parts of a session

  private Path partDir(final long seed) {
    return dir.resolve("parts").resolve(Long.toString(seed));
  }

  private static String fileName(final String row) {
    return row.replace(':', '_');
  }

  /**
   * The checkpoint of one session's work in progress: a file per (row, arm) and per row's journal,
   * so a worker that dies after five of nine arms resumes at the sixth. A part holds what the
   * session file will hold and is read only to assemble it; status never opens one.
   */
  public SessionExecutor.Parts parts(final long seed) {
    final Path d = partDir(seed);
    return new SessionExecutor.Parts() {
      @Override
      public Map<String, Object> row(final String row) {
        return read(d.resolve(fileName(row) + ".row.json"));
      }

      @Override
      public void putRow(final String row, final Map<String, Object> value) {
        writeAtomically(d.resolve(fileName(row) + ".row.json"), Json.write(value) + "\n");
      }

      @Override
      public Map<String, Object> arm(final String row, final String arm) {
        return read(d.resolve(fileName(row) + "__" + arm + ".json"));
      }

      @Override
      public void putArm(final String row, final String arm, final Map<String, Object> value) {
        writeAtomically(d.resolve(fileName(row) + "__" + arm + ".json"), Json.write(value) + "\n");
      }
    };
  }

  private static Map<String, Object> read(final Path p) {
    return Files.isRegularFile(p) ? Yaml.loadMap(p) : null;
  }

  /** Removes a session's parts once its session file is written. */
  public void clearParts(final long seed) {
    final Path d = partDir(seed);
    if (!Files.isDirectory(d)) {
      return;
    }
    try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
      for (final Path p : s) {
        Files.delete(p);
      }
      Files.delete(d);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Number of (row, arm) parts on disk for a session, for status; reads names only. */
  public long partCount(final long seed) {
    final Path d = partDir(seed);
    if (!Files.isDirectory(d)) {
      return 0;
    }
    try (DirectoryStream<Path> s = Files.newDirectoryStream(d, "*__*.json")) {
      long n = 0;
      final java.util.Iterator<Path> it = s.iterator();
      while (it.hasNext()) {
        it.next();
        n++;
      }
      return n;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes text to a temporary file next to {@code target} and moves it into place atomically. */
  static void writeAtomically(final Path target, final String text) {
    try {
      final Path parent = target.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
      Files.writeString(tmp, text, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (final AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes the manifest of a new run, or checks that a resumed run is the same run. */
  public void writeOrCheckManifest(final Map<String, Object> manifest) {
    if (Files.isRegularFile(manifestFile())) {
      final Map<String, Object> existing = readManifest();
      final List<String> differences = new ArrayList<>();
      for (final Map.Entry<String, Object> e : manifest.entrySet()) {
        if (!"created_utc".equals(e.getKey())
            && !"workers".equals(e.getKey())
            // compared as canonical JSON (sorted keys), so a map read back from the file matches
            && !Json.write(e.getValue()).equals(Json.write(existing.get(e.getKey())))) {
          differences.add(e.getKey() + ": " + existing.get(e.getKey()) + " -> " + e.getValue());
        }
      }
      if (!differences.isEmpty()) {
        throw new IllegalStateException(
            "the output directory holds a different run; resume refused (" + String.join("; ", differences) + ")");
      }
      return;
    }
    writeAtomically(manifestFile(), Json.write(manifest) + "\n");
  }

  public Map<String, Object> readManifest() {
    if (!Files.isRegularFile(manifestFile())) {
      throw new IllegalStateException("no run in " + dir + " (manifest.json missing)");
    }
    return Yaml.loadMap(manifestFile());
  }

  /** True once a session's record is in place. */
  public boolean has(final long seed) {
    return Files.isRegularFile(sessionFile(seed));
  }

  /** Records a finished session: its outputs, then its wall-clock time. */
  public void write(final long seed, final Map<String, Object> record) {
    if (!(record.get("seed") instanceof Number n) || n.longValue() != seed) {
      throw new IllegalArgumentException("record of seed " + record.get("seed") + " written as " + seed);
    }
    writeAtomically(sessionFile(seed), Json.write(record) + "\n");
    writeAtomically(timingFile(seed), seed + " " + record.get("wall_ms") + "\n");
  }

  /** Registered seeds without a session record, in order. */
  public List<Long> missing(final List<Long> seeds) {
    final List<Long> out = new ArrayList<>();
    for (final long s : seeds) {
      if (!has(s)) {
        out.add(s);
      }
    }
    return out;
  }

  /** Wall-clock milliseconds per finished session, from the timing files only. */
  public Map<Long, Long> timings() {
    final Map<Long, Long> out = new TreeMap<>();
    final Path t = dir.resolve("timing");
    if (!Files.isDirectory(t)) {
      return out;
    }
    try (DirectoryStream<Path> s = Files.newDirectoryStream(t, "*.txt")) {
      for (final Path p : s) {
        final String text = Files.readString(p, StandardCharsets.UTF_8).trim();
        final int space = text.indexOf(' ');
        out.put(Long.parseLong(text.substring(0, space)), Long.parseLong(text.substring(space + 1)));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  /**
   * Every registered session's record, in seed order. Refuses while any is missing: this is the
   * only reader of session outputs, and it runs once, at the end.
   */
  Map<Long, Map<String, Object>> loadComplete(final List<Long> seeds) {
    final List<Long> missing = missing(seeds);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(missing.size() + " of " + seeds.size() + " registered sessions are missing");
    }
    final Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
    for (final long s : seeds) {
      final Map<String, Object> rec = Yaml.loadMap(sessionFile(s));
      if (!(rec.get("seed") instanceof Number n) || n.longValue() != s) {
        throw new IllegalStateException("session file " + sessionFile(s) + " holds seed " + rec.get("seed"));
      }
      out.put(s, rec);
    }
    return out;
  }

  public void heartbeat(final String line) {
    writeAtomically(heartbeatFile(), line + "\n");
  }

  public void fail(final String why) {
    writeAtomically(failureFile(), why + "\n");
  }

  public boolean failed() {
    return Files.isRegularFile(failureFile());
  }

  /** Clears a failure marker when a run is restarted on purpose. */
  public void clearFailure() {
    try {
      Files.deleteIfExists(failureFile());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
