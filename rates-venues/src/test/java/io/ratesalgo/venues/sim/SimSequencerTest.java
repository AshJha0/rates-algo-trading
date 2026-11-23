package io.ratesalgo.venues.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;

/**
 * The sequencer's rule: FIX threads only queue, one thread handles, venue by venue in
 * registration order and each venue first in, first out.
 */
class SimSequencerTest {

  /** A venue that records what it was asked to handle. */
  private static final class Recording extends VenueSimulator {
    private final List<String> seen;

    Recording(final String compId, final List<String> seen) {
      super(compId, List.of("RALGO"), new ManualClock(0L), 1L);
      this.seen = seen;
    }

    @Override
    protected void onCounterpartyLogon(final String counterparty) {
      seen.add(compId() + " logon " + counterparty);
    }

    @Override
    protected void onCounterpartyLogout(final String counterparty) {
      seen.add(compId() + " logout " + counterparty);
    }

    @Override
    protected void onMessage(final String counterparty, final Message message) {
      seen.add(compId() + " message " + counterparty);
    }
  }

  private static SessionID session(final String venue) {
    return new SessionID("FIX.4.4", venue, "RALGO");
  }

  @Test
  void workIsRunVenueByVenueInRegistrationOrderAndFirstInFirstOutWithinAVenue() {
    final SimSequencer sequencer = new SimSequencer();
    final List<String> ran = new ArrayList<>();
    final int first = sequencer.register(new Recording("A", ran));
    final int second = sequencer.register(new Recording("B", ran));
    sequencer.offer(second, () -> ran.add("B1"));
    sequencer.offer(first, () -> ran.add("A1"));
    sequencer.offer(second, () -> ran.add("B2"));
    sequencer.offer(first, () -> ran.add("A2"));
    assertEquals(4, sequencer.pending());
    assertEquals(4, sequencer.offered());
    assertTrue(ran.isEmpty(), "nothing runs until the owner drains");

    assertEquals(4, sequencer.drain());
    // arrival order was B1 A1 B2 A2: the order handled does not depend on it across venues
    assertEquals(List.of("A1", "A2", "B1", "B2"), ran);
    assertEquals(0, sequencer.pending());
    assertEquals(4, sequencer.drained());
    assertEquals(0, sequencer.drain());
  }

  @Test
  void workQueuedDuringADrainForAVenueAlreadyPassedWaitsForTheNext() {
    final SimSequencer sequencer = new SimSequencer();
    final List<String> ran = new ArrayList<>();
    final int first = sequencer.register(new Recording("A", ran));
    final int second = sequencer.register(new Recording("B", ran));
    sequencer.offer(second, () -> {
      ran.add("B1");
      sequencer.offer(first, () -> ran.add("A-late"));
      sequencer.offer(second, () -> ran.add("B-late"));
    });
    assertEquals(1, sequencer.drain(), "a pass handles what was queued when a venue's turn came, and ends");
    assertEquals(List.of("B1"), ran);
    assertEquals(2, sequencer.pending());
    assertEquals(2, sequencer.drain());
    assertEquals(List.of("B1", "A-late", "B-late"), ran);
  }

  @Test
  void aSecondThreadDrainingBesideTheOwnerIsRefused() throws InterruptedException {
    final SimSequencer sequencer = new SimSequencer();
    final int slot = sequencer.register(new Recording("A", new ArrayList<>()));
    final AtomicReference<Throwable> other = new AtomicReference<>();
    sequencer.offer(slot, () -> {
      // while the owner is inside drain, another thread tries; the owner waits for its answer
      final Thread intruder = new Thread(() -> {
        try {
          sequencer.drain();
        } catch (final RuntimeException e) {
          other.set(e);
        }
      });
      intruder.start();
      try {
        intruder.join();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    assertEquals(1, sequencer.drain());
    assertInstanceOf(IllegalStateException.class, other.get());
    // the refusal left the owner's drain intact and the guard free
    assertEquals(0, sequencer.drain());
  }

  @Test
  void aSequencedVenueHandlesLogonsMessagesAndLogoutsOnlyWhenDrained() {
    final SimSequencer sequencer = new SimSequencer();
    final List<String> seen = new ArrayList<>();
    final Recording a = new Recording("A", seen);
    final Recording b = new Recording("B", seen);
    a.sequenceWith(sequencer);
    b.sequenceWith(sequencer);

    // what the FIX threads of the two venues deliver, B's first
    b.onLogon(session("B"));
    b.fromApp(new Message(), session("B"));
    a.onLogon(session("A"));
    a.fromApp(new Message(), session("A"));
    b.onLogout(session("B"));
    assertTrue(seen.isEmpty(), "the FIX threads only queued");
    assertTrue(a.loggedOn().isEmpty() && b.loggedOn().isEmpty());
    assertEquals(5, sequencer.pending());

    assertEquals(5, sequencer.drain());
    assertEquals(
        List.of("A logon RALGO", "A message RALGO", "B logon RALGO", "B message RALGO", "B logout RALGO"), seen);
    assertTrue(a.loggedOn().contains("RALGO"));
    assertTrue(b.loggedOn().isEmpty());
  }

  @Test
  void aVenueWithoutASequencerHandlesOnTheCallingThreadAsBefore() {
    final List<String> seen = new ArrayList<>();
    final Recording a = new Recording("A", seen);
    a.onLogon(session("A"));
    a.fromApp(new Message(), session("A"));
    a.onLogout(session("A"));
    a.onLogout(session("A"));
    assertEquals(List.of("A logon RALGO", "A message RALGO", "A logout RALGO"), seen);
  }

  @Test
  void aVenueThatIsListeningCannotBeHandedToASequencer() {
    final Recording a = new Recording("A", new ArrayList<>());
    a.start(VenueHarness.freePort());
    try {
      assertThrows(IllegalStateException.class, () -> a.sequenceWith(new SimSequencer()));
    } finally {
      a.close();
    }
  }
}
