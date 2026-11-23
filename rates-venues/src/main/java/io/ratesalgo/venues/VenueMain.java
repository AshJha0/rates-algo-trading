package io.ratesalgo.venues;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.feed.UdpFeedChannel;
import io.ratesalgo.venues.sim.AcceptorOptions;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one simulator as a standalone process on the system clock.
 *
 * <pre>
 * java ... io.ratesalgo.venues.VenueMain --venue d2c --port 9878 --seed 7 [--duration-millis 0]
 *     [--feed-host 127.0.0.1 --feed-port 9900] [--auto-flow true]
 *     [--bind 127.0.0.1]
 *     [--tls-keystore venue.p12 (--tls-keystore-password-env NAME | --tls-keystore-password-file FILE)]
 *     [--username desk (--password-env NAME | --password-file FILE)]
 *     [--store-dir DIR]
 * </pre>
 *
 * A duration of 0 runs until the process is stopped. The CLOB and SEF publish their book feed
 * as UDP datagrams when {@code --feed-port} is given.
 *
 * <p>The simulator listens on loopback unless {@code --bind} names another address, and it
 * listens beyond loopback only with TLS: plain FIX on a routable address is refused before
 * anything is opened. Passwords are named by environment variable or file, never given on the
 * command line. With {@code --username} every Logon must carry the matching Username (553) and
 * Password (554). With {@code --store-dir} the venue keeps its sequence numbers, sent messages
 * and id generation across restarts.
 */
public final class VenueMain {

  private VenueMain() {}

  /** Entry point. */
  public static void main(final String[] args) {
    run(args, System.out);
  }

  /** Parses arguments, runs the simulator for the duration, returns the simulator's event count. */
  public static int run(final String[] args, final PrintStream out) {
    return run(args, out, System.getenv());
  }

  /** As {@link #run(String[], PrintStream)}, reading named secrets from the given environment. */
  public static int run(final String[] args, final PrintStream out, final Map<String, String> env) {
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
    final String bind = opts.getOrDefault("bind", VenueSimulator.LOOPBACK);
    // checked before the options are read further, so that nothing is opened on a refusal
    if (!opts.containsKey("tls-keystore") && !VenueSimulator.isLoopback(bind)) {
      throw new IllegalArgumentException(
          "refusing to listen for plain FIX on " + bind + ": give --tls-keystore, or bind a loopback address");
    }
    final AcceptorOptions options = acceptorOptions(opts, env);
    final FeedChannel feed =
        opts.containsKey("feed-port")
            ? new UdpFeedChannel(opts.getOrDefault("feed-host", "127.0.0.1"), Integer.parseInt(opts.get("feed-port")))
            : new InProcessFeedChannel();
    final VenueSimulator sim = Venues.create(kind, SystemClock.INSTANCE, seed, feed);
    sim.start(bind, port, options);
    if (sim instanceof D2cRfqSimulator d2c && Boolean.parseBoolean(opts.getOrDefault("auto-flow", "true"))) {
      d2c.startAutoFlow();
    }
    out.println(
        sim.compId() + " simulator listening on " + bind + " port " + port + " (seed " + seed + ", "
            + (options.tls() == null ? "plain" : "TLS") + (options.username() == null ? "" : ", credentials required")
            + ")");
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

  private static AcceptorOptions acceptorOptions(final Map<String, String> opts, final Map<String, String> env) {
    TlsConfig tls = null;
    if (opts.containsKey("tls-keystore")) {
      final Secret storePassword =
          Secret.fromEnvOrFile(env, opts.get("tls-keystore-password-env"), opts.get("tls-keystore-password-file"));
      tls = TlsConfig.builder().keyStore(Path.of(opts.get("tls-keystore")), storePassword).build();
      // opened now: a wrong path or password stops the process before it listens
      tls.context();
    }
    final String username = opts.get("username");
    final Secret password =
        username == null
            ? null
            : Secret.fromEnvOrFile(env, opts.get("password-env"), opts.get("password-file"));
    if (username == null && (opts.containsKey("password-env") || opts.containsKey("password-file"))) {
      throw new IllegalArgumentException("a password needs --username");
    }
    final String storeDir = opts.get("store-dir");
    return new AcceptorOptions(tls, username, password, storeDir == null ? null : Path.of(storeDir));
  }
}
