package io.ratesalgo.core;

import java.util.Locale;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * The idle strategies a loop runner accepts, by name. A busy spin gives the lowest wake-up latency
 * and burns a core; it belongs on an isolated, pinned core. Yielding gives the core back to the
 * scheduler between empty cycles. Backoff spins, then yields, then parks for up to a millisecond,
 * for loops on shared machines such as CI runners and laptops.
 */
public final class IdleStrategies {

  private IdleStrategies() {}

  /**
   * The strategy named {@code busy-spin}, {@code yield} or {@code backoff}.
   *
   * @throws IllegalArgumentException for any other name
   */
  public static IdleStrategy of(final String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "busy-spin" -> new BusySpinIdleStrategy();
      case "yield" -> new YieldingIdleStrategy();
      case "backoff" -> new BackoffIdleStrategy(100, 10, 1_000L, 1_000_000L);
      default ->
          throw new IllegalArgumentException(
              "unknown idle strategy '" + name + "'; use busy-spin, yield or backoff");
    };
  }
}
