package io.ratesalgo.core;

import java.util.Locale;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * The idle strategies a loop runner accepts, by name. A busy spin gives the lowest wake-up latency
 * and burns a core; it belongs on an isolated, pinned core. Yielding gives the core back to the
 * scheduler between empty cycles. Backoff spins, then yields, then parks for up to a millisecond,
 * for loops on shared machines such as CI runners and laptops. Park parks for 200 microseconds
 * after every empty cycle, which is what the process's gateway thread always did; it costs the
 * least CPU and adds up to that much to the wake-up.
 */
public final class IdleStrategies {

  private IdleStrategies() {}

  /** The names {@link #of} accepts. */
  public static final String NAMES = "busy-spin, yield, backoff or park";

  /** How long {@code park} parks after an empty cycle. */
  public static final long PARK_NANOS = 200_000L;

  /**
   * The strategy named {@code busy-spin}, {@code yield}, {@code backoff} or {@code park}.
   *
   * @throws IllegalArgumentException for any other name
   */
  public static IdleStrategy of(final String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "busy-spin" -> new BusySpinIdleStrategy();
      case "yield" -> new YieldingIdleStrategy();
      case "backoff" -> new BackoffIdleStrategy(100, 10, 1_000L, 1_000_000L);
      case "park" -> new SleepingIdleStrategy(PARK_NANOS);
      default -> throw new IllegalArgumentException("unknown idle strategy '" + name + "'; use " + NAMES);
    };
  }
}
