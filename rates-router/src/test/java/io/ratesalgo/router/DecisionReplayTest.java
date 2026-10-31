package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionEncoder;
import io.ratesalgo.messages.sbe.RouteReason;
import io.ratesalgo.messages.sbe.RouteDecisionVenueEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explainability: every routing decision written to a real journal can be read back into the
 * exact cost table the router used, and re-running the allocation on the journaled inputs (with
 * the parameters of the journaled config version) reproduces the journaled costs and split.
 */
class DecisionReplayTest {

  private static final long MM = RouterPresets.ONE_MILLION;

  @Test
  void journalRebuildsAndReproducesEveryDecision(@TempDir final Path dir) {
    final ManualClock clock = new ManualClock();
    final SimConfig config = SimConfig.ust10yThreeVenues();
    final SimMarket market = new SimMarket(config, 5L, clock);
    final VenueParams[] venues = config.routerVenues(0.25, 5);
    final RouterParams params = RouterPresets.global();
    final List<CostTable> live = new ArrayList<>();
    final SmartOrderRouter[] holder = new SmartOrderRouter[1];
    final Path file = dir.resolve("router.raj");
    final int[] transitions = new int[1];
    try (JournalWriter writer = JournalWriter.create(file, 16 << 20, clock)) {
      final FrameSink sink =
          (templateId, version, buffer, offset, length) -> {
            writer.append(templateId, version, buffer, offset, length);
            if (templateId == RouteDecisionEncoder.TEMPLATE_ID) {
              final CostTable copy = new CostTable();
              copy.copyFrom(holder[0].lastDecision());
              live.add(copy);
            } else if (templateId == 82) {
              transitions[0]++;
            }
          };
      holder[0] =
          new SmartOrderRouter(
              params, venues, market, market, sink, clock, RandomSource.seeded(3L), 8, 256, 1L);
      final SmartOrderRouter router = holder[0];
      router.submit(1, 10, Side.Buy, 40 * MM, 0.3, 0L);
      router.submit(2, 10, Side.Sell, 25 * MM, 0.8, 0L);
      for (int i = 0; i < 20_000 && (router.isActive(1) || router.isActive(2)); i++) {
        clock.advance(config.stepNanos());
        market.step();
        market.drain(router);
        router.poll();
      }
      assertFalse(router.isActive(1) || router.isActive(2));
    }

    final DecisionCodec codec = new DecisionCodec();
    final List<CostTable> rebuilt = new ArrayList<>();
    final long frames =
        new Replayer(new ManualClock(0L))
            .replay(
                file,
                (sequence, ts, templateId, version, buffer, offset, length) -> {
                  if (codec.onFrame(templateId, buffer, offset, length)) {
                    final CostTable copy = new CostTable();
                    copy.copyFrom(codec.decoded());
                    rebuilt.add(copy);
                  }
                });
    assertTrue(frames > 0);
    assertTrue(transitions[0] > 0);
    assertTrue(live.size() >= 2, "decisions: " + live.size());
    assertEquals(live.size(), rebuilt.size());
    final Allocator allocator = new Allocator();
    for (int i = 0; i < live.size(); i++) {
      final CostTable journaled = rebuilt.get(i);
      assertTrue(live.get(i).contentEquals(journaled), "decision " + i + " differs");
      assertEquals(RouterPresets.CONFIG_VERSION, journaled.configVersion);
      final CostTable again = new CostTable();
      again.copyFrom(journaled);
      again.clearOutputs();
      allocator.allocate(again, params, venues);
      assertTrue(
          again.contentEquals(journaled),
          "re-evaluation differs:\n" + journaled.format() + again.format());
    }
    assertTrue(rebuilt.get(0).format().contains("decision 1 parent 1"));
  }

  @Test
  void decoderRejectsRowsOutOfSequence() {
    final DecisionCodec codec = new DecisionCodec();
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    new RouteDecisionVenueEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(9).row((short) 0);
    assertThrows(
        IllegalStateException.class,
        () -> codec.onFrame(RouteDecisionVenueEncoder.TEMPLATE_ID, buffer, 0, 64));
    assertFalse(codec.onFrame(21, buffer, 0, 64));
    new RouteDecisionEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(4).side(Side.Buy).reason(RouteReason.Slice).venueCount((short) 0);
    assertTrue(codec.onFrame(RouteDecisionEncoder.TEMPLATE_ID, buffer, 0, 64));
    new RouteDecisionEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(5).side(Side.Buy).reason(RouteReason.Slice).venueCount((short) 9);
    assertThrows(
        IllegalStateException.class,
        () -> codec.onFrame(RouteDecisionEncoder.TEMPLATE_ID, buffer, 0, 64));
    header.wrap(buffer, 0).schemaId(77);
    assertThrows(
        IllegalStateException.class,
        () -> codec.onFrame(RouteDecisionEncoder.TEMPLATE_ID, buffer, 0, 64));
  }
}
