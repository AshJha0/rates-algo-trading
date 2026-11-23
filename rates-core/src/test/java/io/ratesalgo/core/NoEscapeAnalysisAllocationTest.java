package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link LoopAllocationTest} again in a JVM without escape analysis (review finding L13,
 * DECISIONS.md DR-R6-L07). With {@code -XX:-DoEscapeAnalysis} C2 allocates every object it would
 * otherwise have replaced by registers, so the loop's zero allocation must hold by construction,
 * not by the optimiser's grace. The windows and their bound are the ones the test uses with
 * escape analysis on.
 */
class NoEscapeAnalysisAllocationTest {

  @Test
  void theLoopAllocatesNothingWithoutEscapeAnalysisInEitherJournalMode() throws Exception {
    for (final JournalMode mode : JournalMode.values()) {
      final ChildJvm.Result r =
          ChildJvm.run(LoopAllocationTest.class, "loopThreadAllocatesNothingOverOneMillionMessages", mode.name(), 900,
              "-XX:-DoEscapeAnalysis", "-Xmx1g");
      System.out.println("no escape analysis, " + mode + ": " + r.output().lines()
          .filter(l -> l.contains("bytes") || l.startsWith("CHILD")).reduce("", (a, b) -> a + b + " | "));
      assertEquals(0, r.exitCode(), r.output());
      assertTrue(r.output().contains("CHILD PASSED"), r.output());
    }
  }
}
