import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.ChainHash;
import io.ratesalgo.journal.JournalWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes the journals research/tests/test_journal_formats.py checks Python against, with the Java
 * JournalWriter and ChainHash, and prints WORD64 known answers. Not built by Maven. Run from the
 * repository root (on Windows the classpath separator is ';' and paths must be Windows paths):
 *
 * <pre>
 * A=~/.m2/repository/org/agrona/agrona/2.7.0/agrona-2.7.0.jar
 * javac -d /tmp/mk -cp $A -sourcepath rates-journal/src/main/java:rates-common/src/main/java \
 *     research/tests/fixtures/journal/MakeJournals.java
 * rm -f research/tests/fixtures/journal/*.raj
 * java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -cp /tmp/mk:$A MakeJournals \
 *     research/tests/fixtures/journal > research/tests/fixtures/journal/word64_vectors.txt
 * </pre>
 */
public final class MakeJournals {

  // payload lengths around the 8-byte word: empty, tails of 1 and 7, whole words, word plus tail
  private static final int[] LENGTHS = {0, 1, 7, 8, 9, 15, 16, 17};

  private static UnsafeBuffer payload(final int seed, final int length) {
    final byte[] b = new byte[Math.max(length, 1)];
    for (int i = 0; i < length; i++) {
      b[i] = (byte) (seed * 31 + i * 7 + 3);
    }
    return new UnsafeBuffer(b);
  }

  private static void write(final Path file, final int algorithm) throws Exception {
    final ManualClock clock = new ManualClock(0L);
    try (JournalWriter w = JournalWriter.create(file, 512, clock, algorithm)) {
      for (int n = 0; n < LENGTHS.length; n++) {
        clock.advance(1_000L);
        w.append(1 + n, 0, payload(n, LENGTHS[n]), 0, LENGTHS[n]);
      }
    }
  }

  public static void main(final String[] args) throws Exception {
    final Path dir = Path.of(args[0]);
    write(dir.resolve("fnv1a_v1.raj"), ChainHash.FNV1A);
    write(dir.resolve("word64_v2.raj"), ChainHash.WORD64);
    // previous hash, payload as hex ("-" for empty), Java's WORD64 hash: one line each
    final long[] previous = {0L, 1L, -1L, 0x8fe9535b8a55c92bL};
    for (final long p : previous) {
      for (int length = 0; length <= 24; length++) {
        final UnsafeBuffer b = payload(length, length);
        final StringBuilder hex = new StringBuilder();
        for (int i = 0; i < length; i++) {
          hex.append(String.format("%02x", b.getByte(i) & 0xFF));
        }
        System.out.printf(
            "%016x %s %016x%n", p, hex.length() == 0 ? "-" : hex, ChainHash.word64(p, b, 0, length));
      }
    }
  }
}
