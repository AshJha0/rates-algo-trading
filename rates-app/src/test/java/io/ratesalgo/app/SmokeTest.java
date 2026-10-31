package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.posttrade.VenueReconciliation;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.ops.http.OpsHttpServer;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole trading process on a seeded synthetic day (API_SPEC.md Section 18.8): the D2C and
 * CLOB simulators, both FIX gateways, the book feed, the curve feed and the USD loop with its
 * journals run for ten simulated minutes from 08:25 New York (across the 08:30 peak of the RFQ
 * arrival model); then the kill switch is pulled through the control endpoint. The test asserts that quotes were sent and filled, hedges were
 * routed and filled on the CLOB, the position keeper rebuilt from the journals holds positions and
 * reconciles with both venues without a break, the kill switch left no quote or order working at
 * the venues, and replaying the journals through a fresh desk reproduces the outbound hash.
 */
class SmokeTest {

  static final long MINUTE = 60_000_000_000L;
  static final long STEP = 10_000_000L;

  @TempDir Path dir;

  @Test
  void aSyntheticDayRunsEndToEnd() throws Exception {
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 7L, DeskSettings.defaults()));
    final DeskGroup desk = p.group(0).desk();
    final Path groupDir = p.group(0).dir();
    try (OpsHttpServer http = TradingProcessMain.serve(p, new MetricsRegistry(), "127.0.0.1", 0, "token")) {
      p.step(10 * MINUTE, STEP);
      System.out.println(String.format(Locale.ROOT,
          "smoke: rfqs %d, quotes %d, fills %d, passes %d, hedges routed %d, hedge fills %d, book updates %d, "
              + "curve messages %d, rfq confirmations %d, live children %d, venue rfqs %d",
          desk.desk().quoting().rfqs(), desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(),
          desk.desk().quoting().passes(), desk.hedgesRouted(), desk.hedgeFills(), p.feed().frames(),
          p.curve().published(), desk.rfqConfirmations(), desk.liveChildren(), p.venues().d2c().rfqCount()));
      System.out.println("venue outcomes " + p.venues().d2c().outcomes());
      assertTrue(desk.desk().quoting().quotesSent() > 0, "quotes sent");
      assertTrue(desk.desk().quoting().fills() > 0, "RFQ fills received");
      assertTrue(desk.hedgesRouted() > 0, "hedges routed");
      assertTrue(desk.hedgeFills() > 0, "hedge fills on the CLOB");
      assertTrue(p.feed().frames() > 0, "book updates through the feed");

      final HttpClient client = HttpClient.newHttpClient();
      final HttpResponse<String> kill =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + "/control/kill"))
                  .header("X-Control-Token", "token")
                  .header("X-Requested-By", "9001")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, kill.statusCode());
      p.step(2_000_000_000L, STEP);
      final String status = p.statusJson();
      System.out.println("after kill: " + status);
      assertTrue(status.contains("\"killSwitch\":true"), status);
      assertTrue(status.contains("\"liveQuotes\":0"), status);
      assertEquals(0, desk.liveChildren(), "router children working after the kill");
      assertTrue(p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty(), "orders resting at the CLOB after the kill");
      assertTrue(p.killSwitchMessages() >= 1, "KillSwitch on the control ring");
      assertNull(p.failure());
    } finally {
      p.close();
    }

    final PositionKeeper keeper =
        JournalFeed.keeper(groupDir, "in", "out", JournalFeed.books(p.universe()), JournalFeed.catalogue(desk.pricing()));
    final ReconResult recon = VenueReconciliation.run(keeper, p.venues().d2c(), p.venues().clob());
    System.out.println(String.format(Locale.ROOT,
        "keeper: %d trades, %d positions; reconciliation ours %d, theirs %d, matched %d, breaks %d",
        keeper.trades().size(), keeper.positions().size(), recon.oursCount(), recon.theirsCount(), recon.matched(),
        recon.breaks().size()));
    assertTrue(keeper.positions().size() > 0, "positions kept");
    assertEquals(0, recon.breaks().size(), "reconciliation breaks: " + recon.breaks());
    assertEquals(recon.theirsCount(), recon.matched());

    final ReplayResult replay = TradingProcessMain.replayGroup(groupDir);
    System.out.println("replay: " + replay);
    assertTrue(replay.matches(), "replay: " + replay);
  }
}
