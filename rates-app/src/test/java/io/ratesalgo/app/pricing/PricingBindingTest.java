package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.risk.Dv01Ladder;
import io.ratesalgo.analytics.risk.KeyRateRisk;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.market.LoopBook;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.router.FrameSink;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.junit.jupiter.api.Test;

/**
 * The pricing binding against P1's analytics used directly (API_SPEC.md Section 18.3). Curve
 * inputs reach the binding as CurveInput messages through a desk's event loop, each one an
 * incremental rebuild; a curve bootstrapped from scratch from the same final quotes then prices
 * every swap template, and the binding must agree to 1e-12 on the par rate and on the key-rate
 * ladder mapped onto the eight tenors.
 */
class PricingBindingTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final double[] SHIFT_BP = {1.5, -0.75, 2.25, -3.0, 0.5};

  private static double finalQuote(final int j) {
    return UNIVERSE.swaps().get(j).initialMid() * 1e-11 + SHIFT_BP[j] * 1e-4;
  }

  /** A desk on a loop whose CurveInputs carry every swap's shifted quote, in two rounds. */
  private static DeskGroup deskAfterCurveInputs() {
    final ManualClock source = new ManualClock(1_793_626_000_000_000_000L);
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(1 << 16);
    final DeskLoopFactory factory = new DeskLoopFactory(UNIVERSE, DeskSettings.defaults(), FrameSink.NONE);
    final EventLoop loop = factory.create(EventLoop.builder(new EventClock(source)).inbound(ring));
    final InboundPublisher publisher = new InboundPublisher(ring);
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
    final CurveInputEncoder e = new CurveInputEncoder();
    for (int round = 0; round < 2; round++) {
      for (int j = 0; j < UNIVERSE.swaps().size(); j++) {
        final UsdUniverse.SwapSpec s = UNIVERSE.swaps().get(j);
        final double quote = round == 0 ? s.initialMid() * 1e-11 + 0.5 * SHIFT_BP[j] * 1e-4 : finalQuote(j);
        e.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).curveId(1).kind(CurveInputKind.ParSwap)
            .instrumentId(s.instrumentId()).tenor("    ").timestampNanos(source.epochNanos());
        e.value().mantissa(Math.round(quote * 1e11));
        assertTrue(publisher.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()));
      }
      source.advance(1_000_000L);
      loop.doWork();
    }
    return factory.last();
  }

  /** P1 directly: a fresh bootstrap of the same final quotes. */
  private static CurveBuilder directCurve() {
    final List<CurveInstrument> inst = new ArrayList<>();
    for (int j = 0; j < UNIVERSE.swaps().size(); j++) {
      final UsdUniverse.SwapSpec s = UNIVERSE.swaps().get(j);
      inst.add(new OisSwapInstrument(SwapConvention.USD_SOFR, UNIVERSE.valuationDate(), s.tenorMonths(), s.tenor(),
          Math.round(finalQuote(j) * 1e11) * 1e-11));
    }
    final CurveBuilder b =
        new CurveBuilder(UNIVERSE.valuationDate(), inst, Interpolation.LINEAR_LOG_DF, 0.0, null, FixingHistory.EMPTY);
    b.build(CurveBuilder.Method.AUTO);
    return b;
  }

  @Test
  void swapsPricedThroughTheBindingEqualDirectAnalytics() {
    final AnalyticsPricing pricing = deskAfterCurveInputs().pricing();
    assertEquals(10, pricing.rebuilds(), "two rounds of five pillar moves, each an incremental rebuild");
    final CurveBuilder direct = directCurve();
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = UNIVERSE.valuationDate();
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      final OisSwap unit = OisSwap.spot(SwapConvention.USD_SOFR, today, s.tenorMonths(), 1.0, 0.0, true);
      final double par = pricer.parRate(unit, direct.curve(), FixingHistory.EMPTY, today);
      assertEquals(par, pricing.parRate(s.instrumentId()), 1e-12, s.tenor() + " par rate");
      assertEquals(Decimal9.round(pricing.parRate(s.instrumentId()) * 100.0), pricing.fairMid(s.instrumentId()));
      final OisSwap offMarket = OisSwap.spot(SwapConvention.USD_SOFR, today, s.tenorMonths(), 1.0, 0.04, true);
      assertEquals(pricer.pv(offMarket, direct.curve(), FixingHistory.EMPTY, today),
          pricing.presentValue(s.instrumentId(), 1.0, 0.04, true), 1e-12, s.tenor() + " PV per unit notional");
    }
  }

  @Test
  void theKeyRateLadderMatchesP1sLadderMappedToTheTenors() {
    final AnalyticsPricing pricing = deskAfterCurveInputs().pricing();
    final CurveBuilder direct = directCurve();
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = UNIVERSE.valuationDate();
    final Dv01Vector viaBinding = new Dv01Vector(CurrencyCode.USD);
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      final OisSwap unit =
          OisSwap.spot(SwapConvention.USD_SOFR, today, s.tenorMonths(), 1.0, s.initialMid() * 1e-11, true);
      final double[] g = new double[direct.curve().pillarCount()];
      pricer.pvGradient(unit, direct.curve(), direct.curve(), FixingHistory.EMPTY, today, g, g);
      final Dv01Ladder ladder = KeyRateRisk.fromGradient(direct, "USD", g).scaled(1e8);
      final Dv01Vector expected = pricing.adapter().toVector(ladder);
      // 100 million notional, long (pay fixed): 1e10 minor units
      assertTrue(pricing.pv01(s.instrumentId(), 10_000_000_000L, viaBinding));
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        assertEquals(expected.get(k), viaBinding.get(k), 1e-12 * Math.abs(ladder.parallel()),
            s.tenor() + " " + KeyRateTenors.label(k));
      }
      assertEquals(ladder.parallel(), viaBinding.parallel(), 1e-9 * Math.abs(ladder.parallel()));
      assertTrue(viaBinding.parallel() > 0, "a payer swap gains as rates rise");
    }
  }

  @Test
  void bondsTakeTheCompositeMidAndHaveNegativeDv01() {
    final DeskGroup desk = deskAfterCurveInputs();
    final AnalyticsPricing pricing = desk.pricing();
    final int bond = UNIVERSE.bonds().get(0).instrumentId();
    assertEquals(FairValueSource.NO_VALUE, pricing.fairMid(bond), "no book yet");
    assertEquals(FairValueSource.NO_VALUE, pricing.referenceNanos(bond));
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    assertTrue(pricing.pv01(bond, 1_000_000_000L, v));
    assertTrue(v.parallel() < 0, "a long bond loses as rates rise: " + v);
    assertFalse(pricing.pv01(99, 1, v));
    assertEquals(0.0, v.gross());
    assertEquals(FairValueSource.NO_VALUE, pricing.fairMid(99));
    assertEquals(FairValueSource.NO_VALUE, pricing.referenceNanos(99));
    assertTrue(Double.isNaN(pricing.parRate(bond)));
    assertFalse(pricing.onCurveInput(bond, 1L, 0L), "a bond is not a curve pillar");
    final LoopBook book = desk.book();
    assertEquals(book.bestBid(bond), FairValueSource.NO_VALUE);
  }

  @Test
  void theAdapterPreservesTheParallelAndRefusesAMismatchedLadder() {
    final Dv01Adapter adapter = new Dv01Adapter(new double[] {1.5, 12.0, 40.0});
    assertEquals(3, adapter.pillars());
    final Dv01Vector v = adapter.toVector(new Dv01Ladder("USD", List.of("A", "B", "C"), new double[] {10, 20, 30}, 60));
    assertEquals(60.0, v.parallel(), 1e-12);
    assertEquals(5.0, v.get(0), 1e-12, "1.5y splits evenly between 1Y and 2Y");
    assertEquals(30.0, v.get(KeyRateTenors.COUNT - 1), 1e-12, "past 30Y goes to 30Y");
    assertThrows(IllegalArgumentException.class,
        () -> adapter.toVector(new Dv01Ladder("USD", List.of("A"), new double[] {1}, 1)));
    assertEquals(120, UsdUniverse.months("10Y"));
    assertEquals(6, UsdUniverse.months("6M"));
    assertThrows(IllegalArgumentException.class, () -> UsdUniverse.months("2W"));
  }
}
