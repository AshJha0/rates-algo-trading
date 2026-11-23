package io.ratesalgo.venues.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one owner of a simulator v2 session's market model.
 *
 * <p>Plain idea. The venues of a session share one model: an order on either Treasury venue takes
 * depth from the same book and moves the same level factor. Each venue's FIX engine has a thread
 * of its own, so two orders sent in one step used to reach the model in whichever order the two
 * threads ran, and without a lock between them. Here the FIX threads do not touch the model at
 * all. Each puts what it received (a logon, a logout, an application message) in its venue's
 * inbox and returns. One thread, the one that also moves the venues' clock and runs their timed
 * events, calls {@link #drain}: it takes the inboxes in the order the venues were registered, and
 * each inbox first in, first out.
 *
 * <p>What that gives. The model is only ever read and written by the draining thread, so there is
 * no race to lose. What a venue does is a function of the sequence of (time, venue, message) the
 * drain handed it. When a driver drains only at moments at which everything it sent has arrived,
 * as the stepped process does, that sequence is the same in every run and so is the day. A process
 * on its own threads drains whenever its venue thread comes round, so two orders sent together may
 * be taken in one drain or in two, at one clock time or at two: its day is not repeatable bit for
 * bit and is not claimed to be (docs/research/DESK_V2_WIRING.md, Section 12).
 *
 * <p>{@link #offer} may be called from any thread; {@link #drain} by one thread at a time.
 */
public final class SimSequencer {

  private final List<VenueSimulator> venues = new ArrayList<>();
  private final List<ConcurrentLinkedQueue<Runnable>> inboxes = new ArrayList<>();
  private final AtomicLong offered = new AtomicLong();
  private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
  private final AtomicLong drained = new AtomicLong();

  /** Adds a venue; its place in the drain order is the order of registration. */
  synchronized int register(final VenueSimulator venue) {
    venues.add(venue);
    inboxes.add(new ConcurrentLinkedQueue<>());
    return venues.size() - 1;
  }

  /** Queues work for the venue in {@code slot}. Any thread. */
  void offer(final int slot, final Runnable work) {
    inbox(slot).add(work);
    offered.incrementAndGet();
  }

  private synchronized ConcurrentLinkedQueue<Runnable> inbox(final int slot) {
    return inboxes.get(slot);
  }

  private synchronized int venueCount() {
    return venues.size();
  }

  /**
   * Runs what is queued: venue by venue in registration order, each venue's work in arrival
   * order. Only what was queued when a venue's turn came is run, so the pass ends. Returns the
   * number of pieces of work run.
   *
   * @throws IllegalStateException if another thread is draining
   */
  public int drain() {
    if (!draining.compareAndSet(false, true)) {
      // two owners would be the race this class exists to remove: fail loudly, not quietly
      throw new IllegalStateException("the venues are being drained by another thread");
    }
    try {
      int done = 0;
      final int count = venueCount();
      for (int v = 0; v < count; v++) {
        final ConcurrentLinkedQueue<Runnable> inbox = inbox(v);
        for (int n = inbox.size(); n > 0; n--) {
          final Runnable work = inbox.poll();
          if (work == null) {
            break;
          }
          work.run();
          done++;
        }
      }
      drained.addAndGet(done);
      return done;
    } finally {
      draining.set(false);
    }
  }

  /** Work queued and not yet run. Any thread; exact only when nothing is being offered or drained. */
  public long pending() {
    long waiting = 0;
    final int count = venueCount();
    for (int v = 0; v < count; v++) {
      waiting += inbox(v).size();
    }
    return waiting;
  }

  /** Pieces of work offered so far. Any thread. */
  public long offered() {
    return offered.get();
  }

  /** Pieces of work run so far. Any thread. */
  public long drained() {
    return drained.get();
  }
}
