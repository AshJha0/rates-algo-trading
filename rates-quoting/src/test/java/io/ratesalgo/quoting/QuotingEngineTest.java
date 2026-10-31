package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillState;
import io.ratesalgo.risk.RiskEngine;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class QuotingEngineTest {

  private static final double TOL = 1e-9;

  private final TestMarket m = new TestMarket();
  private final QuotingEngine engine = m.desk.quoting();
  private final RiskEngine risk = m.desk.risk();
  private long rfq = 1;

  private RfqDecision ask(final int client, final int id, final Side clientSide, final long qty) {
    return engine.onQuoteRequest(7, rfq++, client, id, clientSide, qty, 4, 0L, 0L);
  }

  private static QuoteDecoder quote(final DirectBuffer b) {
    final QuoteDecoder d = new QuoteDecoder();
    d.wrap(b, MessageHeaderEncoder.ENCODED_LENGTH, QuoteDecoder.BLOCK_LENGTH, QuoteDecoder.SCHEMA_VERSION);
    return d;
  }

  @Test
  void firmQuoteToABuyingClientIsAnAskAboveFairWithAReservation() {
    assertEquals(RfqDecision.FIRM, ask(2, TestMarket.UST10, Side.Buy, 25 * FACE_1M));
    final RfqOutcome o = engine.outcome();
    assertEquals(Side.Sell, o.ourSide());
    assertEquals(25 * FACE_1M, o.quantity());
    assertTrue(Decimal9.toDouble(o.price()) > o.fair(), "ask above fair");
    assertTrue(o.halfSpreadBp() >= 0.1 && o.halfSpreadBp() <= 10.0);
    assertTrue(o.winProbability() > 0.0 && o.winProbability() < 1.0);
    assertEquals(1, m.gateway.size());
    final QuoteDecoder q = quote(m.gateway.message(0));
    assertEquals(0L, q.bidQuantity());
    assertEquals(25 * FACE_1M, q.askQuantity());
    assertEquals(o.price(), q.askPrice().mantissa());
    assertEquals(m.clock.epochNanos() + 5_000_000_000L, q.validUntilNanos());
    assertEquals(m.clock.epochNanos(), q.referenceNanos());
    assertEquals(25 * FACE_1M, risk.pendingSell(TestMarket.UST10));
    assertEquals(1, engine.liveQuotes());
  }

  @Test
  void longBookQuotesABetterOfferAndAWorseBid() {
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final double flatAsk = engine.outcome().distanceBp();
    ask(2, TestMarket.UST10, Side.Sell, 10 * FACE_1M);
    final double flatBid = engine.outcome().distanceBp();
    risk.onFill(TestMarket.UST10, 200 * FACE_1M, m.fair.fairMid(TestMarket.UST10));
    ask(1, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final RfqOutcome longAsk = engine.outcome();
    assertTrue(longAsk.skewBp() > 0.0, "long bond risk gives positive skew");
    assertTrue(longAsk.distanceBp() < flatAsk, "offer improves when long");
    ask(1, TestMarket.UST10, Side.Sell, 10 * FACE_1M);
    assertTrue(engine.outcome().distanceBp() > flatBid, "bid worsens when long");
    assertTrue(Math.abs(longAsk.skewBp()) <= engine.config().maxSkewBp() + TOL);
  }

  @Test
  void skewIsBoundedAndNeverCrossesFairByMoreThanTheImproveLimit() {
    risk.onFill(TestMarket.UST10, 300 * FACE_1M, m.fair.fairMid(TestMarket.UST10));
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final RfqOutcome o = engine.outcome();
    assertEquals(engine.config().maxSkewBp(), o.skewBp(), TOL);
    assertTrue(o.distanceBp() >= -engine.config().maxImproveBp() - TOL);
  }

  @Test
  void passesForEachRuleInOrder() {
    assertEquals(RfqDecision.FIRM, engine.onQuoteRequest(7, 99, 2, TestMarket.UST5, Side.Buy, FACE_1M, 4, 0L, 0L));
    assertEquals(RfqDecision.PASS, engine.onQuoteRequest(7, 99, 2, TestMarket.UST5, Side.Buy, FACE_1M, 4, 0L, 0L));
    assertEquals(PassReason.DUPLICATE, engine.outcome().reason());
    ask(4, TestMarket.UST5, Side.Buy, FACE_1M);
    assertEquals(PassReason.CLIENT_BLOCKED, engine.outcome().reason());
    ask(2, 999, Side.Buy, FACE_1M);
    assertEquals(PassReason.UNKNOWN_INSTRUMENT, engine.outcome().reason());
    engine.onQuoteRequest(7, rfq++, 2, TestMarket.UST2, Side.Buy, FACE_1M, 4, m.clock.epochNanos(), 0L);
    assertEquals(PassReason.EXPIRED_REQUEST, engine.outcome().reason());
    ask(2, TestMarket.UST5, Side.Buy, FACE_1M);
    assertEquals(PassReason.THROTTLED, engine.outcome().reason());
    m.fair.remove(TestMarket.SW5);
    ask(2, TestMarket.SW5, Side.Buy, FACE_1M);
    assertEquals(PassReason.NO_FAIR_VALUE, engine.outcome().reason());
    m.clock.advance(2_500_000_000L);
    ask(2, TestMarket.UST30, Side.Buy, FACE_1M);
    assertEquals(PassReason.STALE_INPUT, engine.outcome().reason());
    m.refreshAll(m.clock.epochNanos());
    m.desk.health().trackVenue(7);
    m.desk.health().onSession(7, false);
    ask(2, TestMarket.UST30, Side.Buy, FACE_1M);
    assertEquals(PassReason.UNHEALTHY, engine.outcome().reason());
    m.desk.health().onSession(7, true);
    m.desk.killSwitch().trigger(KillReason.Manual, 0, 1);
    ask(2, TestMarket.UST30, Side.Buy, FACE_1M);
    assertEquals(PassReason.KILLED, engine.outcome().reason());
    assertTrue(engine.passes() >= 9);
  }

  @Test
  void sizeIsReducedToTheRiskHeadroomAndPassedWhenNoneIsLeft() {
    risk.limits().maxPosition(TestMarket.UST10, 30 * FACE_1M);
    ask(2, TestMarket.UST10, Side.Buy, 100 * FACE_1M);
    assertEquals(30 * FACE_1M, engine.outcome().quantity());
    assertEquals(100 * FACE_1M, engine.outcome().requestedQuantity());
    ask(1, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    assertEquals(PassReason.NO_RISK_CAPACITY, engine.outcome().reason());
  }

  @Test
  void quotesExpireAtTheirTimeToLiveAndReleaseTheirReservation() {
    ask(2, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    m.clock.advance(4_999_999_999L);
    m.refreshAll(m.clock.epochNanos());
    engine.poll(m.clock.epochNanos());
    assertEquals(1, engine.heldQuotes());
    m.clock.advance(1L);
    engine.poll(m.clock.epochNanos());
    assertEquals(0, engine.heldQuotes());
    assertEquals(0L, risk.pendingSell(TestMarket.UST10));
    assertEquals(1, engine.expiries());
  }

  @Test
  void quotesArePulledWhenTheirInputsGoStaleOrHealthFails() {
    m.desk.health().requireHeartbeat(42, m.clock.epochNanos());
    ask(2, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    m.clock.advance(2_000_000_001L);
    engine.poll(m.clock.epochNanos());
    assertEquals(0, engine.liveQuotes());
    assertEquals(1, engine.heldQuotes(), "reservation held until expiry or answer");
    assertEquals(25 * FACE_1M, risk.pendingSell(TestMarket.UST10));
    final QuoteWithdrawDecoder c = new QuoteWithdrawDecoder();
    c.wrap(m.gateway.message(1), MessageHeaderEncoder.ENCODED_LENGTH, QuoteWithdrawDecoder.BLOCK_LENGTH, 0);
    assertEquals(CancelReason.StaleInput, c.reason());

  }

  @Test
  void heartbeatLossPullsEverything() {
    final TestMarket hm =
        new TestMarket(
            TestMarket.standardLimits(),
            new QuotingConfig().heartbeatTimeoutNanos(1_000_000_000L),
            io.ratesalgo.hedge.Hedger.config());
    hm.desk.health().requireHeartbeat(42, hm.clock.epochNanos());
    hm.desk.quoting().onQuoteRequest(7, 1, 2, TestMarket.UST5, Side.Buy, 25 * FACE_1M, 4, 0L, 0L);
    assertEquals(1, hm.desk.quoting().liveQuotes());
    hm.clock.advance(1_100_000_000L);
    hm.desk.quoting().poll(hm.clock.epochNanos());
    assertEquals(0, hm.desk.quoting().liveQuotes(), "heartbeat silent for 1.1 s");
    final QuoteWithdrawDecoder c = new QuoteWithdrawDecoder();
    c.wrap(hm.gateway.message(1), MessageHeaderEncoder.ENCODED_LENGTH, QuoteWithdrawDecoder.BLOCK_LENGTH, 0);
    assertEquals(CancelReason.HeartbeatLoss, c.reason());
    hm.desk.health().onHeartbeat(42, hm.clock.epochNanos());
    assertTrue(hm.desk.health().healthy(hm.clock.epochNanos()));
  }

  @Test
  void feedGapAndSessionLossPullEverything() {
    m.desk.health().trackVenue(9);
    ask(2, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    m.desk.health().onBookSequence(9, 1);
    m.desk.health().onBookSequence(9, 3);
    engine.poll(m.clock.epochNanos());
    assertEquals(0, engine.liveQuotes());
    m.desk.health().onFeedRecovered(9);
    assertEquals(RfqDecision.FIRM, ask(2, TestMarket.UST5, Side.Buy, FACE_1M));
    m.desk.health().onSession(9, false);
    engine.poll(m.clock.epochNanos());
    assertEquals(0, engine.liveQuotes());
    assertFalse(m.desk.health().healthy(m.clock.epochNanos()));
    assertEquals(CancelReason.SessionLoss, m.desk.health().reason());
  }

  @Test
  void doneBooksTheFillAndTeachesTheModelIncludingTheCover() {
    ask(2, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    final RfqOutcome o = engine.outcome();
    final double b0 = engine.hitRate().coefficient(HitRateModel.INTERCEPT);
    final long cover = o.price() + Decimal9.round(0.5 * risk.quotePerBp(TestMarket.UST10));
    engine.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), 25 * FACE_1M, cover);
    assertEquals(-25 * FACE_1M, risk.position(TestMarket.UST10));
    assertEquals(0L, risk.pendingSell(TestMarket.UST10));
    assertTrue(engine.hitRate().coefficient(HitRateModel.INTERCEPT) > b0);
    assertEquals(2, engine.hitRate().updates());
    assertEquals(0.5, engine.meanCoverDistanceBp(), 1e-6);
    assertEquals(0, engine.heldQuotes());

    ask(2, TestMarket.UST5, Side.Buy, 25 * FACE_1M);
    final double b1 = engine.hitRate().coefficient(HitRateModel.INTERCEPT);
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.TradedAway, 0L, 0L, 0L);
    assertTrue(engine.hitRate().coefficient(HitRateModel.INTERCEPT) < b1);
    engine.onQuoteResponse(12345L, QuoteStatus.Done, 0L, 0L, 0L);
    assertEquals(1, engine.unmatchedResponses());
  }

  @Test
  void markoutsFeedTheToxicityTableAndWidenThatClient() {
    ask(1, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    final double before = engine.outcome().costBp();
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 25 * FACE_1M, 0L);
    for (int i = 0; i < 6; i++) {
      m.clock.advance(61_000_000_000L);
      m.shift(CurrencyCode.USD, -8.0); // prices rise after we sold: adverse for us
      engine.poll(m.clock.epochNanos());
      ask(1, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
      engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 25 * FACE_1M, 0L);
    }
    m.clock.advance(61_000_000_000L);
    m.refreshAll(m.clock.epochNanos());
    engine.poll(m.clock.epochNanos());
    assertTrue(engine.toxicity().meanMarkoutBp(1) < 0.0, "mean " + engine.toxicity().meanMarkoutBp(1) + " n " + engine.toxicity().observations(1) + " fills " + engine.fills() + " dist " + engine.outcome().distanceBp());
    assertTrue(engine.toxicity().observations(1) >= 6);
    ask(1, TestMarket.UST10, Side.Buy, 25 * FACE_1M);
    assertTrue(engine.outcome().costBp() > before, "toxic client pays more: " + engine.outcome().reason() + " " + engine.outcome().costBp() + " vs " + before + " tox " + engine.toxicity().adverseBp(1));
  }

  @Test
  void subjectTierIsMarkedWithZeroValidity() {
    assertEquals(RfqDecision.SUBJECT, ask(3, TestMarket.UST10, Side.Sell, 10 * FACE_1M));
    final QuoteDecoder q = quote(m.gateway.message(0));
    assertEquals(0L, q.validUntilNanos());
    assertEquals(10 * FACE_1M, q.bidQuantity());
  }

  @Test
  void tierTwoIsWiderThanTierOneAndTiersNeedTwoPeople() {
    ask(1, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final double t1 = engine.outcome().halfSpreadBp();
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    assertTrue(engine.outcome().halfSpreadBp() > t1);
    final TierTableControl control = m.desk.tiers();
    final TierTable v2 = new TierTable(2, List.of(new ClientTier(1, 2.0, FACE_1M * 50, true, false)), Map.of(), 0);
    assertThrows(IllegalArgumentException.class, () -> control.propose(TestMarket.tierTable(1), 5));
    control.propose(v2, 5);
    assertEquals(2, control.pendingVersion());
    assertFalse(control.approve(2, 5));
    assertTrue(control.approve(2, 6));
    assertSame(v2, control.active());
    assertThrows(IllegalStateException.class, () -> control.approve(3, 6));
  }

  @Test
  void signalsNeedAPassingValidationRecord() {
    final FairValueEngine fv = m.desk.fairValue();
    final long now = m.clock.epochNanos();
    final FairValueSignal half = (id, t) -> 0.5;
    assertFalse(fv.install(half, null, now));
    assertFalse(fv.install(half, new ValidationRecord("s", 1, 2, 3, 100, 3.0, 1, 2), now), "too few observations");
    assertFalse(fv.install(half, new ValidationRecord("s", 5, 2, 3, 1000, 3.0, 1, 2), now), "overlaps the fit");
    assertFalse(fv.install(half, new ValidationRecord("s", 1, 2, 3, 1000, 3.0, 1, 1), now), "self-approved");
    assertFalse(fv.install(half, new ValidationRecord("s", 1, 2, now + 1, 1000, 3.0, 1, 2), now), "not finished");
    assertFalse(fv.install(half, new ValidationRecord("s", 1, 2, 3, 1000, 1.5, 1, 2), now), "weak");
    ask(2, TestMarket.SW10, Side.Buy, 10 * FACE_1M);
    final double before = engine.outcome().fair();
    assertTrue(fv.install(half, new ValidationRecord("s", 1, 2, 3, 1000, 3.0, 1, 2), now));
    ask(1, TestMarket.SW10, Side.Buy, 10 * FACE_1M);
    assertEquals(before + 0.5 * 0.01, engine.outcome().fair(), 1e-9);
    fv.uninstall();
    assertNull(fv.validation());
  }

  @Test
  void swapFairValueFollowsTheFuturesMicroprice() {
    ask(2, TestMarket.SW10, Side.Buy, 10 * FACE_1M);
    final double flat = engine.outcome().fair();
    final double ty = m.levelOf(TestMarket.TY);
    final long now = m.clock.epochNanos();
    // heavy bid on TY: microprice above mid, futures yields about to fall
    m.book.set(TestMarket.TY, Decimal9.round(ty - 1.0 / 128), 90 * FACE_1M, Decimal9.round(ty + 1.0 / 128), 10 * FACE_1M, now);
    ask(1, TestMarket.SW10, Side.Buy, 10 * FACE_1M);
    assertTrue(engine.outcome().fair() < flat, "swap fair rate lowered");
  }

  @Test
  void bondFairValueIsTheImbalanceWeightedMicroprice() {
    final double lvl = m.levelOf(TestMarket.UST5);
    final long now = m.clock.epochNanos();
    m.book.set(TestMarket.UST5, Decimal9.round(lvl - 0.01), 30 * FACE_1M, Decimal9.round(lvl + 0.01), 10 * FACE_1M, now);
    ask(2, TestMarket.UST5, Side.Buy, FACE_1M);
    assertEquals((lvl - 0.01) * 0.25 + (lvl + 0.01) * 0.75, engine.outcome().fair(), 1e-9);
  }

  @Test
  void volatilityAndScheduledEventsWiden() {
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final double base = engine.outcome().halfSpreadBp();
    engine.realisedVolatility(CurrencyCode.USD, 12.0);
    ask(1, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    final double vol = engine.outcome().halfSpreadBp();
    assertTrue(vol > base * 0.8 + 0.2, "volatility widening applied");
    engine.realisedVolatility(CurrencyCode.USD, 6.0);
    final long now = m.clock.epochNanos();
    engine.config().addEvent(now - 1, now + 1_000_000_000L, 2.0);
    ask(2, TestMarket.UST5, Side.Buy, 10 * FACE_1M);
    final double event = engine.outcome().halfSpreadBp();
    m.clock.advance(1_000_000_000L);
    m.refreshAll(m.clock.epochNanos());
    ask(2, TestMarket.UST5, Side.Sell, 10 * FACE_1M);
    assertTrue(event > engine.outcome().halfSpreadBp() * 1.5);
    assertThrows(IllegalArgumentException.class, () -> engine.config().addEvent(5, 4, 2.0));
  }

  @Test
  void riskRejectionPassesAndSendsABreach() {
    risk.limits().priceCollarBp(0.05);
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    assertEquals(PassReason.RISK_REJECT, engine.outcome().reason());
    assertEquals(io.ratesalgo.messages.sbe.LimitKind.PriceCollar.value(), engine.outcome().limitRejection());
    assertEquals(0, m.gateway.size());
    assertEquals(1, m.control.size());
  }

  @Test
  void killSwitchPullsQuotesAndBlocksUntilTwoPeopleReset() {
    ask(2, TestMarket.UST10, Side.Buy, 10 * FACE_1M);
    ask(2, TestMarket.UST5, Side.Buy, 10 * FACE_1M);
    assertTrue(m.desk.killSwitch().trigger(KillReason.Manual, 0, 11));
    assertEquals(0, engine.liveQuotes());
    assertEquals(3, m.hedgeSink.cancelAllCount(), "one hedger per currency");
    assertEquals(KillState.KILLED, m.desk.killSwitch().state());
    assertEquals(1, m.control.count(io.ratesalgo.messages.sbe.KillSwitchDecoder.TEMPLATE_ID));
    m.desk.killSwitch().requestReset(11);
    assertFalse(m.desk.killSwitch().approveReset(11, true));
    assertTrue(m.desk.killSwitch().approveReset(12, true));
    assertEquals(RfqDecision.FIRM, ask(1, TestMarket.UST30, Side.Buy, FACE_1M));
  }

  @Test
  void handlerDecodesRequestsAndRejectsForeignSchemas() {
    final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
    new QuoteRequestEncoder()
        .wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
        .venueId(7)
        .rfqId(555)
        .clientId(2)
        .instrumentId(TestMarket.UST10)
        .side(Side.Sell)
        .quantity(10 * FACE_1M)
        .currency("USD")
        .dealerCount((short) 5)
        .receivedNanos(m.clock.epochNanos())
        .expiryNanos(m.clock.epochNanos() + 1_000_000_000L);
    assertTrue(m.desk.handler().onMessage(b, 0, 1L));
    assertEquals(RfqDecision.FIRM, engine.outcome().decision());
    assertEquals(m.clock.epochNanos() + 1_000_000_000L, engine.outcome().validUntilNanos());
    b.putShort(4, (short) 9);
    assertThrows(IllegalStateException.class, () -> m.desk.handler().onMessage(b, 0, 2L));
  }
}
