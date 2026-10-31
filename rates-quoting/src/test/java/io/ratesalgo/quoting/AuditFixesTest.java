package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.risk.RiskEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PNL_AUDIT items 3, 4, 8, 9, 12 and 13 in the RFQ engine and the package quoter. */
class AuditFixesTest {

  private final TestMarket m = new TestMarket();
  private final QuotingEngine engine = m.desk.quoting();
  private final RiskEngine risk = m.desk.risk();
  private long rfq = 1;

  private RfqDecision ask(final QuotingEngine e, final int client, final int id, final Side clientSide, final long qty) {
    return e.onQuoteRequest(7, rfq++, client, id, clientSide, qty, 4, 0L, 0L);
  }

  private static CancelReason withdrawReason(final TestMarket market, final int i) {
    final QuoteWithdrawDecoder c = new QuoteWithdrawDecoder();
    c.wrap(market.gateway.message(i), MessageHeaderEncoder.ENCODED_LENGTH, QuoteWithdrawDecoder.BLOCK_LENGTH, 0);
    return c.reason();
  }

  /** What the recorder saw. */
  private static final class Recorder implements QuotingEngine.RfqRecordListener {
    final List<String> decisions = new ArrayList<>();
    final List<Object[]> results = new ArrayList<>();

    @Override
    public void onDecision(final RfqOutcome o) {
      decisions.add(o.rfqId() + " " + o.decision() + " " + o.reason() + " c" + o.clientId() + " i" + o.instrumentId());
    }

    @Override
    public void onResult(
        final long rfqId, final long quoteId, final QuoteStatus status, final long ourPrice, final long coverPrice,
        final double coverGapBp, final int rank) {
      results.add(new Object[] {rfqId, quoteId, status, ourPrice, coverPrice, coverGapBp, rank});
    }
  }

  // ------------------------------------------------------------- item 3

  @Test
  void everyDecisionAndEveryResultIsRecordedWithCoverAndRank() {
    final Recorder rec = new Recorder();
    engine.recorder(rec);
    final double qpb = risk.quotePerBp(TestMarket.UST10);
    // a pass is recorded with its reason
    assertEquals(RfqDecision.PASS, ask(engine, 2, 99_999, Side.Buy, 10 * FACE_1M));
    assertEquals("1 PASS UNKNOWN_INSTRUMENT c2 i99999", rec.decisions.get(0));
    // a Done whose cover was better for the client than our offer: rank 2, behind by 0.3 bp
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    assertEquals("2 FIRM NONE c2 i" + TestMarket.UST10, rec.decisions.get(1));
    final long ours = engine.outcome().price();
    final long better = ours - Decimal9.round(0.3 * qpb);
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, ours, 10 * FACE_1M, better);
    Object[] r = rec.results.get(0);
    assertEquals(2L, r[0]);
    assertEquals(QuoteStatus.Done, r[2]);
    assertEquals(0.3, (double) r[5], 1e-6);
    assertEquals(2, r[6]);
    assertEquals(1, engine.wonBehindCover());
    assertEquals(0.3, engine.meanWonBehindCoverBp(), 1e-6);
    // a loss with a disclosed price worse than ours: rank 1 (we were best and still lost)
    ask(engine, 1, TestMarket.UST5, Side.Sell, 10 * FACE_1M);
    final long bid = engine.outcome().price();
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.TradedAway, 0L, 0L, bid - Decimal9.round(0.1 * qpb));
    r = rec.results.get(1);
    assertEquals(QuoteStatus.TradedAway, r[2]);
    assertTrue((double) r[5] < 0.0);
    assertEquals(1, r[6]);
    // a loss without a cover: rank unknown
    ask(engine, 1, TestMarket.UST30, Side.Sell, 10 * FACE_1M);
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.TradedAway, 0L, 0L, 0L);
    r = rec.results.get(2);
    assertTrue(Double.isNaN((double) r[5]));
    assertEquals(0, r[6]);
    assertEquals(2, engine.coversRecorded());
    assertEquals(4, rec.decisions.size());
    engine.recorder(null);
    ask(engine, 1, 99_999, Side.Buy, FACE_1M);
    assertEquals(4, rec.decisions.size(), "recording off");
  }

  // ------------------------------------------------------------- item 4

  /**
   * The markout is measured from the mid at the trade: with no market move after the fill it is
   * zero, where the traded price would have shown the captured half spread as a gain.
   */
  @Test
  void toxicityIsMarkedFromTheMidAtTheTrade() {
    ask(engine, 1, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    final double half = engine.outcome().distanceBp();
    assertTrue(half > 0.05, "a spread was captured: " + half);
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 25 * FACE_1M, 0L);
    m.clock.advance(61_000_000_000L);
    m.refreshAll(m.clock.epochNanos());
    engine.poll(m.clock.epochNanos());
    assertEquals(1, engine.toxicity().observations(1));
    assertEquals(0.0, engine.toxicity().meanMarkoutBp(1), 1e-6);
    // an adverse move smaller than the captured half spread is still charged
    ask(engine, 1, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 25 * FACE_1M, 0L);
    m.clock.advance(61_000_000_000L);
    m.shift(CurrencyCode.USD, -0.5 * half); // prices rise after we sold
    engine.poll(m.clock.epochNanos());
    assertTrue(engine.toxicity().meanMarkoutBp(1) < 0.0, "mean " + engine.toxicity().meanMarkoutBp(1));
    assertTrue(engine.toxicity().adverseBp(1) > 0.0);
  }

  // ------------------------------------------------------------- item 8

  @Test
  void aFairValueMoveAgainstALiveQuoteWithdrawsIt() {
    assertEquals(0.25, engine.config().fairMoveWithdrawBp, 0.0);
    ask(engine, 2, TestMarket.UST10, Side.Buy, 25 * FACE_1M); // we offer
    ask(engine, 2, TestMarket.UST5, Side.Sell, 25 * FACE_1M); // we bid
    assertEquals(2, engine.liveQuotes());
    // a small move keeps both
    m.clock.advance(100_000_000L);
    m.shift(CurrencyCode.USD, -0.1);
    engine.poll(m.clock.epochNanos());
    assertEquals(2, engine.liveQuotes());
    // prices rise by more than the threshold: the offer is withdrawn, the bid (now safer) stays
    m.clock.advance(100_000_000L);
    m.shift(CurrencyCode.USD, -0.5);
    engine.poll(m.clock.epochNanos());
    assertEquals(1, engine.liveQuotes());
    assertEquals(1, engine.fairMovePulls());
    assertEquals(CancelReason.AdverseSignal, withdrawReason(m, 2));
    assertEquals(25 * FACE_1M, risk.pendingSell(TestMarket.UST10), "reservation held until expiry or answer");
    engine.poll(m.clock.epochNanos());
    assertEquals(1, engine.fairMovePulls(), "withdrawn once");
  }

  @Test
  void theFairMoveCheckCanBeTurnedOff() {
    final TestMarket off =
        new TestMarket(TestMarket.standardLimits(), new QuotingConfig().fairMoveWithdrawBp(0.0), Hedger.config());
    ask(off.desk.quoting(), 2, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    off.clock.advance(100_000_000L);
    off.shift(CurrencyCode.USD, -2.0);
    off.desk.quoting().poll(off.clock.epochNanos());
    assertEquals(1, off.desk.quoting().liveQuotes());
  }

  // ------------------------------------------------------------- item 9

  @Test
  void theReleaseCalendarIsOnByDefault() {
    final QuotingConfig cfg = new QuotingConfig();
    assertTrue(cfg.releases() > 900, "two releases on each weekday of 2026 and 2027: " + cfg.releases());
    // 2026-11-02 08:30 New York is 13:30Z (daylight saving ended on 1 November)
    final long release = Instant.parse("2026-11-02T13:30:00Z").getEpochSecond() * 1_000_000_000L;
    assertFalse(cfg.inReleasePull(release - 60_000_000_001L));
    assertTrue(cfg.inReleasePull(release - 60_000_000_000L));
    assertTrue(cfg.inReleasePull(release + 29_999_999_999L));
    assertFalse(cfg.inReleasePull(release + 30_000_000_000L));
    assertEquals(QuotingConfig.E3_RELEASE_MULTIPLIER, cfg.eventMultiplier(release + 31_000_000_000L), 0.0);
    assertEquals(1.0, cfg.eventMultiplier(release + 300_000_000_000L), 0.0);
    // 10:00 New York, and none on a Saturday or on 1 January
    assertTrue(cfg.inReleasePull(Instant.parse("2026-11-02T15:00:00Z").getEpochSecond() * 1_000_000_000L));
    assertFalse(cfg.inReleasePull(Instant.parse("2026-10-31T12:30:00Z").getEpochSecond() * 1_000_000_000L));
    assertFalse(cfg.inReleasePull(Instant.parse("2027-01-01T13:30:00Z").getEpochSecond() * 1_000_000_000L));
    assertEquals(4, QuotingConfig.usReleaseSchedule(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)).length);
    assertEquals(0, new QuotingConfig().releaseCalendar(new long[0]).releases());
  }

  @Test
  void quotesArePulledAndRfqsPassedAroundARelease() {
    final long release = TestMarket.T0 + 120_000_000_000L;
    final TestMarket rm =
        new TestMarket(
            TestMarket.standardLimits(), new QuotingConfig().releaseCalendar(new long[] {release}), Hedger.config());
    final QuotingEngine e = rm.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(e, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    final double normal = e.outcome().halfSpreadBp();
    rm.clock.advance(58_000_000_000L);
    rm.refreshAll(rm.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(e, 2, TestMarket.UST5, Side.Buy, 10 * FACE_1M));
    rm.clock.advance(2_000_000_000L);
    rm.refreshAll(rm.clock.epochNanos());
    e.poll(rm.clock.epochNanos());
    assertEquals(0, e.liveQuotes(), "pulled 60 s before the release");
    assertEquals(CancelReason.AdverseSignal, withdrawReason(rm, rm.gateway.size() - 1));
    assertEquals(RfqDecision.PASS, ask(e, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    assertEquals(PassReason.RELEASE_WINDOW, e.outcome().reason());
    // 31 s after: quoting again, wider by the E3 multiplier
    rm.clock.advance(91_000_000_000L);
    rm.refreshAll(rm.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(e, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    assertTrue(e.outcome().halfSpreadBp() > 1.5 * normal, e.outcome().halfSpreadBp() + " vs " + normal);
  }

  // ------------------------------------------------------------- item 12

  @Test
  void packagesPassTheSameControlsAsSingleRfqs() {
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.Result r = new PackageQuoter.Result();
    final PackageQuoter.PackageRequest curve =
        pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10);
    // health
    m.desk.health().trackVenue(9);
    m.desk.health().onBookSequence(9, 1);
    m.desk.health().onBookSequence(9, 3);
    assertFalse(pq.quote(curve, 2, 4, r).quoted());
    assertEquals(PassReason.UNHEALTHY, r.reason());
    m.desk.health().onFeedRecovered(9);
    // quoted: every leg reserved for the time to live
    assertTrue(pq.quote(curve, 2, 4, r).quoted(), "" + r.reason());
    assertEquals(m.clock.epochNanos() + 5_000_000_000L, r.validUntilNanos());
    assertEquals(100 * FACE_1M, risk.pendingSell(TestMarket.SW2), "client pays 2s: we sell the payer");
    assertEquals(-curve.clientQuantity(1), risk.pendingBuy(TestMarket.SW10));
    assertEquals(1, pq.heldPackages());
    // throttle
    assertFalse(pq.quote(curve, 2, 4, new PackageQuoter.Result()).quoted());
    // expiry releases the reservation
    m.clock.advance(5_000_000_000L);
    m.refreshAll(m.clock.epochNanos());
    pq.poll(m.clock.epochNanos());
    assertEquals(0, pq.heldPackages());
    assertEquals(0L, risk.pendingSell(TestMarket.SW2));
    assertEquals(1, pq.expiries());
    // a late Done is still booked, to the net ladder the hedger reads
    assertTrue(pq.onResponse(r.packageId(), true));
    assertEquals(-100 * FACE_1M, risk.position(TestMarket.SW2));
    assertEquals(-curve.clientQuantity(1), risk.position(TestMarket.SW10));
    assertEquals(1, pq.lateFills());
    assertFalse(pq.onResponse(r.packageId(), true), "booked once");
    // kill
    m.clock.advance(1_000_000_000L);
    m.refreshAll(m.clock.epochNanos());
    final PackageQuoter.Result live = pq.quote(curve, 3, 4, new PackageQuoter.Result());
    assertTrue(live.quoted(), "" + live.reason());
    assertTrue(m.desk.killSwitch().trigger(KillReason.Manual, 0, 11));
    pq.onKill(KillReason.Manual, m.clock.epochNanos());
    assertEquals(1, pq.heldPackages(), "withdrawn, reservation held until answered");
    assertFalse(pq.quote(curve, 1, 4, r).quoted());
    assertEquals(PassReason.KILLED, r.reason());
    assertTrue(pq.onResponse(live.packageId(), false));
    assertEquals(0L, risk.pendingSell(TestMarket.SW2));
  }

  @Test
  void packagesArePassedInAReleaseWindowAndWidenedAfterIt() {
    final long release = TestMarket.T0 + 30_000_000_000L;
    final TestMarket rm =
        new TestMarket(
            TestMarket.standardLimits(), new QuotingConfig().releaseCalendar(new long[] {release}), Hedger.config());
    final PackageQuoter pq = rm.desk.packages();
    final PackageQuoter.PackageRequest fly = pq.butterfly(TestMarket.SW2, TestMarket.SW5, 50 * FACE_1M, TestMarket.SW10);
    final PackageQuoter.Result r = new PackageQuoter.Result();
    assertFalse(pq.quote(fly, 1, 3, r).quoted());
    assertEquals(PassReason.RELEASE_WINDOW, r.reason());
    rm.clock.advance(61_000_000_000L);
    rm.refreshAll(rm.clock.epochNanos());
    assertTrue(pq.quote(fly, 1, 3, r).quoted(), "" + r.reason());
    final double widened = r.charge();
    rm.clock.advance(300_000_000_000L);
    rm.refreshAll(rm.clock.epochNanos());
    assertTrue(pq.quote(fly, 1, 3, r).quoted(), "" + r.reason());
    assertTrue(widened > r.charge(), widened + " vs " + r.charge());
  }

  /** A DV01-neutral curve package is charged its spread on the curve risk, not on its legs' PV01. */
  @Test
  void packagesArePricedOnNetRisk() {
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.Result r =
        pq.quote(pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10), 2, 4,
            new PackageQuoter.Result());
    assertTrue(r.quoted());
    final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
    r.netLadder(net);
    assertTrue(r.netRiskPv01() > 0.0 && r.netRiskPv01() < 0.5 * net.gross(), r.netRiskPv01() + " vs " + net.gross());
    assertTrue(r.spreadChargeSum() < r.legChargeSum(), r.spreadChargeSum() + " vs " + r.legChargeSum());
    assertTrue(r.charge() < r.legChargeSum());
  }

  // ------------------------------------------------------------- item 13

  /** A Done after local expiry reaches risk, and so the hedger, which hedges from the ladder. */
  @Test
  void aLateDoneReachesTheHedger() {
    final TestMarket hm =
        new TestMarket(TestMarket.standardLimits(), new QuotingConfig(), Hedger.config().triggerStdDev(1.0));
    final QuotingEngine e = hm.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(e, 2, TestMarket.UST10, Side.Buy, 200 * FACE_1M));
    final long id = e.outcome().quoteId();
    final long price = e.outcome().price();
    hm.clock.advance(6_000_000_000L);
    hm.refreshAll(hm.clock.epochNanos());
    e.poll(hm.clock.epochNanos());
    assertEquals(0, e.heldQuotes());
    final Hedger h = hm.desk.hedger(CurrencyCode.USD);
    assertEquals(0, h.onTimer(hm.clock.epochNanos()), "nothing to hedge yet");
    e.onQuoteResponse(id, QuoteStatus.Done, price, 200 * FACE_1M, 0L);
    assertEquals(1, e.lateFills());
    assertTrue(h.onTimer(hm.clock.epochNanos()) > 0, "the hedger hedges the late fill");
    assertFalse(hm.hedgeSink.parents().isEmpty());
  }
}
