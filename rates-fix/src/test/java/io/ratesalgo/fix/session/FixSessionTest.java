package io.ratesalgo.fix.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.ScriptedVenue;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixSessionTest {

  private ManualClock clock;
  private ScriptedVenue venue;
  private FixSession session;
  private final List<String> app = new ArrayList<>();
  private int logons;
  private int disconnects;
  private int rejects;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(1_793_629_800_000_000_000L);
    venue = new ScriptedVenue(clock);
    session =
        new FixSession(
            new FixSessionConfig("ENGINE", "VENUE", 30, true),
            clock,
            venue,
            new FixSessionHandler() {
              @Override
              public void onApplicationMessage(
                  final FixSession s, final FixParser m, final boolean possDup) {
                app.add(m.getString(FixTags.QUOTE_REQ_ID) + (possDup ? "*" : ""));
              }

              @Override
              public void onLogon(final FixSession s) {
                logons++;
              }

              @Override
              public void onDisconnect(final FixSession s) {
                disconnects++;
              }

              @Override
              public void onSessionReject(final FixSession s, final FixParser m) {
                rejects++;
              }
            });
  }

  private void logon() {
    session.onConnected();
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
  }

  private void rfq(final String id) {
    venue.begin(MsgType.QUOTE_REQUEST).putText(FixTags.QUOTE_REQ_ID, id);
    venue.deliver(session);
  }

  @Test
  void logonHandshake() {
    session.onConnected();
    assertEquals(SessionState.LOGON_SENT, session.state());
    assertTrue(venue.last().contains("|35=A|49=ENGINE|56=VENUE|34=1|"), venue.last());
    assertTrue(venue.last().contains("|98=0|108=30|141=Y|"));
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
    assertEquals(SessionState.ACTIVE, session.state());
    assertEquals(1, logons);
    assertEquals(2, session.expectedInSeq());
    assertEquals(2, session.nextOutSeq());
    assertEquals(clock, session.clock());
  }

  @Test
  void heartbeatsTestRequestsAndTimeout() {
    logon();
    clock.advance(30_000_000_000L);
    rfq("1");
    session.poll();
    assertTrue(venue.last().contains("|35=0|"), venue.last());
    assertEquals(1, session.stats().heartbeatsSent());
    clock.advance(36_000_000_000L);
    session.poll();
    assertTrue(venue.last().contains("|35=1|") && venue.last().contains("|112=TEST1|"), venue.last());
    venue.begin(MsgType.HEARTBEAT).putText(FixTags.TEST_REQ_ID, "TEST1");
    venue.deliver(session);
    clock.advance(36_000_000_000L);
    session.poll();
    assertEquals(2, session.stats().testRequestsSent());
    clock.advance(30_000_000_000L);
    assertTrue(session.poll());
    assertTrue(venue.disconnected);
    assertEquals(1, session.stats().timeouts());
    session.onDisconnected();
    session.onDisconnected();
    assertEquals(1, disconnects);
    assertFalse(session.poll());
  }

  @Test
  void answersTestRequestWithItsId() {
    logon();
    venue.begin(MsgType.TEST_REQUEST).putText(FixTags.TEST_REQ_ID, "ABC");
    venue.deliver(session);
    assertTrue(venue.last().contains("|35=0|") && venue.last().contains("|112=ABC|"), venue.last());
  }

  @Test
  void resendRequestIsAnsweredWithOneGapFill() {
    logon();
    for (int i = 0; i < 3; i++) {
      session.begin(MsgType.QUOTE).put(FixTags.QUOTE_ID, i);
      session.send();
    }
    assertEquals(5, session.nextOutSeq());
    venue.begin(MsgType.RESEND_REQUEST).put(7, 2).put(16, 0);
    venue.deliver(session);
    final String fill = venue.last();
    assertTrue(fill.contains("|35=4|") && fill.contains("|34=2|") && fill.contains("|43=Y|"), fill);
    assertTrue(fill.contains("|123=Y|36=5|") && fill.contains("|122="), fill);
    assertEquals(5, session.nextOutSeq());
    assertEquals(1, session.stats().gapFillsSent());
    venue.begin(MsgType.RESEND_REQUEST).put(7, 9).put(16, 0);
    venue.deliver(session);
    assertEquals(1, session.stats().gapFillsSent());
  }

  @Test
  void inboundGapTriggersOneResendAndIsFilledByPossDup() {
    logon();
    rfq("a"); // seq 2
    venue.skip(); // seq 3 lost
    rfq("c"); // seq 4 arrives early
    assertTrue(venue.last().contains("|35=2|") && venue.last().contains("|7=3|16=0|"), venue.last());
    rfq("d"); // seq 5, still in the gap: no second resend
    assertEquals(1, session.stats().resendRequestsSent());
    for (long s = 3; s <= 5; s++) {
      venue.beginSeq(MsgType.QUOTE_REQUEST, s, true).putText(FixTags.QUOTE_REQ_ID, "r" + s);
      venue.deliver(session);
    }
    rfq("e");
    assertEquals(List.of("a", "r3*", "r4*", "r5*", "e"), app);
    assertEquals(1, session.stats().gapsDetected());
    assertEquals(1, session.stats().sequenceErrors());
    venue.beginSeq(MsgType.QUOTE_REQUEST, 4, true).putText(FixTags.QUOTE_REQ_ID, "dup");
    venue.deliver(session);
    assertEquals(1, session.stats().duplicatesIgnored());
  }

  @Test
  void gapFilledBySequenceResetGapFill() {
    logon();
    venue.skip();
    venue.skip();
    rfq("x"); // seq 4
    venue.beginSeq(MsgType.SEQUENCE_RESET, 2, true).putBool(FixTags.GAP_FILL_FLAG, true).put(36, 4);
    venue.deliver(session);
    venue.beginSeq(MsgType.QUOTE_REQUEST, 4, true).putText(FixTags.QUOTE_REQ_ID, "x");
    venue.deliver(session);
    assertEquals(List.of("x*"), app);
    assertEquals(5, session.expectedInSeq());
  }

  @Test
  void sequenceResetModeAndTooLow() {
    logon();
    venue.beginSeq(MsgType.SEQUENCE_RESET, 99, false).put(36, 10);
    venue.deliver(session);
    assertEquals(10, session.expectedInSeq());
    assertEquals(1, session.stats().sequenceResets());
    venue.beginSeq(MsgType.QUOTE_REQUEST, 3, false).putText(FixTags.QUOTE_REQ_ID, "low");
    venue.deliver(session);
    assertEquals(1, session.stats().sequenceTooLow());
    assertTrue(venue.disconnected);
    assertTrue(venue.last().contains("|35=5|"));
  }

  @Test
  void garbledAndWrongCompIdsAndFormatErrors() {
    logon();
    venue.begin(MsgType.QUOTE_REQUEST).putText(FixTags.QUOTE_REQ_ID, "1");
    final int n = venue.finish();
    venue.buffer().putByte(n - 3, (byte) '9');
    venue.buffer().putByte(n - 2, (byte) '9');
    session.onMessage(venue.buffer(), 0, n);
    assertEquals(1, session.stats().garbled());
    final ScriptedVenue other = new ScriptedVenue(clock);
    final FixSession strict =
        new FixSession(
            new FixSessionConfig("ENGINE", "OTHER", 30, true),
            clock,
            other,
            (s, m, d) -> m.getDecimal(FixTags.PRICE, 9));
    strict.onConnected();
    other.begin(MsgType.LOGON).put(98, 0).put(108, 30);
    other.deliver(strict);
    assertTrue(other.last().contains("|35=5|"), other.last());
    assertEquals(1, strict.stats().rejectsSent());
    venue.beginSeq(MsgType.QUOTE_REQUEST, 2, false).putText(FixTags.PRICE, "abc");
    venue.deliver(session);
    assertEquals(1, session.stats().rejectsSent());
  }

  @Test
  void handlerFormatErrorBecomesSessionReject() {
    final FixSession s =
        new FixSession(
            new FixSessionConfig("ENGINE", "VENUE", 30, true),
            clock,
            venue,
            (fs, m, d) -> m.getDecimal(FixTags.PRICE, 9));
    s.onConnected();
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(s);
    venue.begin(MsgType.QUOTE).putText(FixTags.PRICE, "9x");
    venue.deliver(s);
    assertTrue(venue.last().contains("|35=3|") && venue.last().contains("|371=44|373=5|"), venue.last());
    venue.begin(MsgType.REJECT).put(45, 2);
    venue.deliver(s);
    assertEquals(1, s.stats().rejectsReceived());
    assertEquals(0, rejects);
  }

  @Test
  void logoutBothWaysAndLogonTimeout() {
    logon();
    venue.begin(MsgType.LOGOUT);
    venue.deliver(session);
    assertTrue(venue.last().contains("|35=5|"));
    assertTrue(venue.disconnected);
    setUp();
    logon();
    session.logout();
    assertEquals(SessionState.LOGOUT_SENT, session.state());
    final int before = venue.sent.size();
    venue.begin(MsgType.LOGOUT);
    venue.deliver(session);
    assertEquals(before, venue.sent.size());
    assertTrue(venue.disconnected);
    setUp();
    session.onConnected();
    clock.advance(31_000_000_000L);
    assertTrue(session.poll());
    assertEquals(1, session.stats().timeouts());
  }

  @Test
  void rulesOnStateAndConfig() {
    assertThrows(IllegalStateException.class, () -> session.begin(MsgType.QUOTE));
    assertThrows(IllegalArgumentException.class, () -> session.setSequenceNumbers(0, 1));
    session.setSequenceNumbers(7, 9);
    assertEquals(7, session.nextOutSeq());
    assertEquals(9, session.expectedInSeq());
    assertThrows(IllegalArgumentException.class, () -> new FixSessionConfig("", "V", 30, true));
    assertThrows(IllegalArgumentException.class, () -> new FixSessionConfig("E", "V", 0, true));
    session.logout();
    assertEquals(SessionState.DISCONNECTED, session.state());
  }

  @Test
  void logonWithGapIsAcceptedThenResendRequested() {
    final FixSession s =
        new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, false), clock, venue, (fs, m, d) -> {});
    s.setSequenceNumbers(1, 1);
    s.onConnected();
    venue.beginSeq(MsgType.LOGON, 5, false).put(98, 0).put(108, 30);
    venue.deliver(s);
    assertEquals(SessionState.ACTIVE, s.state());
    assertTrue(venue.last().contains("|35=2|") && venue.last().contains("|7=1|"), venue.last());
    venue.beginSeq(MsgType.RESEND_REQUEST, 9, false).put(7, 1).put(16, 0);
    venue.deliver(s);
    assertTrue(venue.last().contains("|35=4|"), venue.last());
    venue.beginSeq(MsgType.LOGOUT, 10, false);
    venue.deliver(s);
    assertTrue(venue.disconnected);
  }
}
