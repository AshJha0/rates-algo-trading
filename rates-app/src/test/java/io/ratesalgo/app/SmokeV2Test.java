package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.app.sim.V2Reconciliation;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.TradeRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v2 desk profile on a seeded simulator v2 day: the D2C venue, two Treasury CLOBs and the
 * futures book of one market model, a FIX session to each, three book feeds with trade prints,
 * the pricing v2 curve round, and one USD loop running pricing v2, strategy v2 and the ladder
 * router with its journals. The desk logs on at 09:00 New York, two hours into the session, and
 * runs for thirty simulated minutes; then the kill switch is pulled.
 *
 * <p>Why 09:00 and not the version 1 smoke test's 08:25: the desk pulls its quotes from a minute
 * before a scheduled release to half a minute after it and quotes wider for five minutes more
 * (08:30 and 10:00, {@code QuotingConfig}). On this seed the twenty minutes from 08:25 held nine
 * RFQs, of which the desk won two Treasuries and no swap, and nothing reached the hedger. That is
 * the desk behaving as declared, and it exercises almost none of the wiring. The window was moved
 * for coverage of the wiring; no P&amp;L was looked at in either window.
 *
 * <p>The assertions are structural: what was wired works end to end. No P&amp;L is read here or
 * printed, and nothing in the profile was chosen by looking at one; the strategy's parameters are
 * the ones docs/research/STRATEGY_V2.md declares, and whether they make money is what the
 * pre-registered runs R5-001 to R5-007 are for. The counts printed are a record of this seed, not
 * a result.
 *
 * <p>Observed on this seed, recorded and not asserted: the factor hedge planner hedged every
 * residual in cash Treasuries, on both CLOBs, and never chose the future, which costs more per
 * unit of level risk at the declared half-spread of half a tick. The future's path is therefore
 * exercised by the second test, in which the Treasury feeds go silent and the future is the only
 * hedge left; that is a venue outage, not a change to the strategy.
 */
class SmokeV2Test {

  static final long SECOND = 1_000_000_000L;
  static final long MINUTE = 60 * SECOND;
  static final long STEP = 20_000_000L;
  static final long LOG_ON = 120 * MINUTE;
  static final long RUN = 30 * MINUTE;
  static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 91_007);

  @TempDir Path dir;

  private static long[] venueFills(final V2DeskProcess p) {
    final long[] fills = new long[3];
    for (int v = 0; v < 3; v++) {
      fills[v] = V2Reconciliation.fills(p.venues().books().get(v), AppIds.COMP_ID);
    }
    return fills;
  }

  /** Quotes the desk sent, from its outbound journal: {on swaps, on Treasuries}. */
  private static long[] quotesByProduct(final V2DeskProcess p) {
    final long[] quotes = new long[2];
    final QuoteDecoder quote = new QuoteDecoder();
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    JournalFeed.feed(
        p.groupDir(), "in", "out",
        (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
          if (templateId == QuoteDecoder.TEMPLATE_ID) {
            quote.wrapAndApplyHeader(buffer, offset, header);
            final ProductType type = p.desk().binding().directory().byId(quote.instrumentId()).type();
            quotes[type == ProductType.SWAP ? 0 : 1]++;
          }
        });
    return quotes;
  }

  /** The kill switch is pulled and nothing of ours is left working anywhere. */
  private static void killAndCheck(final V2DeskProcess p) {
    final DeskGroup desk = p.desk();
    assertTrue(p.kill(9001));
    p.step(2 * SECOND, STEP);
    assertFalse(desk.desk().killSwitch().isActive(), "the kill switch is pulled");
    assertEquals(0, desk.desk().quoting().liveQuotes(), "quotes live after the kill");
    assertEquals(0, desk.liveChildren(), "router children working after the kill");
    for (final ClobSimulator book : p.venues().books()) {
      assertTrue(book.liveOrders(AppIds.COMP_ID).isEmpty(), "orders resting at " + book.compId() + " after the kill");
    }
    assertTrue(p.killSwitchMessages() >= 1, "KillSwitch on the control ring");
    final String status = p.statusJson();
    assertTrue(status.contains("\"killSwitch\":true") && status.contains("\"liveQuotes\":0")
        && status.contains("\"liveChildren\":0"), status);
  }

  /** Positions from the journals reconcile with every venue, and a replay sends the same bytes. */
  private static void reconcileAndReplay(final V2DeskProcess p, final long liveDigest, final String label) {
    final PositionKeeper keeper = V2Reconciliation.keeper(p.groupDir(), p.desk().binding());
    final ReconResult recon = V2Reconciliation.run(keeper, p.venues());
    System.out.println(String.format(Locale.ROOT,
        "keeper v2 %s: %d trades, %d positions; reconciliation ours %d, theirs %d, matched %d, breaks %d",
        label, keeper.trades().size(), keeper.positions().size(), recon.oursCount(), recon.theirsCount(),
        recon.matched(), recon.breaks().size()));
    assertTrue(keeper.positions().size() > 0, "positions kept");
    assertEquals(0, recon.breaks().size(), "reconciliation breaks: " + recon.breaks());
    assertEquals(recon.theirsCount(), recon.matched());

    // every input entered the loop as a journaled message: a fresh desk fed the journals sends
    // the same bytes, and stands where the live desk stood
    final V2DeskProcess.Replayed replay = V2DeskProcess.replayDesk(p.groupDir(), p.profile());
    System.out.println("replay v2 " + label + ": " + replay.result());
    assertTrue(replay.result().matches(), "replay: " + replay.result());
    assertTrue(replay.result().outboundProduced() > 0);
    assertEquals(liveDigest, replay.desk().stateDigest(p.universe().instrumentIds()),
        "the desk rebuilt from its journals is the desk as it stood");
  }

  @Test
  void aSimulatorV2DayRunsEndToEndOnTheV2Profile() {
    final V2DeskProcess p = V2DeskProcess.start(new V2DeskProcess.Options(dir, SEED, DeskProfile.v2(), LOG_ON));
    final DeskGroup desk = p.desk();
    final QuotingEngine quoting = desk.desk().quoting();
    final long liveDigest;
    long swapsWon = 0;
    long bondsWon = 0;
    try {
      p.step(RUN, STEP);
      final long[] fills = venueFills(p);
      for (final TradeRecord t : p.venues().d2c().trades()) {
        if (AppIds.COMP_ID.equals(t.winner())) {
          if (desk.binding().directory().byId(t.instrumentId()).type() == ProductType.SWAP) {
            swapsWon++;
          } else {
            bondsWon++;
          }
        }
      }
      System.out.println(String.format(Locale.ROOT,
          "smoke v2: rfqs %d, quotes %d, fills %d (swaps %d, bonds %d), passes %d, hedges routed %d, hedge fills %d, "
              + "venue fills CLOB1 %d CLOB2 %d FUT %d, book frames %d/%d/%d, prints %d/%d/%d, curve rounds %d of %d inputs, "
              + "reprices %d, pushed %d, trade prints applied %d, feed gaps %d, breaches %d, passive hedges %d, "
              + "venue rfqs %d",
          quoting.rfqs(), quoting.quotesSent(), quoting.fills(), swapsWon, bondsWon, quoting.passes(),
          desk.hedgesRouted(), desk.hedgeFills(), fills[0], fills[1], fills[2], p.feed(0).frames(),
          p.feed(1).frames(), p.feed(2).frames(), p.feed(0).prints(), p.feed(1).prints(), p.feed(2).prints(),
          p.curve().rounds(), p.curve().inputsPerRound(), desk.pricingV2().reprices(), desk.repricesPushed(),
          desk.tradePrints(), desk.feedGaps(), p.breachMessages(), desk.desk().hedger(CurrencyCode.USD).passiveSent(),
          p.venues().d2c().rfqCount()));
      System.out.println("venue outcomes " + p.venues().d2c().outcomes());
      System.out.println(String.format(Locale.ROOT,
          "strategy v2: cover observations %d, covers recorded %d, markout clients %d, pulls %d of which on a fair-value move %d,"
              + " expiries %d, late fills %d, responses for quotes already released %d",
          quoting.cover().observations(), quoting.coversRecorded(), quoting.markouts().clients(), quoting.pulls(),
          quoting.fairMovePulls(), quoting.expiries(), quoting.lateFills(), quoting.unmatchedResponses()));
      final String before = p.statusJson();
      System.out.println("status v2: " + before);

      // pricing v2 is fed whole by the simulator
      assertEquals(14, p.curve().inputsPerRound(), "the build set: a fixing, five futures, eight swaps");
      assertEquals(0, desk.pricingV2().unfedInputs(), "no curve input is seeded");
      assertTrue(desk.pricingV2().reprices() > 0 && desk.repricesPushed() > 0, "the curve repriced on the loop");
      assertTrue(desk.pricingV2().reprices() <= p.curve().rounds() + 2, "one reprice a round: the rounds are conflated");
      assertEquals(0, desk.pricingV2().rejectedInputs(), "curve inputs rejected");
      assertTrue(before.contains("\"killSwitch\":false") && before.contains("\"unfedCurveInputs\":0"), before);
      assertTrue(before.contains("\"venue\":104,\"usable\":true"), before);
      // quoting, and winning
      assertTrue(quoting.quotesSent() > 0, "quotes sent");
      assertTrue(quoting.fills() > 0, "an RFQ won");
      assertEquals(quoting.fills(), swapsWon + bondsWon, "the venue's register and the desk agree on what it won");
      // strategy v2 is fed: covers from the venue's outcomes, the store kept per client
      assertTrue(quoting.cover().observations() > 0, "Done and TradedAway outcomes reached the cover model");
      assertTrue(quoting.markouts().clients() > 0, "client markouts are kept");
      assertEquals(0, desk.orphanWorkingParents(), "the hedger and the routers agree on what is working");
      // hedging through the ladder router, on both Treasury CLOBs
      assertTrue(desk.hedgesRouted() > 0, "hedges routed");
      assertTrue(desk.hedgeFills() > 0, "hedge fills");
      assertTrue(fills[0] > 0 && fills[1] > 0, "hedge fills on both Treasury CLOBs");
      assertTrue(desk.hedgeAvailable(p.universe().futureIds()[0]), "the future is a hedge the planner may use");
      // market data: every feed whole, prints on every venue
      for (int v = 0; v < 3; v++) {
        assertTrue(p.feed(v).synced(), "feed " + v + " is whole");
        assertTrue(p.feed(v).frames() > 0, "book updates from venue " + v);
        assertTrue(p.feed(v).prints() > 0, "trade prints from venue " + v);
        assertEquals(0, p.feed(v).gaps());
        assertTrue(desk.venueUsable(desk.venueId(v)));
      }
      assertTrue(desk.tradePrints() > 0);
      assertEquals(0, desk.feedGaps());
      assertEquals(0, p.inbound().undelivered());
      // no limit was met and nothing went wrong at a venue
      assertEquals(0, p.breachMessages(), "risk limit breaches");
      assertEquals(0, desk.venueOverfills());
      assertEquals(0, desk.hedgesFailed());
      assertEquals(0, desk.unroutedReports());
      assertFalse(desk.desk().risk().flattenOnly());

      killAndCheck(p);
      assertFalse(Files.exists(dir.resolve("markouts.end")), "the store is written when the session ends, not before");
      liveDigest = desk.stateDigest(p.universe().instrumentIds());
    } finally {
      p.close();
    }
    assertTrue(Files.exists(dir.resolve("markouts.end")), "the session's end saved the client markouts");
    final long[] quoted = quotesByProduct(p);
    System.out.println("quotes v2 by product: swaps " + quoted[0] + ", Treasuries " + quoted[1]);
    assertTrue(quoted[0] > 0, "quotes on swaps");
    assertTrue(quoted[1] > 0, "quotes on Treasuries");
    assertEquals(quoting.quotesSent(), quoted[0] + quoted[1]);
    reconcileAndReplay(p, liveDigest, "day");
  }

  /**
   * A venue outage: three minutes after the desk logs on, both Treasury book feeds go silent without a gap. Their
   * books go stale, the routers stop using those venues, the hedge planner is left with the
   * future, and the desk carries on hedging there.
   */
  @Test
  void whenTheTreasuryFeedsGoSilentTheDeskHedgesInTheFuture() {
    final V2DeskProcess p = V2DeskProcess.start(new V2DeskProcess.Options(dir, SEED, DeskProfile.v2(), LOG_ON));
    final DeskGroup desk = p.desk();
    final int future = p.universe().futureIds()[0];
    final int bond = p.universe().bondIds()[2];
    final long liveDigest;
    try {
      p.step(3 * MINUTE, STEP);
      assertTrue(desk.hedgeAvailable(bond) && desk.hedgeAvailable(future));
      p.pauseFeed(0, true);
      p.pauseFeed(1, true);
      // the age limit, and the two seconds the router gives a child it already sent
      p.step(DeskGroup.MAX_BOOK_AGE_NANOS + 3 * SECOND, STEP);
      assertFalse(desk.hedgeAvailable(bond), "silent Treasury books are no hedge");
      assertTrue(desk.hedgeAvailable(future), "the futures book is alive");
      final long[] atOutage = venueFills(p);
      final long routedAtOutage = desk.hedgesRouted();

      p.step(RUN - 3 * MINUTE, STEP);

      final long[] fills = venueFills(p);
      System.out.println(String.format(Locale.ROOT,
          "smoke v2 outage: rfqs %d, quotes %d, fills %d, passes %d, hedges routed %d (%d before the outage), hedge fills %d,"
              + " venue fills CLOB1 %d CLOB2 %d FUT %d (at the outage %d/%d/%d), breaches %d, stale exclusions on the 10-year note %d",
          desk.desk().quoting().rfqs(), desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(),
          desk.desk().quoting().passes(), desk.hedgesRouted(), routedAtOutage, desk.hedgeFills(), fills[0], fills[1],
          fills[2], atOutage[0], atOutage[1], atOutage[2], p.breachMessages(), desk.router(2).staleExclusions()));
      assertEquals(atOutage[0], fills[0], "no fill on the first CLOB once its book was stale");
      assertEquals(atOutage[1], fills[1], "no fill on the second CLOB once its book was stale");
      assertTrue(desk.hedgesRouted() > routedAtOutage, "the hedger kept routing");
      assertTrue(fills[2] > atOutage[2], "hedge fills on the futures venue");
      assertTrue(desk.desk().risk().position(future) != 0, "a futures position, in face");
      assertEquals(0, desk.desk().risk().position(future) % p.universe().future(future).contractMinor(), "whole contracts");
      assertTrue(desk.venueUsable(AppIds.FUTURES_VENUE));
      assertEquals(0, desk.feedGaps(), "silence is not a gap");
      assertEquals(0, p.breachMessages(), "risk limit breaches");
      assertEquals(0, desk.venueOverfills());
      assertEquals(0, desk.orphanWorkingParents());

      killAndCheck(p);
      liveDigest = desk.stateDigest(p.universe().instrumentIds());
    } finally {
      p.close();
    }
    reconcileAndReplay(p, liveDigest, "outage");
  }
}
