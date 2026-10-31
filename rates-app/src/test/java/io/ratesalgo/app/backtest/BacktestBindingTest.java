package io.ratesalgo.app.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.app.TradingProcessMain;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.core.ReplayResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One day of the D2C RFQ simulator recorded by the threaded process (each loop on its own thread,
 * the gateways and venues on a driver thread pacing simulated time at 60 times real time), then
 * replayed: through {@code LoopReplay} with the same desk factory, which must reproduce the
 * outbound hash, and through the P5 back-tester with the desk as its strategy, which must produce
 * the daily report (API_SPEC.md Section 18.7).
 */
class BacktestBindingTest {

  @TempDir static Path dir;

  static Path groupDir;

  @BeforeAll
  static void recordADay() throws InterruptedException {
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir.resolve("journal"), 21L, DeskSettings.defaults()));
    try {
      p.startThreads(60.0);
      Thread.sleep(6_000L);
      assertNull(p.failure());
      groupDir = p.group(0).dir();
    } finally {
      p.close();
    }
  }

  @Test
  void theRecordedDayReplaysToTheSameOutboundHash() {
    final ReplayResult r = TradingProcessMain.replayGroup(groupDir);
    System.out.println("threaded day replay: " + r);
    assertTrue(r.outboundRecorded() > 0, "the day sent something");
    assertTrue(r.matches(), r.toString());
  }

  @Test
  void theDailyReportRunsOnTheRecordedJournal() throws Exception {
    final DeskBacktest.Run run = DeskBacktest.run(groupDir, DeskBacktest.presets(), dir.resolve("report"), 1L);
    System.out.println(String.format(Locale.ROOT,
        "back-test on the recorded day: %d frames exported, %d RFQs, %d quoted by the desk, %d client fills, "
            + "%d hedge orders, P&L %.2f USD",
        run.exportedFrames(), run.result().rfqs().size(), run.strategy().quotes(), run.result().clientFills(),
        run.strategy().orders(), run.result().total().positionPnl()));
    assertTrue(run.result().rfqs().size() > 0, "RFQs replayed");
    assertTrue(run.strategy().quotes() > 0, "the desk quoted in the back-test");
    final String md = Files.readString(dir.resolve("report").resolve("report.md"));
    assertTrue(md.contains("desk"), md);
    assertTrue(Files.isRegularFile(dir.resolve("report").resolve("report.json")));

    final DeskBacktest.Run again = DeskBacktest.run(groupDir, DeskBacktest.presets(), dir.resolve("again"), 1L);
    assertEquals(run.result().outputChainHash(), again.result().outputChainHash(), "the back-test is deterministic");
  }
}
