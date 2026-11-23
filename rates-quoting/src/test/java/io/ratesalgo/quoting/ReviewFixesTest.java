package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrderWithdrawDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.RiskEngine;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-11-23: the acceptance check (Q7), the DV01 limits in the quote path (Q9), the opt-in
 * cover learning and volatility widening (Q3, Q11), and the package and CLOB quoter holes.
 */
class ReviewFixesTest {

  private static final long SECOND = 1_000_000_000L;
  private long rfq = 1;

  private RfqDecision ask(final QuotingEngine engine, final int client, final int id, final Side clientSide, final long qty) {
    return engine.onQuoteRequest(7, rfq++, client, id, clientSide, qty, 4, 0L, 0L);
  }

  private static TestMarket market(final QuotingConfig config) {
    return new TestMarket(TestMarket.standardLimits(), config, Hedger.config());
  }

  // ---------------------------------------------------------------- Q7

  @Test
  void aDoneAtAnotherPriceIsFlaggedAndStillBookedByDefault() {
    final TestMarket m = market(new QuotingConfig());
    final QuotingEngine engine = m.desk.quoting();
    final long[] told = new long[3];
    final double[] bp = new double[1];
    engine.recorder(
        new QuotingEngine.RfqRecordListener() {
          @Override
          public void onDecision(final RfqOutcome outcome) {}

          @Override
          public void onResult(
              final long rfqId, final long quoteId, final QuoteStatus status, final long ourPrice, final long coverPrice,
              final double coverGapBp, final int rank) {}

          @Override
          public void onPriceMismatch(
              final long rfqId, final long quoteId, final long quoted, final long traded, final double againstUsBp,
              final boolean booked) {
            told[0]++;
            told[1] = quoted;
            told[2] = booked ? 1 : 0;
            bp[0] = againstUsBp;
          }
        });
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    final long quoted = engine.outcome().price();
    // the venue reports the sale half a basis point below our offer
    final long traded = quoted - Decimal9.round(0.5 * m.desk.risk().quotePerBp(TestMarket.UST10));
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, traded, 10 * FACE_1M, 0L);
    assertEquals(1L, engine.priceMismatches());
    assertEquals(0L, engine.rejectedDones());
    assertEquals(1L, told[0]);
    assertEquals(quoted, told[1]);
    assertEquals(1L, told[2], "booked");
    assertEquals(0.5, bp[0], 1e-6, "against us");
    assertEquals(-10 * FACE_1M, m.desk.risk().position(TestMarket.UST10), "the venue's trade is in the book");
    assertEquals(1L, engine.fills());

    // a Done at the quoted price is not flagged
    m.clock.advance(SECOND);
    m.refreshAll(m.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 10 * FACE_1M, 0L);
    assertEquals(1L, engine.priceMismatches());
    assertEquals(2L, engine.fills());
  }

  @Test
  void aMismatchedDoneIsNotBookedWhenTheDeskRejectsThem() {
    final TestMarket m = market(new QuotingConfig().rejectMismatchedDone(true).acceptPriceToleranceBp(0.1));
    final QuotingEngine engine = m.desk.quoting();
    final double qpb = m.desk.risk().quotePerBp(TestMarket.UST10);
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Sell, 10 * FACE_1M));
    final long updates = engine.hitRate().updates();
    // we bid; the venue says we paid 0.3 bp more
    engine.onQuoteResponse(
        engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price() + Decimal9.round(0.3 * qpb), 10 * FACE_1M, 0L);
    assertEquals(1L, engine.priceMismatches());
    assertEquals(1L, engine.rejectedDones());
    assertEquals(0L, engine.fills());
    assertEquals(0L, m.desk.risk().position(TestMarket.UST10), "not booked");
    assertEquals(0L, m.desk.risk().pendingBuy(TestMarket.UST10), "reservation released");
    assertEquals(updates, engine.hitRate().updates(), "not a win");
    assertEquals(0, engine.heldQuotes());

    // inside the tolerance it is a normal fill
    m.clock.advance(SECOND);
    m.refreshAll(m.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Sell, 10 * FACE_1M));
    engine.onQuoteResponse(
        engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price() + Decimal9.round(0.05 * qpb), 10 * FACE_1M, 0L);
    assertEquals(1L, engine.priceMismatches());
    assertEquals(1L, engine.fills());
  }

  @Test
  void aDoneForNothingIsNotAFillAndNotAWin() {
    final TestMarket m = market(new QuotingConfig());
    final QuotingEngine engine = m.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    final long updates = engine.hitRate().updates();
    final long wins = engine.hitRate().wins();
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), 0L, 0L);
    assertEquals(1L, engine.emptyDones());
    assertEquals(0L, engine.fills());
    assertEquals(updates, engine.hitRate().updates());
    assertEquals(wins, engine.hitRate().wins());
    assertEquals(0L, m.desk.risk().pendingSell(TestMarket.UST10));
    assertEquals(0L, m.desk.risk().position(TestMarket.UST10));
    assertEquals(0, engine.heldQuotes());
    // a negative quantity is the same thing
    m.clock.advance(SECOND);
    m.refreshAll(m.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, engine.outcome().price(), -5L, 0L);
    assertEquals(2L, engine.emptyDones());

    // and so is one that arrives after the quote expired here
    m.clock.advance(SECOND);
    m.refreshAll(m.clock.epochNanos());
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    final long late = engine.outcome().quoteId();
    final long price = engine.outcome().price();
    m.clock.advance(6 * SECOND);
    engine.poll(m.clock.epochNanos());
    assertEquals(1L, engine.expiries());
    engine.onQuoteResponse(late, QuoteStatus.Done, price, 0L, 0L);
    assertEquals(3L, engine.emptyDones());
    assertEquals(0L, engine.lateFills());
    assertEquals(0L, m.desk.risk().position(TestMarket.UST10));
  }

  @Test
  void aLateDoneAtAnotherPriceIsFlaggedToo() {
    final TestMarket m = market(new QuotingConfig().rejectMismatchedDone(true));
    final QuotingEngine engine = m.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
    final long late = engine.outcome().quoteId();
    final long price = engine.outcome().price();
    m.clock.advance(6 * SECOND);
    engine.poll(m.clock.epochNanos());
    engine.onQuoteResponse(late, QuoteStatus.Done, price - 1_000_000L, 10 * FACE_1M, 0L);
    assertEquals(1L, engine.priceMismatches());
    assertEquals(0L, engine.lateFills());
    assertEquals(0L, m.desk.risk().position(TestMarket.UST10));
  }

  @Test
  void theFairMovePullCanBeAFractionOfTheQuotedDistance() {
    // absolute threshold 0.25 bp (the default): a 0.24 bp move against a 1 bp quote stays live
    final TestMarket abs = market(new QuotingConfig().minHalfSpreadBp(1.0).maxHalfSpreadBp(1.0).kappa(0.0));
    assertEquals(RfqDecision.FIRM, ask(abs.desk.quoting(), 2, TestMarket.SW10, Side.Buy, 10 * FACE_1M));
    moveSwap(abs, 0.24);
    abs.desk.quoting().poll(abs.clock.epochNanos());
    assertEquals(0L, abs.desk.quoting().fairMovePulls());

    // a fifth of the quoted distance: the same move pulls it
    final TestMarket frac =
        market(new QuotingConfig().minHalfSpreadBp(1.0).maxHalfSpreadBp(1.0).kappa(0.0).fairMoveWithdrawFraction(0.2));
    final QuotingEngine engine = frac.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.SW10, Side.Buy, 10 * FACE_1M));
    assertEquals(1.0, engine.outcome().distanceBp(), 1e-9);
    moveSwap(frac, 0.12);
    engine.poll(frac.clock.epochNanos());
    assertEquals(0L, engine.fairMovePulls(), "0.12 bp is under a fifth of the distance");
    moveSwap(frac, 0.12);
    engine.poll(frac.clock.epochNanos());
    assertEquals(1L, engine.fairMovePulls(), "0.24 bp is over it");
    assertEquals(1, frac.gateway.count(QuoteWithdrawDecoder.TEMPLATE_ID));
    assertThrows(IllegalArgumentException.class, () -> new QuotingConfig().fairMoveWithdrawFraction(-0.1));
    assertThrows(IllegalArgumentException.class, () -> new QuotingConfig().acceptPriceToleranceBp(Double.NaN));
  }

  /** The client buys the payer; fair rising is against our offer. */
  private static void moveSwap(final TestMarket m, final double bp) {
    final int i = m.directory.indexOf(TestMarket.SW10);
    m.level[i] += bp * m.desk.risk().quotePerBp(TestMarket.SW10);
    m.refresh(TestMarket.SW10, m.clock.epochNanos());
  }

  // ---------------------------------------------------------------- Q9

  @Test
  void theQuoteIsSizedInsideThePerRfqDv01Limit() {
    final TestMarket m =
        new TestMarket(TestMarket.standardLimits().rfqDv01(20_000.0), new QuotingConfig(), Hedger.config());
    final QuotingEngine engine = m.desk.quoting();
    assertEquals(RfqDecision.FIRM, ask(engine, 1, TestMarket.UST10, Side.Buy, 100 * FACE_1M));
    final double dv01 = m.desk.risk().dv01(TestMarket.UST10, engine.outcome().quantity());
    assertTrue(dv01 <= 20_000.0 && dv01 > 19_000.0, "sized down to " + dv01);
    assertEquals(100 * FACE_1M, engine.outcome().requestedQuantity());
  }

  @Test
  void aClientIsHeldToItsDv01LimitAcrossLiveQuotesAndFills() {
    final TestMarket m =
        new TestMarket(TestMarket.standardLimits().clientDv01(30_000.0), new QuotingConfig().requoteThrottleNanos(0L), Hedger.config());
    final QuotingEngine engine = m.desk.quoting();
    final RiskEngine risk = m.desk.risk();
    assertEquals(RfqDecision.FIRM, ask(engine, 1, TestMarket.UST10, Side.Buy, 25 * FACE_1M)); // 20.5k live
    final long first = engine.outcome().quoteId();
    final long firstPrice = engine.outcome().price();
    assertEquals(20_500.0, risk.clientOpenDv01(1), 1e-6);
    assertEquals(RfqDecision.FIRM, ask(engine, 1, TestMarket.UST10, Side.Sell, 25 * FACE_1M));
    assertEquals(11 * FACE_1M, engine.outcome().quantity(), "9.5k of the limit left: 11mm");
    final long second = engine.outcome().quoteId();
    assertEquals(RfqDecision.FIRM, ask(engine, 1, TestMarket.UST2, Side.Buy, 25 * FACE_1M));
    assertEquals(2 * FACE_1M, engine.outcome().quantity(), "480 left: 2mm of the 2Y");
    final long third = engine.outcome().quoteId();
    assertEquals(RfqDecision.PASS, ask(engine, 1, TestMarket.UST5, Side.Buy, 25 * FACE_1M));
    assertEquals(PassReason.NO_RISK_CAPACITY, engine.outcome().reason(), "100 left: under the smallest quote");
    assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST5, Side.Buy, 25 * FACE_1M), "another client is free");
    assertEquals(25 * FACE_1M, engine.outcome().quantity());

    engine.onQuoteResponse(first, QuoteStatus.Done, firstPrice, 25 * FACE_1M, 0L);
    engine.onQuoteResponse(second, QuoteStatus.TradedAway, 0L, 0L, 0L);
    engine.onQuoteResponse(third, QuoteStatus.TradedAway, 0L, 0L, 0L);
    assertEquals(20_500.0, risk.clientDealtDv01(1), 1e-6);
    assertEquals(0.0, risk.clientOpenDv01(1), 1e-6);
    // expiry releases the live DV01 as well
    assertEquals(RfqDecision.FIRM, ask(engine, 1, TestMarket.UST2, Side.Buy, 25 * FACE_1M));
    assertTrue(risk.clientOpenDv01(1) > 0.0);
    m.clock.advance(6 * SECOND);
    engine.poll(m.clock.epochNanos());
    assertEquals(0.0, risk.clientOpenDv01(1), 1e-6);
    assertEquals(0.0, risk.clientOpenDv01(2), 1e-6);
  }

  // ---------------------------------------------------------------- Q3, Q11 (opt-in)

  @Test
  void coverGapsReachTheSpreadModelOnlyWhenCoverLearningIsOn() {
    for (final boolean on : new boolean[] {false, true}) {
      final TestMarket m = market(new QuotingConfig().requoteThrottleNanos(0L).coverLearning(on));
      final QuotingEngine engine = m.desk.quoting();
      final double qpb = m.desk.risk().quotePerBp(TestMarket.UST10);
      final double[] gaps = {0.2, 0.6, 1.0};
      for (final double gap : gaps) {
        assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.UST10, Side.Buy, 10 * FACE_1M));
        final long ours = engine.outcome().price();
        // we offered; the cover offer was worse for the client by the gap
        engine.onQuoteResponse(engine.outcome().quoteId(), QuoteStatus.Done, ours, 10 * FACE_1M, ours + Decimal9.round(gap * qpb));
      }
      assertEquals(on ? 3L : 0L, engine.spread().coverGaps());
      if (on) {
        assertEquals(0.6, engine.spread().meanCoverGapBp(), 1e-6);
        assertTrue(engine.spread().coverSigmaBp() > 0.2, "the optimiser now averages over the cover spread");
      } else {
        assertEquals(0.0, engine.spread().coverSigmaBp(), 0.0);
      }
      assertEquals(0.6, engine.meanCoverDistanceBp(), 1e-6, "the statistic is kept either way");
    }
  }

  @Test
  void wideningAfterTheClampSurvivesATightCap() {
    final long now = TestMarket.T0;
    for (final boolean after : new boolean[] {false, true}) {
      final QuotingConfig config =
          new QuotingConfig()
              .minHalfSpreadBp(0.05)
              .maxHalfSpreadBp(0.5)
              .winnersCurseBp(0.0)
              .addEvent(now - SECOND, now + 60 * SECOND, 2.0)
              .widenAfterClamp(after);
      final TestMarket m = market(config);
      final QuotingEngine engine = m.desk.quoting();
      engine.realisedVolatility(CurrencyCode.USD, 10.0); // 4 bp over the 6 bp baseline: +0.2 bp
      assertEquals(RfqDecision.FIRM, ask(engine, 2, TestMarket.SW10, Side.Buy, 10 * FACE_1M));
      final double half = engine.outcome().halfSpreadBp();
      if (after) {
        assertTrue(half > 0.5, "the event and the volatility widen past the cap: " + half);
        assertTrue(half <= 0.5 * 2.0 + 0.2 + 1e-9, "by the widening and no more: " + half);
      } else {
        assertEquals(0.5, half, 1e-9, "the cap swallows the widening");
      }
    }
  }

  @Test
  void realisedVolatilityIsFedFromTheReferenceInstrumentWhenConfigured() {
    final QuotingConfig config =
        new QuotingConfig().volatilityReference(CurrencyCode.USD, TestMarket.SW10).volatilitySampling(SECOND, 28_800 * SECOND, 0.9, 5);
    final TestMarket m = market(config);
    final QuotingEngine engine = m.desk.quoting();
    assertEquals(6.0, engine.realisedVolatility(CurrencyCode.USD), 0.0, "baseline before any sample");
    // a quiet market: the estimate never goes under the baseline
    for (int s = 0; s < 10; s++) {
      m.clock.advance(SECOND);
      m.refreshAll(m.clock.epochNanos());
      engine.poll(m.clock.epochNanos());
    }
    assertEquals(6.0, engine.realisedVolatility(CurrencyCode.USD), 0.0);
    // 0.1 bp a second either way is 0.1 * sqrt(28800) = 17 bp a day
    for (int s = 0; s < 60; s++) {
      m.clock.advance(SECOND);
      final int i = m.directory.indexOf(TestMarket.SW10);
      m.level[i] += (s % 2 == 0 ? 0.1 : -0.1) * m.desk.risk().quotePerBp(TestMarket.SW10);
      m.refreshAll(m.clock.epochNanos());
      engine.poll(m.clock.epochNanos());
    }
    assertEquals(17.0, engine.realisedVolatility(CurrencyCode.USD), 0.5);
    assertEquals(6.0, engine.realisedVolatility(CurrencyCode.EUR), 0.0, "no reference, no estimate");
    // a reference that goes stale restarts the series instead of reading the gap as a move
    m.clock.advance(10 * SECOND);
    engine.poll(m.clock.epochNanos());
    assertEquals(17.0, engine.realisedVolatility(CurrencyCode.USD), 0.5);

    // off by default: nothing moves the figure
    final TestMarket off = market(new QuotingConfig());
    for (int s = 0; s < 40; s++) {
      off.clock.advance(SECOND);
      off.shift(CurrencyCode.USD, s % 2 == 0 ? 1.0 : -1.0);
      off.desk.quoting().poll(off.clock.epochNanos());
    }
    assertEquals(6.0, off.desk.quoting().realisedVolatility(CurrencyCode.USD), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new QuotingConfig().volatilitySampling(0, 1, 0.5, 1));
    assertThrows(IllegalArgumentException.class, () -> new QuotingConfig().volatilitySampling(1, 1, 1.0, 1));
  }

  // ---------------------------------------------------------------- package quoter

  @Test
  void aPackageReservationExpiresOnTheEnginesPoll() {
    final TestMarket m = market(new QuotingConfig());
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.Result r = new PackageQuoter.Result();
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    assertTrue(m.desk.risk().pendingSell(TestMarket.SW2) > 0);
    assertTrue(m.desk.risk().clientOpenDv01(1) > 0.0);
    m.clock.advance(5 * SECOND);
    // no second package arrives; the desk's housekeeping polls the engine, as DeskGroup does
    m.desk.quoting().poll(m.clock.epochNanos());
    assertEquals(1L, pq.expiries());
    assertEquals(0, pq.heldPackages());
    assertEquals(0L, m.desk.risk().pendingSell(TestMarket.SW2));
    assertEquals(0L, m.desk.risk().pendingBuy(TestMarket.SW10));
    assertEquals(0.0, m.desk.risk().clientOpenDv01(1), 1e-6);
  }

  @Test
  void theInventoryTermIsBoundedEitherWay() {
    for (final double sign : new double[] {1.0, -1.0}) {
      final TestMarket m = market(new QuotingConfig().kappa(2e-4).maxSkewBp(0.5));
      // a large 2Y position: the 2s10s steepener or flattener moves a lot of inventory risk
      m.desk.risk().onFill(TestMarket.SW2, (sign > 0 ? 400 : -400) * FACE_1M, m.fair.fairMid(TestMarket.SW2));
      final PackageQuoter pq = m.desk.packages();
      final PackageQuoter.Result r = new PackageQuoter.Result();
      assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
      final double legPv01 = m.desk.risk().dv01(TestMarket.SW2, 100 * FACE_1M) * 2.0;
      assertTrue(Math.abs(r.inventoryCharge()) <= 0.5 * legPv01 * 1.02, "inventory " + r.inventoryCharge());
      assertTrue(Math.abs(r.inventoryCharge()) >= 0.5 * legPv01 * 0.9, "the bound binds in this book: " + r.inventoryCharge());
      // the client buys the 2Y payer from us: that sheds a long 2Y book and adds to a short one
      assertEquals(-sign, Math.signum(r.inventoryCharge()), 0.0);
    }
  }

  @Test
  void theSamePackageIsThrottledWhateverTheSingleRfqThrottleSays() {
    final TestMarket m = market(new QuotingConfig().requoteThrottleNanos(0L));
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.Result r = new PackageQuoter.Result();
    final PackageQuoter.PackageRequest curve = pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10);
    assertTrue(pq.quote(curve, 1, 3, r).quoted());
    assertFalse(pq.quote(curve, 1, 3, r).quoted(), "the same package again at once");
    assertEquals(PassReason.THROTTLED, r.reason());
    assertEquals(1L, pq.throttledPackages());
    // the same legs listed the other way round are the same package
    final PackageQuoter.PackageRequest reversed = pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW10, curve.clientQuantity(1), TestMarket.SW2);
    assertFalse(pq.quote(reversed, 1, 3, r).quoted());
    // the opposite direction, another package and another client are not throttled
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, -100 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW5, 100 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    assertTrue(pq.quote(curve, 2, 3, r).quoted());
    m.clock.advance(250_000_000L);
    m.refreshAll(m.clock.epochNanos());
    assertTrue(pq.quote(curve, 1, 3, r).quoted(), "after the interval");
    pq.packageThrottleNanos(0L);
    assertTrue(pq.quote(curve, 1, 3, r).quoted(), "turned off");
    assertThrows(IllegalArgumentException.class, () -> pq.packageThrottleNanos(-1L));
  }

  // ---------------------------------------------------------------- CLOB quoter

  @Test
  void anUnansweredCancelIsSentAgainAndThenEscalated() {
    final TestMarket m = market(new QuotingConfig());
    final ClobQuoter clob =
        new ClobQuoter(
            m.directory, m.desk.risk(), m.desk.fairValue(), m.book, m.gateway, m.clock,
            ClobQuoter.config().cancelRetryNanos(SECOND).maxCancelRetries(2), 2 * SECOND);
    m.desk.killSwitch().register(clob);
    final long[] stuck = new long[3];
    clob.stuckCancelListener(
        (instrumentId, clOrdId, bid, leaves) -> {
          stuck[0]++;
          stuck[1] = clOrdId;
          stuck[2] = leaves;
        });
    clob.enable(TestMarket.UST2);
    clob.update(TestMarket.UST2, m.clock.epochNanos());
    final long bid = clob.restingOrder(TestMarket.UST2, true);
    final long ask = clob.restingOrder(TestMarket.UST2, false);
    final long reserved = clob.reserved(TestMarket.UST2, true);
    m.desk.killSwitch().trigger(KillReason.Manual, 0, 1);
    assertEquals(2L, clob.cancelsSent());
    // the venue acknowledges the ask and loses the bid's cancel
    clob.onExecution(ask, 0L, 0L, OrdStatus.Cancelled);
    m.clock.advance(SECOND - 1);
    clob.poll(m.clock.epochNanos());
    assertEquals(0L, clob.cancelRetries(), "not yet");
    m.clock.advance(1);
    clob.poll(m.clock.epochNanos());
    assertEquals(1L, clob.cancelRetries(), "resent while the kill switch is on");
    assertEquals(3, m.gateway.count(OrderWithdrawDecoder.TEMPLATE_ID));
    final OrderWithdrawDecoder d = new OrderWithdrawDecoder();
    d.wrap(m.gateway.message(m.gateway.size() - 1), 8, OrderWithdrawDecoder.BLOCK_LENGTH, 0);
    assertEquals(bid, d.origClOrdId());
    assertEquals(io.ratesalgo.messages.sbe.CancelReason.KillSwitch, d.reason());
    m.clock.advance(SECOND);
    clob.poll(m.clock.epochNanos());
    assertEquals(0L, stuck[0]);
    m.clock.advance(SECOND);
    clob.poll(m.clock.epochNanos());
    assertEquals(1L, stuck[0], "escalated after two resends");
    assertEquals(bid, stuck[1]);
    assertEquals(reserved, stuck[2]);
    assertEquals(1L, clob.stuckCancels());
    assertEquals(reserved, m.desk.risk().pendingBuy(TestMarket.UST2), "still reserved: it may be live");
    m.clock.advance(SECOND);
    clob.poll(m.clock.epochNanos());
    assertEquals(1L, stuck[0], "told once; the cancel keeps going out");
    assertEquals(4L, clob.cancelRetries());
    // the venue finally answers
    clob.onExecution(bid, 0L, 0L, OrdStatus.Cancelled);
    assertFalse(clob.cancelPending(TestMarket.UST2, true));
    assertEquals(0L, m.desk.risk().pendingBuy(TestMarket.UST2));
    m.clock.advance(SECOND);
    clob.poll(m.clock.epochNanos());
    assertEquals(4L, clob.cancelRetries());
    clob.stuckCancelListener(null);
    assertThrows(IllegalArgumentException.class, () -> ClobQuoter.config().cancelRetryNanos(0));
    assertThrows(IllegalArgumentException.class, () -> ClobQuoter.config().maxCancelRetries(-1));
  }

  @Test
  void aRejectedCancelFreesTheSideToDecideAgain() {
    final TestMarket m = market(new QuotingConfig());
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.UST10);
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    final long bid = clob.restingOrder(TestMarket.UST10, true);
    m.clock.advance(100_000_000L);
    m.shift(CurrencyCode.USD, 0.4);
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertTrue(clob.cancelPending(TestMarket.UST10, true));
    final long cancels = clob.cancelsSent();
    clob.onCancelRejected(bid);
    assertFalse(clob.cancelPending(TestMarket.UST10, true));
    assertEquals(1L, clob.cancelRejects());
    assertEquals(bid, clob.restingOrder(TestMarket.UST10, true), "still ours");
    clob.onCancelRejected(bid);
    clob.onCancelRejected(424242L);
    assertEquals(1L, clob.cancelRejects(), "only a pending cancel can be rejected");
    // the order is still off the target: the next update cancels it again
    m.clock.advance(100_000_000L);
    m.refreshAll(m.clock.epochNanos());
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertTrue(clob.cancelPending(TestMarket.UST10, true));
    assertTrue(clob.cancelsSent() > cancels);
  }

  @Test
  void theDeskPollsTheClobQuoterThroughTheEngine() {
    final TestMarket m = market(new QuotingConfig());
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.UST2);
    clob.update(TestMarket.UST2, m.clock.epochNanos());
    m.desk.killSwitch().trigger(KillReason.Manual, 0, 1);
    m.clock.advance(SECOND);
    m.desk.quoting().poll(m.clock.epochNanos());
    assertEquals(2L, clob.cancelRetries(), "both cancels resent from the engine's housekeeping");
    assertThrows(
        IllegalStateException.class,
        () -> {
          for (int i = 0; i < 4; i++) {
            m.desk.quoting().attach(now -> {});
          }
        });
  }
}
