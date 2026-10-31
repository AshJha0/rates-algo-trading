package io.ratesalgo.router;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.util.ArrayList;
import java.util.List;

/** A router wired to in-memory venues, with every journaled frame captured. */
final class Rig {

  /** One captured frame. */
  static final class Frame {
    private final int templateId;

    Frame(final int templateId, final byte[] payload) {
      this.templateId = templateId;
    }

    int templateId() {
      return templateId;
    }
  }

  final ManualClock clock = new ManualClock();
  final SimConfig config;
  final SimMarket market;
  final List<Frame> frames = new ArrayList<>();
  final SmartOrderRouter router;
  ExecutionListener listener;

  Rig(final SimConfig config, final RouterParams params, final long seed) {
    this.config = config;
    this.market = new SimMarket(config, seed, clock);
    this.router =
        new SmartOrderRouter(
            params,
            config.routerVenues(0.25, 5),
            market,
            market,
            (templateId, version, buffer, offset, length) -> {
              final byte[] copy = new byte[length];
              buffer.getBytes(offset, copy);
              frames.add(new Frame(templateId, copy));
            },
            clock,
            RandomSource.seeded(seed),
            8,
            256,
            1_000L);
    this.listener = router;
  }

  void step(final int steps) {
    for (int i = 0; i < steps; i++) {
      clock.advance(config.stepNanos());
      market.step();
      market.drain(listener);
      router.poll();
    }
  }

  /** Steps until the parent completes; returns the steps taken, or -1 after {@code max}. */
  int runUntilDone(final long parentId, final int max) {
    for (int i = 0; i < max; i++) {
      if (!router.isActive(parentId)) {
        return i;
      }
      step(1);
    }
    return router.isActive(parentId) ? -1 : max;
  }
}
