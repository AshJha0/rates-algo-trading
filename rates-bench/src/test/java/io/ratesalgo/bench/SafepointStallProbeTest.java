package io.ratesalgo.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The probe's methods run end to end on small files and leave nothing behind. */
class SafepointStallProbeTest {

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(strings = {"agrona", "channel", "channel-touch", "journal"})
  void everyMethodCreatesAndRemovesItsFiles(final String method) throws Exception {
    final SafepointStallProbe.Result r = SafepointStallProbe.run(method, dir, 1 << 20, 2);
    assertEquals(method, r.method());
    assertTrue(r.createMaxNanos() > 0);
    assertTrue(r.victimMaxGapNanos() >= 0);
    assertTrue(r.line().startsWith(method + ": longest create"), r.line());
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(0, files.count());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "nope"})
  void badArgumentsAreRefused(final String method) {
    if (method.isEmpty()) {
      assertThrows(IllegalArgumentException.class, () -> SafepointStallProbe.main(new String[0]));
    } else {
      assertThrows(IllegalArgumentException.class, () -> SafepointStallProbe.run(method, dir, 1 << 16, 1));
    }
  }

  @Test
  void mainPrintsOneLine() throws Exception {
    SafepointStallProbe.main(new String[] {"channel", dir.resolve("m").toString(), "1", "1"});
    try (Stream<Path> files = Files.list(dir.resolve("m"))) {
      assertEquals(0, files.count());
    }
  }
}
