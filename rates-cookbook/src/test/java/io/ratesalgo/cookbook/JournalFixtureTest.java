package io.ratesalgo.cookbook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins data/fixtures/journal/recipe02.raj, the journal recipe 35 verifies from Python, to what
 * JournalWriter writes today: the three frames of recipe 2 in a 256-byte file. If the frame
 * layout or the chain hash changes, this test fails before the Python recipe silently checks a
 * stale file. Regenerate with -Dfixture.regenerate=true after a deliberate format change.
 */
class JournalFixtureTest {

  static final String FIXTURE = "data/fixtures/journal/recipe02.raj";

  static void write(final Path file) throws IOException {
    final ManualClock clock = new ManualClock(0L);
    final UnsafeBuffer payload = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    try (JournalWriter writer = JournalWriter.create(file, 256, clock)) {
      for (int i = 1; i <= 3; i++) {
        clock.advance(1_000L);
        payload.putLong(0, 100L * i);
        writer.append(1, 0, payload, 0, 8);
      }
    }
  }

  @Test
  void committedJournalIsWhatJavaWrites(@TempDir final Path tmp) throws IOException {
    final Path fresh = tmp.resolve("recipe02.raj");
    write(fresh);
    final Path committed = RepoRoot.find().resolve(FIXTURE);
    if (Boolean.getBoolean("fixture.regenerate")) {
      Files.createDirectories(committed.getParent());
      Files.copy(fresh, committed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    assertArrayEquals(Files.readAllBytes(fresh), Files.readAllBytes(committed));
  }
}
