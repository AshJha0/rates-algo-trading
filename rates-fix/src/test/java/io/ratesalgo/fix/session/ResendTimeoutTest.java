package io.ratesalgo.fix.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.ScriptedVenue;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A ResendRequest the venue never answers (review finding L7). Before the timeout existed the
 * session stayed ACTIVE on heartbeats and dropped every later message; now it repeats the request
 * and then leaves the session, so the venue's cancel-on-disconnect applies.
 */
class ResendTimeoutTest {

  private static final long SECOND = 1_000_000_000L;

  private ManualClock clock;
  private ScriptedVenue venue;
  private FixSession session;
  private int disconnects;
  private int delivered;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(1_793_629_800_000_000_000L);
    venue = new ScriptedVenue(clock);
    session =
        new FixSession(
            new FixSessionConfig("ENGINE", "VENUE", 30, true, 2 * SECOND, 2),
            clock,
            venue,
            new FixSessionHandler() {
              @Override
              public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {
                delivered++;
              }

              @Override
              public void onLogon(final FixSession s) {}

              @Override
              public void onDisconnect(final FixSession s) {
                disconnects++;
              }

              @Override
              public void onSessionReject(final FixSession s, final FixParser m) {}
            });
    session.onConnected();
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
  }

  private void rfq(final String id) {
    venue.begin(MsgType.QUOTE_REQUEST).putText(FixTags.QUOTE_REQ_ID, id);
    venue.deliver(session);
  }

  private void heartbeat() {
    venue.begin(MsgType.HEARTBEAT);
    venue.deliver(session);
  }

  @Test
  void anUnansweredResendIsRepeatedThenTheSessionLogsOut() {
    rfq("a");
    venue.skip(); // lost
    rfq("b"); // gap: ResendRequest
    assertEquals(1, session.stats().resendRequestsSent());
    assertTrue(session.resendPending());

    // the venue heartbeats but never fills the gap
    clock.advance(SECOND);
    heartbeat();
    assertFalse(session.poll());
    assertEquals(1, session.stats().resendRequestsSent(), "before the timeout nothing is repeated");

    clock.advance(SECOND);
    assertFalse(session.poll());
    assertEquals(2, session.stats().resendRequestsSent(), "first repeat at the timeout");
    assertTrue(venue.last().contains("|35=2|"), venue.last());
    assertTrue(venue.last().contains("|7=3|16=0|"), venue.last());

    clock.advance(2 * SECOND);
    heartbeat();
    assertFalse(session.poll());
    assertEquals(3, session.stats().resendRequestsSent(), "second repeat");
    assertEquals(SessionState.ACTIVE, session.state());

    clock.advance(2 * SECOND);
    assertTrue(session.poll(), "repeats exhausted: the session ends");
    assertEquals(1, session.stats().resendTimeouts());
    assertTrue(venue.last().contains("|35=5|"), "Logout sent before the disconnect: " + venue.last());
    assertTrue(venue.disconnected);
    assertEquals(1, delivered, "only the message before the gap was delivered");
    assertEquals(1, session.stats().gapsDetected());
  }

  @Test
  void aFilledGapStopsTheTimer() {
    rfq("a");
    venue.skip();
    rfq("b");
    assertTrue(session.resendPending());
    venue.beginSeq(MsgType.SEQUENCE_RESET, 3, true).putBool(FixTags.GAP_FILL_FLAG, true).put(FixTags.NEW_SEQ_NO, 5);
    venue.deliver(session);
    assertFalse(session.resendPending());
    clock.advance(10 * SECOND);
    assertFalse(session.poll());
    assertEquals(1, session.stats().resendRequestsSent());
    assertEquals(0, session.stats().resendTimeouts());
    assertEquals(SessionState.ACTIVE, session.state());
    assertEquals(0, disconnects);
  }
}
