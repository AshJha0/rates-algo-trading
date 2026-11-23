package io.ratesalgo.app.sim;

import io.ratesalgo.app.gateway.VenueSession;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The delivery rule of a process that is stepped on one thread against venues whose work is
 * sequenced ({@link SimulatedVenuesV2}).
 *
 * <p>Plain idea. A stepped day should be the same day every time it is run. Bytes on a loopback
 * socket arrive when the operating system delivers them, so a pump that polls four sessions in
 * turn and takes whatever has arrived puts their messages into the loop's inbound ring in an
 * order that depends on timing. This rule removes the timing: a session is polled until it has
 * written everything it has to send, the venue has received it, and it has read everything the
 * venue has sent, before the next session is looked at. The venues answer only when they are
 * drained, which the pump does when nothing else moves, so there is nothing in flight that the
 * rule does not wait for.
 *
 * <p>The wait is on FIX sequence numbers, with a deadline for a venue that has stopped answering.
 * A run that hits the deadline goes on and is no longer promised to repeat.
 */
public final class SteppedDelivery {

  /** How long one session is waited for before the pump moves on: far longer than loopback needs. */
  public static final long SETTLE_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);

  private SteppedDelivery() {}

  /**
   * True when {@code ours} and the venue's end {@code theirs} agree on both directions: the venue
   * has taken every message we sent and we have read every message it sent. A session that is
   * down, or a venue end that does not exist yet, has nothing in flight.
   */
  public static boolean inSync(final VenueSession ours, final quickfix.Session theirs) {
    if (!ours.isActive() || theirs == null) {
      return true;
    }
    // QuickFIX/J's "sender" number is the venue's own next outgoing sequence number
    return theirs.getExpectedSenderNum() == ours.session().expectedInSeq()
        && theirs.getExpectedTargetNum() == ours.session().nextOutSeq();
  }

  /**
   * Runs {@code ours} until a duty cycle does nothing and the two ends agree. Returns the work
   * done.
   */
  public static int settle(final VenueSession ours, final java.util.function.Supplier<quickfix.Session> theirs) {
    final long deadline = System.nanoTime() + SETTLE_DEADLINE_NANOS;
    int total = 0;
    while (true) {
      final int work = ours.doWork();
      total += work;
      if (work == 0) {
        if (inSync(ours, theirs.get()) || System.nanoTime() - deadline >= 0) {
          return total;
        }
        LockSupport.parkNanos(20_000L);
      }
    }
  }
}
