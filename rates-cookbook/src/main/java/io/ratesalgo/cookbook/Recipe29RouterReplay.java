package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.ChildOrderTransitionDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.CostTable;
import io.ratesalgo.router.DecisionCodec;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** COOKBOOK.md recipe 29: replay a journal and rebuild the router's decisions for one parent. */
public final class Recipe29RouterReplay {

  private Recipe29RouterReplay() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe29");
    final Path file = dir.resolve("router.raj");
    final ManualClock clock = new ManualClock();
    final SimConfig config = SimConfig.ust10yTwoVenues();
    final SimMarket market = new SimMarket(config, 2026L, clock);

    // live run: the router journals every RouteDecision, its venue rows and every child transition
    try (JournalWriter journal = JournalWriter.create(file, 1 << 22, clock)) {
      final SmartOrderRouter router = new SmartOrderRouter(RouterPresets.global(), config.routerVenues(0.25, 5),
          market, market, (t, v, b, o, l) -> journal.append(t, v, b, o, l), clock, RandomSource.seeded(17L),
          4, 64, 1L);
      router.submit(1, 1001, Side.Buy, 20 * RouterPresets.ONE_MILLION, 0.5, 0L);
      router.submit(2, 1001, Side.Sell, 8 * RouterPresets.ONE_MILLION, 0.9, 0L);
      while (router.isActive(1) || router.isActive(2)) {
        clock.advance(config.stepNanos());
        market.step();
        market.drain(router);
        router.poll();
      }
      System.out.println("journal frames " + journal.nextSequence());
    }

    // replay: read the file back and keep what belongs to parent 2
    final DecisionCodec codec = new DecisionCodec();
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final ChildOrderTransitionDecoder transition = new ChildOrderTransitionDecoder();
    final Map<Integer, Integer> byTemplate = new TreeMap<>();
    final StringBuilder decisions = new StringBuilder();
    final StringBuilder children = new StringBuilder();
    try (JournalReader reader = JournalReader.open(file)) {
      while (reader.next((seq, nanos, templateId, version, buffer, offset, length) -> {
        byTemplate.merge(templateId, 1, Integer::sum);
        if (codec.onFrame(templateId, buffer, offset, length) && codec.decoded().parentId() == 2) {
          final CostTable t = codec.decoded();
          decisions.append(decisions.isEmpty() ? t.format() : String.format(Locale.ROOT,
              "decision %d %s slice %.1fm alloc %.1fm%n", t.decisionId(), t.reason(),
              t.sliceQuantity() / (double) RouterPresets.ONE_MILLION,
              t.allocatedQuantity() / (double) RouterPresets.ONE_MILLION));
        }
        if (templateId == ChildOrderTransitionDecoder.TEMPLATE_ID) {
          transition.wrapAndApplyHeader(buffer, offset, header);
          if (transition.parentId() == 2) {
            children.append(String.format(Locale.ROOT, "  seq %3d child %d venue %d %s -> %s (%s) cum %.1fm%n",
                seq, transition.clOrdId(), transition.venueId(), transition.fromState(), transition.toState(),
                transition.cause(), transition.cumQuantity() / (double) RouterPresets.ONE_MILLION));
          }
        }
      })) {
        // reading
      }
    }
    System.out.println("frames by template (80 decision, 81 venue row, 82 child transition): " + byTemplate);
    System.out.print(decisions);
    System.out.println("child orders of parent 2:");
    System.out.print(children);
    Files.delete(file);
    Files.delete(dir);
  }
}
