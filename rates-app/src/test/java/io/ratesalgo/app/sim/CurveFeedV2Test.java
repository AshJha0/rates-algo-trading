package io.ratesalgo.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.sim.SessionSeeds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The simulator v2 curve round feeds pricing v2 whole: every input of the binding's build set,
 * one timestamp per round, so that nothing is seeded. Also the desk profile's own rules.
 */
class CurveFeedV2Test {

  private static final long SECOND = 1_000_000_000L;
  private static final UsdUniverse UNIVERSE = UsdUniverse.simV2();

  /** One decoded message of a round. */
  private record Input(int templateId, int id, CurveInputKind kind, long value, long stamp, String tenor) {}

  @Test
  void aRoundIsTheBindingsBuildSetWithOneTimestampAndLeavesNothingUnfed() {
    final SimClock clock = new SimClock(V2DeskProcess.SESSION_OPEN_EPOCH_NANOS);
    try (SimulatedVenuesV2 venues = new SimulatedVenuesV2(clock, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 93_001))) {
      venues.startFlow();
      venues.runAlone(clock, 600 * SECOND);
      final List<Input> round = new ArrayList<>();
      final MessageHeaderDecoder header = new MessageHeaderDecoder();
      final CurveInputDecoder input = new CurveInputDecoder();
      final HeartbeatDecoder heartbeat = new HeartbeatDecoder();
      final PricingV2 pricing = new PricingV2(UNIVERSE, clock, PricingV2.Options.defaults());
      final CurveFeedV2 feed =
          new CurveFeedV2(
              UNIVERSE, venues.session(),
              (buffer, offset, length) -> {
                header.wrap(buffer, offset);
                if (header.templateId() == CurveInputDecoder.TEMPLATE_ID) {
                  input.wrapAndApplyHeader(buffer, offset, header);
                  assertEquals(AppIds.SOFR_CURVE, input.curveId());
                  round.add(new Input(header.templateId(), input.instrumentId(), input.kind(), input.value().mantissa(),
                      input.timestampNanos(), input.tenor()));
                  pricing.onCurveInput(input.instrumentId(), input.value().mantissa(), input.timestampNanos());
                } else {
                  heartbeat.wrapAndApplyHeader(buffer, offset, header);
                  round.add(new Input(header.templateId(), heartbeat.sourceId(), null, 0, heartbeat.timestampNanos(), ""));
                }
                return true;
              },
              clock, 500_000_000L);

      assertEquals(6, pricing.unfedInputs(), "before the feed, the fixing and the five futures are seeded");
      assertEquals(15, feed.doWork(), "fourteen inputs and the heartbeat");
      assertEquals(14, feed.inputsPerRound());
      assertEquals(1, feed.rounds());
      assertEquals(15, feed.published());
      assertEquals(0, pricing.unfedInputs(), "every input of the build set was fed");
      assertEquals(0, pricing.rejectedInputs());
      assertTrue(pricing.reprices() > 0, "a complete round reprices at once");

      // the round is the binding's own list, in its order, every message with one timestamp
      final Set<Integer> ids = new HashSet<>();
      final Set<Integer> riskTenorMonths = new HashSet<>(Set.of(12, 24, 36, 60, 84, 120, 240, 360));
      int fixings = 0;
      int futures = 0;
      int swaps = 0;
      for (int i = 0; i < 14; i++) {
        final Input in = round.get(i);
        assertEquals(pricing.inputId(i), in.id(), "input " + i);
        assertEquals(round.get(0).stamp(), in.stamp(), "one timestamp per round");
        assertTrue(ids.add(in.id()));
        assertEquals(4, in.tenor().length());
        if (in.kind() == CurveInputKind.Fixing) {
          fixings++;
          assertEquals(V2InputIds.OVERNIGHT, in.id());
          assertEquals("ON  ", in.tenor());
        } else if (in.kind() == CurveInputKind.Future) {
          futures++;
          assertTrue(V2InputIds.isSr1(in.id()) || V2InputIds.isSr3(in.id()));
          assertTrue(in.value() > 90_000_000_000L && in.value() < 100_000_000_000L, "a futures price");
        } else {
          swaps++;
          assertEquals(CurveInputKind.ParSwap, in.kind());
          final UsdUniverse.SwapSpec quoted = UNIVERSE.swap(in.id());
          final int months = quoted != null ? quoted.tenorMonths() : V2InputIds.parSwapMonths(in.id());
          assertTrue(riskTenorMonths.remove(months), "a risk tenor, once: " + months);
        }
      }
      assertEquals(1, fixings);
      assertEquals(5, futures, "SR1 x2 and SR3 x3");
      assertEquals(8, swaps, "a par swap at each risk tenor");
      assertEquals(HeartbeatDecoder.TEMPLATE_ID, round.get(14).templateId());
      assertEquals(AppIds.CURVE_HEARTBEAT_SOURCE, round.get(14).id());
      assertEquals(round.get(0).stamp(), round.get(14).stamp());

      // not due yet; due again after the interval, with a new timestamp and moved rates
      venues.advance(clock, 100_000_000L);
      assertEquals(0, feed.doWork());
      venues.runAlone(clock, 300 * SECOND);
      final long reprices = pricing.reprices();
      assertEquals(15, feed.doWork());
      assertEquals(2, feed.rounds());
      assertNotEquals(round.get(0).stamp(), round.get(15).stamp());
      assertTrue(pricing.reprices() > reprices, "the market moved in five minutes");
      assertEquals(round.get(0).value(), round.get(15).value(), "the overnight rate holds through the day");
      boolean moved = false;
      for (int i = 1; i < 14; i++) {
        moved |= round.get(i).value() != round.get(15 + i).value();
      }
      assertTrue(moved);
      assertEquals(0, pricing.unfedInputs());
    }
  }

  @Test
  void aSinkThatRefusesIsNotCountedAsPublished() {
    final SimClock clock = new SimClock(V2DeskProcess.SESSION_OPEN_EPOCH_NANOS);
    try (SimulatedVenuesV2 venues = new SimulatedVenuesV2(clock, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 93_002))) {
      final CurveFeedV2 feed =
          new CurveFeedV2(UNIVERSE, venues.session(), (buffer, offset, length) -> false, clock, 500_000_000L);
      assertEquals(0, feed.doWork());
      assertEquals(0, feed.published());
      assertEquals(1, feed.rounds());
    }
  }

  @Test
  void aProfileNamesEveryComponentAndRefusesWhatCannotReplay() {
    final DeskProfile recorded = DeskProfile.defaults();
    assertTrue(recorded.isRecorded());
    assertEquals(DeskSettings.defaults(), recorded.settings());
    assertEquals(PricingBindings.defaultVersion(), recorded.pricing());
    assertEquals(DeskProfile.RouterModel.TOUCH, recorded.router());
    assertEquals(DeskProfile.Market.STANDARD, recorded.market());
    assertTrue(recorded.newUniverse().futures().isEmpty());

    final DeskProfile v2 = DeskProfile.v2();
    assertFalse(v2.isRecorded());
    assertEquals(DeskSettings.strategyV2(), v2.settings(), "the strategy exactly as its document declares it");
    assertEquals(PricingBindings.Version.V2, v2.pricing());
    assertEquals(DeskProfile.RouterModel.LADDER, v2.router());
    assertEquals(DeskProfile.Market.SIM_V2, v2.market());
    assertEquals(1, v2.newUniverse().futures().size());

    // one component at a time
    assertFalse(recorded.withPricing(PricingBindings.Version.V2).isRecorded());
    assertFalse(recorded.withRouter(DeskProfile.RouterModel.LADDER).isRecorded());
    assertFalse(recorded.withMarket(DeskProfile.Market.SIM_V2).isRecorded());
    assertTrue(recorded.withSettings(DeskSettings.strategyV2()).isRecorded(), "the strategy is in the settings");
    assertEquals(recorded, v2.withSettings(DeskSettings.defaults()).withPricing(PricingBindings.Version.V1)
        .withRouter(DeskProfile.RouterModel.TOUCH).withMarket(DeskProfile.Market.STANDARD));
    final Path a = Path.of("a");
    assertEquals(a, v2.withMarkouts(a, null).markoutLoad());
    assertEquals(a, v2.withMarkouts(null, a).markoutSave());
    assertThrows(IllegalArgumentException.class, () -> v2.withMarkouts(a, a));
    assertThrows(NullPointerException.class, () -> new DeskProfile(null, PricingBindings.Version.V1,
        DeskProfile.RouterModel.TOUCH, DeskProfile.Market.STANDARD, null, null));

    // the stepped process is for the simulator v2 market only, from the session's open onward
    assertThrows(IllegalArgumentException.class, () -> new V2DeskProcess.Options(a, 1L, recorded, 0L));
    assertThrows(IllegalArgumentException.class, () -> new V2DeskProcess.Options(a, 1L, v2, -1L));
  }
}
