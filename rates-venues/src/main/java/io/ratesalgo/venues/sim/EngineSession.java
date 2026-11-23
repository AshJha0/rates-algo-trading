package io.ratesalgo.venues.sim;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.fix.net.TcpInitiator;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.SessionState;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import quickfix.Session;

/**
 * An engine-side FIX session (our codec, not QuickFIX/J) connected over TCP to a simulator in the
 * same process. Its clock takes intervals from the system monotonic clock, so heartbeats behave,
 * and wall time from the harness's {@link ManualClock}, so stamps agree with the simulator.
 */
public final class EngineSession implements AutoCloseable {

  private final VenueSimulator simulator;
  private final String compId;
  private final TcpInitiator tcp;
  private final FixSession session;

  EngineSession(
      final VenueSimulator simulator,
      final ManualClock venueClock,
      final int port,
      final String compId,
      final FixSessionHandler handler) {
    this(simulator, venueClock, port, compId, handler, null, null, null, true);
  }

  /**
   * A session with TLS, logon credentials or resume.
   *
   * @param tls client TLS settings, or null for plain TCP
   * @param username Username (553) for the Logon, or null
   * @param password Password (554) for the Logon, or null
   * @param resetOnLogon false to keep the sequence numbers across connections and have the venue
   *     resend what was missed
   */
  EngineSession(
      final VenueSimulator simulator,
      final ManualClock venueClock,
      final int port,
      final String compId,
      final FixSessionHandler handler,
      final TlsConfig tls,
      final String username,
      final Secret password,
      final boolean resetOnLogon) {
    this.simulator = simulator;
    this.compId = compId;
    final Clock clock =
        new Clock() {
          @Override
          public long nanoTime() {
            return SystemClock.INSTANCE.nanoTime();
          }

          @Override
          public long epochNanos() {
            return venueClock.epochNanos();
          }
        };
    this.tcp =
        new TcpInitiator(
            "127.0.0.1",
            port,
            1 << 20,
            TcpInitiator.DEFAULT_CONNECT_TIMEOUT_NANOS,
            TcpInitiator.DEFAULT_MAX_WRITE_SPINS,
            tls);
    FixSessionConfig config = new FixSessionConfig(compId, simulator.compId(), 30, resetOnLogon);
    if (username != null) {
      config = config.withCredentials(username, password);
    }
    this.session = new FixSession(config, clock, tcp, handler);
    tcp.attach(session);
  }

  /** The engine-side session. */
  public FixSession session() {
    return session;
  }

  /** The TCP transport (for chaos filters). */
  public TcpInitiator transport() {
    return tcp;
  }

  /** Connects and waits for the logon to complete. */
  public void logon() {
    tcp.connect();
    pollUntil(
        () -> session.state() == SessionState.ACTIVE && simulator.loggedOn().contains(compId), 10_000);
  }

  /** Reads and dispatches what has arrived. */
  public int poll() {
    return tcp.poll();
  }

  /** Polls until the condition holds; throws after the timeout. */
  public void pollUntil(final BooleanSupplier condition, final long timeoutMillis) {
    final long deadline = SystemClock.INSTANCE.nanoTime() + timeoutMillis * 1_000_000L;
    while (!condition.getAsBoolean()) {
      tcp.poll();
      if (condition.getAsBoolean()) {
        return;
      }
      if (SystemClock.INSTANCE.nanoTime() > deadline) {
        throw new IllegalStateException(
            compId + ": condition not met within " + timeoutMillis + " ms; session " + session.state());
      }
      LockSupport.parkNanos(50_000L);
    }
  }

  /**
   * Waits until both sides have processed everything the other has sent: our expected inbound
   * number equals the simulator's next outbound number and vice versa.
   */
  public void quiesce() {
    final Session venue = simulator.session(compId);
    pollUntil(
        () ->
            venue.getExpectedSenderNum() == session.expectedInSeq()
                && venue.getExpectedTargetNum() == session.nextOutSeq(),
        10_000);
  }

  /** Drops the TCP connection and waits until the simulator has seen the logout. */
  public void disconnect() {
    tcp.disconnect();
    pollUntil(() -> !simulator.loggedOn().contains(compId), 10_000);
  }

  @Override
  public void close() {
    if (session.state() == SessionState.ACTIVE) {
      session.logout();
      try {
        pollUntil(() -> !tcp.isConnected(), 2_000);
      } catch (IllegalStateException e) {
        tcp.disconnect();
      }
    }
    tcp.close();
  }
}
