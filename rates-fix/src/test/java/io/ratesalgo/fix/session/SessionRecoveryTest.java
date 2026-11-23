package io.ratesalgo.fix.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.ScriptedVenue;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Logon credentials, and resuming a session whose sequence numbers are kept: across a reconnect,
 * across a restart of the process, with a gap, with duplicates, and when the venue refuses.
 */
class SessionRecoveryTest {

  private static final String PASSWORD = "s3cret-Logon-Word";

  @TempDir Path dir;

  private ManualClock clock;
  private ScriptedVenue venue;
  private final List<String> fills = new ArrayList<>();
  private final List<String> logouts = new ArrayList<>();
  private int logons;
  private int disconnects;

  private final FixSessionHandler handler =
      new FixSessionHandler() {
        @Override
        public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {
          fills.add(m.getString(FixTags.EXEC_ID) + (possDup ? "*" : ""));
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
        public void onLogout(final FixSession s, final FixParser m, final boolean logonRefused) {
          logouts.add((logonRefused ? "refused:" : "logout:") + m.getString(FixTags.TEXT));
        }
      };

  @BeforeEach
  void setUp() {
    clock = new ManualClock(1_793_629_800_000_000_000L);
    venue = new ScriptedVenue(clock);
  }

  private FixSession resuming() {
    return new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, false), clock, venue, handler);
  }

  /** The venue's Logon reply, with its next sequence number and no reset flag. */
  private void venueLogon(final FixSession session) {
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30);
    venue.deliver(session);
  }

  private void fill(final FixSession session, final String execId) {
    venue.begin(MsgType.EXECUTION_REPORT).putText(FixTags.EXEC_ID, execId);
    venue.deliver(session);
  }

  private void resentFill(final FixSession session, final long seq, final String execId) {
    venue.beginSeq(MsgType.EXECUTION_REPORT, seq, true).putText(FixTags.EXEC_ID, execId);
    venue.deliver(session);
  }

  private static void order(final FixSession session, final int clOrdId) {
    session.begin(MsgType.NEW_ORDER_SINGLE).put(FixTags.CL_ORD_ID, clOrdId);
    session.send();
  }

  // ------------------------------------------------------------------ credentials

  @Test
  void credentialsTravelOnTheLogonWhenConfiguredAndOnlyThen() {
    final FixSessionConfig config =
        new FixSessionConfig("ENGINE", "VENUE", 30, true).withCredentials("desk-1", Secret.of(PASSWORD));
    final FixSession session = new FixSession(config, clock, venue, handler);
    session.onConnected();
    assertTrue(venue.last().contains("|98=0|108=30|141=Y|553=desk-1|554=" + PASSWORD + "|"), "Logon carries 553 and 554");
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
    assertEquals(SessionState.ACTIVE, session.state());
    // only the Logon carries them
    order(session, 1);
    assertFalse(venue.last().contains("|553=") || venue.last().contains("|554="), venue.last());

    final FixSession anonymous =
        new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, venue, handler);
    anonymous.onConnected();
    assertTrue(venue.last().contains("|35=A|"), venue.last());
    assertFalse(venue.last().contains("|553=") || venue.last().contains("|554="), venue.last());
  }

  @Test
  void thePasswordIsNotPrintedAndNotLeftInTheSessionBuffers() {
    final FixSessionConfig config =
        new FixSessionConfig("ENGINE", "VENUE", 30, false, 1_000_000_000L, 1).withCredentials("desk-1", Secret.of(PASSWORD));
    assertFalse(config.toString().contains(PASSWORD), config.toString());
    assertTrue(config.toString().contains("username=desk-1") && config.toString().contains("Secret[****]"), config.toString());
    assertEquals("desk-1", config.username());
    assertFalse(config.resetOnLogon());
    assertTrue(config.withResetOnLogon(true).resetOnLogon());
    assertEquals("desk-1", config.withResetOnLogon(true).username());
    assertEquals(1, config.withResetOnLogon(true).maxResendRetries());

    // a transport that keeps hold of the buffer it was given, which a real one must not
    final DirectBuffer[] seen = new DirectBuffer[1];
    final int[] length = new int[1];
    final boolean[] onTheWire = new boolean[1];
    final byte[] secret = PASSWORD.getBytes(StandardCharsets.US_ASCII);
    final FixTransport keeping =
        new FixTransport() {
          @Override
          public void send(final DirectBuffer buffer, final int offset, final int len) {
            seen[0] = buffer;
            length[0] = len;
            onTheWire[0] = indexOf(buffer, len, secret) >= 0;
          }

          @Override
          public void disconnect() {}
        };
    final FixSession session = new FixSession(config, clock, keeping, handler);
    session.onConnected();
    assertTrue(onTheWire[0], "the transport was handed the password");
    assertTrue(length[0] > secret.length);
    assertEquals(-1, indexOf(seen[0], seen[0].capacity(), secret), "and afterwards the buffer no longer holds it");
    for (int i = 0; i < length[0]; i++) {
      assertEquals(0, seen[0].getByte(i), "byte " + i + " of the sent Logon was overwritten");
    }
    // nothing the session says about itself contains it
    final IllegalStateException notActive =
        assertThrows(IllegalStateException.class, () -> session.begin(MsgType.NEW_ORDER_SINGLE));
    assertFalse(notActive.getMessage().contains(PASSWORD));
  }

  private static int indexOf(final DirectBuffer buffer, final int length, final byte[] needle) {
    for (int i = 0; i + needle.length <= length; i++) {
      int j = 0;
      while (j < needle.length && buffer.getByte(i + j) == needle[j]) {
        j++;
      }
      if (j == needle.length) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void credentialsComeInPairsAndMustFitAFixField() {
    final FixSessionConfig base = new FixSessionConfig("ENGINE", "VENUE", 30, true);
    assertThrows(IllegalArgumentException.class, () -> base.withCredentials("desk-1", null));
    assertThrows(IllegalArgumentException.class, () -> base.withCredentials(null, Secret.of(PASSWORD)));
    assertThrows(IllegalArgumentException.class, () -> base.withCredentials("", Secret.of(PASSWORD)));
    assertThrows(IllegalArgumentException.class, () -> base.withCredentials("desk\u00011", Secret.of(PASSWORD)));
    final String broken = "pass\u0001word-Xy";
    final IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> base.withCredentials("desk-1", Secret.of(broken)));
    assertFalse(refused.getMessage().contains("word-Xy"), refused.getMessage());
    assertEquals(null, base.username());
    assertEquals(null, base.password());
  }

  @Test
  void aLogoutInAnswerToTheLogonIsARefusedLogon() {
    final FixSession session =
        new FixSession(
            new FixSessionConfig("ENGINE", "VENUE", 30, true).withCredentials("desk-1", Secret.of(PASSWORD)),
            clock,
            venue,
            handler);
    session.onConnected();
    venue.begin(MsgType.LOGOUT).putText(FixTags.TEXT, "logon refused: invalid credentials");
    venue.deliver(session);
    assertEquals(List.of("refused:logon refused: invalid credentials"), logouts);
    assertEquals(1, session.stats().logonsRefused());
    assertEquals(0, logons);
    assertTrue(venue.disconnected);
    assertTrue(venue.last().contains("|35=5|"), venue.last());
    session.onDisconnected();
    assertEquals(1, disconnects);

    // an ordinary logout of an active session is not a refusal
    final FixSession active = new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, venue, handler);
    active.onConnected();
    venue.beginSeq(MsgType.LOGON, 1, false).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(active);
    venue.beginSeq(MsgType.LOGOUT, 2, false).putText(FixTags.TEXT, "end of day");
    venue.deliver(active);
    assertEquals("logout:end of day", logouts.get(1));
    assertEquals(0, active.stats().logonsRefused());
  }

  // ------------------------------------------------------------------ resume

  @Test
  void withResetOffTheNumbersContinueAcrossAReconnect() {
    final FixSession session = resuming();
    session.onConnected();
    assertTrue(venue.last().contains("|35=A|49=ENGINE|56=VENUE|34=1|"), venue.last());
    assertFalse(venue.last().contains("|141="), "no ResetSeqNumFlag: " + venue.last());
    venueLogon(session);
    assertEquals(SessionState.ACTIVE, session.state());
    order(session, 1);
    order(session, 2);
    fill(session, "e1");
    assertEquals(4, session.nextOutSeq());
    assertEquals(3, session.expectedInSeq());

    session.onDisconnected();
    session.onConnected();
    assertTrue(venue.last().contains("|35=A|49=ENGINE|56=VENUE|34=4|"), "the Logon continues our numbers: " + venue.last());
    assertFalse(venue.last().contains("|141="), venue.last());
    assertEquals(3, session.expectedInSeq(), "and the inbound number was not reset");
    venueLogon(session); // the venue's 3: exactly what we expect
    assertEquals(SessionState.ACTIVE, session.state());
    assertEquals(2, logons);
    assertEquals(0, session.stats().gapsDetected());
    assertEquals(0, session.stats().resendRequestsSent());
    fill(session, "e2");
    assertEquals(List.of("e1", "e2"), fills);
    assertEquals(0, session.stats().sequenceErrors());
  }

  @Test
  void fillsMissedWhileDisconnectedAreResentOnceEachFlaggedPossDup() {
    final FixSession session = resuming();
    session.onConnected();
    venueLogon(session);
    fill(session, "e1"); // venue 2
    session.onDisconnected();
    // the venue filled us three times while we were away: its 3, 4 and 5
    venue.skip();
    venue.skip();
    venue.skip();

    session.onConnected();
    venueLogon(session); // the venue's 6, and we expect 3
    assertEquals(SessionState.ACTIVE, session.state(), "the Logon is accepted although it opened a gap");
    assertEquals(1, session.stats().gapsDetected());
    assertEquals(1, session.stats().resendRequestsSent());
    assertTrue(venue.last().contains("|35=2|") && venue.last().contains("|7=3|16=0|"), venue.last());
    assertTrue(session.resendPending());

    // a live fill behind the gap is not delivered out of order; the resend will bring it
    fill(session, "e5-live"); // venue 7
    assertEquals(List.of("e1"), fills);

    resentFill(session, 3, "e2");
    resentFill(session, 4, "e3");
    resentFill(session, 5, "e4");
    // the Logon itself is admin: the venue gap-fills over it, then resends the fill behind it
    venue.beginSeq(MsgType.SEQUENCE_RESET, 6, true).putBool(FixTags.GAP_FILL_FLAG, true).put(FixTags.NEW_SEQ_NO, 7);
    venue.deliver(session);
    resentFill(session, 7, "e5-live");
    assertFalse(session.resendPending());
    assertEquals(List.of("e1", "e2*", "e3*", "e4*", "e5-live*"), fills);

    // a second copy of a resent fill is below the expected number and flagged: dropped, not delivered
    resentFill(session, 4, "e3");
    assertEquals(1, session.stats().duplicatesIgnored());
    assertEquals(5, fills.size());
    fill(session, "e6"); // venue 8, live again
    assertEquals("e6", fills.get(5));
    assertEquals(0, session.stats().sequenceTooLow());
    assertEquals(SessionState.ACTIVE, session.state());
  }

  @Test
  void aVenueNumberBelowTheExpectedOneWithoutPossDupIsFatalOnResume() {
    final FixSession session = resuming();
    session.onConnected();
    venueLogon(session);
    fill(session, "e1");
    fill(session, "e2");
    session.onDisconnected();
    assertEquals(4, session.expectedInSeq());

    session.onConnected();
    // the venue has lost its numbers and starts again at 2: nothing it says can be placed
    venue.beginSeq(MsgType.LOGON, 2, false).put(98, 0).put(108, 30);
    venue.deliver(session);
    assertEquals(1, session.stats().sequenceTooLow());
    assertEquals(1, session.stats().sequenceErrors());
    assertTrue(venue.disconnected);
    assertTrue(venue.last().contains("|35=5|"), "Logout before the disconnect: " + venue.last());
    assertTrue(session.state() != SessionState.ACTIVE);
    assertEquals(1, logons, "the second logon never completed");
  }

  @Test
  void ourOwnMessagesAreGapFilledNeverResent() {
    final FixSession session = resuming();
    session.onConnected();
    venueLogon(session);
    order(session, 1);
    order(session, 2);
    final long sentBefore = venue.sentCount();
    venue.begin(MsgType.RESEND_REQUEST).put(FixTags.BEGIN_SEQ_NO, 2).put(FixTags.END_SEQ_NO, 0);
    venue.deliver(session);
    assertEquals(1, venue.sentCount() - sentBefore, "one message answers the whole request");
    assertTrue(venue.last().contains("|35=4|") && venue.last().contains("|34=2|43=Y|"), venue.last());
    assertTrue(venue.last().contains("|123=Y|36=4|"), "a gap fill to our next number: " + venue.last());
    assertFalse(venue.sent.subList((int) sentBefore, venue.sent.size()).stream().anyMatch(m -> m.contains("|35=D|")));
    assertEquals(1, session.stats().gapFillsSent());
  }

  // ------------------------------------------------------------------ the sequence store

  @Test
  void theStoreIsToldEachTimeANumberChangesAndOnlyThen() {
    final List<String> recorded = new ArrayList<>();
    final FixSession session = resuming();
    session.sequenceStore((o, i) -> recorded.add(o + "," + i));
    session.setSequenceNumbers(5, 9);
    assertEquals(List.of("5,9"), recorded);
    session.setSequenceNumbers(5, 9);
    assertEquals(1, recorded.size(), "nothing changed, nothing recorded");
    session.onConnected(); // Logon is 5
    assertEquals("6,9", recorded.get(1));
    venue.beginSeq(MsgType.LOGON, 9, false).put(98, 0).put(108, 30);
    venue.deliver(session);
    assertEquals("6,10", recorded.get(2));
    order(session, 1);
    assertEquals("7,10", recorded.get(3));
    // a duplicate moves neither number
    venue.beginSeq(MsgType.EXECUTION_REPORT, 9, true).putText(FixTags.EXEC_ID, "old");
    venue.deliver(session);
    assertEquals(4, recorded.size());
    assertThrows(IllegalArgumentException.class, () -> session.setSequenceNumbers(0, 1));

    // with ResetSeqNumFlag on, connecting records the reset before the Logon goes out
    final List<String> reset = new ArrayList<>();
    final FixSession resetting = new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, venue, handler);
    resetting.setSequenceNumbers(40, 50);
    resetting.sequenceStore((o, i) -> reset.add(o + "," + i));
    resetting.onConnected();
    assertEquals(List.of("1,1", "2,1"), reset);
    resetting.sequenceStore(null); // back to recording nothing
    resetting.setSequenceNumbers(3, 3);
    assertEquals(2, reset.size());
  }

  @Test
  void aRestartedProcessResumesTheSessionFromTheMappedStore() {
    final Path file = dir.resolve("ENGINE-VENUE.seq");
    MappedSequenceStore store = MappedSequenceStore.open(file);
    FixSession session = resuming();
    session.setSequenceNumbers(store.nextOutSeq(), store.resumeInSeq());
    session.sequenceStore(store);
    session.onConnected();
    venueLogon(session);
    order(session, 1);
    fill(session, "e1");
    fill(session, "e2");
    store.safeInSeq(3); // e1 has been journalled, e2 not yet
    assertEquals(3, session.nextOutSeq());
    assertEquals(4, session.expectedInSeq());
    // the process dies: no logout, no clean close
    store.closeWithoutMarkingClean();

    store = MappedSequenceStore.open(file);
    assertTrue(store.existed());
    assertFalse(store.cleanAtOpen());
    assertEquals(3, store.nextOutSeq());
    assertEquals(4, store.expectedInSeq());
    assertEquals(3, store.resumeInSeq(), "e2 may not have been processed: ask for it again");
    session = resuming();
    session.setSequenceNumbers(store.nextOutSeq(), store.resumeInSeq());
    session.sequenceStore(store);
    session.onConnected();
    assertTrue(venue.last().contains("|35=A|49=ENGINE|56=VENUE|34=3|"), "the new process continues at 3: " + venue.last());
    venueLogon(session); // the venue's 4, we resume at 3: a gap of one
    assertTrue(venue.last().contains("|35=2|") && venue.last().contains("|7=3|16=0|"), venue.last());
    resentFill(session, 3, "e2");
    venue.beginSeq(MsgType.SEQUENCE_RESET, 4, true).putBool(FixTags.GAP_FILL_FLAG, true).put(FixTags.NEW_SEQ_NO, 5);
    venue.deliver(session);
    assertEquals(List.of("e1", "e2", "e2*"), fills, "the replay is flagged, so the order tracker books e2 once");
    assertEquals(5, session.expectedInSeq());
    assertEquals(5, store.expectedInSeq());
    assertEquals(5, store.nextOutSeq()); // Logon 3, ResendRequest 4
    store.close();
    store = MappedSequenceStore.open(file);
    assertTrue(store.cleanAtOpen());
    assertEquals(5, store.resumeInSeq());
    store.close();
  }
}
