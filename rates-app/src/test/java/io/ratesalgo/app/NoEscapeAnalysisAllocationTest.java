package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link WiredLoopAllocationTest} again in a JVM without escape analysis (review finding L13,
 * DECISIONS.md DR-R6-L07): the real desk on the loop, with {@code -XX:-DoEscapeAnalysis}, so an
 * object the desk's code makes per message and that only escape analysis took off the heap shows
 * as bytes in every window. The windows and the bound are the ones of the test itself.
 */
class NoEscapeAnalysisAllocationTest {

  @Test
  void theWiredDeskAllocatesNothingWithoutEscapeAnalysis() throws Exception {
    final ChildJvm.Result r =
        ChildJvm.run(WiredLoopAllocationTest.class, "theWiredDeskAllocatesNothingOnTheLoopThread", 900,
            "-XX:-DoEscapeAnalysis", "-Xmx1g");
    System.out.println("no escape analysis: " + r.output().lines()
        .filter(l -> l.startsWith("wired allocation") || l.startsWith("CHILD")).reduce("", (a, b) -> a + b + " | "));
    assertEquals(0, r.exitCode(), r.output());
    assertTrue(r.output().contains("CHILD PASSED"), r.output());
  }
}
