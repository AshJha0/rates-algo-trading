package io.ratesalgo.risk;

import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The process-wide kill switch (API_SPEC.md Section 13.4, state machine in
 * docs/diagrams/kill_switch_state.mmd).
 *
 * <ul>
 *   <li>ACTIVE to KILLED on {@link #trigger}: every listener withdraws what it has outstanding, in
 *       registration order, then one KillSwitch message is sent to the control sink. Triggering
 *       while KILLED only counts the request.
 *   <li>RESET_PENDING to KILLED on {@link #trigger}: a new kill during a pending reset cancels the
 *       reset and is a full kill, with listeners called and a KillSwitch message sent.
 *   <li>KILLED to RESET_PENDING on {@link #requestReset} by an operator.
 *   <li>RESET_PENDING to ACTIVE on {@link #approveReset} by a different person, and only when the
 *       caller says inputs are healthy. Otherwise the state stays RESET_PENDING.
 * </ul>
 *
 * <p>While not ACTIVE the risk engine refuses every quote and order. Single-writer; allocation-free
 * after construction.
 */
public final class KillSwitchController {

  private static final int MAX_LISTENERS = 16;

  private final KillSwitchListener[] listeners = new KillSwitchListener[MAX_LISTENERS];
  private int listenerCount;
  private final OutboundSink control;
  private final Clock clock;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final KillSwitchEncoder encoder = new KillSwitchEncoder();

  private KillState state = KillState.ACTIVE;
  private KillReason lastReason = KillReason.NULL_VAL;
  private long killCount;
  private long triggerRequests;
  private int resetRequestedBy;

  /** A switch that reports to {@code control} and stamps from {@code clock}. */
  public KillSwitchController(final OutboundSink control, final Clock clock) {
    this.control = control;
    this.clock = clock;
  }

  /** Registers a component to be told when the switch fires. */
  public void register(final KillSwitchListener listener) {
    if (listenerCount == MAX_LISTENERS) {
      throw new IllegalStateException("more than " + MAX_LISTENERS + " kill-switch listeners");
    }
    listeners[listenerCount++] = listener;
  }

  /** Fires the switch. Returns true if this call moved it to KILLED. */
  public boolean trigger(final KillReason reason, final int scopeId, final int requestedBy) {
    triggerRequests++;
    if (state == KillState.KILLED) {
      return false;
    }
    state = KillState.KILLED;
    lastReason = reason;
    killCount++;
    final long now = clock.epochNanos();
    for (int i = 0; i < listenerCount; i++) {
      listeners[i].onKill(reason, now);
    }
    encoder
        .wrapAndApplyHeader(buffer, 0, header)
        .reason(reason)
        .scopeId(scopeId)
        .requestedBy(requestedBy)
        .timestampNanos(now);
    control.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    return true;
  }

  /** First step of resuming: an operator asks. Only valid from KILLED. */
  public void requestReset(final int operatorId) {
    if (state != KillState.KILLED) {
      throw new IllegalStateException("reset requested in state " + state);
    }
    state = KillState.RESET_PENDING;
    resetRequestedBy = operatorId;
  }

  /**
   * Second step: a different person approves. Returns true if the switch is ACTIVE afterwards.
   * Refused (state unchanged) when the approver is the requester or inputs are not healthy.
   */
  public boolean approveReset(final int approverId, final boolean inputsHealthy) {
    if (state != KillState.RESET_PENDING) {
      throw new IllegalStateException("reset approved in state " + state);
    }
    if (approverId == resetRequestedBy || !inputsHealthy) {
      return false;
    }
    state = KillState.ACTIVE;
    return true;
  }

  public KillState state() {
    return state;
  }

  /** True only in ACTIVE. */
  public boolean isActive() {
    return state == KillState.ACTIVE;
  }

  /** Reason of the last transition to KILLED. */
  public KillReason lastReason() {
    return lastReason;
  }

  /** Number of ACTIVE to KILLED transitions. */
  public long killCount() {
    return killCount;
  }

  /** Number of trigger calls, effective or not. */
  public long triggerRequests() {
    return triggerRequests;
  }
}
