package io.ratesalgo.app.sim;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cConfig;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.AcceptorOptions;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;

/**
 * P2's D2C RFQ and CLOB simulators started in this process on free local ports, on a shared
 * clock, with the CLOB's book feed on an in-process channel. The trading process connects to them
 * over TCP exactly as it would to a venue in another process.
 */
public final class SimulatedVenues implements AutoCloseable {

  private final Clock clock;
  private final D2cRfqSimulator d2c;
  private final ClobSimulator clob;
  private final InProcessFeedChannel feed = new InProcessFeedChannel();

  /** Builds and starts both simulators; RFQ flow starts with {@link #startFlow}. */
  public SimulatedVenues(final Clock clock, final long seed) {
    this(clock, seed, AcceptorOptions.PLAIN, AcceptorOptions.PLAIN);
  }

  /**
   * Builds and starts both simulators with the given acceptor settings: TLS, logon credentials and
   * the directory in which each keeps its sequence numbers and its id sequence across restarts.
   */
  public SimulatedVenues(
      final Clock clock, final long seed, final AcceptorOptions d2cOptions, final AcceptorOptions clobOptions) {
    this.clock = clock;
    final List<Instrument> rfqInstruments = new ArrayList<>(Venues.treasuries());
    rfqInstruments.addAll(Venues.swaps());
    d2c = new D2cRfqSimulator(D2cConfig.defaults(), rfqInstruments, Venues.ENGINES, clock, seed);
    clob = new ClobSimulator("CLOB", ClobConfig.defaults(), Venues.treasuries(), Venues.ENGINES, feed, clock, seed + 1);
    d2c.start(VenueHarness.freePort(), d2cOptions);
    boolean started = false;
    try {
      clob.start(VenueHarness.freePort(), clobOptions);
      started = true;
    } finally {
      if (!started) {
        d2c.close();
      }
    }
  }

  /** Starts the D2C arrival model. */
  public void startFlow() {
    d2c.startAutoFlow();
  }

  /** Runs every event due at the current time on both venues. */
  public void onTime() {
    d2c.onTime();
    clob.onTime();
  }

  /**
   * Moves a {@link SimClock} under both venues' locks, so a venue thread never observes the move
   * half way through handling a message.
   */
  public void advance(final SimClock sim, final long nanos) {
    d2c.locked(() -> clob.locked(() -> sim.advance(nanos)));
  }

  public D2cRfqSimulator d2c() {
    return d2c;
  }

  public ClobSimulator clob() {
    return clob;
  }

  /** The CLOB's book feed. */
  public InProcessFeedChannel feed() {
    return feed;
  }

  public Clock clock() {
    return clock;
  }

  @Override
  public void close() {
    d2c.close();
    clob.close();
  }
}
