package io.ratesalgo.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.app.posttrade.JournalFeed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stepped v2 day is the same day every time. The three order books share one market model and
 * each venue has FIX threads of its own; before the venues' work was sequenced, two children sent
 * to the two Treasury venues in one step reached the model in whichever order those threads ran,
 * and the same seed gave different book frames from run to run. The journals are compared frame
 * by frame: the inbound journal holds every frame of every feed and every execution report in
 * the order the loop saw them, the outbound journal everything the desk sent.
 */
class V2SteppedDeterminismTest {

  private static final long MINUTE = 60_000_000_000L;
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 91_007);

  @TempDir Path dir;

  /**
   * Frames and a digest over every journaled frame, inbound and outbound merged in journal order:
   * sequence, timestamp, template and bytes. The files themselves are not compared: a segment's
   * header carries when it was created.
   */
  private static long[] digest(final Path groupDir) {
    final long[] d = {0L, 1469598103934665603L};
    JournalFeed.feed(
        groupDir, "in", "out",
        (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
          long h = d[1];
          h = (h ^ sequence) * 1099511628211L;
          h = (h ^ timestampNanos) * 1099511628211L;
          h = (h ^ templateId) * 1099511628211L;
          for (int i = 0; i < length; i++) {
            h = (h ^ buffer.getByte(offset + i)) * 1099511628211L;
          }
          d[1] = h;
          d[0]++;
        });
    return d;
  }

  private long[] run(final Path journalDir) {
    try (V2DeskProcess p =
        V2DeskProcess.start(new V2DeskProcess.Options(journalDir, SEED, DeskProfile.v2(), 120 * MINUTE))) {
      p.step(20 * MINUTE, 20_000_000L);
      long fills = 0;
      for (int v = 0; v < 3; v++) {
        fills += V2Reconciliation.fills(p.venues().books().get(v), AppIds.COMP_ID);
      }
      return new long[] {p.desk().desk().quoting().quotesSent(), p.desk().hedgesRouted(), fills};
    }
  }

  @Test
  void threeRunsOfOneSeedWriteTheSameJournals() {
    final long[] first = run(dir.resolve("a"));
    final long[] second = run(dir.resolve("b"));
    final long[] third = run(dir.resolve("c"));
    // the window must exercise what used to race: hedges filled on the order books
    assertTrue(first[1] > 0 && first[2] > 0, "hedges routed and filled: " + java.util.Arrays.toString(first));
    assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(second));
    assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(third));
    final long[] a = digest(dir.resolve("a").resolve("usd"));
    assertTrue(a[0] > 10_000, "frames journaled: " + a[0]);
    assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(digest(dir.resolve("b").resolve("usd"))));
    assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(digest(dir.resolve("c").resolve("usd"))));
  }
}
