package io.ratesalgo.cookbook;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.app.TradingProcessMain;
import io.ratesalgo.app.backtest.DeskBacktest;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.posttrade.VenueReconciliation;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.posttrade.position.Position;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** COOKBOOK.md recipe 22: run the trading process on a synthetic day and read the daily report. */
public final class Recipe22TradingDay {

  private Recipe22TradingDay() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe22");
    // the D2C and CLOB simulators, both gateways and the USD loop, from 08:25 New York
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 7L, DeskSettings.defaults()));
    final DeskGroup desk = p.group(0).desk();
    try {
      p.step(10 * 60_000_000_000L, 10_000_000L);
      System.out.println(String.format(Locale.ROOT,
          "10 simulated minutes: %d RFQs, %d quotes, %d RFQ fills, %d hedge parents, %d hedge fills",
          desk.desk().quoting().rfqs(), desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(),
          desk.hedgesRouted(), desk.hedgeFills()));
      p.kill(9001);
      p.step(1_000_000_000L, 10_000_000L);
      System.out.println("after the kill switch: " + p.statusJson());
    } finally {
      p.close();
    }

    // post-trade: positions from the journals, reconciled with the venues' trade registers
    final PositionKeeper keeper =
        JournalFeed.keeper(p.group(0).dir(), "in", "out", JournalFeed.books(p.universe()),
            JournalFeed.catalogue(desk.pricing()));
    for (final Position pos : keeper.positions()) {
      System.out.println(String.format(Locale.ROOT, "  %s net %,d", pos.key().text(), pos.netQuantity()));
    }
    final ReconResult recon = VenueReconciliation.run(keeper, p.venues().d2c(), p.venues().clob());
    System.out.println(String.format(Locale.ROOT, "reconciliation: %d ours, %d theirs, %d matched, %d breaks",
        recon.oursCount(), recon.theirsCount(), recon.matched(), recon.breaks().size()));

    // the same journals through a fresh desk: the outbound stream must repeat exactly
    final ReplayResult replay = TradingProcessMain.replayGroup(p.group(0).dir());
    System.out.println(String.format(Locale.ROOT, "replay: %d inbound frames, %d of %d outbound messages, %s",
        replay.inboundFrames(), replay.outboundProduced(), replay.outboundRecorded(),
        replay.matches() ? "hash matches" : "MISMATCH"));

    // the daily report: the back-tester replays the day with the desk as its strategy
    final DeskBacktest.Run run = DeskBacktest.run(p.group(0).dir(), DeskBacktest.presets(), dir.resolve("report"), 1L);
    System.out.println(String.format(Locale.ROOT, "back-test: %d RFQs, %d client fills, %d hedge fills",
        run.result().rfqs().size(), run.result().clientFills(), run.result().hedgeFills()));
    final String report = Files.readString(dir.resolve("report").resolve("report.md"));
    report.lines().filter(l -> l.startsWith("| TOTAL") || l.startsWith("# ")).forEach(System.out::println);
  }
}
