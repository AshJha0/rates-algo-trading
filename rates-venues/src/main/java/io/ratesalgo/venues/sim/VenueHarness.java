package io.ratesalgo.venues.sim;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.session.FixSessionHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Runs a simulator in the test's process on a free local port, with a {@link ManualClock} that
 * only the harness moves. Time moves under the simulator's lock, so the order of events depends
 * only on the clock and the messages.
 */
public final class VenueHarness implements AutoCloseable {

  private final VenueSimulator simulator;
  private final ManualClock clock;

  /** Starts the simulator on a free port. The simulator must have been built with {@code clock}. */
  public VenueHarness(final VenueSimulator simulator, final ManualClock clock) {
    this.simulator = simulator;
    this.clock = clock;
    simulator.start(freePort());
  }

  /** A port that was free a moment ago. */
  public static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      s.setReuseAddress(true);
      return s.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The simulator. */
  public VenueSimulator simulator() {
    return simulator;
  }

  /** The harness clock. */
  public ManualClock clock() {
    return clock;
  }

  /** Moves time forward and runs due events. */
  public void advance(final long nanos) {
    simulator.locked(() -> clock.advance(nanos));
    simulator.onTime();
  }

  /**
   * Moves to the next scheduled event if it is within {@code maxNanos}, else by {@code maxNanos}.
   * Returns true if an event was reached.
   */
  public boolean advanceToNextEvent(final long maxNanos) {
    final long next = simulator.nextEventNanos();
    final long now = clock.nanoTime();
    if (next == Long.MAX_VALUE || next - now > maxNanos) {
      advance(maxNanos);
      return false;
    }
    advance(Math.max(0, next - now));
    return true;
  }

  /** Connects an engine-side session and waits for logon. */
  public EngineSession connect(final String compId, final FixSessionHandler handler) {
    final EngineSession s = new EngineSession(simulator, clock, simulator.port(), compId, handler);
    s.logon();
    return s;
  }

  @Override
  public void close() {
    simulator.close();
  }
}
