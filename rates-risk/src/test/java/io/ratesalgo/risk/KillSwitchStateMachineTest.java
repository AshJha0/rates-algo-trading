package io.ratesalgo.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kill switch against the transition table of docs/diagrams/kill_switch_state.mmd, over every
 * sequence of up to five operations from a small alphabet (19,608 sequences). The oracle is
 * the table itself, written out below as data, not the controller's code.
 */
class KillSwitchStateMachineTest {

  private static final int DEPTH = 5;

  /** One operation of the alphabet. */
  private enum Op {
    TRIGGER_MANUAL,
    TRIGGER_FEED_GAP,
    REQUEST_BY_1,
    REQUEST_BY_2,
    APPROVE_BY_1,
    APPROVE_BY_2,
    APPROVE_BY_2_UNHEALTHY
  }

  /** What the table says about one operation in one state. */
  private record Expect(KillState next, boolean throwsIllegalState, Boolean returns, boolean fires) {}

  /** The specified table; {@code requester} is who asked for the pending reset (0 if none). */
  private static Expect table(final KillState s, final Op op, final int requester) {
    return switch (op) {
      case TRIGGER_MANUAL, TRIGGER_FEED_GAP ->
          s == KillState.KILLED
              ? new Expect(KillState.KILLED, false, false, false)
              : new Expect(KillState.KILLED, false, true, true);
      case REQUEST_BY_1, REQUEST_BY_2 ->
          s == KillState.KILLED
              ? new Expect(KillState.RESET_PENDING, false, null, false)
              : new Expect(s, true, null, false);
      case APPROVE_BY_1, APPROVE_BY_2, APPROVE_BY_2_UNHEALTHY -> {
        if (s != KillState.RESET_PENDING) {
          yield new Expect(s, true, null, false);
        }
        final int approver = op == Op.APPROVE_BY_1 ? 1 : 2;
        final boolean ok = approver != requester && op != Op.APPROVE_BY_2_UNHEALTHY;
        yield new Expect(ok ? KillState.ACTIVE : KillState.RESET_PENDING, false, ok, false);
      }
    };
  }

  @Test
  void everySequenceOfOperationsFollowsTheTransitionTable() {
    final Op[] ops = Op.values();
    final int[] digits = new int[DEPTH];
    long sequences = 0;
    for (int len = 0; len <= DEPTH; len++) {
      Arrays.fill(digits, 0);
      do {
        replay(ops, digits, len);
        sequences++;
      } while (increment(digits, len, ops.length));
    }
    assertEquals(19_608L, sequences, "1 + 7 + ... + 7^5 sequences checked");
  }

  private static boolean increment(final int[] digits, final int len, final int base) {
    for (int p = 0; p < len; p++) {
      if (++digits[p] < base) {
        return true;
      }
      digits[p] = 0;
    }
    return false;
  }

  private static void replay(final Op[] ops, final int[] digits, final int len) {
    final ManualClock clock = new ManualClock(1_000L);
    final RecordingOutboundSink control = new RecordingOutboundSink();
    final KillSwitchController kill = new KillSwitchController(control, clock);
    final List<KillReason> heard = new ArrayList<>();
    kill.register((reason, t) -> heard.add(reason));
    KillState state = KillState.ACTIVE;
    int requester = 0;
    long kills = 0;
    long requests = 0;
    KillReason last = KillReason.NULL_VAL;
    for (int step = 0; step < len; step++) {
      final Op op = ops[digits[step]];
      final Expect e = table(state, op, requester);
      clock.advance(10L);
      final String where = "step " + step + " " + op + " from " + state;
      switch (op) {
        case TRIGGER_MANUAL, TRIGGER_FEED_GAP -> {
          final KillReason reason = op == Op.TRIGGER_MANUAL ? KillReason.Manual : KillReason.FeedGap;
          assertEquals(e.returns(), kill.trigger(reason, 3, 9), where);
          requests++;
          if (e.fires()) {
            kills++;
            last = reason;
            assertEquals(reason, heard.get(heard.size() - 1), where);
            final KillSwitchDecoder d = new KillSwitchDecoder();
            d.wrap(
                control.message(control.size() - 1),
                MessageHeaderEncoder.ENCODED_LENGTH,
                KillSwitchDecoder.BLOCK_LENGTH,
                0);
            assertEquals(reason, d.reason(), where);
            assertEquals(3, d.scopeId(), where);
            assertEquals(9, d.requestedBy(), where);
            assertEquals(clock.epochNanos(), d.timestampNanos(), where);
          }
        }
        case REQUEST_BY_1, REQUEST_BY_2 -> {
          final int who = op == Op.REQUEST_BY_1 ? 1 : 2;
          if (e.throwsIllegalState()) {
            assertThrows(IllegalStateException.class, () -> kill.requestReset(who), where);
          } else {
            kill.requestReset(who);
            requester = who;
          }
        }
        default -> {
          final int who = op == Op.APPROVE_BY_1 ? 1 : 2;
          final boolean healthy = op != Op.APPROVE_BY_2_UNHEALTHY;
          if (e.throwsIllegalState()) {
            assertThrows(IllegalStateException.class, () -> kill.approveReset(who, healthy), where);
          } else {
            assertEquals(e.returns(), kill.approveReset(who, healthy), where);
          }
        }
      }
      state = e.next();
      assertEquals(state, kill.state(), where);
      assertEquals(state == KillState.ACTIVE, kill.isActive(), where);
      assertEquals(kills, kill.killCount(), where);
      assertEquals(kills, heard.size(), "listeners hear each effective kill once: " + where);
      assertEquals(kills, control.count(KillSwitchDecoder.TEMPLATE_ID), where);
      assertEquals(last, kill.lastReason(), where);
      assertEquals(requests, kill.triggerRequests(), where);
    }
    assertEquals(0L, kill.listenerFailures());
  }
}
