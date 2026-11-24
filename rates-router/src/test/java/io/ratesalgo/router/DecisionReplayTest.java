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
import io.ratesalgo.messages.sbe.VenueExclusion;
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
        // venue health is an input to the decision: it must come back from the journal too
        if (i == 5) {
          router.venueHealth(venues[0].venueId(), false);
        } else if (i == 60) {
          router.venueHealth(venues[0].venueId(), true);
        }
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
    assertTrue(
        rebuilt.stream().anyMatch(t -> t.exclusion(0) == VenueExclusion.Stale),
        "no journaled decision shows the unhealthy venue as Stale");
  }

  /** Runs a ladder-model router on the benchmark market and journals everything it writes. */
  private static List<CostTable> ladderRun(final Path file, final List<byte[]> bytes) {
    final ManualClock clock = new ManualClock();
    final io.ratesalgo.router.sim.ImpactConfig cfg =
        io.ratesalgo.router.backtest.RouterBenchmarkScenario.ust10yImpactV1().market();
    final io.ratesalgo.router.sim.ImpactMarket market =
        new io.ratesalgo.router.sim.ImpactMarket(cfg, 11L, clock);
    final VenueParams[] venues = cfg.declaredVenues(0.25, 5);
    final List<CostTable> live = new ArrayList<>();
    final SmartOrderRouter[] holder = new SmartOrderRouter[1];
    try (JournalWriter writer = JournalWriter.create(file, 64 << 20, clock)) {
      final FrameSink sink =
          (templateId, version, buffer, offset, length) -> {
            writer.append(templateId, version, buffer, offset, length);
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            bytes.add(copy);
            if (templateId == RouteDecisionEncoder.TEMPLATE_ID) {
              final CostTable t = new CostTable();
              t.copyFrom(holder[0].lastDecision());
              live.add(t);
            }
          };
      holder[0] =
          new SmartOrderRouter(
              RouterPresets.ladder(), venues, market, market, sink, clock, RandomSource.seeded(3L),
              8, 256, 1L, RouterTimeouts.DEFAULT, ExecutionModel.defaults());
      final SmartOrderRouter router = holder[0];
      long id = 1;
      for (int i = 0; i < 40_000 && (id <= 12 || router.parentsLive() > 0); i++) {
        if (router.parentsLive() == 0) {
          router.submit(id, 10, (id & 1) == 0 ? Side.Buy : Side.Sell, (10 + 4 * id) * MM, (id % 3) * 0.4, 0L);
          id++;
        }
        if (i == 700) {
          router.venueHealth(venues[1].venueId(), false);
        } else if (i == 900) {
          router.venueHealth(venues[1].venueId(), true);
        }
        clock.advance(cfg.stepNanos());
        market.step();
        market.drain(router);
        router.poll();
      }
      assertEquals(0, router.parentsLive());
      assertTrue(market.executions() > 0);
    }
    return live;
  }

  @Test
  void ladderDecisionsReplayToIdenticalValuesIncludingTheNewTerms(@TempDir final Path dir) {
    final List<byte[]> bytes = new ArrayList<>();
    final Path file = dir.resolve("ladder.raj");
    final List<CostTable> live = ladderRun(file, bytes);
    final DecisionCodec codec = new DecisionCodec();
    final List<CostTable> rebuilt = new ArrayList<>();
    final int[] templates = new int[128];
    new Replayer(new ManualClock(0L))
        .replay(
            file,
            (sequence, ts, templateId, version, buffer, offset, length) -> {
              templates[templateId]++;
              if (codec.onFrame(templateId, buffer, offset, length)) {
                final CostTable copy = new CostTable();
                copy.copyFrom(codec.decoded());
                rebuilt.add(copy);
              }
            });
    assertTrue(templates[83] > 0 && templates[84] > 0, "ladder frames journaled");
    assertEquals(live.size(), rebuilt.size());
    final Allocator allocator = new Allocator();
    final VenueParams[] venues =
        io.ratesalgo.router.backtest.RouterBenchmarkScenario.ust10yImpactV1().market().declaredVenues(0.25, 5);
    boolean penalised = false;
    boolean learned = false;
    boolean passive = false;
    boolean swept = false;
    for (int i = 0; i < live.size(); i++) {
      final CostTable journaled = rebuilt.get(i);
      assertTrue(journaled.isLadder());
      assertTrue(live.get(i).contentEquals(journaled), "decision " + i + " differs");
      final CostTable again = new CostTable();
      again.copyFrom(journaled);
      again.clearOutputs();
      allocator.allocate(again, RouterPresets.ladder(), venues, ExecutionModel.defaults());
      assertTrue(again.contentEquals(journaled),
          "re-evaluation differs:\n" + journaled.format() + again.format());
      for (int r = 0; r < journaled.venueCount(); r++) {
        penalised |= journaled.penalty(r) > 0;
        learned |= journaled.cancelRate(r) != Math.round(0.2 * 1e9) && journaled.cancelRate(r) > 0;
        passive |= journaled.allocated(r) > 0 && journaled.passive(r);
        swept |= journaled.sweepQuantity(r) > 0 && journaled.levelCount(r) > 0;
      }
    }
    assertTrue(penalised, "a scorecard penalty was journaled and reproduced");
    assertTrue(learned, "a learned cancellation rate was journaled");
    assertTrue(passive && swept, "both legs were exercised");
    assertTrue(rebuilt.stream().anyMatch(t -> t.exclusion(1) == VenueExclusion.Stale));
    // the same seed gives the same journal, byte for byte
    final List<byte[]> second = new ArrayList<>();
    ladderRun(dir.resolve("ladder2.raj"), second);
    assertEquals(bytes.size(), second.size());
    for (int i = 0; i < bytes.size(); i++) {
      assertTrue(java.util.Arrays.equals(bytes.get(i), second.get(i)), "frame " + i + " differs");
    }
  }

  @Test
  void decoderRejectsLadderFramesOutOfSequence() {
    final DecisionCodec codec = new DecisionCodec();
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    // a model row with no decision open
    new io.ratesalgo.messages.sbe.RouteDecisionModelEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(3).row((short) 0);
    assertThrows(IllegalStateException.class, () -> codec.onFrame(84, buffer, 0, 200));
    // a level with no model row before it
    new io.ratesalgo.messages.sbe.RouteDecisionLadderEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(3).row((short) 0).level((short) 0);
    assertThrows(IllegalStateException.class, () -> codec.onFrame(83, buffer, 0, 64));
    // a ladder decision whose venue row arrives before its model row
    new RouteDecisionEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(4).side(Side.Buy).reason(RouteReason.Slice).venueCount((short) 2);
    assertFalse(codec.onFrame(80, buffer, 0, 64));
    new io.ratesalgo.messages.sbe.RouteDecisionModelEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(4).row((short) 0).levelCount((short) 1);
    assertFalse(codec.onFrame(84, buffer, 0, 200));
    new RouteDecisionVenueEncoder().wrapAndApplyHeader(buffer, 0, header).decisionId(4).row((short) 0);
    assertThrows(IllegalStateException.class, () -> codec.onFrame(81, buffer, 0, 64), "its level is missing");
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
