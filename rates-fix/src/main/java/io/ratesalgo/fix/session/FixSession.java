package io.ratesalgo.fix.session;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.fix.codec.FixFormatException;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Engine-side FIX 4.4 session (initiator). Owns both sequence numbers, the heartbeat and
 * test-request timers, and the inbound gap state. Single-threaded: the owning thread calls
 * {@link #onMessage}, {@link #poll} and the send methods.
 *
 * <p>Rules (API_SPEC.md Section 10.1):
 *
 * <ul>
 *   <li>Inbound sequence equal to expected: processed. Higher: a gap; one ResendRequest from the
 *       expected number to infinity, and messages are dropped until the gap is filled by resent
 *       (PossDup) messages or a SequenceReset-GapFill. Lower with PossDup=Y: ignored as a
 *       duplicate. Lower without PossDup: fatal; Logout and disconnect.
 *   <li>A ResendRequest from the counterparty is always answered with one SequenceReset-GapFill
 *       to our next sequence number: the engine never resends application messages, because a
 *       resent quote or order would be stale (DR-P2V-03).
 *   <li>SequenceReset in reset mode sets the expected number regardless of MsgSeqNum.
 *   <li>Heartbeat when nothing was sent for HeartBtInt; TestRequest when nothing was received for
 *       1.2 HeartBtInt; disconnect when nothing was received for 2 HeartBtInt.
 * </ul>
 */
public final class FixSession {

  private static final byte[] TEST_PREFIX = {'T', 'E', 'S', 'T'};

  private final Clock clock;
  private final FixTransport transport;
  private final FixSessionHandler handler;
  private final byte[] sender;
  private final byte[] target;
  private final long heartbeatNanos;
  private final int heartbeatSeconds;
  private final boolean resetOnLogon;
  private final FixParser parser = new FixParser();
  private final FixWriter writer = new FixWriter(64 * 1024);
  private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(64 * 1024 + 64));
  private final UnsafeBuffer testReqId = new UnsafeBuffer(ByteBuffer.allocateDirect(32));
  private final SessionStats stats = new SessionStats();

  private SessionState state = SessionState.DISCONNECTED;
  private long nextOutSeq = 1;
  private long expectedInSeq = 1;
  private boolean resendPending;
  private long resendUntil;
  private long lastSentNanos;
  private long lastReceivedNanos;
  private long logonSentNanos;
  private boolean testRequestPending;
  private long testRequestCounter;
  private int testReqIdLength;
  private long pendingSeq = -1;

  /** Creates a disconnected session. */
  public FixSession(
      final FixSessionConfig config,
      final Clock clock,
      final FixTransport transport,
      final FixSessionHandler handler) {
    this.clock = clock;
    this.transport = transport;
    this.handler = handler;
    this.sender = config.senderBytes();
    this.target = config.targetBytes();
    this.heartbeatSeconds = config.heartbeatIntervalSeconds();
    this.heartbeatNanos = config.heartbeatIntervalSeconds() * 1_000_000_000L;
    this.resetOnLogon = config.resetOnLogon();
  }

  /** Current state. */
  public SessionState state() {
    return state;
  }

  /** Counters. */
  public SessionStats stats() {
    return stats;
  }

  /** Sequence number the next outbound message will carry. */
  public long nextOutSeq() {
    return nextOutSeq;
  }

  /** Sequence number expected on the next inbound message. */
  public long expectedInSeq() {
    return expectedInSeq;
  }

  /** Sets both sequence numbers, for a session resumed from a persisted store. */
  public void setSequenceNumbers(final long nextOut, final long expectedIn) {
    if (nextOut < 1 || expectedIn < 1) {
      throw new IllegalArgumentException("sequence numbers start at 1");
    }
    this.nextOutSeq = nextOut;
    this.expectedInSeq = expectedIn;
  }

  /** The clock the session stamps messages with. */
  public Clock clock() {
    return clock;
  }

  /** Called when the transport is connected: sends Logon. */
  public void onConnected() {
    if (resetOnLogon) {
      nextOutSeq = 1;
      expectedInSeq = 1;
    }
    resendPending = false;
    testRequestPending = false;
    final long now = clock.nanoTime();
    lastReceivedNanos = now;
    logonSentNanos = now;
    final FixWriter w = beginAdmin(MsgType.LOGON);
    w.put(FixTags.ENCRYPT_METHOD, 0).put(FixTags.HEART_BT_INT, heartbeatSeconds);
    if (resetOnLogon) {
      w.putBool(FixTags.RESET_SEQ_NUM_FLAG, true);
    }
    send();
    state = SessionState.LOGON_SENT;
  }

  /** Called when the transport has closed. */
  public void onDisconnected() {
    if (state != SessionState.DISCONNECTED) {
      state = SessionState.DISCONNECTED;
      handler.onDisconnect(this);
    }
  }

  /** Sends Logout; the session ends when the counterparty answers or the transport closes. */
  public void logout() {
    if (state == SessionState.ACTIVE || state == SessionState.LOGON_SENT) {
      beginAdmin(MsgType.LOGOUT);
      send();
      state = SessionState.LOGOUT_SENT;
    }
  }

  /**
   * Starts an application message: writes MsgType, CompIDs, MsgSeqNum and SendingTime. The
   * caller adds body fields to the returned writer and calls {@link #send()}.
   */
  public FixWriter begin(final int msgType) {
    if (state != SessionState.ACTIVE) {
      throw new IllegalStateException("session not active: " + state);
    }
    return beginAdmin(msgType);
  }

  private FixWriter beginAdmin(final int msgType) {
    return header(msgType, nextOutSeq, false);
  }

  private FixWriter header(final int msgType, final long seq, final boolean possDup) {
    writer
        .begin(msgType)
        .putAscii(FixTags.SENDER_COMP_ID, sender)
        .putAscii(FixTags.TARGET_COMP_ID, target)
        .put(FixTags.MSG_SEQ_NUM, seq);
    if (possDup) {
      writer.putBool(FixTags.POSS_DUP_FLAG, true);
    }
    writer.putTimestamp(FixTags.SENDING_TIME, clock.epochNanos());
    if (possDup) {
      writer.putTimestamp(FixTags.ORIG_SENDING_TIME, clock.epochNanos());
    }
    pendingSeq = seq;
    return writer;
  }

  /** Finishes the message started by {@link #begin} and sends it. */
  public void send() {
    final int length = writer.finish(out, 0);
    if (pendingSeq == nextOutSeq) {
      nextOutSeq++;
    }
    pendingSeq = -1;
    lastSentNanos = clock.nanoTime();
    stats.sent++;
    transport.send(out, 0, length);
  }

  /** Processes one framed inbound message. */
  public void onMessage(final DirectBuffer buffer, final int offset, final int length) {
    if (parser.wrap(buffer, offset, length) != FixParser.OK) {
      stats.garbled++;
      return;
    }
    lastReceivedNanos = clock.nanoTime();
    testRequestPending = false;
    stats.received++;
    try {
      process();
    } catch (FixFormatException e) {
      reject(e.tag());
    }
  }

  private void process() {
    final int type = parser.msgType();
    if (!parser.valueEquals(FixTags.SENDER_COMP_ID, target)
        || !parser.valueEquals(FixTags.TARGET_COMP_ID, sender)) {
      reject(FixTags.SENDER_COMP_ID);
      logout();
      return;
    }
    if (type == MsgType.SEQUENCE_RESET && !parser.isYes(FixTags.GAP_FILL_FLAG)) {
      final long newSeq = parser.getLong(FixTags.NEW_SEQ_NO);
      if (newSeq > expectedInSeq) {
        expectedInSeq = newSeq;
        stats.sequenceResets++;
      }
      clearResendIfFilled();
      return;
    }
    final long seq = parser.getLong(FixTags.MSG_SEQ_NUM);
    if (type == MsgType.LOGON && parser.isYes(FixTags.RESET_SEQ_NUM_FLAG) && seq == 1) {
      expectedInSeq = 1;
    }
    if (seq > expectedInSeq) {
      onGap(type);
      return;
    }
    if (seq < expectedInSeq) {
      if (parser.isYes(FixTags.POSS_DUP_FLAG)) {
        stats.duplicatesIgnored++;
        return;
      }
      stats.sequenceTooLow++;
      logout();
      transport.disconnect();
      return;
    }
    expectedInSeq++;
    dispatch(type);
    clearResendIfFilled();
  }

  private void clearResendIfFilled() {
    if (resendPending && expectedInSeq > resendUntil) {
      resendPending = false;
    }
  }

  private void onGap(final int type) {
    if (type == MsgType.LOGON) {
      acceptLogon();
    } else if (type == MsgType.RESEND_REQUEST) {
      answerResend();
    } else if (type == MsgType.LOGOUT) {
      onLogout();
      return;
    }
    final long seq = parser.getLong(FixTags.MSG_SEQ_NUM);
    if (!resendPending) {
      stats.gapsDetected++;
      stats.resendRequestsSent++;
      resendPending = true;
      beginAdmin(MsgType.RESEND_REQUEST)
          .put(FixTags.BEGIN_SEQ_NO, expectedInSeq)
          .put(FixTags.END_SEQ_NO, 0);
      send();
    }
    if (seq > resendUntil) {
      resendUntil = seq;
    }
  }

  private void dispatch(final int type) {
    switch (type) {
      case MsgType.LOGON -> acceptLogon();
      case MsgType.HEARTBEAT -> {
        // TestRequest answered; the pending flag was cleared on receipt.
      }
      case MsgType.TEST_REQUEST -> {
        final int i = parser.indexOf(FixTags.TEST_REQ_ID);
        final FixWriter w = beginAdmin(MsgType.HEARTBEAT);
        if (i >= 0) {
          w.putAscii(FixTags.TEST_REQ_ID, parser.buffer(), parser.valueOffset(i), parser.valueLength(i));
        }
        stats.heartbeatsSent++;
        send();
      }
      case MsgType.RESEND_REQUEST -> answerResend();
      case MsgType.SEQUENCE_RESET -> {
        final long newSeq = parser.getLong(FixTags.NEW_SEQ_NO);
        if (newSeq > expectedInSeq) {
          expectedInSeq = newSeq;
        }
      }
      case MsgType.REJECT -> {
        stats.rejectsReceived++;
        handler.onSessionReject(this, parser);
      }
      case MsgType.LOGOUT -> onLogout();
      default -> {
        if (state == SessionState.ACTIVE) {
          handler.onApplicationMessage(this, parser, parser.isYes(FixTags.POSS_DUP_FLAG));
        }
      }
    }
  }

  private void acceptLogon() {
    if (state == SessionState.LOGON_SENT) {
      state = SessionState.ACTIVE;
      handler.onLogon(this);
    }
  }

  private void onLogout() {
    if (state != SessionState.LOGOUT_SENT && state != SessionState.DISCONNECTED) {
      beginAdmin(MsgType.LOGOUT);
      send();
    }
    state = SessionState.LOGOUT_SENT;
    transport.disconnect();
  }

  private void answerResend() {
    final long begin = parser.getLong(FixTags.BEGIN_SEQ_NO);
    if (begin >= nextOutSeq) {
      return;
    }
    header(MsgType.SEQUENCE_RESET, begin, true)
        .putBool(FixTags.GAP_FILL_FLAG, true)
        .put(FixTags.NEW_SEQ_NO, nextOutSeq);
    stats.gapFillsSent++;
    send();
  }

  private void reject(final int refTag) {
    final long refSeq = parser.getLong(FixTags.MSG_SEQ_NUM, 0);
    stats.rejectsSent++;
    beginAdmin(MsgType.REJECT)
        .put(FixTags.REF_SEQ_NUM, refSeq)
        .put(FixTags.REF_TAG_ID, refTag)
        .put(FixTags.SESSION_REJECT_REASON, 5);
    send();
  }

  /**
   * Runs the timers; call from the owning loop. Returns true if the session timed out and
   * disconnected on this call.
   */
  public boolean poll() {
    final long now = clock.nanoTime();
    if (state == SessionState.LOGON_SENT && now - logonSentNanos >= heartbeatNanos) {
      stats.timeouts++;
      transport.disconnect();
      return true;
    }
    if (state != SessionState.ACTIVE) {
      return false;
    }
    final long silent = now - lastReceivedNanos;
    if (silent >= 2 * heartbeatNanos) {
      stats.timeouts++;
      transport.disconnect();
      return true;
    }
    if (!testRequestPending && silent >= heartbeatNanos + heartbeatNanos / 5) {
      testRequestPending = true;
      testRequestCounter++;
      testReqId.putBytes(0, TEST_PREFIX);
      testReqIdLength =
          TEST_PREFIX.length
              + io.ratesalgo.fix.codec.AsciiNumbers.writeNatural(
                  testReqId, TEST_PREFIX.length, testRequestCounter);
      beginAdmin(MsgType.TEST_REQUEST).putAscii(FixTags.TEST_REQ_ID, testReqId, 0, testReqIdLength);
      stats.testRequestsSent++;
      send();
    } else if (now - lastSentNanos >= heartbeatNanos) {
      beginAdmin(MsgType.HEARTBEAT);
      stats.heartbeatsSent++;
      send();
    }
    return false;
  }
}
