package io.ratesalgo.app.loop;

import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.LoopFactory;
import io.ratesalgo.router.FrameSink;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one instrument group's loop application (API_SPEC.md Section 18.4). The live process and
 * {@code LoopReplay} call the same factory, which is what makes the outbound hash comparison test
 * the desk itself: timer geometry, the {@link DeskGroup} graph and handler registration are
 * identical in both.
 *
 * <p>The router's decision records go to {@code routeJournal} live; in replay they are discarded,
 * since the replay compares only the loop's outbound stream.
 */
public final class DeskLoopFactory implements LoopFactory {

  /** Timer wheel tick: 1 ms, as the core default. */
  public static final long TIMER_TICK_NANOS = 1_000_000L;

  private final UsdUniverse universe;
  private final DeskSettings settings;
  private final FrameSink routeJournal;
  private final List<DeskGroup> built = new ArrayList<>();

  public DeskLoopFactory(final UsdUniverse universe, final DeskSettings settings, final FrameSink routeJournal) {
    this.universe = universe;
    this.settings = settings;
    this.routeJournal = routeJournal;
  }

  @Override
  public EventLoop create(final EventLoop.Builder builder) {
    builder.timers(TIMER_TICK_NANOS, 1024, 4096);
    final EventLoop loop = builder.build();
    final DeskGroup group =
        new DeskGroup(loop, universe, settings, builder.isReplay() ? FrameSink.NONE : routeJournal);
    group.register();
    built.add(group);
    return loop;
  }

  /** The most recently built group, or null. */
  public DeskGroup last() {
    return built.isEmpty() ? null : built.get(built.size() - 1);
  }
}
