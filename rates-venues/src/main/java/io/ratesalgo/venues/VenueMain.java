package io.ratesalgo.venues;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.feed.UdpFeedChannel;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one simulator as a standalone process on the system clock.
 *
 * <pre>
 * java ... io.ratesalgo.venues.VenueMain --venue d2c --port 9878 --seed 7 [--duration-millis 0]
 *     [--feed-host 127.0.0.1 --feed-port 9900] [--auto-flow true]
 * </pre>
 *
 * A duration of 0 runs until the process is stopped. The CLOB and SEF publish their book feed
 * as UDP datagrams when {@code --feed-port} is given.
 */
public final class VenueMain {

  private VenueMain() {}

  /** Entry point. */
  public static void main(final String[] args) {
    run(args, System.out);
  }

  /** Parses arguments, runs the simulator for the duration, returns the simulator's event count. */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --option value, got " + args[i]);
      }
      opts.put(args[i].substring(2), args[i + 1]);
    }
    final String kind = opts.getOrDefault("venue", "d2c");
    final int port = Integer.parseInt(opts.getOrDefault("port", "9878"));
    final long seed = Long.parseLong(opts.getOrDefault("seed", "1"));
    final long duration = Long.parseLong(opts.getOrDefault("duration-millis", "0"));
    final FeedChannel feed =
        opts.containsKey("feed-port")
            ? new UdpFeedChannel(opts.getOrDefault("feed-host", "127.0.0.1"), Integer.parseInt(opts.get("feed-port")))
            : new InProcessFeedChannel();
    final VenueSimulator sim = Venues.create(kind, SystemClock.INSTANCE, seed, feed);
    sim.start(port);
    if (sim instanceof D2cRfqSimulator d2c && Boolean.parseBoolean(opts.getOrDefault("auto-flow", "true"))) {
      d2c.startAutoFlow();
    }
    out.println(sim.compId() + " simulator listening on port " + port + " (seed " + seed + ")");
    final long end = SystemClock.INSTANCE.nanoTime() + duration * 1_000_000L;
    try {
      while (duration == 0 || SystemClock.INSTANCE.nanoTime() < end) {
        sim.onTime();
        LockSupport.parkNanos(5_000_000L);
      }
    } finally {
      sim.close();
      if (feed instanceof UdpFeedChannel udp) {
        udp.close();
      }
    }
    out.println(sim.compId() + " simulator stopped");
    return sim.eventLog().size();
  }
}
